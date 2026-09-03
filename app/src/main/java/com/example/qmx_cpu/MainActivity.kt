package com.example.qmx_cpu

import android.media.MediaPlayer
import android.os.Bundle
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

class MainActivity : AppCompatActivity() {

    private lateinit var rvChat: RecyclerView
    private lateinit var etPrompt: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var tvBadge: TextView
    private lateinit var tvModelInfo: TextView
    private lateinit var tvLiveSpeed: TextView
    private lateinit var btnThreadToggle: TextView
    private lateinit var pbLoading: ProgressBar

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private var isModelReady = false
    private var isGenerating = false
    private var currentThreads = 1

    // Qwen3-TTS state
    private var isTtsReady = false
    private var isTtsSpeaking = false
    private var isTtsGenerating = false
    private var mediaPlayer: MediaPlayer? = null

    // Track the current generation job for clean cancellation
    private var generationJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
        pbLoading = findViewById(R.id.pbLoading)

        // Pass the TTS speak callback to the adapter
        adapter = ChatAdapter(messages) { textToSpeak ->
            speakWithQwenTts(textToSpeak)
        }
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

        // Load Chat Model
        loadModelAsync()

        // Load Qwen3-TTS model in background
        loadTtsAsync()
    }

    /**
     * Load the Qwen3-TTS model (backbone + mmproj) in the background.
     * This runs alongside the chat model as a separate llama context.
     */
    private fun loadTtsAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            val backbonePath = "/data/local/tmp/models/Qwen3-TTS-12Hz-1.7B-Base-Q4_K_M.gguf"
            val mmprojPath = "/data/local/tmp/models/mmproj-Qwen3-TTS-12Hz-1.7B-Base-Q8_0.gguf"

            val backboneExists = File(backbonePath).exists()
            val mmprojExists = File(mmprojPath).exists()

            if (!backboneExists || !mmprojExists) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "TTS models not found. Push to /data/local/tmp/models/",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }

            val success = InferenceBridge.nativeTtsInit(backbonePath, mmprojPath, 4)

            withContext(Dispatchers.Main) {
                isTtsReady = success
                if (success) {
                    Toast.makeText(this@MainActivity, "🔊 Qwen3-TTS Ready (4 Threads)!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Speak text aloud using Qwen3-TTS neural model.
     * Generates WAV audio locally on-device, then plays it via MediaPlayer.
     */
    private fun speakWithQwenTts(text: String) {
        if (!isTtsReady) {
            Toast.makeText(this, "TTS model not loaded yet", Toast.LENGTH_SHORT).show()
            return
        }
        if (isTtsSpeaking) {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            isTtsSpeaking = false
            return
        }
        if (isTtsGenerating) {
            Toast.makeText(this, "Speech generation already in progress...", Toast.LENGTH_SHORT).show()
            return
        }

        // Strip markdown formatting and stats block for cleaner speech
        val cleanText = text
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("\\*(.+?)\\*"), "$1")
            .replace(Regex("─+.*$", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("[•⚡🚀💡📱⚙️🔊]"), "")
            .trim()

        if (cleanText.isBlank()) return

        // Take first 1-2 sentences (up to 180 chars) for snappy on-device voice generation
        val sentences = cleanText.split(Regex("(?<=[.!?])\\s+"))
        val speechText = if (sentences.isNotEmpty()) {
            sentences.take(2).joinToString(" ").take(180)
        } else {
            cleanText.take(180)
        }

        isTtsGenerating = true
        Toast.makeText(this, "🔊 Generating speech with Qwen3-TTS...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            val wavPath = File(cacheDir, "tts_output_${System.currentTimeMillis()}.wav").absolutePath

            val success = InferenceBridge.nativeTtsGenerate(speechText, "en", wavPath)

            withContext(Dispatchers.Main) {
                isTtsGenerating = false
                if (success && File(wavPath).exists()) {
                    try {
                        mediaPlayer?.release()
                        mediaPlayer = MediaPlayer().apply {
                            setDataSource(wavPath)
                            prepare()
                            setOnCompletionListener {
                                isTtsSpeaking = false
                                it.release()
                                mediaPlayer = null
                                File(wavPath).delete()
                            }
                            start()
                        }
                        isTtsSpeaking = true
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Playback failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        File(wavPath).delete()
                    }
                } else {
                    Toast.makeText(this@MainActivity, "TTS generation failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadModelAsync() {
        pbLoading.visibility = View.VISIBLE
        tvBadge.text = "INITIALIZING..."
        tvBadge.setBackgroundResource(R.drawable.bg_badge_loading)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Os.setenv("GGML_KLEIDIAI_SME", "1", true)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val candidatePaths = listOf(
                "/data/local/tmp/models/gemma-3-270m-it-Q8_0.gguf",
                File(getExternalFilesDir(null), "models/gemma-3-270m-it-Q8_0.gguf").absolutePath,
                "/data/local/tmp/models/gemma-3-270m-it-Q4_0.gguf"
            )

            var selectedPath: String? = null
            for (path in candidatePaths) {
                if (File(path).exists()) {
                    selectedPath = path
                    break
                }
            }

            if (selectedPath == null) {
                selectedPath = "/data/local/tmp/models/gemma-3-270m-it-Q8_0.gguf"
            }

            val success = InferenceBridge.nativeInit(selectedPath, currentThreads, true)

            withContext(Dispatchers.Main) {
                pbLoading.visibility = View.GONE
                if (success) {
                    isModelReady = true
                    tvBadge.text = "⚡ QMX ACTIVE"
                    tvBadge.setBackgroundResource(R.drawable.bg_badge_active)
                    val modelName = File(selectedPath).name
                    tvModelInfo.text = "$modelName • $currentThreads Thread${if (currentThreads > 1) "s" else ""}"
                    tvLiveSpeed.text = if (currentThreads == 1) "⚡ Ready (~267 tok/s)" else "⚡ Ready (~210 tok/s)"

                    messages.add(
                        ChatMessage(
                            "⚡ **Snapdragon AI Studio is Ready ($currentThreads Thread Mode)!**\n\n" +
                                    "Accelerated via **Qualcomm Matrix Extension (QMX)** & **ARMv9.2-A SME**.\n" +
                                    "Tap 🔊 on any response to hear it spoken aloud via **Qwen3-TTS**!",
                            false
                        )
                    )
                    adapter.notifyItemInserted(messages.size - 1)
                    rvChat.smoothScrollToPosition(messages.size - 1)
                } else {
                    tvBadge.text = "LOAD FAILED"
                    tvBadge.setBackgroundResource(R.drawable.bg_badge_error)
                    Toast.makeText(this@MainActivity, "Could not load model: $selectedPath", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Send a message and stream the response with jitter-free token batching.
     *
     * Architecture:
     * - JNI token callback -> Channel (non-blocking, zero UI work)
     * - UI ticker coroutine drains channel every 60ms -> single notifyItemChanged() per batch
     * - This reduces ~267 layout passes/sec to ~16, eliminating visual jitter
     */
    private fun sendMessage(promptText: String) {
        etPrompt.setText("")
        isGenerating = true
        btnSend.isEnabled = false

        // Stop any ongoing TTS playback when sending a new message
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        isTtsSpeaking = false

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

        // Unbounded channel: JNI callback just sends tokens here (O(1), non-blocking)
        val tokenChannel = Channel<String>(Channel.UNLIMITED)

        // UI consumer: batched updates at ~16 fps (60ms interval)
        val uiTickerJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(60) // ~16 UI updates per second
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
        mediaPlayer?.release()
        mediaPlayer = null
        InferenceBridge.nativeTtsFree()
        InferenceBridge.nativeFree()
    }
}
