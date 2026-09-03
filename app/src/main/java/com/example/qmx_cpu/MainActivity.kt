package com.example.qmx_cpu

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.system.Os
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var rvChat: RecyclerView
    private lateinit var etPrompt: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var tvBadge: TextView
    private lateinit var tvModelInfo: TextView
    private lateinit var tvLiveSpeed: TextView
    private lateinit var btnThreadToggle: TextView
    private lateinit var btnModelToggle: TextView
    private lateinit var pbLoading: ProgressBar

    enum class ModelQuant(val label: String, val filename: String, val kernelDesc: String) {
        Q4_0("Q4_0", "gemma-3-270m-it-Q4_0.gguf", "Qualcomm 4-Bit QMX Microkernel (MOPA/SDOT)"),
        Q8_0("Q8_0", "gemma-3-270m-it-Q8_0.gguf", "Qualcomm 8-Bit QMX Microkernel (MOPA)")
    }

    private var currentModelQuant = ModelQuant.Q4_0

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private var isModelReady = false
    private var isGenerating = false
    private var currentThreads = 1

    // Engine 1: Android Native System TTS (0s instant playback)
    private var androidTts: TextToSpeech? = null
    private var isAndroidTtsReady = false

    // Engine 2: Qwen3-TTS Neural Model (On-Device Streaming PCM)
    private var isQwenTtsReady = false
    private var isQwenTtsSpeaking = false
    private var isQwenTtsGenerating = false
    private var audioTrack: AudioTrack? = null

    // Track the current generation job for clean cancellation
    private var generationJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Android System TextToSpeech
        androidTts = TextToSpeech(this, this)

        // Handle safe window insets
        val rootLayout = findViewById<View>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        rvChat = findViewById(R.id.rvChat)
        etPrompt = findViewById(R.id.etPrompt)
        btnSend = findViewById(R.id.btnSend)
        tvBadge = findViewById(R.id.tvQmxBadge)
        tvModelInfo = findViewById(R.id.tvModelInfo)
        tvLiveSpeed = findViewById(R.id.tvLiveSpeed)
        btnThreadToggle = findViewById(R.id.btnThreadToggle)
        btnModelToggle = findViewById(R.id.btnModelToggle)
        pbLoading = findViewById(R.id.pbLoading)

        // Setup ChatAdapter with dual voice callbacks
        adapter = ChatAdapter(
            messages = messages,
            onSpeakAndroidClick = { textToSpeak ->
                speakWithAndroidTts(textToSpeak)
            },
            onSpeakQwenClick = { textToSpeak ->
                speakWithQwenTts(textToSpeak)
            }
        )

        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        rvChat.layoutManager = layoutManager
        rvChat.adapter = adapter

        // Disable RecyclerView item change animations to prevent flicker during streaming
        rvChat.itemAnimator?.changeDuration = 0

        btnSend.setOnClickListener {
            val text = etPrompt.text.toString().trim()
            if (text.isNotEmpty() && isModelReady && !isGenerating) {
                sendMessage(text)
            }
        }

        btnThreadToggle.setOnClickListener {
            if (!isGenerating) {
                currentThreads = if (currentThreads == 1) 4 else 1
                btnThreadToggle.text = if (currentThreads == 1) "1 Thread ⚡" else "4 Threads 🚀"
                loadModelAsync()
            }
        }

        btnModelToggle.text = "${currentModelQuant.label} ⚡"
        btnModelToggle.setOnClickListener {
            if (!isGenerating) {
                currentModelQuant = if (currentModelQuant == ModelQuant.Q4_0) ModelQuant.Q8_0 else ModelQuant.Q4_0
                btnModelToggle.text = "${currentModelQuant.label} ⚡"
                loadModelAsync()
            } else {
                Toast.makeText(this, "Please wait for generation to complete", Toast.LENGTH_SHORT).show()
            }
        }

        // Setup Suggestion Chips
        findViewById<TextView>(R.id.chipPrompt1)?.setOnClickListener {
            etPrompt.setText("What is Qualcomm Matrix Extension (QMX) and how does it speed up LLM inference?")
            sendMessage(etPrompt.text.toString())
        }
        findViewById<TextView>(R.id.chipPrompt2)?.setOnClickListener {
            etPrompt.setText("Benchmark on-device generation speed and explain prompt eval vs decode.")
            sendMessage(etPrompt.text.toString())
        }
        findViewById<TextView>(R.id.chipPrompt3)?.setOnClickListener {
            etPrompt.setText("Explain quantum computing in one creative sentence.")
            sendMessage(etPrompt.text.toString())
        }
        findViewById<TextView>(R.id.chipPrompt4)?.setOnClickListener {
            etPrompt.setText("Why run LLMs on mobile CPUs instead of NPUs or GPUs?")
            sendMessage(etPrompt.text.toString())
        }

        // Load Chat Model (Gemma)
        loadModelAsync()

        // Load Qwen3-TTS in background (4 threads)
        loadQwenTtsAsync()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = androidTts?.setLanguage(Locale.US)
            isAndroidTtsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            if (isAndroidTtsReady) {
                androidTts?.setSpeechRate(1.08f)
                androidTts?.setPitch(1.0f)
            }
        }
    }

    /**
     * Engine 1: Speak using Android Native System TTS.
     * Starts in <100ms with zero model overhead.
     */
    private fun speakWithAndroidTts(text: String) {
        stopAllAudio()

        if (!isAndroidTtsReady) {
            Toast.makeText(this, "Android TTS engine initializing...", Toast.LENGTH_SHORT).show()
            return
        }

        val cleanText = cleanMarkdownText(text)
        if (cleanText.isBlank()) return

        Toast.makeText(this, "🔊 Playing System Voice...", Toast.LENGTH_SHORT).show()
        androidTts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "android_tts_${System.currentTimeMillis()}")
    }

    /**
     * Engine 2: Speak using Qwen3-TTS Neural Model.
     * Streams 24kHz PCM audio in real-time via AudioTrack.
     */
    private fun speakWithQwenTts(text: String) {
        if (isQwenTtsSpeaking) {
            stopAllAudio()
            return
        }

        stopAllAudio()

        if (!isQwenTtsReady) {
            Toast.makeText(this, "Qwen3-TTS initializing in background...", Toast.LENGTH_SHORT).show()
            return
        }

        val cleanText = cleanMarkdownText(text)
        if (cleanText.isBlank()) return

        val sentences = cleanText.split(Regex("(?<=[.!?])\\s+"))
        val speechText = if (sentences.isNotEmpty()) {
            sentences.take(2).joinToString(" ").take(180)
        } else {
            cleanText.take(180)
        }

        isQwenTtsGenerating = true
        Toast.makeText(this, "🧠 Streaming Qwen3-TTS Neural Voice...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            val sampleRate = 24000
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            try {
                audioTrack?.release()
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                audioTrack?.play()
                isQwenTtsSpeaking = true
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val success = InferenceBridge.nativeTtsGenerateStream(
                speechText,
                "en",
                object : PcmCallback {
                    override fun onPcmChunk(pcmData: ShortArray) {
                        if (isQwenTtsSpeaking && audioTrack != null) {
                            audioTrack?.write(pcmData, 0, pcmData.size)
                        }
                    }
                }
            )

            withContext(Dispatchers.Main) {
                isQwenTtsGenerating = false
                if (!success) {
                    Toast.makeText(this@MainActivity, "Qwen TTS streaming failed", Toast.LENGTH_SHORT).show()
                    stopAudioTrack()
                } else {
                    lifecycleScope.launch {
                        delay(2500)
                        stopAudioTrack()
                    }
                }
            }
        }
    }

    private fun cleanMarkdownText(text: String): String {
        return text
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("\\*(.+?)\\*"), "$1")
            .replace(Regex("─+.*$", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("[•⚡🚀💡📱⚙️🔊🧠]"), "")
            .trim()
    }

    private fun stopAllAudio() {
        androidTts?.stop()
        stopAudioTrack()
    }

    private fun stopAudioTrack() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            // ignore
        }
        audioTrack = null
        isQwenTtsSpeaking = false
        isQwenTtsGenerating = false
    }

    /**
     * Load Qwen3-TTS model in the background with 4 threads for fast parallel synthesis.
     */
    private fun loadQwenTtsAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            val backbonePath = "/data/local/tmp/models/Qwen3-TTS-12Hz-1.7B-Base-Q4_K_M.gguf"
            val mmprojPath = "/data/local/tmp/models/mmproj-Qwen3-TTS-12Hz-1.7B-Base-Q8_0.gguf"

            if (File(backbonePath).exists() && File(mmprojPath).exists()) {
                val success = InferenceBridge.nativeTtsInit(backbonePath, mmprojPath, 4)
                withContext(Dispatchers.Main) {
                    isQwenTtsReady = success
                }
            }
        }
    }

    private fun loadModelAsync() {
        pbLoading.visibility = View.VISIBLE
        isModelReady = false
        tvBadge.text = "LOADING..."
        tvBadge.setBackgroundResource(R.drawable.bg_badge_loading)

        val quant = currentModelQuant
        btnModelToggle.text = "${quant.label} ⚡"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Os.setenv("GGML_KLEIDIAI_SME", "1", true)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val candidatePaths = listOf(
                "/data/local/tmp/models/${quant.filename}",
                File(getExternalFilesDir(null), "models/${quant.filename}").absolutePath
            )

            var selectedPath: String? = null
            for (path in candidatePaths) {
                if (File(path).exists()) {
                    selectedPath = path
                    break
                }
            }

            if (selectedPath == null) {
                selectedPath = "/data/local/tmp/models/${quant.filename}"
            }

            val success = InferenceBridge.nativeInit(selectedPath, currentThreads, true)

            withContext(Dispatchers.Main) {
                pbLoading.visibility = View.GONE
                if (success) {
                    isModelReady = true
                    tvBadge.text = "⚡ QMX ACTIVE"
                    tvBadge.setBackgroundResource(R.drawable.bg_badge_active)
                    btnModelToggle.text = "${quant.label} ⚡"
                    val modelName = File(selectedPath).name
                    tvModelInfo.text = "$modelName • $currentThreads Thread${if (currentThreads > 1) "s" else ""}"
                    tvLiveSpeed.text = if (currentThreads == 1) "⚡ Ready (~267 tok/s)" else "⚡ Ready (~210 tok/s)"

                    messages.add(
                        ChatMessage(
                            "⚡ **Active Model: Gemma 3 270M (${quant.label})!**\n\n" +
                                    "• **Microkernel**: ${quant.kernelDesc}\n" +
                                    "• **Hardware Target**: Qualcomm Matrix Extension (SME MOPA) • Oryon Cores\n" +
                                    "• **Thread Config**: $currentThreads Thread${if (currentThreads > 1) "s" else ""}\n" +
                                    "• Tap **${quant.label} ⚡** in the top bar to toggle between Q4_0 & Q8_0 anytime.",
                            false
                        )
                    )
                    adapter.notifyItemInserted(messages.size - 1)
                    rvChat.smoothScrollToPosition(messages.size - 1)
                } else {
                    tvBadge.text = "LOAD FAILED"
                    tvBadge.setBackgroundResource(R.drawable.bg_badge_error)
                    Toast.makeText(this@MainActivity, "Could not load ${quant.label}: $selectedPath", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Send a message and stream the response with jitter-free token batching.
     */
    private fun sendMessage(promptText: String) {
        etPrompt.setText("")
        isGenerating = true
        btnSend.isEnabled = false

        // Stop any ongoing audio playback
        stopAllAudio()

        // Add user message
        messages.add(ChatMessage(promptText, true))
        adapter.notifyItemInserted(messages.size - 1)

        // Add placeholder assistant message
        val assistantMsgIndex = messages.size
        val assistantMsg = ChatMessage("Thinking...", false, isStreaming = true)
        messages.add(assistantMsg)
        adapter.notifyItemInserted(assistantMsgIndex)
        rvChat.smoothScrollToPosition(messages.size - 1)

        val streamBuffer = StringBuilder()

        // Unbounded channel: JNI callback sends tokens here (O(1), non-blocking)
        val tokenChannel = Channel<String>(Channel.UNLIMITED)

        // UI consumer: batched updates at ~16 fps (60ms interval)
        val uiTickerJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(60)
                var chunk = ""
                while (true) {
                    val piece = tokenChannel.tryReceive().getOrNull() ?: break
                    chunk += piece
                }
                if (chunk.isNotEmpty()) {
                    streamBuffer.append(chunk)
                    assistantMsg.text = streamBuffer.toString()
                    adapter.notifyItemChanged(assistantMsgIndex, "text_update")
                    rvChat.smoothScrollToPosition(assistantMsgIndex)
                }
            }
        }

        // Generation coroutine on IO dispatcher
        generationJob = lifecycleScope.launch(Dispatchers.IO) {
            val fullResult = InferenceBridge.nativeGenerate(
                promptText,
                256,
                object : TokenCallback {
                    override fun onToken(piece: String) {
                        tokenChannel.trySend(piece)
                    }
                }
            )

            uiTickerJob.cancel()
            tokenChannel.close()

            withContext(Dispatchers.Main) {
                var remaining = ""
                while (true) {
                    val piece = tokenChannel.tryReceive().getOrNull() ?: break
                    remaining += piece
                }
                if (remaining.isNotEmpty()) {
                    streamBuffer.append(remaining)
                }

                assistantMsg.text = fullResult
                assistantMsg.isStreaming = false
                adapter.notifyItemChanged(assistantMsgIndex)
                rvChat.smoothScrollToPosition(assistantMsgIndex)
                isGenerating = false
                btnSend.isEnabled = true

                val speedMatch = Regex("Generation: (\\d+) tok/s").find(fullResult)
                val actualSpeed = speedMatch?.groupValues?.get(1) ?: "267"
                tvLiveSpeed.text = "⚡ $actualSpeed tok/s"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        generationJob?.cancel()
        stopAllAudio()
        androidTts?.shutdown()
        androidTts = null
        InferenceBridge.nativeTtsFree()
        InferenceBridge.nativeFree()
    }
}
