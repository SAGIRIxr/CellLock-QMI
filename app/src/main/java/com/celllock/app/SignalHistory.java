package com.celllock.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 服务小区信号的滚动历史。系统返回的缓存样本（时间戳没变）不会重复记录。 */
final class SignalHistory {
    static final int MAX_SAMPLES = 60;
    static final long WINDOW_MS = 300_000L;
    static final long MAX_CONNECTED_GAP_MS = 15_000L;

    static final class Sample {
        final long elapsedMillis, wallMillis;
        final int rsrp;
        final String key, label;
        final boolean breakBefore;

        Sample(long elapsedMillis, long wallMillis, int rsrp, String key, String label, boolean breakBefore) {
            this.elapsedMillis = elapsedMillis;
            this.wallMillis = wallMillis;
            this.rsrp = rsrp;
            this.key = key;
            this.label = label;
            this.breakBefore = breakBefore;
        }
    }

    private final ArrayList<Sample> samples = new ArrayList<>();
    private long latestTimestamp = -1;
    private boolean gap = true;

    List<Sample> samples() {
        return Collections.unmodifiableList(samples);
    }

    void clear() {
        samples.clear();
        latestTimestamp = -1;
        gap = true;
    }

    /** 下一个样本不与上一个连线（后台、读取失败等）。 */
    void markGap() {
        gap = true;
    }

    void trim(long now) {
        while (!samples.isEmpty()
                && (samples.size() > MAX_SAMPLES || now - samples.get(0).elapsedMillis > WINDOW_MS)) {
            samples.remove(0);
        }
    }

    boolean add(long timestamp, long nowElapsed, long nowWall, int rsrp, String key, String label) {
        trim(nowElapsed);
        if (timestamp <= 0 || timestamp > nowElapsed || nowElapsed - timestamp > WINDOW_MS
                || timestamp <= latestTimestamp || !validSignal(rsrp)) {
            return false;
        }
        Sample previous = samples.isEmpty() ? null : samples.get(samples.size() - 1);
        boolean split = gap || previous == null || !previous.key.equals(key)
                || timestamp - previous.elapsedMillis > MAX_CONNECTED_GAP_MS;
        samples.add(new Sample(timestamp, nowWall - (nowElapsed - timestamp), rsrp, key, label, split));
        latestTimestamp = timestamp;
        gap = false;
        trim(nowElapsed);
        return true;
    }

    void restore(Sample sample, long nowElapsed) {
        if (sample.elapsedMillis <= 0 || sample.elapsedMillis > nowElapsed
                || nowElapsed - sample.elapsedMillis > WINDOW_MS
                || sample.elapsedMillis <= latestTimestamp || !validSignal(sample.rsrp)) {
            return;
        }
        samples.add(sample);
        latestTimestamp = sample.elapsedMillis;
        trim(nowElapsed);
        gap = true;
    }

    static boolean validSignal(int value) {
        return value >= -160 && value <= -20;
    }

    static String strength(int value) {
        if (!validSignal(value)) return "信号未知";
        if (value >= -85) return "强";
        if (value >= -95) return "较强";
        if (value >= -105) return "一般";
        if (value >= -115) return "较弱";
        return "弱";
    }

    /** 由红经橙到绿的连续渐变。 */
    static int color(int value) {
        if (!validSignal(value)) return 0xff728078;
        if (value <= -100) return blend(0xffcf5355, 0xffc58c2d, clamp((value + 125) / 25f));
        return blend(0xffc58c2d, 0xff177557, clamp((value + 100) / 20f));
    }

    static float fraction(int value) {
        return validSignal(value) ? Math.max(.035f, Math.min(1f, (value + 140) / 60f)) : 0f;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static int blend(int from, int to, float amount) {
        int red = Math.round(((from >> 16) & 255) * (1 - amount) + ((to >> 16) & 255) * amount);
        int green = Math.round(((from >> 8) & 255) * (1 - amount) + ((to >> 8) & 255) * amount);
        int blue = Math.round((from & 255) * (1 - amount) + (to & 255) * amount);
        return 0xff000000 | (red << 16) | (green << 8) | blue;
    }
}
