package com.example.musicplayer.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.musicplayer.R
import com.example.musicplayer.model.Song
import java.util.concurrent.TimeUnit


class MusicAdapter(
    private val songList: List<Song>,
    private val onSongClick: (Song) -> Unit
) : RecyclerView.Adapter<MusicAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivAlbumArt: ImageView = itemView.findViewById(R.id.iv_album_art)
        val tvTitle: TextView = itemView.findViewById(R.id.tv_song_title)
        val tvArtist: TextView = itemView.findViewById(R.id.tv_artist)
        val tvDuration: TextView = itemView.findViewById(R.id.tv_duration)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = songList[position]
        holder.tvTitle.text = song.title
        holder.tvArtist.text = song.artist
        holder.tvDuration.text = formatDuration(song.duration)

        // 使用Glide加载专辑封面
        Glide.with(holder.itemView.context)
            .load(song.albumArtUri ?: R.drawable.ic_music_placeholder)
            .placeholder(R.drawable.ic_music_placeholder)
            .error(R.drawable.ic_music_placeholder)
            .into(holder.ivAlbumArt);

        // 设置点击事件
        holder.itemView.setOnClickListener {
            onSongClick(song)
        }
    }

    override fun getItemCount(): Int = songList.size

    // 格式化时长（毫秒转分:秒）
    private fun formatDuration(durationMs: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}