package com.celllock.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** 所有 Root 子进程共用串行入口，诊断注销完成后才允许基带操作。 */
final class RootShell {
    private static volatile Boolean granted;
    private static boolean diagnosticSafe = true;
    private static final int MAX_OUTPUT = 256 * 1024;

    static boolean available() { return Boolean.TRUE.equals(granted); }
    static void retry() { granted = null; }
    static void authorized() { granted = true; }

    static synchronized boolean probe() {
        if (granted != null) return granted;
        try { granted = "0".equals(run("id -u", 10).trim()); }
        catch (Exception ignored) { granted = false; }
        return granted;
    }

    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    static synchronized String runWrite(String command, int timeoutSeconds) throws Exception {
        if (!diagnosticSafe) throw new IllegalStateException("诊断会话退出未确认，已停止基带操作；请重启手机后重试");
        return run(command, timeoutSeconds);
    }

    static synchronized String runDiagnostic(String command, int timeoutSeconds) throws Exception {
        if (!diagnosticSafe) throw new IllegalStateException("诊断接口已暂停");
        try {
            String output = run(command, timeoutSeconds);
            // su 可能掩盖信号退出，必须收到原生程序的完成回执。
            JSONObject result = null;
            for (String line : output.split("\\r?\\n"))
                if (line.trim().startsWith("{")) result = new JSONObject(line);
            if (result == null || !result.has("logs") && !result.has("error"))
                throw new IllegalStateException("诊断退出未返回完成结果");
            if (result.optString("error").equals("cleanup")) diagnosticSafe = false;
            return output;
        } catch (Exception error) {
            // 无法确认注销时，不让后续写操作与残留诊断会话并发。
            if (available()) diagnosticSafe = false;
            throw error;
        }
    }

    static synchronized String run(String command, int timeoutSeconds) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        boolean[] overflow = {false};
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) synchronized (captured) {
                    int room = MAX_OUTPUT - captured.size();
                    if (count > room) overflow[0] = true;
                    if (room > 0) captured.write(buffer, 0, Math.min(count, room));
                }
            } catch (Exception ignored) { }
        }, "celllock-root-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Root 读取超时，请检查授权或设备接口");
            }
            reader.join(1500);
            synchronized (captured) {
                if (reader.isAlive() || overflow[0]) throw new IllegalStateException("Root 输出不完整或超出上限");
                String text = captured.toString(StandardCharsets.UTF_8.name());
                if (process.exitValue() != 0) {
                    String error = text.trim().toLowerCase(java.util.Locale.ROOT);
                    // 单个设备接口/SELinux 拒绝不代表整个 Root 授权失效。
                    if (error.equals("permission denied") || error.startsWith("su:")) {
                        granted = false;
                        throw new IllegalStateException("Root 未授权，请检查后手动刷新");
                    }
                    // 原生接口以 1 返回业务错误，watchdog 或信号退出不能忽略。
                    if (process.exitValue() != 1 || error.isEmpty()) throw new IllegalStateException("Root 接口异常退出（" + process.exitValue() + "）");
                }
                return text;
            }
        } finally { process.destroy(); }
    }
}
