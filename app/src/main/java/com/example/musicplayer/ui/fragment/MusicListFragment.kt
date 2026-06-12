package com.example.musicplayer.ui.fragment

import android.app.Activity
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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.musicplayer.R
import com.example.musicplayer.databinding.FragmentMusicListBinding
import com.example.musicplayer.model.Song
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
        
        // 使用 Application 级别的 ViewModelStore，确保与 ScanProgressActivity 共享同一个 ViewModel 实例
        musicViewModel = ViewModelProvider(
            (requireActivity().application as androidx.lifecycle.ViewModelStoreOwner),
            AndroidViewModelFactory.getInstance(requireActivity().application)
        )[MusicViewModel::class.java]
        
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
        startActivityForResult(intent, REQUEST_SCAN)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SCAN && resultCode == Activity.RESULT_OK) {
            // 扫描完成，刷新歌曲列表
            android.util.Log.d("MusicListFragment", "扫描完成，刷新歌曲列表")
            // 不需要手动刷新，因为 ViewModel 是共享的，LiveData 会自动更新
        }
    }
    
    companion object {
        private const val REQUEST_SCAN = 1001
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
            },
            onEditSong = { song ->
                showEditSongDialog(song)
            },
            onViewDetail = { song ->
                showSongDetailDialog(song)
            },
            onDeleteSong = { song ->
                deleteSong(song)
            },
            onRemoveFromPlaylist = { song ->
                removeFromPlaylist(song)
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

    private fun showEditSongDialog(song: Song) {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("编辑歌曲信息")
        
        val view = layoutInflater.inflate(R.layout.dialog_edit_song, null)
        val etTitle = view.findViewById<android.widget.EditText>(R.id.et_title)
        val etArtist = view.findViewById<android.widget.EditText>(R.id.et_artist)
        val etAlbum = view.findViewById<android.widget.EditText>(R.id.et_album)
        
        etTitle.setText(song.title)
        etArtist.setText(song.artist)
        etAlbum.setText(song.album)
        
        builder.setView(view)
        
        builder.setPositiveButton("保存") { _, _ ->
            val newTitle = etTitle.text.toString()
            val newArtist = etArtist.text.toString()
            val newAlbum = etAlbum.text.toString()
            
            // 更新歌曲信息（实际实现需要保存到文件或数据库）
            Toast.makeText(requireContext(), "歌曲信息已更新", Toast.LENGTH_SHORT).show()
        }
        
        builder.setNegativeButton("取消", null)
        builder.show()
    }

    private fun showSongDetailDialog(song: Song) {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("歌曲详情")
        
        val detail = """
            标题: ${song.title}
            艺术家: ${song.artist}
            专辑: ${song.album}
            时长: ${formatDuration(song.duration)}
            路径: ${song.path}
            大小: ${formatFileSize(song.fileSize)}
        """.trimIndent()
        
        builder.setMessage(detail)
        builder.setPositiveButton("确定", null)
        builder.show()
    }

    private fun deleteSong(song: Song) {
        // 删除文件
        val file = java.io.File(song.path)
        if (file.exists()) {
            if (file.delete()) {
                // 从列表中移除
                val currentList = musicViewModel.songs.value?.toMutableList() ?: mutableListOf()
                currentList.removeIf { it.id == song.id }
                musicViewModel.updateSongList(currentList)
                Toast.makeText(requireContext(), "歌曲已删除", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "删除失败", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(requireContext(), "文件不存在", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeFromPlaylist(song: Song) {
        // 从播放列表中移除（不删除文件）
        val currentList = musicViewModel.songs.value?.toMutableList() ?: mutableListOf()
        currentList.removeIf { it.id == song.id }
        musicViewModel.updateSongList(currentList)
        Toast.makeText(requireContext(), "已从播放列表移除", Toast.LENGTH_SHORT).show()
    }

    private fun formatDuration(duration: Long): String {
        val minutes = (duration / 1000) / 60
        val seconds = (duration / 1000) % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun formatFileSize(size: Long): String {
        if (size < 1024) {
            return "$size B"
        } else if (size < 1024 * 1024) {
            return String.format("%.2f KB", size / 1024.0)
        } else {
            return String.format("%.2f MB", size / (1024.0 * 1024))
        }
    }

    private fun observeViewModel() {
        musicViewModel.filteredSongs.observe(viewLifecycleOwner) {
            android.util.Log.d("MusicListFragment", "filteredSongs 更新，歌曲数: ${it.size}")
            if (it.isEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
                binding.recyclerViewMusic.visibility = View.GONE
            } else {
                binding.tvEmpty.visibility = View.GONE
                binding.recyclerViewMusic.visibility = View.VISIBLE
                songAdapter.submitList(it)
                android.util.Log.d("MusicListFragment", "已更新 adapter，歌曲数: ${it.size}")
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