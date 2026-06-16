package com.example.musicplayer.ui;

import android.animation.ObjectAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.IBinder;
import android.view.animation.LinearInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.bumptech.glide.Glide;
import com.example.musicplayer.R;
import com.example.musicplayer.service.MusicPlayerService;
import com.example.musicplayer.view.SpectrumView;
import com.example.musicplayer.viewmodel.MusicViewModel;

public class PlayDetailActivity extends AppCompatActivity {

    private ImageView ivDisc;
    private SpectrumView spectrumView;
    private SeekBar seekbarBass;
    private SeekBar seekbarTreble;
    private TextView tvBassValue;
    private TextView tvTrebleValue;
    private TextView tvSongTitle;
    private TextView tvSongArtist;
    private ImageButton btnPlayPause;
    private ImageButton btnBack;
    private ImageButton btnPrevious;
    private ImageButton btnNext;

    private MusicViewModel musicViewModel;
    private ObjectAnimator discAnimator;
    private boolean isBound = false;
    private MusicPlayerService musicPlayerService;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MusicPlayerService.LocalBinder binder = (MusicPlayerService.LocalBinder) service;
            musicPlayerService = binder.getService();
            isBound = true;
            
            // 初始化 EQ 均衡器
            setupEqualizer();
            
            // 开始频谱更新
            startSpectrumUpdates();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            musicPlayerService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_play_detail);

        musicViewModel = new ViewModelProvider(
                (androidx.lifecycle.ViewModelStoreOwner) getApplication(),
                ViewModelProvider.AndroidViewModelFactory.getInstance(getApplication())
        ).get(MusicViewModel.class);

        initViews();
        setupDiscAnimation();
        setupPlaybackControls();
        setupEqualizerControls();
        
        // 绑定服务
        bindMusicService();
    }

    private void initViews() {
        ivDisc = findViewById(R.id.iv_disc);
        spectrumView = findViewById(R.id.spectrum_view);
        seekbarBass = findViewById(R.id.seekbar_bass);
        seekbarTreble = findViewById(R.id.seekbar_treble);
        tvBassValue = findViewById(R.id.tv_bass_value);
        tvTrebleValue = findViewById(R.id.tv_treble_value);
        tvSongTitle = findViewById(R.id.tv_song_title);
        tvSongArtist = findViewById(R.id.tv_song_artist);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnBack = findViewById(R.id.btn_back);
        btnPrevious = findViewById(R.id.btn_previous);
        btnNext = findViewById(R.id.btn_next);

        btnBack.setOnClickListener(v -> finish());
    }

    private void setupDiscAnimation() {
        // 创建旋转动画（360度无限循环）
        discAnimator = ObjectAnimator.ofFloat(ivDisc, "rotation", 0f, 360f);
        discAnimator.setDuration(20000); // 20秒转一圈
        discAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        discAnimator.setInterpolator(new LinearInterpolator()); // 匀速旋转
    }

    private void setupPlaybackControls() {
        // 播放/暂停按钮
        btnPlayPause.setOnClickListener(v -> musicViewModel.togglePlayPause());

        // 上一首按钮
        btnPrevious.setOnClickListener(v -> musicViewModel.skipToPrevious());

        // 下一首按钮
        btnNext.setOnClickListener(v -> musicViewModel.skipToNext());

        musicViewModel.isPlaying().observe(this, isPlaying -> {
            // 更新播放/暂停按钮图标
            int iconRes = isPlaying ? R.drawable.ic_pause : R.drawable.ic_play;
            btnPlayPause.setImageResource(iconRes);

            // 控制唱片旋转动画
            if (isPlaying) {
                startDiscRotation();
            } else {
                stopDiscRotation();
            }

            // 控制频谱播放状态
            if (spectrumView != null) {
                spectrumView.setPlaying(isPlaying);
            }
        });

        musicViewModel.getCurrentSong().observe(this, song -> {
            if (song != null) {
                // 更新歌曲信息
                tvSongTitle.setText(song.getTitle());
                tvSongArtist.setText(song.getArtist());
                
                // 加载专辑封面
                Glide.with(this)
                        .load(song.getAlbumArtUri())
                        .placeholder(R.drawable.ic_music_placeholder)
                        .error(R.drawable.ic_music_placeholder)
                        .fallback(R.drawable.ic_music_placeholder)
                        .into(ivDisc);
            }
        });
    }

    private void startDiscRotation() {
        if (discAnimator != null && !discAnimator.isRunning()) {
            discAnimator.start();
        }
    }

    private void stopDiscRotation() {
        if (discAnimator != null && discAnimator.isRunning()) {
            discAnimator.cancel();
        }
    }

    private void bindMusicService() {
        Intent intent = new Intent(this, MusicPlayerService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void setupEqualizer() {
        if (musicPlayerService != null) {
            musicPlayerService.setupEqualizer();
        }
    }

    private void startSpectrumUpdates() {
        if (musicPlayerService != null) {
            // 初始化频谱可视化（会自动选择系统Visualizer或AudioAnalyzer）
            musicPlayerService.setupVisualizer();
            
            // 设置频谱数据监听器
            musicPlayerService.setSpectrumListener(new MusicPlayerService.SpectrumListener() {
                @Override
                public void onSpectrumData(byte[] data) {
                    if (spectrumView != null && data != null) {
                        runOnUiThread(() -> {
                            spectrumView.updateSpectrum(data);
                        });
                    }
                }
            });
        }
    }

    private void setupEqualizerControls() {
        // 低音调节
        seekbarBass.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    // 将进度映射到 -15 到 +15 的范围
                    short bassGain = (short) ((progress - 50) * 0.3); // -15 到 +15
                    tvBassValue.setText(String.valueOf(bassGain));
                    
                    if (isBound && musicPlayerService != null) {
                        musicPlayerService.setBassGain(bassGain);
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 高音调节
        seekbarTreble.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    // 将进度映射到 -15 到 +15 的范围
                    short trebleGain = (short) ((progress - 50) * 0.3); // -15 到 +15
                    tvTrebleValue.setText(String.valueOf(trebleGain));
                    
                    if (isBound && musicPlayerService != null) {
                        musicPlayerService.setTrebleGain(trebleGain);
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // 停止动画
        stopDiscRotation();
        
        // 解绑服务
        if (isBound) {
            if (musicPlayerService != null) {
                musicPlayerService.setSpectrumListener(null);
            }
            unbindService(serviceConnection);
            isBound = false;
        }
    }
}
