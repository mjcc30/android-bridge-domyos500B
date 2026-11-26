package com.domyos500b.bridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { BridgeScreen() }
        }
    }

    /** Permissions runtime requises sous Android 16 (SDK 36). */
    fun requiredPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
            list += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        return list.toTypedArray()
    }

    fun allPermissionsGranted(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
}

private fun isBatteryOptimizationIgnored(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true

@Composable
fun BridgeScreen() {
    val context = LocalContext.current
    val activity = context as MainActivity

    var permissionsGranted by remember { mutableStateOf(activity.allPermissionsGranted()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> permissionsGranted = result.values.all { it } }

    LaunchedEffect(Unit) {
        if (!permissionsGranted) permissionLauncher.launch(activity.requiredPermissions())
    }

    var batteryOk by remember { mutableStateOf(isBatteryOptimizationIgnored(context)) }
    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { batteryOk = isBatteryOptimizationIgnored(context) }

    val uiState by BridgeBus.uiState.collectAsStateWithLifecycle()
    val metrics by BridgeBus.metrics.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Auto-scroll de la console vers la trame la plus récente.
    val listState = rememberLazyListState()
    LaunchedEffect(uiState.logs.size) {
        if (uiState.logs.isNotEmpty()) listState.animateScrollToItem(uiState.logs.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Domyos 500B → MyWhoosh — Pont BLE FTMS",
            style = MaterialTheme.typography.titleLarge
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusChip("Service", uiState.serviceRunning)
            StatusChip("Advertising", uiState.advertising)
            StatusChip("Domyos", uiState.domyosConnected)
            StatusChip("MyWhoosh", uiState.mywhooshConnected)
        }

        if (!permissionsGranted) {
            Text(
                text = "Permissions Bluetooth/notification manquantes — redemande en cours…",
                color = Color(0xFFF44336),
                fontSize = 12.sp
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricsCard("Watts", "${metrics.watts} W", Modifier.weight(1f))
            MetricsCard("Pace", formatPace(metrics.paceSecPer500m), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricsCard("Cadence", String.format("%.1f spm", metrics.strokeRateSpm), Modifier.weight(1f))
            MetricsCard(
                "Distance",
                String.format("%.2f km (${metrics.strokeCount} coups)", metrics.distanceMeters / 1000.0),
                Modifier.weight(1f)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    // Exclusion de l'optimisation de batterie : le Pixel 6 Pro
                    // ne coupera pas le BLE écran éteint.
                    batteryLauncher.launch(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                },
                enabled = !batteryOk
            ) {
                Text(if (batteryOk) "Batterie : sans restriction" else "Batterie sans restriction")
            }

            Button(
                onClick = {
                    when {
                        !activity.allPermissionsGranted() ->
                            permissionLauncher.launch(activity.requiredPermissions())
                        uiState.serviceRunning ->
                            context.stopService(Intent(context, BleBridgeService::class.java))
                        else ->
                            // Le service ne fera startForeground() que si les permissions sont OK.
                            ContextCompat.startForegroundService(
                                context,
                                Intent(context, BleBridgeService::class.java)
                            )
                    }
                }
            ) {
                Text(if (uiState.serviceRunning) "Arrêter le bridge" else "Démarrer le bridge")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    // Mise à jour in-app : télécharge l'APK de la release GitHub
                    // « nightly » (réseau uniquement, aucun impact sur le BLE).
                    scope.launch { ApkUpdater.downloadAndInstall(context) }
                }
            ) {
                Text("Mise à jour APK")
            }
            OutlinedButton(onClick = { LogExporter.share(context) }) {
                Text("Envoyer les logs")
            }
        }

        Text("Console — trames hexadécimales", style = MaterialTheme.typography.titleSmall)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(Color(0xFF101010), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(uiState.logs) { line ->
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = when {
                            line.contains("RX") -> Color(0xFF80CBC4)
                            line.contains("TX") -> Color(0xFFFFCC80)
                            line.contains("ERREUR") -> Color(0xFFEF5350)
                            else -> Color(0xFFB0BEC5)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(
                    if (active) Color(0xFF4CAF50) else Color(0xFFF44336),
                    CircleShape
                )
        )
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 12.sp)
    }
}

@Composable
private fun MetricsCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 18.sp, style = MaterialTheme.typography.titleMedium)
    }
}

private fun formatPace(paceSecPer500m: Int): String {
    if (paceSecPer500m <= 0) return "--:-- /500m"
    return String.format("%d:%02d /500m", paceSecPer500m / 60, paceSecPer500m % 60)
}
