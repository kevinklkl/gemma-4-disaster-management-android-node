package com.bayanihan.node

import android.app.*
import android.content.*
import android.net.wifi.WifiManager
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class LlamaService : Service() {

    private var server: OllamaServer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var thermalReceiver: BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "bayanihan_node"
        const val ACTION_STOP = "com.bayanihan.node.STOP"
        @Volatile var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BayanihanNode:inference")
        @Suppress("DEPRECATION")
        wifiLock = (getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BayanihanNode:wifi")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }

        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
        isRunning = true
        registerThermal()

        scope.launch {
            val modelFile = getExternalFilesDir(null)?.absolutePath + "/gemma-4-E2B-it-Q5_K_S.gguf"
            notify("Loading model…")

            val ok = LlamaEngine.load(modelFile, nGpuLayers = 0)
            if (!ok) {
                notify("Failed to load model — check file exists")
                stopSelf()
                return@launch
            }

            server = OllamaServer(11434).also { it.start() }
            wakeLock?.acquire(4 * 60 * 60 * 1000L)
            wifiLock?.acquire()
            notify("Ready — serving on :11434")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        server?.stop()
        LlamaEngine.unload()
        thermalReceiver?.let { unregisterReceiver(it) }
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerThermal() {
        thermalReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
                // Android reports temperature in tenths of a degree
                val tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
                val throttle = tempC >= 45.0
                server?.thermalThrottle = throttle
                val status = when {
                    throttle -> "Cooling down (${tempC.toInt()}°C) — requests paused"
                    LlamaEngine.isLoaded -> "Ready — serving on :11434"
                    else -> "Loading…"
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
            Intent(this, LlamaService::class.java).apply { action = ACTION_STOP },
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
}
