package com.example.musicplayer.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.musicplayer.service.MusicPlayerService
import com.example.musicplayer.model.Song
import com.example.musicplayer.scanner.MusicScanner
import com.example.musicplayer.storage.PlaybackStateStorage
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val _songs = MutableLiveData<List<Song>>()
    val songs: LiveData<List<Song>> = _songs

    private val _filteredSongs = MutableLiveData<List<Song>>()
    val filteredSongs: LiveData<List<Song>> = _filteredSongs

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _currentSong = MutableLiveData<Song?>(null)
    
    // 添加实例ID用于调试
    private val instanceId = System.identityHashCode(this)
    
    init {
        android.util.Log.d("MusicViewModel", "创建 ViewModel 实例，ID: $instanceId")
    }
    val currentSong: LiveData<Song?> = _currentSong

    private val _isPlaying = MutableLiveData<Boolean>(false)
    val isPlaying: LiveData<Boolean> = _isPlaying

    private val _currentPosition = MutableLiveData<Long>(0)
    val currentPosition: LiveData<Long> = _currentPosition

    private val _duration = MutableLiveData<Long>(0)
    val duration: LiveData<Long> = _duration

    private val _loopMode = MutableLiveData<Int>(MusicPlayerService.LOOP_MODE_NONE)
    val loopMode: LiveData<Int> = _loopMode

    private val _isShuffleEnabled = MutableLiveData<Boolean>(false)
    val isShuffleEnabled: LiveData<Boolean> = _isShuffleEnabled

    private val _scanProgress = MutableLiveData<Int>(0)
    val scanProgress: LiveData<Int> = _scanProgress

    private val _scanCurrentDir = MutableLiveData<String>("")
    val scanCurrentDir: LiveData<String> = _scanCurrentDir

    private var musicPlayerService: MusicPlayerService? = null
    private var isServiceBound = false
    private var hasRestoredState = false

    private val musicScanner = MusicScanner(application, viewModelScope)
    private val playbackStateStorage = PlaybackStateStorage(application)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as MusicPlayerService.LocalBinder
            musicPlayerService = binder.service
            isServiceBound = true
            
            musicPlayerService?.addPlayerStateListener(object : MusicPlayerService.PlayerStateListener {
                override fun onCurrentSongChanged(song: Song?) {
                    _currentSong.postValue(song)
                    // 使用歌曲对象的时长属性，更可靠
                    _duration.postValue(song?.duration ?: musicPlayerService?.getDuration() ?: 0L)
                    // 重置当前位置
                    _currentPosition.postValue(0)
                    // 保存播放状态
                    song?.let {
                        savePlaybackState(it.id, it.path, _currentPosition.value ?: 0, _isPlaying.value ?: false, _loopMode.value ?: 0)
                    }
                }

                override fun onPlaybackStateChanged(isPlaying: Boolean) {
                    _isPlaying.postValue(isPlaying)
                    // 保存播放状态
                    _currentSong.value?.let {
                        savePlaybackState(it.id, it.path, _currentPosition.value ?: 0, isPlaying, _loopMode.value ?: 0)
                    }
                    if (isPlaying) {
                        startPositionUpdate()
                    }
                }

                override fun onLoopModeChanged(mode: Int) {
                    _loopMode.postValue(mode)
                    // 保存播放状态
                    _currentSong.value?.let {
                        savePlaybackState(it.id, it.path, _currentPosition.value ?: 0, _isPlaying.value ?: false, mode)
                    }
                }

                override fun onPositionChanged(position: Long, duration: Long) {
                    _currentPosition.postValue(position)
                    _duration.postValue(duration)
                }
            })
            
            updatePlayerState()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isServiceBound = false
            musicPlayerService = null
        }
    }

    init {
        startAndBindService()
    }

    // 标志位：表示刚刚完成扫描，避免 loadMusic() 覆盖扫描结果
    private var justScanned = false
    
    fun setJustScanned(value: Boolean) {
        justScanned = value
    }
    
    fun startLoadMusic() {
        loadMusic()
    }
    
    private fun startAndBindService() {
        val application = getApplication<Application>()
        val intent = Intent(application, MusicPlayerService::class.java)
        
        try {
            application.startService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun loadMusic() {
        android.util.Log.d("MusicViewModel", "loadMusic() 方法被调用")
        
        // 如果刚刚完成扫描，跳过加载，避免覆盖扫描结果
        if (justScanned) {
            android.util.Log.d("MusicViewModel", "刚刚完成扫描，跳过 loadMusic()")
            justScanned = false  // 重置标志位
            return
        }
        
        _isLoading.value = true
        viewModelScope.launch {
            try {
                // 始终使用快速的 MediaStore 扫描（打开APP时使用）
                val scannedSongs = musicScanner.scanMusic()
                android.util.Log.d("MusicViewModel", "扫描到 ${scannedSongs.size} 首歌曲")
                
                // 尝试加载保存的歌曲路径列表
                val finalSongs = if (playbackStateStorage.hasSavedSongList()) {
                    val savedSongPaths = playbackStateStorage.getSavedSongPaths()
                    android.util.Log.d("MusicViewModel", "存在保存的歌曲列表，路径数: ${savedSongPaths.size}")
                    // 根据保存的路径顺序重新排序扫描到的歌曲
                    val reordered = reorderSongsBySavedIds(scannedSongs, savedSongPaths)
                    android.util.Log.d("MusicViewModel", "重新排序后歌曲数量: ${reordered.size}")
                    reordered
                } else {
                    android.util.Log.d("MusicViewModel", "没有保存的歌曲列表，使用扫描结果")
                    scannedSongs
                }
                
                android.util.Log.d("MusicViewModel", "最终歌曲数量: ${finalSongs.size}")
                _songs.postValue(finalSongs)
                // 如果正在搜索，不重置 filteredSongs，保持搜索结果
                if (!isSearching) {
                    _filteredSongs.postValue(finalSongs)
                }
                android.util.Log.d("MusicViewModel", "歌曲列表已更新到 LiveData")
                
                // 尝试恢复播放状态
                restorePlaybackState(finalSongs)
            } catch (e: Exception) {
                e.printStackTrace()
                // 发生错误时，直接使用 MediaStore 扫描
                val scannedSongs = musicScanner.scanMusic()
                _songs.postValue(scannedSongs)
                // 如果正在搜索，不重置 filteredSongs
                if (!isSearching) {
                    _filteredSongs.postValue(scannedSongs)
                }
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    // 使用路径重新排序歌曲列表
    private fun reorderSongsBySavedIds(scanned: List<Song>, savedPaths: List<String>): List<Song> {
        // 使用路径作为key进行匹配，路径比ID更稳定
        val scannedMap = scanned.associateBy { it.path.lowercase() }
        val scannedPaths = scanned.map { it.path.lowercase() }.toSet()
        
        // 先按保存的路径顺序排列已存在的歌曲
        val reordered = savedPaths.mapNotNull { savedPath -> 
            val lowerSavedPath = savedPath.lowercase()
            
            // 首先尝试精确匹配
            if (scannedPaths.contains(lowerSavedPath)) {
                scannedMap[lowerSavedPath]
            } else {
                // 如果精确匹配失败，尝试使用文件名匹配
                val savedFile = java.io.File(savedPath)
                val savedFileName = savedFile.name.lowercase()
                
                // 在扫描结果中查找相同文件名的歌曲
                val matchedSong = scanned.firstOrNull { 
                    val scannedFile = java.io.File(it.path)
                    scannedFile.name.lowercase() == savedFileName
                }
                
                if (matchedSong != null) {
                    android.util.Log.d("MusicViewModel", "路径匹配失败，使用文件名匹配: ${savedPath} -> ${matchedSong.path}")
                    matchedSong
                } else {
                    android.util.Log.d("MusicViewModel", "路径在当前扫描中不存在: $savedPath")
                    null
                }
            }
        }.distinctBy { it.path.lowercase() }  // 按路径去重
        
        // 添加所有不在保存列表中的新歌（保持原有顺序）
        val newSongs = scanned.filter { song -> 
            !savedPaths.any { savedPath ->
                savedPath.lowercase() == song.path.lowercase() || 
                java.io.File(savedPath).name.lowercase() == java.io.File(song.path).name.lowercase()
            }
        }
        
        // 合并后再次去重（处理可能的重复）
        val combined = (reordered + newSongs).distinctBy { it.path.lowercase() }
        
        android.util.Log.d("MusicViewModel", "reorderSongsBySavedIds: 保存列表中匹配 ${reordered.size} 首，新增 ${newSongs.size} 首，去重后 ${combined.size} 首")
        
        return combined
    }

    private fun restorePlaybackState(songs: List<Song>) {
        if (!playbackStateStorage.hasSavedState() || songs.isEmpty()) {
            return
        }
        
        val savedSongId = playbackStateStorage.getCurrentSongId()
        val savedSongPath = playbackStateStorage.getCurrentSongPath()
        val savedPosition = playbackStateStorage.getCurrentPosition()
        val savedIsPlaying = playbackStateStorage.isPlaying()
        val savedLoopMode = playbackStateStorage.getLoopMode()
        
        // 优先使用路径匹配，路径更稳定
        var songIndex = if (savedSongPath != null) {
            songs.indexOfFirst { it.path.lowercase() == savedSongPath.lowercase() }
        } else {
            -1
        }
        
        // 如果路径匹配失败，尝试用ID匹配（兼容旧数据）
        if (songIndex < 0 && savedSongId >= 0) {
            songIndex = songs.indexOfFirst { it.id == savedSongId }
        }
        
        if (songIndex >= 0) {
            _currentSong.value = songs[songIndex]
            _currentPosition.value = savedPosition
            _loopMode.value = savedLoopMode
            hasRestoredState = true
            
            // 等待服务连接后再恢复播放
            viewModelScope.launch {
                // 等待服务绑定，最多等待3秒
                var waitCount = 0
                while (!isServiceBound || musicPlayerService == null) {
                    if (waitCount >= 30) break // 3秒超时
                    delay(100)
                    waitCount++
                }
                
                if (isServiceBound && musicPlayerService != null) {
                    musicPlayerService?.setMediaItems(songs, songIndex)
                    musicPlayerService?.seekTo(savedPosition)
                    _loopMode.value?.let { musicPlayerService?.setLoopMode(it) }
                    if (savedIsPlaying) {
                        musicPlayerService?.play()
                        _isPlaying.postValue(true)
                        startPositionUpdate()
                    }
                }
                // 无论成功还是超时，都重置标志
                hasRestoredState = false
            }
        }
    }

    private fun savePlaybackState(songId: Long, songPath: String, position: Long, isPlaying: Boolean, loopMode: Int) {
        playbackStateStorage.savePlaybackState(songId, songPath, position, isPlaying, loopMode)
        _songs.value?.let { playbackStateStorage.saveSongList(it) }
    }

    // 添加一个标志位来跟踪是否正在搜索
    private var isSearching = false
    
    fun searchSongs(query: String) {
        val allSongs = _songs.value ?: emptyList()
        if (query.isEmpty()) {
            _filteredSongs.postValue(allSongs)
            isSearching = false
        } else {
            val filtered = allSongs.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true)
            }
            _filteredSongs.postValue(filtered)
            isSearching = true
        }
    }

    /**
     * 更新歌曲列表（用于删除或移除歌曲后更新UI）
     */
    fun updateSongList(newSongs: List<Song>) {
        _songs.postValue(newSongs)
        _filteredSongs.postValue(newSongs)
    }

    // 当前播放列表（可能是完整列表或搜索结果列表）
    private var currentPlayList: List<Song> = emptyList()
    
    fun playSong(song: Song, songs: List<Song>) {
        if (isServiceBound && musicPlayerService != null) {
            // 保存当前播放列表引用
            currentPlayList = songs
            musicPlayerService?.setMediaItems(songs, songs.indexOfFirst { it.id == song.id })
            musicPlayerService?.play()
            _currentSong.value = song
            _isPlaying.value = true
            startPositionUpdate()
            // 保存播放状态
            savePlaybackState(song.id, song.path, 0, true, _loopMode.value ?: 0)
        }
    }

    fun togglePlayPause() {
        if (isServiceBound && musicPlayerService != null) {
            if (musicPlayerService!!.isPlaying()) {
                musicPlayerService?.pause()
                _isPlaying.value = false
            } else {
                musicPlayerService?.play()
                _isPlaying.value = true
                startPositionUpdate()
            }
            // 保存播放状态
            _currentSong.value?.let {
                savePlaybackState(it.id, it.path, _currentPosition.value ?: 0, _isPlaying.value ?: false, _loopMode.value ?: 0)
            }
        }
    }

    fun skipToNext() {
        if (isServiceBound && musicPlayerService != null) {
            musicPlayerService?.skipToNext()
            updateCurrentSong()
        }
    }

    fun skipToPrevious() {
        if (isServiceBound && musicPlayerService != null) {
            musicPlayerService?.skipToPrevious()
            updateCurrentSong()
        }
    }

    fun seekTo(position: Long) {
        if (isServiceBound && musicPlayerService != null) {
            musicPlayerService?.seekTo(position)
            _currentPosition.value = position
            // 保存播放状态
            _currentSong.value?.let {
                savePlaybackState(it.id, it.path, position, _isPlaying.value ?: false, _loopMode.value ?: 0)
            }
        }
    }

    fun toggleLoopMode() {
        if (isServiceBound && musicPlayerService != null) {
            musicPlayerService?.toggleLoopMode()
            _loopMode.value = musicPlayerService?.loopMode ?: MusicPlayerService.LOOP_MODE_NONE
        }
    }

    fun toggleShuffleMode() {
        if (isServiceBound && musicPlayerService != null) {
            musicPlayerService?.toggleShuffleMode()
            _isShuffleEnabled.value = musicPlayerService?.isShuffleModeEnabled ?: false
        }
    }

    fun moveSong(fromPosition: Int, toPosition: Int) {
        val currentSongs = _songs.value?.toMutableList() ?: return
        
        if (fromPosition in currentSongs.indices && toPosition in currentSongs.indices && fromPosition != toPosition) {
            val movedSong = currentSongs.removeAt(fromPosition)
            currentSongs.add(toPosition, movedSong)
            
            _songs.postValue(currentSongs)
            _filteredSongs.postValue(currentSongs)
            
            // 保存排序后的列表
            playbackStateStorage.saveSongList(currentSongs)
            
            // 更新播放器的媒体列表（使用 moveMediaItem，不影响播放状态）
            if (isServiceBound && musicPlayerService != null) {
                musicPlayerService?.updateSongListOrder(currentSongs, fromPosition, toPosition)
            }
        }
    }

    private fun updateCurrentSong() {
        if (isServiceBound && musicPlayerService != null) {
            val currentIndex = musicPlayerService!!.exoPlayer.currentMediaItemIndex
            // 使用当前播放列表而不是完整列表
            val songs = if (currentPlayList.isNotEmpty()) currentPlayList else _songs.value ?: emptyList()
            if (currentIndex >= 0 && currentIndex < songs.size) {
                _currentSong.value = songs[currentIndex]
                _duration.value = musicPlayerService!!.getDuration()
                // 保存播放状态
                val currentSong = songs[currentIndex]
                savePlaybackState(currentSong.id, currentSong.path, _currentPosition.value ?: 0, _isPlaying.value ?: false, _loopMode.value ?: 0)
            }
        }
    }

    fun refreshPlayerState() {
        if (isServiceBound && musicPlayerService != null) {
            _isPlaying.value = musicPlayerService!!.isPlaying()
            updateCurrentSong()
            _currentPosition.value = musicPlayerService!!.getCurrentPosition()
            _loopMode.value = musicPlayerService!!.loopMode
            _isShuffleEnabled.value = musicPlayerService!!.isShuffleModeEnabled
            if (_isPlaying.value == true) {
                startPositionUpdate()
            }
        }
    }

    // 刷新歌曲列表（用于添加新歌后手动刷新）
    fun refreshSongList() {
        loadMusic()
    }

    // 强制刷新：触发系统媒体库扫描后重新加载
    fun forceRefreshSongList() {
        _isLoading.value = true
        _scanProgress.value = 0
        _scanCurrentDir.value = "准备扫描..."
        
        viewModelScope.launch {
            try {
                // 设置扫描进度监听器
                musicScanner.setProgressListener(object : com.example.musicplayer.scanner.FileScanner.ScanProgressListener {
                    override fun onProgressChanged(progress: Int, currentDir: String, foundCount: Int) {
                        _scanProgress.postValue(progress)
                        _scanCurrentDir.postValue(currentDir)
                        // 实时更新发现的歌曲数量
                        _songs.postValue(_songs.value?.toMutableList() ?: mutableListOf())
                    }
                })
                
                // 使用混合扫描方式
                val scannedSongs = musicScanner.scanWithRefresh()
                
                // 强制刷新时直接使用扫描结果
                val finalSongs = scannedSongs
                
                // 保存歌曲列表，确保下次打开应用时能恢复
                playbackStateStorage.saveSongList(finalSongs)
                
                _songs.postValue(finalSongs)
                _filteredSongs.postValue(finalSongs)
                _scanCurrentDir.postValue("扫描完成")
            } catch (e: Exception) {
                e.printStackTrace()
                _scanCurrentDir.postValue("扫描失败")
                // 失败时回退到标准扫描
                loadMusic()
            } finally {
                _isLoading.postValue(false)
                _scanProgress.postValue(100)
            }
        }
    }
    
    // 菜单触发的扫描：只使用文件系统遍历（最多5层）
    fun scanFromMenu() {
        android.util.Log.d("MusicViewModel", "scanFromMenu() 调用，ViewModel ID: $instanceId")
        _isLoading.value = true
        _scanProgress.value = 0
        _scanCurrentDir.value = "准备扫描..."
        
        // 创建临时列表来存储扫描过程中发现的歌曲（用于实时更新UI）
        val tempSongs = mutableListOf<Song>()
        
        viewModelScope.launch {
            try {
                // 设置扫描进度监听器
                musicScanner.setProgressListener(object : com.example.musicplayer.scanner.FileScanner.ScanProgressListener {
                    override fun onProgressChanged(progress: Int, currentDir: String, foundCount: Int) {
                        // 进度为 -1 表示只更新目录，不改变进度条
                        if (progress >= 0) {
                            _scanProgress.postValue(progress)
                        }
                        _scanCurrentDir.postValue(currentDir)
                        // 实时更新发现的歌曲数量显示（只更新计数，不更新列表）
                        android.util.Log.d("MusicViewModel", "扫描进度：$progress%, 目录：$currentDir, 已发现：$foundCount 首")
                    }
                })
                
                // 使用文件系统扫描方式（支持临时列表实时更新）
                val scannedSongs = musicScanner.scanWithRefreshFileOnly(tempSongs)
                
                android.util.Log.d("MusicViewModel", "扫描完成，发现 ${scannedSongs.size} 首歌曲，ViewModel ID: $instanceId")
                android.util.Log.d("MusicViewModel", "临时列表歌曲数：${tempSongs.size}")
                
                // 扫描完成后，用去重排序后的结果更新临时列表（避免重复）
                tempSongs.clear()
                tempSongs.addAll(scannedSongs)
                android.util.Log.d("MusicViewModel", "临时列表已更新为去重结果，歌曲数: ${tempSongs.size}")
                
                // 扫描完成后直接使用扫描结果，不尝试恢复旧的排序
                val finalSongs = scannedSongs
                android.util.Log.d("MusicViewModel", "使用扫描结果，歌曲数: ${finalSongs.size}")
                
                android.util.Log.d("MusicViewModel", "更新歌曲列表，最终歌曲数: ${finalSongs.size}，ViewModel ID: $instanceId")
                
                // 打印最终歌曲列表（用于调试重复问题）
                android.util.Log.d("MusicViewModel", "========== 最终歌曲列表 ==========")
                for ((index, song) in finalSongs.withIndex()) {
                    // 打印所有歌曲的名称和路径
                    android.util.Log.d("MusicViewModel", "[$index] ${song.title} - ${song.artist} | ${song.path}")
                }
                android.util.Log.d("MusicViewModel", "========== 歌曲列表结束 ==========")
                
                // 使用 setValue 而不是 postValue，确保同步更新
                _songs.value = finalSongs
                _filteredSongs.value = finalSongs
                
                // 保存歌曲列表，确保下次打开应用时能恢复
                playbackStateStorage.saveSongList(finalSongs)
                android.util.Log.d("MusicViewModel", "歌曲列表已保存，共 ${finalSongs.size} 首")
                
                // 设置标志位，表示刚刚完成扫描，避免 loadMusic() 覆盖扫描结果
                justScanned = true
                android.util.Log.d("MusicViewModel", "已设置 justScanned = true")
                
                // 延迟检查 LiveData 值
                android.util.Log.d("MusicViewModel", "已更新 LiveData，songs.size: ${_songs.value?.size}")
                
                // 更新播放器的媒体列表，确保扫描到的歌曲能在播放器中使用
                android.util.Log.d("MusicViewModel", "检查服务绑定状态: isServiceBound=$isServiceBound, musicPlayerService=$musicPlayerService")
                if (isServiceBound && musicPlayerService != null) {
                    // 获取当前播放歌曲在新列表中的位置
                    val currentIndex = if (_currentSong.value != null) {
                        val index = finalSongs.indexOfFirst { it.id == _currentSong.value?.id }
                        android.util.Log.d("MusicViewModel", "当前歌曲ID: ${_currentSong.value?.id}, 在新列表中的位置: $index")
                        index
                    } else {
                        android.util.Log.d("MusicViewModel", "没有当前播放歌曲，从索引0开始")
                        0
                    }
                    
                    if (currentIndex >= 0 && currentIndex < finalSongs.size) {
                        // 更新播放器媒体列表，保持当前歌曲位置
                        android.util.Log.d("MusicViewModel", "调用 setMediaItems，歌曲数: ${finalSongs.size}, 当前索引: $currentIndex")
                        musicPlayerService?.setMediaItems(finalSongs, currentIndex)
                        android.util.Log.d("MusicViewModel", "播放器媒体列表已更新，当前索引: $currentIndex")
                    } else {
                        // 如果当前歌曲不在新列表中，从头开始
                        android.util.Log.d("MusicViewModel", "当前歌曲不在新列表中，从索引0开始")
                        musicPlayerService?.setMediaItems(finalSongs, 0)
                        android.util.Log.d("MusicViewModel", "播放器媒体列表已更新，当前歌曲不在新列表中，从索引0开始")
                    }
                    
                    // 验证播放器服务中的歌曲列表
                    android.util.Log.d("MusicViewModel", "验证播放器服务中的歌曲列表...")
                    // 注意：这里无法直接访问 service 的 songList，只能通过日志验证
                } else {
                    android.util.Log.e("MusicViewModel", "服务未绑定或为空，无法更新播放器媒体列表")
                }
                
                _scanCurrentDir.postValue("扫描完成")
            } catch (e: Exception) {
                android.util.Log.e("MusicViewModel", "扫描失败: ${e.message}", e)
                // 异常时清空临时列表，避免显示错误数据
                tempSongs.clear()
                _scanCurrentDir.postValue("扫描失败")
            } finally {
                _isLoading.postValue(false)
                _scanProgress.postValue(100)
            }
        }
    }

    private fun updatePlayerState() {
        // 如果正在恢复状态，跳过这次更新，等待恢复完成
        if (hasRestoredState) {
            return
        }
        refreshPlayerState()
    }

    private fun startPositionUpdate() {
        viewModelScope.launch {
            while (_isPlaying.value == true && isServiceBound) {
                if (musicPlayerService != null) {
                    _currentPosition.postValue(musicPlayerService!!.getCurrentPosition())
                    _duration.postValue(musicPlayerService!!.getDuration())
                }
                delay(1000)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (isServiceBound) {
            try {
                getApplication<Application>().unbindService(serviceConnection)
            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
            }
            isServiceBound = false
        }
    }
}