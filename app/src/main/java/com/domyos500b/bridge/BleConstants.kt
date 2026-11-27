package com.domyos500b.bridge

import java.util.UUID

/**
 * UUIDs et constantes du profil GATT FTMS (Fitness Machine Service, 0x1826).
 */
object BleConstants {

    val FTMS_SERVICE: UUID = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")

    /** 0x2ACC — Fitness Machine Feature (Read). */
    val FITNESS_MACHINE_FEATURE: UUID = UUID.fromString("00002ACC-0000-1000-8000-00805f9b34fb")

    /** 0x2AD1 — Rower Data (Notify côté serveur / Notify côté client rameur). */
    val ROWER_DATA: UUID = UUID.fromString("00002AD1-0000-1000-8000-00805f9b34fb")

    /** 0x2AD3 — Fitness Machine Status (Read/Notify), exposé comme "Training Status" dans la demande. */
    val FITNESS_MACHINE_STATUS: UUID = UUID.fromString("00002AD3-0000-1000-8000-00805f9b34fb")

    /** 0x2ADA — Training Status (Read/Notify), exposé en complément pour compatibilité. */
    val TRAINING_STATUS: UUID = UUID.fromString("00002ADA-0000-1000-8000-00805f9b34fb")

    /** 0x2AD9 — Fitness Machine Control Point (Write + Indicate). */
    val FTMS_CONTROL_POINT: UUID = UUID.fromString("00002AD9-0000-1000-8000-00805f9b34fb")

    /** 0x2902 — Client Characteristic Configuration Descriptor. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** 0x180D — Heart Rate Service : exposé pour relayer la FC lue sur le rameur (ceinture Domyos). */
    val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")

    /** 0x2A37 — Heart Rate Measurement (Notify). */
    val HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")

    /**
     * Nom BLE diffusé vers MyWhoosh. « PM5 » = nom du moniteur Concept2 reconnu
     * nativement par MyWhoosh (même nom que le pont virtuel qdomyos-zwift) :
     * MyWhoosh liste de préférence un rameur FTMS annoncé sous ce nom.
     */
    const val DEVICE_NAME = "PM5"

    /**
     * Valeur de 0x2ACC (4 octets, little-endian).
     * 1er mot 0x0126 = bit1 Average Cadence/Stroke Rate | bit2 Total Distance
     *                  | bit5 Pace | bit8 Instantaneous Power.
     * 2nd mot 0x0008 = bit19 Target Power (control point).
     */
    val FITNESS_MACHINE_FEATURE_VALUE: ByteArray = byteArrayOf(0x26, 0x01, 0x00, 0x08)

    // Opcodes du Control Point FTMS (0x2AD9)
    const val OP_REQUEST_CONTROL: Byte = 0x00
    const val OP_START_OR_RESUME: Byte = 0x07

    // Réponse Control Point : 0x80 (Response Code) + opcode + result (0x01 = Success)
    const val RESPONSE_OPCODE: Byte = 0x80.toByte()
    const val RESPONSE_SUCCESS: Byte = 0x01

    // ---- Protocole propriétaire Domyos (Domyos-ROW-*, ex. rameur 500B) ----
    // Le 500B n'expose PAS toujours le service FTMS 0x1826 en GATT : il parle le
    // protocole propriétaire « changyow » sur le service 49535343-…
    val DOMYOS_SERVICE: UUID = UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455")
    val DOMYOS_WRITE: UUID = UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3")
    val DOMYOS_NOTIFY: UUID = UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616")

    /**
     * Séquence d'init « changyow » COMPLÈTE envoyée à chaque connexion (identique à
     * `btinit_changyow` de qdomyos-zwift, octets copiés tels quels). Au-delà des 3
     * premières trames, elle contient notamment :
     * - `f0 cb 02 00 08 …` : passe la console en mode « affichage piloté par l'app »
     *   (icône/word BT) — sans ça, l'écran du rameur n'affiche pas les trames f0 cb/f0 cd ;
     * - `f0 cb ff …` : « start tape » ;
     * - deux trames `f0 ad …` d'init de la résistance.
     * Chaque trame est écrite en paquets de 20 octets max (voir writeDomyosFrame).
     */
    val DOMYOS_INIT: List<ByteArray> = listOf(
        hexBytes("F0 C8 01 B9"),
        hexBytes("F0 C9 B9"),
        hexBytes("F0 A3 93"),
        hexBytes("F0 A4 94"),
        hexBytes("F0 A5 95"),
        hexBytes("F0 AB 9B"),
        hexBytes("F0 C4 03 B7"),
        hexBytes("F0 AD FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF 01 FF FF FF 8B"), // 23 o
        hexBytes("F0 CB 02 00 08 FF FF FF FF FF FF FF FF FF FF FF FF FF 01 00 00 01 FF FF FF FF B6"), // 27 o, mode BT
        hexBytes("F0 AD FF FF 00 05 FF FF FF FF FF FF FF 00 00 FF FF FF 01 FF FF FF 94"), // 23 o
        hexBytes("F0 CB FF FF FF FF FF FF FF FF FF FF FF FF 01 00 14 01 FF FF FF FF FF FF FF FF BD") // 27 o, start tape
    )

    /**
     * Trames d'init d'affichage renvoyées périodiquement (toutes les 5 s) et à la
     * demande (bouton « Réveiller écran ») : [0] = `f0 c8 01 b9` « start tape »,
     * [8] = `f0 cb 02 …` mode BT « affichage piloté par l'app », [10] = `f0 cb ff …`
     * « start tape » de l'afficheur. Si la console s'est réveillée APRÈS la connexion
     * BLE (le module BLE acceptait déjà la connexion pendant que le MCU dormait),
     * ces trames ont été perdues -> les données circulent mais l'écran du 500B
     * reste noir (le tactile répond, l'afficheur non).
     */
    val DOMYOS_DISPLAY_WAKE: List<ByteArray> = listOf(DOMYOS_INIT[0], DOMYOS_INIT[8], DOMYOS_INIT[10])

    /** Keepalive envoyé chaque seconde tant que la session propriétaire est active. */
    val DOMYOS_KEEPALIVE: ByteArray = byteArrayOf(0xF0.toByte(), 0xAC.toByte(), 0x9C.toByte())

    /** Opcode Control Point FTMS 0x2AD9 : Set Target Resistance (relayé vers le rameur). */
    const val OP_SET_TARGET_RESISTANCE: Byte = 0x04
}
