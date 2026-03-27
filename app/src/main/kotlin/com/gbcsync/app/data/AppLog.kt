package com.gbcsync.app.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object AppLog {
    private const val TAG = "GBCSync"
    private const val MAX_LINES = 500

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    var enabled: Boolean = true

    fun d(message: String) {
        Log.d(TAG, message)
        if (enabled) append("D", message)
    }

    fun i(message: String) {
        Log.i(TAG, message)
        if (enabled) append("I", message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
        if (enabled) append("W", message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        if (enabled) {
            append("E", message)
            throwable?.let { append("E", "  ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    fun clear() {
        _lines.update { emptyList() }
    }

    private fun append(level: String, message: String) {
        val timestamp = LocalTime.now().format(timeFormatter)
        val line = "$timestamp $level $message"
        _lines.update { current -> (current + line).takeLast(MAX_LINES) }
    }
}
