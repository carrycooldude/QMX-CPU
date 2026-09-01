package com.example.qmx_cpu

interface TokenCallback {
    fun onToken(piece: String)
}

object InferenceBridge {
    init {
        try {
            System.loadLibrary("omp")
            System.loadLibrary("ggml-base")
            System.loadLibrary("ggml")
            System.loadLibrary("ggml-cpu")
            System.loadLibrary("llama")
            System.loadLibrary("qmx_native")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    external fun nativeInit(modelPath: String, nThreads: Int, enableSme: Boolean): Boolean
    external fun nativeGetSystemInfo(): String
    external fun nativeGenerate(prompt: String, maxTokens: Int, callback: TokenCallback?): String
    external fun nativeFree()
}
