package com.bayanihan.node

import android.app.ActivityManager
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.bayanihan.node.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val MODEL_FILENAME = "gemma-4-E2B-it-Q5_K_S.gguf"
        private const val MODEL_URL =
            "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/$MODEL_FILENAME"
        private const val MIN_VALID_SIZE = 500_000_000L // 500 MB sanity check
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkRam()
        refreshModelStatus()
        refreshServiceStatus()

        binding.btnDownload.setOnClickListener { startDownload() }
        binding.btnStartStop.setOnClickListener { toggleService() }
    }

    override fun onResume() {
        super.onResume()
        refreshServiceStatus()
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

    // ── model status ──────────────────────────────────────────────────────────

    private fun modelFile() = File(getExternalFilesDir(null), MODEL_FILENAME)

    private fun refreshModelStatus() {
        val f = modelFile()
        val ready = f.exists() && f.length() > MIN_VALID_SIZE
        if (ready) {
            val gb = DecimalFormat("#.##").format(f.length() / 1_000_000_000.0)
            binding.tvModelStatus.text = "Model: ready ($gb GB)"
            binding.btnDownload.visibility = View.GONE
        } else {
            binding.tvModelStatus.text = "Model: not downloaded"
            binding.btnDownload.visibility = View.VISIBLE
        }
        binding.btnStartStop.isEnabled = ready
    }

    // ── service controls ─────────────────────────────────────────────────────

    private fun refreshServiceStatus() {
        if (LlamaService.isRunning) {
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
        if (LlamaService.isRunning) {
            startService(Intent(this, LlamaService::class.java).apply {
                action = LlamaService.ACTION_STOP
            })
        } else {
            startForegroundService(Intent(this, LlamaService::class.java))
        }
        Handler(Looper.getMainLooper()).postDelayed({ refreshServiceStatus() }, 600)
    }

    // ── download ──────────────────────────────────────────────────────────────

    private fun startDownload() {
        binding.btnDownload.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = false

        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    downloadModel { progress, dlMb, totalMb ->
                        launch(Dispatchers.Main) {
                            binding.progressBar.progress = progress
                            binding.tvModelStatus.text = "Downloading: $dlMb / $totalMb MB"
                        }
                    }
                    launch(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        refreshModelStatus()
                    }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) {
                        binding.tvModelStatus.text = "Download failed: ${e.message}"
                        binding.btnDownload.isEnabled = true
                        binding.progressBar.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun downloadModel(onProgress: (progress: Int, dlMb: Int, totalMb: Int) -> Unit) {
        val dest = modelFile()
        val tmp  = File(dest.parent, "${dest.name}.tmp")
        val existingBytes = if (tmp.exists()) tmp.length() else 0L

        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout    = 60_000
        if (existingBytes > 0) conn.setRequestProperty("Range", "bytes=$existingBytes-")
        conn.connect()

        val remoteLen  = conn.contentLengthLong
        val totalBytes = if (existingBytes > 0 && remoteLen > 0) existingBytes + remoteLen else remoteLen

        FileOutputStream(tmp, existingBytes > 0).use { out ->
            conn.inputStream.use { input ->
                val buf = ByteArray(128 * 1024)
                var downloaded = existingBytes
                var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    out.write(buf, 0, n)
                    downloaded += n
                    val pct = if (totalBytes > 0) ((downloaded * 100) / totalBytes).toInt() else 0
                    onProgress(pct, (downloaded / 1_000_000).toInt(), (totalBytes / 1_000_000).toInt())
                }
            }
        }
        tmp.renameTo(dest)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun getLocalIp(): String {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo.ipAddress
        return if (ip == 0) "check WiFi" else Formatter.formatIpAddress(ip)
    }
}
