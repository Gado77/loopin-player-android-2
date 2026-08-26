package com.loopin.player2.core.foundation

import com.loopin.player2.core.model.LogLevel
import com.loopin.player2.core.model.PlayerLogger

class GlobalExceptionHandler(
    private val logger: PlayerLogger,
    private val stateManager: DeviceStateManager,
    private val previousHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        stateManager.reportFailure("Uncaught exception on ${thread.name}: ${throwable.javaClass.simpleName}")
        logger.log(LogLevel.ERROR, TAG, "Uncaught exception on ${thread.name}", throwable)
        previousHandler?.uncaughtException(thread, throwable)
    }

    companion object {
        private const val TAG = "GlobalException"
    }
}
