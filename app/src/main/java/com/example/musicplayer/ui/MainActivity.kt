package com.example.musicplayer.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.example.musicplayer.databinding.ActivityMainBinding
import com.example.musicplayer.ui.fragment.MusicListFragment
import com.example.musicplayer.viewmodel.MusicViewModel
import com.bumptech.glide.Glide
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import com.example.musicplayer.service.MusicPlayerService
import com.example.musicplayer.R

// 主活动类，应用的主界面
@UnstableApi
class MainActivity : AppCompatActivity() {
    // 视图绑定对象
    private lateinit var binding: ActivityMainBinding
    // 音乐视图模型实例
    private lateinit var musicViewModel: MusicViewModel
    // 请求权限的代码
    private val requestPermissionCode = 1001
    // 音乐播放器服务实例
    private var musicService: MusicPlayerService? = null
    // 服务连接回调对象
    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        // 服务连接成功时调用
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            // 获取服务的本地绑定器
            val binder = service as MusicPlayerService.LocalBinder
            musicService = binder.service
            updateLoopIcon()
        }

        // 服务断开连接时调用
        override fun onServiceDisconnected(arg0: ComponentName) {
            musicService = null
        }
    }

    // 活动创建时调用
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 获取音乐视图模型实例
        musicViewModel = ViewModelProvider(this)[MusicViewModel::class.java]
        // 创建启动音乐服务的意图
        Intent(this, MusicPlayerService::class.java).also {
            // 绑定服务
            bindService(it, serviceConnection, BIND_AUTO_CREATE)
        }

        // 检查权限
        if (checkPermission()) {
            initFragment()
        } else {
            requestPermission()
        }

        // 设置播放控制按钮点击事件
        setupPlaybackControls()
    }



    private fun setupPlaybackControls() {
        // 播放/暂停按钮
        binding.btnPlayPause.setOnClickListener {
            musicViewModel.togglePlayPause()
        }

        // 上一曲按钮
        binding.btnPrev.setOnClickListener {
            musicViewModel.skipToPrevious()
        }

        // 下一曲按钮
        binding.btnNext.setOnClickListener {
            musicViewModel.skipToNext()
        }

        // 观察播放状态更新按钮图标
        musicViewModel.isPlaying.observe(this) {
            // 根据播放状态选择图标资源
            val iconRes = if (it) {
                R.drawable.ic_pause
            } else {
                R.drawable.ic_play
            }
            binding.btnPlayPause.setImageResource(iconRes)
        }

        // 观察当前歌曲更新标题和艺术家
        musicViewModel.currentSong.observe(this) {
            it?.let {
                binding.tvSongTitle.text = it.title
                binding.tvArtist.text = it.artist
                // 使用Glide加载专辑封面
                Glide.with(this)
                    .load(it.albumArtUri ?: R.drawable.ic_music_placeholder)
                    .placeholder(R.drawable.ic_music_placeholder)
                    .into(binding.ivAlbumArt)
            }
        }

        // 观察进度更新
        musicViewModel.currentPosition.observe(this) {
            binding.seekBar.progress = it.toInt()
            binding.tvCurrentTime.text = formatDuration(it)
        }

        // 观察总时长更新
        musicViewModel.duration.observe(this) {
            binding.seekBar.max = it.toInt()
            binding.tvTotalTime.text = formatDuration(it)
        }

        // 进度条拖动事件
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            // 当进度条进度改变时调用
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvCurrentTime.text = formatDuration(progress.toLong())
                }
            }

            // 开始拖动进度条时调用
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            // 停止拖动进度条时调用
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.progress?.let {
                    musicViewModel.seekTo(it.toLong())
                }
            }
        })
        // 添加循环按钮点击事件
        binding.btnRepeat.setOnClickListener {
            musicService?.toggleLoopMode()
            updateLoopIcon()
        }
    }

    // 格式化时长（毫秒转分:秒）
    private fun formatDuration(duration: Long): String {
        // 计算分钟数
        val minutes = (duration / 1000 / 60).toString()
        // 计算秒数并补零
        val seconds = (duration / 1000 % 60).toString().padStart(2, '0')
        return "$minutes:$seconds"
    }
    private fun checkPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermission() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        ActivityCompat.requestPermissions(
            this,
            permissions,
            requestPermissionCode
        )
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestPermissionCode) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initFragment()
            } else {
                Toast.makeText(
                    this,
                    "需要存储权限才能扫描音乐文件",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }


    private fun initFragment() {

        replaceFragment(MusicListFragment())
        startMusicService()
        musicViewModel.loadMusic() // 权限获取成功后加载音乐
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(binding.container.id, fragment)
            .commit()
    }

    private fun startMusicService() {
        // 创建启动音乐服务的意图
        val intent = Intent(this, MusicPlayerService::class.java)
        startService(intent)
    }

    // 添加循环图标更新方法
    private fun updateLoopIcon() {
        // 根据当前循环模式选择图标资源
        val iconRes = when (musicService?.loopMode) {

            MusicPlayerService.LOOP_MODE_ALL -> R.drawable.ic_repeat
            MusicPlayerService.LOOP_MODE_ONE -> R.drawable.ic_repeat_one

            else -> R.drawable.ic_repeat
        }

        // 根据循环模式设置图标透明度
        val alpha = if (musicService?.loopMode == MusicPlayerService.LOOP_MODE_NONE) 0.5f else 1.0f
        binding.btnRepeat.setImageResource(iconRes)
        binding.btnRepeat.alpha = alpha
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(serviceConnection)
    }
}