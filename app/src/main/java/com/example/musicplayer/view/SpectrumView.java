package com.example.musicplayer.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

public class SpectrumView extends View {

    private Paint paint;
    private float[] spectrumData;
    private float[] previousData;
    private int barCount = 32; // 条形数量
    private float barWidth;
    private float barSpacing;
    private Handler handler;
    private Runnable testRunnable;
    private boolean isTestMode = false;

    // 颜色渐变（蓝色到紫色）
    private int[] colors = {
            Color.parseColor("#42A5F5"), // 蓝色
            Color.parseColor("#64B5F6"),
            Color.parseColor("#90CAF9"),
            Color.parseColor("#CE93D8"), // 紫色
            Color.parseColor("#BA68C8")
    };

    public SpectrumView(Context context) {
        super(context);
        init();
    }

    public SpectrumView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SpectrumView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        paint = new Paint();
        paint.setStyle(Paint.Style.FILL);
        paint.setAntiAlias(true);
        
        // 初始化频谱数据
        spectrumData = new float[barCount];
        previousData = new float[barCount];
        
        handler = new Handler(Looper.getMainLooper());
    }

    /**
     * 启用测试模式（生成模拟数据）
     */
    public void startTestMode() {
        isTestMode = true;
        stopTestMode();
        testRunnable = new Runnable() {
            @Override
            public void run() {
                // 生成模拟的频谱数据（模拟真实音乐频谱）
                byte[] testData = generateSimulatedSpectrum();
                updateSpectrum(testData);
                handler.postDelayed(this, 80);
            }
        };
        handler.post(testRunnable);
    }

    /**
     * 生成模拟的频谱数据（模拟真实音乐频谱）
     */
    private byte[] generateSimulatedSpectrum() {
        byte[] data = new byte[128]; // 64个频率分量
        
        for (int i = 0; i < 64; i++) {
            // 模拟真实音乐频谱
            float freqRatio = (float)i / 64;
            
            // 低音区（0-200Hz）：较高能量
            float bassEnergy = (freqRatio < 0.15f) ? (1.0f - freqRatio / 0.15f) * 0.7f : 0;
            
            // 低中音区：中等能量
            float lowerMidEnergy = (freqRatio >= 0.15f && freqRatio < 0.3f) ? 
                (float)Math.sin((freqRatio - 0.15f) / 0.15f * Math.PI / 2) * 0.8f : 0;
            
            // 中音区（人声和主要乐器）：最高能量
            float midEnergy = (freqRatio >= 0.3f && freqRatio < 0.5f) ? 
                1.0f : 0;
            
            // 中高音区
            float upperMidEnergy = (freqRatio >= 0.5f && freqRatio < 0.7f) ? 
                (float)Math.cos((freqRatio - 0.5f) / 0.2f * Math.PI / 2) * 0.7f : 0;
            
            // 高音区：能量随频率增加而衰减
            float trebleEnergy = (freqRatio >= 0.7f) ? 
                (float)Math.cos((freqRatio - 0.7f) / 0.3f * Math.PI / 2) * 0.4f : 0;
            
            // 总能量
            float energy = bassEnergy + lowerMidEnergy + midEnergy + upperMidEnergy + trebleEnergy;
            
            // 添加随机变化（模拟音乐的动态变化）
            float random = (float) (Math.random() * 0.3 + 0.7);
            float magnitude = energy * random * 127;
            
            // 设置实部和虚部
            data[i * 2] = (byte) (magnitude * Math.cos(i * 0.2));
            data[i * 2 + 1] = (byte) (magnitude * Math.sin(i * 0.2));
        }
        return data;
    }

    /**
     * 停止测试模式
     */
    public void stopTestMode() {
        isTestMode = false;
        if (testRunnable != null) {
            handler.removeCallbacks(testRunnable);
            testRunnable = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // 如果没有外部数据输入，启动测试模式
        if (isTestMode) {
            startTestMode();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopTestMode();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        
        // 计算每个条形的宽度和间距
        float totalWidth = w;
        barSpacing = 3f; // 条形之间的间距
        barWidth = (totalWidth - (barSpacing * (barCount - 1))) / barCount;
        
        // 设置渐变
        updateGradient();
    }

    private void updateGradient() {
        if (getHeight() > 0) {
            LinearGradient gradient = new LinearGradient(0, 0, 0, getHeight(),
                    colors[0], colors[colors.length - 1], Shader.TileMode.CLAMP);
            paint.setShader(gradient);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (spectrumData == null || spectrumData.length == 0) {
            return;
        }

        float height = getHeight();
        float width = getWidth();
        
        // 更新渐变
        updateGradient();

        for (int i = 0; i < barCount; i++) {
            // 获取条形高度
            float barHeight = spectrumData[i] * height;
            // 确保最小高度
            barHeight = Math.max(barHeight, 3f);
            // 限制最大高度
            barHeight = Math.min(barHeight, height * 0.95f);

            // 计算条形的X坐标
            float left = i * (barWidth + barSpacing);
            float right = left + barWidth;
            
            // 计算条形的Y坐标（从底部开始）
            float bottom = height;
            float top = bottom - barHeight;

            // 绘制条形（带圆角）
            float cornerRadius = barWidth / 2f;
            canvas.drawRoundRect(left, top, right, bottom, cornerRadius, cornerRadius, paint);
        }
    }

    /**
     * 更新频谱数据
     * @param data FFT频谱数据（字节数组）
     */
    public void updateSpectrum(byte[] data) {
        if (data == null || data.length < 4) {
            return;
        }

        float[] newData = new float[barCount];
        
        // Visualizer FFT数据格式说明：
        // - data[0] = DC分量（实部）
        // - data[1] = 奈奎斯特频率分量（实部）
        // - data[2], data[3] = 第一个频率分量的实部和虚部
        // - data[4], data[5] = 第二个频率分量的实部和虚部
        // ...以此类推
        
        // 跳过DC分量和奈奎斯特分量，从索引2开始
        int dataIndex = 2;
        int dataSize = data.length - 2;
        
        // 计算步长，确保频率分布均匀覆盖整个频谱
        // 使用对数刻度映射，因为人耳对频率的感知是对数的
        float maxMagnitude = 0;
        
        for (int i = 0; i < barCount; i++) {
            // 使用对数映射来分布频率分量
            // 低频部分采样密集，高频部分采样稀疏（符合人耳听觉特性）
            float logIndex = (float) Math.log10(1 + 9 * ((float)i / barCount));
            int dataPosition = dataIndex + (int)(logIndex * (dataSize / 2));
            
            if (dataPosition + 1 < data.length) {
                // 获取有符号字节值（范围-128到127）
                float real = (data[dataPosition] + 128) / 256.0f * 2 - 1;
                float imag = (data[dataPosition + 1] + 128) / 256.0f * 2 - 1;
                
                // 计算幅度：sqrt(real^2 + imag^2)
                float magnitude = (float) Math.sqrt(real * real + imag * imag);
                
                // 记录最大幅度用于归一化
                if (magnitude > maxMagnitude) {
                    maxMagnitude = magnitude;
                }
                
                newData[i] = magnitude;
            } else {
                newData[i] = 0;
            }
        }
        
        // 归一化处理（确保数据能充分利用显示范围）
        if (maxMagnitude > 0) {
            float scale = Math.min(1.0f / maxMagnitude, 3.0f);
            for (int i = 0; i < barCount; i++) {
                newData[i] = newData[i] * scale;
            }
        }
        
        // 应用平滑处理，避免频谱突变
        applySmoothing(newData);
        
        // 更新数据
        spectrumData = newData;
        
        // 触发重绘
        invalidate();
    }

    /**
     * 应用平滑处理，避免频谱突变
     */
    private void applySmoothing(float[] newData) {
        float smoothFactor = 0.35f; // 平滑因子（0-1，值越大越平滑）
        
        for (int i = 0; i < barCount; i++) {
            // 与上一帧数据混合
            float smoothed = previousData[i] * smoothFactor + newData[i] * (1 - smoothFactor);
            newData[i] = smoothed;
        }
        
        // 保存当前数据用于下一帧平滑
        System.arraycopy(newData, 0, previousData, 0, barCount);
    }

    /**
     * 设置条形颜色
     * @param colors 颜色数组（渐变）
     */
    public void setBarColors(int[] colors) {
        this.colors = colors;
        invalidate();
    }

    /**
     * 设置条形数量
     * @param count 条形数量
     */
    public void setBarCount(int count) {
        this.barCount = count;
        this.spectrumData = new float[count];
        this.previousData = new float[count];
        requestLayout();
        invalidate();
    }
}
