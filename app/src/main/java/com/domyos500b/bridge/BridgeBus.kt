package com.domyos500b.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** État exposé à l'UI (MainActivity) par le BleBridgeService. */
data class BridgeUiState(
    val serviceRunning: Boolean = false,
    val advertising: Boolean = false,
    val domyosConnected: Boolean = false,
    val mywhooshConnected: Boolean = false,
    val resistanceLevel: Int = 0, // niveau de résistance actuel du rameur (1-15)
    val logs: List<String> = emptyList()
)

/** Dernières métriques reçues du rameur (pour affichage temps réel). */
data class MetricsUi(
    val watts: Int = 0,
    val paceSecPer500m: Int = 0,
    val strokeRateSpm: Double = 0.0,
    val strokeCount: Int = 0,
    val distanceMeters: Int = 0,
    val heartRateBpm: Int = 0,
    val rawHex: String = ""
)

/**
 * Bus singleton entre [BleBridgeService] (producteur) et [MainActivity] (consommateur).
 * Les StateFlow sont lus via collectAsStateWithLifecycle() côté Compose.
 */
object BridgeBus {

    val uiState = MutableStateFlow(BridgeUiState())
    val metrics = MutableStateFlow(MetricsUi())

    private const val MAX_LOGS = 300
    private val tsFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun setServiceRunning(v: Boolean) = uiState.update { it.copy(serviceRunning = v) }
    fun setAdvertising(v: Boolean) = uiState.update { it.copy(advertising = v) }
    fun setDomyosConnected(v: Boolean) = uiState.update { it.copy(domyosConnected = v) }
    fun setMyWhooshConnected(v: Boolean) = uiState.update { it.copy(mywhooshConnected = v) }
    fun setResistance(v: Int) = uiState.update { it.copy(resistanceLevel = v) }

    fun log(line: String) {
        val ts = tsFormat.format(Date())
        android.util.Log.i("BridgeBus", line)
        uiState.update { it.copy(logs = (it.logs + "$ts  $line").takeLast(MAX_LOGS)) }
    }

    /** Tous les logs retenus (ring buffer de [MAX_LOGS] lignes), pour export fichier. */
    fun snapshotLogs(): String = uiState.value.logs.joinToString("\n")

    fun publish(m: RowerMetrics) {
        metrics.value = MetricsUi(
            watts = m.powerWatts,
            paceSecPer500m = m.paceSecPer500m,
            strokeRateSpm = m.strokeRateSpm,
            strokeCount = m.strokeCount,
            distanceMeters = m.distanceMeters,
            heartRateBpm = m.heartRateBpm,
            rawHex = m.rawHex
        )
    }
}
