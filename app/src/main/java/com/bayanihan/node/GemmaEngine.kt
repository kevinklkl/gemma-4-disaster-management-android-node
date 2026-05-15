package com.bayanihan.node

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

object GemmaEngine {
    private const val TAG = "GemmaEngine"
    private var llmInference: LlmInference? = null
    @Volatile var isLoaded: Boolean = false

    fun load(context: Context, modelPath: String, maxTokens: Int = 2048, temperature: Float = 0.7f): Boolean {
        if (!File(modelPath).exists()) {
            Log.e(TAG, "Model file does not exist at $modelPath")
            return false
        }
        
        return try {
            Log.i(TAG, "Initializing LlmInference with model: $modelPath")
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(maxTokens)
                .build()
            
            llmInference = LlmInference.createFromOptions(context, options)
            Log.i(TAG, "LlmInference created successfully")
            isLoaded = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create LlmInference: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    fun generate(prompt: String): String {
        return llmInference?.generateResponse(prompt) ?: ""
    }

    fun unload() {
        llmInference?.close()
        llmInference = null
        isLoaded = false
    }
}
