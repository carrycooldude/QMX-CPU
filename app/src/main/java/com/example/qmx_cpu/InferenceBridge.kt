package com.example.qmx_cpu

interface TokenCallback {
    fun onToken(piece: String)
}

interface PcmCallback {
    fun onPcmChunk(pcmData: ShortArray)
}

object InferenceBridge {
    init {
        try {
            System.loadLibrary("omp")
            System.loadLibrary("ggml-base")
            System.loadLibrary("ggml")
            System.loadLibrary("ggml-cpu")
            System.loadLibrary("llama")
            System.loadLibrary("llama-common")
            System.loadLibrary("mtmd")
            System.loadLibrary("qmx_native")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    // Chat LLM (Gemma)
    external fun nativeInit(modelPath: String, nThreads: Int, enableSme: Boolean): Boolean
    external fun nativeGetSystemInfo(): String
    external fun nativeGenerate(prompt: String, maxTokens: Int, callback: TokenCallback?): String
    external fun nativeFree()

    // Qwen3-TTS
    external fun nativeTtsInit(backbonePath: String, mmprojPath: String, nThreads: Int): Boolean
    external fun nativeTtsGenerate(text: String, lang: String, outputWavPath: String): Boolean
    external fun nativeTtsGenerateStream(text: String, lang: String, callback: PcmCallback): Boolean
    external fun nativeTtsFree()
}
