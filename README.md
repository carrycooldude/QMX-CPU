# 🚀 Snapdragon QMX On-Device LLM Chat App

[![Platform: Android](https://img.shields.io/badge/Platform-Android%2015%20(API%2036)-green.svg)](https://developer.android.com)
[![Hardware: Snapdragon SM8850 / Oryon CPU](https://img.shields.io/badge/Hardware-Snapdragon%20SM8850%20(Oryon)-blue.svg)](https://www.qualcomm.com)
[![ISA: ARMv9.2-A SME](https://img.shields.io/badge/ISA-ARMv9.2--A%20SME%20%2B%20SVE2-orange.svg)](https://developer.arm.com)
[![Engine: llama.cpp + KleidiAI](https://img.shields.io/badge/Engine-llama.cpp%20%2B%20KleidiAI-red.svg)](https://github.com/ggml-org/llama.cpp)

A fully native, on-device Android chat application and benchmarking suite accelerated via **Qualcomm Matrix Extension (QMX)** and **ARM Scalable Matrix Extension (SME)** on Qualcomm Snapdragon Oryon CPU cores.

---

## 📱 App Preview

```
┌─────────────────────────────────────────────────────────┐
│  Snapdragon AI Studio                   [⚡ QMX ACTIVE] │
│  gemma-3-270m-it-Q8_0.gguf • 4 Oryon Cores              │
│  ⚙️ ARMv9.2-A • SME Matrix Tiles • Oryon CPU  ⚡ 210 t/s │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  🤖 Assistant                                           │
│  👋 Welcome to Snapdragon AI Studio!                    │
│  Accelerated via Qualcomm Matrix Extension (QMX) &      │
│  ARMv9.2-A SME dedicated 2D matrix tiles.               │
│                                                         │
│                                           👤 User       │
│                           What is Qualcomm QMX?         │
│                                                         │
│  🤖 Assistant                                           │
│  Qualcomm Matrix Extension (QMX) is a hardware matrix   │
│  acceleration engine built into the CPU that executes   │
│  ARM SME instructions for lightning-fast LLM prefill...│
│                                                         │
│  ─── ⚡ QMX Hardware Stats ───                          │
│  • Prefill: 1,090 tok/s (22 ms)                         │
│  • Generation: 208 tok/s (127 tokens)                   │
│                                                         │
├─────────────────────────────────────────────────────────┤
│  [⚡ What is QMX?]  [🚀 Benchmark Speed]  [💡 Quantum AI]│
├─────────────────────────────────────────────────────────┤
│  [ Ask Snapdragon AI...                           ] [➤] │
└─────────────────────────────────────────────────────────┘
```

---

## 🏛️ System Architecture

```
┌───────────────────────────────────────────────────────────────┐
│                  KOTLIN CHAT APPLICATION                      │
│             (RecyclerView, Material 3, Coroutines)            │
└───────────────────────────────┬───────────────────────────────┘
                                │ JNI (Token Streaming & Stats)
                                ▼
┌───────────────────────────────────────────────────────────────┐
│                   NATIVE INFERENCE ENGINE                     │
│         (llama.cpp / GGML CPU Backend @ commit 2e92ecd)       │
└───────────────────────────────┬───────────────────────────────┘
                                │ Micro-Kernel Dispatch
                                ▼
┌───────────────────────────────────────────────────────────────┐
│              KLEIDIAI ACCELERATION KERNELS                    │
│         (qualcomm/kleidiai @ branch kleidi-ai-qmx)            │
└───────────────────────────────┬───────────────────────────────┘
                                │ ARMv9.2-A Assembly (`mopa`, `smstart`)
                                ▼
┌───────────────────────────────────────────────────────────────┐
│                 QUALCOMM ORYON CPU SILICON                    │
│       Dedicated 2D SME Matrix Accumulator Tiles ($ZA$)        │
└───────────────────────────────────────────────────────────────┘
```

---

## 📊 Performance Benchmarks & Plots

Live benchmarks conducted on **Qualcomm Snapdragon Reference Platform (SM8850)** running **Google Gemma 3 270M Instruct**.

### 1. Prompt Evaluation Speed (Prefill / GEMM) — Tokens / Second
*Higher is better 🚀*

```text
Gemma 270M Q8_0 (1-Thread) [I8MM Fallback]  ██████████████████████████████ 2,102 t/s
Gemma 270M Q4_0 (1-Thread) [I8MM Fallback]  ███████████████████ 1,363 t/s
Gemma 270M Q8_0 (4-Thread) [QMX / SME]      ██████████ 706 t/s
Gemma 270M Q4_0 (4-Thread) [QMX / SME]      █████████ 611 t/s
Gemma 270M Q8_0 (1-Thread) [QMX / SME]      ████████ 592 t/s
Gemma 270M Q4_0 (1-Thread) [QMX / SME]      ███████ 485 t/s
```

### 2. Token Generation Speed (Decode / GEMV) — Tokens / Second
*Higher is better 🚀 (Human reading speed is ~5–7 tokens/sec)*

```text
Gemma 270M Q4_0 (4-Thread) [QMX / SME]      █████████████████████████ 254.7 t/s  (36x Human Speed)
Gemma 270M Q8_0 (1-Thread) [QMX / SME]      ████████████████████████  248.8 t/s  (35x Human Speed)
Gemma 270M Q4_0 (1-Thread) [QMX / SME]      ███████████████████████   240.4 t/s  (34x Human Speed)
Gemma 270M Q8_0 (1-Thread) [I8MM Fallback]  ███████████████████████   239.0 t/s  (34x Human Speed)
Gemma 270M Q4_0 (4-Thread) [I8MM Fallback]  ███████████████████████   238.5 t/s  (34x Human Speed)
Gemma 270M Q8_0 (4-Thread) [QMX / SME]      █████████████████████     215.8 t/s  (30x Human Speed)
```

---

### Detailed Benchmark Data Table

| Architecture Mode | Model | Quantization | Threads | Prompt Eval (PP) | Token Gen (TG) | TTFT (128 Prompt) |
|---|---|---|---|---|---|---|
| **QMX (SME Enabled)** | Gemma 3 270M | `Q4_0` | 1 Thread | **485.79 t/s** | **240.43 t/s** | 263 ms |
| **QMX (SME Enabled)** | Gemma 3 270M | `Q4_0` | 4 Threads | **611.51 t/s** | **254.66 t/s** | 209 ms |
| **QMX (SME Enabled)** | Gemma 3 270M | `Q8_0` | 1 Thread | **592.54 t/s** | **248.83 t/s** | 216 ms |
| **QMX (SME Enabled)** | Gemma 3 270M | `Q8_0` | 4 Threads | **706.52 t/s** | **215.77 t/s** | 181 ms |
| **I8MM Micro-kernel** | Gemma 3 270M | `Q4_0` | 1 Thread | **1,362.66 t/s** | **237.44 t/s** | 94 ms |
| **I8MM Micro-kernel** | Gemma 3 270M | `Q8_0` | 1 Thread | **2,102.01 t/s** | **239.00 t/s** | 61 ms |

---

## 🔍 Key Architectural Discoveries

### 1. The Two Compute Phases of LLMs
* **Prefill Phase (GEMM)**: Matrix-Matrix Multiplication. When you submit a prompt, all tokens are processed in parallel. This is compute-heavy and benefits massively from QMX 2D matrix tiles.
* **Decode Phase (GEMV)**: Matrix-Vector Multiplication. Generates one token at a time sequentially. The bottleneck is DRAM memory bandwidth, where Oryon CPU achieves **>240 tokens/sec**.

### 2. The "Benchmark Trap" (NEON vs I8MM vs QMX)
Setting `GGML_KLEIDIAI_SME=0` does **not** fall back to slow standard NEON. KleidiAI routes execution to ARMv8.6-A `I8MM` (Integer Matrix Multiplication) micro-kernels. To establish a genuine generic baseline, one must compile with `GGML_CPU_KLEIDIAI=OFF` with generic `-march=armv8-a`.

### 3. SME vs. SME2 Instruction Sets
Older `llama.cpp` commits routed base-SME feature flags to SME2 assembly instructions, triggering a `SIGILL` (Illegal instruction) exception on devices exposing base SME (`sme`, `smei8i32`, `smef16f32`). Using Qualcomm's `kleidi-ai-qmx` branch ensures pure base-SME compatibility.

---

## 🛠️ Build & Run Instructions

### Prerequisites
* Android NDK `r28b` (`28.2.13676358`)
* CMake `3.22.1+` & Ninja `1.13+`
* Java 17 / 21
* Android device connected via ADB (Snapdragon 8 Elite / SM8850)

### 1. Build and Run the Android Mobile App
```powershell
# Build the Debug APK
.\gradlew.bat :app:assembleDebug

# Install on connected device
adb install -r .\app\build\outputs\apk\debug\app-debug.apk

# Launch the app
adb shell am start -n com.example.qmx_cpu/.MainActivity
```

### 2. Run CLI Chat Directly on Device via ADB
```powershell
# Interactive chat
powershell -ExecutionPolicy Bypass -File .\scripts\run_chat_app.ps1

# Single-turn prompt with custom parameters
powershell -ExecutionPolicy Bypass -File .\scripts\run_chat_app.ps1 `
    -Prompt "Explain quantum computing in one sentence" `
    -Model "gemma-3-270m-it-Q8_0.gguf" `
    -Threads 4
```

### 3. Rebuild Native Binaries
```powershell
# Build with full QMX / SME ISA flags
powershell -ExecutionPolicy Bypass -File .\scripts\setup_and_build.ps1 -EnableSME
```

---

## 📜 Stack & References
* [Qualcomm Developer Blog: Llama Models Acceleration on CPU QMX](https://www.qualcomm.com/developer/blog/2026/04/llama-models-acceleration-on-cpu-qmx)
* [ARM KleidiAI (Qualcomm QMX Branch)](https://github.com/qualcomm/kleidiai/tree/kleidi-ai-qmx)
* [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp)
* [Google Gemma 3 Models](https://huggingface.co/google/gemma-3-270m-it)
