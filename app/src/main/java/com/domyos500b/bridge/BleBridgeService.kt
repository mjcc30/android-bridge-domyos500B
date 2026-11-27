package com.domyos500b.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * Foreground Service `connectedDevice` (Android 15/16, SDK 35/36) faisant office de
 * pont BLE autonome :
 *
 *   Domyos 500B (périphérique FTMS 0x1826)  --GATT client-->  [CE SERVICE]  --GATT serveur + advertising-->  MyWhoosh
 *
 * - Côté client : scan 0x1826 + service propriétaire Domyos 49535343-…, connexion
 *   TRANSPORT_LE. Si le rameur expose FTMS : notifications 0x2AD1, indications 0x2AD9,
 *   Control Point 0x00 + 0x07. Sinon (Domyos 500B) : protocole propriétaire « changyow »
 *   (init `f0 c8 01 b9` / `f0 c9 b9` / `f0 a3 93`, keepalive `f0 ac 9c` 1 Hz, paquets
 *   `f0 bc …` parsés par [DomyosParser]). Toutes les opérations GATT passent par
 *   [GattCommandQueue] (sérialisées + timeout).
 * - Côté serveur : service FTMS exposé sous le nom "PM5" (reconnu par MyWhoosh), trame 0x2AD1
 *   normalisée par [FtmsParser] et notifiée à 4 Hz (250 ms).
 *
 * Règle Android 16 : startForeground() n'est appelé qu'APRÈS vérification des
 * permissions BLUETOOTH_CONNECT / BLUETOOTH_ADVERTISE / BLUETOOTH_SCAN.
 */
@SuppressLint("MissingPermission")
class BleBridgeService : Service() {

    companion object {
        const val CHANNEL_ID = "domyos_bridge_channel"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.domyos500b.bridge.ACTION_STOP"
        const val ACTION_SET_RESISTANCE = "com.domyos500b.bridge.ACTION_SET_RESISTANCE"
        const val EXTRA_RESISTANCE_LEVEL = "resistance_level"
        const val ACTION_TEST_DISPLAY = "com.domyos500b.bridge.ACTION_TEST_DISPLAY"
        private const val NOTIFY_PERIOD_MS = 250L   // 4 Hz
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val MTU_REQUEST = 247
        /** Délai pendant lequel on conserve la dernière FC connue quand le rameur renvoie 0. */
        private const val HR_HOLD_MS = 30_000L
        const val RESISTANCE_MIN = 1
        const val RESISTANCE_MAX = 15
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = GattCommandQueue()

    private var bluetoothManager: BluetoothManager? = null
    private var adapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    private var serverRowerDataChar: BluetoothGattCharacteristic? = null
    private var serverControlPointChar: BluetoothGattCharacteristic? = null
    private var serverHeartRateChar: BluetoothGattCharacteristic? = null

    /** Centraux (MyWhoosh) ayant activé les notifications sur 0x2AD1 (accès multi-threads). */
    private val subscribers = java.util.Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())

