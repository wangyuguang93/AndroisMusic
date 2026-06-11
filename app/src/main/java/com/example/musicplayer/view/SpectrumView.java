package com.example.musicplayer.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

public class SpectrumView extends View {

    private Paint paint;
    private byte[] spectrumData;
    private int barCount = 32; // 条形数量
    private float barWidth;
    private float barSpacing;
    private int barColor = Color.parseColor("#42A5F5"); // 蓝色条形
    private Handler handler;
    private Runnable testRunnable;
    private boolean isTestMode = false;

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
        paint.setColor(barColor);
        paint.setStyle(Paint.Style.FILL);
        paint.setAntiAlias(true);
        
        // 初始化频谱数据
        spectrumData = new byte[barCount];
        
        handler = new Handler(Looper.getMainLooper());
    }

    /**
     * 启用测试模式（生成随机数据）
     */
    public void startTestMode() {
        isTestMode = true;
        stopTestMode();
        testRunnable = new Runnable() {
            @Override
            public void run() {
                // 生成随机频谱数据
                byte[] testData = new byte[barCount];
                for (int i = 0; i < barCount; i++) {
                    testData[i] = (byte) (Math.random() * 127);
                }
                spectrumData = testData;
                invalidate();
                
                // 继续循环
                handler.postDelayed(this, 100);
            }
        };
        handler.post(testRunnable);
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
        // 测试模式已验证通过，现在使用真实数据
        // startTestMode();
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
        barSpacing = 4f; // 条形之间的间距
        barWidth = (totalWidth - (barSpacing * (barCount - 1))) / barCount;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (spectrumData == null || spectrumData.length == 0) {
            return;
        }

        float height = getHeight();
        float width = getWidth();

        for (int i = 0; i < barCount; i++) {
            // 计算条形的高度（基于频谱数据）
            float barHeight = 0;
            if (i < spectrumData.length) {
                // 频谱数据是幅度值（0-127）
                float magnitude = spectrumData[i] & 0xFF; // 转换为无符号值
                // 映射到视图高度（0到height的80%）
                barHeight = (magnitude / 128f) * height * 0.8f;
                // 确保最小高度（静音时贴近底部）
                barHeight = Math.max(barHeight, 3f);
            }

            // 计算条形的X坐标
            float left = i * (barWidth + barSpacing);
            float right = left + barWidth;
            
            // 计算条形的Y坐标（从底部开始）
            float bottom = height;
            float top = bottom - barHeight;

            // 绘制条形
            canvas.drawRect(left, top, right, bottom, paint);
        }
    }

    /**
     * 更新频谱数据
     * @param data FFT频谱数据（字节数组）
     */
    public void updateSpectrum(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }

        // 将数据映射到条形数量
        if (spectrumData == null || spectrumData.length != barCount) {
            spectrumData = new byte[barCount];
        }

        // FFT数据格式：偶数索引是实部，奇数索引是虚部
        // 我们需要计算每个频率分量的幅度
        int step = data.length / (barCount * 2);
        for (int i = 0; i < barCount; i++) {
            int baseIndex = i * step * 2;
            if (baseIndex + 1 < data.length) {
                // 计算幅度：sqrt(real^2 + imag^2)
                float real = data[baseIndex] / 128.0f;
                float imag = data[baseIndex + 1] / 128.0f;
                float magnitude = (float) Math.sqrt(real * real + imag * imag);
                // 将幅度转换为字节范围（0-127）
                spectrumData[i] = (byte) (magnitude * 127);
            } else {
                spectrumData[i] = 0;
            }
        }

        // 触发重绘
        invalidate();
    }

    /**
     * 设置条形颜色
     * @param color 颜色值
     */
    public void setBarColor(int color) {
        this.barColor = color;
        paint.setColor(color);
        invalidate();
    }

    /**
     * 设置条形数量
     * @param count 条形数量
     */
    public void setBarCount(int count) {
        this.barCount = count;
        this.spectrumData = new byte[count];
        requestLayout();
        invalidate();
    }
}