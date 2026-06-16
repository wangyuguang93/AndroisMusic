package com.example.musicplayer.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

public class SpectrumView extends View {
    private final Paint mPaint;
    private LinearGradient mBarGradient;

    // 总柱子数量，遵循行业听觉分配：32柱对数划分
    private int mBarCount = 32;
    private float mBarWidth;
    private float mBarSpacing;

    private float[] mCurrentHeights;
    private float[] mPrevHeights;
    private boolean mIsPlaying;

    // 动画参数：上升灵敏、下落快，强化动态对比
    private static final float SMOOTH_FACTOR = 0.62f;
    private static final float DROP_FACTOR = 0.22f;
    // 基线最小高度
    private static final float BASE_LINE = 0.002f;
    // 最大高度限制，永久保留渐变色彩，不会整块发黑
    private static final float MAX_HEIGHT_RATIO = 0.65f;
    // 全局放大倍率
    private static final float AMPLIFY = 0.62f;
    // 静默判定阈值，过滤极低底噪
    private static final float PLAY_SILENT_THRESHOLD = 0.4f;
    private static final float LOW_VOLUME_THRESHOLD = 3f;
    // FFT采样标准采样率
    private static final float SAMPLE_RATE = 44100f;

    // 渐变配色
    private static final int COLOR_BOTTOM = Color.parseColor("#42A5F5");
    private static final int COLOR_TOP = Color.parseColor("#EA40A4");

    public SpectrumView(Context context) {
        super(context);
        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        initView();
    }

