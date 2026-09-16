package com.crylo.ludo

/**
 * Static geometry of a standard 15x15 Ludo board. Everything here is derived
 * once at class-init and then read-only, so both the rules engine and the
 * renderer can share it without copying.
 *
 * A token's whole position is a single integer, `steps`:
 *
 *   0       in its yard
 *   1..51   on the shared 52-cell ring, at ring index (start + steps - 1) % 52
 *   52..56  in its own five-cell home run
 *   57      finished, on the centre square
 *
 * A player only ever touches 51 of the 52 ring cells: it enters on its own
 * start cell and peels off into the home run just before it would come back
 * round to that start cell again. That is why FINISH is 57 and not 58.
 */
object Board {

    const val GRID = 15
    const val RING_CELLS = 52
    const val HOME_RUN = 5
    const val FINISH = 57
    const val PLAYERS = 4
    const val TOKENS_PER_PLAYER = 4
    const val TOKENS = PLAYERS * TOKENS_PER_PLAYER

    /** Last `steps` value that is still on the shared ring. */
    const val LAST_RING_STEP = RING_CELLS - 1

    /** Ring cells in travel order, packed as col * 16 + row. Index 0 is (0,6). */
    val ring: IntArray = buildRing()

    /**
     * Ring index each player enters the board on, one cell in from the edge as
     * on a printed board. Kept 13 apart so the four quadrants are symmetric.
     */
    val start = intArrayOf(1, 14, 27, 40)

    /** Start cells, plus the star square eight cells on. No captures on these. */
    val safe: Set<Int> = start.flatMap { listOf(it, (it + 8) % RING_CELLS) }.toSet()

    /** The five coloured cells of each home run, packed like [ring]. */
    val homeRun: Array<IntArray> = arrayOf(
        intArrayOf(pack(1, 7), pack(2, 7), pack(3, 7), pack(4, 7), pack(5, 7)),
        intArrayOf(pack(7, 1), pack(7, 2), pack(7, 3), pack(7, 4), pack(7, 5)),
        intArrayOf(pack(13, 7), pack(12, 7), pack(11, 7), pack(10, 7), pack(9, 7)),
        intArrayOf(pack(7, 13), pack(7, 12), pack(7, 11), pack(7, 10), pack(7, 9)),
    )

    /** Top-left cell of each 6x6 yard, in board order (TL, TR, BR, BL). */
    val yardOrigin: Array<IntArray> = arrayOf(
        intArrayOf(0, 0), intArrayOf(9, 0), intArrayOf(9, 9), intArrayOf(0, 9),
    )

    /** Unit vector pointing from the centre back down each player's home run. */
    private val homeDir: Array<FloatArray> = arrayOf(
        floatArrayOf(-1f, 0f), floatArrayOf(0f, -1f),
        floatArrayOf(1f, 0f), floatArrayOf(0f, 1f),
    )

    val colors = intArrayOf(
        0xFFE53935.toInt(), // red   - top left
        0xFF43A047.toInt(), // green - top right
        0xFFFDD835.toInt(), // yellow- bottom right
        0xFF1E88E5.toInt(), // blue  - bottom left
    )

    val names = arrayOf("Red", "Green", "Yellow", "Blue")

    /**
     * How much of the journey a player's tokens have covered, from their four
     * positions added up, rounded down so 100 means every token is home.
     */
    fun travelPercent(totalSteps: Int): Int = totalSteps * 100 / (TOKENS_PER_PLAYER * FINISH)

    fun owner(token: Int) = token / TOKENS_PER_PLAYER

    fun firstToken(player: Int) = player * TOKENS_PER_PLAYER

    fun pack(col: Int, row: Int) = col * 16 + row

    fun colOf(packed: Int) = packed / 16

    fun rowOf(packed: Int) = packed % 16

    /**
     * Ring index a token stands on, or -1 when it is in a yard, in a home run
     * or finished. Two tokens collide only when this matches, which is what
     * makes capture detection a plain integer compare.
     */
    fun ringIndex(player: Int, steps: Int): Int =
        if (steps in 1..LAST_RING_STEP) (start[player] + steps - 1) % RING_CELLS else -1

    /**
     * Centre of a token in cell units (0..15 on both axes). `slot` is the
     * token's index within its own four, used to fan out tokens that share a
     * yard or the centre square.
     */
    fun locate(player: Int, steps: Int, slot: Int, out: FloatArray) {
        when {
            steps <= 0 -> {
                val o = yardOrigin[player]
                out[0] = o[0] + if (slot % 2 == 0) 1.5f else 4.5f
                out[1] = o[1] + if (slot < 2) 1.5f else 4.5f
            }

            steps <= LAST_RING_STEP -> {
                val c = ring[ringIndex(player, steps)]
                out[0] = colOf(c) + 0.5f
                out[1] = rowOf(c) + 0.5f
            }

            steps < FINISH -> {
                val c = homeRun[player][steps - RING_CELLS]
                out[0] = colOf(c) + 0.5f
                out[1] = rowOf(c) + 0.5f
            }

            else -> {
                // Finished tokens park inside the centre square, fanned out
                // along the edge their own home run arrives from.
                val d = homeDir[player]
                val spread = (slot - 1.5f) * 0.34f
                out[0] = 7.5f + d[0] * 0.75f - d[1] * spread
                out[1] = 7.5f + d[1] * 0.75f + d[0] * spread
            }
        }
    }

    private fun buildRing(): IntArray {
        val cells = ArrayList<Int>(RING_CELLS)
        fun add(col: Int, row: Int) = cells.add(pack(col, row))

        // Each quadrant is six cells out along one lane, six back along the
        // next, plus the single cell on the board edge between them.
        for (c in 0..5) add(c, 6)          // left arm, upper lane
        for (r in 5 downTo 0) add(6, r)    // top arm, left lane
        add(7, 0)
        for (r in 0..5) add(8, r)          // top arm, right lane
        for (c in 9..14) add(c, 6)         // right arm, upper lane
        add(14, 7)
        for (c in 14 downTo 9) add(c, 8)   // right arm, lower lane
        for (r in 9..14) add(8, r)         // bottom arm, right lane
        add(7, 14)
        for (r in 14 downTo 9) add(6, r)   // bottom arm, left lane
        for (c in 5 downTo 0) add(c, 8)    // left arm, lower lane
        add(0, 7)

        check(cells.size == RING_CELLS) { "ring has ${cells.size} cells" }
        return cells.toIntArray()
    }
}
