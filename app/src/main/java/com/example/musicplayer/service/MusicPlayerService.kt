package com.example.musicplayer.service
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSourceFactory
import androidx.media3.datasource.DataSource
import androidx.media3.session.legacy.MediaSessionCompat
import androidx.media3.session.legacy.PlaybackStateCompat
import com.example.musicplayer.model.Song
import com.example.musicplayer.receiver.UsbDeviceReceiver
// Remove deprecated import
// androidx.media3.session.SessionPlaybackState
// import android.media.AudioManager // Commented out if not directly used after ExoPlayer setup
// import android.media.session.MediaSession // This will be replaced by Media3 MediaSession
// import android.os.PowerManager // Commented out if not directly used
// import com.example.musicplayer.R // Keep if used for notification resources
// import com.example.musicplayer.ui.MainActivity // Keep if used for notification intent
// Replace ExoPlayer v2 imports with Media3 imports
// For DataSource, if you are using DefaultDataSourceFactory with Media3,
// it might be androidx.media3.datasource.DefaultDataSource
// 定义音乐播放器服务类，继承自 Service 类
@UnstableApi
class MusicPlayerService : Service() {
    public  val guang = 1;
    private val TAG = "MusicPlayerService"
    // 通知渠道 ID
    private val CHANNEL_ID = "music_player_channel"
    // 通知 ID
    private val NOTIFICATION_ID = 1
    // 无循环模式
    companion object {
        val LOOP_MODE_NONE = 0
        const val LOOP_MODE_ALL = 1
        const val LOOP_MODE_ONE = 2
    }
    val LOOP_MODE_ONE = 2
    var loopMode = LOOP_MODE_NONE
    // 本地绑定器实例
    private val binder = LocalBinder()
    // 延迟初始化的 ExoPlayer 实例
    lateinit var exoPlayer: ExoPlayer // This is the lateinit property
    // 媒体会话兼容实例
    @SuppressLint("RestrictedApi")
    private lateinit var mediaSessionCompat: MediaSessionCompat
    // 音频管理器实例
    private lateinit var audioManager: AudioManager
    // 数据源工厂，确保与 Media3 兼容
    private lateinit var dataSourceFactory: DataSource.Factory // Keep, ensure it's compatible with Media3
    // 当前播放的歌曲
    private var currentSong: Song? = null
    // 歌曲列表
    private var songList: List<Song> = emptyList()
    // 当前在歌曲列表中的位置，重命名以提高清晰度
    private var currentPositionInList = 0 // Renamed for clarity from currentPosition
    // USB 管理器实例
    private var usbManager: UsbManager? = null
    // USB 设备实例
    private var usbDevice: UsbDevice? = null
    // USB 设备连接实例
    private var usbConnection: UsbDeviceConnection? = null
    // USB 设备广播接收器实例
    private var usbReceiver: UsbDeviceReceiver? = null
    // USB DAC 是否启用
    private var isUsbDacEnabled = false

    // 内部类，用于本地绑定服务
    inner class LocalBinder : Binder() {
        // 提供对 MusicPlayerService 实例的访问
        val service: MusicPlayerService
            get() = this@MusicPlayerService
    }

    // **MODIFICATION: Initialize exoPlayer and MediaSession in onCreate**
    @SuppressLint("RestrictedApi", "ForegroundServiceType")
    @OptIn(UnstableApi::class) override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate: Initializing player and media session.")

