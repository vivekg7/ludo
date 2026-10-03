package com.crylo.ludo

import android.view.HapticFeedbackConstants

/**
 * A game of Snakes & Ladders on the game screen. A roll has at most one move,
 * so once the die lands the token walks on its own, for people and bots
 * alike; a bot's seat just rolls without waiting for a tap. The turn loop
 * itself is [GameActivity]'s.
 */
class SnakesActivity : GameActivity<SnakesState, SnakesBoardView>() {

    override val kind = GameKind.SNAKES

    override fun decode(saved: String?) = SnakesState.decode(saved)

    // On the board picked on the setup screen, or with Random picked, a
    // normal one drawn afresh for every game, a rematch included.
    override fun newMatch(seats: Array<Seat>) =
        SnakesState(seats, Saves.snakesLayout(this) ?: SnakesLayout.forRandom.random(random))

    override fun createBoard() = SnakesBoardView(this)

    override fun wireBoard() {
        board.onSquareReached = { sounds.play(Sound.STEP) }
    }

    override fun playRoll(face: Int) {
        if (Snakes.targetOf(state.squares[state.current], face) < 0) {
            hint.text = getString(R.string.overshoot, face)
            sounds.play(Sound.NO_MOVE)
            handOver()
            return
        }
        // A beat before the token sets off, as for a forced move in Ludo, so
        // the die is seen to land first.
        busy = true
        handler.postDelayed({ busy = false; play() }, AUTO_MOVE_MS)
    }

    private fun play() {
        if (busy || state.die == 0) return
        busy = true
        val climb = Snakes.apply(state, state.die)
        // Settled before the token sets off, so a game saved mid-animation
        // restores with the move made and the next roll to come.
        Snakes.settle(state, climb)
        creditIfWon()

        board.showState(state)
        board.animateMove(climb, onJump = { jumped(climb) }) { afterMove(climb) }
    }

    /** As the token starts up a ladder or down a snake. */
    private fun jumped(climb: Climb) {
        if (climb.climbed) {
            sounds.play(Sound.HOME)
            if (reactionsOn) board.react(climb.player, picker.pick(Reactions.ladder))
        } else {
            sounds.play(Sound.CAPTURE)
            buzz(HapticFeedbackConstants.LONG_PRESS)
            if (reactionsOn) board.react(climb.player, picker.pick(Reactions.snake))
        }
    }

    private fun afterMove(climb: Climb) {
        if (state.winner >= 0) {
            celebrateWin()
            return
        }

        hint.text = when {
            climb.climbed -> getString(R.string.ladder, climb.landed, climb.to)
            climb.bitten -> getString(R.string.snake, climb.landed, climb.to)
            climb.extraTurn -> getString(R.string.rolled_six)
            else -> ""
        }

        if (climb.extraTurn) {
            busy = false
            beginTurn()
        } else {
            // play() has already passed the dice.
            pauseThenBeginTurn()
        }
    }

    override fun progressLine(player: Int): String = getString(R.string.on_square, state.squares[player])
}
