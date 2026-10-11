package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.crypto.DiceCheck
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** Real dice almost never trip the check; typical made-up sequences do. */
class DiceCheckTest {
    private val rnd = SecureRandom()
    private fun roll(n: Int) = (1..n).map { '1' + rnd.nextInt(6) }.joinToString("")

    @Test fun fairDiceRarelyFlagged() {
        for (n in listOf(50, 99)) {
            val trials = 20_000
            val flagged = (1..trials).count { DiceCheck.problems(roll(n)).isNotEmpty() }
            println("n=$n flagged $flagged / $trials")
            assertTrue("n=$n: $flagged false alarms", flagged * 1000 <= trials * 2) // ≤ 0.2 %
        }
    }

    @Test fun madeUpSequencesFlagged() {
        val fakes = listOf(
            "12345612345612345612345612345612345612345612345612",            // walking the keypad
            "16253416253416253416253416253416253416253416253416",            // a short pattern again and again
            "13524635241362514352461352436152431625341526354162",            // carefully "random", never repeating
            "66666666662222222222333333333311111111114444444444",            // the same key over and over
            "15151515151515151515262626262626262626263434343434",            // few pairs
        )
        for (f in fakes) assertTrue("not caught: $f", DiceCheck.problems(f).isNotEmpty())
        // The tester's real rolls pass.
        assertTrue(DiceCheck.problems("62226655443442651123422456542145252316242231356124").isEmpty())
    }
}
