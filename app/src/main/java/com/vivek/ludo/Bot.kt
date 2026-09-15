package com.vivek.ludo

import kotlin.random.Random

/**
 * A one-ply heuristic opponent: it scores every legal move on its own merits
 * and picks the best, with ties broken at random so repeat games differ.
 *
 * Deliberately not a search. Ludo's branching factor is tiny but the dice make
 * lookahead mostly noise, and a greedy player that values captures, safety and
 * getting tokens out of the yard already plays a recognisably sane game.
 */
object Bot {

    fun chooseMove(state: GameState, die: Int, moves: IntArray, random: Random): Int {
        var best = moves[0]
        var bestScore = Int.MIN_VALUE
        var ties = 0
        for (token in moves) {
            val score = score(state, token, die)
            when {
                score > bestScore -> {
                    bestScore = score
                    best = token
                    ties = 1
                }
                // Reservoir-sample among equal-scoring moves so the bot does
                // not always favour its lowest-numbered token.
                score == bestScore -> {
                    ties++
                    if (random.nextInt(ties) == 0) best = token
                }
            }
        }
        return best
    }

    private fun score(state: GameState, token: Int, die: Int): Int {
        val player = Board.owner(token)
        val from = state.steps[token]
        val to = Rules.targetOf(from, die)
        var score = 0

        if (to == Board.FINISH) score += 1000
        if (from == 0) score += 320                               // get out of the yard
        if (from <= Board.LAST_RING_STEP && to > Board.LAST_RING_STEP) score += 260

        score += capturedValue(state, player, to) * 6
        if (Board.ringIndex(player, to) in Board.safe) score += 90

        // Prefer running a threatened token out of reach, and avoid walking
        // into someone else's.
        score += risk(state, player, from)
        score -= risk(state, player, to)

        score += to                                               // break ties by progress
        return score
    }

    /** Total distance travelled by enemy tokens this move would send home. */
    private fun capturedValue(state: GameState, player: Int, to: Int): Int {
        val landedOn = Board.ringIndex(player, to)
        if (landedOn < 0 || landedOn in Board.safe) return 0
        var value = 0
        for (enemy in 0 until Board.TOKENS) {
            val owner = Board.owner(enemy)
            if (owner == player || state.seats[owner] == Seat.NONE) continue
            if (Board.ringIndex(owner, state.steps[enemy]) == landedOn) {
                value += 40 + state.steps[enemy]
            }
        }
        return value
    }

    /** How exposed a token at `steps` is: enemies sitting one roll behind it. */
    private fun risk(state: GameState, player: Int, steps: Int): Int {
        val here = Board.ringIndex(player, steps)
        if (here < 0 || here in Board.safe) return 0
        var hunters = 0
        for (enemy in 0 until Board.TOKENS) {
            val owner = Board.owner(enemy)
            if (owner == player || state.seats[owner] == Seat.NONE) continue
            val there = Board.ringIndex(owner, state.steps[enemy])
            if (there < 0) continue
            val gap = (here - there + Board.RING_CELLS) % Board.RING_CELLS
            if (gap in 1..6) hunters++
        }
        // Losing a token that has walked a long way costs more than losing a
        // fresh one, so weight the threat by how much progress is at stake.
        return hunters * (25 + steps / 2)
    }
}
