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
        // kcal = u16be(10), HR = packet[18] : non exploités côté FTMS.
        val distance = u16be(12)
        val power = FtmsParser.wattsFromPace(pace)

        return RowerMetrics(
            flags = FtmsParser.NORMALIZED_FLAGS,
            strokeRateSpm = cadence.toDouble(),
            strokeCount = strokeCount,
            distanceMeters = distance,
            paceSecPer500m = pace,
            powerWatts = power,
            rawHex = packet.toHex()
        )
    }
}
