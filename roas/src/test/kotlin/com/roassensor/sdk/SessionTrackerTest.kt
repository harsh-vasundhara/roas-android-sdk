package com.roassensor.sdk

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * [SessionTracker] in isolation — the rules are mirrored deliberately from
 * `sdk/src/session.ts` (see the class doc) and must not drift, so this pins
 * exactly the behaviour a web/app comparison in one dashboard depends on:
 * 30-minute idle timeout, local-midnight rollover, and per-beacon `sequence`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionTrackerTest {

    private lateinit var storage: Storage
    private lateinit var sessions: SessionTracker

    @Before
    fun setUp() {
        storage = Storage(ApplicationProvider.getApplicationContext())
        sessions = SessionTracker(storage)
    }

    @Test
    fun `a fresh device starts session 1`() {
        val session = sessions.current()
        assertTrue(session.started)
        assertEquals(1, session.number)
        assertTrue(session.id.isNotEmpty())
        assertTrue(session.pvId.startsWith("pv"))
    }

    @Test
    fun `calling current again immediately resumes the same session`() {
        val first = sessions.current()
        val second = sessions.current()
        assertFalse(second.started)
        assertEquals(first.id, second.id)
        assertEquals(first.pvId, second.pvId)
        assertEquals(first.number, second.number)
    }

    @Test
    fun `30 minutes of inactivity rolls over to a new session`() {
        val first = sessions.current()
        storage.sessionLastActiveAt = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(31)

        val second = sessions.current()

        assertTrue(second.started)
        assertNotEquals(first.id, second.id)
        assertEquals(first.number + 1, second.number)
    }

    @Test
    fun `inside the 30 minute window the session is NOT rolled over`() {
        val first = sessions.current()
        storage.sessionLastActiveAt = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(29)

        val second = sessions.current()

        assertFalse(second.started)
        assertEquals(first.id, second.id)
    }

    @Test
    fun `a visit past local midnight rolls over even inside the 30 minute window`() {
        val first = sessions.current()
        // Recently active, but "yesterday" by the stored session day.
        storage.sessionLastActiveAt = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1)
        storage.sessionDay = "2000-1-1"

        val second = sessions.current()

        assertTrue(second.started)
        assertNotEquals(first.id, second.id)
        assertEquals(first.number + 1, second.number)
    }

    @Test
    fun `nextSequence increments within a session and touches the idle clock`() {
        sessions.current()
        assertEquals(1, sessions.nextSequence())
        assertEquals(2, sessions.nextSequence())
        assertEquals(3, sessions.nextSequence())
    }

    @Test
    fun `nextSequence after 30 idle minutes starts a fresh session at sequence 1`() {
        sessions.current()
        sessions.nextSequence() // -> 1
        sessions.nextSequence() // -> 2
        storage.sessionLastActiveAt = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(31)

        assertEquals(1, sessions.nextSequence())
    }

    @Test
    fun `foreground time accumulates across several stretches in one session`() {
        sessions.current()
        sessions.markForeground()
        Thread.sleep(30)
        val afterFirstStretch = sessions.markBackground()
        assertTrue(afterFirstStretch > 0)

        sessions.markForeground()
        Thread.sleep(30)
        val afterSecondStretch = sessions.markBackground()

        assertTrue(
            "foreground time must accumulate, not reset, across stretches",
            afterSecondStretch > afterFirstStretch,
        )
    }
}
