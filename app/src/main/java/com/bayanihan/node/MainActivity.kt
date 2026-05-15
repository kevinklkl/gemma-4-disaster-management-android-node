package com.bayanihan.node

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bayanihan.node.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.*
import java.text.DecimalFormat

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var monitorJob: Job? = null

    companion object {
        private const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        private const val MIN_VALID_SIZE = 1_000_000_000L // 1 GB minimum
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            // User denied permission. The app may not be able to show foreground service notifications.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        createDownloadChannel()
        askNotificationPermission()
        checkRam()
        refreshModelStatus()
        refreshServiceStatus()

        binding.btnDownload.setOnClickListener { startDownload() }
        binding.btnStartStop.setOnClickListener { toggleService() }
        binding.btnDelete.setOnClickListener {
            if (GemmaService.isRunning) {
                Toast.makeText(this, "Stop the node before deleting the model", Toast.LENGTH_SHORT).show()
            } else {
                deleteModels()
                refreshModelStatus()
            }
        }
    }

    private fun deleteModels() {
        val dir = getExternalFilesDir(null) ?: return
        dir.listFiles()?.forEach { file ->
            val name = file.name.lowercase()
            if (name.endsWith(".bin") || name.endsWith(".gguf") || 
                name.endsWith(".task") || name.endsWith(".litertlm") || name.endsWith(".tmp")) {
                file.delete()
            }
        }
        Toast.makeText(this, "All model files deleted", Toast.LENGTH_SHORT).show()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshModelStatus()
        refreshServiceStatus()
        if (ModelDownloadService.isRunning) {
            monitorDownloadProgress()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── RAM check ─────────────────────────────────────────────────────────────

    private fun checkRam() {
        val am = getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val gb = mi.totalMem / (1024.0 * 1024.0 * 1024.0)
        if (gb < 4.0) {
            binding.tvRamWarning.visibility = View.VISIBLE
            binding.tvRamWarningText.text =
                "Warning: only ${DecimalFormat("#.#").format(gb)} GB RAM detected. " +
                "At least 4 GB is recommended — the app may be slow or crash."
        }
    }

    private fun createDownloadChannel() {
        val ch = NotificationChannel(ModelDownloadService.CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    // ── model status ──────────────────────────────────────────────────────────

    private fun modelFile() = File(getExternalFilesDir(null), MODEL_FILENAME)

    private fun refreshModelStatus() {
        val f = modelFile()
        val ready = f.exists() && f.length() > MIN_VALID_SIZE
        if (ready) {
            val gb = DecimalFormat("#.##").format(f.length() / 1_000_000_000.0)
            binding.tvModelStatus.text = "Model: ready ($gb GB)"
            binding.btnDownload.visibility = View.GONE
            binding.btnDelete.visibility = View.VISIBLE
        } else {
            binding.tvModelStatus.text = "Model: not downloaded"
            binding.btnDownload.visibility = View.VISIBLE
            binding.btnDelete.visibility = View.GONE
        }
        binding.btnStartStop.isEnabled = ready
    }

    // ── service controls ─────────────────────────────────────────────────────

    private fun refreshServiceStatus() {
        if (GemmaService.isRunning) {
            binding.btnStartStop.text = "Stop Node"
            binding.tvServiceStatus.text = "Status: Running on :11434"
            binding.tvIpAddress.text = "LAN address: ${getLocalIp()}:11434"
        } else {
            binding.btnStartStop.text = "Start Node"
            binding.tvServiceStatus.text = "Status: Stopped"
            binding.tvIpAddress.text = ""
        }
    }

    private fun toggleService() {
        if (GemmaService.isRunning) {
            startService(Intent(this, GemmaService::class.java).apply {
                action = GemmaService.ACTION_STOP
            })
        } else {
            startForegroundService(Intent(this, GemmaService::class.java))
        }
        Handler(Looper.getMainLooper()).postDelayed({ refreshServiceStatus() }, 600)
    }

    // ── download ──────────────────────────────────────────────────────────────

    private fun startDownload() {
        val intent = Intent(this, ModelDownloadService::class.java).apply {
            putExtra("url", MODEL_URL)
            putExtra("path", modelFile().absolutePath)
        }
        startForegroundService(intent)
        monitorDownloadProgress()
    }

    private fun monitorDownloadProgress() {
        monitorJob?.cancel()
        binding.btnDownload.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        
        monitorJob = scope.launch {
            while (ModelDownloadService.isRunning) {
                binding.progressBar.isIndeterminate = ModelDownloadService.progress == 0
                binding.progressBar.progress = ModelDownloadService.progress
                binding.tvModelStatus.text = "Downloading: ${ModelDownloadService.downloadedMb} / ${ModelDownloadService.totalMb} MB"
                delay(1000)
            }
            
            if (ModelDownloadService.error != null) {
                binding.tvModelStatus.text = "Download failed: ${ModelDownloadService.error}"
                binding.btnDownload.isEnabled = true
            } else {
                refreshModelStatus()
            }
            binding.progressBar.visibility = View.GONE
        }
    }

    // (Old manual download functions removed)

    // ── helpers ───────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun getLocalIp(): String {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo.ipAddress
        return if (ip == 0) "check WiFi" else Formatter.formatIpAddress(ip)
    }
}
