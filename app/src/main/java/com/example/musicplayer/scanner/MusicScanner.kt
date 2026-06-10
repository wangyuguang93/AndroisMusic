package com.example.musicplayer.scanner

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.musicplayer.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class MusicScanner(private val context: Context, private val coroutineScope: CoroutineScope) {
    // 扫描本地音乐文件并返回歌曲列表
    suspend fun scanMusic(): List<Song> = withContext(Dispatchers.IO) {
        val songList = mutableListOf<Song>()
        val contentResolver: ContentResolver = context.contentResolver

        // 定义要查询的媒体列
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID
        )

        // 查询外部存储中的音频文件
        val uri: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        // 筛选条件：仅包含音乐文件且时长大于60秒
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} = 1 AND ${MediaStore.Audio.Media.DURATION} > 60000"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, projection, selection, null, sortOrder)
            cursor?.use {
                // 获取列索引
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val pathColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val albumIdColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                // 遍历查询结果
                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    val title = it.getString(titleColumn)
                    val artist = it.getString(artistColumn)
                    val album = it.getString(albumColumn)
                    val duration = it.getLong(durationColumn)
                    val path = it.getString(pathColumn)
                    val albumId = it.getLong(albumIdColumn)

                    // 获取专辑封面URI
                    val albumArtUri = Uri.parse("content://media/external/audio/albumart/$albumId")

                    // 创建Song对象并添加到列表
                    val song = Song(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        duration = duration,
                        path = path,
                        uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString()),
                        albumArtUri = albumArtUri
                    )
                    songList.add(song)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
        return@withContext songList
    }

    // 启动音乐扫描（供外部调用）
    fun startScan(onComplete: (List<Song>) -> Unit) {
        coroutineScope.launch {
            val songs = scanMusic()
            withContext(Dispatchers.Main) {
                onComplete(songs)
            }
        }
    }
}