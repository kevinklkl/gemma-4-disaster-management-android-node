package com.akbay.node

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Backend
import java.io.File
import kotlinx.coroutines.runBlocking

object GemmaEngine {
    private const val TAG = "GemmaEngine"
    private var engine: Engine? = null
    private var defaultSystemPrompt: String? = null
    
    init {
        try {
            // Pre-load core LiteRT and JNI libraries to prevent "No implementation found" errors
            System.loadLibrary("LiteRt")
            System.loadLibrary("litertlm_jni")
            Log.i(TAG, "Successfully pre-loaded libLiteRt.so and liblitertlm_jni.so")
            
            // Try to pre-load optional GPU samplers
            try { System.loadLibrary("LiteRtTopKWebGpuSampler") } catch (e: Throwable) {}
            try { System.loadLibrary("LiteRtTopKOpenClSampler") } catch (e: Throwable) {}
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native libraries not found in APK: ${e.message}")
        }
    }

    @Volatile var isLoaded: Boolean = false
    private val loadLock = Any()
    private val inferenceLock = Any()

    fun load(context: Context, modelPath: String, maxTokens: Int = 4096): Boolean {
        synchronized(loadLock) {
            if (isLoaded && (engine != null)) {
                Log.i(TAG, "Model already loaded, skipping.")
                return true
            }

            if (!File(modelPath).exists()) {
                Log.e(TAG, "Model file does not exist at $modelPath")
                return false
            }

            return try {
                Log.i(TAG, "Initializing LiteRT-LM Engine with model: $modelPath (maxTokens: $maxTokens)")
                
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.GPU(),
                    maxNumTokens = maxTokens
                )

                synchronized(inferenceLock) {
                    engine?.close()
                    engine = Engine(config)
                    engine?.initialize() 
                }
                
                Log.i(TAG, "LiteRT-LM Engine initialized successfully")
                isLoaded = true
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize LiteRT-LM: ${e.message}")
                e.printStackTrace()
                isLoaded = false
                false
            }
        }
    }

    data class GenerationResult(
        val text: String,
        val prefillTimeMs: Long,
        val decodeTimeMs: Long,
        val tokenCount: Int
    )

    fun generate(prompt: String): GenerationResult {
        Log.i(TAG, "Generating response for prompt length: ${prompt.length}...")
        val startTime = System.currentTimeMillis()
        var firstTokenTime = 0L
        var tokenCount = 0
        val fullResponse = StringBuilder()
        
        synchronized(inferenceLock) {
            val currentEngine = engine ?: return GenerationResult("Engine not loaded", 0, 0, 0)

            val conversation = currentEngine.createConversation()
            try {
                runBlocking {
                    conversation.sendMessageAsync(prompt).collect { token ->
                        if (tokenCount == 0) {
                            firstTokenTime = System.currentTimeMillis()
                        }
                        tokenCount++
                        fullResponse.append(token)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Inference error: ${e.message}")
                fullResponse.append("Error: ${e.message}")
            } finally {
                conversation.close()
            }
        }
        
        val endTime = System.currentTimeMillis()
        val prefillDuration = if (firstTokenTime > 0) firstTokenTime - startTime else (endTime - startTime)
        val decodeDuration = if (firstTokenTime > 0) endTime - firstTokenTime else 0L
        
        Log.i(TAG, "Generation complete. Prefill: ${prefillDuration}ms, Decode: ${decodeDuration}ms, Tokens: $tokenCount")
        return GenerationResult(fullResponse.toString(), prefillDuration, decodeDuration, tokenCount)
    }

    fun generateWithSystem(systemPrompt: String, userPrompt: String, temperature: Float = 0.2f, topK: Int = 20): GenerationResult {
        if (systemPrompt.length > 50) {
            Log.i(TAG, "Updating default system prompt cache (${systemPrompt.length} chars)")
            defaultSystemPrompt = systemPrompt
        }

        val effectiveSystem = if (systemPrompt.length > 50) systemPrompt else (defaultSystemPrompt ?: "")
        
        if (effectiveSystem.isBlank()) {
            Log.w(TAG, "Both provided and cached system prompts are empty!")
        }

        Log.d(TAG, "generateWithSystem called. Final system length: ${effectiveSystem.length}")
        return generate(effectiveSystem + userPrompt)
    }

    fun unload() {
        synchronized(inferenceLock) {
            engine?.close()
            engine = null
            isLoaded = false
        }
    }
}