        // Initialize ExoPlayer
        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true) // Example configuration
            .setHandleAudioBecomingNoisy(true)
            .build()

        // 添加错误监听
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "ExoPlayer 播放错误: ", error)
                // 可以在这里添加更多错误处理逻辑，如提示用户等
            }
        })

        // Apply initial loop mode
        applyLoopMode()

        // 初始化音频管理器
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        // 初始化 MediaSessionCompat
        mediaSessionCompat = MediaSessionCompat(this, "MusicPlayerService")
        mediaSessionCompat.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
        // Replace SessionPlaybackState with Player state
        mediaSessionCompat.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(convertPlayerStateToPlaybackState(exoPlayer.playbackState), exoPlayer.currentPosition, 1f)
                .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                .build()
        )
        mediaSessionCompat.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                play()
            }

            override fun onPause() {
                pause()
            }

            override fun onSkipToNext() {
                skipToNext()
            }

            override fun onSkipToPrevious() {
                skipToPrevious()
            }
        })
        mediaSessionCompat.isActive = true

        // 初始化 DataSourceFactory (example using DefaultDataSourceFactory for Media3)
        dataSourceFactory = DefaultDataSourceFactory(this, TAG) // Adjust if using custom data sources

        // 创建通知渠道
        createNotificationChannel()

        // 创建通知
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // TODO: Register USB receiver if needed
        // usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        // usbReceiver = UsbDeviceReceiver(this)
        // // 创建意图过滤器，暂时注释掉
        // val filter = IntentFilter()
        // filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        // filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        // registerReceiver(usbReceiver, filter)

        Log.d(TAG, "Service fully initialized.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Player",
                NotificationManager.IMPORTANCE_LOW
            )
            val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            service.createNotificationChannel(channel)
        }
    }

    @SuppressLint("RestrictedApi")
    private fun createNotification(): android.app.Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Music Player")
            .setContentText("Playing music")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(mediaSessionCompat.controller.sessionActivity)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)

        // 添加播放控制操作
        builder.addAction(android.R.drawable.ic_media_previous, "Previous",
            mediaSessionCompat.controller.sessionActivity
        )
        builder.addAction(android.R.drawable.ic_media_play, "Play",
            mediaSessionCompat.controller.sessionActivity
        )
        builder.addAction(android.R.drawable.ic_media_next, "Next",
            mediaSessionCompat.controller.sessionActivity
        )

        return builder.build()
    }

    // Add helper function to convert Player state to PlaybackStateCompat
    @SuppressLint("RestrictedApi")
    private fun convertPlayerStateToPlaybackState(playerState: Int): Int {
        return when (playerState) {
            Player.STATE_IDLE -> PlaybackStateCompat.STATE_STOPPED
            Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            Player.STATE_READY -> if (exoPlayer.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            Player.STATE_ENDED -> PlaybackStateCompat.STATE_STOPPED
            else -> PlaybackStateCompat.STATE_NONE
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    // **MODIFICATION: Release exoPlayer and MediaSession in onDestroy**
    @SuppressLint("RestrictedApi")
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy: Releasing resources.")
        // 释放 MediaSessionCompat
        mediaSessionCompat.isActive = false
        mediaSessionCompat.release()

        if (::exoPlayer.isInitialized) {
            exoPlayer.release()
        } else {
            Log.e(TAG, "exoPlayer was not initialized before destruction (or already released).")
        }

        // Unregister USB receiver
        // if (usbReceiver != null) {
        //     unregisterReceiver(usbReceiver)
        // }
        // disconnectUsbDac() // Ensure this doesn't try to use exoPlayer if it's already released
        Log.d(TAG, "Service destroyed")
    }

    // --- Player Control Methods ---
    // **MODIFICATION: Add isInitialized checks for safety, though should be fine after onCreate fix**

    fun play() {
        if (!::exoPlayer.isInitialized) {
            Log.w(TAG, "play() called but exoPlayer not initialized.")
            return
        }
        exoPlayer.play()
    }

    fun pause() {
        if (!::exoPlayer.isInitialized) {
            Log.w(TAG, "pause() called but exoPlayer not initialized.")
            return
        }
        exoPlayer.pause()
    }

    fun togglePlayPause() {
        if (!::exoPlayer.isInitialized) {
            Log.w(TAG, "togglePlayPause() called but exoPlayer not initialized.")
            return
        }
        if (exoPlayer.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    fun skipToNext() {
        if (!::exoPlayer.isInitialized) {
            Log.w(TAG, "skipToNext() called but exoPlayer not initialized.")
            return
        }
        exoPlayer.seekToNextMediaItem()
    }

    fun skipToPrevious() {
        if (!::exoPlayer.isInitialized) {
            Log.w(TAG, "skipToPrevious() called but exoPlayer not initialized.")
            return
        }
        exoPlayer.seekToPreviousMediaItem()
    }

    // playPrevious logic seems fine, just ensure exoPlayer is ready.
    fun playPrevious() {
        if (!::exoPlayer.isInitialized) {
            Log.w(TAG, "playPrevious() called but exoPlayer not initialized.")
            return
        }
        if (songList.isEmpty()) return
        currentPositionInList = (currentPositionInList - 1 + songList.size) % songList.size
        playSongAtIndex(currentPositionInList) // Changed to avoid direct song object dependency if list changes
    }


    fun seekTo(position: Long) {
        if (!::exoPlayer.isInitialized) {
            Log.w(TAG, "seekTo() called but exoPlayer not initialized.")
            return
        }
        exoPlayer.seekTo(position)
    }

    fun getDuration(): Long {
        if (!::exoPlayer.isInitialized) {
            Log.w(TAG, "getDuration() called but exoPlayer not initialized.")
            return 0L
        }
        return exoPlayer.duration
    }

    fun getCurrentPosition(): Long {
        if (!::exoPlayer.isInitialized) {
            Log.w(TAG, "getCurrentPosition() called but exoPlayer not initialized.")
            return 0L
        }
        return exoPlayer.currentPosition
    }

    fun isPlaying(): Boolean {
        if (!::exoPlayer.isInitialized) {
            Log.w(TAG, "isPlaying() called but exoPlayer not initialized.")
            return false
        }
        return exoPlayer.isPlaying
    }

    fun getCurrentSong(): Song? {
        // This method depends on how you update currentSong,
        // ensure it's consistent with exoPlayer.currentMediaItem
        if (!::exoPlayer.isInitialized || songList.isEmpty() || exoPlayer.currentMediaItemIndex < 0 || exoPlayer.currentMediaItemIndex >= songList.size) {
            return null
        }
        // It's safer to get the current song based on the player's currentMediaItemIndex
        // This assumes your songList directly corresponds to the media items in ExoPlayer
        return songList.getOrNull(exoPlayer.currentMediaItemIndex)
    }

    fun toggleLoopMode() {
        if (!::exoPlayer.isInitialized) {
            Log.w(TAG, "toggleLoopMode() called but exoPlayer not initialized.")
            return
        }
        loopMode = when (loopMode) {
            LOOP_MODE_NONE -> LOOP_MODE_ALL
            LOOP_MODE_ALL -> LOOP_MODE_ONE
            else -> LOOP_MODE_NONE
        }
        applyLoopMode()
    }

    private fun applyLoopMode() {
        if (!::exoPlayer.isInitialized) return // Don't try to apply if player isn't ready

        when (loopMode) {
            LOOP_MODE_ONE -> exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
            LOOP_MODE_ALL -> exoPlayer.repeatMode = Player.REPEAT_MODE_ALL
            LOOP_MODE_NONE -> exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    // Renamed for clarity and to take index
    fun playSongAtIndex(index: Int) {
        if (!::exoPlayer.isInitialized) {
            Log.w(TAG, "playSongAtIndex() called but exoPlayer not initialized.")
            return
        }
        if (songList.isEmpty() || index < 0 || index >= songList.size) {
            Log.e(TAG, "Invalid index or empty song list for playSongAtIndex.")
            return
        }
        currentSong = songList[index]
        currentPositionInList = index
        // If media items are already set, just seek. Otherwise, set media items.
        if (exoPlayer.mediaItemCount > 0 && index < exoPlayer.mediaItemCount) {
            exoPlayer.seekToDefaultPosition(index)
            exoPlayer.prepare() // Prepare if not already prepared or after seeking
            exoPlayer.play()
        } else {
            // This suggests setMediaItems should be called first
            Log.w(
                TAG,
                "playSongAtIndex called, but media items might not be set or index is out of bounds for current playlist."
            )
            // You might want to call setMediaItems here if that's the desired behavior
            // For now, let's assume setMediaItems is called separately to populate the playlist
            val mediaItem = MediaItem.fromUri(songList[index].uri)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.play()
        }
    }


    // Original playSong might be less safe if songList changes.
    // Consider using playSongAtIndex internally more.
    fun playSong(song: Song) {
        val index = songList.indexOf(song)
        if (index != -1) {
            playSongAtIndex(index)
        } else {
            Log.e(TAG, "Song not found in current songList: ${song.title}")
            // Optionally, add the song to the list and then play
            // currentSong = song
            // val mediaItem = MediaItem.fromUri(song.uri)
            // exoPlayer.setMediaItem(mediaItem)
            // exoPlayer.prepare()
            // exoPlayer.play()
        }
    }


    fun setMediaItems(songs: List<Song>, startIndex: Int) {
        if (!::exoPlayer.isInitialized) {
            Log.w(TAG, "setMediaItems() called but exoPlayer not initialized.")
            return
        }
        this.songList = songs // Update the internal song list
        val mediaItems = songs.map { MediaItem.fromUri(it.uri) }
        exoPlayer.setMediaItems(
            mediaItems,
            startIndex,
            0L
        ) // Start at specific index and position 0
        exoPlayer.prepare()
        // Optionally, start playback immediately:
        // exoPlayer.play()
        currentPositionInList = startIndex
        currentSong = songs.getOrNull(startIndex)
    }

    // USB DAC connection function (stub for now)
    fun connectUsbDac(device: UsbDevice) {
        // TODO: Implement USB DAC connection logic
        // This might involve re-initializing ExoPlayer or setting its audio sink
        Log.d(TAG, "connectUsbDac called for device: ${device.deviceName}")
        isUsbDacEnabled = true // Example
    }

    @SuppressLint("RestrictedApi")
    fun disconnectUsbDac() {
        usbConnection?.close()
        usbConnection = null
        usbDevice = null
        isUsbDacEnabled = false
        Toast.makeText(this, "USB DAC已断开连接", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "USB DAC已断开连接")

        if (!::exoPlayer.isInitialized) {
            Log.w(
                TAG,
                "disconnectUsbDac: exoPlayer not initialized, cannot re-initialize for default audio."
            )
            return
        }
        // Reinitialize player if music is playing to use default audio output
        if (exoPlayer.isPlaying) {
            val currentPlaybackPosition = exoPlayer.currentPosition
            val currentMediaItemIndex = exoPlayer.currentMediaItemIndex
            // Re-initialize or update audio sink (simpler to re-initialize for this example)
            // This is a bit heavy-handed; a better approach might involve setting audio output device on ExoPlayer if API allows
            exoPlayer.release() // Release the old instance

            // Re-initialize with default audio output
            exoPlayer = ExoPlayer.Builder(this)
                .setAudioAttributes(AudioAttributes.DEFAULT, true)
                .setHandleAudioBecomingNoisy(true)
                .build()
            applyLoopMode() // Re-apply loop mode
            // Replace SessionPlaybackState with Player state
            mediaSessionCompat.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(convertPlayerStateToPlaybackState(exoPlayer.playbackState), exoPlayer.currentPosition, 1f)
                    .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                    .build()
            ) // Update media session with new player instance

            if (songList.isNotEmpty() && currentMediaItemIndex >= 0 && currentMediaItemIndex < songList.size) {
                val mediaItems = songList.map { MediaItem.fromUri(it.uri) }
                exoPlayer.setMediaItems(mediaItems, currentMediaItemIndex, currentPlaybackPosition)
                exoPlayer.prepare()
                exoPlayer.play()
            }
        }
    }


}

