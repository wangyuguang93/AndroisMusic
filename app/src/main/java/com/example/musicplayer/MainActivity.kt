package com.example.musicplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.example.musicplayer.databinding.ActivityMainBinding
import com.example.musicplayer.ui.fragment.MusicListFragment
import com.example.musicplayer.viewmodel.MusicViewModel
import com.bumptech.glide.Glide
import androidx.fragment.app.Fragment

import android.widget.SeekBar
import com.example.musicplayer.service.MusicPlayerService

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var musicViewModel: MusicViewModel
    private val requestPermissionCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        musicViewModel = ViewModelProvider(this)[MusicViewModel::class.java]

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
                    .load(it.albumArtUri)
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
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvCurrentTime.text = formatDuration(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.progress?.let {
                    musicViewModel.seekTo(it.toLong())
                }
            }
        })
    }

    // 格式化时长（毫秒转分:秒）
    private fun formatDuration(duration: Long): String {
        val minutes = (duration / 1000 / 60).toString()
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
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(binding.container.id, fragment)
            .commit()
    }

    private fun startMusicService() {
        val intent = Intent(this, MusicPlayerService::class.java)
        startService(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

}