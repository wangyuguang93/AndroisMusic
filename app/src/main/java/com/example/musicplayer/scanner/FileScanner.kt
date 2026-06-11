package com.example.musicplayer.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.musicplayer.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class FileScanner(private val context: Context) {
    
    interface ScanProgressListener {
        fun onProgressChanged(progress: Int, currentDir: String, foundCount: Int)
    }
    
    private var progressListener: ScanProgressListener? = null
    
    // 当前发现的歌曲总数
    private var totalFoundSongs = 0
    
    fun setProgressListener(listener: ScanProgressListener?) {
        this.progressListener = listener
    }
    
    // 更新扫描进度
    fun updateProgress(progress: Int, currentDir: String) {
        progressListener?.onProgressChanged(progress, currentDir, totalFoundSongs)
    }
    
    // 只更新当前扫描目录（不改变进度）
    fun updateCurrentDirectory(dirName: String) {
        progressListener?.onProgressChanged(-1, dirName, totalFoundSongs)
    }
    
    // 支持的音乐文件扩展名
    private val supportedExtensions = setOf(".mp3", ".flac", ".wav", ".ogg", ".m4a", ".aac")
    
    // 最小文件大小（字节），小于此大小的文件不认为是音乐
    private val minFileSize = 100 * 1024L // 100KB
    
    // 最小时长（毫秒）
    private val minDuration = 60 * 1000L // 60秒
    
    // 主动遍历整个内部存储根目录扫描歌曲（最多进入5层）
    suspend fun scanFiles(tempList: MutableList<Song>? = null): List<Song> = withContext(Dispatchers.IO) {
        val songList = mutableListOf<Song>()
        
        // 更新进度：开始扫描
        updateProgress(0, "准备扫描...")
        
        // 获取外部存储根目录
        val rootDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.getExternalStorageDirectory()
        } else {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory()
        }
        
        // 重置计数器
        totalFoundSongs = 0
        
        // 检查目录是否可访问
        android.util.Log.d("FileScanner", "根目录路径: ${rootDir.absolutePath}")
        android.util.Log.d("FileScanner", "根目录存在: ${rootDir.exists()}")
        android.util.Log.d("FileScanner", "根目录是目录: ${rootDir.isDirectory}")
        android.util.Log.d("FileScanner", "根目录可读取: ${rootDir.canRead()}")
        
        if (!rootDir.exists() || !rootDir.isDirectory) {
            updateProgress(100, "无法访问存储目录")
            return@withContext songList
        }
        
        // 先扫描公共目录
        updateProgress(10, "扫描公共目录...")
        val publicDirs = getPublicMusicDirectories()
        
        android.util.Log.d("FileScanner", "公共目录数量: ${publicDirs.size}")
        publicDirs.forEach { dir ->
            android.util.Log.d("FileScanner", "公共目录: ${dir.absolutePath} - 存在: ${dir.exists()}, 可读取: ${dir.canRead()}")
        }
        
        var foundCount = 0
        var scannedCount = 0
        val totalFiles = countMusicFiles(publicDirs)
        
        // 扫描公共目录
        for (dir in publicDirs) {
            if (dir.exists() && dir.isDirectory && dir.canRead()) {
                android.util.Log.d("FileScanner", "开始扫描目录: ${dir.name}")
                updateProgress(10 + (scannedCount * 40 / totalFiles.coerceAtLeast(1)), "扫描: ${dir.name}")
                val songs = scanDirectoryWithDepth(dir, 0, 5, tempList)
                android.util.Log.d("FileScanner", "目录 ${dir.name} 发现 ${songs.size} 首歌曲")
                songList.addAll(songs)
                foundCount += songs.size
                scannedCount++
            }
        }
        
        // 更新进度：公共目录扫描完成
        updateProgress(50, "公共目录扫描完成，发现 $foundCount 首歌曲")
        
        // 再扫描根目录的其他子目录
        updateProgress(50, "扫描根目录...")
        android.util.Log.d("FileScanner", "开始扫描根目录: ${rootDir.absolutePath}")
        val rootSongs = scanDirectoryWithDepth(rootDir, 0, 5, tempList)
        android.util.Log.d("FileScanner", "根目录扫描完成，发现 ${rootSongs.size} 首歌曲")
        
        // 合并去重
        val songMap = mutableMapOf<String, Song>()
        songList.forEach { song ->
            songMap[song.path] = song
        }
        rootSongs.forEach { song ->
            if (!songMap.containsKey(song.path)) {
                songMap[song.path] = song
            }
        }
        
        android.util.Log.d("FileScanner", "公共目录扫描结果数量: ${songList.size}")
        android.util.Log.d("FileScanner", "根目录扫描结果数量: ${rootSongs.size}")
        android.util.Log.d("FileScanner", "去重后总数量: ${songMap.size}")
        
        updateProgress(100, "扫描完成！共发现 ${songMap.size} 首歌曲")
        
        return@withContext songMap.values.toList()
    }
    
    // 获取公共音乐目录列表
    private fun getPublicMusicDirectories(): List<File> {
        val dirs = mutableListOf<File>()
        
        try {
            // Music 目录
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            if (musicDir.exists()) dirs.add(musicDir)
            
            // Download 目录
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadDir.exists()) dirs.add(downloadDir)
            
            // DCIM 目录
            val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            if (dcimDir.exists()) dirs.add(dcimDir)
            
            // Podcasts 目录
            val podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS)
            if (podcastsDir.exists()) dirs.add(podcastsDir)
            
            // Notifications 目录
            val notificationsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_NOTIFICATIONS)
            if (notificationsDir.exists()) dirs.add(notificationsDir)
            
            // Ringtones 目录
            val ringtonesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RINGTONES)
            if (ringtonesDir.exists()) dirs.add(ringtonesDir)
            
            // Alarms 目录
            val alarmsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_ALARMS)
            if (alarmsDir.exists()) dirs.add(alarmsDir)
            
            // 根目录下的常见音乐文件夹
            val rootDir = Environment.getExternalStorageDirectory()
            val commonFolders = listOf("Music", "music", "Download", "download", "Downloads", "downloads", 
                "录音", "Recordings", "recordings", "Voice", "voice")
            
            for (folder in commonFolders) {
                val folderFile = File(rootDir, folder)
                if (folderFile.exists() && folderFile.isDirectory && folderFile.canRead()) {
                    if (!dirs.any { it.absolutePath == folderFile.absolutePath }) {
                        dirs.add(folderFile)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return dirs.distinctBy { it.absolutePath }
    }
    
    // 统计音乐文件数量（用于进度计算）
    private fun countMusicFiles(dirs: List<File>): Int {
        var count = 0
        for (dir in dirs) {
            count += countFilesRecursive(dir, 0, 5)
        }
        return count.coerceAtLeast(1)
    }
    
    // 递归统计文件数量
    private fun countFilesRecursive(dir: File, currentDepth: Int, maxDepth: Int): Int {
        if (currentDepth >= maxDepth) return 0
        if (!dir.exists() || !dir.isDirectory || !dir.canRead()) return 0
        
        val files = dir.listFiles() ?: return 0
        var count = 0
        
        for (file in files) {
            if (file.isDirectory) {
                count += countFilesRecursive(file, currentDepth + 1, maxDepth)
            } else if (file.isFile) {
                val ext = file.extension.lowercase(Locale.getDefault())
                if (supportedExtensions.contains(".$ext")) {
                    count++
                }
            }
        }
        
        return count
    }
    
    // 扫描单个目录（带深度限制）
    private suspend fun scanDirectoryWithDepth(
        dir: File,
        currentDepth: Int,
        maxDepth: Int,
        tempList: MutableList<Song>? = null
    ): List<Song> {
        val songs = mutableListOf<Song>()
        
        // 检查深度限制
        if (currentDepth >= maxDepth) {
            return songs
        }
        
        // 检查目录是否可访问
        if (!dir.exists() || !dir.isDirectory || !dir.canRead()) {
            return songs
        }
        
        // 跳过隐藏目录和系统目录
        val dirName = dir.name.lowercase(Locale.getDefault())
        if (dirName.startsWith(".") || dirName == "android" || dirName == "lost.dir") {
            return songs
        }
        
        // 跳过应用私有目录
        if (dir.absolutePath.contains("/Android/data/") || 
            dir.absolutePath.contains("/Android/obb/") ||
            dir.absolutePath.contains("/Android/media/")) {
            return songs
        }
        
        // 记录正在扫描的目录
        android.util.Log.d("FileScanner", "开始扫描目录: ${dir.name} (深度: $currentDepth)")
        
        // 更新当前扫描目录到UI（不改变进度）
        updateCurrentDirectory(dir.name)
        
        val files = dir.listFiles() ?: return songs
        
        for (file in files) {
            if (file.isDirectory) {
                // 递归扫描子目录
                songs.addAll(scanDirectoryWithDepth(file, currentDepth + 1, maxDepth, tempList))
            } else if (file.isFile) {
                // 检查是否是支持的音频文件
                val ext = file.extension.lowercase(Locale.getDefault())
                if (!supportedExtensions.contains(".$ext")) {
                    continue
                }
                
                // 检查文件大小
                if (file.length() < minFileSize) {
                    continue
                }
                
                // 获取音频信息
                val song = getSongFromFile(file)
                if (song != null && song.duration >= minDuration) {
                    songs.add(song)
                    // 实时更新发现的歌曲数量
                    synchronized(this) {
                        totalFoundSongs++
                    }
                    // 同时添加到临时列表用于实时UI更新
                    tempList?.add(song)
                }
            }
        }
        
        android.util.Log.d("FileScanner", "目录 ${dir.name} 发现 ${songs.size} 首歌曲")
        
        return songs
    }
    
    // 从文件获取歌曲信息
    private fun getSongFromFile(file: File): Song? {
        var retriever: MediaMetadataRetriever? = null
        
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) 
                ?: file.nameWithoutExtension
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) 
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                ?: "Unknown"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) 
                ?: "Unknown"
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationStr?.toLongOrNull() ?: 0L
            
            // 构建专辑封面 URI
            val albumId = file.absolutePath.hashCode().toLong()
            val albumArtUri = android.net.Uri.parse("content://media/external/audio/albumart/$albumId")
            
            return Song(
                id = file.absolutePath.hashCode().toLong(),
                title = title.trim(),
                artist = artist.trim(),
                album = album.trim(),
                duration = duration,
                path = file.absolutePath,
                uri = android.net.Uri.parse("file://${file.absolutePath}"),
                albumArtUri = albumArtUri
            )
            
        } catch (e: Exception) {
            // 如果 MediaMetadataRetriever 失败，尝试使用文件名
            try {
                val title = file.nameWithoutExtension
                return Song(
                    id = file.absolutePath.hashCode().toLong(),
                    title = title,
                    artist = "Unknown",
                    album = "Unknown",
                    duration = 0,
                    path = file.absolutePath,
                    uri = android.net.Uri.parse("file://${file.absolutePath}"),
                    albumArtUri = null
                )
            } catch (e2: Exception) {
                return null
            }
        } finally {
            try {
                retriever?.release()
            } catch (e: Exception) {
                // 忽略释放异常
            }
        }
    }
    
    // 触发系统媒体库扫描
    fun triggerMediaScan() {
        try {
            // 使用 Intent 广播通知媒体扫描器
            @Suppress("DEPRECATION")
            val intent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            intent.data = android.net.Uri.fromFile(Environment.getExternalStorageDirectory())
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
