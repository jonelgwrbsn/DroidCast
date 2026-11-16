package com.droidcast

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiagnosticLogEntry(
    val timestamp: String,
    val level: String,
    val tag: String,
    val message: String,
)

object DroidLog {
    const val TAG_SESSION = "DroidCast/Session"
    const val TAG_WEBRTC = "DroidCast/WebRTC"
    const val TAG_SCREEN = "DroidCast/ScreenCapture"
    const val TAG_AUDIO = "DroidCast/Audio"
    const val TAG_NETWORK = "DroidCast/Network"

    private const val MAX_ENTRIES = 80
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _entries = MutableStateFlow<List<DiagnosticLogEntry>>(emptyList())
    val entries: StateFlow<List<DiagnosticLogEntry>> = _entries.asStateFlow()

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        append("INFO", tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        append("WARN", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
        val fullMsg = if (throwable?.message != null) "$message (${throwable.message})" else message
        append("ERROR", tag, fullMsg)
    }

    private fun append(level: String, tag: String, message: String) {
        val ts = synchronized(timeFormat) { timeFormat.format(Date()) }
        val entry = DiagnosticLogEntry(ts, level, tag, message)
        _entries.value = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
    }
}
