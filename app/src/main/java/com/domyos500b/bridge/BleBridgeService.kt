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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
 * - Côté client : scan 0x1826, connexion TRANSPORT_LE, notifications 0x2AD1,
 *   indications 0x2AD9, puis Control Point 0x00 (Request Control) + 0x07 (Start/Resume).
 *   Toutes les opérations GATT passent par [GattCommandQueue] (sérialisées + timeout).
 * - Côté serveur : service FTMS exposé sous le nom "Domyos500B-Bridge", trame 0x2AD1
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
        private const val NOTIFY_PERIOD_MS = 250L   // 4 Hz
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val MTU_REQUEST = 247
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

    /** Centraux (MyWhoosh) ayant activé les notifications sur 0x2AD1 (accès multi-threads). */
    private val subscribers = java.util.Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())

    @Volatile
    private var latestFrame: ByteArray = FtmsParser.idleFrame()

    @Volatile
    private var running = false

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
        scope.cancel()
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        subscribers.clear()
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
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.FTMS_SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
            BridgeBus.log("Scan BLE démarré (service 0x1826)")
        } catch (se: SecurityException) {
            BridgeBus.log("Scan refusé: ${se.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (gatt != null) return // déjà connecté / connexion en cours
            val device = result.device
            val name = try { device.name } catch (_: SecurityException) { null }
            if (name != null && !name.contains("Domyos", ignoreCase = true) &&
                result.scanRecord?.serviceUuids?.none { it.uuid == BleConstants.FTMS_SERVICE } == true
            ) return
            BridgeBus.log("Scan: ${name ?: "?"} ${device.address} RSSI=${result.rssi}")
            connectToRower(device)
        }

        override fun onScanFailed(errorCode: Int) {
            BridgeBus.log("Scan échoué, code=$errorCode")
        }
    }

    private fun connectToRower(device: BluetoothDevice) {
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        BridgeBus.log("Connexion GATT -> ${device.address} (TRANSPORT_LE)")
        try {
            gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (se: SecurityException) {
            BridgeBus.log("connectGatt refusé: ${se.message}")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                BridgeBus.setDomyosConnected(true)
                BridgeBus.log("Domyos CONNECTÉ (status=$status)")
                scope.launch {
                    try {
                        queue.enqueue { awaitOp("requestMtu") { g.requestMtu(MTU_REQUEST) } }
                        val discovered = queue.enqueue { awaitOp("discoverServices") { g.discoverServices() } }
                        if (!discovered) throw Exception("découverte des services échouée")
                        setupFtmSession(g)
                    } catch (e: Exception) {
                        BridgeBus.log("Session FTMS: ${e.message}")
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                BridgeBus.setDomyosConnected(false)
                BridgeBus.log("Domyos DÉCONNECTÉ (status=$status) -> reconnexion auto dans ${RECONNECT_DELAY_MS} ms")
                resumePending(false)
                try { g.close() } catch (_: Exception) {}
                if (gatt === g) gatt = null
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
                if (m != null) {
                    BridgeBus.publish(m)
                    latestFrame = FtmsParser.buildNormalizedFrame(m)
                    BridgeBus.log(
                        "   parse: ${m.strokeRateSpm} spm | ${m.strokeCount} coups | " +
                            "${m.distanceMeters} m | pace=${m.paceSecPer500m}s/500m | ${m.powerWatts} W"
                    )
                }
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

    /** Active notifications 0x2AD1 + indications 0x2AD9, puis réveille le rameur (0x00 puis 0x07). */
    private suspend fun setupFtmSession(g: BluetoothGatt) {
        val service = g.services?.firstOrNull { it.uuid == BleConstants.FTMS_SERVICE }
            ?: throw Exception("service FTMS 0x1826 introuvable sur le rameur")
        val rower = service.getCharacteristic(BleConstants.ROWER_DATA)
            ?: throw Exception("caractéristique 0x2AD1 absente")
        val cp = service.getCharacteristic(BleConstants.FTMS_CONTROL_POINT)
            ?: throw Exception("caractéristique 0x2AD9 absente")

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
        BridgeBus.log("GATT server FTMS exposé (2ACC / 2AD1 / 2AD3 / 2ADA / 2AD9)")
    }

    private val serverCallback = object : BluetoothGattServerCallback() {

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
                if (opcode == BleConstants.OP_REQUEST_CONTROL || opcode == BleConstants.OP_START_OR_RESUME) {
                    scope.launch { sendControlPointToRower(byteArrayOf(opcode)) }
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
            if (descriptor.uuid == BleConstants.CCCD &&
                descriptor.characteristic.uuid == BleConstants.ROWER_DATA
            ) {
                val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                if (enabled) subscribers.add(device) else subscribers.remove(device)
                refreshMyWhooshStatus()
                BridgeBus.log("CCCD 0x2AD1 ${if (enabled) "ENABLE" else "DISABLE"} par ${device.address}")
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    private fun refreshMyWhooshStatus() {
        BridgeBus.setMyWhooshConnected(subscribers.isNotEmpty())
    }

    private fun startAdvertising() {
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
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0) // illimité
            .build()
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false) // le nom passe dans la scan response (limite 31 octets)
            .addServiceUuid(ParcelUuid(BleConstants.FTMS_SERVICE))
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
            BridgeBus.log("Advertising démarré: \"${BleConstants.DEVICE_NAME}\" (service 0x1826, LOW_LATENCY / TX_HIGH)")
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
                            BridgeBus.log("notify 2AD1 -> ${device.address}: ${e.message}")
                        }
                    }
                }
                delay(NOTIFY_PERIOD_MS)
            }
        }
    }
}
