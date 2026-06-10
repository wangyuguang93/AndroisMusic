package com.example.musicplayer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.media.session.MediaSession
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.musicplayer.R
import com.example.musicplayer.model.Song
import com.example.musicplayer.receiver.UsbDeviceReceiver
import com.example.musicplayer.ui.MainActivity
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.source.ConcatenatingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.util.Util

class MusicPlayerService : android.app.Service() {
    private val TAG = "MusicPlayerService"
    private val CHANNEL_ID = "music_player_channel"
    private val NOTIFICATION_ID = 1
    private val USB_PERMISSION_ACTION = "com.example.musicplayer.USB_PERMISSION"

    companion object {
        const val LOOP_MODE_NONE = 0
        const val LOOP_MODE_ALL = 1
        const val LOOP_MODE_ONE = 2
        const val LOOP_MODE_SHUFFLE = 3
    }

    interface PlayerStateListener {
        fun onCurrentSongChanged(song: Song?)
        fun onPlaybackStateChanged(isPlaying: Boolean)
        fun onLoopModeChanged(mode: Int)
        fun onPositionChanged(position: Long, duration: Long)
    }

    private var playerStateListener: PlayerStateListener? = null

    fun setPlayerStateListener(listener: PlayerStateListener?) {
        playerStateListener = listener
    }

    var loopMode = LOOP_MODE_NONE
        private set

    var isShuffleModeEnabled = false
        private set

    private val binder = LocalBinder()
    lateinit var exoPlayer: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var playerNotificationManager: PlayerNotificationManager
    private lateinit var dataSourceFactory: DataSource.Factory
    private var currentSong: Song? = null
    private var songList: List<Song> = emptyList()
    private var currentPosition = 0

    private var powerManager: PowerManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // USB DAC related variables
    private var usbManager: UsbManager? = null
    private var usbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var usbEndpoint: UsbEndpoint? = null
    private var usbReceiver: UsbDeviceReceiver? = null
    private var isUsbDacEnabled = false

