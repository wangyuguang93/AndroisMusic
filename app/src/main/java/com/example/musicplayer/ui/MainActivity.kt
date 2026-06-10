package com.example.musicplayer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import com.example.musicplayer.R
import com.example.musicplayer.databinding.ActivityMainBinding
import com.example.musicplayer.service.MusicPlayerService
import com.example.musicplayer.ui.fragment.MusicListFragment
import com.example.musicplayer.viewmodel.MusicViewModel

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var musicViewModel: MusicViewModel
    private val requestPermissionCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        musicViewModel = ViewModelProvider(this)[MusicViewModel::class.java]

        if (checkPermission()) {
            initFragment()
        } else {
            requestPermission()
        }

        setupPlaybackControls()
    }

    override fun onResume() {
        super.onResume()
        musicViewModel.refreshPlayerState()
    }

    private fun setupPlaybackControls() {
        binding.btnPlayPause.setOnClickListener {
            musicViewModel.togglePlayPause()
        }

        binding.btnPrev.setOnClickListener {
            musicViewModel.skipToPrevious()
        }

        binding.btnNext.setOnClickListener {
            musicViewModel.skipToNext()
        }

        musicViewModel.isPlaying.observe(this) {
            val iconRes = if (it) {
                R.drawable.ic_pause
            } else {
                R.drawable.ic_play
            }
            binding.btnPlayPause.setImageResource(iconRes)
        }

        musicViewModel.currentSong.observe(this) {
            it?.let {
                binding.tvSongTitle.text = it.title
                binding.tvArtist.text = it.artist
                Glide.with(this)
                    .load(it.albumArtUri)
                    .placeholder(R.drawable.ic_music_placeholder)
                    .into(binding.ivAlbumArt)
            }
        }

        musicViewModel.currentPosition.observe(this) {
            // 只处理有效的 position 值
            if (it >= 0) {
                binding.seekBar.progress = it.toInt()
                binding.tvCurrentTime.text = formatDuration(it)
            }
        }

        musicViewModel.duration.observe(this) {
            // 只处理有效的 duration 值
            if (it >= 0 && it <= 24 * 60 * 60 * 1000) {
                binding.seekBar.max = it.toInt()
                binding.tvTotalTime.text = formatDuration(it)
            } else {
                binding.tvTotalTime.text = "0:00"
            }
        }

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

        binding.btnRepeat.setOnClickListener {
            musicViewModel.toggleLoopMode()
        }

        musicViewModel.loopMode.observe(this) { mode ->
            val iconRes = when (mode) {
                MusicPlayerService.LOOP_MODE_NONE -> R.drawable.ic_repeat_off
                MusicPlayerService.LOOP_MODE_ALL -> R.drawable.ic_repeat
                MusicPlayerService.LOOP_MODE_ONE -> R.drawable.ic_repeat_one
                MusicPlayerService.LOOP_MODE_SHUFFLE -> R.drawable.ic_shuffle
                else -> R.drawable.ic_repeat_off
            }
            binding.btnRepeat.setImageResource(iconRes)
            
            // 顺序播放时显示灰色（未开启循环/随机），其他模式显示白色（已开启特殊播放模式）
            binding.btnRepeat.imageTintList = if (mode == MusicPlayerService.LOOP_MODE_NONE) {
                ContextCompat.getColorStateList(this, R.color.gray)
            } else {
                ContextCompat.getColorStateList(this, R.color.white)
            }
        }
    }

    private fun formatDuration(duration: Long): String {
        // 处理无效值（如 TIME_UNSET 或负数）
        if (duration < 0 || duration > 24 * 60 * 60 * 1000) {  // 最大24小时
            return "0:00"
        }
        val minutes = (duration / 1000 / 60)
        val seconds = (duration / 1000 % 60)
        return "$minutes:${seconds.toString().padStart(2, '0')}"
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
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(binding.container.id, fragment)
            .commit()
    }
}