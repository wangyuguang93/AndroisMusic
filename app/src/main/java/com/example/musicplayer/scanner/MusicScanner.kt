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
    
    private val fileScanner = FileScanner(context)
    
    // 最小时长（毫秒）
    private val minDuration = 60 * 1000L // 60秒
    
    // 设置扫描进度监听器
    fun setProgressListener(listener: FileScanner.ScanProgressListener?) {
        fileScanner.setProgressListener(listener)
    }
    
    // 使用 MediaStore 扫描（默认方式）
    suspend fun scanMusic(): List<Song> = withContext(Dispatchers.IO) {
        android.util.Log.d("MusicScanner", "scanMusic() 方法被调用")
        val songList = mutableListOf<Song>()
        val contentResolver: ContentResolver = context.contentResolver

        // 更新进度：开始扫描MediaStore
        fileScanner.updateProgress(10, "正在扫描媒体库...")

        // 定义要查询的媒体列
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.RELATIVE_PATH
        )

        // 查询外部存储中的音频文件
        val uri: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        // 筛选条件：仅时长大于60秒
        val selection = "${MediaStore.Audio.Media.DURATION} > 60000"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        var cursor: Cursor? = null
        try {
            // 尝试查询
            cursor = contentResolver.query(uri, projection, selection, null, sortOrder)
            
            // 记录查询结果数量
            android.util.Log.d("MusicScanner", "MediaStore查询结果数量: ${cursor?.count ?: -1}")
            
            // 如果查询失败或没有结果，尝试不带筛选条件的查询
            if (cursor == null || cursor.count == 0) {
                cursor?.close()
                cursor = contentResolver.query(uri, projection, null, null, sortOrder)
                android.util.Log.d("MusicScanner", "无筛选条件查询结果数量: ${cursor?.count ?: -1}")
            }
            
            // 如果 MediaStore 查询仍然没有结果，使用文件系统扫描作为备选
            if (cursor == null || cursor.count == 0) {
                cursor?.close()
                android.util.Log.d("MusicScanner", "MediaStore查询无结果，使用文件系统扫描...")
                val fileScanResult = fileScanner.scanFiles()
                songList.addAll(fileScanResult)
                android.util.Log.d("MusicScanner", "文件系统扫描结果: ${songList.size} 首歌曲")
                return@withContext songList
            }
            
            if (cursor == null) {
                return@withContext songList
            }
            
            cursor.use {
                // 获取列索引（使用 getColumnIndex 避免抛出异常）
                val idColumn = it.getColumnIndex(MediaStore.Audio.Media._ID)
                val titleColumn = it.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistColumn = it.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumColumn = it.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val durationColumn = it.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val pathColumn = it.getColumnIndex(MediaStore.Audio.Media.DATA)
                val albumIdColumn = it.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val relativePathColumn = it.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)

                // 遍历查询结果
                while (it.moveToNext()) {
                    // 安全获取值，避免列索引为 -1
                    val id = if (idColumn >= 0) it.getLong(idColumn) else it.position.toLong()
                    val title = if (titleColumn >= 0) it.getString(titleColumn) else "Unknown"
                    val artist = if (artistColumn >= 0) it.getString(artistColumn) else "Unknown"
                    val album = if (albumColumn >= 0) it.getString(albumColumn) else "Unknown"
                    val duration = if (durationColumn >= 0) it.getLong(durationColumn) else 0L
                    val path = if (pathColumn >= 0) it.getString(pathColumn) else ""
                    val albumId = if (albumIdColumn >= 0) it.getLong(albumIdColumn) else 0L

                    // 在 Android 10+ 上，DATA 列可能为空，所以放宽条件
                    // 只要有标题或者路径不为空就保留
                    if (title.isEmpty() && path.isEmpty()) {
                        continue
                    }
                    
                    // 如果时长无效，使用默认值
                    val finalDuration = if (duration > 0 && duration < 24 * 60 * 60 * 1000) {
                        duration
                    } else {
                        minDuration + 1 // 确保大于最小时长限制
                    }

                    // 获取专辑封面URI
                    val albumArtUri = Uri.parse("content://media/external/audio/albumart/$albumId")

                    // 创建Song对象并添加到列表
                    val song = Song(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        duration = finalDuration,
                        path = path,
                        uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString()),
                        albumArtUri = albumArtUri
                    )
                    songList.add(song)
                }
            }
            
            // 如果从 MediaStore 获取的歌曲太少，使用文件系统扫描作为备选
            android.util.Log.d("MusicScanner", "MediaStore扫描结果数量: ${songList.size}")
            if (songList.size < 5) {
                android.util.Log.d("MusicScanner", "MediaStore结果太少，使用文件系统扫描...")
                val fileScanResult = fileScanner.scanFiles()
                songList.clear()
                songList.addAll(fileScanResult)
                android.util.Log.d("MusicScanner", "文件系统扫描结果: ${songList.size} 首歌曲")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
        return@withContext songList
    }

    // 主动遍历文件系统扫描
    suspend fun scanFiles(tempList: MutableList<Song>? = null): List<Song> {
        return fileScanner.scanFiles(tempList)
    }
    
    // 触发系统媒体库扫描更新
    fun triggerMediaScan() {
        fileScanner.triggerMediaScan()
    }
    
    // 混合扫描：先触发媒体库更新，然后结合MediaStore和主动文件扫描
    suspend fun scanWithRefresh(): List<Song> {
        try {
            // 更新进度：开始扫描
            fileScanner.updateProgress(0, "准备扫描...")
            
            // 先触发系统媒体库扫描
            triggerMediaScan()
            
            // 等待一小段时间让系统扫描
            withContext(Dispatchers.IO) {
                kotlinx.coroutines.delay(1000)
            }
            
            // 使用两种方式扫描：MediaStore + 主动文件遍历
            val mediaStoreSongs = scanMusic()
            val fileSystemSongs = scanFiles()
            
            // 合并去重：优先使用MediaStore的数据（更完整），补充文件系统发现的新歌
            val songMap = mutableMapOf<String, Song>()
            
            // 先添加MediaStore的歌曲
            mediaStoreSongs.forEach { song ->
                songMap[song.path] = song
            }
            
            // 再添加文件系统发现的歌曲（如果MediaStore中没有）
            fileSystemSongs.forEach { song ->
                if (!songMap.containsKey(song.path)) {
                    songMap[song.path] = song
                }
            }
            
            val finalList = songMap.values.toList().sortedBy { it.title }
            
            // 更新进度：扫描完成
            fileScanner.updateProgress(100, "扫描完成！共发现 ${finalList.size} 首歌曲")
            
            return finalList
            
        } catch (e: Exception) {
            e.printStackTrace()
            // 如果混合扫描失败，尝试单独使用MediaStore
            return scanMusic().sortedBy { it.title }
        }
    }
    
    // 只使用文件系统遍历扫描（最多5层深度，用于菜单触发）
    suspend fun scanWithRefreshFileOnly(tempList: MutableList<Song>? = null): List<Song> {
        try {
            // 更新进度：开始扫描
            fileScanner.updateProgress(0, "准备扫描...")
            
            // 只使用文件系统遍历扫描
            val fileSystemSongs = scanFiles(tempList)
            
            val finalList = fileSystemSongs.sortedBy { it.title }
            
            // 更新进度：扫描完成
            fileScanner.updateProgress(100, "扫描完成！共发现 ${finalList.size} 首歌曲")
            
            return finalList
            
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }
    
    // 启动音乐扫描（供外部调用）
    fun startScan(onComplete: (List<Song>) -> Unit) {
        coroutineScope.launch {
            val songs = scanWithRefresh()
            onComplete(songs)
        }
    }
}
