package com.example.musicplayer.service;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

/**
 * 使用 AudioRecord 采集音频数据并进行 FFT 分析
 * 注意：由于某些设备不支持系统 Visualizer API，这是一个备选方案
 * 采集的是麦克风输入，不是直接从播放的音频流获取
 */
public class AudioAnalyzer {

    private static final String TAG = "AudioAnalyzer";
    
    // 音频配置
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    
    private AudioRecord audioRecord;
    private HandlerThread workerThread;
    private Handler workerHandler;
    private boolean isRecording = false;
    private OnAudioDataListener listener;
    
    // FFT相关
    private int fftSize = 256;
    private float[] window;
    
    /**
     * 音频数据监听器
     */
    public interface OnAudioDataListener {
        void onFftData(byte[] fftData);
    }
    
    public AudioAnalyzer() {
        // 初始化汉宁窗
        window = new float[fftSize];
        for (int i = 0; i < fftSize; i++) {
            window[i] = (float) (0.5 * (1 - Math.cos(2 * Math.PI * i / (fftSize - 1))));
        }
    }
    
    /**
     * 设置监听器
     */
    public void setOnAudioDataListener(OnAudioDataListener listener) {
        this.listener = listener;
    }
    
    /**
     * 开始采集
     */
    public void start() {
        if (isRecording) {
            return;
        }
        
        try {
            int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            if (bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "音频配置参数无效");
                return;
            }
            
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            );
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失败");
                return;
            }
            
            isRecording = true;
            
            // 启动工作线程
            workerThread = new HandlerThread("AudioAnalyzerWorker");
            workerThread.start();
            workerHandler = new Handler(workerThread.getLooper());
            
            // 开始采集
            audioRecord.startRecording();
            processAudioData();
            
            Log.d(TAG, "AudioAnalyzer 启动成功（麦克风采集模式）");
            
        } catch (SecurityException e) {
            Log.e(TAG, "缺少录音权限", e);
        } catch (Exception e) {
            Log.e(TAG, "启动失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 停止采集
     */
    public void stop() {
        isRecording = false;
        
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "停止失败: " + e.getMessage());
            }
            audioRecord = null;
        }
        
        if (workerThread != null) {
            workerThread.quitSafely();
            try {
                workerThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            workerThread = null;
            workerHandler = null;
        }
        
        Log.d(TAG, "AudioAnalyzer 已停止");
    }
    
    /**
     * 处理音频数据
     */
    private void processAudioData() {
        if (!isRecording || audioRecord == null || workerHandler == null) {
            return;
        }
        
        workerHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isRecording) {
                    return;
                }
                
                try {
                    short[] buffer = new short[fftSize];
                    int bytesRead = audioRecord.read(buffer, 0, buffer.length);
                    
                    if (bytesRead > 0) {
                        // 应用汉宁窗
                        for (int i = 0; i < bytesRead && i < window.length; i++) {
                            buffer[i] = (short) (buffer[i] * window[i]);
                        }
                        
                        // 执行 FFT
                        byte[] fftData = performFFT(buffer);
                        
                        // 通知监听器
                        if (listener != null) {
                            listener.onFftData(fftData);
                        }
                    }
                    
                } catch (Exception e) {
                    Log.e(TAG, "处理音频数据失败: " + e.getMessage());
                }
                
                // 继续处理下一批数据
                processAudioData();
            }
        });
    }
    
    /**
     * 执行 FFT 变换
     */
    private byte[] performFFT(short[] input) {
        int n = input.length;
        
        // 将 short 转换为复数（实部和虚部交替存储）
        float[] real = new float[n];
        float[] imag = new float[n];
        
        for (int i = 0; i < n; i++) {
            real[i] = input[i] / 32768.0f;
            imag[i] = 0;
        }
        
        // Cooley-Tukey FFT 算法
        fft(real, imag, n, false);
        
        // 计算幅度并返回（直接返回幅度数组，不是交替格式）
        int outputSize = Math.min(n / 2, 64); // 最多取64个频率分量
        byte[] result = new byte[outputSize];
        
        for (int i = 0; i < outputSize; i++) {
            // 计算幅度
            float magnitude = (float) Math.sqrt(real[i] * real[i] + imag[i] * imag[i]);
            // 映射到字节范围（0-127）
            result[i] = (byte) (magnitude * 127);
        }
        
        return result;
    }
    
    /**
     * 快速傅里叶变换
     */
    private void fft(float[] real, float[] imag, int n, boolean invert) {
        // Bit-reversal permutation
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; j >= bit; bit >>= 1) {
                j -= bit;
            }
            j += bit;
            if (i < j) {
                float temp = real[i];
                real[i] = real[j];
                real[j] = temp;
                temp = imag[i];
                imag[i] = imag[j];
                imag[j] = temp;
            }
        }
        
        // Cooley-Tukey FFT
        for (int len = 2; len <= n; len <<= 1) {
            float angle = (float) (2 * Math.PI / len * (invert ? -1 : 1));
            float wlenReal = (float) Math.cos(angle);
            float wlenImag = (float) Math.sin(angle);
            
            for (int i = 0; i < n; i += len) {
                float wReal = 1;
                float wImag = 0;
                
                for (int j = 0; j < len / 2; j++) {
                    int k = i + j;
                    int l = k + len / 2;
                    
                    float tReal = real[l] * wReal - imag[l] * wImag;
                    float tImag = real[l] * wImag + imag[l] * wReal;
                    
                    real[l] = real[k] - tReal;
                    imag[l] = imag[k] - tImag;
                    real[k] += tReal;
                    imag[k] += tImag;
                    
                    float wTemp = wReal;
                    wReal = wReal * wlenReal - wImag * wlenImag;
                    wImag = wTemp * wlenImag + wImag * wlenReal;
                }
            }
        }
        
        if (invert) {
            for (int i = 0; i < n; i++) {
                real[i] /= n;
                imag[i] /= n;
            }
        }
    }
    
    /**
     * 检查是否正在运行
     */
    public boolean isRunning() {
        return isRecording;
    }
}