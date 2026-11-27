package com.domyos500b.bridge

import kotlin.math.pow
import kotlin.math.roundToInt

/** Métriques extraites d'une trame Rower Data (0x2AD1). */
data class RowerMetrics(
    val flags: Int,
    val strokeRateSpm: Double,   // coups/min (résolution 0.5)
    val strokeCount: Int,
    val distanceMeters: Int,
    val paceSecPer500m: Int,     // secondes par 500 m (0 = absent)
    val powerWatts: Int,         // Watts (déjà normalisée : fallback via pace si le rameur envoie 0)
    val heartRateBpm: Int = 0,   // FC (0 = absente) — relayée via le service 0x180D, pas via 2AD1
    val caloriesKcal: Int = 0,   // kcal (octets 10-11 des paquets Domyos) — affichage écran du rameur
    val rawHex: String
)

/**
 * Parseur / normaliseur binaire de la caractéristique Rower Data (0x2AD1),
 * conforme au profil FTMS — tout en little-endian.
 */
object FtmsParser {

    // Bits du champ flags (uint16, octets 0-1)
    const val FLAG_MORE_DATA = 0x0001
    const val FLAG_AVG_STROKE_RATE = 0x0002
    const val FLAG_STROKE_COUNT = 0x0004
    const val FLAG_TOTAL_DISTANCE = 0x0008
    const val FLAG_INSTANTANEOUS_PACE = 0x0010
    const val FLAG_INSTANTANEOUS_POWER = 0x0020
    const val FLAG_EXPENDED_ENERGY = 0x0040
    const val FLAG_HEART_RATE = 0x0080
    const val FLAG_METABOLIC_EQUIVALENT = 0x0100
    const val FLAG_ELAPSED_TIME = 0x0200
    const val FLAG_REMAINING_TIME = 0x0400

    /**
     * Flags de la trame normalisée émise vers MyWhoosh.
     *
     * La demande mentionnait 0x002C, mais 0x002C = bits 2|3|5 (Stroke Count +
     * Total Distance + Instantaneous Power uniquement) et ne couvre pas les 5
     * champs demandés. Les 5 champs demandés (Stroke Rate + Stroke Count +
     * Total Distance + Instantaneous Pace + Instantaneous Power) correspondent
     * exactement aux bits 1 à 5, soit **0x003E** — c'est cette valeur, conforme
     * à la spec FTMS, qui est émise (trame de 16 octets).
     *
     * NOTE (MyWhoosh 6.3.0, Sensor.ReadGenericRower) : le client exige une trame
     * d'au moins 31 caractères hexadécimaux, soit **16 octets minimum** — une
     * trame de 12 octets est ignorée silencieusement (affichage à 0 W).
     */
    const val NORMALIZED_FLAGS = 0x003E

