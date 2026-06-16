package com.example.musicplayer.service;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.example.musicplayer.R;
import com.example.musicplayer.model.Song;
import com.example.musicplayer.view.SpectrumView;

public class FloatingWindowManager {

    private Context context;
    private WindowManager windowManager;
    private View floatingView;
    private ImageView ivDiscFloating;
    private SpectrumView spectrumViewFloating;
    private ObjectAnimator discAnimator;
    private boolean isShowing = false;

    public FloatingWindowManager(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    /**
     * 显示悬浮窗
     */
    public void showFloatingWindow(Song currentSong) {
        if (isShowing) {
            return;
        }

        // 创建悬浮窗布局
        floatingView = LayoutInflater.from(context).inflate(R.layout.layout_floating_window, null);
        ivDiscFloating = floatingView.findViewById(R.id.iv_disc_floating);
        spectrumViewFloating = floatingView.findViewById(R.id.spectrum_view_floating);

        // 加载专辑封面
        if (currentSong != null) {
            Glide.with(context)
                    .load(currentSong.getAlbumArtUri())
                    .placeholder(R.drawable.ic_music_placeholder)
                    .error(R.drawable.ic_music_placeholder)
                    .fallback(R.drawable.ic_music_placeholder)
                    .into(ivDiscFloating);
        }

        // 创建旋转动画
        discAnimator = ObjectAnimator.ofFloat(ivDiscFloating, "rotation", 0f, 360f);
        discAnimator.setDuration(20000); // 20秒转一圈
        discAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        discAnimator.setInterpolator(new android.view.animation.LinearInterpolator());

        // 设置悬浮窗参数
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            params.type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        params.format = PixelFormat.TRANSLUCENT;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                       WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                       WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                       WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
        params.gravity = Gravity.CENTER;
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.MATCH_PARENT;

        // 添加悬浮窗
        windowManager.addView(floatingView, params);
        isShowing = true;

        // 启动旋转动画
        discAnimator.start();
    }

    /**
     * 隐藏悬浮窗
     */
    public void hideFloatingWindow() {
        if (!isShowing || floatingView == null) {
            return;
        }

        // 停止旋转动画
        if (discAnimator != null && discAnimator.isRunning()) {
            discAnimator.cancel();
        }

        // 移除悬浮窗
        windowManager.removeView(floatingView);
        floatingView = null;
        ivDiscFloating = null;
        spectrumViewFloating = null;
        isShowing = false;
    }

    /**
     * 设置播放状态
     */
    public void setPlaying(boolean playing) {
        if (spectrumViewFloating != null) {
            spectrumViewFloating.setPlaying(playing);
        }
    }

    /**
     * 更新频谱数据
     */
    public void updateSpectrum(byte[] data) {
        if (spectrumViewFloating != null && isShowing) {
            spectrumViewFloating.updateSpectrum(data);
        }
    }

    /**
     * 更新当前歌曲
     */
    public void updateCurrentSong(Song song) {
        if (ivDiscFloating != null && isShowing && song != null) {
            Glide.with(context)
                    .load(song.getAlbumArtUri())
                    .placeholder(R.drawable.ic_music_placeholder)
                    .error(R.drawable.ic_music_placeholder)
                    .fallback(R.drawable.ic_music_placeholder)
                    .into(ivDiscFloating);
        }
    }

    /**
     * 开始旋转动画
     */
    public void startRotation() {
        if (discAnimator != null && isShowing && !discAnimator.isRunning()) {
            discAnimator.start();
        }
    }

    /**
     * 停止旋转动画
     */
    public void stopRotation() {
        if (discAnimator != null && discAnimator.isRunning()) {
            discAnimator.cancel();
        }
    }

    /**
     * 悬浮窗是否正在显示
     */
    public boolean isShowing() {
        return isShowing;
    }
}