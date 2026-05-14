package com.bayanihan.node

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD

class OllamaServer(port: Int = 11434) : NanoHTTPD(port) {

    // Set by LlamaService's thermal broadcast receiver
    @Volatile var thermalThrottle: Boolean = false

    private val inferLock = Any()

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.method == Method.POST && session.uri == "/api/generate" -> handleGenerate(session)
            session.method == Method.GET  && session.uri == "/api/tags"     -> handleTags()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun handleGenerate(session: IHTTPSession): Response {
        if (thermalThrottle) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE, "application/json",
                """{"error":"thermal_throttle","message":"Device cooling down, try again shortly"}"""
            )
        }

        if (!LlamaEngine.isLoaded) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE, "application/json",
                """{"error":"model_not_loaded"}"""
            )
        }

        val body = readBody(session)
        val json: JsonObject = try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                """{"error":"invalid_json"}""")
        }

        val prompt = json.get("prompt")?.asString
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                """{"error":"missing prompt"}""")

        val opts      = json.getAsJsonObject("options")
        val temp      = opts?.get("temperature")?.asFloat  ?: 1.0f
        val maxTokens = opts?.get("num_predict")?.asInt    ?: 512

        val startNs   = System.nanoTime()
        val response  = synchronized(inferLock) {
            LlamaEngine.generate(LlamaEngine.contextHandle, prompt, temp, maxTokens)
        }
        val durationNs = System.nanoTime() - startNs

        // Rough token count: split on spaces
        val evalCount = response.trim().split("\\s+".toRegex()).size

        val result = JsonObject().apply {
            addProperty("model", "gemma4-e2b-unsloth")
            addProperty("response", response)
            addProperty("done", true)
            addProperty("total_duration", durationNs)
            addProperty("eval_duration", durationNs)
            addProperty("eval_count", evalCount)
            addProperty("prompt_eval_count", prompt.length / 4)
        }

        return newFixedLengthResponse(Response.Status.OK, "application/json", result.toString())
    }

    private fun handleTags(): Response {
        val body = """{"models":[{"name":"gemma4-e2b-unsloth","model":"gemma4-e2b-unsloth"}]}"""
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
