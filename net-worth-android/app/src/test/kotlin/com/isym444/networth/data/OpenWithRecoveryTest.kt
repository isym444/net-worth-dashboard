package com.isym444.networth.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenWithRecoveryTest {
    @Test fun succeedsFirstTimeWithoutPausingOrResetting() {
        var pauses = 0
        var resets = 0
        assertEquals("ok", openWithRecovery(create = { "ok" }, reset = { resets++ }, pause = { pauses++ }))
        assertEquals(0, pauses)
        assertEquals(0, resets)
    }

    @Test fun aTransientFailureIsRetriedAfterAPauseWithoutResetting() {
        var calls = 0
        var pauses = 0
        var resets = 0
        val v = openWithRecovery(
            create = { if (calls++ == 0) throw IllegalStateException("keystore hiccup") else "ok" },
            reset = { resets++ },
            pause = { pauses++ },
        )
        assertEquals("ok", v)
        assertEquals(1, pauses)
        assertEquals(0, resets)
    }

    @Test fun aPersistentFailureResetsThenTriesOnceMore() {
        var calls = 0
        var resets = 0
        val v = openWithRecovery(
            create = { if (calls++ < 2) throw IllegalStateException("corrupt") else "fresh" },
            reset = { resets++ },
            pause = {},
        )
        assertEquals("fresh", v)
        assertEquals(1, resets)
    }

    @Test fun returnsNullWhenEvenTheResetAttemptFails() {
        var resets = 0
        val v = openWithRecovery<String>(create = { throw IllegalStateException("dead") }, reset = { resets++ }, pause = {})
        assertNull(v)
        assertEquals(1, resets)
    }
}
