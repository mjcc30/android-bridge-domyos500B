package com.domyos500b.bridge

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * File d'attente séquentielle pour toutes les opérations GATT côté client.
 *
 * Android rejette silencieusement les opérations BLE lancées en parallèle
 * (écriture CCCD + écriture Control Point simultanées, etc.), d'où la
 * sérialisation stricte via un [Mutex] + timeout : chaque opération bloquante
 * (découverte, writeDescriptor, writeCharacteristic, requestMtu) est enveloppée
 * dans une coroutine suspendue reprise par le callback GATT correspondant.
 */
class GattCommandQueue(
    private val timeoutMs: Long = 7_000L
) {
    private val mutex = Mutex()

    /**
     * Exécute [block] en exclusivité mutuelle avec les autres opérations de la file.
     * Lève [TimeoutCancellationException] si l'opération dépasse [timeoutMs].
     */
    suspend fun <T> enqueue(block: suspend () -> T): T =
        mutex.withLock {
            withTimeout(timeoutMs) { block() }
        }
}
