package com.example.musicplayer.storage

import android.content.Context
import android.content.SharedPreferences
import com.example.musicplayer.model.Song
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class PlaybackStateStorage(context: Context) {
    private val PREFS_NAME = "music_player_prefs"
    private val KEY_CURRENT_SONG_ID = "current_song_id"
    private val KEY_CURRENT_POSITION = "current_position"
    private val KEY_IS_PLAYING = "is_playing"
    private val KEY_LOOP_MODE = "loop_mode"
    private val KEY_SONG_IDS = "song_ids"
    
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun savePlaybackState(songId: Long, position: Long, isPlaying: Boolean, loopMode: Int) {
        sharedPreferences.edit()
            .putLong(KEY_CURRENT_SONG_ID, songId)
            .putLong(KEY_CURRENT_POSITION, position)
            .putBoolean(KEY_IS_PLAYING, isPlaying)
            .putInt(KEY_LOOP_MODE, loopMode)
            .apply()
    }

    fun saveSongList(songs: List<Song>) {
        // 只保存歌曲ID列表，不保存完整的Song对象
        val songIds = songs.map { it.id }
        val json = gson.toJson(songIds)
        sharedPreferences.edit()
            .putString(KEY_SONG_IDS, json)
            .apply()
    }

    fun getCurrentSongId(): Long {
        return sharedPreferences.getLong(KEY_CURRENT_SONG_ID, -1)
    }

    fun getCurrentPosition(): Long {
        return sharedPreferences.getLong(KEY_CURRENT_POSITION, 0)
    }

    fun isPlaying(): Boolean {
        return sharedPreferences.getBoolean(KEY_IS_PLAYING, false)
    }

    fun getLoopMode(): Int {
        return sharedPreferences.getInt(KEY_LOOP_MODE, 0)
    }

    fun getSavedSongIds(): List<Long> {
        val json = sharedPreferences.getString(KEY_SONG_IDS, null)
        return if (json != null) {
            try {
                val type = object : TypeToken<List<Long>>() {}.type
                gson.fromJson(json, type) ?: emptyList()
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    fun hasSavedState(): Boolean {
        return sharedPreferences.contains(KEY_CURRENT_SONG_ID)
    }

    fun hasSavedSongList(): Boolean {
        return sharedPreferences.contains(KEY_SONG_IDS)
    }

    fun clear() {
        sharedPreferences.edit().clear().apply()
    }
}