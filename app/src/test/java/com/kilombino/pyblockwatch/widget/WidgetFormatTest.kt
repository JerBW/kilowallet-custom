package com.kilombino.pyblockwatch.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetFormatTest {

    @Test fun difficultyUnits() {
        assertEquals("5.09 G", WidgetFormat.difficulty(5086468190.224727))
        assertEquals("4.63 G", WidgetFormat.difficulty(4625110531.247428))
        assertEquals("30.4 M", WidgetFormat.difficulty(30393776.10393918))
        assertEquals("127 T", WidgetFormat.difficulty(127479855693691.4))
        assertEquals("1.00 P", WidgetFormat.difficulty(1e15))
        assertEquals("950", WidgetFormat.difficulty(950.0))
    }

    @Test fun changeArrowsAndRounding() {
        assertEquals("▲16.6%", WidgetFormat.pct(16.592464218483883))
        assertEquals("▼4.2%", WidgetFormat.pct(-4.249))
        assertEquals("▲0.0%", WidgetFormat.pct(0.0))
    }

    @Test fun rowTexts() {
        assertEquals("prev 4.63 G", WidgetFormat.difficultyPrev(4625110531.0))
        assertEquals("~5.93 G", WidgetFormat.difficultyNext(5086468190.0 * 1.16592464))
        assertEquals("Next in 66 blocks", WidgetFormat.nextLabel(66))
        assertEquals("Next in 1,950 blocks", WidgetFormat.nextLabel(1950))
        assertEquals("Next in 1 block", WidgetFormat.nextLabel(1))
        assertEquals("Next (est.)", WidgetFormat.nextLabel(null))
    }

    @Test fun compactChain() {
        assertEquals(listOf("4.63", "5.09", "~5.93 G"),
            WidgetFormat.difficultyChain(4625110531.0, 5086468190.0, 5929062650.0))
        // Different prefixes: each value keeps its own.
        assertEquals(listOf("980 M", "1.05 G", "~1.20 G"),
            WidgetFormat.difficultyChain(980e6, 1.05e9, 1.2e9))
        // Missing pieces (older server or no estimate yet).
        assertEquals(listOf("5.09 G"), WidgetFormat.difficultyChain(null, 5086468190.0, null))
        assertEquals(listOf("4.63", "5.09 G"), WidgetFormat.difficultyChain(4625110531.0, 5086468190.0, null))
    }
}
