package com.akbay.node

object LlamaEngine {

    init {
        System.loadLibrary("llama_jni")
        initBackend()
    }

    @Volatile var modelHandle: Long = 0
    @Volatile var contextHandle: Long = 0
    @Volatile var isLoaded: Boolean = false

    private external fun initBackend()
    private external fun freeBackend()
    private external fun loadModel(path: String, nGpuLayers: Int): Long
    private external fun createContext(modelHandle: Long, nCtx: Int): Long
    external fun warmCache(ctxHandle: Long, prefix: String)
    external fun generate(ctxHandle: Long, prompt: String, temperature: Float, topP: Float, topK: Int, maxTokens: Int): String
    private external fun freeContext(ctxHandle: Long)
    private external fun freeModel(modelHandle: Long)

    fun load(modelPath: String, nGpuLayers: Int = 0, nCtx: Int = 2048): Boolean {
        modelHandle = loadModel(modelPath, nGpuLayers)
        if (modelHandle == 0L) return false
        contextHandle = createContext(modelHandle, nCtx)
        if (contextHandle == 0L) { freeModel(modelHandle); return false }
        isLoaded = true
        return true
    }

    fun unload() {
        isLoaded = false
        if (contextHandle != 0L) { freeContext(contextHandle); contextHandle = 0 }
        if (modelHandle   != 0L) { freeModel(modelHandle);   modelHandle   = 0 }
    }
}
