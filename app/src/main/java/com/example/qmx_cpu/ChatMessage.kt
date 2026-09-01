package com.example.qmx_cpu

data class ChatMessage(
    var text: String,
    val isUser: Boolean,
    var isStreaming: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
