package com.crylo.ludo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SnakesTest {

    private fun game() = SnakesState(arrayOf(Seat.HUMAN, Seat.BOT, Seat.NONE, Seat.NONE))

    @Test
    fun `squares run back and forth from the bottom left`() {
        assertEquals(0 to 9, Snakes.colOf(1) to Snakes.rowOf(1))
        assertEquals(9 to 9, Snakes.colOf(10) to Snakes.rowOf(10))
        assertEquals(9 to 8, Snakes.colOf(11) to Snakes.rowOf(11))
        assertEquals(0 to 0, Snakes.colOf(100) to Snakes.rowOf(100))
        // Every square is its own cell, and each is next to the one before.
        val cells = (1..Snakes.FINISH).map { Snakes.colOf(it) to Snakes.rowOf(it) }
        assertEquals(Snakes.FINISH, cells.toSet().size)
        for (i in 1 until cells.size) {
            val (a, b) = cells[i - 1] to cells[i]
            assertEquals("square ${i + 1}", 1, abs(a.first - b.first) + abs(a.second - b.second))
        }
    }

    @Test
    fun `ladders go up, snakes go down, and none starts where another ends`() {
        for ((foot, top) in Snakes.ladders) assertTrue("ladder $foot", top > foot)
        for ((head, tail) in Snakes.snakes) assertTrue("snake $head", tail < head)
        val starts = Snakes.ladders.keys + Snakes.snakes.keys
        val ends = Snakes.ladders.values + Snakes.snakes.values
        assertEquals("one thing per square", Snakes.ladders.size + Snakes.snakes.size, starts.size)
        assertTrue("a jump never lands on another jump", ends.none { it in starts })
        assertFalse("nothing on the start square", Snakes.START in starts)
        assertFalse("no snake on the last square", Snakes.FINISH in starts)
    }

    @Test
    fun `a roll walks, then climbs or slides`() {
        val state = game()
        state.current = 0
        state.squares[0] = 2
        val climb = Snakes.apply(state, 2)
        assertEquals(4, climb.landed)
        assertEquals(14, climb.to)
        assertTrue(climb.climbed)
        assertEquals(14, state.squares[0])

        state.squares[0] = 13
        val bite = Snakes.apply(state, 3)
        assertEquals(16, bite.landed)
        assertEquals(6, bite.to)
        assertTrue(bite.bitten)
    }

    @Test
    fun `the last square needs an exact roll`() {
        assertEquals(100, Snakes.targetOf(97, 3))
        assertEquals(-1, Snakes.targetOf(97, 4))
        assertEquals(-1, Snakes.targetOf(50, 0))
    }

    @Test
    fun `reaching 100 wins, and a winning six does not roll again`() {
        val state = game()
        state.current = 1
        state.squares[1] = 94
        state.die = 6
        val climb = Snakes.apply(state, 6)
        assertEquals(1, state.winner)
        assertFalse(climb.extraTurn)
        Snakes.settle(state, climb)
        assertEquals(1, state.current)
    }

    @Test
    fun `a ladder to the last square wins too`() {
        val state = game()
        state.current = 0
        state.squares[0] = 77
        assertEquals(100, Snakes.apply(state, 3).to)
        assertEquals(0, state.winner)
    }

    @Test
    fun `a six rolls again and anything else passes the dice`() {
        val state = game()
        state.current = 0
        state.squares[0] = 30
        state.die = 6
        Snakes.settle(state, Snakes.apply(state, 6))
        assertEquals(0, state.current)
        assertEquals(0, state.die)

        state.die = 2
        Snakes.settle(state, Snakes.apply(state, 2))
        assertEquals(1, state.current)
    }

    @Test
    fun `standings and progress follow the squares`() {
        val state = SnakesState(arrayOf(Seat.HUMAN, Seat.BOT, Seat.HUMAN, Seat.NONE))
        state.squares[0] = 40
        state.squares[1] = 70
        state.squares[2] = 40
        assertArrayEquals(intArrayOf(1, 0, 2), state.standings())
        assertTrue(state.sameStanding(0, 2))
        assertEquals(0, SnakesState(state.seats).percent(0))
        assertEquals(98, state.also { it.squares[1] = 99 }.percent(1))
        assertEquals(100, state.also { it.squares[1] = 100 }.percent(1))
    }

    @Test
    fun `a game survives a round trip through its save string`() {
        val state = game()
        state.squares[0] = 57
        state.squares[1] = 12
        state.current = 1
        state.die = 5
        state.sixStreak = 1
        state.profiles[0] = 3

        val restored = requireNotNull(SnakesState.decode(state.encode()))
        assertArrayEquals(state.squares, restored.squares)
        assertEquals(1, restored.current)
        assertEquals(5, restored.die)
        assertEquals(1, restored.sixStreak)
        assertArrayEquals(intArrayOf(3, 0, 0, 0), restored.profiles)
    }

    @Test
    fun `a damaged or foreign save is refused`() {
        assertNull(SnakesState.decode(null))
        assertNull(SnakesState.decode("1|1,2,0,0|0,1,1,1|0|0|0|-1|0,0,0,0"))     // square 0
        assertNull(SnakesState.decode("1|1,2,0,0|1,1,1|0|0|0|-1|0,0,0,0"))       // three squares
        assertNull(SnakesState.decode("2|1,2,0,0|1,1,1,1|0|0|0|-1|0,0,0,0"))     // unknown version
        // A Ludo save is not a Snakes & Ladders one.
        assertNull(SnakesState.decode(GameState(game().seats).encode()))
    }
}
