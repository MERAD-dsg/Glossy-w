package com.jay.glossy.lyrics

import android.content.Context
import com.jay.glossy.constants.EnableMusixmatchKey
import com.jay.glossy.musixmatch.Musixmatch
import com.jay.glossy.utils.dataStore
import com.jay.glossy.utils.get

object MusixmatchLyricsProvider : LyricsProvider {
    override val name: String = "Musixmatch"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableMusixmatchKey] ?: true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = Musixmatch.getLyrics(title = title, artist = artist, duration = duration, album = album)

    override suspend fun getAllLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
        callback: (String) -> Unit,
    ) {
        Musixmatch.getAllLyrics(title = title, artist = artist, duration = duration, album = album, callback = callback)
    }
}