    inner class LocalBinder : Binder() {
        val service: MusicPlayerService
            get() = this@MusicPlayerService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        initializePlayer()
        initializeNotificationChannel()
        initializeNotificationManager()
        initializeUsbReceiver()
        acquireWakeLock()
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    private fun initializePlayer() {
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setHandleAudioBecomingNoisy(true)
            .build()

        dataSourceFactory = DefaultDataSource.Factory(this)

        exoPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                super.onMediaItemTransition(mediaItem, reason)
                updateCurrentSong()
                playerStateListener?.onCurrentSongChanged(currentSong)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                playerStateListener?.onPlaybackStateChanged(exoPlayer.isPlaying)
                if (playbackState == Player.STATE_ENDED && loopMode == LOOP_MODE_NONE) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }

            override fun onPositionDiscontinuity(reason: Int) {
                super.onPositionDiscontinuity(reason)
                playerStateListener?.onCurrentSongChanged(currentSong)
            }
        })
    }

    private fun initializeNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音乐播放",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "音乐播放器通知频道"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun initializeNotificationManager() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        playerNotificationManager = PlayerNotificationManager.Builder(this, NOTIFICATION_ID, CHANNEL_ID)
            .setNotificationListener(object : PlayerNotificationManager.NotificationListener {
                override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
                    super.onNotificationCancelled(notificationId, dismissedByUser)
                    if (!exoPlayer.isPlaying) {
                        stopSelf()
                    }
                }
            })
            .setMediaDescriptionAdapter(object : PlayerNotificationManager.MediaDescriptionAdapter {
                override fun getCurrentContentTitle(player: Player): CharSequence {
                    return currentSong?.title ?: "未知歌曲"
                }

                override fun getCurrentContentText(player: Player): CharSequence {
                    return currentSong?.artist ?: "未知艺术家"
                }

                override fun getCurrentLargeIcon(
                    player: Player,
                    callback: PlayerNotificationManager.BitmapCallback
                ): android.graphics.Bitmap? {
                    return null
                }

                override fun createCurrentContentIntent(player: Player): PendingIntent? {
                    return pendingIntent
                }
            })
            .build()

        playerNotificationManager.setPlayer(exoPlayer)
        playerNotificationManager.setPriority(Notification.PRIORITY_LOW)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentSong?.title ?: "音乐播放器")
            .setContentText(currentSong?.artist ?: "正在播放")
            .setSmallIcon(R.drawable.ic_music_placeholder)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun initializeUsbReceiver() {
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        usbReceiver = UsbDeviceReceiver()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(USB_PERMISSION_ACTION)
        }
        registerReceiver(usbReceiver, filter)
    }

    private fun acquireWakeLock() {
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:WakeLock")
        wakeLock?.acquire()
    }

    private fun releaseWakeLock() {
        wakeLock?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        
        releaseWakeLock()
        unregisterReceiver(usbReceiver)
        disconnectUsbDac()
        
        if (::exoPlayer.isInitialized) {
            exoPlayer.release()
        }
        
        playerNotificationManager.setPlayer(null)
    }

    fun play() = exoPlayer.play()
    fun pause() = exoPlayer.pause()
    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    fun skipToNext() = exoPlayer.seekToNextMediaItem()
    fun skipToPrevious() = exoPlayer.seekToPreviousMediaItem()

    fun seekTo(position: Long) = exoPlayer.seekTo(position)
    fun getDuration() = exoPlayer.duration
    fun getCurrentPosition() = exoPlayer.currentPosition
    fun isPlaying() = exoPlayer.isPlaying
    fun getCurrentSong(): Song? = currentSong

    fun toggleLoopMode() {
        loopMode = when (loopMode) {
            LOOP_MODE_NONE -> LOOP_MODE_ALL
            LOOP_MODE_ALL -> LOOP_MODE_ONE
            LOOP_MODE_ONE -> LOOP_MODE_SHUFFLE
            else -> LOOP_MODE_NONE
        }
        applyLoopMode()
        playerStateListener?.onLoopModeChanged(loopMode)
    }

    fun setLoopMode(mode: Int) {
        if (mode in LOOP_MODE_NONE..LOOP_MODE_SHUFFLE) {
            loopMode = mode
            applyLoopMode()
        }
    }

    private fun applyLoopMode() {
        when (loopMode) {
            LOOP_MODE_ONE -> {
                exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
                exoPlayer.shuffleModeEnabled = false
            }
            LOOP_MODE_ALL -> {
                exoPlayer.repeatMode = Player.REPEAT_MODE_ALL
                exoPlayer.shuffleModeEnabled = false
            }
            LOOP_MODE_SHUFFLE -> {
                exoPlayer.repeatMode = Player.REPEAT_MODE_ALL
                exoPlayer.shuffleModeEnabled = true
            }
            LOOP_MODE_NONE -> {
                exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
                exoPlayer.shuffleModeEnabled = false
            }
        }
    }

    fun toggleShuffleMode() {
        isShuffleModeEnabled = !isShuffleModeEnabled
        exoPlayer.shuffleModeEnabled = isShuffleModeEnabled
    }

    fun setShuffleMode(enabled: Boolean) {
        isShuffleModeEnabled = enabled
        exoPlayer.shuffleModeEnabled = enabled
    }

    fun playSong(song: Song) {
        currentSong = song
        currentPosition = songList.indexOfFirst { it.id == song.id }
        val mediaItem = MediaItem.fromUri(song.uri)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.play()
    }

    fun setMediaItems(songs: List<Song>, startIndex: Int) {
        songList = songs
        val mediaItems = songs.map { MediaItem.fromUri(it.uri) }
        exoPlayer.setMediaItems(mediaItems)
        exoPlayer.prepare()
        exoPlayer.seekTo(startIndex, 0)
        if (startIndex < songs.size) {
            currentSong = songs[startIndex]
            currentPosition = startIndex
        }
    }

    // 更新歌曲列表顺序，不影响当前播放状态
    fun updateSongListOrder(songs: List<Song>, fromPosition: Int, toPosition: Int) {
        songList = songs
        
        // 使用 moveMediaItem 来移动单个项，这样不会中断播放
        if (fromPosition != toPosition) {
            exoPlayer.moveMediaItem(fromPosition, toPosition)
        }
        
        // 更新当前歌曲信息
        val currentIndex = exoPlayer.currentMediaItemIndex
        if (currentIndex >= 0 && currentIndex < songs.size) {
            currentSong = songs[currentIndex]
            currentPosition = currentIndex
        }
    }

    private fun updateCurrentSong() {
        val currentIndex = exoPlayer.currentMediaItemIndex
        if (currentIndex >= 0 && currentIndex < songList.size) {
            currentSong = songList[currentIndex]
            currentPosition = currentIndex
        }
    }

    fun connectUsbDac(device: UsbDevice) {
        if (!usbManager?.hasPermission(device)!!) {
            val permissionIntent = PendingIntent.getBroadcast(
                this,
                0,
                Intent(USB_PERMISSION_ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            usbManager?.requestPermission(device, permissionIntent)
            return
        }

        try {
            usbDevice = device
            usbInterface = device.getInterface(0)
            
            if (usbInterface?.interfaceClass != 0x01) {
                Toast.makeText(this, "不是音频设备", Toast.LENGTH_SHORT).show()
                return
            }

            usbConnection = usbManager?.openDevice(device)
            usbConnection?.let { connection ->
                if (connection.claimInterface(usbInterface, true)) {
                    usbEndpoint = usbInterface?.getEndpoint(0)
                    isUsbDacEnabled = true
                    
                    switchToUsbAudio()
                    Toast.makeText(this, "USB DAC已连接", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "USB DAC连接成功: ${device.deviceName}")
                } else {
                    Log.e(TAG, "无法获取USB接口权限")
                    disconnectUsbDac()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "USB DAC连接失败: ${e.message}", e)
            disconnectUsbDac()
        }
    }

    private fun switchToUsbAudio() {
        if (exoPlayer.isPlaying) {
            val pos = exoPlayer.currentPosition
            currentSong?.let { playSong(it) }
            exoPlayer.seekTo(pos)
        }
        Log.d(TAG, "USB音频路由将由系统自动处理")
    }

    fun disconnectUsbDac() {
        usbInterface?.let {
            usbConnection?.releaseInterface(it)
        }
        usbConnection?.close()
        usbConnection = null
        usbInterface = null
        usbEndpoint = null
        usbDevice = null
        isUsbDacEnabled = false
        
        Toast.makeText(this, "USB DAC已断开连接", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "USB DAC已断开连接")
        
        if (exoPlayer.isPlaying) {
            val currentPosition = exoPlayer.currentPosition
            currentSong?.let { playSong(it) }
            exoPlayer.seekTo(currentPosition)
        }
    }
}