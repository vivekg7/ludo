package com.crylo.ludo

import kotlin.random.Random

/** How much a capture hurt, which sets how loud the reaction to it is. */
enum class Capture { CHEAP, PLAIN, BIG, MULTI }

/**
 * What the yards and names say about a capture or a token getting home: the
 * emoji and which pool of taunts. Pure, like [Rules], so it is testable off
 * the device; the taunt lines themselves are string arrays, to be translated.
 */
object Reactions {

    /** A victim at most this far along had barely left its yard. */
    const val CHEAP_STEPS = 6

    /** A victim at least this far along was nearly at its home run (step 52). */
    const val BIG_STEPS = 40

    /** Grades a capture by how many tokens it took and how far the farthest had come. */
    fun capture(victims: Int, farthest: Int): Capture = when {
        victims >= 2 -> Capture.MULTI
        farthest >= BIG_STEPS -> Capture.BIG
        farthest <= CHEAP_STEPS -> Capture.CHEAP
        else -> Capture.PLAIN
    }

    /** Shown in the capturing player's yard. */
    val gloat: Map<Capture, Array<String>> = mapOf(
        Capture.CHEAP to arrayOf("😏", "🤭", "😜"),
        Capture.PLAIN to arrayOf("😂", "😎", "😏", "🤭", "😜"),
        Capture.BIG to arrayOf("🤣", "😈", "😎", "😂"),
        Capture.MULTI to arrayOf("🤯", "😈", "🔥", "🤣"),
    )

    /** Shown in each captured player's yard. */
    val sulk: Map<Capture, Array<String>> = mapOf(
        Capture.CHEAP to arrayOf("🙄", "😒", "😤"),
        Capture.PLAIN to arrayOf("😭", "😤", "😩", "😠"),
        Capture.BIG to arrayOf("😱", "😭", "💔", "😫"),
        Capture.MULTI to arrayOf("😵", "😱", "💀", "😭"),
    )

    /** Shown in a player's yard when one of their tokens gets home. */
    val cheer = arrayOf("🥳", "🎉", "🙌", "😎", "💃")
}

/**
 * Picks at random from a pool, never the same entry twice running from the
 * same pool, so the third capture of a game does not look like the first.
 */
class Picker(private val random: Random) {

    private val last = HashMap<Any, Int>()

    fun <T> pick(pool: Array<T>): T {
        if (pool.size == 1) return pool[0]
        val previous = last[pool] ?: -1
        var index: Int
        do index = random.nextInt(pool.size) while (index == previous)
        last[pool] = index
        return pool[index]
    }
}
