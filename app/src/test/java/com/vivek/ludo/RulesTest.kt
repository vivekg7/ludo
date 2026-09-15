package com.vivek.ludo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

class BoardTest {

    @Test
    fun `ring has 52 distinct cells`() {
        assertEquals(Board.RING_CELLS, Board.ring.size)
        assertEquals(Board.RING_CELLS, Board.ring.toSet().size)
    }

    @Test
    fun `ring cells are contiguous all the way round`() {
        for (i in Board.ring.indices) {
            val a = Board.ring[i]
            val b = Board.ring[(i + 1) % Board.RING_CELLS]
            val step = max(
                abs(Board.colOf(a) - Board.colOf(b)),
                abs(Board.rowOf(a) - Board.rowOf(b)),
            )
            // Straight runs step by one; the four quadrant turns cut the
            // corner of the centre block diagonally, which is also one.
            assertEquals("gap between ring cell $i and ${i + 1}", 1, step)
        }
    }

    @Test
    fun `start cells sit where a printed board puts them`() {
        val expected = intArrayOf(
            Board.pack(1, 6), Board.pack(8, 1), Board.pack(13, 8), Board.pack(6, 13),
        )
        assertArrayEquals(expected, IntArray(4) { Board.ring[Board.start[it]] })
    }

    @Test
    fun `starts are evenly spaced and safe squares are symmetric`() {
        for (p in 0 until Board.PLAYERS) {
            assertEquals(13, (Board.start[(p + 1) % 4] - Board.start[p] + 52) % 52)
        }
        assertEquals(8, Board.safe.size)
    }

    @Test
    fun `each player walks 51 ring cells and then turns into its home run`() {
        for (player in 0 until Board.PLAYERS) {
            val walked = (1..Board.LAST_RING_STEP).map { Board.ringIndex(player, it) }
            assertEquals(51, walked.toSet().size)
            assertEquals(Board.start[player], walked.first())

            // The lap ends one cell short of coming back round to its own
            // start: the last ring cell is the one that opens onto the home
            // run, and must be orthogonally adjacent to its first cell.
            val exit = Board.ring[walked.last()]
            val firstHome = Board.homeRun[player][0]
            val gap = abs(Board.colOf(exit) - Board.colOf(firstHome)) +
                abs(Board.rowOf(exit) - Board.rowOf(firstHome))
            assertEquals("home run entry for player $player", 1, gap)

            assertEquals(-1, Board.ringIndex(player, Board.RING_CELLS))
        }
    }

    @Test
    fun `home runs and the centre are never on the ring`() {
        val ringCells = Board.ring.toSet()
        for (player in 0 until Board.PLAYERS) {
            assertEquals(Board.HOME_RUN, Board.homeRun[player].size)
            for (cell in Board.homeRun[player]) {
                assertFalse("home run cell on ring", cell in ringCells)
            }
        }
    }

    @Test
    fun `tokens in a yard land on four separate spots`() {
        val out = FloatArray(2)
        for (player in 0 until Board.PLAYERS) {
            val spots = (0 until 4).map {
                Board.locate(player, 0, it, out)
                out[0] to out[1]
            }
            assertEquals(4, spots.toSet().size)
        }
    }
}

class RulesTest {

    private fun game(vararg seats: Seat) = GameState(Array(4) { seats.getOrElse(it) { Seat.NONE } })

    private fun soloVsGreen() = game(Seat.HUMAN, Seat.BOT)

    @Test
    fun `a token only leaves the yard on a six`() {
        for (die in 1..5) assertEquals(-1, Rules.targetOf(0, die))
        assertEquals(1, Rules.targetOf(0, 6))
    }

    @Test
    fun `the centre must be reached exactly`() {
        assertEquals(Board.FINISH, Rules.targetOf(Board.FINISH - 3, 3))
        assertEquals(-1, Rules.targetOf(Board.FINISH - 3, 4))
        assertEquals(-1, Rules.targetOf(Board.FINISH, 1))
    }

    @Test
    fun `a stuck player has no legal moves`() {
        val state = soloVsGreen()
        assertEquals(0, Rules.legalMoves(state, 3).size)
        assertEquals(4, Rules.legalMoves(state, 6).size)
    }

    @Test
    fun `landing on an enemy off a safe square sends it home`() {
        val state = soloVsGreen()
        state.steps[0] = 1                       // red on ring index 1
        state.steps[4] = 42                      // green on ring index 3

        assertEquals(3, Board.ringIndex(1, 42))
        val move = Rules.apply(state, 0, 2)

        assertArrayEquals(intArrayOf(4), move.captured)
        assertEquals(0, state.steps[4])
        assertTrue("a capture buys another roll", move.extraTurn)
    }

