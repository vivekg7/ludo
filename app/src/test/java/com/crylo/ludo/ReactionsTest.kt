package com.crylo.ludo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReactionsTest {

    @Test
    fun `a capture is graded by how far the victim had come`() {
        assertEquals(Capture.CHEAP, Reactions.capture(1, 1))
        assertEquals(Capture.CHEAP, Reactions.capture(1, Reactions.CHEAP_STEPS))
        assertEquals(Capture.PLAIN, Reactions.capture(1, Reactions.CHEAP_STEPS + 1))
        assertEquals(Capture.PLAIN, Reactions.capture(1, Reactions.BIG_STEPS - 1))
        assertEquals(Capture.BIG, Reactions.capture(1, Reactions.BIG_STEPS))
        assertEquals(Capture.BIG, Reactions.capture(1, Board.LAST_RING_STEP))
    }

    @Test
    fun `taking two tokens at once outranks how far they had come`() {
        assertEquals(Capture.MULTI, Reactions.capture(2, 1))
        assertEquals(Capture.MULTI, Reactions.capture(3, Board.LAST_RING_STEP))
    }

    @Test
    fun `every grade has emoji for both sides`() {
        for (kind in Capture.entries) {
            assertTrue(Reactions.gloat.getValue(kind).size > 1)
            assertTrue(Reactions.sulk.getValue(kind).size > 1)
        }
    }

    @Test
    fun `the picker never repeats itself from one pool`() {
        val picker = Picker(Random(7))
        val pool = arrayOf("a", "b", "c")
        var last = picker.pick(pool)
        repeat(200) {
            val next = picker.pick(pool)
            assertNotEquals(last, next)
            last = next
        }
    }

    @Test
    fun `the picker remembers each pool separately`() {
        val picker = Picker(Random(3))
        val first = arrayOf("a", "b")
        val second = arrayOf("a", "b")
        val a = picker.pick(first)
        // Another pool's pick does not count as a repeat for this one.
        picker.pick(second)
        assertNotEquals(a, picker.pick(first))
    }

    @Test
    fun `a pool of one keeps giving its only entry`() {
        val picker = Picker(Random(1))
        val pool = arrayOf("only")
        repeat(3) { assertEquals("only", picker.pick(pool)) }
    }
}
