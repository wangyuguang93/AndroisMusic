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
            
            musicPlayerService?.setPlayerStateListener(object : MusicPlayerService.PlayerStateListener {
                override fun onCurrentSongChanged(song: Song?) {
                    _currentSong.postValue(song)
                    _duration.postValue(musicPlayerService?.getDuration())
                    // 保存播放状态
                    song?.let {
                        savePlaybackState(it.id, _currentPosition.value ?: 0, _isPlaying.value ?: false, _loopMode.value ?: 0)
                    }
                }

                override fun onPlaybackStateChanged(isPlaying: Boolean) {
                    _isPlaying.postValue(isPlaying)
                    // 保存播放状态
                    _currentSong.value?.let {
                        savePlaybackState(it.id, _currentPosition.value ?: 0, isPlaying, _loopMode.value ?: 0)
                    }
                    if (isPlaying) {
                        startPositionUpdate()
                    }
                }

                override fun onLoopModeChanged(mode: Int) {
                    _loopMode.postValue(mode)
                    // 保存播放状态
                    _currentSong.value?.let {
                        savePlaybackState(it.id, _currentPosition.value ?: 0, _isPlaying.value ?: false, mode)
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
        loadMusic()
        startAndBindService()
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
        _isLoading.value = true
        viewModelScope.launch {
            try {
                // 扫描设备上的音乐文件
                val scannedSongs = musicScanner.scanMusic()
                
                // 尝试加载保存的歌曲ID列表
                val finalSongs = if (playbackStateStorage.hasSavedSongList()) {
                    val savedSongIds = playbackStateStorage.getSavedSongIds()
                    // 根据保存的ID顺序重新排序扫描到的歌曲
                    reorderSongsBySavedIds(scannedSongs, savedSongIds)
                } else {
                    scannedSongs
                }
                
                _songs.postValue(finalSongs)
                _filteredSongs.postValue(finalSongs)
                
                // 尝试恢复播放状态
                restorePlaybackState(finalSongs)
            } catch (e: Exception) {
                e.printStackTrace()
                // 发生错误时，直接使用扫描到的歌曲
                val scannedSongs = musicScanner.scanMusic()
                _songs.postValue(scannedSongs)
                _filteredSongs.postValue(scannedSongs)
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    private fun reorderSongsBySavedIds(scanned: List<Song>, savedIds: List<Long>): List<Song> {
        val scannedMap = scanned.associateBy { it.id }
        // 按保存的ID顺序排列歌曲，保留用户排序
        return savedIds.mapNotNull { id -> scannedMap[id] }
    }

    private fun restorePlaybackState(songs: List<Song>) {
        if (!playbackStateStorage.hasSavedState() || songs.isEmpty()) {
            return
        }
        
        val savedSongId = playbackStateStorage.getCurrentSongId()
        val savedPosition = playbackStateStorage.getCurrentPosition()
        val savedIsPlaying = playbackStateStorage.isPlaying()
        val savedLoopMode = playbackStateStorage.getLoopMode()
        
        val songIndex = songs.indexOfFirst { it.id == savedSongId }
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

    private fun savePlaybackState(songId: Long, position: Long, isPlaying: Boolean, loopMode: Int) {
        playbackStateStorage.savePlaybackState(songId, position, isPlaying, loopMode)
        _songs.value?.let { playbackStateStorage.saveSongList(it) }
    }

    fun searchSongs(query: String) {
        val allSongs = _songs.value ?: emptyList()
        if (query.isEmpty()) {
            _filteredSongs.postValue(allSongs)
        } else {
            val filtered = allSongs.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true)
            }
            _filteredSongs.postValue(filtered)
        }
    }

    fun playSong(song: Song, songs: List<Song>) {
        if (isServiceBound && musicPlayerService != null) {
            musicPlayerService?.setMediaItems(songs, songs.indexOfFirst { it.id == song.id })
            musicPlayerService?.play()
            _currentSong.value = song
            _isPlaying.value = true
            startPositionUpdate()
            // 保存播放状态
            savePlaybackState(song.id, 0, true, _loopMode.value ?: 0)
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
                savePlaybackState(it.id, _currentPosition.value ?: 0, _isPlaying.value ?: false, _loopMode.value ?: 0)
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
                savePlaybackState(it.id, position, _isPlaying.value ?: false, _loopMode.value ?: 0)
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
            val songs = _songs.value ?: emptyList()
            if (currentIndex >= 0 && currentIndex < songs.size) {
                _currentSong.value = songs[currentIndex]
                _duration.value = musicPlayerService!!.getDuration()
                // 保存播放状态
                savePlaybackState(songs[currentIndex].id, _currentPosition.value ?: 0, _isPlaying.value ?: false, _loopMode.value ?: 0)
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