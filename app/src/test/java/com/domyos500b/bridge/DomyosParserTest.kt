package com.domyos500b.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests du protocole propriétaire Domyos 500B, à partir de **vraies trames capturées**
 * sur le rameur (voir docs/protocole-domyos.md et bridge-logs).
 */
class DomyosParserTest {

    private fun hex(s: String): ByteArray =
        s.split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun parseRealPacket_rowing() {
        // 26 coups, pace 274 s/500m, 23 spm, 4 kcal, dist 1, 84 bpm, 17 W (log 16:17:48)
        val m = DomyosParser.parse(
            hex("F0 BC 00 1A 41 D6 01 12 00 17 00 04 00 01 01 00 01 00 54 00 13 00 00 00 01 76")
        )!!
        assertEquals(26, m.strokeCount)
        assertEquals(274, m.paceSecPer500m)
        assertEquals(23.0, m.strokeRateSpm, 0.001)
        assertEquals(4, m.caloriesKcal)
        assertEquals(1, m.distanceMeters)
        assertEquals(84, m.heartRateBpm)
        assertEquals(17, m.powerWatts) // 2.80 / (274/500)^3 = 17.0
    }

    @Test
    fun parseRealPacket_fast() {
        // 37 coups, pace 158 s/500m, 52 spm, 7 kcal, dist 2, 82 bpm, 89 W (log 16:18:56)
        val m = DomyosParser.parse(
            hex("F0 BC 00 25 56 9B 00 9E 00 34 00 07 00 02 01 00 01 00 52 00 14 00 00 00 01 05")
        )!!
        assertEquals(37, m.strokeCount)
        assertEquals(158, m.paceSecPer500m)
        assertEquals(52.0, m.strokeRateSpm, 0.001)
        assertEquals(7, m.caloriesKcal)
        assertEquals(2, m.distanceMeters)
        assertEquals(82, m.heartRateBpm)
        assertEquals(89, m.powerWatts) // 2.80 / (158/500)^3 = 88.7 -> 89
    }

    @Test
    fun parseRealPacket_idle() {
        // à l'arrêt : pace 0, 0 spm, FC 0 (log 16:18:10)
        val m = DomyosParser.parse(
            hex("F0 BC 00 21 00 00 00 00 00 00 00 06 00 01 01 00 01 00 00 00 13 00 00 00 01 EA")
        )!!
        assertEquals(33, m.strokeCount)
        assertEquals(0, m.paceSecPer500m)
        assertEquals(0.0, m.strokeRateSpm, 0.001)
        assertEquals(0, m.heartRateBpm)
        assertEquals(0, m.powerWatts)
    }

    @Test
    fun parseRejectsBadPackets() {
        assertNull(DomyosParser.parse(ByteArray(5)))                       // trop court
        assertNull(DomyosParser.parse(hex("F0 AC 9C")))                    // pas un paquet de données
        assertNull(DomyosParser.parse(hex("F1 BC 00 16 00 00 00 00 00 00 00 04 00 01 01 00 01 00 5E 00 13 00 00 00 01 3B"))) // mauvais header
    }

    @Test
    fun displayFrameMatchesCapturedFrame() {
        // trame f0 cb réellement émise par le pont (log émulateur) : 3 s, 9 km/h, FC 0, 27 spm, 4 kcal
        val f = DomyosParser.buildDisplayFrame(elapsedSec = 3, speedKmh = 9, hrBpm = 0, cadenceSpm = 27, caloriesKcal = 4)
        assertEquals(27, f.size)
        assertArrayEquals(
            hex("F0 CB 03 00 03 FF 01 00 09 02 01 00 00 00 01 00 1B 01 01 00 04 01 FF FF FF FF EC"),
            f
        )
    }

    @Test
    fun distanceFrameMatchesCapturedFrame() {
        // trame f0 cd réellement émise par le pont (log émulateur) : distance brute 0x0433
        val f = DomyosParser.buildDistanceDisplayFrame(0x0433)
        assertEquals(27, f.size)
        assertArrayEquals(
            hex("F0 CD 01 04 33 01 FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF E2"),
            f
        )
    }

    @Test
    fun displayFrameFieldPositions() {
        val f = DomyosParser.buildDisplayFrame(elapsedSec = 125, speedKmh = 0x1234, hrBpm = 81, cadenceSpm = 26, caloriesKcal = 0x0102)
        assertEquals(2, f[3].toInt())          // 125 s = 2 min
        assertEquals(5, f[4].toInt())          // 125 % 60 = 5 s
        assertEquals(0x12, f[7].toInt() and 0xFF)
        assertEquals(0x34, f[8].toInt() and 0xFF)
        assertEquals(81, f[12].toInt() and 0xFF)
        assertEquals(26, f[16].toInt() and 0xFF)
        assertEquals(0x01, f[19].toInt() and 0xFF)
        assertEquals(0x02, f[20].toInt() and 0xFF)
    }

    @Test
    fun checksumIsSumOfFirst26Bytes() {
        for (frame in listOf(
            DomyosParser.buildDisplayFrame(3, 9, 0, 27, 4),
            DomyosParser.buildDisplayFrame(125, 300, 81, 26, 1000),
            DomyosParser.buildDistanceDisplayFrame(0),
            DomyosParser.buildDistanceDisplayFrame(0xFFFF)
        )) {
            var sum = 0
            for (i in 0 until 26) sum += frame[i].toInt() and 0xFF
            assertEquals("frame ${frame.toHex()}", (sum and 0xFF).toByte(), frame[26])
        }
    }

    @Test
    fun displayFrameClampsOutOfRangeValues() {
        val f = DomyosParser.buildDisplayFrame(elapsedSec = 100_000, speedKmh = 99_999, hrBpm = 999, cadenceSpm = -5, caloriesKcal = -1)
        assertEquals(255, f[3].toInt() and 0xFF)   // minutes capées à 255
        assertEquals(0xFF, f[7].toInt() and 0xFF)  // vitesse u16
        assertEquals(0xFF, f[8].toInt() and 0xFF)
        assertEquals(255, f[12].toInt() and 0xFF)  // FC
        assertEquals(0, f[16].toInt() and 0xFF)    // cadence négative -> 0
        assertEquals(0, f[19].toInt() and 0xFF)    // kcal négatives -> 0
        assertEquals(0, f[20].toInt() and 0xFF)
    }
}
