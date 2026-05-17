package com.bayanihan.node

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.bayanihan.node.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.File
import java.text.DecimalFormat

class MainActivity : AppCompatActivity(), NodeFragment.Listener {

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var monitorJob: Job? = null
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        private const val MIN_VALID_SIZE = 1_000_000_000L
        private const val PREF_SETUP_COMPLETE = "setup_complete"
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "Notification permission denied.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences("bayanihan", Context.MODE_PRIVATE)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }

        createDownloadChannel()
        askNotificationPermission()
        setupInitialStep()
        
        // Start discovery service automatically so Pulso works even if node is stopped
        startForegroundService(Intent(this, GemmaService::class.java).apply { 
            action = GemmaService.ACTION_DISCOVER_ONLY 
        })

        binding.btnNextStep1.setOnClickListener {
            binding.viewFlipper.displayedChild = 1
            binding.tvStepIndicator.text = "Step 2/3"
        }
        binding.btnStartDownload.setOnClickListener { startDownload() }

        binding.bottomNav.apply {
            itemIconTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.nav_item_tint)
            itemTextColor = ContextCompat.getColorStateList(this@MainActivity, R.color.nav_item_tint)
            setItemActiveIndicatorColor(ColorStateList.valueOf(
                ContextCompat.getColor(this@MainActivity, R.color.akbay_nav_indicator)
            ))
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_node   -> { showFragment("node"); true }
                R.id.nav_pulso  -> { showFragment("pulso"); true }
                else -> false
            }
        }
    }

    // ── NodeFragment.Listener ──────────────────────────────────────
    override fun onResetRequested() {
        prefs.edit().putBoolean(PREF_SETUP_COMPLETE, false).apply()
        setupInitialStep()
    }

    // ── Navigation ─────────────────────────────────────────────────
    private fun showFragment(tag: String) {
        val tx = supportFragmentManager.beginTransaction()
        
        // Hide all current fragments
        supportFragmentManager.fragments.forEach { tx.hide(it) }
        
        val existing = supportFragmentManager.findFragmentByTag(tag)
        if (existing != null) {
            tx.show(existing)
        } else {
            val fragment = when (tag) {
                "node"  -> NodeFragment()
                "pulso" -> PulsoFragment()
                else    -> return
            }
            tx.add(R.id.fragmentContainer, fragment, tag)
        }
        tx.commit()
    }

    private fun showMainApp() {
        binding.onboardingContainer.visibility = View.GONE
        binding.appContainer.visibility = View.VISIBLE
        binding.tvStepIndicator.visibility = View.GONE
        
        // Ensure the initial fragment is loaded
        if (supportFragmentManager.findFragmentById(R.id.fragmentContainer) == null) {
            showFragment("node")
            binding.bottomNav.selectedItemId = R.id.nav_node
        }
    }

    private fun showOnboarding() {
        binding.onboardingContainer.visibility = View.VISIBLE
        binding.appContainer.visibility = View.GONE
        binding.tvStepIndicator.visibility = View.VISIBLE
    }

    // ── Setup flow ─────────────────────────────────────────────────
    private fun setupInitialStep() {
        val f = modelFile()
        val modelReady = f.exists() && f.length() > MIN_VALID_SIZE

        if (modelReady) {
            showMainApp()
        } else if (ModelDownloadService.isRunning) {
            showOnboarding()
            binding.viewFlipper.displayedChild = 1
            binding.tvStepIndicator.text = "Step 2/3"
            monitorDownloadProgress()
        } else {
            showOnboarding()
            binding.viewFlipper.displayedChild = 0
            binding.tvStepIndicator.text = "Step 1/3"
        }
    }

    override fun onResume() {
        super.onResume()
        if (ModelDownloadService.isRunning) monitorDownloadProgress()
        // Refresh node status if node fragment is visible
        (supportFragmentManager.findFragmentByTag("node") as? NodeFragment)?.refreshServiceStatus()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── Download ───────────────────────────────────────────────────
    private fun startDownload() {
        val intent = Intent(this, ModelDownloadService::class.java).apply {
            putExtra("url", MODEL_URL)
            putExtra("path", modelFile().absolutePath)
        }
        startForegroundService(intent)
        monitorDownloadProgress()
        prefs.edit().putBoolean(PREF_SETUP_COMPLETE, true).apply()
    }

    private fun monitorDownloadProgress() {
        if (monitorJob?.isActive == true) return

        binding.btnStartDownload.visibility = View.GONE
        binding.cardDownloadProgress.visibility = View.VISIBLE

        monitorJob = scope.launch {
            var waitCount = 0
            while (!ModelDownloadService.isRunning && waitCount < 5) {
                delay(500); waitCount++
            }

            val downloadStartTime = System.currentTimeMillis()
            var lastCheckpointMb = 0
            var lastCheckpointTime = downloadStartTime
            var remainingSeconds = -1

            while (ModelDownloadService.isRunning) {
                binding.downloadProgress.isIndeterminate = ModelDownloadService.progress == 0
                binding.downloadProgress.progress = ModelDownloadService.progress
                binding.tvDownloadStatus.text = "${ModelDownloadService.downloadedMb} / ${ModelDownloadService.totalMb} MB"

                val currentMb = ModelDownloadService.downloadedMb
                val now = System.currentTimeMillis()
                if ((currentMb - lastCheckpointMb >= 300) || (lastCheckpointMb == 0 && currentMb >= 50)) {
                    val diffMb = currentMb - lastCheckpointMb
                    val diffMs = now - lastCheckpointTime
                    if (diffMs > 1000) {
                        val speed = diffMb.toDouble() / (diffMs / 1000.0)
                        val remaining = ModelDownloadService.totalMb - currentMb
                        if (speed > 0.1) remainingSeconds = (remaining / speed).toInt()
                    }
                    lastCheckpointMb = currentMb
                    lastCheckpointTime = now
                } else if (remainingSeconds > 0) {
                    remainingSeconds--
                }

                binding.tvTimeRemaining.text = if (remainingSeconds > 0) {
                    val m = remainingSeconds / 60; val s = remainingSeconds % 60
                    if (m > 0) "${m}m ${s}s remaining" else "${s}s remaining"
                } else "Estimating..."

                delay(1000)
            }

            if (ModelDownloadService.error != null) {
                binding.tvDownloadStatus.text = "Error: ${ModelDownloadService.error}"
                binding.btnStartDownload.visibility = View.VISIBLE
                binding.btnStartDownload.text = "Retry Download"
                binding.cardDownloadProgress.visibility = View.GONE
            } else {
                val f = modelFile()
                if (f.exists() && f.length() > MIN_VALID_SIZE) {
                    showMainApp()
                    binding.cardDownloadProgress.visibility = View.GONE
                } else {
                    binding.btnStartDownload.visibility = View.VISIBLE
                    binding.cardDownloadProgress.visibility = View.GONE
                }
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────
    private fun modelFile() = File(getExternalFilesDir(null), MODEL_FILENAME)

    private fun createDownloadChannel() {
        val ch = NotificationChannel(ModelDownloadService.CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
