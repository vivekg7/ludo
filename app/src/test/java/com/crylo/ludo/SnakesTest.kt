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
    fun `on every board ladders go up, snakes go down, and none starts where another ends`() {
        for (layout in SnakesLayout.entries) {
            for ((foot, top) in layout.ladders) assertTrue("$layout ladder $foot", top > foot)
            for ((head, tail) in layout.snakes) assertTrue("$layout snake $head", tail < head)
            val starts = layout.ladders.keys + layout.snakes.keys
            val ends = layout.ladders.values + layout.snakes.values
            assertEquals("$layout: one thing per square", layout.ladders.size + layout.snakes.size, starts.size)
            assertTrue("$layout: a jump never lands on another jump", ends.none { it in starts })
            assertFalse("$layout: nothing on the start square", Snakes.START in starts)
            assertFalse("$layout: no snake on the last square", Snakes.FINISH in starts)
        }
        assertEquals("keys are unique", SnakesLayout.entries.size, SnakesLayout.entries.map { it.key }.toSet().size)
    }

    @Test
    fun `no ladder or snake lies flat across the board`() {
        // A ladder that crosses most of a row to climb one or two is drawn
        // as a long plank over the board and reads as a big climb when it is
        // not; a short snake laid along a row does the same.
        for (layout in SnakesLayout.entries) {
            for ((from, to) in layout.ladders + layout.snakes) {
                val across = abs(Snakes.colOf(from) - Snakes.colOf(to))
                val up = abs(Snakes.rowOf(from) - Snakes.rowOf(to))
                assertFalse("$layout $from to $to", across >= 5 && across > 2 * up)
            }
        }
    }

    /**
     * The expected number of rolls one player takes to get from the start to
     * the last square, worked out exactly: each square's expectation is one
     * roll plus the average over the six faces of where that face leads, and
     * repeating that settles on the answer. Sixes rolling again changes who
     * rolls, not how many rolls the token needs, so it is left out.
     */
    private fun expectedRolls(layout: SnakesLayout): Double {
        val expected = DoubleArray(Snakes.FINISH + 1)
        repeat(5000) {
            for (square in Snakes.FINISH - 1 downTo Snakes.START) {
                var total = 0.0
                for (face in 1..6) {
                    val target = Snakes.targetOf(square, face)
                    total += expected[if (target < 0) square else layout.jumpFrom(target)]
                }
                expected[square] = 1 + total / 6
            }
        }
        return expected[Snakes.START]
    }

    @Test
    fun `each board takes as long to finish as its difficulty says`() {
        // As a share of the classic board's rolls. A normal board is there for
        // variety, so it stays close; easy and hard ones are clearly apart
        // from it, but neither so quick it is over at once nor a slog.
        val bands = mapOf(
            Difficulty.EASY to 0.45..0.75,
            Difficulty.NORMAL to 0.88..1.12,
            Difficulty.HARD to 1.3..1.8,
        )
        val classic = expectedRolls(SnakesLayout.CLASSIC)
        for (layout in SnakesLayout.entries) {
            val share = expectedRolls(layout) / classic
            assertTrue("$layout takes $share of classic's rolls", share in bands.getValue(layout.difficulty))
        }
    }

    @Test
    fun `random draws only normal boards, and the boards are listed easiest first`() {
        assertTrue(SnakesLayout.forRandom.isNotEmpty())
        assertTrue(SnakesLayout.forRandom.all { it.difficulty == Difficulty.NORMAL })
        val difficulties = SnakesLayout.entries.map { it.difficulty }
        assertEquals(difficulties.sorted(), difficulties)
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
        assertEquals(SnakesLayout.CLASSIC, restored.layout)
        assertArrayEquals(state.squares, restored.squares)
        assertEquals(1, restored.current)
        assertEquals(5, restored.die)
        assertEquals(1, restored.sixStreak)
        assertArrayEquals(intArrayOf(3, 0, 0, 0), restored.profiles)

        val river = SnakesState(state.seats, SnakesLayout.RIVER)
        assertEquals(SnakesLayout.RIVER, SnakesState.decode(river.encode())?.layout)
    }

    @Test
    fun `a game saved before there were boards comes back on the classic one`() {
        val restored = requireNotNull(SnakesState.decode("1|1,2,0,0|57,12,1,1|1|5|1|-1|3,0,0,0"))
        assertEquals(SnakesLayout.CLASSIC, restored.layout)
        assertEquals(57, restored.squares[0])
        assertArrayEquals(intArrayOf(3, 0, 0, 0), restored.profiles)
    }

    @Test
    fun `a board's own rules decide where a token ends up`() {
        val state = SnakesState(arrayOf(Seat.HUMAN, Seat.BOT, Seat.NONE, Seat.NONE), SnakesLayout.JUNGLE)
        state.current = 0
        state.squares[0] = 1
        assertEquals(22, Snakes.apply(state, 2).to)          // Jungle's ladder on 3
        state.squares[0] = 2
        assertEquals(4, Snakes.apply(state, 2).to)           // Classic's ladder on 4 is not here
    }

    @Test
    fun `a damaged or foreign save is refused`() {
        assertNull(SnakesState.decode(null))
        assertNull(SnakesState.decode("1|1,2,0,0|0,1,1,1|0|0|0|-1|0,0,0,0"))     // square 0
        assertNull(SnakesState.decode("1|1,2,0,0|1,1,1|0|0|0|-1|0,0,0,0"))       // three squares
        assertNull(SnakesState.decode("3|1,2,0,0|1,1,1,1|0|0|0|-1|0,0,0,0|classic"))  // unknown version
        assertNull(SnakesState.decode("2|1,2,0,0|1,1,1,1|0|0|0|-1|0,0,0,0|moon"))     // unknown board
        assertNull(SnakesState.decode("2|1,2,0,0|1,1,1,1|0|0|0|-1|0,0,0,0"))          // board missing
        // A Ludo save is not a Snakes & Ladders one.
        assertNull(SnakesState.decode(GameState(game().seats).encode()))
    }
}
