package com.akbay.node

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.akbay.node.databinding.FragmentNodeBinding
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet4Address
import java.text.DecimalFormat

class NodeFragment : Fragment() {

    interface Listener {
        fun onResetRequested()
    }

    private var _binding: FragmentNodeBinding? = null
    private val binding get() = _binding!!
    private var listener: Listener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? Listener
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNodeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        checkRam()
        observeStats()
        observeNodeState()

        binding.btnStartStop.setOnClickListener { toggleService() }
        binding.btnDelete.setOnClickListener {
            if (GemmaService.isRunning) {
                Toast.makeText(requireContext(), "Stop the node before resetting", Toast.LENGTH_SHORT).show()
            } else {
                showDeleteConfirmation()
            }
        }
    }

    private fun showDeleteConfirmation() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Reset Model Data?")
            .setMessage("Deleting the model means you'll have to download it again (~2.6GB). This is not recommended unless you're having issues.")
            .setPositiveButton("Delete Everything") { _, _ ->
                deleteModels()
                listener?.onResetRequested()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshServiceStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun checkRam() {
        val am = requireContext().getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val gb = mi.totalMem / (1024.0 * 1024.0 * 1024.0)
        if (gb < 4.0) {
            binding.cardRamWarning.visibility = View.VISIBLE
            binding.tvRamWarningText.text =
                "Warning: only ${DecimalFormat("#.#").format(gb)} GB RAM detected. " +
                "At least 4 GB is recommended — performance may be poor."
        }
    }

    private fun observeStats() {
        lifecycleScope.launch {
            NodeStats.processedRequests.collect { binding.tvStatsProcessed.text = it.toString() }
        }
        lifecycleScope.launch {
            NodeStats.failedRequests.collect { binding.tvStatsFailed.text = it.toString() }
        }
        lifecycleScope.launch {
            NodeStats.tokensProcessed.collect { binding.tvStatsTokens.text = it.toString() }
        }
        lifecycleScope.launch {
            NodeStats.deviceTemp.collect {
                binding.tvStatsTemp.text = if (it == null) "--" else "${it.toInt()}°C"
            }
        }
    }

    private fun observeNodeState() {
        lifecycleScope.launch {
            GemmaService.nodeState.collect {
                refreshServiceStatus()
            }
        }
    }

    fun refreshServiceStatus() {
        if (_binding == null) return
        val context = requireContext()
        when (GemmaService.nodeState.value) {
            GemmaService.NodeState.RUNNING -> {
                binding.btnStartStop.isEnabled = true
                binding.btnStartStop.text = "Stop Node"
                binding.btnStartStop.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.akbay_paper))
                binding.tvServiceStatus.text = "Status: Running on :11434"
                binding.tvIpAddress.text = "LAN address: ${getLocalIp()}:11434"
            }
            GemmaService.NodeState.LOADING -> {
                binding.btnStartStop.isEnabled = false
                binding.btnStartStop.text = "Loading..."
                // Force white/paper text even when disabled
                binding.btnStartStop.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.akbay_paper))
                binding.tvServiceStatus.text = "Status: Initializing model..."
                binding.tvIpAddress.text = "Please wait ~1 minute"
            }
            GemmaService.NodeState.STOPPED -> {
                binding.btnStartStop.isEnabled = true
                binding.btnStartStop.text = "Start Node"
                binding.btnStartStop.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.akbay_paper))
                binding.tvServiceStatus.text = "Status: Stopped"
                binding.tvIpAddress.text = "Click start to begin serving"
            }
        }
    }

    private fun toggleService() {
        if (GemmaService.isNodeActive || GemmaService.isNodeLoading) {
            requireContext().startService(
                Intent(requireContext(), GemmaService::class.java).apply { action = GemmaService.ACTION_STOP_NODE }
            )
        } else {
            requireContext().startForegroundService(
                Intent(requireContext(), GemmaService::class.java).apply { action = GemmaService.ACTION_START_NODE }
            )
        }
        // Small delay to allow the service state to update
        Handler(Looper.getMainLooper()).postDelayed({ 
            if (isAdded) refreshServiceStatus() 
        }, 500)
    }

    private fun deleteModels() {
        val dir = requireActivity().getExternalFilesDir(null) ?: return
        dir.listFiles()?.forEach { file ->
            val name = file.name.lowercase()
            if (name.endsWith(".bin") || name.endsWith(".gguf") ||
                name.endsWith(".task") || name.endsWith(".litertlm") || name.endsWith(".tmp")) {
                file.delete()
            }
        }
        Toast.makeText(requireContext(), "All model files deleted", Toast.LENGTH_SHORT).show()
    }

    private fun getLocalIp(): String {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "check connection"
        val lp = cm.getLinkProperties(network) ?: return "check connection"
        for (addr in lp.linkAddresses) {
            val inet = addr.address
            if (!inet.isLoopbackAddress && inet is Inet4Address) {
                return inet.hostAddress ?: ""
            }
        }
        return "check connection"
    }
}
