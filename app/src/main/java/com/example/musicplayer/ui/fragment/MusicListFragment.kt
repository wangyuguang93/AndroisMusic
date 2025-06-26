package com.example.musicplayer.ui.fragment

import android.widget.SearchView
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.musicplayer.databinding.FragmentMusicListBinding
import com.example.musicplayer.model.Song
import com.example.musicplayer.ui.adapter.SongAdapter
import com.example.musicplayer.viewmodel.MusicViewModel

// 音乐列表片段类，用于显示音乐列表
class MusicListFragment : Fragment() {
    // 视图绑定对象
    private lateinit var binding: FragmentMusicListBinding
    // 音乐视图模型实例
    private lateinit var musicViewModel: MusicViewModel
    // 歌曲适配器实例
    private lateinit var songAdapter: SongAdapter

    // 创建视图时调用
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentMusicListBinding.inflate(inflater, container, false)
        return binding.root
    }

    // 视图创建完成后调用
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // 获取音乐视图模型实例
        musicViewModel = ViewModelProvider(requireActivity())[MusicViewModel::class.java]
        setupRecyclerView()
        observeViewModel()
        setupSearchView()
    }

    private fun setupSearchView() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let {
                    musicViewModel.searchSongs(it)
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                newText?.let {
                    musicViewModel.searchSongs(it)
                }
                return true
            }
        })
    }

    // 设置 RecyclerView
    private fun setupRecyclerView() {
        songAdapter = SongAdapter {
            musicViewModel.playSong(it, musicViewModel.songs.value ?: emptyList())
        }
        binding.recyclerViewMusic.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = songAdapter
        }
    }

    // 观察视图模型的数据变化
    private fun observeViewModel() {
        musicViewModel.songs.observe(viewLifecycleOwner) {
            if (it.isEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
                binding.recyclerViewMusic.visibility = View.GONE
            } else {
                binding.tvEmpty.visibility = View.GONE
                binding.recyclerViewMusic.visibility = View.VISIBLE
                songAdapter.submitList(it)
            }
        }

        musicViewModel.isLoading.observe(viewLifecycleOwner) {
            // Removed progressBar reference as it doesn't exist in layout
        }

        musicViewModel.currentSong.observe(viewLifecycleOwner) {
            it?.let {
                songAdapter.setCurrentSongId(it.id)
            }
        }

        musicViewModel.searchResults.observe(viewLifecycleOwner) {
            if (it.isEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
                binding.recyclerViewMusic.visibility = View.GONE
            } else {
                binding.tvEmpty.visibility = View.GONE
                binding.recyclerViewMusic.visibility = View.VISIBLE
                songAdapter.submitList(it)
            }
        }
    }
}