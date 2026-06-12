package com.example.musicplayer.ui.adapter

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.musicplayer.R
import com.example.musicplayer.databinding.ItemSongBinding
import com.example.musicplayer.model.Song

class SongAdapter(
    private val onSongClick: (Song) -> Unit,
    private val onItemMove: (fromPosition: Int, toPosition: Int) -> Unit,
    private val onEditSong: (Song) -> Unit,
    private val onViewDetail: (Song) -> Unit,
    private val onDeleteSong: (Song) -> Unit,
    private val onRemoveFromPlaylist: (Song) -> Unit
) : ListAdapter<Song, SongAdapter.SongViewHolder>(SongDiffCallback()) {
    
    private var currentSongId: Long = -1
    private var currentList: List<Song> = emptyList()

    inner class SongViewHolder(private val binding: ItemSongBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            // 歌曲点击事件
            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val song = getItem(position)
                    onSongClick(song)
                }
            }

            // 菜单按钮点击事件
            binding.btnMore.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val song = getItem(position)
                    showPopupMenu(binding.btnMore, song)
                }
            }
        }

        private fun showPopupMenu(view: View, song: Song) {
            val popupMenu = PopupMenu(view.context, view)
            popupMenu.menuInflater.inflate(R.menu.song_item_menu, popupMenu.menu)
            
            popupMenu.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.menu_edit -> {
                        onEditSong(song)
                        true
                    }
                    R.id.menu_detail -> {
                        onViewDetail(song)
                        true
                    }
                    R.id.menu_delete -> {
                        showDeleteConfirmDialog(song)
                        true
                    }
                    R.id.menu_remove -> {
                        onRemoveFromPlaylist(song)
                        true
                    }
                    else -> false
                }
            }
            popupMenu.show()
        }

        private fun showDeleteConfirmDialog(song: Song) {
            AlertDialog.Builder(itemView.context)
                .setTitle("删除歌曲")
                .setMessage("确定要删除这首歌曲吗？")
                .setPositiveButton("确定") { _, _ ->
                    onDeleteSong(song)
                }
                .setNegativeButton("取消", null)
                .show()
        }

        fun bind(song: Song, isCurrentSong: Boolean) {
            binding.tvSongTitle.text = song.title
            binding.tvArtist.text = song.artist
            
            // 显示歌曲时长
            binding.tvDuration.text = formatDuration(song.duration)
            
            if (isCurrentSong) {
                binding.root.setBackgroundColor(ContextCompat.getColor(binding.root.context, R.color.playing_pink))
            } else {
                binding.root.setBackgroundColor(ContextCompat.getColor(binding.root.context, R.color.transparent))
            }

            Glide.with(binding.ivAlbumArt.context)
                .load(song.albumArtUri)
                .placeholder(R.drawable.ic_music_placeholder)
                .error(R.drawable.ic_music_placeholder)
                .fallback(R.drawable.ic_music_placeholder)
                .into(binding.ivAlbumArt)
        }

        private fun formatDuration(duration: Long): String {
            val minutes = (duration / 1000) / 60
            val seconds = (duration / 1000) % 60
            return String.format("%02d:%02d", minutes, seconds)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemSongBinding.inflate(inflater, parent, false)
        return SongViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = getItem(position)
        holder.bind(song, song.id == currentSongId)
    }

    fun setCurrentSongId(songId: Long) {
        currentSongId = songId
        notifyDataSetChanged()
    }

    // ItemTouchHelper 调用此方法来移动项目
    fun onItemMoved(fromPosition: Int, toPosition: Int) {
        val newList = currentList.toMutableList()
        if (fromPosition < newList.size && toPosition < newList.size) {
            val movedSong = newList.removeAt(fromPosition)
            newList.add(toPosition, movedSong)
            currentList = newList
            
            // 通知 ViewModel 更新数据源
            onItemMove(fromPosition, toPosition)
        }
    }

    override fun getCurrentList(): List<Song> {
        return currentList
    }

    override fun submitList(list: List<Song>?) {
        super.submitList(list)
        currentList = list ?: emptyList()
        // 强制刷新，确保列表更新
        notifyDataSetChanged()
    }

    private class SongDiffCallback : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem == newItem
        }
    }
}