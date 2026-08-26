package com.loopin.player2.core.foundation

import android.content.Context
import android.util.Log
import com.loopin.player2.core.model.LogLevel
import com.loopin.player2.core.model.PlayerLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class BoundedFileLogger(context: Context) : PlayerLogger {
    private val directory = File(context.filesDir, "diagnostics").apply { mkdirs() }
    private val activeFile = File(directory, "player.log")
    private val previousFile = File(directory, "player.previous.log")
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Synchronized
    override fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
        writeLogcat(level, tag, message, error)
        runCatching {
            rotateIfRequired()
            val throwableText = error?.stackTraceToString()?.take(MAX_THROWABLE_CHARS).orEmpty()
            activeFile.appendText(
                buildString {
                    append(timestampFormat.format(Date()))
                    append(' ')
                    append(level.name)
                    append(' ')
                    append(tag.take(MAX_TAG_CHARS))
                    append(' ')
                    append(message.replace('\n', ' ').take(MAX_MESSAGE_CHARS))
                    if (throwableText.isNotEmpty()) {
                        append('\n')
                        append(throwableText)
                    }
                    append('\n')
                },
            )
        }.onFailure {
            Log.e(INTERNAL_TAG, "Unable to persist log", it)
        }
    }

    private fun rotateIfRequired() {
        if (!activeFile.exists() || activeFile.length() < MAX_FILE_BYTES) return
        if (previousFile.exists()) previousFile.delete()
        if (!activeFile.renameTo(previousFile)) activeFile.delete()
    }

    private fun writeLogcat(level: LogLevel, tag: String, message: String, error: Throwable?) {
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message, error)
            LogLevel.INFO -> Log.i(tag, message, error)
            LogLevel.WARN -> Log.w(tag, message, error)
            LogLevel.ERROR -> Log.e(tag, message, error)
        }
    }

    companion object {
        private const val INTERNAL_TAG = "LoopinLogger"
        private const val MAX_FILE_BYTES = 512L * 1024L
        private const val MAX_TAG_CHARS = 40
        private const val MAX_MESSAGE_CHARS = 2_000
        private const val MAX_THROWABLE_CHARS = 8_000
    }
}
