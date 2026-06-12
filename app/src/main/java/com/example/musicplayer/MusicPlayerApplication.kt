package com.example.musicplayer

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

class MusicPlayerApplication : Application(), ViewModelStoreOwner {
    companion object {
        lateinit var context: Context
            private set
        
        // 全局的 ViewModelStore，用于在 Activity 之间共享 ViewModel
        private val appViewModelStore = ViewModelStore()
        
        fun getAppViewModelStore(): ViewModelStore {
            return appViewModelStore
        }
    }

    override fun onCreate() {
        super.onCreate()
        context = applicationContext
    }
    
    override val viewModelStore: ViewModelStore
        get() = getAppViewModelStore()
}