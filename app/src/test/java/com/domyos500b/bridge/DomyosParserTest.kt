package com.domyos500b.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun displayFrameMatchesExpected500bLayout() {
        // trame f0 cb layout 500B : 3 s, 27 spm, pace 274 s (4:34 -> 434), 4 kcal, 100 m (0.10 km)
        val f = DomyosParser.buildDisplayFrame(elapsedSec = 3, cadenceSpm = 27, paceSecPer500m = 274, caloriesKcal = 4, distanceMeters = 100)
        assertEquals(27, f.size)
        assertArrayEquals(
            hex("F0 CB 03 00 03 FF 01 00 1B 02 01 00 B2 01 01 04 1B 01 01 00 0A 01 FF FF FF FF BB"),
            f
        )
    }

    @Test
    fun distanceFrameCarriesTenthOfKm() {
        // trame f0 cd : distance en unités de 0,1 km (odometer*10 chez qdomyos) — 10820 m -> 1082 = 0x043A
        val f = DomyosParser.buildDistanceDisplayFrame(10_820)
        assertEquals(27, f.size)
        assertArrayEquals(
            hex("F0 CD 01 04 3A 01 FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF E9"),
            f
        )
    }

    @Test
    fun displayFrameFieldPositions() {
        // mapping 500B : Time=[3]min/[4]sec, Spm=[7,8] u16 BE, pace=[12,13] u16 LE (min*100+sec),
        // Kcal=[15], cadence=[16], Km=[19] partie entière / [20] centièmes
        val f = DomyosParser.buildDisplayFrame(elapsedSec = 125, cadenceSpm = 300, paceSecPer500m = 220, caloriesKcal = 258, distanceMeters = 1234)
        assertEquals(2, f[3].toInt())          // 125 s = 2 min
        assertEquals(5, f[4].toInt())          // 125 % 60 = 5 s
        assertEquals(0x01, f[7].toInt() and 0xFF)  // 300 = 0x012C
        assertEquals(0x2C, f[8].toInt() and 0xFF)
        assertEquals(0x54, f[12].toInt() and 0xFF) // 220 s = 3:40 -> 340 = 0x0154, LE : lo d'abord
        assertEquals(0x01, f[13].toInt() and 0xFF)
        assertEquals(0xFF, f[15].toInt() and 0xFF) // kcal 258 capé à 255
        assertEquals(0xFF, f[16].toInt() and 0xFF) // cadence 300 capée à 255
        assertEquals(0x01, f[19].toInt() and 0xFF) // 1234 m = 1.234 km -> partie entière 1
        assertEquals(0x17, f[20].toInt() and 0xFF) // centièmes 23
    }

    @Test
    fun displayFrameKmEncodingMatchesConsolePhotos() {
        // photos de la console 500B : valeur 12 en [19,20] -> affichée "0.12" ; 2 -> "00.2"
        // => la console affiche "hi.lo" ; on encode donc 1200 m -> "1.20", 220 m -> "0.22"
        val f1 = DomyosParser.buildDisplayFrame(0, 0, 0, 0, 1200)
        assertEquals(1, f1[19].toInt() and 0xFF)
        assertEquals(20, f1[20].toInt() and 0xFF)
        val f2 = DomyosParser.buildDisplayFrame(0, 0, 0, 0, 220)
        assertEquals(0, f2[19].toInt() and 0xFF)
        assertEquals(22, f2[20].toInt() and 0xFF)
    }

    @Test
    fun checksumIsSumOfFirst26Bytes() {
        for (frame in listOf(
            DomyosParser.buildDisplayFrame(3, 27, 274, 4, 100),
            DomyosParser.buildDisplayFrame(125, 300, 220, 1000, 65_535),
            DomyosParser.buildDistanceDisplayFrame(0),
            DomyosParser.buildDistanceDisplayFrame(999_999)
        )) {
            var sum = 0
            for (i in 0 until 26) sum += frame[i].toInt() and 0xFF
            assertEquals("frame ${frame.toHex()}", (sum and 0xFF).toByte(), frame[26])
        }
    }

    @Test
    fun resistanceFrameMatchesQdomyosForceResistance() {
        // niveau 10 : checksum calculé comme le code C de qdomyos (octet 22 accumulé à partir de 0xFF, boucle i=0..22)
        val f = DomyosParser.buildResistanceFrame(10)
        assertEquals(24, f.size)
        assertArrayEquals(
            hex("F0 AD FF FF FF FF FF FF FF FF 0A FF FF FF FF FF FF FF 00 01 FF FF 2C 00"),
            f
        )
    }

    @Test
    fun resistanceFrameClampsLevel() {
        assertEquals(0, DomyosParser.buildResistanceFrame(-3)[10].toInt() and 0xFF)
        assertEquals(255, DomyosParser.buildResistanceFrame(999)[10].toInt() and 0xFF)
    }

    @Test
    fun parseRealPacket_readsResistanceLevel() {
        // octet 14 = 0x01 -> niveau 1 (log 16:17:18)
        val m = DomyosParser.parse(
            hex("F0 BC 00 16 00 00 00 00 00 00 00 04 00 01 01 00 01 00 5E 00 13 00 00 00 01 3B")
        )!!
        assertEquals(1, m.resistanceLevel)
        assertEquals(94, m.heartRateBpm)
    }

    @Test
    fun initSequenceFramesAreWellFormed() {
        // séquence changyow complète (qdomyos-zwift btinit_changyow), 11 trames,
        // octets identiques au code C d'origine (initDataStart6..13 concaténés)
        assertEquals(11, BleConstants.DOMYOS_INIT.size)
        val expectedSizes = listOf(4, 3, 3, 3, 3, 3, 4, 23, 27, 23, 27)
        for ((i, f) in BleConstants.DOMYOS_INIT.withIndex()) {
            assertEquals(0xF0.toByte(), f[0])
            assertEquals("trame $i", expectedSizes[i], f.size)
        }
        // les 4 longues trames ont un checksum valide (dernier octet = somme & 0xFF)
        for (i in listOf(7, 8, 9, 10)) {
            val f = BleConstants.DOMYOS_INIT[i]
            var sum = 0
            for (b in 0 until f.size - 1) sum += f[b].toInt() and 0xFF
            assertEquals("checksum trame $i", (sum and 0xFF).toByte(), f[f.size - 1])
        }
        // octets exacts des deux trames longues recopiées du C (initDataStart10+11, 12+13)
        assertArrayEquals(
            hex("F0 AD FF FF 00 05 FF FF FF FF FF FF FF 00 00 FF FF FF 01 FF FF FF 94"),
            BleConstants.DOMYOS_INIT[9]
        )
        assertArrayEquals(
            hex("F0 CB FF FF FF FF FF FF FF FF FF FF FF FF 01 00 14 01 FF FF FF FF FF FF FF FF BD"),
            BleConstants.DOMYOS_INIT[10]
        )
    }

    @Test
    fun markerFramesHaveDistinctByteValuesAndValidChecksum() {
        val cb = DomyosParser.buildMarkerDisplayFrame()
        val cd = DomyosParser.buildMarkerDistanceFrame()
        assertEquals(27, cb.size)
        assertEquals(27, cd.size)
        assertEquals(0xF0.toByte(), cb[0])
        assertEquals(0xCB.toByte(), cb[1])
        assertEquals(0xF0.toByte(), cd[0])
        assertEquals(0xCD.toByte(), cd[1])
        for (i in 3..25) {
            if (i == 5 || i == 6) continue // structurels dans f0 cb
            assertEquals("cb[$i]", (10 + i).toByte(), cb[i])
            assertEquals("cd[$i]", (10 + i).toByte(), cd[i])
        }
        for (f in listOf(cb, cd)) {
            var sum = 0
            for (i in 0 until 26) sum += f[i].toInt() and 0xFF
            assertEquals((sum and 0xFF).toByte(), f[26])
        }
    }

    @Test
    fun hexBytesRoundTrip() {
        assertArrayEquals(hex("F0 C8 01 B9"), hexBytes("F0 C8 01 B9"))
        assertEquals("F0 C8 01 B9", hexBytes("F0 C8 01 B9").toHex())
    }

    @Test
    fun displayFrameClampsOutOfRangeValues() {
        val f = DomyosParser.buildDisplayFrame(elapsedSec = 100_000, cadenceSpm = 99_999, paceSecPer500m = -5, caloriesKcal = -1, distanceMeters = -1)
        assertEquals(255, f[3].toInt() and 0xFF)   // minutes capées à 255
        assertEquals(40, f[4].toInt() and 0xFF)    // 100 000 % 60 = 40 s
        assertEquals(0xFF, f[7].toInt() and 0xFF)  // cadence u16
        assertEquals(0xFF, f[8].toInt() and 0xFF)
        assertEquals(0, f[12].toInt() and 0xFF)    // pace négative -> 0
        assertEquals(0, f[13].toInt() and 0xFF)
        assertEquals(0, f[15].toInt() and 0xFF)    // kcal négatives -> 0
        assertEquals(0xFF, f[16].toInt() and 0xFF) // cadence capée à 255
        assertEquals(0, f[19].toInt() and 0xFF)    // distance négative -> 0
        assertEquals(0, f[20].toInt() and 0xFF)
    }
}
