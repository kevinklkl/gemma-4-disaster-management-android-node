package com.akbay.node

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
                val body = """{"modelfile":"# Akbay Gemma 4 Node\nFROM gemma-4-e2b","parameters":"stop \"<end_of_turn>\"","template":"{{ .Prompt }}"}"""
                newFixedLengthResponse(Response.Status.OK, "application/json", body)
            }
            session.method == Method.POST && session.uri == "/api/create" -> {
                Log.i(TAG, "Stubbing /api/create — model is natively available as gemma-4-e2b")
                newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"success"}""")
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

        val rawPrompt = json.get("prompt")?.asString
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                """{"error":"missing prompt"}""")

        if (rawPrompt.isBlank()) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", 
                """{"model":"gemma-4-e2b","response":"","done":true}""")
        }

        // Truncate prompt if it's way too long to avoid native crashes
        // Context window is 4096 tokens (~16k chars). 
        // 12000 chars allows safe overhead for output.
        val maxChars = 12000
        var isTruncated = false
        val truncatedPrompt = if (rawPrompt.length > maxChars) {
            Log.w(TAG, "Prompt too long (${rawPrompt.length} chars), truncating to $maxChars")
            isTruncated = true
            rawPrompt.substring(0, maxChars)
        } else {
            rawPrompt
        }

        // Sanitize prompt (Point #7) - KEEP NEWLINES for Gemma template!
        val sanitizedPrompt = truncatedPrompt.replace("\r", "")

        val modelName = json.get("model")?.asString ?: ""
        // Fix isGemma detection (Point #5)
        val isGemma = modelName.contains("gemma", ignoreCase = true) || sanitizedPrompt.contains("gemma", ignoreCase = true)

        // Standardize Gemma tokens (Point #2)
        val formattedPrompt = if (!sanitizedPrompt.contains("<start_of_turn>")) {
            if (isGemma) {
                Log.d(TAG, "Applying Gemma chat template")
                val systemMarker = "TASK:"
                if (sanitizedPrompt.contains(systemMarker)) {
                    val parts = sanitizedPrompt.split(systemMarker, limit = 2)
                    "<start_of_turn>system\n${parts[0].trim()}<end_of_turn>\n<start_of_turn>user\n$systemMarker ${parts[1].trim()}<end_of_turn>\n<start_of_turn>model\n"
                } else {
                    "<start_of_turn>user\n${sanitizedPrompt.trim()}<end_of_turn>\n<start_of_turn>model\n"
                }
            } else {
                sanitizedPrompt.trim() + "\n"
            }
        } else {
            Log.d(TAG, "Prompt already contains control tokens, skipping template")
            sanitizedPrompt
        }

        Log.i(TAG, "FULL PROMPT SENT TO ENGINE:\n$formattedPrompt\n--- END PROMPT ---")

        val startNs   = System.nanoTime()
        Log.d(TAG, "Waiting for inference lock...")
        
        val genResult = synchronized(inferLock) {
            try {
                // If it's a long prompt with instructions, use the warm session for KV cache reuse
                val systemMarker = "TASK:"
                val result = if (sanitizedPrompt.contains(systemMarker)) {
                    val parts = sanitizedPrompt.split(systemMarker, limit = 2)
                    val systemInstructions = parts[0].trim()
                    val taskData = systemMarker + " " + parts[1].trim()

                    val sysPrompt = if (systemInstructions.isNotEmpty()) {
                        "<start_of_turn>system\n$systemInstructions<end_of_turn>\n"
                    } else {
                        "" // Use cached system prompt
                    }
                    val userPrompt = "<start_of_turn>user\n$taskData<end_of_turn>\n<start_of_turn>model\n"

                    Log.i(TAG, "Using generateWithSystem for KV cache reuse")
                    GemmaEngine.generateWithSystem(sysPrompt, userPrompt)
                } else {
                    GemmaEngine.generate(formattedPrompt)
                }
                NodeStats.addRequest(true)
                result
            } catch (e: Exception) {
                Log.e(TAG, "Inference error: ${e.message}")
                NodeStats.addRequest(false)
                GemmaEngine.GenerationResult("Error during inference: ${e.message}", 0, 0, 0)
            }
        }
        val response = genResult.text
        val durationNs = System.nanoTime() - startNs
        Log.i(TAG, "FULL RESPONSE FROM ENGINE:\n$response\n--- END RESPONSE ---")
        Log.i(TAG, "Response generated in ${durationNs / 1_000_000}ms (Prefill: ${genResult.prefillTimeMs}ms, Decode: ${genResult.decodeTimeMs}ms)")

        // Accurate token count from engine
        val evalCount = genResult.tokenCount
        NodeStats.addTokens(evalCount)

        // Fix JSON output enforcement (Point #3)
        val shouldBeJson = json.get("format")?.asString == "json"

        // Clean up common JSON errors produced by LLMs (trailing commas, markdown blocks)
        var cleanedResponse = response.trim()
            .removeSurrounding("```json", "```")
            .removeSurrounding("```")
            .trim()
            // Fix keyword confusion (Point #8)
            .replace(":\\s*null\"".toRegex(), ": null")
            .replace(":\\s*\"null\"".toRegex(), ": null")
            // Fix trailing commas before a closing brace or bracket
            .replace(",\\s*([}\\]])".toRegex(), "$1")

        // Point #7: Strict escaping for raw control characters within string values
        // Python's json.loads(strict=True) fails on raw \n, \r, \t inside double quotes.
        // We find all content between quotes and escape literal control bytes.
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < cleanedResponse.length) {
            val c = cleanedResponse[i]
            if (c == '\"' && (i == 0 || cleanedResponse[i-1] != '\\')) {
                inQuotes = !inQuotes
                sb.append(c)
            } else if (inQuotes) {
                when (c) {
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    in '\u0000'..'\u001f' -> { /* ignore other control chars */ }
                    else -> sb.append(c)
                }
            } else {
                sb.append(c)
            }
            i++
        }
        cleanedResponse = sb.toString()

        if (shouldBeJson) {
            try {
                JsonParser.parseString(cleanedResponse)
            } catch (e: Exception) {
                Log.w(TAG, "Output is not valid JSON, attempting aggressive recovery")
                
                // Aggressive Recovery: Find the last valid result object boundary
                val lastItemEnd = cleanedResponse.lastIndexOf("}")
                if (lastItemEnd != -1) {
                    val partial = cleanedResponse.substring(0, lastItemEnd + 1)
                    
                    // If it's a list, try to close it properly at the last valid item
                    if (partial.contains("\"results\":")) {
                         // Remove trailing garbage/partial items after the last '}'
                         val cleanPartial = partial.trim().removeSuffix(",")
                         cleanedResponse = if (cleanPartial.endsWith("]")) {
                             cleanPartial + "}"
                         } else if (cleanPartial.endsWith("}")) {
                             cleanPartial + "]}"
                         } else {
                             cleanPartial
                         }
                         
                         // Double check: if it still fails, find the last } before that
                         try { JsonParser.parseString(cleanedResponse) }
                         catch (e2: Exception) {
                             val secondLast = partial.substring(0, partial.length - 1).lastIndexOf("}")
                             if (secondLast != -1) {
                                 cleanedResponse = partial.substring(0, secondLast + 1).trim().removeSuffix(",") + "]}"
                             }
                         }
                    }
                }
            }
        }

        val result = JsonObject().apply {
            addProperty("model", "gemma-4-e2b")
            addProperty("response", cleanedResponse)
            addProperty("done", true)
            addProperty("total_duration", durationNs)
            addProperty("load_duration", 0L)
            addProperty("prompt_eval_duration", genResult.prefillTimeMs * 1_000_000) 
            addProperty("eval_duration", genResult.decodeTimeMs * 1_000_000)
            addProperty("eval_count", evalCount)
            addProperty("prompt_eval_count", truncatedPrompt.length / 4) // Still an estimate
        }

        val resp = newFixedLengthResponse(Response.Status.OK, "application/json", result.toString())
        if (isTruncated) {
            resp.addHeader("X-Warning", "Prompt truncated to $maxChars chars")
        }
        return resp
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
        return String(buf, 0, offset, Charsets.UTF_8)
    }
}
