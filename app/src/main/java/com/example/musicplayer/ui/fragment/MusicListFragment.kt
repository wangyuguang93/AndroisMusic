package com.example.musicplayer.ui.fragment

import android.content.Intent
import android.os.Bundle
import com.example.musicplayer.service.MusicPlayerService
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.musicplayer.R
import com.example.musicplayer.databinding.FragmentMusicListBinding
import com.example.musicplayer.ui.ScanProgressActivity
import com.example.musicplayer.ui.adapter.SongAdapter
import com.example.musicplayer.viewmodel.MusicViewModel

class MusicListFragment : Fragment() {
    private lateinit var binding: FragmentMusicListBinding
    private lateinit var musicViewModel: MusicViewModel
    private lateinit var songAdapter: SongAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentMusicListBinding.inflate(inflater, container, false)
        setHasOptionsMenu(true)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        musicViewModel = ViewModelProvider(requireActivity())[MusicViewModel::class.java]
        
        setupRecyclerView()
        setupSearchView()
        setupRefresh()
        setupMenu()
        observeViewModel()
    }
    
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.main_menu, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_scan -> {
                startScanActivity()
                true
            }
            R.id.menu_exit -> {
                exitApp()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun setupMenu() {
        binding.ivMenu.setOnClickListener {
            val popup = PopupMenu(requireContext(), binding.ivMenu)
            popup.menuInflater.inflate(R.menu.main_menu, popup.menu)
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.menu_scan -> {
                        startScanActivity()
                        true
                    }
                    R.id.menu_exit -> {
                        exitApp()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }
    
    private fun startScanActivity() {
        val intent = Intent(requireContext(), ScanProgressActivity::class.java)
        startActivity(intent)
    }
    
    private fun exitApp() {
        AlertDialog.Builder(requireContext())
            .setTitle("退出应用")
            .setMessage("确定要退出应用吗？")
            .setPositiveButton("确定") { _, _ ->
                // 停止后台播放服务
                val stopIntent = Intent(requireContext(), MusicPlayerService::class.java)
                requireContext().stopService(stopIntent)
                // 结束应用
                requireActivity().finishAffinity()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setupRefresh() {
        // 下拉刷新：只刷新当前列表显示，不扫描新歌曲
        binding.swipeRefreshLayout.setOnRefreshListener {
            binding.swipeRefreshLayout.isRefreshing = false
        }
    }

    private fun setupSearchView() {
        binding.searchView.setOnQueryTextListener(object : android.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                musicViewModel.searchSongs(query.orEmpty())
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                musicViewModel.searchSongs(newText.orEmpty())
                return true
            }
        })
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(
            onSongClick = { song ->
                musicViewModel.playSong(song, musicViewModel.filteredSongs.value ?: musicViewModel.songs.value ?: emptyList())
            },
            onItemMove = { fromPosition, toPosition ->
                musicViewModel.moveSong(fromPosition, toPosition)
            }
        )
        
        binding.recyclerViewMusic.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = songAdapter
        }
        
        // 创建 ItemTouchHelper 并绑定到 RecyclerView
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,  // 拖拽方向
            0  // 不支持滑动
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = viewHolder.adapterPosition
                val toPosition = target.adapterPosition
                
                if (fromPosition != RecyclerView.NO_POSITION && toPosition != RecyclerView.NO_POSITION) {
                    songAdapter.onItemMoved(fromPosition, toPosition)
                    return true
                }
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // 不处理滑动
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                when (actionState) {
                    ItemTouchHelper.ACTION_STATE_DRAG -> {
                        viewHolder?.itemView?.alpha = 0.5f
                    }
                    ItemTouchHelper.ACTION_STATE_IDLE -> {
                        viewHolder?.itemView?.alpha = 1.0f
                    }
                }
            }
        })
        
        itemTouchHelper.attachToRecyclerView(binding.recyclerViewMusic)
    }

    private fun observeViewModel() {
        musicViewModel.filteredSongs.observe(viewLifecycleOwner) {
            if (it.isEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
                binding.recyclerViewMusic.visibility = View.GONE
            } else {
                binding.tvEmpty.visibility = View.GONE
                binding.recyclerViewMusic.visibility = View.VISIBLE
                songAdapter.submitList(it)
            }
        }

        musicViewModel.currentSong.observe(viewLifecycleOwner) {
            it?.let {
                songAdapter.setCurrentSongId(it.id)
            }
        }

        // 观察加载状态，控制刷新动画
        musicViewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.swipeRefreshLayout.isRefreshing = isLoading
        }
    }
}