    public SpectrumView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        initView();
    }

    public SpectrumView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        initView();
    }

    private void initView() {
        mPaint.setStyle(Paint.Style.FILL);
        mCurrentHeights = new float[mBarCount];
        mPrevHeights = new float[mBarCount];
        for (int i = 0; i < mBarCount; i++) {
            mCurrentHeights[i] = BASE_LINE;
            mPrevHeights[i] = BASE_LINE;
        }
    }

    /** 手动清空所有柱子基线 */
    public void resetAllToBase() {
        for (int i = 0; i < mBarCount; i++) {
            mCurrentHeights[i] = BASE_LINE;
            mPrevHeights[i] = BASE_LINE;
        }
        invalidate();
    }

    public void setPlaying(boolean playing) {
        mIsPlaying = playing;
    }

    /** 动态修改柱子总数 */
    public void setBarCount(int count) {
        if (count < 4) return;
        mBarCount = count;
        mCurrentHeights = new float[mBarCount];
        mPrevHeights = new float[mBarCount];
        for (int i = 0; i < mBarCount; i++) {
            mCurrentHeights[i] = BASE_LINE;
            mPrevHeights[i] = BASE_LINE;
        }
        requestLayout();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (mBarCount <= 0) return;
        mBarSpacing = 3f;
        float totalUseWidth = w - (mBarSpacing * (mBarCount - 1));
        mBarWidth = totalUseWidth / mBarCount;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int viewHeight = getHeight();
        float currentX = 0f;

        for (int i = 0; i < mBarCount; i++) {
            float ratio = mCurrentHeights[i];
            ratio = Math.max(ratio, BASE_LINE);
            ratio = Math.min(ratio, MAX_HEIGHT_RATIO);

            float barH = ratio * viewHeight;
            float topY = viewHeight - barH;
            float bottomY = viewHeight;

            mBarGradient = new LinearGradient(0, topY, 0, bottomY, COLOR_TOP, COLOR_BOTTOM, Shader.TileMode.CLAMP);
            mPaint.setShader(mBarGradient);
            canvas.drawRect(currentX, topY, currentX + mBarWidth, bottomY, mPaint);
            currentX += mBarWidth + mBarSpacing;
        }
        mPaint.setShader(null);
    }

    // 接收Visualizer FFT字节数组入口
    public void updateSpectrum(byte[] fftData) {
        if (fftData == null || fftData.length < 8) {
            softDecay();
            invalidate();
            return;
        }

        int totalBinCount = fftData.length / 2;
        if (totalBinCount <= 1) {
            softDecay();
            invalidate();
            return;
        }

        // 1. 解析全部FFT频点幅值mag
        float[] energyArr = new float[totalBinCount];
        float totalEnergy = 0f;
        for (int bin = 0; bin < totalBinCount; bin++) {
            int idxReal = bin * 2;
            int idxImag = bin * 2 + 1;
            int r = (fftData[idxReal] & 0xFF) - 128;
            int i = (fftData[idxImag] & 0xFF) - 128;
            float mag = (float) Math.hypot(r, i);
            energyArr[bin] = mag;
            totalEnergy += mag;
        }

        float avgEnergy = totalEnergy / totalBinCount;
        // 极低音量直接衰减，消除静态残留柱
        if (avgEnergy < PLAY_SILENT_THRESHOLD) {
            softDecay();
            invalidate();
            return;
        }

        // 2. 核心重构：对数倍频映射，符合人耳听觉标准
        mapLogHumanEarSpectrum(energyArr, totalBinCount, avgEnergy);
        invalidate();
    }

    /**
     * 行业标准对数频带映射（解决线性均分缺陷）
     * 20~500Hz低频：宽频带，少量柱子
     * 500~5000Hz人声中频：窄频细分，最多柱子
     * 5000~20000超高频：极宽合并，仅末尾少量柱子，杜绝长条信号栏
     */
    private void mapLogHumanEarSpectrum(float[] energyArr, int totalBinCount, float avgEnergy) {
        float nyquistFreq = SAMPLE_RATE / 2f;
        for (int bar = 0; bar < mBarCount; bar++) {
            // 对数刻度计算当前柱子对应起止频率
            float barRatio = (float) bar / mBarCount;
            float barNextRatio = (float) (bar + 1) / mBarCount;

            // 对数映射：log10(20) ~ log10(20000)
            float logMin = (float) Math.log10(20f);
            float logMax = (float) Math.log10(20000f);
            float currentLog = logMin + barRatio * (logMax - logMin);
            float nextLog = logMin + barNextRatio * (logMax - logMin);

            float freqLow = (float) Math.pow(10, currentLog);
            float freqHigh = (float) Math.pow(10, nextLog);

            // 频率转换为FFT Bin下标
            int binStart = freqToBin(freqLow, nyquistFreq, totalBinCount);
            int binEnd = freqToBin(freqHigh, nyquistFreq, totalBinCount);
            binStart = Math.max(0, binStart);
            binEnd = Math.min(totalBinCount - 1, binEnd);

            // 取该对数频段内最大幅值
            float maxRawMag = 0f;
            for (int b = binStart; b <= binEnd; b++) {
                maxRawMag = Math.max(maxRawMag, energyArr[b]);
            }

            // 幂函数压缩，拉大强弱幅值差距，静态底噪小幅值被自然压低
            float boostedMag = (float) Math.pow(maxRawMag / 100f, 1.2f) * 100f;
            // 低音量全局缩放
            float volScale = avgEnergy < LOW_VOLUME_THRESHOLD ? 0.7f : 1f;
            float targetHeight = boostedMag * volScale * AMPLIFY / 256f;
            targetHeight = Math.max(BASE_LINE, Math.min(targetHeight, MAX_HEIGHT_RATIO));

            // 平滑过渡计算
            float prev = mPrevHeights[bar];
            float newH;
            if (targetHeight > prev) {
                newH = prev * (1f - SMOOTH_FACTOR) + targetHeight * SMOOTH_FACTOR;
            } else {
                newH = prev * DROP_FACTOR + targetHeight * SMOOTH_FACTOR;
            }

            mCurrentHeights[bar] = newH;
            mPrevHeights[bar] = newH;
        }
    }

    /** 频率Hz 换算为FFT Bin下标 */
    private int freqToBin(float targetHz, float nyquist, int totalBin) {
        float ratio = targetHz / nyquist;
        int bin = Math.round(ratio * (totalBin - 1));
        return Math.max(0, Math.min(totalBin - 1, bin));
    }

    /** 无音频输入缓慢回落至基线 */
    private void softDecay() {
        float dropSpeed = 0.35f;
        for (int i = 0; i < mBarCount; i++) {
            float val = mCurrentHeights[i] * dropSpeed;
            mCurrentHeights[i] = Math.max(val, BASE_LINE);
            mPrevHeights[i] = BASE_LINE;
        }
    }
}