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
import android.media.audiofx.Equalizer
import android.media.audiofx.Visualizer
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
import android.content.BroadcastReceiver
import android.view.animation.LinearInterpolator
import android.animation.ObjectAnimator

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

    // 频谱数据监听器接口
    interface SpectrumListener {
        fun onSpectrumData(data: ByteArray?)
    }

    private var playerStateListener: PlayerStateListener? = null
    private val playerStateListeners = mutableListOf<PlayerStateListener>()
    private val spectrumListeners = mutableListOf<SpectrumListener>()

    fun addPlayerStateListener(listener: PlayerStateListener) {
        if (!playerStateListeners.contains(listener)) {
            playerStateListeners.add(listener)
        }
    }

    fun removePlayerStateListener(listener: PlayerStateListener) {
        playerStateListeners.remove(listener)
    }

    private fun notifyCurrentSongChanged(song: Song?) {
        playerStateListeners.forEach { it.onCurrentSongChanged(song) }
    }

    private fun notifyPlaybackStateChanged(isPlaying: Boolean) {
        playerStateListeners.forEach { it.onPlaybackStateChanged(isPlaying) }
    }

    private fun notifyLoopModeChanged(mode: Int) {
        playerStateListeners.forEach { it.onLoopModeChanged(mode) }
    }

    private fun notifyPositionChanged(position: Long, duration: Long) {
        playerStateListeners.forEach { it.onPositionChanged(position, duration) }
    }

    fun addSpectrumListener(listener: SpectrumListener) {
        if (!spectrumListeners.contains(listener)) {
            spectrumListeners.add(listener)
            Log.d(TAG, "添加频谱监听器，当前监听器数量: ${spectrumListeners.size}")
        }
    }

    fun removeSpectrumListener(listener: SpectrumListener) {
        spectrumListeners.remove(listener)
        Log.d(TAG, "移除频谱监听器，当前监听器数量: ${spectrumListeners.size}")
    }

    fun setSpectrumListener(listener: SpectrumListener?) {
        // 兼容旧API：清空所有监听器并添加新的
        spectrumListeners.clear()
        listener?.let {
            spectrumListeners.add(it)
            // 添加监听器后，如果Visualizer还没初始化，尝试初始化
            if (!isVisualizerEnabled && ::exoPlayer.isInitialized) {
                setupVisualizer()
            }
        }
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

    // EQ 均衡器和频谱相关变量
    private var equalizer: Equalizer? = null
    private var isEqualizerEnabled = false
    private var visualizer: Visualizer? = null
    private var isVisualizerEnabled = false

    // 锁屏悬浮窗相关变量
    private var floatingWindowManager: FloatingWindowManager? = null
    private var screenStateReceiver: BroadcastReceiver? = null
    private var isScreenOn = true

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
        initializeFloatingWindow()
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
                // 切换歌曲时重置播放进度为 0
                exoPlayer.seekTo(0)
                // 使用歌曲对象的时长属性，而不是 exoPlayer.duration（可能还未准备好）
                notifyCurrentSongChanged(currentSong)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                notifyPlaybackStateChanged(exoPlayer.isPlaying)
                
                if (playbackState == Player.STATE_READY) {
                    // 播放器准备好后初始化频谱可视化（使用系统Visualizer从播放音频获取数据）
                    setupVisualizer()
                    // 此时更新当前歌曲信息，确保时长正确
                    notifyCurrentSongChanged(currentSong)
                }
                
                if (playbackState == Player.STATE_ENDED) {
                    // 播放结束，处理循环模式
                    if (loopMode == LOOP_MODE_NONE) {
                        // 非循环模式，停止播放
                        stopSelf()
                    } else if (loopMode == LOOP_MODE_ALL) {
                        // 列表循环，从头播放
                        exoPlayer.seekTo(0)
                        exoPlayer.play()
                    }
                    // LOOP_MODE_ONE 模式下 ExoPlayer 会自动循环
                }
            }

            override fun onPositionDiscontinuity(reason: Int) {
                super.onPositionDiscontinuity(reason)
                notifyCurrentSongChanged(currentSong)
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
        
        // 释放 EQ 和频谱资源
        releaseEqualizer()
        releaseVisualizer()
        
        // 释放悬浮窗和锁屏检测资源
        releaseFloatingWindow()
        
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
        android.util.Log.d(TAG, "setMediaItems() 被调用，歌曲数: ${songs.size}, 起始索引: $startIndex")
        
        // 优化：检查歌曲列表是否已经相同，避免不必要的重新设置
        if (songList.isNotEmpty() && songs.size == songList.size) {
            val isSameList = songs.zip(songList).all { (new, old) -> new.id == old.id }
            if (isSameList) {
                android.util.Log.d(TAG, "歌曲列表未变化，跳过重新设置媒体列表")
                // 只更新当前位置（如果需要）
                if (startIndex >= 0 && startIndex < songs.size && startIndex != currentPosition) {
                    currentPosition = startIndex
                    currentSong = songs[startIndex]
                    exoPlayer.seekTo(startIndex, 0)
                    android.util.Log.d(TAG, "仅更新当前索引到: $startIndex")
                }
                return
            }
        }
        
        // 保存当前播放状态
        val wasPlaying = exoPlayer.isPlaying
        val currentPlaybackPosition = exoPlayer.currentPosition
        android.util.Log.d(TAG, "当前播放状态: isPlaying=$wasPlaying, position=$currentPlaybackPosition")
        
        songList = songs
        android.util.Log.d(TAG, "songList 已更新，当前大小: ${songList.size}")
        
        val mediaItems = songs.map { MediaItem.fromUri(it.uri) }
        android.util.Log.d(TAG, "创建 ${mediaItems.size} 个 MediaItem")
        
        exoPlayer.setMediaItems(mediaItems)
        android.util.Log.d(TAG, "ExoPlayer 媒体列表已设置")
        
        exoPlayer.prepare()
        android.util.Log.d(TAG, "ExoPlayer 已准备")
        
        if (startIndex >= 0 && startIndex < songs.size) {
            exoPlayer.seekTo(startIndex, currentPlaybackPosition)
            android.util.Log.d(TAG, "ExoPlayer 已跳转到索引 $startIndex, 位置 $currentPlaybackPosition")
            
            currentSong = songs[startIndex]
            currentPosition = startIndex
            android.util.Log.d(TAG, "当前歌曲已更新: ${currentSong?.title} (索引: $currentPosition)")
            
            // 如果之前在播放，恢复播放
            if (wasPlaying) {
                exoPlayer.play()
                android.util.Log.d(TAG, "恢复播放")
                // 主动通知播放状态更新
                notifyPlaybackStateChanged(true)
            }
        } else {
            android.util.Log.e(TAG, "起始索引 $startIndex 超出歌曲列表范围 ${songs.size}")
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

    // ==================== EQ 均衡器相关方法 ====================

    /**
     * 初始化均衡器
     */
    fun setupEqualizer() {
        // 如果已经初始化，不再重复初始化
        if (isEqualizerEnabled) {
            Log.d(TAG, "EQ均衡器已经初始化，跳过")
            return
        }
        
        try {
            // 创建新的均衡器
            val audioSessionId = exoPlayer.audioSessionId
            if (audioSessionId != 0) {
                equalizer = Equalizer(0, audioSessionId)
                equalizer?.enabled = true
                isEqualizerEnabled = true
                Log.d(TAG, "EQ均衡器初始化成功，音频会话ID: $audioSessionId")
            } else {
                Log.e(TAG, "音频会话ID为0，无法初始化均衡器")
            }
        } catch (e: Exception) {
            Log.e(TAG, "EQ均衡器初始化失败: ${e.message}", e)
        }
    }

    /**
     * 设置低音增益
     * @param gain 增益值（-15 到 +15）
     */
    fun setBassGain(gain: Short) {
        try {
            equalizer?.let { eq ->
                if (eq.numberOfBands.toInt() > 0) {
                    // 低音通常对应第一个频段（最低频率）
                    val bandLevel = (gain * 100).toShort() // Equalizer使用毫贝尔，1dB = 100mB
                    eq.setBandLevel(0.toShort(), bandLevel)
                    Log.d(TAG, "设置低音增益: $gain dB")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "设置低音增益失败: ${e.message}", e)
        }
    }

    /**
     * 设置高音增益
     * @param gain 增益值（-15 到 +15）
     */
    fun setTrebleGain(gain: Short) {
        try {
            equalizer?.let { eq ->
                val numBands = eq.numberOfBands.toInt()
                if (numBands > 0) {
                    // 高音通常对应最后一个频段（最高频率）
                    val bandLevel = (gain * 100).toShort() // Equalizer使用毫贝尔，1dB = 100mB
                    eq.setBandLevel((numBands - 1).toShort(), bandLevel)
                    Log.d(TAG, "设置高音增益: $gain dB")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "设置高音增益失败: ${e.message}", e)
        }
    }

    /**
     * 释放均衡器资源
     */
    private fun releaseEqualizer() {
        equalizer?.release()
        equalizer = null
        isEqualizerEnabled = false
        Log.d(TAG, "EQ均衡器已释放")
    }

    // ==================== 频谱可视化相关方法 ====================

    /**
     * 初始化频谱可视化（从正在播放的音频流获取数据）
     */
    fun setupVisualizer() {
        // 如果已经初始化，不再重复初始化
        if (isVisualizerEnabled) {
            Log.d(TAG, "Visualizer已经初始化，跳过, 监听器数量: ${spectrumListeners.size}")
            return
        }
        
        // 使用系统 Visualizer API 从正在播放的音频会话获取频谱数据
        try {
            val audioSessionId = exoPlayer.audioSessionId
            Log.d(TAG, "初始化系统Visualizer，音频会话ID: $audioSessionId, isPlaying: ${exoPlayer.isPlaying}")
            
            if (audioSessionId == 0) {
                Log.e(TAG, "音频会话ID为0，无法初始化Visualizer")
                return
            }

            // 先释放旧的 Visualizer（如果存在）
            if (visualizer != null) {
                releaseVisualizer()
            }

            visualizer = Visualizer(audioSessionId).apply {
                // 设置捕获大小为最大值，以获取更丰富的频谱数据
                val captureSizes = Visualizer.getCaptureSizeRange()
                captureSize = captureSizes[1] // 使用最大捕获大小
                Log.d(TAG, "Visualizer捕获大小: $captureSize (范围: ${captureSizes[0]}-${captureSizes[1]})")
                
                // 设置数据捕获监听器
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int
                        ) {
                            // 不需要波形数据
                        }

                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int
                        ) {
                            // FFT数据格式：偶数索引是实部，奇数索引是虚部
                            // 直接将原始FFT数据发送给所有监听器
                            fft?.let {
                                // 添加日志：验证FFT数据长度和内容（只在有监听器时）
                                if (spectrumListeners.isNotEmpty() && it.size >= 4) {
                                    // 计算首个频率分量的幅度
                                    val real0 = (it[2].toInt() and 0xFF) - 128
                                    val imag0 = (it[3].toInt() and 0xFF) - 128
                                    val mag0 = kotlin.math.sqrt((real0 * real0 + imag0 * imag0).toDouble())
                                    
                                    Log.v(TAG, "Visualizer FFT: len=${it.size}, mag=$mag0, playing=${exoPlayer.isPlaying}")
                                }
                                
                                // 发送给所有频谱监听器
                                spectrumListeners.forEach { listener ->
                                    listener.onSpectrumData(it)
                                }
                                // 同时发送给悬浮窗管理器
                                floatingWindowManager?.updateSpectrum(it)
                            }
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2,  // 更新频率
                    false,  // 不捕获波形
                    true    // 捕获FFT数据
                )
                
                // 启用Visualizer
                enabled = true
                isVisualizerEnabled = true
                Log.d(TAG, "系统Visualizer初始化成功，数据来自正在播放的音频流")
            }

        } catch (securityException: SecurityException) {
            Log.e(TAG, "Visualizer权限被拒绝: ${securityException.message}")
            // 如果没有权限，不显示频谱或使用模拟数据
        } catch (exception: Exception) {
            Log.e(TAG, "Visualizer初始化失败: ${exception.message}", exception)
        }
    }

    /**
     * 释放频谱可视化资源
     */
    private fun releaseVisualizer() {
        visualizer?.enabled = false
        visualizer?.release()
        visualizer = null
        isVisualizerEnabled = false
        Log.d(TAG, "频谱可视化已释放")
    }

    // ==================== 锁屏悬浮窗相关方法 ====================

    /**
     * 初始化悬浮窗和锁屏检测
     */
    private fun initializeFloatingWindow() {
        floatingWindowManager = FloatingWindowManager(this)
        
        // 注册屏幕状态监听器
        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        isScreenOn = false
                        Log.d(TAG, "屏幕关闭")
                        // 如果正在播放，显示悬浮窗
                        if (exoPlayer.isPlaying) {
                            showFloatingWindow()
                        }
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        isScreenOn = true
                        Log.d(TAG, "屏幕打开")
                        // 隐藏悬浮窗
                        hideFloatingWindow()
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        Log.d(TAG, "用户解锁")
                        // 隐藏悬浮窗
                        hideFloatingWindow()
                    }
                }
            }
        }
        
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        filter.addAction(Intent.ACTION_SCREEN_ON)
        filter.addAction(Intent.ACTION_USER_PRESENT)
        registerReceiver(screenStateReceiver, filter)
        
        Log.d(TAG, "锁屏悬浮窗初始化完成")
    }

    /**
     * 显示悬浮窗
     */
    private fun showFloatingWindow() {
        try {
            floatingWindowManager?.showFloatingWindow(currentSong)
            Log.d(TAG, "悬浮窗已显示")
        } catch (e: Exception) {
            Log.e(TAG, "显示悬浮窗失败: ${e.message}", e)
        }
    }

    /**
     * 隐藏悬浮窗
     */
    private fun hideFloatingWindow() {
        try {
            floatingWindowManager?.hideFloatingWindow()
            Log.d(TAG, "悬浮窗已隐藏")
        } catch (e: Exception) {
            Log.e(TAG, "隐藏悬浮窗失败: ${e.message}", e)
        }
    }

    /**
     * 释放悬浮窗和锁屏检测资源
     */
    private fun releaseFloatingWindow() {
        // 隐藏悬浮窗
        hideFloatingWindow()
        
        // 注销屏幕状态监听器
        screenStateReceiver?.let {
            unregisterReceiver(it)
            screenStateReceiver = null
        }
        
        floatingWindowManager = null
        Log.d(TAG, "锁屏悬浮窗资源已释放")
    }
}