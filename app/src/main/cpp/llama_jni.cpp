#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>
#include <algorithm>
#include <chrono>

// llama.cpp cloned into app/src/main/cpp/llama.cpp/
#include "llama.cpp/include/llama.h"

#define LOG_TAG "BayanihanJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int g_prefix_n_tokens = 0;

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
    LOGI("Loading model: %s", path);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = nGpuLayers;
    mp.use_mmap = false;
    mp.use_mlock = true;

    llama_model* model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jPath, path);

    if (!model) { LOGE("llama_load_model_from_file failed"); return 0; }
    LOGI("Model loaded OK");
    return (jlong)(uintptr_t)model;
}

JNIEXPORT void JNICALL
Java_com_bayanihan_node_LlamaEngine_freeModel(JNIEnv*, jobject, jlong handle) {
    llama_model_free(reinterpret_cast<llama_model*>(handle));
    g_prefix_n_tokens = 0;
}

// ── context ──────────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_bayanihan_node_LlamaEngine_createContext(JNIEnv*, jobject,
                                                   jlong modelHandle, jint nCtx) {
    auto* model = reinterpret_cast<llama_model*>(modelHandle);
    if (!model) return 0;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx          = nCtx;
    cp.n_batch        = 2048;
    cp.n_threads      = 4;
    cp.n_threads_batch = 4;

    llama_context* ctx = llama_init_from_model(model, cp);
    if (!ctx) { LOGE("llama_init_from_model failed"); return 0; }
    LOGI("Context created OK (n_ctx=%d)", nCtx);
    return (jlong)(uintptr_t)ctx;
}

JNIEXPORT void JNICALL
Java_com_bayanihan_node_LlamaEngine_freeContext(JNIEnv*, jobject, jlong handle) {
    llama_free(reinterpret_cast<llama_context*>(handle));
    g_prefix_n_tokens = 0;
}

// ── cache warming ────────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_bayanihan_node_LlamaEngine_warmCache(JNIEnv* env, jobject,
                                               jlong ctxHandle, jstring jPrefix) {
    auto* ctx = reinterpret_cast<llama_context*>(ctxHandle);
    if (!ctx) return;
    auto* model = llama_get_model(ctx);
    const auto* vocab = llama_model_get_vocab(model);

    const char* cPrefix = env->GetStringUTFChars(jPrefix, nullptr);
    std::string prefix(cPrefix);
    env->ReleaseStringUTFChars(jPrefix, cPrefix);

    LOGI("Warming cache with prefix (length %zu)", prefix.size());

    llama_memory_t mem = llama_get_memory(ctx);
    if (mem) llama_memory_clear(mem, true);

    std::vector<llama_token> toks(prefix.size() + 128);
    int nToks = llama_tokenize(vocab, prefix.c_str(), (int)prefix.size(),
                               toks.data(), (int)toks.size(), true, true);
    if (nToks < 0) {
        toks.resize(-nToks);
        nToks = llama_tokenize(vocab, prefix.c_str(), (int)prefix.size(),
                               toks.data(), (int)toks.size(), true, true);
    }
    if (nToks <= 0) return;
    toks.resize(nToks);

    // Use larger batches for faster processing
    int32_t n_batch_limit = 512;
    for (int i = 0; i < nToks; i += n_batch_limit) {
        int32_t n_eval = std::min(n_batch_limit, (int32_t)nToks - i);
        llama_batch batch = llama_batch_init(n_eval, 0, 1);
        batch.n_tokens = n_eval;
        for (int j = 0; j < n_eval; j++) {
            batch.token[j]     = toks[i + j];
            batch.pos[j]       = i + j;
            batch.n_seq_id[j]  = 1;
            batch.seq_id[j][0] = 0;
            batch.logits[j]    = 0;
        }
        if (llama_decode(ctx, batch) != 0) {
            LOGE("warmCache: llama_decode failed at %d", i);
            llama_batch_free(batch);
            g_prefix_n_tokens = 0;
            return;
        }
        llama_batch_free(batch);
        LOGI("Warmed cache: %d/%d tokens", i + n_eval, nToks);
    }

    g_prefix_n_tokens = nToks;
    LOGI("Cache warmed with %d tokens", g_prefix_n_tokens);
}

