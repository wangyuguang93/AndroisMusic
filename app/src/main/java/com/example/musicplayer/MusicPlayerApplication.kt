package com.example.musicplayer

import android.app.Application
import android.content.Context

class MusicPlayerApplication : Application() {
    companion object {
        lateinit var context: Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        context = applicationContext
    }
}