    /**
     * Décode dynamiquement une trame 0x2AD1 selon le bitmask des flags.
     * Si la puissance instantanée est absente ou nulle mais que le pace est
     * valide, les Watts sont calculés avec la formule Concept2/FTMS :
     * `watts = 2.80 / (pace / 500)^3`.
     */
    fun parse(value: ByteArray): RowerMetrics? {
        if (value.size < 2) return null
        val flags = (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)

        var o = 2
        var strokeRate = 0.0
        var strokeCount = 0
        var distance = 0
        var pace = 0
        var power = 0

        fun u8(): Int { val v = value[o].toInt() and 0xFF; o += 1; return v }
        fun u16(): Int {
            val v = (value[o].toInt() and 0xFF) or ((value[o + 1].toInt() and 0xFF) shl 8)
            o += 2; return v
        }
        fun u24(): Int {
            val v = (value[o].toInt() and 0xFF) or
                    ((value[o + 1].toInt() and 0xFF) shl 8) or
                    ((value[o + 2].toInt() and 0xFF) shl 16)
            o += 3; return v
        }
        fun s16(): Int {
            val v = (value[o].toInt() and 0xFF) or ((value[o + 1].toInt() and 0xFF) shl 8)
            o += 2; return v.toShort().toInt()
        }

        if (flags and FLAG_AVG_STROKE_RATE != 0 && o + 1 <= value.size) strokeRate = u8() * 0.5
        if (flags and FLAG_STROKE_COUNT != 0 && o + 2 <= value.size) strokeCount = u16()
        if (flags and FLAG_TOTAL_DISTANCE != 0 && o + 3 <= value.size) distance = u24()
        if (flags and FLAG_INSTANTANEOUS_PACE != 0 && o + 2 <= value.size) pace = u16()
        if (flags and FLAG_INSTANTANEOUS_POWER != 0 && o + 2 <= value.size) power = s16()
        // Les champs suivants (energy, HR, MET, elapsed, remaining) ne sont pas
        // exploités : pas besoin de faire avancer l'offset.

        val effectivePower = when {
            power > 0 -> power
            pace > 0 -> wattsFromPace(pace)
            else -> 0
        }

        return RowerMetrics(
            flags = flags,
            strokeRateSpm = strokeRate,
            strokeCount = strokeCount,
            distanceMeters = distance,
            paceSecPer500m = pace,
            powerWatts = effectivePower,
            rawHex = value.toHex()
        )
    }

    /** Formule standard Concept2/FTMS : pace en secondes/500 m -> Watts. */
    fun wattsFromPace(paceSecPer500m: Int): Int {
        if (paceSecPer500m <= 0) return 0
        return (2.80 / (paceSecPer500m / 500.0).pow(3.0)).roundToInt()
    }

    /**
     * Reconstruit une trame 0x2AD1 normalisée (16 octets, flags [NORMALIZED_FLAGS])
     * directement consommable par MyWhoosh :
     * flags(2) | strokeRate(1, x2) | strokeCount(2) | distance(3) | pace(2) | power(2, sint16) | 4 octets de padding.
     *
     * Les 4 derniers octets sont lus mais ignorés par le parseur de MyWhoosh
     * (bits 6 et 10 à 0) ; ils portent la trame à 16 octets, seuil minimal exigé
     * par Sensor.ReadGenericRower (str.length() >= 31 caractères hex).
     */
    fun buildNormalizedFrame(m: RowerMetrics): ByteArray {
        val out = ByteArray(16)
        out[0] = (NORMALIZED_FLAGS and 0xFF).toByte()
        out[1] = ((NORMALIZED_FLAGS shr 8) and 0xFF).toByte()
        out[2] = (m.strokeRateSpm * 2).roundToInt().coerceIn(0, 255).toByte()
        out[3] = (m.strokeCount and 0xFF).toByte()
        out[4] = ((m.strokeCount shr 8) and 0xFF).toByte()
        out[5] = (m.distanceMeters and 0xFF).toByte()
        out[6] = ((m.distanceMeters shr 8) and 0xFF).toByte()
        out[7] = ((m.distanceMeters shr 16) and 0xFF).toByte()
        out[8] = (m.paceSecPer500m.coerceIn(0, 0xFFFF) and 0xFF).toByte()
        out[9] = ((m.paceSecPer500m.coerceIn(0, 0xFFFF) shr 8) and 0xFF).toByte()
        val p = m.powerWatts.coerceIn(-32768, 32767)
        out[10] = (p and 0xFF).toByte()
        out[11] = ((p shr 8) and 0xFF).toByte()
        return out
    }

    /** Trame d'initialisation (tout à zéro) envoyée tant qu'aucune donnée rameur n'est reçue. */
    fun idleFrame(): ByteArray = buildNormalizedFrame(
        RowerMetrics(
            flags = NORMALIZED_FLAGS,
            strokeRateSpm = 0.0,
            strokeCount = 0,
            distanceMeters = 0,
            paceSecPer500m = 0,
            powerWatts = 0,
            rawHex = ""
        )
    )
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
