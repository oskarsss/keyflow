package com.keyflow.pianotiles.library

import org.junit.Assert.assertEquals
import org.junit.Test

class SongTitleTest {
    @Test
    fun lockedTitlesKeepHintsWhileHidingShortWordsAndUnicodeCharacters() {
        assertEquals("Ru* Ra****", SongTitle.display("Run Rabbit", false))
        assertEquals("* A*", SongTitle.display("I AM", false))
        assertEquals("* Ni***", SongTitle.display("🎵 Night", false))
        assertEquals("  Ru*\tRa****  ", SongTitle.display("  Run\tRabbit  ", false))
        assertEquals("", SongTitle.display("", false))
    }

    @Test
    fun unlockedTitlesRemainIntact() {
        assertEquals("Run Rabbit 🎵", SongTitle.display("Run Rabbit 🎵", true))
    }
}
