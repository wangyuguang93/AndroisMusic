package com.example.musicplayer.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.musicplayer.service.MusicPlayerService
import com.example.musicplayer.model.Song
import com.example.musicplayer.scanner.MusicScanner
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

// 音乐视图模型类，继承自 AndroidViewModel，用于管理音乐相关的数据和逻辑
class MusicViewModel(application: Application) : AndroidViewModel(application) {
    // 可变的 LiveData，用于存储歌曲列表
    private val _songs = MutableLiveData<List<Song>>()
    // 不可变的 LiveData，供外部观察歌曲列表
    val songs: LiveData<List<Song>> = _songs
    // 可变的 LiveData，用于存储加载状态
    private val _isLoading = MutableLiveData<Boolean>()
    // 不可变的 LiveData，供外部观察加载状态
    val isLoading: LiveData<Boolean> = _isLoading
    // 可变的 LiveData，用于存储当前播放的歌曲
    private val _currentSong = MutableLiveData<Song?>(null)
    // 不可变的 LiveData，供外部观察当前播放的歌曲
    val currentSong: LiveData<Song?> = _currentSong
    // 可变的 LiveData，用于存储播放状态
    private val _isPlaying = MutableLiveData<Boolean>(false)
    // 不可变的 LiveData，供外部观察播放状态
    val isPlaying: LiveData<Boolean> = _isPlaying
    // 可变的 LiveData，用于存储当前播放位置
    private val _currentPosition = MutableLiveData<Long>(0)
    // 不可变的 LiveData，供外部观察当前播放位置
    val currentPosition: LiveData<Long> = _currentPosition
    // 可变的 LiveData，用于存储歌曲总时长
    private val _duration = MutableLiveData<Long>(0)
    // 不可变的 LiveData，供外部观察歌曲总时长
    val duration: LiveData<Long> = _duration
    // 音乐播放器服务实例
    private var musicPlayerService: MusicPlayerService? = null
    // 服务是否已绑定
    private var isServiceBound = false
    // 音乐扫描器实例
    private val musicScanner = MusicScanner(application, viewModelScope)
    // 服务连接回调对象
    private val serviceConnection = object : ServiceConnection {
        // 服务连接成功时调用
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            // 获取服务的本地绑定器
            val binder = service as MusicPlayerService.LocalBinder
            musicPlayerService = binder.service
            isServiceBound = true
            updatePlayerState()
        }
        // 服务断开连接时调用
        override fun onServiceDisconnected(arg0: ComponentName) {
            isServiceBound = false
        }
    }

    init {
        loadMusic()
        bindToService()
    }

    // 绑定到音乐服务
    private fun bindToService() {
        // 创建启动音乐服务的意图
        val intent = Intent(getApplication(), MusicPlayerService::class.java)
        getApplication<Application>().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    // 加载音乐文件
    fun loadMusic() {
        _isLoading.value = true
        musicScanner.startScan {
            try {
                _songs.postValue(it)
            } catch (e: Exception) {
                Log.e("MusicViewModel", "Failed to load music", e)
            }
            _isLoading.value = false
        }
    }

    // 播放指定歌曲
    fun playSong(song: Song, songs: List<Song>) {
        // 获取歌曲在列表中的索引
        val index = songs.indexOfFirst { it.id == song.id }
        if (index != -1) {
            musicPlayerService?.setMediaItems(songs, index)
            musicPlayerService?.exoPlayer?.seekTo(index, 0)
            musicPlayerService?.play()
            _currentSong.value = song
            _isPlaying.value = true
            startPositionUpdate()
        }
    }

    // 切换播放/暂停状态
    fun togglePlayPause() {
        if (isServiceBound) {
            if (musicPlayerService?.isPlaying() == true) {
                musicPlayerService?.pause()
                _isPlaying.value = false
            } else {
                musicPlayerService?.play()
                _isPlaying.value = true
                startPositionUpdate()
            }
        }
    }

    // 跳到下一首歌曲
    fun skipToNext() {
        if (isServiceBound) {
            musicPlayerService?.skipToNext()
            updateCurrentSong()
        }
    }

    // 跳到上一首歌曲
    fun skipToPrevious() {
        if (isServiceBound) {
            musicPlayerService?.skipToPrevious()
            updateCurrentSong()
        }
    }

    // 跳转到指定播放位置
    fun seekTo(position: Long) {
        if (isServiceBound) {
            musicPlayerService?.seekTo(position)
            _currentPosition.value = position
        }
    }

    // 更新当前播放的歌曲
    private fun updateCurrentSong() {
        // 获取当前媒体项的索引
        val currentIndex = musicPlayerService?.exoPlayer?.currentMediaItemIndex ?: -1
        // 获取歌曲列表
        val songs = _songs.value ?: emptyList()
        if (currentIndex >= 0 && currentIndex < songs.size) {
            _currentSong.value = songs[currentIndex]
            _duration.value = musicPlayerService?.getDuration() ?: 0
        }
    }

    // 更新播放器状态
    private fun updatePlayerState() {
        _isPlaying.value = musicPlayerService?.isPlaying() ?: false
        updateCurrentSong()
        _currentPosition.value = musicPlayerService?.getCurrentPosition() ?: 0
        if (_isPlaying.value == true) {
            startPositionUpdate()
        }
    }

    // 开始更新播放位置
    private fun startPositionUpdate() {
        viewModelScope.launch {
            while (_isPlaying.value == true) {
                _currentPosition.postValue(musicPlayerService?.getCurrentPosition() ?: 0)
                _duration.postValue(musicPlayerService?.getDuration() ?: 0)
                delay(1000)
            }
        }
    }

    // 视图模型销毁时调用
    override fun onCleared() {
        super.onCleared()
        if (isServiceBound) {
            getApplication<Application>().unbindService(serviceConnection)
            isServiceBound = false
        }
    }
    // 可变的 LiveData，用于存储搜索结果歌曲列表
    private val _searchResults = MutableLiveData<List<Song>>()
    // 不可变的 LiveData，供外部观察搜索结果歌曲列表
    val searchResults: LiveData<List<Song>> = _searchResults

    // 搜索歌曲的方法
    fun searchSongs(query: String) {
        val allSongs = _songs.value ?: emptyList()
        val results = allSongs.filter {
            it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true)
        }
        _searchResults.value = results
    }
}