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

// 音乐扫描器类，用于扫描本地音乐文件
class MusicScanner(private val context: Context, private val coroutineScope: CoroutineScope) {
    // 协程中在 IO 线程扫描音乐文件
    suspend fun scanMusic(): List<Song> = withContext(Dispatchers.IO) {
        // 存储扫描到的歌曲列表
        val songList = mutableListOf<Song>()
        // 获取 ContentResolver 实例，用于查询媒体存储
        val contentResolver: ContentResolver = context.contentResolver
        // 定义要查询的媒体存储列
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
        // 查询条件，筛选出音乐文件且时长大于 60 秒的文件
        val selection =
            "${MediaStore.Audio.Media.IS_MUSIC} = 1 AND ${MediaStore.Audio.Media.DURATION} > 60000"
        // 按歌曲标题升序排序
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"
        // 游标，用于遍历查询结果
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, projection, selection, null, sortOrder)
            cursor?.use {
                // 获取歌曲 ID 列的索引
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                // 获取歌曲标题列的索引
                val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                // 获取歌曲艺术家列的索引
                val artistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                // 获取歌曲专辑列的索引
                val albumColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                // 获取歌曲时长列的索引
                val durationColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                // 获取歌曲文件路径列的索引
                val pathColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                // 获取歌曲专辑 ID 列的索引
                val albumIdColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                // 遍历查询结果
                while (it.moveToNext()) {
                    // 获取歌曲 ID
                    val id = it.getLong(idColumn)
                    // 获取歌曲标题
                    val title = it.getString(titleColumn)
                    // 获取歌曲艺术家
                    val artist = it.getString(artistColumn)
                    // 获取歌曲专辑
                    val album = it.getString(albumColumn)
                    // 获取歌曲时长
                    val duration = it.getLong(durationColumn)
                    // 获取歌曲文件路径
                    val path = it.getString(pathColumn)
                    // 获取歌曲专辑 ID
                    val albumId = it.getLong(albumIdColumn)

                    // 获取专辑封面 URI
                    val albumArtUri = Uri.parse("content://media/external/audio/albumart/$albumId")

                    // 创建歌曲对象并添加到列表中
                    val song = Song(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        duration = duration,
                        path = path,
                        uri = Uri.withAppendedPath(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            id.toString()
                        ),
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

    // 启动扫描任务，扫描完成后回调通知
    fun startScan(onComplete: (List<Song>) -> Unit) {
        coroutineScope.launch {
            val songs = scanMusic()
            withContext(Dispatchers.Main) {
                onComplete(songs)
            }
        }
    }
}
