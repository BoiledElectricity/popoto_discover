package com.popotomodem.discover

internal enum class RebootRecoveryAction {
    WAIT,
    FORCE_REBOOT,
    FAIL,
}

internal class RebootRecoveryPolicy(
    startedAtNanos: Long = System.nanoTime(),
    private val graceNanos: Long = DEFAULT_GRACE_NANOS,
    private val retryNanos: Long = DEFAULT_RETRY_NANOS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    var attempts: Int = 0
        private set

    private var nextAttemptNanos = startedAtNanos + graceNanos

    init {
        require(graceNanos >= 0) { "reboot grace period cannot be negative" }
        require(retryNanos > 0) { "reboot retry period must be positive" }
        require(maxAttempts > 0) { "reboot recovery attempts must be positive" }
    }

    fun onLinuxObserved(nowNanos: Long = System.nanoTime()): RebootRecoveryAction {
        if (nowNanos < nextAttemptNanos) {
            return RebootRecoveryAction.WAIT
        }
        if (attempts >= maxAttempts) {
            return RebootRecoveryAction.FAIL
        }
        attempts++
        nextAttemptNanos = nowNanos + retryNanos
        return RebootRecoveryAction.FORCE_REBOOT
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_GRACE_NANOS = 3_000_000_000L
        const val DEFAULT_RETRY_NANOS = 5_000_000_000L
    }
}
