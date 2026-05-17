package com.bayanihan.node

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bayanihan.node.databinding.FragmentPulsoBinding
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class PulsoFragment : Fragment() {

    private var _binding: FragmentPulsoBinding? = null
    private val binding get() = _binding!!

    private val adapter = PulsoAdapter { msgId -> markDone(msgId) }

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var debounceJob: Job? = null

    // ── Lifecycle ──────────────────────────────────────────────────

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPulsoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        setStatus("connecting", R.color.akbay_araw, R.color.akbay_araw_soft)
    }

    override fun onResume() {
        super.onResume()
        startUpdateLoop()
    }

    private var updateJob: Job? = null
    private fun startUpdateLoop() {
        updateJob?.cancel()
        updateJob = lifecycleScope.launch {
            while (isActive) {
                val serverUrl = GemmaService.centralServerUrl
                if (serverUrl == null) {
                    showEmpty("Connect to a central server\nto view the message feed.\n(Service running: ${GemmaService.isRunning})")
                } else if (webSocket == null) {
                    showLoading()
                    fetchAll(serverUrl)
                    openWebSocket(serverUrl)
                }
                delay(5000)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        updateJob?.cancel()
        webSocket?.close(1000, "paused")
        webSocket = null
        reconnectJob?.cancel()
        debounceJob?.cancel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── WebSocket ──────────────────────────────────────────────────

    private fun openWebSocket(serverUrl: String) {
        webSocket?.close(1000, "reconnecting")
        val wsUrl = serverUrl.replace("http://", "ws://") + "/ws"
        webSocket = httpClient.newWebSocket(
            Request.Builder().url(wsUrl).build(),
            object : WebSocketListener() {

                override fun onOpen(ws: WebSocket, response: Response) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        setStatus("live", R.color.akbay_damay, R.color.akbay_damay_soft)
                    }
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    try {
                        val type = JSONObject(text).optString("type")
                        if (type in setOf("processing_done", "processing_failed", "processing_started")) {
                            lifecycleScope.launch {
                                debounceJob?.cancel()
                                debounceJob = launch {
                                    delay(300)
                                    fetchAll(serverUrl)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    scheduleReconnect(serverUrl, delayMs = 3_000)
                    lifecycleScope.launch(Dispatchers.Main) {
                        setStatus("reconnecting", R.color.akbay_signal, R.color.akbay_signal_soft)
                    }
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    if (code != 1000) scheduleReconnect(serverUrl, delayMs = 1_000)
                }
            }
        )
    }

    private fun scheduleReconnect(serverUrl: String, delayMs: Long) {
        reconnectJob?.cancel()
        reconnectJob = lifecycleScope.launch {
            delay(delayMs)
            if (isAdded) openWebSocket(serverUrl)
        }
    }

    // ── HTTP fetch ─────────────────────────────────────────────────

    private fun fetchAll(serverUrl: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$serverUrl/api/messages?limit=50").openConnection() as HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout = 10_000
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val messages = parseMessages(JSONObject(body))
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    adapter.update(messages)
                    if (messages.isEmpty()) showEmpty("No processed messages yet.")
                    else showContent()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    showEmpty("Could not reach server.\nReconnecting…")
                }
            }
        }
    }

    // ── Mark done ──────────────────────────────────────────────────

    private fun markDone(msgId: String) {
        val serverUrl = GemmaService.centralServerUrl ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$serverUrl/api/messages/$msgId/status").openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 5_000
                conn.outputStream.use { it.write("""{"status":"fulfilled"}""".toByteArray()) }
                val code = conn.responseCode
                conn.disconnect()
                // Server broadcasts the change — the WS event will trigger a re-fetch.
                // Optimistically remove immediately for snappy UI.
                if (code == 200) {
                    withContext(Dispatchers.Main) { adapter.removeById(msgId) }
                }
            } catch (_: Exception) {}
        }
    }

    // ── Parse ──────────────────────────────────────────────────────

    private fun parseMessages(root: JSONObject): List<PulsoAdapter.PulsoMessage> {
        val arr = root.optJSONArray("messages") ?: return emptyList()
        val result = mutableListOf<PulsoAdapter.PulsoMessage>()

        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("status") != "processed") continue
            val ed = obj.optJSONObject("extractedData") ?: continue

            val locObj = ed.optJSONObject("location")
            val location = when {
                locObj != null -> listOfNotNull(
                    locObj.optString("barangay").ifEmpty { null },
                    locObj.optString("city").ifEmpty { null },
                    locObj.optString("province").ifEmpty { null }
                ).joinToString(", ")
                else -> ed.optString("location").ifEmpty { "" }
            }

            val itemsList = buildList {
                val arr2 = ed.optJSONArray("items") ?: return@buildList
                for (j in 0 until arr2.length()) {
                    val itm = arr2.getJSONObject(j)
                    val name = itm.optString("name").ifEmpty { itm.optString("canonical") }
                    val qty = itm.optString("quantity").ifEmpty { itm.optString("count") }
                    val unit = itm.optString("unit")
                    val displayQty = if (qty.isNotEmpty()) "$qty $unit".trim() else "?"
                    add(PulsoAdapter.PulsoItem(name, displayQty))
                }
            }

            result.add(
                PulsoAdapter.PulsoMessage(
                    id        = obj.optString("id"),
                    source    = obj.optString("source", "unknown"),
                    content   = obj.optString("content"),
                    time      = obj.optString("time"),
                    type      = obj.optString("type", "sms"),
                    urgency   = ed.optString("urgency", "low"),
                    location  = location,
                    persons   = ed.optInt("persons", 0),
                    itemsList = itemsList,
                    medical   = ed.optString("medical").ifEmpty { null }
                )
            )
        }
        return result.sortedByDescending { it.time }
    }

    // ── UI state ───────────────────────────────────────────────────

    private fun showLoading() {
        if (_binding == null) return
        binding.emptyState.visibility = View.GONE
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.contentState.visibility = View.GONE
    }

    private fun showEmpty(message: String) {
        if (_binding == null) return
        binding.tvEmptyMessage.text = message
        binding.emptyState.visibility = View.VISIBLE
        binding.loadingIndicator.visibility = View.GONE
        binding.contentState.visibility = View.GONE
    }

    private fun showContent() {
        if (_binding == null) return
        binding.emptyState.visibility = View.GONE
        binding.loadingIndicator.visibility = View.GONE
        binding.contentState.visibility = View.VISIBLE
    }

    private fun setStatus(label: String, mainColorRes: Int, softColorRes: Int) {
        if (_binding == null) return
        val mainColor = ContextCompat.getColor(requireContext(), mainColorRes)
        val softColor = ContextCompat.getColor(requireContext(), softColorRes)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(softColor)
        }
        binding.tvConnectionStatus.background = bg
        binding.tvConnectionStatus.setTextColor(mainColor)
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
            binding.tvConnectionStatus,
            android.content.res.ColorStateList.valueOf(mainColor)
        )
        binding.tvConnectionStatus.text = label
    }
}
