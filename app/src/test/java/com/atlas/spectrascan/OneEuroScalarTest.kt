package com.atlas.spectrascan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OneEuroScalarTest {
    @Test fun initialValueAndReset() {
        val filter = OneEuroScalar(.2f)
        assertEquals(.2f, filter.value, .00001f)
        filter.update(.2f, 1000L, 6f, 3f)
        filter.update(.7f, 1033L, 6f, 3f)
        filter.reset(.4f)
        assertEquals(.4f, filter.value, .00001f)
        assertEquals(.4f, filter.update(.4f, 2000L, 6f, 3f), .00001f)
    }

    @Test fun followsMotionWithoutOvershooting() {
        val filter = OneEuroScalar(.1f)
        filter.update(.1f, 1000L, 6f, 3f)
        var last = .1f
        for (i in 1..20) {
            val next = filter.update(.1f + .03f * i, 1000L + 33L * i, 6f, 3f)
            assertTrue(next >= last)
            assertTrue(next <= .1f + .03f * i)
            last = next
        }
        assertTrue(last > .60f)
    }

    @Test fun responsiveModeTracksFastMotionBetter() {
        val fast = OneEuroScalar(.2f)
        val slow = OneEuroScalar(.2f)
        fast.update(.2f, 100L, 8f, 6f)
        slow.update(.2f, 100L, 2f, 0f)
        val f = fast.update(.6f, 133L, 8f, 6f)
        val s = slow.update(.6f, 133L, 2f, 0f)
        assertTrue(f > s)
        assertTrue(f < .600001f)
    }
}
