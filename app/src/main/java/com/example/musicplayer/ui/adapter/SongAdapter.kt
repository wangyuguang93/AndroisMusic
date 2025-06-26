package com.example.musicplayer.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.musicplayer.databinding.ItemSongBinding
import com.example.musicplayer.model.Song

class SongAdapter(private val onSongClick: (Song) -> Unit) : ListAdapter<Song, SongAdapter.SongViewHolder>(SongDiffCallback()) {
    private var currentSongId: Long = -1

    inner class SongViewHolder(private val binding: ItemSongBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val song = getItem(position)
                    onSongClick(song)
                }
            }
        }

        fun bind(song: Song, isCurrentSong: Boolean) {
            
            
            binding.tvSongTitle.text = song.title
            binding.tvArtist.text = song.artist
            binding.root.isSelected = isCurrentSong

            // 使用Glide加载专辑封面
            Glide.with(binding.ivAlbumArt.context)
                .load(song.albumArtUri ?: com.example.musicplayer.R.drawable.ic_music_placeholder)
                .placeholder(com.example.musicplayer.R.drawable.ic_music_placeholder)
                .error(com.example.musicplayer.R.drawable.ic_music_placeholder)
                .into(binding.ivAlbumArt);
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

    private class SongDiffCallback : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem == newItem
        }
    }
}