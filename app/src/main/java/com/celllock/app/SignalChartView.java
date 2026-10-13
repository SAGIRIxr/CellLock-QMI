package com.celllock.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 服务小区信号趋势图。只画真实采样点，缺测处断线。 */
final class SignalChartView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<SignalHistory.Sample> samples = new ArrayList<>();
    private final float density, scaledDensity;
    private long start, end;
    private int bottom = -140, top = -60;
    private String startLabel = "", endLabel = "";

    SignalChartView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        scaledDensity = getResources().getDisplayMetrics().scaledDensity;
    }

    void setSamples(List<SignalHistory.Sample> values, long nowElapsed, long nowWall) {
        samples.clear();
        samples.addAll(values);
        end = nowElapsed;
        start = samples.isEmpty()
                ? end - 60_000
                : Math.max(end - SignalHistory.WINDOW_MS, Math.min(end - 30_000, samples.get(0).elapsedMillis));
        bottom = -140;
        top = -60;
        for (SignalHistory.Sample s : samples) {
            bottom = Math.min(bottom, (int) Math.floor(s.rsrp / 20d) * 20);
            top = Math.max(top, (int) Math.ceil(s.rsrp / 20d) * 20);
        }
        SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
        startLabel = format.format(new Date(nowWall - (nowElapsed - start)));
        endLabel = format.format(new Date(nowWall));
        setContentDescription(samples.isEmpty()
                ? "信号趋势：暂无采样"
                : "信号趋势，最近 " + Math.max(1, (end - start) / 1000) + " 秒，共 " + samples.size() + " 个采样");
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = dp(34), right = getWidth() - dp(7), upper = dp(12), lower = getHeight() - dp(24);
        if (right <= left || lower <= upper) return;

        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(sp(9));
        paint.setStrokeWidth(dp(1));
        for (int dbm = top; dbm >= bottom; dbm -= 20) {
            float y = y(dbm, upper, lower);
            paint.setColor(0xffe9eee8);
            canvas.drawLine(left, y, right, y, paint);
            paint.setColor(0xff859187);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(String.valueOf(dbm), left - dp(7), y + dp(3), paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(startLabel, left, getHeight() - dp(4), paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(endLabel, right, getHeight() - dp(4), paint);

        if (samples.isEmpty()) {
            paint.setColor(Ui.MUTED);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(sp(12));
            canvas.drawText("等待信号采样", (left + right) / 2, (upper + lower) / 2, paint);
            return;
        }

        paint.setStrokeWidth(dp(2.2f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        SignalHistory.Sample previous = null;
        int labels = 0;
        for (SignalHistory.Sample s : samples) {
            float x = x(s.elapsedMillis, left, right), y = y(s.rsrp, upper, lower);
            paint.setColor(SignalHistory.color(s.rsrp));
            if (previous != null && !s.breakBefore) {
                canvas.drawLine(x(previous.elapsedMillis, left, right), y(previous.rsrp, upper, lower), x, y, paint);
            }
            canvas.drawCircle(x, y, dp(samples.size() < 16 ? 3 : 2), paint);
            // 切换小区时标注新小区
            if (s.breakBefore && previous != null && !s.key.equals(previous.key) && labels++ < 3) {
                paint.setTextSize(sp(9));
                paint.setTextAlign(Paint.Align.LEFT);
                float width = paint.measureText(s.label);
                canvas.drawText(s.label, Math.max(left, Math.min(x + dp(5), right - width)),
                        Math.max(upper + dp(9), y - dp(8)), paint);
            }
            previous = s;
        }
        SignalHistory.Sample last = samples.get(samples.size() - 1);
        float lastX = x(last.elapsedMillis, left, right), lastY = y(last.rsrp, upper, lower);
        paint.setColor(SignalHistory.color(last.rsrp));
        paint.setAlpha(30);
        canvas.drawCircle(lastX, lastY, dp(7), paint);
        paint.setAlpha(255);
        canvas.drawCircle(lastX, lastY, dp(3.5f), paint);
    }

    private float x(long t, float left, float right) {
        return left + (t - start) * (right - left) / Math.max(1, end - start);
    }

    private float y(int dbm, float upper, float lower) {
        return lower - (dbm - bottom) * (lower - upper) / (top - bottom);
    }

    private float dp(float v) {
        return v * density;
    }

    private float sp(float v) {
        return v * scaledDensity;
    }

    /** 邻区列表里的信号强度条。 */
    static final class StrengthBar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int rsrp;
        private LinearGradient gradient;

        StrengthBar(Context context, int rsrp) {
            super(context);
            this.rsrp = rsrp;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            gradient = new LinearGradient(0, 0, Math.max(1, w), 0,
                    new int[]{0xffcf5355, 0xffc58c2d, Ui.GREEN}, null, Shader.TileMode.CLAMP);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float radius = getHeight() / 2f;
            paint.setShader(null);
            paint.setColor(0xffedf0eb);
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, paint);
            paint.setShader(gradient);
            canvas.drawRoundRect(0, 0, getWidth() * SignalHistory.fraction(rsrp), getHeight(), radius, radius, paint);
            paint.setShader(null);
        }
    }
}
