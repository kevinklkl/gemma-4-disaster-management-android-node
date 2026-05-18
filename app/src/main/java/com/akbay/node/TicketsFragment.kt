package com.akbay.node

import android.content.Context
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.akbay.node.databinding.FragmentTicketsBinding
import com.akbay.node.databinding.ItemExtractedRowBinding
import com.akbay.node.databinding.ItemTicketCardBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class TicketsFragment : Fragment() {

    private var _binding: FragmentTicketsBinding? = null
    private val binding get() = _binding!!

    private val prefs by lazy {
        requireActivity().getSharedPreferences("akbay", Context.MODE_PRIVATE)
    }

    private val token get() = prefs.getString("tickets_token", null)
    private val savedUsername get() = prefs.getString("tickets_username", null)

    // Prefer live discovered URL, fall back to last used URL stored at login
    private val serverUrl: String?
        get() = GemmaService.centralServerUrl ?: prefs.getString("tickets_server_url", null)

    private var currentTab = "my-tickets"
    private var available = true
    private var queueOpen = false

    private var myTickets = listOf<TicketItem>()
    private var archivedTickets = listOf<TicketItem>()

    private var activeInboxFilter = "all"
    private var inboxSearchQuery = ""
    private var inboxSearchJob: Job? = null
    private var inboxOffset = 0
    private var inboxTotal = 0
    private val INBOX_PAGE = 20

    // JSONObject.optString returns the literal "null" string for JSON null values;
    // this helper converts that to an empty string.
    private fun JSONObject.str(key: String) =
        optString(key).let { if (it == "null") "" else it }

    // ── Data classes ──────────────────────────────────────────────────

    data class TicketItem(
        val id: String,
        val source: String,
        val content: String,
        val time: String,
        val replyNeeded: Boolean,
        val replyDraft: String?,
        val ticketStatus: String?,
        val extractedData: ExtractedData?
    )

    data class ExtractedData(
        val location: String,
        val urgency: String,
        val persons: Int,
        val items: List<ExtractedItem>
    )

    data class ExtractedItem(val name: String, val qty: Int?, val unit: String?)

    data class InboxMessage(
        val id: String,
        val source: String,
        val content: String,
        val time: String,
        val type: String,
        val status: String,
        val extractedData: ExtractedData?
    )

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTicketsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupTabs()
        setupQueueToggle()
        setupInboxFilters()

        binding.btnLogin.setOnClickListener { showLoginDialog() }
        binding.btnToggleAvail.setOnClickListener { toggleAvailability() }

        if (token == null) {
            showLoginCard()
        } else {
            fetchAvailability()
            loadCurrentTab()
        }
    }

    override fun onResume() {
        super.onResume()
        if (token != null) loadCurrentTab()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Tab navigation ─────────────────────────────────────────────────

    private fun setupTabs() {
        binding.tabMyTickets.setOnClickListener { switchTab("my-tickets") }
        binding.tabInbox.setOnClickListener     { switchTab("inbox") }
        binding.tabArchive.setOnClickListener   { switchTab("archive") }
    }

    private fun switchTab(tab: String) {
        currentTab = tab
        val tabs = listOf(
            binding.tabMyTickets to "my-tickets",
            binding.tabInbox     to "inbox",
            binding.tabArchive   to "archive"
        )
        tabs.forEach { (tv, id) ->
            if (id == tab) {
                tv.setBackgroundResource(R.drawable.tab_active_bg)
                tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.akbay_ink))
            } else {
                tv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.akbay_ash))
            }
        }

        binding.contentMyTickets.visibility = if (tab == "my-tickets") View.VISIBLE else View.GONE
        binding.contentInbox.visibility     = if (tab == "inbox")      View.VISIBLE else View.GONE
        binding.contentArchive.visibility   = if (tab == "archive")    View.VISIBLE else View.GONE

        loadCurrentTab()
    }

    private fun loadCurrentTab() {
        when (currentTab) {
            "my-tickets" -> if (token != null) fetchMyTickets() else showLoginCard()
            "inbox"      -> fetchInbox()
            "archive"    -> if (token != null) fetchArchive()
        }
    }

    // ── Login ──────────────────────────────────────────────────────────

    private fun showLoginCard() {
        binding.loadingMyTickets.visibility       = View.GONE
        binding.emptyMyTickets.visibility         = View.GONE
        binding.cardAvailability.visibility       = View.GONE
        binding.activeTicketsContainer.visibility = View.GONE
        binding.cardLogin.visibility              = View.VISIBLE
    }

    private fun showLoginDialog() {
        val url = serverUrl
        if (url == null) {
            Toast.makeText(
                requireContext(),
                "No server connected. Start the node and connect to a server first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_login, null)
        val etUsername  = dialogView.findViewById<TextInputEditText>(R.id.etUsername)
        val etPassword  = dialogView.findViewById<TextInputEditText>(R.id.etPassword)
        val tvError     = dialogView.findViewById<TextView>(R.id.tvLoginError)
        val btnSignIn   = dialogView.findViewById<MaterialButton>(R.id.btnSignIn)
        val btnCancel   = dialogView.findViewById<MaterialButton>(R.id.btnCancelLogin)
        val tvGoSignup  = dialogView.findViewById<TextView>(R.id.tvGoToSignup)

        etUsername.setText(savedUsername ?: "")

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        btnSignIn.setOnClickListener {
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString()
            tvError.visibility = View.GONE
            if (username.isBlank()) { tvError.visibility = View.VISIBLE; tvError.text = "Enter your username"; return@setOnClickListener }
            if (password.isBlank()) { tvError.visibility = View.VISIBLE; tvError.text = "Enter your password"; return@setOnClickListener }
            btnSignIn.isEnabled = false
            btnSignIn.text = "signing in…"
            performLogin(dialog, url, username, password, tvError, btnSignIn)
        }

        tvGoSignup.setOnClickListener {
            dialog.dismiss()
            showSignupDialog()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showSignupDialog() {
        val url = serverUrl
        if (url == null) {
            Toast.makeText(requireContext(), "No server connected. Start the node first.", Toast.LENGTH_LONG).show()
            return
        }

        val dialogView   = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_signup, null)
        val etFullName   = dialogView.findViewById<TextInputEditText>(R.id.etFullName)
        val etUsername   = dialogView.findViewById<TextInputEditText>(R.id.etSignupUsername)
        val etPassword   = dialogView.findViewById<TextInputEditText>(R.id.etSignupPassword)
        val etConfirm    = dialogView.findViewById<TextInputEditText>(R.id.etSignupConfirmPassword)
        val tvError      = dialogView.findViewById<TextView>(R.id.tvSignupError)
        val btnCreate    = dialogView.findViewById<MaterialButton>(R.id.btnCreateAccount)
        val btnCancel    = dialogView.findViewById<MaterialButton>(R.id.btnCancelSignup)
        val tvGoSignIn   = dialogView.findViewById<TextView>(R.id.tvGoToSignIn)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        btnCreate.setOnClickListener {
            val fullName = etFullName.text.toString().trim()
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString()
            val confirm  = etConfirm.text.toString()
            tvError.visibility = View.GONE

            when {
                fullName.isBlank() -> { tvError.visibility = View.VISIBLE; tvError.text = "Enter your full name" }
                username.isBlank() -> { tvError.visibility = View.VISIBLE; tvError.text = "Enter a username" }
                password.length < 6 -> { tvError.visibility = View.VISIBLE; tvError.text = "Password must be at least 6 characters" }
                password != confirm -> { tvError.visibility = View.VISIBLE; tvError.text = "Passwords do not match" }
                else -> {
                    btnCreate.isEnabled = false
                    btnCreate.text = "creating account…"
                    performSignup(dialog, url, fullName, username, password, tvError, btnCreate)
                }
            }
        }

        tvGoSignIn.setOnClickListener {
            dialog.dismiss()
            showLoginDialog()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun performLogin(
        dialog: android.app.Dialog,
        url: String,
        username: String,
        password: String,
        tvError: TextView,
        btnSignIn: MaterialButton
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$url/auth/login").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.outputStream.use { it.write(JSONObject().put("username", username).put("password", password).toString().toByteArray()) }

                val code = conn.responseCode
                val responseText = runCatching { (if (code == 200) conn.inputStream else conn.errorStream).bufferedReader().readText() }.getOrElse { "" }
                conn.disconnect()

                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    if (code == 200) {
                        val tok = JSONObject(responseText).optString("token").ifEmpty {
                            JSONObject(responseText).optString("access_token")
                        }
                        if (tok.isNotEmpty()) {
                            saveSession(tok, username, url)
                            dialog.dismiss()
                            binding.cardLogin.visibility = View.GONE
                            fetchAvailability()
                            loadCurrentTab()
                        } else {
                            resetBtn(tvError, btnSignIn, "Unexpected response", "sign in")
                        }
                    } else {
                        val msg = runCatching { JSONObject(responseText).optString("detail", "Invalid credentials") }.getOrElse { "Invalid credentials" }
                        resetBtn(tvError, btnSignIn, msg, "sign in")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    resetBtn(tvError, btnSignIn, "Could not reach server: ${e.message}", "sign in")
                }
            }
        }
    }

    private fun performSignup(
        dialog: android.app.Dialog,
        url: String,
        fullName: String,
        username: String,
        password: String,
        tvError: TextView,
        btnCreate: MaterialButton
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$url/auth/signup").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                val body = JSONObject().put("full_name", fullName).put("username", username).put("password", password)
                conn.outputStream.use { it.write(body.toString().toByteArray()) }

                val code = conn.responseCode
                val responseText = runCatching { (if (code == 200) conn.inputStream else conn.errorStream).bufferedReader().readText() }.getOrElse { "" }
                conn.disconnect()

                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    if (code == 200) {
                        val tok = JSONObject(responseText).optString("token").ifEmpty {
                            JSONObject(responseText).optString("access_token")
                        }
                        if (tok.isNotEmpty()) {
                            saveSession(tok, username, url)
                            dialog.dismiss()
                            binding.cardLogin.visibility = View.GONE
                            fetchAvailability()
                            loadCurrentTab()
                        } else {
                            resetBtn(tvError, btnCreate, "Unexpected response", "create account")
                        }
                    } else {
                        val msg = runCatching { JSONObject(responseText).optString("detail", "Signup failed") }.getOrElse { "Signup failed" }
                        resetBtn(tvError, btnCreate, msg, "create account")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    resetBtn(tvError, btnCreate, "Could not reach server: ${e.message}", "create account")
                }
            }
        }
    }

    private fun saveSession(tok: String, username: String, url: String) {
        prefs.edit()
            .putString("tickets_token", tok)
            .putString("tickets_username", username)
            .putString("tickets_server_url", url)
            .apply()
    }

    private fun resetBtn(tvError: TextView, btn: MaterialButton, msg: String, label: String) {
        tvError.text = msg
        tvError.visibility = View.VISIBLE
        btn.isEnabled = true
        btn.text = label
    }

    // ── Availability ──────────────────────────────────────────────────

    private fun fetchAvailability() {
        val url = serverUrl ?: return
        val tok = token ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$url/auth/me").openConnection() as HttpURLConnection
                conn.setRequestProperty("Authorization", "Bearer $tok")
                conn.connectTimeout = 5_000; conn.readTimeout = 5_000
                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    conn.disconnect()
                    withContext(Dispatchers.Main) {
                        if (_binding == null) return@withContext
                        available = json.optBoolean("available", true)
                        updateAvailabilityUI()
                        binding.cardAvailability.visibility = View.VISIBLE
                    }
                } else {
                    conn.disconnect()
                }
            } catch (_: Exception) {}
        }
    }

    private fun updateAvailabilityUI() {
        val ctx = requireContext()
        if (available) {
            binding.cardAvailability.setCardBackgroundColor(0xFFF0FDF4.toInt())
            binding.availDot.backgroundTintList = ColorStateList.valueOf(0xFF16A34A.toInt())
            binding.tvAvailTitle.text = "Taking tickets"
            binding.tvAvailSubtitle.text = "You will be assigned new tickets as they come in."
            binding.btnToggleAvail.text = "Pause"
            binding.btnToggleAvail.setTextColor(0xFF15803D.toInt())
            binding.btnToggleAvail.backgroundTintList = ColorStateList.valueOf(0xFFDCFCE7.toInt())
        } else {
            binding.cardAvailability.setCardBackgroundColor(0xFFFFF5F5.toInt())
            binding.availDot.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.akbay_tabang)
            )
            binding.tvAvailTitle.text = "Paused"
            binding.tvAvailSubtitle.text = "Your tickets have been reassigned. Turn on to receive new ones."
            binding.btnToggleAvail.text = "Resume"
            binding.btnToggleAvail.setTextColor(ContextCompat.getColor(ctx, R.color.akbay_bone))
            binding.btnToggleAvail.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.akbay_header)
            )
        }
    }

    private fun toggleAvailability() {
        val url = serverUrl ?: return
        val tok = token ?: return
        val next = !available
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$url/auth/users/me/available").openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $tok")
                conn.doOutput = true
                conn.connectTimeout = 5_000
                conn.outputStream.use { it.write(JSONObject().put("available", next).toString().toByteArray()) }
                if (conn.responseCode == 200) {
                    withContext(Dispatchers.Main) {
                        if (_binding == null) return@withContext
                        available = next
                        updateAvailabilityUI()
                        if (currentTab == "my-tickets") fetchMyTickets()
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    // ── My Tickets ─────────────────────────────────────────────────────

    private fun fetchMyTickets() {
        val url = serverUrl ?: return
        val tok = token ?: return
        binding.loadingMyTickets.visibility       = View.VISIBLE
        binding.emptyMyTickets.visibility         = View.GONE
        binding.activeTicketsContainer.visibility = View.GONE
        binding.queueHeader.visibility            = View.GONE
        binding.queueContainer.visibility         = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$url/api/tickets").openConnection() as HttpURLConnection
                conn.setRequestProperty("Authorization", "Bearer $tok")
                conn.connectTimeout = 8_000; conn.readTimeout = 8_000
                val code = conn.responseCode
                if (code == 401) {
                    prefs.edit().remove("tickets_token").apply()
                    withContext(Dispatchers.Main) { if (_binding != null) { binding.loadingMyTickets.visibility = View.GONE; showLoginCard() } }
                    return@launch
                }
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                myTickets = parseTickets(JSONArray(json))
                    .filter { it.ticketStatus == "pending_approval" || it.ticketStatus == "queued" }
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.loadingMyTickets.visibility = View.GONE
                    renderMyTickets()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.loadingMyTickets.visibility = View.GONE
                    binding.emptyMyTickets.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun renderMyTickets() {
        val active = myTickets.filter { it.ticketStatus == "pending_approval" }
        val queued = myTickets.filter { it.ticketStatus == "queued" }

        if (active.isEmpty() && queued.isEmpty()) {
            binding.emptyMyTickets.visibility = View.VISIBLE
            return
        }

        binding.activeTicketsContainer.visibility = View.VISIBLE
        binding.activeTicketsContainer.removeAllViews()
        active.forEach { inflateTicketCard(binding.activeTicketsContainer, it, isArchived = false) }

        if (queued.isNotEmpty()) {
            binding.queueHeader.visibility = View.VISIBLE
            binding.tvQueueTitle.text = "queue — ${queued.size} waiting · approve a ticket to pull the next"
            if (queueOpen) renderQueueItems(queued)
        }
    }

    private fun renderQueueItems(queued: List<TicketItem>) {
        binding.queueContainer.removeAllViews()
        queued.forEach { inflateTicketCard(binding.queueContainer, it, isArchived = false) }
    }

    private fun setupQueueToggle() {
        binding.tvQueueToggle.setOnClickListener {
            queueOpen = !queueOpen
            binding.tvQueueToggle.text = if (queueOpen) "hide ▼" else "show ▶"
            if (queueOpen) {
                binding.queueContainer.visibility = View.VISIBLE
                renderQueueItems(myTickets.filter { it.ticketStatus == "queued" })
            } else {
                binding.queueContainer.visibility = View.GONE
                binding.queueContainer.removeAllViews()
            }
        }
    }

    // ── Archive ────────────────────────────────────────────────────────

    private fun fetchArchive() {
        val url = serverUrl ?: return
        val tok = token ?: return
        binding.loadingArchive.visibility   = View.VISIBLE
        binding.emptyArchive.visibility     = View.GONE
        binding.archiveContainer.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$url/api/tickets").openConnection() as HttpURLConnection
                conn.setRequestProperty("Authorization", "Bearer $tok")
                conn.connectTimeout = 8_000; conn.readTimeout = 8_000
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                archivedTickets = parseTickets(JSONArray(json)).filter { it.ticketStatus == "approved" }
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.loadingArchive.visibility = View.GONE
                    renderArchive()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.loadingArchive.visibility = View.GONE
                    binding.emptyArchive.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun renderArchive() {
        if (archivedTickets.isEmpty()) {
            binding.emptyArchive.visibility = View.VISIBLE
            return
        }
        binding.archiveContainer.visibility = View.VISIBLE
        binding.archiveContainer.removeAllViews()
        archivedTickets.forEach { inflateTicketCard(binding.archiveContainer, it, isArchived = true) }
    }

    // ── Inbox ──────────────────────────────────────────────────────────

    private fun setupInboxFilters() {
        val filterViews = mapOf(
            binding.filterAll    to "all",
            binding.filterSms    to "sms",
            binding.filterVoice  to "voice",
            binding.filterManual to "manual"
        )
        filterViews.forEach { (tv, value) ->
            tv.setOnClickListener {
                activeInboxFilter = value
                updateFilterStyles(filterViews)
                fetchInbox()
            }
        }

        binding.etInboxSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                inboxSearchJob?.cancel()
                inboxSearchQuery = s?.toString() ?: ""
                inboxSearchJob = lifecycleScope.launch {
                    delay(300)
                    if (currentTab == "inbox") fetchInbox()
                }
            }
        })

        binding.btnLoadMoreInbox.setOnClickListener {
            fetchInboxPage(offset = inboxOffset, append = true)
        }
    }

    private fun updateFilterStyles(filterViews: Map<TextView, String>) {
        filterViews.forEach { (tv, value) ->
            if (value == activeInboxFilter) {
                tv.setBackgroundResource(R.drawable.tab_active_bg)
                tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.akbay_ink))
            } else {
                tv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.akbay_ash))
            }
        }
    }

    // Reset to page 1 (called on filter/search change or tab switch)
    private fun fetchInbox() {
        inboxOffset = 0
        inboxTotal = 0
        binding.inboxContainer.removeAllViews()
        binding.btnLoadMoreInbox.visibility = View.GONE
        fetchInboxPage(offset = 0, append = false)
    }

    private fun fetchInboxPage(offset: Int, append: Boolean) {
        val url = serverUrl ?: return
        if (!append) binding.loadingInbox.visibility = View.VISIBLE
        binding.btnLoadMoreInbox.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Stats only on first page load
                val statsJson = if (!append) {
                    val c = URL("$url/api/stats").openConnection() as HttpURLConnection
                    c.connectTimeout = 5_000; c.readTimeout = 5_000
                    val j = if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().readText()) else JSONObject()
                    c.disconnect(); j
                } else null

                // Messages
                val params = buildString {
                    append("limit=$INBOX_PAGE&offset=$offset")
                    if (activeInboxFilter != "all") append("&source=${Uri.encode(activeInboxFilter)}")
                    if (inboxSearchQuery.isNotBlank()) append("&q=${Uri.encode(inboxSearchQuery)}")
                }
                val msgConn = URL("$url/api/messages?$params").openConnection() as HttpURLConnection
                msgConn.connectTimeout = 8_000; msgConn.readTimeout = 8_000
                val msgJson = JSONObject(msgConn.inputStream.bufferedReader().readText())
                msgConn.disconnect()

                val total    = msgJson.optInt("total", 0)
                val messages = parseInboxMessages(msgJson.optJSONArray("messages") ?: JSONArray())

                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.loadingInbox.visibility = View.GONE

                    statsJson?.let {
                        binding.tvStatsProcessed.text = it.optInt("processed", 0).toString()
                        binding.tvStatsInQueue.text   = it.optInt("inQueue", 0).toString()
                        binding.tvStatsFailed.text    = it.optInt("failed", 0).toString()
                    }

                    inboxTotal  = total
                    inboxOffset = offset + messages.size
                    renderInboxPage(messages)
                    updateLoadMoreButton()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.loadingInbox.visibility = View.GONE
                    binding.btnLoadMoreInbox.isEnabled = true
                }
            }
        }
    }

    private fun updateLoadMoreButton() {
        val remaining = inboxTotal - inboxOffset
        if (remaining > 0) {
            binding.btnLoadMoreInbox.visibility = View.VISIBLE
            binding.btnLoadMoreInbox.isEnabled  = true
            binding.btnLoadMoreInbox.text = "load older messages ($remaining remaining)"
        } else {
            binding.btnLoadMoreInbox.visibility = View.GONE
        }
    }

    private fun renderInboxPage(messages: List<InboxMessage>) {
        val inflater = LayoutInflater.from(requireContext())
        messages.forEach { msg ->
            val cardBinding = ItemTicketCardBinding.inflate(inflater, binding.inboxContainer, false)

            applyUrgencyToBadge(cardBinding.tvUrgencyBadge, msg.extractedData?.urgency ?: "low")
            cardBinding.tvSource.text = "from ${msg.source}  ·  ${msg.type}"
            cardBinding.tvTime.text   = formatTime(msg.time)
            cardBinding.tvContent.text = msg.content

            cardBinding.tvStatus.visibility = View.VISIBLE
            cardBinding.tvStatus.text = when (msg.status) {
                "processed"        -> "✓ processed"
                "processing"       -> "⟳ processing…"
                "needs_processing" -> "pending"
                "failed"           -> "✕ failed"
                "fulfilled"        -> "✓ fulfilled"
                else               -> msg.status
            }
            cardBinding.tvStatus.setTextColor(
                when (msg.status) {
                    "processed", "fulfilled" -> ContextCompat.getColor(requireContext(), R.color.akbay_damay)
                    "failed"                 -> ContextCompat.getColor(requireContext(), R.color.akbay_tabang)
                    else                     -> ContextCompat.getColor(requireContext(), R.color.akbay_ash)
                }
            )

            if (msg.extractedData != null) bindExtractedPanel(cardBinding, msg.extractedData, ticketId = null, disabled = true)

            // Inbox cards are view-only
            cardBinding.tvReplyLabel.visibility = View.GONE
            cardBinding.tvSaveState.visibility  = View.GONE
            cardBinding.etReplyDraft.visibility = View.GONE
            cardBinding.btnApprove.visibility   = View.GONE

            binding.inboxContainer.addView(cardBinding.root)
        }
    }

    // ── Card rendering ─────────────────────────────────────────────────

    private fun inflateTicketCard(container: LinearLayout, ticket: TicketItem, isArchived: Boolean) {
        val cardBinding = ItemTicketCardBinding.inflate(LayoutInflater.from(requireContext()), container, false)
        bindTicketCard(cardBinding, ticket, isArchived)
        container.addView(cardBinding.root)
    }

    private fun bindTicketCard(cardBinding: ItemTicketCardBinding, ticket: TicketItem, isArchived: Boolean) {
        val isApproved = isArchived || ticket.ticketStatus == "approved"
        val isQueued   = ticket.ticketStatus == "queued"

        applyUrgencyToBadge(cardBinding.tvUrgencyBadge, ticket.extractedData?.urgency ?: "medium")
        cardBinding.tvSource.text = "from ${ticket.source}"
        cardBinding.tvTime.text   = formatTime(ticket.time)
        cardBinding.tvContent.text = ticket.content

        when {
            isApproved -> {
                cardBinding.tvStatus.visibility = View.VISIBLE
                cardBinding.tvStatus.text = "✓ approved"
                cardBinding.tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.akbay_damay))
                cardBinding.ticketCard.alpha = 0.75f
            }
            isQueued -> {
                cardBinding.tvStatus.visibility = View.VISIBLE
                cardBinding.tvStatus.text = "⏱ queued"
                cardBinding.tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.akbay_ash))
                cardBinding.ticketCard.alpha = 0.6f
            }
            else -> {
                cardBinding.tvStatus.visibility = View.GONE
                cardBinding.ticketCard.alpha = 1.0f
            }
        }

        val disabled = isApproved || isQueued

        if (ticket.extractedData != null) bindExtractedPanel(cardBinding, ticket.extractedData, ticket.id, disabled)
        cardBinding.etReplyDraft.isEnabled = !disabled
        cardBinding.etReplyDraft.setText(ticket.replyDraft ?: "")
        cardBinding.etReplyDraft.hint = when {
            isQueued           -> "Waiting for a responder…"
            ticket.replyNeeded -> "Write a reply to send back via SMS…"
            else               -> "Optionally write a reply…"
        }
        cardBinding.tvReplyLabel.text = if (ticket.replyNeeded) "reply draft" else "reply (optional)"

        if (!disabled) {
            var saveJob: Job? = null
            cardBinding.etReplyDraft.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    saveJob?.cancel()
                    cardBinding.tvSaveState.text = ""
                    saveJob = lifecycleScope.launch {
                        delay(600)
                        val draft = s?.toString()?.takeIf { it.isNotBlank() } ?: return@launch
                        cardBinding.tvSaveState.text = "saving…"
                        saveReplyDraft(ticket.id, draft) {
                            cardBinding.tvSaveState.text = "saved"
                            lifecycleScope.launch { delay(1500); cardBinding.tvSaveState.text = "" }
                        }
                    }
                }
            })

            cardBinding.btnApprove.visibility = View.VISIBLE
            cardBinding.btnApprove.text = if (ticket.replyNeeded) "approve reply" else "mark reviewed"
            cardBinding.btnApprove.setOnClickListener {
                it.isEnabled = false
                approveTicket(ticket.id) {
                    if (currentTab == "my-tickets") fetchMyTickets() else fetchArchive()
                }
            }
        } else {
            cardBinding.btnApprove.visibility = View.GONE
        }
    }

    private fun bindExtractedPanel(
        cardBinding: ItemTicketCardBinding,
        initialData: ExtractedData,
        ticketId: String? = null,
        disabled: Boolean = false
    ) {
        val ctx = requireContext()
        val editable = ticketId != null && !disabled
        cardBinding.extractedPanel.visibility = View.VISIBLE

        var curLocation = initialData.location
        var curPersons  = initialData.persons
        var curUrgency  = initialData.urgency
        val curItems    = initialData.items.map { ExtractedItem(it.name, it.qty, it.unit) }.toMutableList()

        var saveJob: Job? = null
        fun scheduleSave() {
            if (!editable) return
            saveJob?.cancel()
            saveJob = lifecycleScope.launch {
                delay(600)
                if (_binding == null) return@launch
                cardBinding.tvExtractedSaveState.setTextColor(
                    ContextCompat.getColor(ctx, R.color.akbay_ash)
                )
                cardBinding.tvExtractedSaveState.text = "saving…"
                withContext(Dispatchers.IO) {
                    saveExtractedData(ticketId!!, curLocation, curPersons, curUrgency, curItems)
                }
                if (_binding == null) return@launch
                cardBinding.tvExtractedSaveState.setTextColor(0xFF16A34A.toInt())
                cardBinding.tvExtractedSaveState.text = "saved"
                delay(1500)
                if (_binding == null) return@launch
                cardBinding.tvExtractedSaveState.text = ""
            }
        }

        fun renderItems() {
            cardBinding.itemsContainer.removeAllViews()
            val inflater = LayoutInflater.from(ctx)
            curItems.forEachIndexed { i, item ->
                val row = ItemExtractedRowBinding.inflate(inflater, cardBinding.itemsContainer, false)
                row.etItemName.setText(item.name)
                row.etItemName.isEnabled = editable

                val isTriage = item.unit == "triage"
                if (isTriage) {
                    row.layoutQty.visibility   = View.GONE
                    row.tvItemTriage.visibility = View.VISIBLE
                } else {
                    row.layoutQty.visibility   = View.VISIBLE
                    row.tvItemTriage.visibility = View.GONE
                    row.etItemQty.setText(item.qty?.toString() ?: "")
                    row.etItemQty.isEnabled = editable
                    if (item.unit?.isNotBlank() == true) {
                        row.tvItemUnit.visibility = View.VISIBLE
                        row.tvItemUnit.text = item.unit
                    }
                }

                if (editable) {
                    row.btnRemoveItem.visibility = View.VISIBLE
                    row.btnRemoveItem.setOnClickListener {
                        curItems.removeAt(i)
                        renderItems()
                        scheduleSave()
                    }
                    row.etItemName.addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            if (i < curItems.size)
                                curItems[i] = curItems[i].copy(name = s?.toString() ?: "")
                            scheduleSave()
                        }
                    })
                    if (!isTriage) {
                        row.etItemQty.addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                            override fun afterTextChanged(s: Editable?) {
                                if (i < curItems.size)
                                    curItems[i] = curItems[i].copy(qty = s?.toString()?.toIntOrNull())
                                scheduleSave()
                            }
                        })
                    }
                }
                cardBinding.itemsContainer.addView(row.root)
            }
            cardBinding.btnAddItem.visibility = if (editable) View.VISIBLE else View.GONE
        }

        // Location
        cardBinding.etLocation.setText(initialData.location)
        cardBinding.etLocation.isEnabled = editable
        if (editable) {
            cardBinding.etLocation.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    curLocation = s?.toString() ?: ""
                    scheduleSave()
                }
            })
        }

        // Persons
        cardBinding.etPersons.setText(if (initialData.persons > 0) initialData.persons.toString() else "")
        cardBinding.etPersons.isEnabled = editable
        if (editable) {
            cardBinding.etPersons.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    curPersons = s?.toString()?.toIntOrNull() ?: 0
                    scheduleSave()
                }
            })
        }

        // Urgency pill picker — matches web's colored select
        data class UrgencyOpt(val value: String, val label: String, val color: Int, val bgAlpha: Int)
        val urgencyOpts = listOf(
            UrgencyOpt("critical", "CRITICAL", 0xFFDC2626.toInt(), 0x22DC2626),
            UrgencyOpt("high",     "URGENT",   0xFFEA580C.toInt(), 0x22EA580C),
            UrgencyOpt("medium",   "MEDIUM",   0xFFCA8A04.toInt(), 0x22CA8A04),
            UrgencyOpt("low",      "LOW",      0xFF16A34A.toInt(), 0x2216A34A),
        )
        fun renderUrgencyPills() {
            cardBinding.urgencyPills.removeAllViews()
            val dp = ctx.resources.displayMetrics.density
            urgencyOpts.forEach { opt ->
                val selected = curUrgency == opt.value
                val tv = TextView(ctx).apply {
                    text = opt.label
                    textSize = 10f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(if (selected) opt.color else 0xFF6A645A.toInt())
                    val ph = (10 * dp).toInt(); val pv = (4 * dp).toInt()
                    setPadding(ph, pv, ph, pv)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 999 * dp
                        setColor(if (selected) opt.bgAlpha else Color.TRANSPARENT)
                        if (selected) setStroke((1 * dp).toInt(), opt.color)
                    }
                    if (editable) setOnClickListener {
                        curUrgency = opt.value
                        renderUrgencyPills()
                        scheduleSave()
                    }
                }
                cardBinding.urgencyPills.addView(
                    tv,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = (4 * dp).toInt() }
                )
            }
        }
        renderUrgencyPills()

        // Add item button
        cardBinding.btnAddItem.setOnClickListener {
            curItems.add(ExtractedItem("", null, ""))
            renderItems()
            scheduleSave()
        }

        renderItems()
    }

    // ── Urgency styling ────────────────────────────────────────────────

    private fun applyUrgencyToBadge(tv: TextView, urgency: String) {
        // Colors match the web's Tailwind palette exactly
        val (textColor, bgColor) = when (urgency.lowercase()) {
            "critical"       -> Pair(0xFFDC2626.toInt(), 0xFFFEE2E2.toInt())
            "high", "urgent" -> Pair(0xFFEA580C.toInt(), 0xFFFFEDD5.toInt())
            "medium"         -> Pair(0xFFCA8A04.toInt(), 0xFFFEF9C3.toInt())
            "low"            -> Pair(0xFF16A34A.toInt(), 0xFFDCFCE7.toInt())
            else             -> Pair(0xFF6A645A.toInt(), 0xFFE2DDD2.toInt())
        }
        tv.setTextColor(textColor)
        tv.backgroundTintList = ColorStateList.valueOf(bgColor)
        tv.text = urgency.uppercase()
    }

    // ── API actions ────────────────────────────────────────────────────

    private fun saveReplyDraft(ticketId: String, draft: String, onDone: () -> Unit) {
        val url = serverUrl ?: return
        val tok = token ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$url/api/tickets/$ticketId/reply").openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $tok")
                conn.doOutput = true
                conn.connectTimeout = 5_000
                conn.outputStream.use { it.write(JSONObject().put("reply_draft", draft).toString().toByteArray()) }
                conn.responseCode
                conn.disconnect()
                withContext(Dispatchers.Main) { onDone() }
            } catch (_: Exception) {}
        }
    }

    private fun approveTicket(ticketId: String, onDone: () -> Unit) {
        val url = serverUrl ?: return
        val tok = token ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$url/api/tickets/$ticketId/approve").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $tok")
                conn.connectTimeout = 5_000
                conn.responseCode
                conn.disconnect()
                withContext(Dispatchers.Main) { onDone() }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    Toast.makeText(requireContext(), "Failed to approve ticket", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveExtractedData(
        ticketId: String,
        location: String,
        persons: Int,
        urgency: String,
        items: List<ExtractedItem>
    ) {
        val url = serverUrl ?: return
        val tok = token ?: return
        try {
            val conn = URL("$url/api/tickets/$ticketId/extracted").openConnection() as HttpURLConnection
            conn.requestMethod = "PATCH"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $tok")
            conn.doOutput = true
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            val itemsArr = JSONArray()
            items.forEach { item ->
                itemsArr.put(JSONObject().apply {
                    put("name", item.name)
                    if (item.qty != null) put("qty", item.qty) else put("qty", JSONObject.NULL)
                    put("unit", item.unit ?: "")
                })
            }
            val body = JSONObject().put(
                "extracted_data", JSONObject()
                    .put("location", location)
                    .put("persons", persons)
                    .put("urgency", urgency)
                    .put("items", itemsArr)
            )
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {}
    }

    // ── JSON parsing ───────────────────────────────────────────────────

    private fun parseTickets(arr: JSONArray): List<TicketItem> = (0 until arr.length()).map { i ->
        val obj = arr.getJSONObject(i)
        TicketItem(
            id           = obj.str("id"),
            source       = obj.str("source"),
            content      = obj.str("content"),
            time         = obj.str("time"),
            replyNeeded  = obj.optBoolean("replyNeeded", false),
            replyDraft   = obj.str("replyDraft").ifEmpty { null },
            ticketStatus = obj.str("ticketStatus").ifEmpty { null },
            extractedData = obj.optJSONObject("extractedData")?.let { parseExtracted(it) }
        )
    }

    private fun parseInboxMessages(arr: JSONArray): List<InboxMessage> = (0 until arr.length()).map { i ->
        val obj = arr.getJSONObject(i)
        InboxMessage(
            id            = obj.str("id"),
            source        = obj.str("source"),
            content       = obj.str("content"),
            time          = obj.str("time"),
            type          = obj.str("type").ifEmpty { "sms" },
            status        = obj.str("status").ifEmpty { "needs_processing" },
            extractedData = obj.optJSONObject("extractedData")?.let { parseExtracted(it) }
        )
    }

    private fun parseExtracted(obj: JSONObject): ExtractedData {
        val itemsArr = obj.optJSONArray("items") ?: JSONArray()
        val items = (0 until itemsArr.length()).map { j ->
            val itm = itemsArr.getJSONObject(j)
            ExtractedItem(
                name = itm.str("name").ifEmpty { itm.str("canonical") },
                qty  = if (itm.isNull("qty")) null else itm.optInt("qty"),
                unit = itm.str("unit").ifEmpty { null }
            )
        }
        val locObj = obj.optJSONObject("location")
        val location = if (locObj != null)
            listOfNotNull(
                locObj.str("barangay").ifEmpty { null },
                locObj.str("city").ifEmpty { null },
                locObj.str("province").ifEmpty { null }
            ).joinToString(", ")
        else obj.str("location")

        return ExtractedData(
            location = location,
            urgency  = obj.str("urgency").ifEmpty { "medium" },
            persons  = obj.optInt("persons", 0),
            items    = items
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun formatTime(iso: String): String = try {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val date = parser.parse(iso.take(19)) ?: return iso
        SimpleDateFormat("MMM d, h:mm a", Locale.US).format(date)
    } catch (_: Exception) { iso }
}
