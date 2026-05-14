#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>

// llama.cpp cloned into app/src/main/cpp/llama.cpp/
#include "llama.cpp/include/llama.h"

#define LOG_TAG "BayanihanJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

// ── backend lifecycle ────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_bayanihan_node_LlamaEngine_initBackend(JNIEnv*, jobject) {
    llama_backend_init();
    LOGI("llama backend initialised");
}

JNIEXPORT void JNICALL
Java_com_bayanihan_node_LlamaEngine_freeBackend(JNIEnv*, jobject) {
    llama_backend_free();
}

// ── model ────────────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_bayanihan_node_LlamaEngine_loadModel(JNIEnv* env, jobject,
                                               jstring jPath, jint nGpuLayers) {
    const char* path = env->GetStringUTFChars(jPath, nullptr);
    LOGI("Loading model: %s  gpu_layers=%d", path, nGpuLayers);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = nGpuLayers;

    llama_model* model = llama_load_model_from_file(path, mp);
    env->ReleaseStringUTFChars(jPath, path);

    if (!model) { LOGE("llama_load_model_from_file failed"); return 0; }
    LOGI("Model loaded OK");
    return (jlong)(uintptr_t)model;
}

JNIEXPORT void JNICALL
Java_com_bayanihan_node_LlamaEngine_freeModel(JNIEnv*, jobject, jlong handle) {
    llama_free_model(reinterpret_cast<llama_model*>(handle));
}

// ── context ──────────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_bayanihan_node_LlamaEngine_createContext(JNIEnv*, jobject,
                                                   jlong modelHandle, jint nCtx) {
    auto* model = reinterpret_cast<llama_model*>(modelHandle);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx          = nCtx;
    cp.n_batch        = 512;
    cp.n_threads      = 4;
    cp.n_threads_batch = 4;

    llama_context* ctx = llama_new_context_with_model(model, cp);
    if (!ctx) { LOGE("llama_new_context_with_model failed"); return 0; }
    return (jlong)(uintptr_t)ctx;
}

JNIEXPORT void JNICALL
Java_com_bayanihan_node_LlamaEngine_freeContext(JNIEnv*, jobject, jlong handle) {
    llama_free(reinterpret_cast<llama_context*>(handle));
}

// ── generation ───────────────────────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_com_bayanihan_node_LlamaEngine_generate(JNIEnv* env, jobject,
                                              jlong ctxHandle, jstring jPrompt,
                                              jfloat temperature, jint maxTokens) {
    auto* ctx   = reinterpret_cast<llama_context*>(ctxHandle);
    auto* model = llama_get_model(ctx);

    const char* cPrompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(cPrompt);
    env->ReleaseStringUTFChars(jPrompt, cPrompt);

    // tokenise
    std::vector<llama_token> toks(prompt.size() + 32);
    int nToks = llama_tokenize(model, prompt.c_str(), (int)prompt.size(),
                               toks.data(), (int)toks.size(), true, true);
    if (nToks < 0) {
        LOGE("tokenize failed");
        return env->NewStringUTF("");
    }
    toks.resize(nToks);

    // clear previous state
    llama_kv_cache_clear(ctx);

    // process prompt in one batch
    llama_batch batch = llama_batch_init((int)toks.size(), 0, 1);
    batch.n_tokens = (int)toks.size();
    for (int i = 0; i < (int)toks.size(); i++) {
        batch.token[i]     = toks[i];
        batch.pos[i]       = i;
        batch.n_seq_id[i]  = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i]    = (i == (int)toks.size() - 1) ? 1 : 0;
    }
    if (llama_decode(ctx, batch) != 0) {
        LOGE("prompt decode failed");
        llama_batch_free(batch);
        return env->NewStringUTF("");
    }
    llama_batch_free(batch);

    // sampler chain
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature > 0.0f ? temperature : 0.01f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string out;
    int nPos = (int)toks.size();

    for (int i = 0; i < maxTokens; i++) {
        llama_token tok = llama_sampler_sample(smpl, ctx, -1);
        if (llama_token_is_eog(model, tok)) break;

        char piece[256];
        int n = llama_token_to_piece(model, tok, piece, sizeof(piece) - 1, 0, true);
        if (n > 0) { piece[n] = '\0'; out += piece; }

        llama_batch next = llama_batch_init(1, 0, 1);
        next.n_tokens     = 1;
        next.token[0]     = tok;
        next.pos[0]       = nPos++;
        next.n_seq_id[0]  = 1;
        next.seq_id[0][0] = 0;
        next.logits[0]    = 1;

        if (llama_decode(ctx, next) != 0) {
            llama_batch_free(next);
            LOGE("decode failed at token %d", i);
            break;
        }
        llama_batch_free(next);
    }

    llama_sampler_free(smpl);
    return env->NewStringUTF(out.c_str());
}

} // extern "C"
