package com.example.musicplayer.model

import android.net.Uri

// 音乐数据模型
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val path: String,
    val uri: Uri,
    val albumArtUri: Uri? = null
)