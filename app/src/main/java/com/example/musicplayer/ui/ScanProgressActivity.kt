package com.example.musicplayer.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory
import com.example.musicplayer.databinding.ActivityScanProgressBinding
import com.example.musicplayer.viewmodel.MusicViewModel

class ScanProgressActivity : AppCompatActivity() {
    private lateinit var binding: ActivityScanProgressBinding
    private lateinit var musicViewModel: MusicViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanProgressBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 使用 Application 级别的 ViewModelStore，确保与 MainActivity 共享同一个 ViewModel 实例
        musicViewModel = ViewModelProvider(
            (application as androidx.lifecycle.ViewModelStoreOwner),
            AndroidViewModelFactory.getInstance(application)
        )[MusicViewModel::class.java]

        setupListeners()
        observeViewModel()

        // 开始扫描（只使用文件系统遍历，最多 5 层）
        musicViewModel.scanFromMenu()
    }

    private fun setupListeners() {
        // 返回按钮
        binding.ivBack.setOnClickListener {
            finish()
        }

        // 返回主界面按钮
        binding.btnBack.setOnClickListener {
            // 扫描完成后，设置返回结果通知主界面刷新
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun observeViewModel() {
        // 观察扫描进度
        musicViewModel.scanProgress.observe(this) { progress ->
            binding.progressBar.progress = progress
            binding.tvProgress.text = "$progress%"
        }

        // 观察当前扫描目录
        musicViewModel.scanCurrentDir.observe(this) { currentDir ->
            binding.tvCurrentDir.text = currentDir
        }

        // 观察加载状态
        musicViewModel.isLoading.observe(this) { isLoading ->
            if (!isLoading) {
                // 扫描完成
                binding.tvStatus.text = "扫描完成!"
                binding.btnBack.visibility = View.VISIBLE
            }
        }

        // 观察歌曲列表
        musicViewModel.songs.observe(this) { songs ->
            binding.tvFoundCount.text = "已发现：${songs.size} 首歌曲"
        }
    }
}