// ── generation ───────────────────────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_com_bayanihan_node_LlamaEngine_generate(JNIEnv* env, jobject,
                                              jlong ctxHandle, jstring jPrompt,
                                              jfloat temperature, jfloat topP,
                                              jint topK, jint maxTokens) {
    auto* ctx = reinterpret_cast<llama_context*>(ctxHandle);
    if (!ctx) return env->NewStringUTF("Error: Null context");

    auto* model = llama_get_model(ctx);
    const auto* vocab = llama_model_get_vocab(model);

    const char* cPrompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(cPrompt);
    env->ReleaseStringUTFChars(jPrompt, cPrompt);

    // tokenise
    std::vector<llama_token> toks(prompt.size() + 128);
    int nToks = llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(),
                               toks.data(), (int)toks.size(), false, true); // false to add_bos since it's likely part of prefix or already handled
    if (nToks < 0) {
        toks.resize(-nToks);
        nToks = llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(),
                               toks.data(), (int)toks.size(), false, true);
    }
    if (nToks <= 0) return env->NewStringUTF("");
    toks.resize(nToks);

    llama_memory_t mem = llama_get_memory(ctx);
    if (mem) {
        if (g_prefix_n_tokens > 0) {
            LOGI("Trimming KV cache: keeping prefix (%d tokens), removing suffix", g_prefix_n_tokens);
            // Fix: remove tokens AFTER the prefix (from g_prefix_n_tokens to end)
            // Sequence ID -1 means all sequences.
            llama_memory_seq_rm(mem, g_prefix_n_tokens, -1, -1);
        } else {
            llama_memory_clear(mem, true);
        }
    }

    LOGI("Processing prompt suffix (%d tokens)...", nToks);
    int32_t n_batch_limit = 512;
    for (int i = 0; i < nToks; i += n_batch_limit) {
        int32_t n_eval = std::min(n_batch_limit, (int32_t)nToks - i);

        llama_batch batch = llama_batch_init(n_eval, 0, 1);
        batch.n_tokens = n_eval;
        for (int j = 0; j < n_eval; j++) {
            batch.token[j]     = toks[i + j];
            batch.pos[j]       = g_prefix_n_tokens + i + j;
            batch.n_seq_id[j]  = 1;
            batch.seq_id[j][0] = 0;
            batch.logits[j]    = (j == n_eval - 1 && i + n_eval == nToks);
        }

        if (llama_decode(ctx, batch) != 0) {
            LOGE("llama_decode failed");
            llama_batch_free(batch);
            return env->NewStringUTF("Error: Decode failed");
        }
        llama_batch_free(batch);
        LOGI("Processing prompt suffix: %d/%d tokens...", i + n_eval, nToks);
    }

    // sampler chain
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(std::max(0.01f, temperature)));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string out;
    int nPos = g_prefix_n_tokens + nToks;

    LOGI("Prompt decoded. Starting generation...");

    for (int i = 0; i < maxTokens; i++) {
        if (nPos >= llama_n_ctx(ctx)) break;

        llama_token tok = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        char piece[256];
        int n = llama_token_to_piece(vocab, tok, piece, sizeof(piece) - 1, 0, true);
        if (n > 0) {
            piece[n] = '\0';
            out += piece;
            if (i % 5 == 0) LOGI("Generating... current text: %s", out.c_str());
        }

        llama_batch next = llama_batch_init(1, 0, 1);
        next.n_tokens     = 1;
        next.token[0]     = tok;
        next.pos[0]       = nPos++;
        next.n_seq_id[0]  = 1;
        next.seq_id[0][0] = 0;
        next.logits[0]    = 1;

        if (llama_decode(ctx, next) != 0) {
            llama_batch_free(next);
            break;
        }
        llama_batch_free(next);
    }

    LOGI("Done.");
    llama_sampler_free(smpl);
    return env->NewStringUTF(out.c_str());
}

} // extern "C"
