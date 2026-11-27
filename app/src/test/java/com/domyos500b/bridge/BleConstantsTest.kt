package com.domyos500b.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cohérence de la séquence d'init « changyow » et des trames de réveil d'écran
 * (renvoyées toutes les 5 s quand la console s'est réveillée après la connexion BLE).
 */
class BleConstantsTest {

    @Test
    fun initHasExpectedLength() {
        assertEquals(11, BleConstants.DOMYOS_INIT.size)
    }

    @Test
    fun displayWakeUsesInitDisplayFrames() {
        // [0] = f0 c8 01 b9 (start tape), [8] = f0 cb 02 … (mode BT),
        // [10] = f0 cb ff … (start tape de l'afficheur)
        val wake = BleConstants.DOMYOS_DISPLAY_WAKE
        assertEquals(3, wake.size)
        assertTrue(wake[0].contentEquals(BleConstants.DOMYOS_INIT[0]))
        assertTrue(wake[1].contentEquals(BleConstants.DOMYOS_INIT[8]))
        assertTrue(wake[2].contentEquals(BleConstants.DOMYOS_INIT[10]))
        assertEquals(0xF0.toByte(), wake[0][0])
        assertEquals(0xC8.toByte(), wake[0][1])
        assertEquals(0xF0.toByte(), wake[1][0])
        assertEquals(0xCB.toByte(), wake[1][1])
        assertEquals(0x02.toByte(), wake[1][2])
        assertEquals(0xF0.toByte(), wake[2][0])
        assertEquals(0xCB.toByte(), wake[2][1])
        assertEquals(0xFF.toByte(), wake[2][2])
    }
}
