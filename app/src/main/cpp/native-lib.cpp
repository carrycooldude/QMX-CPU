#include <jni.h>
#include <string>
#include <vector>
#include <sstream>
#include <mutex>
#include <android/log.h>
#include <unistd.h>
#include <cstdlib>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "QmxNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ============================================================================
// Chat LLM State (Gemma)
// ============================================================================
static struct {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    llama_sampler* smpl = nullptr;
    bool is_initialized = false;
} g_llama;

// ============================================================================
// TTS State (Qwen3-TTS)
// ============================================================================
static struct {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    llama_sampler* smpl = nullptr;
    mtmd_context* mctx = nullptr;
    bool is_initialized = false;
} g_tts;

static std::mutex g_tts_mutex;

// ============================================================================
// Chat LLM JNI Functions
// ============================================================================

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_qmx_1cpu_InferenceBridge_nativeInit(
        JNIEnv* env,
        jobject /* this */,
        jstring model_path_j,
        jint n_threads,
        jboolean enable_sme) {

    if (g_llama.is_initialized) {
        LOGI("Model already initialized, releasing previous instance.");
        if (g_llama.smpl) { llama_sampler_free(g_llama.smpl); g_llama.smpl = nullptr; }
        if (g_llama.ctx) { llama_free(g_llama.ctx); g_llama.ctx = nullptr; }
        if (g_llama.model) { llama_model_free(g_llama.model); g_llama.model = nullptr; }
        g_llama.is_initialized = false;
    }

    if (enable_sme) {
        setenv("GGML_KLEIDIAI_SME", "1", 1);
        LOGI("Enabled GGML_KLEIDIAI_SME=1");
    } else {
        setenv("GGML_KLEIDIAI_SME", "0", 1);
        LOGI("Set GGML_KLEIDIAI_SME=0 (I8MM mode)");
    }

    llama_backend_init();

    const char* model_path = env->GetStringUTFChars(model_path_j, nullptr);
    LOGI("Loading model from: %s", model_path);

    llama_model_params mparams = llama_model_default_params();
    g_llama.model = llama_model_load_from_file(model_path, mparams);
    env->ReleaseStringUTFChars(model_path_j, model_path);

    if (!g_llama.model) {
        LOGE("Failed to load model from file.");
        return JNI_FALSE;
    }

    g_llama.vocab = llama_model_get_vocab(g_llama.model);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 2048;
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = n_threads > 0 ? n_threads : 4;
    cparams.n_threads_batch = cparams.n_threads;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;

    g_llama.ctx = llama_init_from_model(g_llama.model, cparams);
    if (!g_llama.ctx) {
        LOGE("Failed to initialize llama context.");
        llama_model_free(g_llama.model);
        g_llama.model = nullptr;
        return JNI_FALSE;
    }

    // Initialize standard sampler chain
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    g_llama.smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(g_llama.smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(g_llama.smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(g_llama.smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(g_llama.smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    g_llama.is_initialized = true;
    LOGI("Model initialized successfully with %d threads!", cparams.n_threads);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_qmx_1cpu_InferenceBridge_nativeGetSystemInfo(
        JNIEnv* env,
        jobject /* this */) {
    const char* sys_info = llama_print_system_info();
    return env->NewStringUTF(sys_info ? sys_info : "Unknown");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_qmx_1cpu_InferenceBridge_nativeGenerate(
        JNIEnv* env,
        jobject /* this */,
        jstring prompt_j,
        jint max_tokens,
        jobject callback) {

    if (!g_llama.is_initialized || !g_llama.model || !g_llama.ctx) {
        return env->NewStringUTF("Error: Model not initialized.");
    }

    const char* prompt_cstr = env->GetStringUTFChars(prompt_j, nullptr);
    std::string user_prompt = prompt_cstr;
    env->ReleaseStringUTFChars(prompt_j, prompt_cstr);

    // Format prompt using Gemma Chat Template
    std::string formatted_prompt = "<start_of_turn>user\n" + user_prompt + "<end_of_turn>\n<start_of_turn>model\n";

    // Tokenize prompt
    const int n_prompt_max = 2048;
    std::vector<llama_token> prompt_tokens(n_prompt_max);
    int n_tokens = llama_tokenize(
            g_llama.vocab,
            formatted_prompt.c_str(),
            formatted_prompt.length(),
            prompt_tokens.data(),
            prompt_tokens.size(),
            true,  // add_special
            true   // parse_special
    );

    if (n_tokens < 0) {
        prompt_tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(
                g_llama.vocab,
                formatted_prompt.c_str(),
                formatted_prompt.length(),
                prompt_tokens.data(),
                prompt_tokens.size(),
                true,
                true
        );
    }
    prompt_tokens.resize(n_tokens);

    // Prepare callback method ID if provided
    jclass cb_class = nullptr;
    jmethodID cb_on_token = nullptr;
    if (callback != nullptr) {
        cb_class = env->GetObjectClass(callback);
        if (cb_class != nullptr) {
            cb_on_token = env->GetMethodID(cb_class, "onToken", "(Ljava/lang/String;)V");
        }
    }

    // Prefill Phase
    int64_t t_start_pp = llama_time_us();
    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), prompt_tokens.size());
    if (llama_decode(g_llama.ctx, batch) != 0) {
        LOGE("Failed to decode prompt tokens.");
        return env->NewStringUTF("Error during prompt evaluation.");
    }
    int64_t t_end_pp = llama_time_us();
    double pp_ms = (t_end_pp - t_start_pp) / 1000.0;
    double pp_ts = (n_tokens / (pp_ms / 1000.0));

    // Decode Phase (Generation)
    std::string generated_text = "";
    int n_generated = 0;
    int64_t t_start_gen = llama_time_us();

    for (int i = 0; i < max_tokens; ++i) {
        llama_token new_token = llama_sampler_sample(g_llama.smpl, g_llama.ctx, -1);
        llama_sampler_accept(g_llama.smpl, new_token);

        if (llama_vocab_is_eog(g_llama.vocab, new_token)) {
            break;
        }

        char piece_buf[256];
        int n_piece = llama_token_to_piece(g_llama.vocab, new_token, piece_buf, sizeof(piece_buf), 0, true);
        if (n_piece > 0) {
            std::string piece_str(piece_buf, n_piece);
            generated_text += piece_str;

            if (cb_on_token != nullptr) {
                jstring piece_j = env->NewStringUTF(piece_str.c_str());
                env->CallVoidMethod(callback, cb_on_token, piece_j);
                env->DeleteLocalRef(piece_j);
            }
        }
        n_generated++;

        llama_batch next_batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(g_llama.ctx, next_batch) != 0) {
            LOGE("Failed to decode generated token.");
            break;
        }
    }
    int64_t t_end_gen = llama_time_us();
    double gen_ms = (t_end_gen - t_start_gen) / 1000.0;
    double gen_ts = n_generated > 0 ? (n_generated / (gen_ms / 1000.0)) : 0.0;

    LOGI("Generation complete: %d tokens in %.2f ms (%.2f tok/s). Prefill: %.2f tok/s.",
         n_generated, gen_ms, gen_ts, pp_ts);

    std::ostringstream stats;
    stats << generated_text << "\n\n"
          << "─── ⚡ QMX Hardware Stats ───\n"
          << "• Prefill: " << (int)pp_ts << " tok/s (" << (int)pp_ms << " ms)\n"
          << "• Generation: " << (int)gen_ts << " tok/s (" << n_generated << " tokens)\n";

    return env->NewStringUTF(stats.str().c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_qmx_1cpu_InferenceBridge_nativeFree(
        JNIEnv* /* env */,
        jobject /* this */) {
    if (g_llama.smpl) { llama_sampler_free(g_llama.smpl); g_llama.smpl = nullptr; }
    if (g_llama.ctx) { llama_free(g_llama.ctx); g_llama.ctx = nullptr; }
    if (g_llama.model) { llama_model_free(g_llama.model); g_llama.model = nullptr; }
    g_llama.is_initialized = false;
    LOGI("Chat model resources freed.");
}

// ============================================================================
// Qwen3-TTS JNI Functions
// ============================================================================

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_qmx_1cpu_InferenceBridge_nativeTtsInit(
        JNIEnv* env,
        jobject /* this */,
        jstring backbone_path_j,
        jstring mmproj_path_j,
        jint n_threads) {

    // Free any previous TTS context
    if (g_tts.is_initialized) {
        if (g_tts.smpl) { llama_sampler_free(g_tts.smpl); g_tts.smpl = nullptr; }
        if (g_tts.mctx) { mtmd_free(g_tts.mctx); g_tts.mctx = nullptr; }
        if (g_tts.ctx) { llama_free(g_tts.ctx); g_tts.ctx = nullptr; }
        if (g_tts.model) { llama_model_free(g_tts.model); g_tts.model = nullptr; }
        g_tts.is_initialized = false;
    }

    const char* backbone_path = env->GetStringUTFChars(backbone_path_j, nullptr);
    const char* mmproj_path = env->GetStringUTFChars(mmproj_path_j, nullptr);

    LOGI("TTS: Loading backbone from: %s", backbone_path);
    LOGI("TTS: Loading mmproj from: %s", mmproj_path);

    // Load backbone model
    llama_model_params mparams = llama_model_default_params();
    g_tts.model = llama_model_load_from_file(backbone_path, mparams);
    if (!g_tts.model) {
        LOGE("TTS: Failed to load backbone model.");
        env->ReleaseStringUTFChars(backbone_path_j, backbone_path);
        env->ReleaseStringUTFChars(mmproj_path_j, mmproj_path);
        return JNI_FALSE;
    }

    g_tts.vocab = llama_model_get_vocab(g_tts.model);

    // Create context with embedding mode enabled (required for TTS hidden states)
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 4096;
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = n_threads > 0 ? n_threads : 1;
    cparams.n_threads_batch = cparams.n_threads;
    cparams.embeddings = true;  // Required for TTS hidden state extraction

    g_tts.ctx = llama_init_from_model(g_tts.model, cparams);
    if (!g_tts.ctx) {
        LOGE("TTS: Failed to create llama context.");
        llama_model_free(g_tts.model);
        g_tts.model = nullptr;
        env->ReleaseStringUTFChars(backbone_path_j, backbone_path);
        env->ReleaseStringUTFChars(mmproj_path_j, mmproj_path);
        return JNI_FALSE;
    }

    // Load mmproj (audio decoder / vocoder)
    mtmd_context_params mtmd_params = mtmd_context_params_default();
    mtmd_params.use_gpu = false;  // CPU-only on Android
    g_tts.mctx = mtmd_init_from_file(mmproj_path, g_tts.model, mtmd_params);
    if (!g_tts.mctx) {
        LOGE("TTS: Failed to load mmproj.");
        llama_free(g_tts.ctx); g_tts.ctx = nullptr;
        llama_model_free(g_tts.model); g_tts.model = nullptr;
        env->ReleaseStringUTFChars(backbone_path_j, backbone_path);
        env->ReleaseStringUTFChars(mmproj_path_j, mmproj_path);
        return JNI_FALSE;
    }

    // Initialize sampler for semantic code generation
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    g_tts.smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(g_tts.smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(g_tts.smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(g_tts.smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(g_tts.smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    env->ReleaseStringUTFChars(backbone_path_j, backbone_path);
    env->ReleaseStringUTFChars(mmproj_path_j, mmproj_path);

    g_tts.is_initialized = true;
    LOGI("TTS: Qwen3-TTS initialized successfully with %d threads!", cparams.n_threads);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_qmx_1cpu_InferenceBridge_nativeTtsGenerate(
        JNIEnv* env,
        jobject /* this */,
        jstring text_j,
        jstring lang_j,
        jstring output_path_j) {

    if (!g_tts.is_initialized || !g_tts.model || !g_tts.ctx || !g_tts.mctx) {
        LOGE("TTS: Not initialized.");
        return JNI_FALSE;
    }

    std::unique_lock<std::mutex> lock(g_tts_mutex, std::try_to_lock);
    if (!lock.owns_lock()) {
        LOGE("TTS: Another generation is already in progress.");
        return JNI_FALSE;
    }

    const char* text = env->GetStringUTFChars(text_j, nullptr);
    const char* lang = env->GetStringUTFChars(lang_j, nullptr);
    const char* output_path = env->GetStringUTFChars(output_path_j, nullptr);

    LOGI("TTS: Generating speech for: '%.80s...' lang=%s output=%s", text, lang, output_path);

    // Clear KV memory from previous generations so prompt evaluation starts at position 0
    llama_memory_clear(llama_get_memory(g_tts.ctx), true);
    llama_sampler_reset(g_tts.smpl);

    // Set up audio generation using the mtmd C++ wrapper
    mtmd_helper::gen_audio gen(g_tts.ctx, g_tts.mctx);

    mtmd_helper_gen_audio_inp inp{};
    inp.seq_id     = 0;
    inp.prompt     = text;
    inp.prompt_len = strlen(text);
    inp.speaker_ref = nullptr;  // No voice cloning reference
    inp.lang       = lang;
    inp.top_k      = 40;
    inp.top_p      = 0.95f;
    inp.seed       = UINT32_MAX;  // Random seed
    inp.out_type   = MTMD_HELPER_GEN_AUDIO_OUTTYPE_WAV;

    // Stage 1: Process prompt through backbone
    if (gen.set_input(&inp) != 0) {
        LOGE("TTS: set_input failed");
        env->ReleaseStringUTFChars(text_j, text);
        env->ReleaseStringUTFChars(lang_j, lang);
        env->ReleaseStringUTFChars(output_path_j, output_path);
        return JNI_FALSE;
    }

    for (;;) {
        int32_t ret = gen.step_prompt(512);
        if (ret < 0) {
            LOGE("TTS: prompt processing failed");
            env->ReleaseStringUTFChars(text_j, text);
            env->ReleaseStringUTFChars(lang_j, lang);
            env->ReleaseStringUTFChars(output_path_j, output_path);
            return JNI_FALSE;
        }
        if (ret == 0) break;
    }

    // Sample semantic code and get hidden state
    auto sample_semantic = [&]() -> llama_token {
        llama_token t = llama_sampler_sample(g_tts.smpl, g_tts.ctx, -1);
        llama_sampler_accept(g_tts.smpl, t);
        return t;
    };

    const int max_frames = 150;
    int n_frames = 0;
    llama_token sampled = sample_semantic();
    const float* h_state = llama_get_embeddings_ith(g_tts.ctx, -1);

    int64_t t_gen_start = llama_time_us();
    bool stop = false;

    // Stage 2+3: Generate audio frames
    while (!stop && n_frames < max_frames) {
        const float* h_next = nullptr;

        if (gen.step_gen(sampled, h_state, &h_next, &stop) != 0) {
            LOGE("TTS: step_gen failed at frame %d", n_frames);
            break;
        }
        if (!h_next) break;

        n_frames++;
        h_state = h_next;
        sampled = sample_semantic();

        if (n_frames % 50 == 0) {
            double elapsed = (llama_time_us() - t_gen_start) / 1e6;
            LOGI("TTS: generated %d frames (%.1f fps)", n_frames, n_frames / elapsed);
        }
    }

    double t_gen_s = (llama_time_us() - t_gen_start) / 1e6;
    LOGI("TTS: Generated %d frames in %.2fs (%.1f fps)", n_frames, t_gen_s, n_frames / t_gen_s);

    // Stage 4: Get WAV output
    int32_t sample_rate = 0;
    const char* data = nullptr;
    size_t data_len = 0;
    int64_t n_samples = 0;

    if (gen.get_output(&sample_rate, &data, &data_len, &n_samples) != 0) {
        LOGE("TTS: get_output failed");
        env->ReleaseStringUTFChars(text_j, text);
        env->ReleaseStringUTFChars(lang_j, lang);
        env->ReleaseStringUTFChars(output_path_j, output_path);
        return JNI_FALSE;
    }

    LOGI("TTS: WAV output: %zu bytes, %d Hz, %lld samples (%.2fs audio)",
         data_len, sample_rate, (long long)n_samples,
         sample_rate > 0 ? (double)n_samples / sample_rate : 0.0);

    // Write WAV file
    FILE* f = fopen(output_path, "wb");
    if (!f) {
        LOGE("TTS: Failed to open output file: %s", output_path);
        env->ReleaseStringUTFChars(text_j, text);
        env->ReleaseStringUTFChars(lang_j, lang);
        env->ReleaseStringUTFChars(output_path_j, output_path);
        return JNI_FALSE;
    }
    fwrite(data, 1, data_len, f);
    fclose(f);

    LOGI("TTS: Wrote WAV to %s", output_path);

    env->ReleaseStringUTFChars(text_j, text);
    env->ReleaseStringUTFChars(lang_j, lang);
    env->ReleaseStringUTFChars(output_path_j, output_path);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_qmx_1cpu_InferenceBridge_nativeTtsGenerateStream(
        JNIEnv* env,
        jobject /* this */,
        jstring text_j,
        jstring lang_j,
        jobject callback) {

    if (!g_tts.is_initialized || !g_tts.model || !g_tts.ctx || !g_tts.mctx) {
        LOGE("TTS Stream: Not initialized.");
        return JNI_FALSE;
    }

    std::unique_lock<std::mutex> lock(g_tts_mutex, std::try_to_lock);
    if (!lock.owns_lock()) {
        LOGE("TTS Stream: Another generation is already in progress.");
        return JNI_FALSE;
    }

    const char* text = env->GetStringUTFChars(text_j, nullptr);
    const char* lang = env->GetStringUTFChars(lang_j, nullptr);

    LOGI("TTS Stream: Generating PCM for: '%.80s...' lang=%s", text, lang);

    // Clear KV memory from previous generations so prompt evaluation starts at position 0
    llama_memory_clear(llama_get_memory(g_tts.ctx), true);
    llama_sampler_reset(g_tts.smpl);

    jclass cb_class = env->GetObjectClass(callback);
    jmethodID cb_on_pcm = env->GetMethodID(cb_class, "onPcmChunk", "([S)V");
    if (!cb_on_pcm) {
        LOGE("TTS Stream: Callback method onPcmChunk not found");
        env->ReleaseStringUTFChars(text_j, text);
        env->ReleaseStringUTFChars(lang_j, lang);
        return JNI_FALSE;
    }

    mtmd_helper::gen_audio gen(g_tts.ctx, g_tts.mctx);

    mtmd_helper_gen_audio_inp inp{};
    inp.seq_id      = 0;
    inp.prompt      = text;
    inp.prompt_len  = strlen(text);
    inp.speaker_ref = nullptr;
    inp.lang        = lang;
    inp.top_k       = 40;
    inp.top_p       = 0.95f;
    inp.seed        = UINT32_MAX;
    inp.out_type    = MTMD_HELPER_GEN_AUDIO_OUTTYPE_PCM;

    if (gen.set_input(&inp) != 0) {
        LOGE("TTS Stream: set_input failed");
        env->ReleaseStringUTFChars(text_j, text);
        env->ReleaseStringUTFChars(lang_j, lang);
        return JNI_FALSE;
    }

    for (;;) {
        int32_t ret = gen.step_prompt(512);
        if (ret < 0) {
            LOGE("TTS Stream: prompt processing failed");
            env->ReleaseStringUTFChars(text_j, text);
            env->ReleaseStringUTFChars(lang_j, lang);
            return JNI_FALSE;
        }
        if (ret == 0) break;
    }

    auto sample_semantic = [&]() -> llama_token {
        llama_token t = llama_sampler_sample(g_tts.smpl, g_tts.ctx, -1);
        llama_sampler_accept(g_tts.smpl, t);
        return t;
    };

    const int max_frames = 150;
    int n_frames = 0;
    llama_token sampled = sample_semantic();
    const float* h_state = llama_get_embeddings_ith(g_tts.ctx, -1);

    int64_t t_gen_start = llama_time_us();
    bool stop = false;
    size_t last_sample_offset = 0;

    auto dispatch_pcm_chunks = [&](bool /* is_final */) {
        int32_t sample_rate = 0;
        const char* data = nullptr;
        size_t data_len = 0;
        int64_t n_samples = 0;
        if (gen.get_output(&sample_rate, &data, &data_len, &n_samples) == 0 && n_samples > (int64_t)last_sample_offset) {
            size_t new_samples = (size_t)n_samples - last_sample_offset;
            const float* pcm_floats = (const float*)data;
            std::vector<int16_t> pcm16(new_samples);
            for (size_t i = 0; i < new_samples; ++i) {
                float s = pcm_floats[last_sample_offset + i];
                float clamped = std::max(-1.0f, std::min(1.0f, s));
                pcm16[i] = (int16_t)(clamped * 32767.0f);
            }
            last_sample_offset = (size_t)n_samples;

            jshortArray j_pcm = env->NewShortArray((jsize)new_samples);
            env->SetShortArrayRegion(j_pcm, 0, (jsize)new_samples, (const jshort*)pcm16.data());
            env->CallVoidMethod(callback, cb_on_pcm, j_pcm);
            env->DeleteLocalRef(j_pcm);
            LOGI("TTS Stream: Dispatched %zu PCM samples (%.2fs audio) at frame %d",
                 new_samples, (double)new_samples / 24000.0, n_frames);
        }
    };

    while (!stop && n_frames < max_frames) {
        const float* h_next = nullptr;
        if (gen.step_gen(sampled, h_state, &h_next, &stop) != 0) {
            LOGE("TTS Stream: step_gen failed at frame %d", n_frames);
            break;
        }
        if (!h_next) break;

        n_frames++;
        h_state = h_next;
        sampled = sample_semantic();

        // Check and dispatch streaming audio every 25 frames (~2s audio)
        if (n_frames % 25 == 0) {
            dispatch_pcm_chunks(false);
        }
    }

    // Final flush
    dispatch_pcm_chunks(true);

    double t_gen_s = (llama_time_us() - t_gen_start) / 1e6;
    LOGI("TTS Stream: Complete: %d frames in %.2fs (%.1f fps), total %zu samples (%.2fs audio)",
         n_frames, t_gen_s, n_frames > 0 ? (n_frames / t_gen_s) : 0.0,
         last_sample_offset, (double)last_sample_offset / 24000.0);

    env->ReleaseStringUTFChars(text_j, text);
    env->ReleaseStringUTFChars(lang_j, lang);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_qmx_1cpu_InferenceBridge_nativeTtsFree(
        JNIEnv* /* env */,
        jobject /* this */) {
    if (g_tts.smpl) { llama_sampler_free(g_tts.smpl); g_tts.smpl = nullptr; }
    if (g_tts.mctx) { mtmd_free(g_tts.mctx); g_tts.mctx = nullptr; }
    if (g_tts.ctx) { llama_free(g_tts.ctx); g_tts.ctx = nullptr; }
    if (g_tts.model) { llama_model_free(g_tts.model); g_tts.model = nullptr; }
    g_tts.is_initialized = false;
    LOGI("TTS: Model resources freed.");
}
