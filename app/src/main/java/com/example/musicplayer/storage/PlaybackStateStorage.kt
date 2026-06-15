package com.example.musicplayer.storage

import android.content.Context
import android.content.SharedPreferences
import com.example.musicplayer.model.Song
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class PlaybackStateStorage(context: Context) {
    private val PREFS_NAME = "music_player_prefs"
    private val KEY_CURRENT_SONG_ID = "current_song_id"
    private val KEY_CURRENT_SONG_PATH = "current_song_path"  // 新增：保存当前歌曲路径
    private val KEY_CURRENT_POSITION = "current_position"
    private val KEY_IS_PLAYING = "is_playing"
    private val KEY_LOOP_MODE = "loop_mode"
    private val KEY_SONG_PATHS = "song_paths"  // 改用路径保存，路径更稳定
    private val KEY_SONG_IDS = "song_ids"  // 保留旧key用于迁移
    
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun savePlaybackState(songId: Long, songPath: String, position: Long, isPlaying: Boolean, loopMode: Int) {
        sharedPreferences.edit()
            .putLong(KEY_CURRENT_SONG_ID, songId)
            .putString(KEY_CURRENT_SONG_PATH, songPath)
            .putLong(KEY_CURRENT_POSITION, position)
            .putBoolean(KEY_IS_PLAYING, isPlaying)
            .putInt(KEY_LOOP_MODE, loopMode)
            .apply()
    }

    fun saveSongList(songs: List<Song>) {
        // 使用歌曲路径保存列表，路径比ID更稳定可靠
        val songPaths = songs.map { it.path }
        val json = gson.toJson(songPaths)
        sharedPreferences.edit()
            .putString(KEY_SONG_PATHS, json)
            .apply()
    }

    fun getCurrentSongId(): Long {
        return sharedPreferences.getLong(KEY_CURRENT_SONG_ID, -1)
    }

    fun getCurrentSongPath(): String? {
        return sharedPreferences.getString(KEY_CURRENT_SONG_PATH, null)
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

    fun getSavedSongPaths(): List<String> {
        val json = sharedPreferences.getString(KEY_SONG_PATHS, null)
        return if (json != null) {
            try {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson(json, type) ?: emptyList()
            } catch (e: Exception) {
                e.printStackTrace()
                // 尝试兼容旧版的 ID 列表
                getSavedSongIdsCompat()
            }
        } else {
            // 尝试兼容旧版的 ID 列表
            getSavedSongIdsCompat()
        }
    }

    // 兼容旧版：尝试从 song_ids 获取
    private fun getSavedSongIdsCompat(): List<String> {
        val json = sharedPreferences.getString(KEY_SONG_IDS, null)
        return if (json != null) {
            try {
                val type = object : TypeToken<List<Long>>() {}.type
                val ids = gson.fromJson<List<Long>>(json, type) ?: emptyList()
                // ID 无法转换为路径，返回空列表
                android.util.Log.w("PlaybackStateStorage", "旧版播放列表使用ID，无法迁移，将重新创建")
                emptyList()
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
        return sharedPreferences.contains(KEY_SONG_PATHS) || sharedPreferences.contains(KEY_SONG_IDS)
    }

    fun clear() {
        sharedPreferences.edit().clear().apply()
    }
}