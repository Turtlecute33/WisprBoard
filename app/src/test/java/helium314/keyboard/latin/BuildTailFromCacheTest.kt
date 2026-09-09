// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `getTextBeforeCursor` is on the typing path and feeds auto-correct, auto-caps and the n-gram
 * context, so the tail build that replaced its copy-everything-then-truncate implementation has to
 * be provably byte-identical, not merely "the suite is still green".
 *
 * The reference below is the original algorithm verbatim.
 */
class BuildTailFromCacheTest {

    /** The pre-optimisation implementation, kept as the oracle. */
    private fun reference(committed: CharSequence, composing: CharSequence, n: Int): CharSequence {
        val s = StringBuilder(committed.toString())
        s.append(composing.toString())
        if (s.length > n) s.delete(0, s.length - n)
        return s
    }

    private fun check(committed: String, composing: String, n: Int) {
        assertEquals(
            reference(committed, composing, n).toString(),
            RichInputConnection.buildTailFromCache(
                StringBuilder(committed), StringBuilder(composing), n,
            ).toString(),
            "committed=$committed composing=$composing n=$n",
        )
    }

    @Test
    fun matchesTheOldImplementationOnTheOrdinaryCases() {
        check("hello world", "", 40)
        check("hello world", "typ", 40)
        check("hello world", "typ", 5)
        check("hello world", "typ", 3)
        check("hello world", "typ", 1)
        check("", "typ", 2)
        check("", "", 40)
        check("abc", "def", 6)
        check("abc", "def", 0)
    }

    @Test
    fun matchesTheOldImplementationAtTheBoundaries() {
        // Exactly the composing length, exactly the total, one past the total.
        check("abcd", "efg", 3)
        check("abcd", "efg", 7)
        check("abcd", "efg", 8)
        check("abcd", "efg", 1000)
    }

    @Test
    fun surrogatePairsAreCopiedAsBytesJustLikeBefore() {
        // The old code sliced by UTF-16 unit and could split a pair; the new code must reproduce
        // that exactly rather than "improve" it, or callers that count units would drift.
        val emoji = "a😀b"
        for (n in 0..6) check(emoji, "", n)
        for (n in 0..6) check("xy", emoji, n)
    }

    @Test
    fun negativeRequestsReturnEmptyInsteadOfThrowing() {
        // Reachable: setComposingRegion derives n from a caret offset. The old code happened to
        // survive this (delete(0, len) on a StringBuilder), so the new one must too — a
        // NegativeArraySizeException here would kill the keyboard.
        assertEquals(
            "",
            RichInputConnection.buildTailFromCache(StringBuilder("abc"), StringBuilder("de"), -1).toString(),
        )
        assertEquals(
            "",
            RichInputConnection.buildTailFromCache(
                StringBuilder("abc"), StringBuilder("de"), Int.MIN_VALUE,
            ).toString(),
        )
    }

    @Test
    fun fuzzAgainstTheOldImplementation() {
        val rnd = Random(20260909)
        val alphabet = "ab \n😀é"
        repeat(20_000) {
            val committed = (0 until rnd.nextInt(0, 40)).map { alphabet.random(rnd) }.joinToString("")
            val composing = (0 until rnd.nextInt(0, 8)).map { alphabet.random(rnd) }.joinToString("")
            check(committed, composing, rnd.nextInt(0, 60))
        }
    }
}
