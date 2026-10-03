package com.crylo.ludo

import android.view.HapticFeedbackConstants

/**
 * A game of Ludo on the game screen: offering the tokens a roll can move,
 * playing the one picked, and the board's reactions to captures and tokens
 * getting home. The turn loop itself is [GameActivity]'s.
 */
class LudoActivity : GameActivity<GameState, BoardView>() {

    override val kind = GameKind.LUDO

    // Read once, since each read builds a new array and the picker tells
    // pools apart by identity.
    private lateinit var taunts: Map<Capture, Array<String>>

    override fun decode(saved: String?) = GameState.decode(saved)

    override fun newMatch(seats: Array<Seat>) = GameState(seats)

    override fun createBoard() = BoardView(this)

    override fun wireBoard() {
        taunts = mapOf(
            Capture.CHEAP to resources.getStringArray(R.array.taunts_cheap),
            Capture.PLAIN to resources.getStringArray(R.array.taunts_plain),
            Capture.BIG to resources.getStringArray(R.array.taunts_big),
            Capture.MULTI to resources.getStringArray(R.array.taunts_multi),
        )

        board.onTokenPicked = { token -> play(token) }
        board.onSquareReached = { sounds.play(Sound.STEP) }
        board.onCapture = { token, captured, capturedFrom ->
            sounds.play(Sound.CAPTURE)
            buzz(HapticFeedbackConstants.LONG_PRESS)
            reactToCapture(Board.owner(token), captured, capturedFrom)
        }
    }

    override fun playRoll(face: Int) {
        val moves = Rules.legalMoves(state, face)
        val bot = state.isBot(state.current)
        when {
            moves.isEmpty() -> {
                hint.text = getString(R.string.no_move, face)
                sounds.play(Sound.NO_MOVE)
                handOver()
            }

            // With a single option there is nothing to decide, so play it
            // rather than making the player tap the only legal token. Tokens
            // stacked on one square are a single option too.
            Rules.isForced(state, moves) || bot -> {
                val token = if (bot) Bot.chooseMove(state, face, moves, random) else moves[0]
                busy = true
                handler.postDelayed({ busy = false; play(token) }, if (bot) BOT_THINK_MS else AUTO_MOVE_MS)
            }

            else -> {
                busy = false
                hint.text = getString(R.string.pick_token)
                board.setHighlights(moves)
            }
        }
    }

    private fun play(token: Int) {
        if (busy || state.die == 0) return
        busy = true
        board.clearHighlights()

        val face = state.die
        val before = state.steps.copyOf()
        val move = Rules.apply(state, token, face)
        // Settled before the token starts to slide: were the die left set, a
        // game saved mid-animation would restore with the token already moved
        // and the same roll still to play.
        Rules.settle(state, move)
        creditIfWon()
        // Captured tokens are drawn where they stood until the move lands.
        val capturedFrom = IntArray(move.captured.size) { before[move.captured[it]] }

        board.showState(state)
        board.animateMove(move.token, move.from, move.to, move.captured, capturedFrom) {
            afterMove(move)
        }
    }

    private fun afterMove(move: Move) {
        if (state.winner >= 0) {
            celebrateWin()
            return
        }

        // A capture has already sounded through board.onCapture, as its token
        // landed; this runs once the captured tokens are back in their yard.
        if (move.finished) {
            sounds.play(Sound.HOME)
            if (reactionsOn) board.react(Board.owner(move.token), picker.pick(Reactions.cheer))
        }

        hint.text = when {
            move.captured.isNotEmpty() -> getString(R.string.captured)
            move.finished -> getString(R.string.token_home)
            move.extraTurn -> getString(R.string.rolled_six)
            else -> ""
        }

        if (move.extraTurn) {
            busy = false
            beginTurn()
        } else {
            // play() has already passed the dice.
            pauseThenBeginTurn()
        }
    }

    /**
     * The capturing player gloats in their yard and taunts from their name,
     * and each player who lost a token sulks in theirs. How loud depends on
     * how much the capture cost. Nothing here holds up the turn.
     */
    private fun reactToCapture(attacker: Int, captured: IntArray, capturedFrom: IntArray) {
        if (!reactionsOn) return
        val capture = Reactions.capture(captured.size, capturedFrom.max())
        board.react(attacker, picker.pick(Reactions.gloat.getValue(capture)))
        board.say(attacker, picker.pick(taunts.getValue(capture)))
        for (victim in captured.map(Board::owner).distinct()) {
            board.react(victim, picker.pick(Reactions.sulk.getValue(capture)))
        }
    }

    override fun progressLine(player: Int): String =
        getString(R.string.progress, state.percent(player), state.tokensHome(player), Board.TOKENS_PER_PLAYER)

    override fun clearChoices() = board.clearHighlights()
}
