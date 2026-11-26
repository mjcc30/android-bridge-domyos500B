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

    /** Nom BLE diffusé vers MyWhoosh. */
    const val DEVICE_NAME = "Domyos500B-Bridge"

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
}