    /** Centraux ayant activé les notifications sur 0x2A37 (Heart Rate Measurement). */
    private val hrSubscribers = java.util.Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())

    /** Dernière FC reçue du rameur (0 = absente). */
    @Volatile
    private var latestHr = 0

    @Volatile
    private var latestFrame: ByteArray = FtmsParser.idleFrame()

    /** Dernières métriques brutes reçues du rameur (pour le rafraîchissement de sa console). */
    @Volatile
    private var latestRawMetrics: RowerMetrics? = null

    /** Début de session (premier coup détecté) : sert au temps affiché sur l'écran du rameur. */
    @Volatile
    private var sessionStartMs = 0L

    /** Horodatage de la connexion GATT au rameur (repli pour le temps écran si pas encore de coup). */
    @Volatile
    private var connectedAtMs = 0L

    /** Dernière FC non nulle reçue + son horodatage : on la conserve [HR_HOLD_MS] quand le rameur renvoie 0. */
    private var lastHrValue = 0
    private var lastHrTimeMs = 0L

    /** Niveau de résistance courant du rameur (1-15), réglable depuis l'app ou via FTMS 0x2AD9 (0x04). */
    @Volatile
    private var currentResistance = 8

    @Volatile
    private var running = false

    /** Session propriétaire Domyos active (keepalive en cours). */
    @Volatile
    private var proprietaryActive = false

    /** Caractéristique d'écriture du protocole propriétaire (quand actif). */
    private var domyosWriteChar: BluetoothGattCharacteristic? = null

    /** Connexion directe à la ceinture cardiaque (0x180D) — source FC de secours. */
    private var hrGatt: BluetoothGatt? = null

    /** Dernière FC lue directement sur la ceinture + horodatage. */
    private var strapHr = 0
    private var strapHrTimeMs = 0L

    /** Vrai pendant l'envoi des trames « marqueurs » de test d'écran (suspend le ticker). */
    @Volatile
    private var displayTestRunning = false

    /** Continuation de l'opération GATT en cours (la file garantit l'unicité). */
    private var pending: Continuation<Boolean>? = null

    // ------------------------------------------------------------------
    // Cycle de vie
    // ------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        bluetoothManager = getSystemService(BluetoothManager::class.java)
        adapter = bluetoothManager?.adapter
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SET_RESISTANCE) {
            val level = intent.getIntExtra(EXTRA_RESISTANCE_LEVEL, currentResistance)
            scope.launch { setRowerResistance(level) }
            return START_STICKY
        }
        if (intent?.action == ACTION_TEST_DISPLAY) {
            scope.launch { sendDisplayTest() }
            return START_STICKY
        }

        // CONTRAINTE ANDROID 16 : jamais de startForeground() sans les permissions BT.
        if (!hasRequiredPermissions()) {
            BridgeBus.log("ERREUR: BLUETOOTH_CONNECT/ADVERTISE/SCAN non accordés -> startForeground() non appelé, arrêt.")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()

        if (!running) {
            running = true
            BridgeBus.setServiceRunning(true)
            BridgeBus.log("=== Service démarré (foregroundServiceType=connectedDevice) ===")
            startGattServer()
            startAdvertising()
            startScan()
            startNotifyTicker()
            // La console du 500B ne s'allume / n'advertise qu'au premier coup de rame :
            // si aucun rameur n'est vu, on le signale clairement (le scan continue en
            // arrière-plan et se connectera tout seul dès que la console se réveille).
            scope.launch {
                var hinted = false
                while (running && gatt == null) {
                    delay(10_000)
                    if (!running || gatt != null) break
                    if (!hinted) {
                        hinted = true
                        BridgeBus.log("Aucun rameur détecté pour l'instant — si la console du Domyos est éteinte, ramez un coup pour la réveiller : le scan continue et la connexion se fera automatiquement.")
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun hasRequiredPermissions(): Boolean {
        val required = listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_SCAN
        )
        return required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Pont BLE Domyos 500B", NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Domyos 500B Bridge actif")
            .setContentText("Pont FTMS vers MyWhoosh en cours…")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        // API 29+ : type explicite requis (connectedDevice).
        startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    private fun shutdown() {
        running = false
        proprietaryActive = false
        scope.cancel()
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        try { hrGatt?.disconnect() } catch (_: Exception) {}
        try { hrGatt?.close() } catch (_: Exception) {}
        hrGatt = null
        strapHr = 0
        strapHrTimeMs = 0L
        displayTestRunning = false
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        subscribers.clear()
        hrSubscribers.clear()
        hrServiceAdded = false
        pendingHrService = null
        integratedDistanceM = 0.0
        lastMetricsTimeMs = 0L
        lastStrokeCount = -1
        latestRawMetrics = null
        sessionStartMs = 0L
        connectedAtMs = 0L
        lastHrValue = 0
        lastHrTimeMs = 0L
        currentResistance = 8
        BridgeBus.setResistance(0)
        BridgeBus.setRowerStreaming(false)
        BridgeBus.setServiceRunning(false)
        BridgeBus.setAdvertising(false)
        BridgeBus.setDomyosConnected(false)
        BridgeBus.setMyWhooshConnected(false)
        BridgeBus.log("=== Bridge arrêté ===")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------
    // Aide : opération GATT bloquante sérialisée par la GattCommandQueue
    // ------------------------------------------------------------------

    private suspend fun awaitOp(label: String, starter: () -> Boolean): Boolean =
        suspendCancellableCoroutine { cont ->
            pending = cont
            cont.invokeOnCancellation { pending = null }
            val started = try {
                starter()
            } catch (e: Exception) {
                BridgeBus.log("$label: exception ${e.message}")
                false
            }
            if (!started) {
                pending = null
                cont.resume(false)
            }
        }

    private fun resumePending(ok: Boolean) {
        pending?.resume(ok)
        pending = null
    }

    // ------------------------------------------------------------------
    // Côté CLIENT : scan + connexion au rameur Domyos 500B
    // ------------------------------------------------------------------

    private fun startScan() {
        val btAdapter = adapter ?: return
        if (!btAdapter.isEnabled) {
            BridgeBus.log("Bluetooth éteint — scan impossible.")
            return
        }
        scanner = btAdapter.bluetoothLeScanner ?: return
        // Scan SANS filtre : certains firmwares ne mettent pas l'UUID du service dans
        // le paquet d'advertising principal (seulement en scan response), un filtre
        // UUID les fait rater et allonge la découverte. La classification se fait dans
        // le callback (rameur = 0x1826 ou 49535343-… ou nom Domyos/ROW ; ceinture =
        // service 0x180D seul).
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            scanner?.startScan(null, settings, scanCallback)
            BridgeBus.log("Scan BLE démarré (sans filtre, LOW_LATENCY, classification dans le callback)")
        } catch (se: SecurityException) {
            BridgeBus.log("Scan refusé: ${se.message}")
        }
        // Watchdog : si aucun périphérique n'est vu, certains chipsets cessent de
        // délivrer les résultats -> on redémarre le scan périodiquement.
        scope.launch {
            while (running) {
                delay(20_000)
                if (!running) break
                if (gatt == null || hrGatt == null) {
                    BridgeBus.log("Watchdog scan: redémarrage du scan (rower=${gatt != null}, ceinture=${hrGatt != null})")
                    try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
                    try { scanner?.startScan(null, settings, scanCallback) } catch (_: Exception) {}
                } else {
                    // Les deux périphériques sont connectés : le scan ne sert plus à rien.
                    try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
            val name = try { device.name } catch (_: SecurityException) { null }
            val isRower = uuids.contains(BleConstants.DOMYOS_SERVICE) ||
                uuids.contains(BleConstants.FTMS_SERVICE) ||
                (name != null && (name.contains("Domyos", ignoreCase = true) ||
                    name.startsWith("ROW", ignoreCase = true) ||
                    name.startsWith("RW", ignoreCase = true)))
            val isHrStrap = !isRower && uuids.contains(BleConstants.HEART_RATE_SERVICE)
            if (isRower && gatt == null) {
                BridgeBus.log("Scan: RAMEUR ${name ?: "?"} ${device.address} RSSI=${result.rssi}")
                connectToRower(device)
            } else if (isHrStrap && hrGatt == null) {
                BridgeBus.log("Scan: CEINTURE HR ${name ?: "?"} ${device.address} RSSI=${result.rssi}")
                connectHrStrap(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            BridgeBus.log("Scan échoué, code=$errorCode")
        }
    }

    private fun connectToRower(device: BluetoothDevice) {
        BridgeBus.log("Connexion GATT -> rameur ${device.address} (TRANSPORT_LE)")
        try {
            gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (se: SecurityException) {
            BridgeBus.log("connectGatt refusé: ${se.message}")
        }
    }

    /** Connexion directe à la ceinture cardiaque (0x180D/2A37) pour une FC plus rapide. */
    private fun connectHrStrap(device: BluetoothDevice) {
        BridgeBus.log("Connexion GATT -> ceinture HR ${device.address} (TRANSPORT_LE)")
        try {
            hrGatt = device.connectGatt(this, false, hrGattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (se: SecurityException) {
            BridgeBus.log("connectGatt (ceinture) refusé: ${se.message}")
        }
    }

    private val hrGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                BridgeBus.log("Ceinture HR CONNECTÉE (status=$status) -> discovery services")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                BridgeBus.log("Ceinture HR DÉCONNECTÉE (status=$status) -> rescan dans ${RECONNECT_DELAY_MS} ms")
                try { g.close() } catch (_: Exception) {}
                if (hrGatt === g) hrGatt = null
                strapHr = 0
                strapHrTimeMs = 0L
                scope.launch {
                    delay(RECONNECT_DELAY_MS)
                    if (running) startScan()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val hrChar = g.services
                ?.firstOrNull { it.uuid == BleConstants.HEART_RATE_SERVICE }
                ?.getCharacteristic(BleConstants.HEART_RATE_MEASUREMENT)
            if (hrChar == null) {
                BridgeBus.log("Ceinture HR: service 0x180D/0x2A37 introuvable (status=$status)")
                return
            }
            g.setCharacteristicNotification(hrChar, true)
            val cccd = hrChar.getDescriptor(BleConstants.CCCD)
            if (cccd != null) {
                // Fire-and-forget : on n'attend pas la réponse pour ne pas polluer la
                // GattCommandQueue du rameur (continuation `pending` partagée sinon).
                val rc = g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                BridgeBus.log("Ceinture HR: CCCD 0x2A37 écrit (rc=$rc)")
            }
            BridgeBus.log("Ceinture HR: notifications 0x2A37 armées")
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid != BleConstants.HEART_RATE_MEASUREMENT || value.size < 2) return
            val flags = value[0].toInt() and 0xFF
            val bpm = if (flags and 0x01 == 0) {
                value[1].toInt() and 0xFF
            } else {
                (((value[2].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)).coerceIn(0, 255)
            }
            if (bpm > 0) {
                strapHr = bpm
                strapHrTimeMs = System.currentTimeMillis()
                BridgeBus.log("RX HR ceinture <- $bpm bpm (${value.toHex()})")
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                BridgeBus.setDomyosConnected(true)
                connectedAtMs = System.currentTimeMillis()
                BridgeBus.log("Domyos CONNECTÉ (status=$status)")
                // Latence minimale sur la liaison rameur (métriques à ~1 Hz mais
                // keepalive/écran à 1 Hz : on veut le délai le plus court possible).
                try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (_: Exception) {}
                // Workaround : certains chipsets suspendent l'advertising peripheral tant
                // qu'une connexion centrale est active -> on le redémarre à chaque connexion,
                // en BALANCED (le LOW_LATENCY est sacrifié dès qu'on est central).
                try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
                startAdvertising(lowLatency = false)
                scope.launch {
                    try {
                        delay(500) // laisse le requestConnectionPriority s'appliquer
                        queue.enqueue { awaitOp("requestMtu") { g.requestMtu(MTU_REQUEST) } }
                        val discovered = queue.enqueue { awaitOp("discoverServices") { g.discoverServices() } }
                        if (!discovered) throw Exception("découverte des services échouée")
                        setupSession(g)
                    } catch (e: Exception) {
                        BridgeBus.log("Session rameur: ${e.message}")
                    }
                }
                // Watchdog « console endormie » : la console du 500B ne s'allume et
                // n'émet des trames `f0 bc` qu'au premier coup de rame. Si on est
                // connecté mais silencieux, on le signale et on renvoie l'init une fois.
                scope.launch {
                    var hinted = false
                    while (running && gatt != null && latestRawMetrics == null) {
                        delay(6_000)
                        if (!running || gatt == null || latestRawMetrics != null) break
                        if (!hinted) {
                            hinted = true
                            BridgeBus.log("Rameur connecté mais aucune trame reçue — ramez un coup pour réveiller la console et démarrer la session ; renvoi de la séquence d'init.")
                            if (proprietaryActive) {
                                for (pkt in BleConstants.DOMYOS_INIT) {
                                    writeDomyosFrame(pkt)
                                    delay(120)
                                }
                            }
                        } else {
                            BridgeBus.log("Toujours aucune trame du rameur — ramez un coup pour démarrer la session.")
                        }
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                BridgeBus.setDomyosConnected(false)
                BridgeBus.setRowerStreaming(false)
                proprietaryActive = false
                domyosWriteChar = null
                lastHrValue = 0
                lastHrTimeMs = 0L
                sessionStartMs = 0L
                connectedAtMs = 0L
                latestRawMetrics = null
                integratedDistanceM = 0.0
                lastMetricsTimeMs = 0L
                lastStrokeCount = -1
                BridgeBus.setResistance(0)
                BridgeBus.log("Domyos DÉCONNECTÉ (status=$status) -> reconnexion auto dans ${RECONNECT_DELAY_MS} ms")
                resumePending(false)
                try { g.close() } catch (_: Exception) {}
                if (gatt === g) gatt = null
                // Rameur parti : on repasse l'advertising en LOW_LATENCY pour que
                // MyWhoosh retrouve le pont au plus vite.
                try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
                startAdvertising(lowLatency = true)
                scope.launch {
                    delay(RECONNECT_DELAY_MS)
                    if (running) startScan()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            BridgeBus.log("MTU = $mtu (status=$status)")
            resumePending(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            resumePending(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 33+ : nouvelle signature avec `value: ByteArray` (jamais la version dépréciée).
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == BleConstants.ROWER_DATA) {
                BridgeBus.log("RX 2AD1 <- ${value.toHex()}")
                val m = FtmsParser.parse(value)
                if (m != null) publishMetrics(m)
            } else if (characteristic.uuid == BleConstants.DOMYOS_NOTIFY) {
                BridgeBus.log("RX Domyos <- ${value.toHex()}")
                val m = DomyosParser.parse(value)
                if (m != null) publishMetrics(m)
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            resumePending(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            resumePending(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    // Distance intégrée localement : le compteur du Domyos progresse trop lentement
    // (quelques mètres après des dizaines de coups) et MyWhoosh affiche/intègre le
    // champ Total Distance de 2AD1 pour la position/vitesse du bateau -> bateau à l'arrêt.
    // On intègre ici la vitesse dérivée du pace : v (m/s) = 500 / pace(s/500m).
    @Volatile private var integratedDistanceM = 0.0
    private var lastMetricsTimeMs = 0L
    private var lastStrokeCount = -1

    private fun publishMetrics(m: RowerMetrics) {
        BridgeBus.setRowerStreaming(true)
        val now = System.currentTimeMillis()
        val dt = if (lastMetricsTimeMs > 0L) ((now - lastMetricsTimeMs) / 1000.0).coerceIn(0.0, 2.0) else 0.0
        lastMetricsTimeMs = now
        if (lastStrokeCount >= 0 && m.strokeCount < lastStrokeCount) {
            integratedDistanceM = 0.0 // nouvelle session
            sessionStartMs = 0L
            lastHrValue = 0
            lastHrTimeMs = 0L
        }
        lastStrokeCount = m.strokeCount
        if (m.paceSecPer500m > 0) integratedDistanceM += (500.0 / m.paceSecPer500m) * dt
        if (sessionStartMs == 0L && (m.strokeCount > 0 || m.strokeRateSpm > 0.0)) sessionStartMs = now
        // Hold FC : quand le rameur renvoie 0 (ceinture pas encore appairée par la
        // console, ou pause), on prend la FC lue directement sur la ceinture si elle
        // est fraîche, sinon on conserve la dernière valeur connue pendant HR_HOLD_MS.
        val strapFresh = strapHr > 0 && now - strapHrTimeMs <= 8_000
        val hrEff = when {
            m.heartRateBpm > 0 -> {
                lastHrValue = m.heartRateBpm
                lastHrTimeMs = now
                m.heartRateBpm
            }
            strapFresh -> {
                lastHrValue = strapHr
                lastHrTimeMs = now
                strapHr
            }
            lastHrValue > 0 && now - lastHrTimeMs <= HR_HOLD_MS -> lastHrValue
            else -> 0
        }
        if (m.resistanceLevel > 0) {
            currentResistance = m.resistanceLevel
            BridgeBus.setResistance(m.resistanceLevel)
        }
        latestRawMetrics = m.copy(heartRateBpm = hrEff)
        val mEff = m.copy(distanceMeters = integratedDistanceM.roundToInt(), heartRateBpm = hrEff)
        BridgeBus.publish(mEff)
        latestFrame = FtmsParser.buildNormalizedFrame(mEff)
        latestHr = hrEff
        BridgeBus.log(
            "   parse: ${m.strokeRateSpm} spm | ${m.strokeCount} coups | " +
                "${mEff.distanceMeters} m (intégrée) | pace=${m.paceSecPer500m}s/500m | ${m.powerWatts} W | ${m.heartRateBpm} bpm"
        )
    }

    /**
     * Essaie d'abord le FTMS standard (0x1826), puis repli sur le protocole
     * propriétaire Domyos (service 49535343-…) si le rameur ne l'expose pas.
     */
    private suspend fun setupSession(g: BluetoothGatt) {
        val hasFtms = g.services?.any { it.uuid == BleConstants.FTMS_SERVICE } == true
        if (hasFtms) {
            setupFtmSession(g)
        } else {
            setupProprietarySession(g)
        }
    }

    /** Session via le protocole propriétaire Domyos « changyow ». */
    private suspend fun setupProprietarySession(g: BluetoothGatt) {
        val service = g.services?.firstOrNull { it.uuid == BleConstants.DOMYOS_SERVICE }
            ?: throw Exception("ni FTMS 0x1826 ni service Domyos 49535343-… trouvés sur le rameur")
        val notifyChar = service.getCharacteristic(BleConstants.DOMYOS_NOTIFY)
            ?: throw Exception("caractéristique notify Domyos absente")
        val writeChar = service.getCharacteristic(BleConstants.DOMYOS_WRITE)
            ?: throw Exception("caractéristique write Domyos absente")
        domyosWriteChar = writeChar

        // Indispensable côté Android : écrire le CCCD ne suffit pas, il faut aussi
        // activer la notification localement, sinon onCharacteristicChanged n'est
        // jamais appelé et aucun paquet `f0 bc` n'est reçu.
        g.setCharacteristicNotification(notifyChar, true)

        val cccd = notifyChar.getDescriptor(BleConstants.CCCD)
        val notifyOk = queue.enqueue {
            cccd != null && awaitOp("CCCD Domyos") {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothGatt.GATT_SUCCESS
            }
        }
        BridgeBus.log("CCCD notify Domyos: ${if (notifyOk) "OK" else "ÉCHEC"}")

        // Séquence d'init « changyow » COMPLÈTE (inclut le passage de la console en mode
        // « affichage piloté par l'app » f0 cb 02 … et le « start tape »), puis keepalive 1 Hz.
        for (pkt in BleConstants.DOMYOS_INIT) {
            writeDomyosFrame(pkt)
            delay(120)
        }
        proprietaryActive = true
        startKeepalive()
        sendDisplayFrames() // première trame tout de suite, sans attendre 1 s
        startDisplayTicker()
        BridgeBus.log("Session Domyos propriétaire active (init complète ${BleConstants.DOMYOS_INIT.size} trames, keepalive 1 Hz, écran 1 Hz)")
    }

    /**
     * Écrit une trame longue sur la caractéristique Domyos en paquets de 20 octets max
     * (MTU 23), comme qdomyos-zwift — ex. trames d'affichage de 27 octets (20+7).
     */
    private suspend fun writeDomyosFrame(frame: ByteArray) {
        var off = 0
        while (off < frame.size) {
            val end = minOf(off + 20, frame.size)
            writeDomyos(frame.copyOfRange(off, end))
            off = end
        }
    }

    /**
     * Règle la résistance du rameur (niveau 1-15) via la trame `f0 ad …`, envoyée
     * depuis l'app (boutons +/-) ou relayée du Control Point FTMS (opcode 0x04).
     */
    suspend fun setRowerResistance(level: Int) {
        val l = level.coerceIn(RESISTANCE_MIN, RESISTANCE_MAX)
        val frame = DomyosParser.buildResistanceFrame(l)
        BridgeBus.log("TX résistance -> niveau $l (trame ${frame.toHex()})")
        writeDomyosFrame(frame)
        currentResistance = l
        BridgeBus.setResistance(l)
    }

    private suspend fun writeDomyos(value: ByteArray) {
        val g = gatt ?: return
        val ch = domyosWriteChar ?: return
        BridgeBus.log("TX Domyos -> ${value.toHex()}")
        val ok = queue.enqueue {
            awaitOp("write Domyos") {
                g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothGatt.GATT_SUCCESS
            }
        }
        if (!ok) BridgeBus.log("Écriture Domyos ${value.toHex()} : ÉCHEC/timeout")
    }

    /** Keepalive `f0 ac 9c` chaque seconde tant que la session propriétaire est active. */
    private fun startKeepalive() {
        scope.launch {
            while (running && proprietaryActive) {
                delay(1_000)
                if (proprietaryActive) writeDomyos(BleConstants.DOMYOS_KEEPALIVE)
            }
        }
    }

    /**
     * Rafraîchit l'écran du rameur à 1 Hz en lui renvoyant ses propres métriques
     * (trames `f0 cd` distance + `f0 cb` temps/cadence/pace/kcal/distance, mapping
     * 500B cf. [DomyosParser]), comme le fait Kinomap ou qdomyos-zwift quand un
     * central est connecté : sans ces trames, la console du 500B n'affiche pas
     * les données de la session.
     * Chaque trame de 27 octets est écrite en 2 paquets (20 + 7) pour tenir dans
     * un MTU 23 si le module ne négocie pas davantage.
     */
    private fun startDisplayTicker() {
        scope.launch {
            while (running && proprietaryActive) {
                delay(1_000)
                if (!proprietaryActive) break
                if (displayTestRunning) continue // ne pas écraser les trames marqueurs
                sendDisplayFrames()
            }
        }
    }

    /**
     * Envoie une paire de trames d'affichage (f0 cd distance + f0 cb temps/Spm/pace/Kcal/Km).
     * La distance affichée est la distance intégrée localement (le compteur du rameur
     * progresse trop lentement), comme dans le flux FTMS vers MyWhoosh.
     * Tant qu'aucune trame `f0 bc` n'a été reçue (console réveillée mais rameur au repos),
     * on envoie des zéros avec le temps écoulé depuis la connexion : la console du 500B
     * reste ainsi allumée et affiche 000 / 00:00 au lieu de rester noire.
     */
    private suspend fun sendDisplayFrames() {
        val m = latestRawMetrics ?: RowerMetrics(
            flags = FtmsParser.NORMALIZED_FLAGS,
            strokeRateSpm = 0.0,
            strokeCount = 0,
            distanceMeters = 0,
            paceSecPer500m = 0,
            powerWatts = 0,
            heartRateBpm = 0,
            caloriesKcal = 0,
            resistanceLevel = 0,
            rawHex = ""
        )
        val startMs = if (sessionStartMs > 0L) sessionStartMs else connectedAtMs
        val elapsedSec =
            if (startMs > 0L) ((System.currentTimeMillis() - startMs) / 1000).toInt()
            else 0
        val distM = integratedDistanceM.roundToInt()
        val frames = listOf(
            DomyosParser.buildDistanceDisplayFrame(distM),
            DomyosParser.buildDisplayFrame(
                elapsedSec, m.strokeRateSpm.roundToInt(), m.paceSecPer500m,
                m.caloriesKcal, distM
            )
        )
        for (frame in frames) {
            writeDomyosFrame(frame)
        }
    }

    /**
     * Test d'écran (diagnostic) : envoie des trames « marqueurs » où chaque octet a une
     * valeur distincte, pour lire sur une photo de la console quels octets pilotent
     * quels champs. Envoie aussi une trame de résistance niveau 10. ~6 s.
     */
    private suspend fun sendDisplayTest() {
        if (domyosWriteChar == null) {
            BridgeBus.log("TEST ÉCRAN: rameur non connecté -> abandon")
            return
        }
        displayTestRunning = true
        try {
            BridgeBus.log("TEST ÉCRAN: trames marqueurs f0 cb / f0 cd + résistance 10 (photographiez la console)")
            writeDomyosFrame(DomyosParser.buildResistanceFrame(10))
            repeat(15) {
                writeDomyosFrame(DomyosParser.buildMarkerDistanceFrame())
                writeDomyosFrame(DomyosParser.buildMarkerDisplayFrame())
                delay(400)
            }
            BridgeBus.log("TEST ÉCRAN: terminé")
        } finally {
            displayTestRunning = false
        }
    }

    /** Active notifications 0x2AD1 + indications 0x2AD9, puis réveille le rameur (0x00 puis 0x07). */
    private suspend fun setupFtmSession(g: BluetoothGatt) {
        val service = g.services?.firstOrNull { it.uuid == BleConstants.FTMS_SERVICE }
            ?: throw Exception("service FTMS 0x1826 introuvable sur le rameur")
        val rower = service.getCharacteristic(BleConstants.ROWER_DATA)
            ?: throw Exception("caractéristique 0x2AD1 absente")
        val cp = service.getCharacteristic(BleConstants.FTMS_CONTROL_POINT)
            ?: throw Exception("caractéristique 0x2AD9 absente")

        // Active la réception locale des notifications/indications (le CCCD seul
        // ne déclenche pas onCharacteristicChanged sur Android).
        g.setCharacteristicNotification(rower, true)
        g.setCharacteristicNotification(cp, true)

        val cccdRower = rower.getDescriptor(BleConstants.CCCD)
        val cccdCp = cp.getDescriptor(BleConstants.CCCD)

        // API 33+ : writeDescriptor(descriptor, value) — nouvelle signature.
        val notifyOk = queue.enqueue {
            cccdRower != null &&
                awaitOp("CCCD 2AD1") {
                    // API 33+ : writeDescriptor renvoie un Int (statut), pas un Boolean.
                    g.writeDescriptor(cccdRower, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                        BluetoothGatt.GATT_SUCCESS
                }
        }
        BridgeBus.log("CCCD 0x2AD1 notifications: ${if (notifyOk) "OK" else "ÉCHEC"}")

        val indicateOk = queue.enqueue {
            cccdCp != null &&
                awaitOp("CCCD 2AD9") {
                    g.writeDescriptor(cccdCp, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) ==
                        BluetoothGatt.GATT_SUCCESS
                }
        }
        BridgeBus.log("CCCD 0x2AD9 indications: ${if (indicateOk) "OK" else "ÉCHEC"}")

        sendControlPointToRower(byteArrayOf(BleConstants.OP_REQUEST_CONTROL)) // 0x00
        delay(300)
        sendControlPointToRower(byteArrayOf(BleConstants.OP_START_OR_RESUME)) // 0x07
    }

    private suspend fun sendControlPointToRower(value: ByteArray) {
        try {
            val g = gatt ?: return
            val cp = g.services
                ?.firstOrNull { it.uuid == BleConstants.FTMS_SERVICE }
                ?.getCharacteristic(BleConstants.FTMS_CONTROL_POINT) ?: return
            BridgeBus.log("TX CP  -> ${value.toHex()}")
            // API 33+ : writeCharacteristic(char, value, writeType) — nouvelle signature.
            val ok = queue.enqueue {
                awaitOp("write CP") {
                    // API 33+ : writeCharacteristic renvoie un Int (statut), pas un Boolean.
                    g.writeCharacteristic(cp, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                        BluetoothGatt.GATT_SUCCESS
                }
            }
            BridgeBus.log("Écriture 0x2AD9 ${value.toHex()} : ${if (ok) "OK" else "ÉCHEC/timeout"}")
        } catch (e: Exception) {
            BridgeBus.log("Control Point: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Côté SERVEUR : GATT server FTMS + advertising vers MyWhoosh
    // ------------------------------------------------------------------

    private fun startGattServer() {
        val server = bluetoothManager?.openGattServer(this, serverCallback) ?: run {
            BridgeBus.log("ERREUR: openGattServer() a retourné null (Bluetooth éteint ?)")
            return
        }
        gattServer = server

        fun cccd(): BluetoothGattDescriptor = BluetoothGattDescriptor(
            BleConstants.CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )

        val feature = BluetoothGattCharacteristic(
            BleConstants.FITNESS_MACHINE_FEATURE,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val rowerData = BluetoothGattCharacteristic(
            BleConstants.ROWER_DATA,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply { addDescriptor(cccd()) }

        val machineStatus = BluetoothGattCharacteristic(
            BleConstants.FITNESS_MACHINE_STATUS,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply { addDescriptor(cccd()) }

        val trainingStatus = BluetoothGattCharacteristic(
            BleConstants.TRAINING_STATUS,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply { addDescriptor(cccd()) }

        val controlPoint = BluetoothGattCharacteristic(
            BleConstants.FTMS_CONTROL_POINT,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        ).apply { addDescriptor(cccd()) }

        val ftmsService = BluetoothGattService(
            BleConstants.FTMS_SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        ).apply {
            addCharacteristic(feature)
            addCharacteristic(rowerData)
            addCharacteristic(machineStatus)
            addCharacteristic(trainingStatus)
            addCharacteristic(controlPoint)
        }

        server.addService(ftmsService)
        serverRowerDataChar = rowerData
        serverControlPointChar = controlPoint

        // Service Heart Rate 0x180D : MyWhoosh ne lit PAS la FC dans la trame 2AD1,
        // il la cherche sur un service 0x180D (caractéristique 0x2A37) — on la relaie
        // depuis l'octet 18 des paquets Domyos (ceinture fournie avec le rameur).
        val heartRate = BluetoothGattCharacteristic(
            BleConstants.HEART_RATE_MEASUREMENT,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply { addDescriptor(cccd()) }
        pendingHrService = BluetoothGattService(
            BleConstants.HEART_RATE_SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        ).apply { addCharacteristic(heartRate) }
        serverHeartRateChar = heartRate
        // ATTENTION : ne PAS appeler addService(hrService) ici. Deux addService()
        // back-to-back font planter le stub binder du framework dans onServiceAdded
        // (IndexOutOfBoundsException) -> table de handles corrompue -> plus aucun
        // descripteur reconnu ("no desc for handle") -> aucun CCCD écrit par le
        // client -> MyWhoosh reste à 0 W / 0 spm. On ajoute le service HR dans
        // onServiceAdded(FTMS) ci-dessous, une fois le premier confirmé.
        BridgeBus.log("GATT server: FTMS 2ACC/2AD1/2AD3/2ADA/2AD9 exposé; HR 180D/2A37 ajouté après confirmation onServiceAdded")
    }

    /** Service HR en attente d'être ajouté après confirmation du service FTMS. */
    private var pendingHrService: BluetoothGattService? = null
    private var hrServiceAdded = false

    private val serverCallback = object : BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            BridgeBus.log("onServiceAdded ${service.uuid} status=$status")
            // Ajout chaîné du service HR une fois le FTMS confirmé : évite la course
            // du framework (voir commentaire dans startGattServer).
            if (status == BluetoothGatt.GATT_SUCCESS &&
                service.uuid == BleConstants.FTMS_SERVICE &&
                !hrServiceAdded
            ) {
                hrServiceAdded = true
                val hr = pendingHrService
                pendingHrService = null
                if (hr != null) {
                    gattServer?.addService(hr)
                    BridgeBus.log("Service HR 0x180D/2A37 ajouté (chaîné après FTMS)")
                }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                BridgeBus.log("CENTRAL connecté: ${device.address} (${device.name ?: "?"})")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribers.remove(device)
                refreshMyWhooshStatus()
                BridgeBus.log("CENTRAL déconnecté: ${device.address}")
            }
        }

        // SDK 36 : ordre des paramètres (device, requestId, offset, characteristic).
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value: ByteArray? = when (characteristic.uuid) {
                BleConstants.FITNESS_MACHINE_FEATURE -> BleConstants.FITNESS_MACHINE_FEATURE_VALUE
                BleConstants.ROWER_DATA -> latestFrame
                BleConstants.FITNESS_MACHINE_STATUS -> byteArrayOf(0x00)
                BleConstants.TRAINING_STATUS -> byteArrayOf(0x00)
                BleConstants.HEART_RATE_MEASUREMENT -> byteArrayOf(0x00, latestHr.toByte())
                else -> null
            }
            val server = gattServer ?: return
            if (value != null) {
                val from = offset.coerceIn(0, value.size)
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(from, value.size))
            } else {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }

        // API 33+ : nouvelle signature avec `value: ByteArray`.
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val server = gattServer
            if (characteristic.uuid == BleConstants.FTMS_CONTROL_POINT && value.isNotEmpty() && server != null) {
                val opcode = value[0]
                BridgeBus.log("RX CP  <- ${value.toHex()} (opcode 0x${"%02X".format(opcode)})")
                if (responseNeeded) {
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
                // Toute commande reçue est validée par l'indication 0x80, opcode, 0x01 (Success).
                // confirm = true car 0x2AD9 est une caractéristique à INDICATION.
                val response = byteArrayOf(BleConstants.RESPONSE_OPCODE, opcode, BleConstants.RESPONSE_SUCCESS)
                serverControlPointChar?.let { cp ->
                    server.notifyCharacteristicChanged(device, cp, true, response)
                }
                BridgeBus.log("TX CP  -> ${response.toHex()} (Response, indication)")
                // Relais vers le rameur pour garder les deux sessions synchronisées.
                when {
                    opcode == BleConstants.OP_REQUEST_CONTROL || opcode == BleConstants.OP_START_OR_RESUME -> {
                        scope.launch { sendControlPointToRower(byteArrayOf(opcode)) }
                    }
                    opcode == BleConstants.OP_SET_TARGET_RESISTANCE && value.size >= 2 -> {
                        // Set Target Resistance : paramètre uint8 (0-255) -> niveau 1-15 du rameur.
                        val v = value[1].toInt() and 0xFF
                        val level = (RESISTANCE_MIN + v * (RESISTANCE_MAX - RESISTANCE_MIN) / 255)
                            .coerceIn(RESISTANCE_MIN, RESISTANCE_MAX)
                        scope.launch { setRowerResistance(level) }
                    }
                }
            } else if (responseNeeded && server != null) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        // API 33+ : nouvelle signature avec `value: ByteArray`.
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == BleConstants.CCCD) {
                val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                when (descriptor.characteristic.uuid) {
                    BleConstants.ROWER_DATA -> {
                        if (enabled) subscribers.add(device) else subscribers.remove(device)
                        refreshMyWhooshStatus()
                        BridgeBus.log("CCCD 0x2AD1 ${if (enabled) "ENABLE" else "DISABLE"} par ${device.address}")
                    }
                    BleConstants.HEART_RATE_MEASUREMENT -> {
                        if (enabled) hrSubscribers.add(device) else hrSubscribers.remove(device)
                        BridgeBus.log("CCCD 0x2A37 ${if (enabled) "ENABLE" else "DISABLE"} par ${device.address}")
                    }
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    private fun refreshMyWhooshStatus() {
        BridgeBus.setMyWhooshConnected(subscribers.isNotEmpty())
    }

    private var advertisingLowLatency = true

    private fun startAdvertising(lowLatency: Boolean = true) {
        advertisingLowLatency = lowLatency
        val btAdapter = adapter ?: return
        if (!btAdapter.isEnabled) return
        try {
            btAdapter.setName(BleConstants.DEVICE_NAME) // nécessite BLUETOOTH_CONNECT (S+)
        } catch (se: SecurityException) {
            BridgeBus.log("setName refusé: ${se.message}")
        }
        advertiser = btAdapter.bluetoothLeAdvertiser ?: run {
            BridgeBus.log("ERREUR: bluetoothLeAdvertiser indisponible (BLE non supporté en peripheral ?)")
            return
        }
        val settings = AdvertiseSettings.Builder()
            // LOW_LATENCY (100 ms) tant que le téléphone n'est pas central : MyWhoosh
            // trouve le pont plus vite. BALANCED (250 ms) une fois connecté au rameur :
            // certains chipsets sacrifient l'advertising LOW_LATENCY dès qu'une
            // connexion centrale est active.
            .setAdvertiseMode(
                if (lowLatency) AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                else AdvertiseSettings.ADVERTISE_MODE_BALANCED
            )
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0) // illimité
            .build()
        val advertiseData = AdvertiseData.Builder()
            // Nom DANS le paquet principal : un scan passif (Windows/MyWhoosh HD) ne
            // reçoit pas la scan response et verrait un périphérique sans nom.
            // 0x180D dans le paquet principal aussi : le scan "Heart Rate" de
            // MyWhoosh filtre sur ce service (UUID 16 bits = 3 octets, total ~14 o).
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(BleConstants.FTMS_SERVICE))
            .addServiceUuid(ParcelUuid(BleConstants.HEART_RATE_SERVICE))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        try {
            advertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
        } catch (se: SecurityException) {
            BridgeBus.log("Advertising refusé: ${se.message}")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            BridgeBus.setAdvertising(true)
            BridgeBus.log("Advertising démarré: \"${BleConstants.DEVICE_NAME}\" (service 0x1826, nom dans adv, TX_HIGH, mode=${if (advertisingLowLatency) "LOW_LATENCY" else "BALANCED"})")
        }

        override fun onStartFailure(errorCode: Int) {
            BridgeBus.setAdvertising(false)
            BridgeBus.log("Advertising échoué, code=$errorCode")
        }
    }

    /** Notifie la trame 0x2AD1 normalisée à tous les abonnés, à 4 Hz (250 ms). */
    private fun startNotifyTicker() {
        scope.launch {
            while (running) {
                val frame = latestFrame
                val char = serverRowerDataChar
                val server = gattServer
                if (char != null && server != null && subscribers.isNotEmpty()) {
                    for (device in subscribers.toList()) {
                        try {
                            // API 33+ : notifyCharacteristicChanged(device, char, confirm=false, value)
                            server.notifyCharacteristicChanged(device, char, false, frame)
                        } catch (e: Exception) {
                            // Client parti sans se désabonner : on le retire pour ne pas
                            // spammer les logs à chaque trame.
                            if (e is android.os.DeadObjectException) {
                                subscribers.remove(device)
                                BridgeBus.log("abonné ${device.address} mort, retiré (${subscribers.size} restant(s))")
                                refreshMyWhooshStatus()
                            } else {
                                BridgeBus.log("notify 2AD1 -> ${device.address}: ${e.message}")
                            }
                        }
                    }
                }
                // Heart Rate Measurement 0x2A37 : flags 0x00 (FC uint8) + FC en bpm.
                val hrChar = serverHeartRateChar
                if (hrChar != null && server != null && hrSubscribers.isNotEmpty()) {
                    val hrFrame = byteArrayOf(0x00, latestHr.toByte())
                    for (device in hrSubscribers.toList()) {
                        try {
                            server.notifyCharacteristicChanged(device, hrChar, false, hrFrame)
                        } catch (e: Exception) {
                            if (e is android.os.DeadObjectException) hrSubscribers.remove(device)
                        }
                    }
                }
                delay(NOTIFY_PERIOD_MS)
            }
        }
    }
}
