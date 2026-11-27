package com.domyos500b.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests du normaliseur FTMS 0x2AD1 (trame émise vers MyWhoosh). */
class FtmsParserTest {

    private fun hex(s: String): ByteArray =
        s.split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun wattsFromPaceMatchesConcept2() {
        assertEquals(89, FtmsParser.wattsFromPace(158))  // log réel : 89 W à pace 158
        assertEquals(17, FtmsParser.wattsFromPace(274))  // log réel : 17 W à pace 274
        assertEquals(44, FtmsParser.wattsFromPace(200))  // 2.80 / 0.4^3 = 43.75
        assertEquals(0, FtmsParser.wattsFromPace(0))
        assertEquals(0, FtmsParser.wattsFromPace(-5))
    }

    @Test
    fun normalizedFrameIs16BytesWithFlags0x003E() {
        // MyWhoosh (Sensor.ReadGenericRower) exige >= 16 octets (31 caractères hex)
        val f = FtmsParser.buildNormalizedFrame(
            RowerMetrics(
                flags = FtmsParser.NORMALIZED_FLAGS,
                strokeRateSpm = 25.0, strokeCount = 37, distanceMeters = 129,
                paceSecPer500m = 175, powerWatts = 65, rawHex = ""
            )
        )
        assertEquals(16, f.size)
        assertEquals(0x3E, f[0].toInt() and 0xFF) // flags little-endian, bits 1-5
        assertEquals(0x00, f[1].toInt() and 0xFF)
    }

    @Test
    fun buildThenParseRoundTrip() {
        val m = RowerMetrics(
            flags = FtmsParser.NORMALIZED_FLAGS,
            strokeRateSpm = 25.5, strokeCount = 37, distanceMeters = 12345,
            paceSecPer500m = 200, powerWatts = 89, heartRateBpm = 82, rawHex = ""
        )
        val p = FtmsParser.parse(FtmsParser.buildNormalizedFrame(m))!!
        assertEquals(25.5, p.strokeRateSpm, 0.001) // byte = spm*2, relu *0.5
        assertEquals(37, p.strokeCount)
        assertEquals(12345, p.distanceMeters)      // u24 little-endian
        assertEquals(200, p.paceSecPer500m)
        assertEquals(89, p.powerWatts)
    }

    @Test
    fun parseAppliesWattsFallbackFromPace() {
        // trame avec power = 0 mais pace valide -> W recalculés
        val m = RowerMetrics(
            flags = FtmsParser.NORMALIZED_FLAGS,
            strokeRateSpm = 20.0, strokeCount = 10, distanceMeters = 50,
            paceSecPer500m = 250, powerWatts = 0, rawHex = ""
        )
        val p = FtmsParser.parse(FtmsParser.buildNormalizedFrame(m))!!
        assertEquals(FtmsParser.wattsFromPace(250), p.powerWatts)
        assertEquals(22, p.powerWatts) // 2.80 / (250/500)^3 = 2.8 / 0.125 = 22.4 -> 22
    }

    @Test
    fun idleFrameParsesToZeros() {
        val p = FtmsParser.parse(FtmsParser.idleFrame())!!
        assertEquals(0.0, p.strokeRateSpm, 0.001)
        assertEquals(0, p.strokeCount)
        assertEquals(0, p.distanceMeters)
        assertEquals(0, p.paceSecPer500m)
        assertEquals(0, p.powerWatts)
    }

    @Test
    fun parseRejectsTruncatedFrame() {
        assertEquals(null, FtmsParser.parse(ByteArray(1)))
    }
}
