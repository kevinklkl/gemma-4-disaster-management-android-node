package com.bayanihan.node

import android.app.*
import android.content.*
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class GemmaService : Service() {

    private var server: OllamaServer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var thermalReceiver: BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val SERVICE_TYPE = "_http._tcp."
    private val TARGET_SERVICE_NAME = "GemmaHost"

    enum class NodeState {
        STOPPED, LOADING, RUNNING
    }

    companion object {
        private const val TAG = "GemmaService"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "bayanihan_node"
        const val ACTION_STOP = "com.bayanihan.node.STOP"
        const val ACTION_START_NODE = "com.bayanihan.node.START_NODE"
        const val ACTION_STOP_NODE = "com.bayanihan.node.STOP_NODE"
        const val ACTION_DISCOVER_ONLY = "com.bayanihan.node.DISCOVER_ONLY"
        
        @Volatile var isRunning = false
        private val _nodeState = MutableStateFlow(NodeState.STOPPED)
        val nodeState: StateFlow<NodeState> = _nodeState

        @Volatile var isNodeActive = false
        @Volatile var isNodeLoading = false
        @Volatile var centralServerUrl: String? = null
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BayanihanNode:inference")
        @Suppress("DEPRECATION")
        wifiLock = (getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BayanihanNode:wifi")
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_NODE -> {
                stopNodeLogic()
            }
            ACTION_START_NODE -> {
                startNodeLogic()
            }
        }

        if (!isRunning) {
            isRunning = true
            startForeground(NOTIFICATION_ID, buildNotification("Ready..."))
            registerThermal()
            discoverCentralServer()
        }

        return START_STICKY
    }

    private fun startNodeLogic() {
        if (isNodeActive || isNodeLoading) return
        
        val modelPath = getExternalFilesDir(null)?.absolutePath + "/gemma-4-E2B-it.litertlm"
        val file = File(modelPath)
        if (!file.exists()) {
            Log.w(TAG, "startNodeLogic: Model file NOT FOUND at $modelPath")
            return
        }

        isNodeLoading = true
        _nodeState.value = NodeState.LOADING
        
        // If we already discovered the server, register now
        centralServerUrl?.let { url ->
            try {
                val uri = java.net.URI(url)
                registerWithCentralServer(uri.host, uri.port)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register on startNodeLogic: ${e.message}")
            }
        }

        // Start server immediately
        try {
            if (server == null) {
                server = OllamaServer(11434).also { it.start() }
                Log.i(TAG, "Ollama Server started on :11434")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server: ${e.message}")
            notify("Server Error: ${e.message}")
        }

        scope.launch {
            Log.i(TAG, "Model file: ${file.absolutePath}, size: ${file.length()} bytes")
            notify("Loading model (this takes ~1 min)…")

            val startTime = System.currentTimeMillis()
            val ok = try {
                GemmaEngine.load(this@GemmaService, modelPath)
            } catch (e: Exception) {
                Log.e(TAG, "Critical load error: ${e.message}")
                false
            }
            val duration = System.currentTimeMillis() - startTime

            isNodeLoading = false
            if (!ok) {
                _nodeState.value = NodeState.STOPPED
                Log.e(TAG, "GemmaEngine failed to load after ${duration}ms")
                notify("Failed to load model — check logs")
                return@launch
            }

            Log.i(TAG, "Model loaded successfully in ${duration}ms.")
            isNodeActive = true
            _nodeState.value = NodeState.RUNNING
            wakeLock?.acquire(4 * 60 * 60 * 1000L)
            wifiLock?.acquire()
            notify("Ready — serving on :11434")
        }
    }

    private fun stopNodeLogic() {
        isNodeActive = false
        isNodeLoading = false
        _nodeState.value = NodeState.STOPPED
        deregisterFromCentralServer()
        server?.stop()
        server = null
        GemmaEngine.unload()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        notify("Node stopped")
    }

    override fun onDestroy() {
        isRunning = false
        isNodeActive = false
        _nodeState.value = NodeState.STOPPED
        deregisterFromCentralServer()
        stopDiscovery()
        scope.cancel()
        server?.stop()
        server = null
        GemmaEngine.unload()
        thermalReceiver?.let { unregisterReceiver(it) }
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerThermal() {
        if (thermalReceiver != null) return
        thermalReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
                // Android reports temperature in tenths of a degree
                val tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
                NodeStats.setTemp(tempC)
                // Lower threshold to 41°C to stay ahead of aggressive system-level GPU throttling
                val throttle = tempC >= 41.0
                server?.thermalThrottle = throttle
                val status = when {
                    throttle -> "Cooling down (${tempC.toInt()}°C) — requests paused"
                    GemmaEngine.isLoaded -> "Ready — serving on :11434"
                    isNodeLoading -> "Loading model…"
                    else -> "Ready"
                }
                notify(status)
            }
        }
        registerReceiver(thermalReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Bayanihan Node", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(status: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, GemmaService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bayanihan Node")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
            .setOngoing(true)
            .build()
    }

    private fun notify(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun discoverCentralServer() {
        stopDiscovery() // clean start
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "NSD Discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${service.serviceName} type: ${service.serviceType}")
                val nameMatch = service.serviceName.contains(TARGET_SERVICE_NAME, ignoreCase = true)
                val typeMatch = service.serviceType.contains(SERVICE_TYPE)
                
                if (nameMatch && typeMatch) {
                    Log.i(TAG, "Found matching central server: ${service.serviceName}. Resolving...")
                    nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val host = serviceInfo.host?.hostAddress
                            val port = serviceInfo.port
                            if (host != null) {
                                val url = "http://$host:$port"
                                Log.i(TAG, "Resolved central server: $url")
                                centralServerUrl = url
                                
                                // Only register as a compute node if we are actually active or loading
                                if (isNodeActive || isNodeLoading) {
                                    registerWithCentralServer(host, port)
                                }
                            } else {
                                Log.w(TAG, "Resolved service but host is null: ${serviceInfo.serviceName}")
                            }
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${service.serviceName}")
                if (service.serviceName.contains(TARGET_SERVICE_NAME, ignoreCase = true)) {
                    centralServerUrl = null
                    // If we lost the server, we should probably start discovering again
                    discoverCentralServer()
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped.")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed: $errorCode")
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
                stopDiscovery()
            }
        }

        nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager?.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping NSD: ${e.message}")
            }
        }
        discoveryListener = null
    }

    private fun deregisterFromCentralServer() {
        val baseUrl = centralServerUrl ?: return
        val url = "$baseUrl/api/nodes/me"
        // Use IO dispatcher and a short-lived scope to ensure the request is sent during shutdown
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i(TAG, "Deregistering from: $url")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "DELETE"
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                val code = conn.responseCode
                Log.i(TAG, "Deregistration response code: $code")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to deregister: ${e.message}")
            }
        }
    }

    private fun registerWithCentralServer(host: String, port: Int) {
        scope.launch {
            try {
                val url = "http://$host:$port/api/nodes"
                Log.i(TAG, "Registering with: $url")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                val json = JSONObject().apply {
                    put("name", Build.MODEL)
                    put("prefix_cached", false) // Ensure central server sends full prompt for internal splitting
                }.toString()
                conn.outputStream.use { it.write(json.toByteArray()) }

                val code = conn.responseCode
                if (code == 200) {
                    val respText = conn.inputStream.bufferedReader().use { it.readText() }
                    Log.i(TAG, "Successfully registered with central server. Response: $respText")
                    
                    try {
                        val respJson = JSONObject(respText)
                        if (respJson.has("cache_prompt")) {
                            val cachePrompt = respJson.getString("cache_prompt")
                            // Point #2: Correct tokens
                            val formattedCache = "<start_of_turn>system\n${cachePrompt.trim()}<end_of_turn>\n"
                            Log.i(TAG, "Received cache_prompt (len: ${cachePrompt.length}). Warm-loading engine...")
                            // Point #4: Standard tokens for pre-warm
                            GemmaEngine.generateWithSystem(formattedCache, "<start_of_turn>user\nHello<end_of_turn>\n<start_of_turn>model\n", temperature = 0.1f)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse registration response: ${e.message}")
                    }
                } else {
                    Log.w(TAG, "Registration failed with code: $code")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register with central server: ${e.message}")
            }
        }
    }
}
