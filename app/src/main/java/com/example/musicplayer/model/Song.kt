package com.example.musicplayer.model

import android.net.Uri

// 音乐数据模型
data class Song(
    // 数据类，表示一首歌曲的信息
    // 歌曲的唯一标识
    val id: Long,
    // 歌曲的标题
    val title: String,
    // 歌曲的艺术家
    val artist: String,
    // 歌曲所属的专辑
    val album: String,
    // 歌曲的时长，单位为毫秒
    val duration: Long,
    // 歌曲文件的路径
    val path: String,
    // 歌曲文件的 URI
    val uri: Uri,
    // 歌曲专辑封面的 URI，默认为 null
    val albumArtUri: Uri? = null

)