    @Test
    fun `an enemy on a safe square is not captured`() {
        val state = soloVsGreen()
        state.steps[0] = 3
        state.steps[4] = 48                      // green sitting on ring index 9, a star

        assertEquals(9, Board.ringIndex(1, 48))
        assertTrue(9 in Board.safe)

        val move = Rules.apply(state, 0, 6)
        assertEquals(0, move.captured.size)
        assertEquals(48, state.steps[4])
    }

    @Test
    fun `a token in its home run cannot be captured`() {
        val state = soloVsGreen()
        state.steps[4] = 54                      // green in its own home run
        state.steps[0] = 1
        val move = Rules.apply(state, 0, 1)
        assertEquals(0, move.captured.size)
        assertEquals(54, state.steps[4])
    }

    @Test
    fun `getting a token home grants another roll and wins on the fourth`() {
        val state = soloVsGreen()
        state.steps[0] = Board.FINISH
        state.steps[1] = Board.FINISH
        state.steps[2] = Board.FINISH
        state.steps[3] = Board.FINISH - 5

        val move = Rules.apply(state, 3, 5)
        assertTrue(move.finished)
        assertTrue(move.extraTurn)
        assertEquals(0, state.winner)
    }

    @Test
    fun `a six is worth another roll but a three is not`() {
        val state = soloVsGreen()
        state.steps[0] = 10
        assertTrue(Rules.apply(state, 0, 6).extraTurn)
        assertFalse(Rules.apply(state, 0, 3).extraTurn)
    }

    @Test
    fun `passing the dice skips empty seats`() {
        val state = game(Seat.HUMAN, Seat.NONE, Seat.BOT, Seat.NONE)
        state.current = 0
        state.die = 6
        state.sixStreak = 2

        Rules.passTurn(state)
        assertEquals(2, state.current)
        assertEquals(0, state.die)
        assertEquals(0, state.sixStreak)

        Rules.passTurn(state)
        assertEquals(0, state.current)
    }
}

class SaveTest {

    @Test
    fun `a game survives a round trip through its save string`() {
        val state = GameState(arrayOf(Seat.HUMAN, Seat.BOT, Seat.NONE, Seat.BOT))
        state.steps[0] = 17
        state.steps[7] = Board.FINISH
        state.current = 3
        state.die = 4
        state.sixStreak = 1

        val restored = requireNotNull(GameState.decode(state.encode()))
        assertArrayEquals(state.steps, restored.steps)
        assertArrayEquals(state.seats.map { it.ordinal }.toIntArray(),
            restored.seats.map { it.ordinal }.toIntArray())
        assertEquals(state.current, restored.current)
        assertEquals(state.die, restored.die)
        assertEquals(state.sixStreak, restored.sixStreak)
    }

    @Test
    fun `junk is rejected rather than crashing`() {
        assertNull(GameState.decode(null))
        assertNull(GameState.decode(""))
        assertNull(GameState.decode("1|0,0,0,0|0|0|0|0"))
        assertNull(GameState.decode("9|1,2,0,0|" + "0,".repeat(15) + "0|0|0|0|-1"))
        assertNull(GameState.decode("1|1,2,0,0|" + "0,".repeat(15) + "99|0|0|0|-1"))
    }
}

class BotTest {

    @Test
    fun `the bot takes a capture over quiet progress`() {
        val state = GameState(arrayOf(Seat.BOT, Seat.HUMAN, Seat.NONE, Seat.NONE))
        state.steps[0] = 1      // can capture on ring index 3 with a 2
        state.steps[1] = 20     // a quiet alternative
        state.steps[4] = 42     // human sitting on ring index 3

        val moves = Rules.legalMoves(state.also { it.current = 0 }, 2)
        assertEquals(0, Bot.chooseMove(state, 2, moves, kotlin.random.Random(7)))
    }

    @Test
    fun `the bot brings a token home when it can`() {
        val state = GameState(arrayOf(Seat.BOT, Seat.HUMAN, Seat.NONE, Seat.NONE))
        state.current = 0
        state.steps[0] = Board.FINISH - 3
        state.steps[1] = 10

        val moves = Rules.legalMoves(state, 3)
        assertEquals(0, Bot.chooseMove(state, 3, moves, kotlin.random.Random(1)))
    }
}
