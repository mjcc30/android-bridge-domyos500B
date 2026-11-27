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

    /** Séquence d'init « changyow » envoyée à chaque connexion. */
    val DOMYOS_INIT: List<ByteArray> = listOf(
        byteArrayOf(0xF0.toByte(), 0xC8.toByte(), 0x01, 0xB9.toByte()),
        byteArrayOf(0xF0.toByte(), 0xC9.toByte(), 0xB9.toByte()),
        byteArrayOf(0xF0.toByte(), 0xA3.toByte(), 0x93.toByte())
    )

    /** Keepalive envoyé chaque seconde tant que la session propriétaire est active. */
    val DOMYOS_KEEPALIVE: ByteArray = byteArrayOf(0xF0.toByte(), 0xAC.toByte(), 0x9C.toByte())
}
