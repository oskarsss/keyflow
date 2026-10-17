package com.keyflow.pianotiles.arcade

import android.content.Context
import com.keyflow.pianotiles.library.LibraryStore
import com.keyflow.pianotiles.library.OnDeviceLibrary
import kotlin.random.Random

/**
 * Chooses Arcade's songs and remembers what it played, so the songs played longest ago come back
 * first. Getting songs ready is the preparation worker's job; Arcade is one of its requesters.
 */
class ArcadeSongs(context: Context) {
    private val store = LibraryStore(context.applicationContext)
    private val library = OnDeviceLibrary.get(context.applicationContext)

    fun pick(ready: List<Arcade.Entry>, recent: Collection<String>, random: Random): Arcade.Entry? {
        val playlists = library.savedPlaylists()
        val allowed = Arcade.eligible(Arcade.pool(playlists), Arcade.distances(playlists, store::completed)).map { it.id }.toSet()
        // Locked levels are only played when nothing else, not even a built-in song, is ready.
        val choices = ready.filter { it.id in allowed }.ifEmpty { ready.filter { it.demo } }.ifEmpty { ready }
        return Arcade.pick(choices, recent, random, history())
    }

    /** Remembers a song Arcade is about to play. */
    fun played(id: String) = synchronized(ArcadeSongs::class.java) {
        val history = (history().filter { it != id } + id).takeLast(Arcade.HISTORY)
        store.prefs.edit().putString(HISTORY_KEY, history.joinToString(",")).apply()
    }

    fun history(): List<String> = history(store)

    companion object {
        private const val HISTORY_KEY = "arcade.history"

        fun history(store: LibraryStore): List<String> =
            store.prefs.getString(HISTORY_KEY, "").orEmpty().split(',').filter { it.isNotBlank() }
    }
}
