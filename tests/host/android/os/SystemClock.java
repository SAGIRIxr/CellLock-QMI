package android.os;

/** 仅供主机纯逻辑测试：可推进的测量时钟，不参与 APK 编译。 */
public final class SystemClock {
    public static long now = 1_000_000;
    public static long elapsedRealtime() { return now; }
}
