package com.domyos500b.bridge

import kotlin.math.roundToInt

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
        val distance = u16be(12)
        val resistance = packet[14].toInt() and 0xFF
        val heartRate = packet[18].toInt() and 0xFF
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
            resistanceLevel = resistance,
            rawHex = packet.toHex()
        )
    }

    // ------------------------------------------------------------------
    // Écriture vers l'écran du rameur (protocole « changyow », cf. qdomyos-zwift
    // domyosrower::updateDisplay) : quand un central BLE est connecté, la console
    // attend que l'app lui renvoie les valeurs à afficher (comme Kinomap).
    // Deux trames de 27 octets, checksum = somme des 26 premiers octets & 0xFF.
    //
    // **Mapping empirique du firmware du Domyos 500B** (photos de la console en
    // mode BT, cf. docs/protocole-domyos.md §3) — il diffère du changyow de
    // qdomyos (vitesse en 7-8, FC en 12, kcal en 19-20) :
    // - `f0 cb` octet 3 = minutes, octet 4 = secondes -> champ **Time** (affiché
    //   en %04d de min*60+sec : 11 min 44 s -> "0704") ;
    // - octets 7-8 (u16 BE) -> champ **Spm** (3 chiffres, mod 1000 : 4370 -> "370") ;
    // - octets 12-13 (u16 **LE**) -> champ **Time/500M** affiché "M:SS" avec
    //   min = v/100, sec = v%100 (81 -> "0:81", 100 -> "1:00") : on y encode
    //   le pace en min*100+sec (ex. 3:40 -> 340) ;
    // - octet 15 -> champ **Kcal** ;
    // - octets 19-20 -> champ **Km** affiché "hi.lo" (12 -> "0.12", 2 -> "00.2") :
    //   on y encode partie entière en 19 et centièmes de km en 20 ;
    // - octet 16 = cadence (champ non visible sur le 500B en mode BT, gardé tel quel) ;
    // - champ **Count** = compteur de coups propre à la console (pas piloté par la trame).
    // - `f0 cd` octets 3-4 = distance u16 BE en unités de 0,1 km (odometer*10 chez
    //   qdomyos) — sur le 500B le champ Km semble lu dans `f0 cb` 19-20, la trame
    //   `f0 cd` est envoyée quand même par compatibilité.
    // Chaque trame est écrite en 2 paquets (20 + 7 octets) comme qdomyos-zwift,
    // pour rester sous le MTU 23 si le module ne négocie pas plus.
    // ------------------------------------------------------------------

    /**
     * Trame `f0 cb` pour l'écran du Domyos 500B : temps, cadence (Spm), pace
     * (Time/500M), kcal et distance (Km) — voir le mapping ci-dessus.
     */
    fun buildDisplayFrame(
        elapsedSec: Int,
        cadenceSpm: Int,
        paceSecPer500m: Int,
        caloriesKcal: Int,
        distanceMeters: Int
    ): ByteArray {
        val f = byteArrayOf(
            0xF0.toByte(), 0xCB.toByte(), 0x03, 0, 0, 0xFF.toByte(), 0x01, 0, 0,
            0x02, 0x01, 0, 0, 0, 0x01, 0, 0, 0x01, 0x01, 0, 0, 0x01,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0
        )
        f[3] = (elapsedSec / 60).coerceIn(0, 255).toByte()   // Time : minutes
        f[4] = (elapsedSec % 60).toByte()                     // Time : secondes
        val spm = cadenceSpm.coerceIn(0, 0xFFFF)
        f[7] = (spm shr 8).toByte()                           // Spm (u16 BE, 3 chiffres)
        f[8] = spm.toByte()
        val paceVal = if (paceSecPer500m > 0) (paceSecPer500m / 60) * 100 + (paceSecPer500m % 60) else 0
        val pv = paceVal.coerceIn(0, 0xFFFF)
        f[12] = (pv and 0xFF).toByte()                        // Time/500M (u16 LE, min*100+sec)
        f[13] = (pv shr 8).toByte()
        f[15] = caloriesKcal.coerceIn(0, 255).toByte()        // Kcal
        f[16] = cadenceSpm.coerceIn(0, 255).toByte()          // cadence (champ non visible)
        val km = distanceMeters.coerceIn(0, Int.MAX_VALUE) / 1000.0
        val kmInt = km.toInt().coerceIn(0, 255)
        val kmFrac = ((km - kmInt) * 100).roundToInt().coerceIn(0, 99)
        f[19] = kmInt.toByte()                                // Km : partie entière
        f[20] = kmFrac.toByte()                               // Km : centièmes
        f[26] = checksum(f)
        return f
    }

    /**
     * Trame `f0 cd` : distance affichée sur l'écran du rameur, en unités de 0,1 km
     * (u16 BE en octets 3-4), comme `odometer() * 10` chez qdomyos-zwift.
     */
    fun buildDistanceDisplayFrame(distanceMeters: Int): ByteArray {
        val f = ByteArray(27) { 0xFF.toByte() }
        f[0] = 0xF0.toByte()
        f[1] = 0xCD.toByte()
        f[2] = 0x01
        f[5] = 0x01
        val d = (distanceMeters.coerceIn(0, Int.MAX_VALUE) / 10).coerceIn(0, 0xFFFF)
        f[3] = (d shr 8).toByte()
        f[4] = d.toByte()
        f[26] = checksum(f)
        return f
    }

    // ------------------------------------------------------------------
    // Diagnostic : trames « marqueurs ». Chaque octet de données reçoit une
    // valeur distincte (10 + index) pour identifier empiriquement — via une
    // photo de la console du 500B — quel octet pilote quel champ de l'écran.
    // (Mapping 500B confirmé par photos : Spm <- 7-8, Time/500M <- 12-13,
    // Kcal <- 15, Km <- 19-20, Time <- 3-4.)
    // ------------------------------------------------------------------

    /** Trame `f0 cb` marqueur : octet i = 10+i pour i in 3..25 (sauf 5,6 structurels). */
    fun buildMarkerDisplayFrame(): ByteArray {
        val f = buildDisplayFrame(0, 0, 0, 0, 0)
        for (i in 3..25) {
            if (i == 5 || i == 6) continue
            f[i] = (10 + i).toByte()
        }
        f[26] = checksum(f)
        return f
    }

    /** Trame `f0 cd` marqueur : octet i = 10+i pour i in 3..25. */
    fun buildMarkerDistanceFrame(): ByteArray {
        val f = buildDistanceDisplayFrame(0)
        for (i in 3..25) f[i] = (10 + i).toByte()
        f[26] = checksum(f)
        return f
    }

    /** Dernier octet = somme des octets précédents & 0xFF. */
    private fun checksum(frame: ByteArray): Byte {
        var sum = 0
        for (i in 0 until frame.size - 1) sum += frame[i].toInt() and 0xFF
        return (sum and 0xFF).toByte()
    }

    /**
     * Trame de réglage de la résistance `f0 ad …` (24 octets), identique à
     * `domyosrower::forceResistance` de qdomyos-zwift : octet 10 = niveau (1-15).
     * Le checksum est calculé exactement comme le code C d'origine (octet 22
     * initialisé à 0xFF et accumulé dans la boucle, qui inclut i=22).
     */
    fun buildResistanceFrame(level: Int): ByteArray {
        val f = ByteArray(24) { 0xFF.toByte() }
        f[0] = 0xF0.toByte()
        f[1] = 0xAD.toByte()
        f[18] = 0x00
        f[19] = 0x01
        f[23] = 0x00
        f[10] = level.coerceIn(0, 255).toByte()
        for (i in 0 until 23) f[22] = (f[22].toInt() + f[i].toInt()).toByte() // comme le C (i=22 inclus)
        return f
    }
}
