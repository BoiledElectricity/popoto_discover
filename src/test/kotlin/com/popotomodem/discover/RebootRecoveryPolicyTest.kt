package com.popotomodem.discover

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RebootRecoveryPolicyTest {
    @Test
    fun waitsDuringInitialGracePeriod() {
        val policy = policy()

        assertEquals(RebootRecoveryAction.WAIT, policy.onLinuxObserved(99))
        assertEquals(0, policy.attempts)
    }

    @Test
    fun forcesRebootWhenLinuxRemainsAfterGracePeriod() {
        val policy = policy()

        assertEquals(RebootRecoveryAction.FORCE_REBOOT, policy.onLinuxObserved(100))
        assertEquals(1, policy.attempts)
        assertEquals(RebootRecoveryAction.WAIT, policy.onLinuxObserved(149))
        assertEquals(RebootRecoveryAction.FORCE_REBOOT, policy.onLinuxObserved(150))
        assertEquals(2, policy.attempts)
    }

    @Test
    fun failsOnlyAfterAllForcedRebootsStillLeaveLinuxRunning() {
        val policy = policy()

        assertEquals(RebootRecoveryAction.FORCE_REBOOT, policy.onLinuxObserved(100))
        assertEquals(RebootRecoveryAction.FORCE_REBOOT, policy.onLinuxObserved(150))
        assertEquals(RebootRecoveryAction.FORCE_REBOOT, policy.onLinuxObserved(200))
        assertEquals(RebootRecoveryAction.FAIL, policy.onLinuxObserved(250))
        assertEquals(3, policy.attempts)
    }

    @Test
    fun rejectsInvalidRecoveryConfiguration() {
        assertFailsWith<IllegalArgumentException> {
            RebootRecoveryPolicy(graceNanos = -1, retryNanos = 1, maxAttempts = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            RebootRecoveryPolicy(graceNanos = 0, retryNanos = 0, maxAttempts = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            RebootRecoveryPolicy(graceNanos = 0, retryNanos = 1, maxAttempts = 0)
        }
    }

    @Test
    fun forcedRebootCommandRunsInForeground() {
        val command = UbootAoeMode.forceRebootCommand()

        assertTrue(command.contains("sync"))
        assertTrue(command.contains("/sbin/reboot || reboot"))
        assertFalse(command.contains("&"))
        assertFalse(command.contains("sleep"))
    }

    private fun policy(): RebootRecoveryPolicy {
        return RebootRecoveryPolicy(
            startedAtNanos = 0,
            graceNanos = 100,
            retryNanos = 50,
            maxAttempts = 3,
        )
    }
}
