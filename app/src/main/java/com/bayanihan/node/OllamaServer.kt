package com.bayanihan.node

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD

class OllamaServer(port: Int = 11434) : NanoHTTPD("0.0.0.0", port) {

    companion object {
        private const val TAG = "OllamaServer"
    }

    init {
        Log.i(TAG, "OllamaServer initialized on 0.0.0.0:11434")
    }

    // Set by GemmaService's thermal broadcast receiver
    @Volatile var thermalThrottle: Boolean = false

    private val inferLock = Any()

    override fun serve(session: IHTTPSession): Response {
        Log.i(TAG, "--- API Request: ${session.method} ${session.uri} ---")
        return when {
            session.uri == "/" || session.uri == "/health" -> {
                val status = if (GemmaEngine.isLoaded) "Ready" else "Loading"
                newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"$status","engine":"LiteRT"}""")
            }
            session.method == Method.POST && session.uri == "/api/generate" -> handleGenerate(session)
            session.method == Method.GET  && session.uri == "/api/tags"     -> handleTags()
            session.uri.startsWith("/api/blobs") -> {
                // Return 200 OK for any blob request to tell the backend we "have" the data
                Log.i(TAG, "Stubbing blob request: ${session.uri}")
                newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"exists"}""")
            }
            session.method == Method.POST && session.uri == "/api/show" -> {
                val body = """{"modelfile":"# Bayanihan Gemma 4 Node\nFROM gemma-4-e2b","parameters":"stop \"<end_of_turn>\"","template":"{{ .Prompt }}"}"""
                newFixedLengthResponse(Response.Status.OK, "application/json", body)
            }
            else -> {
                Log.w(TAG, "Route not found: ${session.uri}")
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
        }
    }

    private fun handleGenerate(session: IHTTPSession): Response {
        Log.i(TAG, "Handling /api/generate")
        if (thermalThrottle) {
            Log.w(TAG, "Throttled due to high temperature")
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE, "application/json",
                """{"error":"thermal_throttle","message":"Device cooling down, try again shortly"}"""
            )
        }

        if (!GemmaEngine.isLoaded) {
            Log.e(TAG, "Model not loaded yet")
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE, "application/json",
                """{"error":"model_not_loaded"}"""
            )
        }

        val body = readBody(session)
        Log.d(TAG, "Request body: $body")
        val json: JsonObject = try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            Log.e(TAG, "Invalid JSON: ${e.message}")
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                """{"error":"invalid_json"}""")
        }

        val prompt = json.get("prompt")?.asString
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                """{"error":"missing prompt"}""")

        Log.i(TAG, "Prompt: $prompt")

        val startNs   = System.nanoTime()
        val response  = synchronized(inferLock) {
            try {
                GemmaEngine.generate(prompt)
            } catch (e: Exception) {
                Log.e(TAG, "Inference error: ${e.message}")
                "Error during inference: ${e.message}"
            }
        }
        val durationNs = System.nanoTime() - startNs
        Log.i(TAG, "Response generated in ${durationNs / 1_000_000}ms")

        // Rough token count: split on spaces
        val evalCount = response.trim().split("\\s+".toRegex()).size

        val result = JsonObject().apply {
            addProperty("model", "gemma-4-e2b")
            addProperty("response", response)
            addProperty("done", true)
            addProperty("total_duration", durationNs)
            addProperty("load_duration", 0L)
            addProperty("prompt_eval_duration", 0L)
            addProperty("eval_duration", durationNs)
            addProperty("eval_count", evalCount)
            addProperty("prompt_eval_count", prompt.length / 4)
        }

        return newFixedLengthResponse(Response.Status.OK, "application/json", result.toString())
    }

    private fun handleTags(): Response {
        Log.i(TAG, "Handling /api/tags")
        val body = """{"models":[{"name":"gemma-4-e2b","model":"gemma-4-e2b"}]}"""
        return newFixedLengthResponse(Response.Status.OK, "application/json", body)
    }

    private fun readBody(session: IHTTPSession): String {
        val len = session.headers["content-length"]?.toIntOrNull() ?: return ""
        val buf = ByteArray(len)
        var offset = 0
        while (offset < len) {
            val n = session.inputStream.read(buf, offset, len - offset)
            if (n < 0) break
            offset += n
        }
        return String(buf, 0, offset)
    }
}
