package com.keyflow.pianotiles.library

import org.junit.Assert.assertEquals
import org.junit.Test

class SongTitleTest {
    @Test
    fun lockedTitlesShowTheirFirstFiveCharacters() {
        assertEquals("Back ***", SongTitle.display("Back Chat (Remastered 2011)", false))
        assertEquals("Мокры***", SongTitle.display("Мокрые кроссы", false))
        assertEquals("Lone ***", SongTitle.display("  Lone Digger  ", false))
        // Emoji stay whole.
        assertEquals("🎵 Nig***", SongTitle.display("🎵 Night drive", false))
        // At least three characters stay hidden, so short titles aren't given away.
        assertEquals("After***", SongTitle.display("Afterglow", false))
        assertEquals("He***", SongTitle.display("Hello", false))
        assertEquals("***", SongTitle.display("Run", false))
        assertEquals("", SongTitle.display("", false))
        assertEquals("Back Chat", SongTitle.display("Back Chat", true))
    }
}
