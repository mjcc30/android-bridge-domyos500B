package com.domyos500b.bridge

/**
 * Parseur du protocole propriétaire des rameurs Domyos (Domyos-ROW-*, ex. 500B).
 *
 * Service `49535343-fe7d-4ae5-8fa9-9fafd205e455` :
 * - écriture des commandes sur `…-8841-43f4-a8d4-ecbe34729bb3`
 *   (init « changyow » `f0 c8 01 b9`, `f0 c9 b9`, `f0 a3 93`, keepalive `f0 ac 9c` / s) ;
 * - notifications sur `…-1e4d-4bd9-ba61-23c647249616` : paquets de 26 octets
 *   `f0 bc …` (champs multi-octets en **big-endian**, ex. `f0 bc 00 05 6a 6f 00 ea 00 1d …`
 *   = 5 coups, pace 234 s/500m, cadence 29 spm) :
 *     octets 2-3   : nombre de coups (u16 BE)
 *     octets 6-7   : pace en s/500m (u16 BE)
 *     octet  9     : cadence (coups/min)
 *     octets 10-11 : kcal (u16 BE)
 *     octets 12-13 : distance en mètres (u16 BE)
 *     octet  18    : fréquence cardiaque
 *
 * Le rameur n'envoie pas de puissance : elle est recalculée depuis le pace avec
 * la formule Concept2/FTMS `W = 2.80 / (pace/500)^3` (voir [FtmsParser.wattsFromPace]).
 */
object DomyosParser {

    fun parse(packet: ByteArray): RowerMetrics? {
        if (packet.size < 19) return null
        if ((packet[0].toInt() and 0xFF) != 0xF0 || (packet[1].toInt() and 0xFF) != 0xBC) return null

        fun u16be(o: Int): Int =
            ((packet[o].toInt() and 0xFF) shl 8) or (packet[o + 1].toInt() and 0xFF)

        val strokeCount = u16be(2)
        val pace = u16be(6)
        val cadence = packet[9].toInt() and 0xFF
        val calories = u16be(10)
        val heartRate = packet[18].toInt() and 0xFF
        val distance = u16be(12)
        val power = FtmsParser.wattsFromPace(pace)

        return RowerMetrics(
            flags = FtmsParser.NORMALIZED_FLAGS,
            strokeRateSpm = cadence.toDouble(),
            strokeCount = strokeCount,
            distanceMeters = distance,
            paceSecPer500m = pace,
            powerWatts = power,
            heartRateBpm = heartRate,
            caloriesKcal = calories,
            rawHex = packet.toHex()
        )
    }

    // ------------------------------------------------------------------
    // Écriture vers l'écran du rameur (protocole « changyow », cf. qdomyos-zwift
    // domyosrower::updateDisplay) : quand un central BLE est connecté, la console
    // attend que l'app lui renvoie les valeurs à afficher (comme Kinomap).
    // Deux trames de 27 octets, checksum = somme des 26 premiers octets & 0xFF :
    // - `f0 cd` : distance (octets 3-4, u16 BE, unité 0,1 km = valeur brute 12-13
    //   du paquet reçu) ;
    // - `f0 cb` : octets 3-4 = temps écoulé min/sec, 7-8 = vitesse km/h (u16 BE),
    //   12 = FC, 16 = cadence spm, 19-20 = kcal (u16 BE).
    // Chaque trame est écrite en 2 paquets (20 + 7 octets) comme qdomyos-zwift,
    // pour rester sous le MTU 23 si le module ne négocie pas plus.
    // ------------------------------------------------------------------

    /** Trame `f0 cb` : temps, vitesse, FC, cadence, calories pour l'écran du rameur. */
    fun buildDisplayFrame(
        elapsedSec: Int,
        speedKmh: Int,
        hrBpm: Int,
        cadenceSpm: Int,
        caloriesKcal: Int
    ): ByteArray {
        val f = byteArrayOf(
            0xF0.toByte(), 0xCB.toByte(), 0x03, 0, 0, 0xFF.toByte(), 0x01, 0, 0,
            0x02, 0x01, 0, 0, 0, 0x01, 0, 0, 0x01, 0x01, 0, 0, 0x01,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0
        )
        f[3] = (elapsedSec / 60).coerceIn(0, 255).toByte()
        f[4] = (elapsedSec % 60).toByte()
        val spd = speedKmh.coerceIn(0, 0xFFFF)
        f[7] = (spd shr 8).toByte()
        f[8] = spd.toByte()
        f[12] = hrBpm.coerceIn(0, 255).toByte()
        f[16] = cadenceSpm.coerceIn(0, 255).toByte()
        val cal = caloriesKcal.coerceIn(0, 0xFFFF)
        f[19] = (cal shr 8).toByte()
        f[20] = cal.toByte()
        f[26] = checksum(f)
        return f
    }

    /** Trame `f0 cd` : distance affichée sur l'écran du rameur (unité 0,1 km). */
    fun buildDistanceDisplayFrame(distanceUnits: Int): ByteArray {
        val f = ByteArray(27) { 0xFF.toByte() }
        f[0] = 0xF0.toByte()
        f[1] = 0xCD.toByte()
        f[2] = 0x01
        f[5] = 0x01
        val d = distanceUnits.coerceIn(0, 0xFFFF)
        f[3] = (d shr 8).toByte()
        f[4] = d.toByte()
        f[26] = checksum(f)
        return f
    }

    /** Dernier octet = somme des octets précédents & 0xFF. */
    private fun checksum(frame: ByteArray): Byte {
        var sum = 0
        for (i in 0 until frame.size - 1) sum += frame[i].toInt() and 0xFF
        return (sum and 0xFF).toByte()
    }
}
