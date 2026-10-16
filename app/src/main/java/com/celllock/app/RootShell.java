package com.celllock.app;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;

/** 所有 Root 子进程共用串行入口，诊断注销完成后才允许基带操作。 */
final class RootShell {
    private static volatile Boolean granted;
    private static boolean diagnosticSafe = true;
    private static final int MAX_OUTPUT = 256 * 1024;
    private static Process shell;
    private static InputStream shellOutput;

    static final class Failure extends IllegalStateException {
        final boolean started, retryable;
        Failure(String message, boolean started, boolean retryable, Exception cause) {
            super(message, cause); this.started = started; this.retryable = retryable;
        }
    }

    static boolean available() { return Boolean.TRUE.equals(granted); }
    static void retry() { if (!available()) granted = null; }
    static void authorized() { granted = true; }

    static synchronized boolean probe() {
        if (granted != null) return granted;
        try { granted = "0".equals(run("id -u", 10).trim()); }
        catch (Exception ignored) { granted = false; }
        return granted;
    }

    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    static synchronized String runWrite(String command, int timeoutSeconds) throws Exception {
        return runBackend(command, timeoutSeconds, false);
    }

    static synchronized String runBackend(String command, int timeoutSeconds, boolean readOnly) throws Exception {
        if (!diagnosticSafe) throw new IllegalStateException("诊断会话退出未确认，已停止基带操作；请重启手机后重试");
        return execute(command, timeoutSeconds, readOnly);
    }

    static String runDiagnostic(File executable, String arguments, int timeoutSeconds) throws Exception {
        // APK 路径处于隔离 linker namespace；复用 Root 私有临时目录执行和退出清理。
        String command = "dir=$(mktemp -d /data/local/tmp/celllock-diag.XXXXXX) || exit 1; "
                + "trap 'rm -f \"$dir/diag\"; rmdir \"$dir\"' EXIT; "
                + "cp " + quote(executable.getAbsolutePath()) + " \"$dir/diag\" && "
                + "chmod 700 \"$dir/diag\" && \"$dir/diag\" " + arguments;
        return runDiagnostic(command, timeoutSeconds);
    }

    static synchronized String runDiagnostic(String command, int timeoutSeconds) throws Exception {
        if (!diagnosticSafe) throw new IllegalStateException("诊断接口已暂停");
        try {
            String output = execute(command, timeoutSeconds, false);
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
            if (!(error instanceof Failure) || ((Failure) error).started) diagnosticSafe = false;
            throw error;
        }
    }

    static synchronized String run(String command, int timeoutSeconds) throws Exception {
        return execute(command, timeoutSeconds, true);
    }

    private static String execute(String command, int timeoutSeconds, boolean readOnly) throws Exception {
        boolean previouslyGranted = available();
        for (int attempt = 0; ; attempt++) {
            boolean started = false;
            try {
                if (shell == null || !shell.isAlive()) {
                    closeShell();
                    shell = new ProcessBuilder("su").redirectErrorStream(true).start();
                    shellOutput = new BufferedInputStream(shell.getInputStream());
                    if (!runOnce("id -u", Math.min(timeoutSeconds, 10)).trim().equals("0")) {
                        granted = false;
                        throw new SecurityException("Root 未授权，请检查后手动刷新");
                    }
                    granted = true;
                }
                // 完成授权握手后才发送业务命令；发送失败也按可能已执行处理。
                started = true;
                return runOnce(command, timeoutSeconds);
            } catch (Exception error) {
                closeShell();
                boolean retryable = !(error instanceof InterruptedException)
                        && (!(error instanceof Failure) || ((Failure) error).retryable);
                if (attempt == 0 && retryable && (readOnly || !started)
                        && (!Boolean.FALSE.equals(granted) || previouslyGranted)) continue;
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new Failure(error.getMessage(), started, retryable, error);
            }
        }
    }

    private static String runOnce(String command, int timeoutSeconds) throws Exception {
        InputStream input = shellOutput;
        String marker = "CELLLOCK_DONE_" + UUID.randomUUID() + ":";
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        boolean[] overflow = {false};
        int[] exit = {-1};
        Thread reader = new Thread(() -> {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            try {
                int value;
                while ((value = input.read()) != -1) {
                    if (value == '\n') {
                        String text = line.toString(StandardCharsets.UTF_8.name());
                        if (text.startsWith(marker)) {
                            exit[0] = Integer.parseInt(text.substring(marker.length()).trim());
                            return;
                        }
                        if (captured.size() + line.size() + 1 <= MAX_OUTPUT) {
                            line.writeTo(captured); captured.write('\n');
                        } else overflow[0] = true;
                        line.reset();
                    } else if (line.size() < MAX_OUTPUT) line.write(value);
                    else overflow[0] = true;
                }
                if (captured.size() + line.size() <= MAX_OUTPUT) line.writeTo(captured);
                else overflow[0] = true;
            } catch (Exception ignored) { }
        }, "celllock-root-output");
        reader.setDaemon(true);
        reader.start();
        try {
            // 子 shell 隔离 exec、exit、trap 与环境，结束标记只由常驻 shell 输出。
            String script = "(\n" + command + "\n) </dev/null\nprintf '\\n" + marker + "%s\\n' \"$?\"\n";
            java.io.IOException writeError = null;
            try {
                shell.getOutputStream().write(script.getBytes(StandardCharsets.UTF_8));
                shell.getOutputStream().flush();
            } catch (java.io.IOException error) {
                // su 拒绝时可能先关闭输入，仍需读完输出以识别授权结果。
                writeError = error;
            }
            reader.join(timeoutSeconds * 1000L);
            if (reader.isAlive()) {
                throw new IllegalStateException("Root 读取超时，请检查授权或设备接口");
            }
            if (overflow[0]) throw new Failure("Root 输出不完整或超出上限", true, false, null);
            String text = captured.toString(StandardCharsets.UTF_8.name());
            if (exit[0] != 0) {
                String error = text.trim().toLowerCase(java.util.Locale.ROOT);
                // 单个设备接口/SELinux 拒绝不代表整个 Root 授权失效。
                if (error.equals("permission denied") || error.startsWith("su:")) {
                    granted = false;
                    throw new SecurityException("Root 未授权，请检查后手动刷新");
                }
                if (writeError != null) throw writeError;
                // 原生接口以 1 返回业务错误，watchdog 或信号退出不能忽略。
                if (exit[0] != 1 || error.isEmpty()) throw new IllegalStateException("Root 接口异常退出（" + exit[0] + "）");
            }
            if (writeError != null) throw writeError;
            return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        } catch (Exception error) {
            closeShell();
            throw error;
        }
    }

    private static void closeShell() {
        if (shell == null) return;
        shell.destroyForcibly();
        try { shell.getOutputStream().close(); } catch (Exception ignored) { }
        try { shellOutput.close(); } catch (Exception ignored) { }
        shell = null; shellOutput = null;
    }
}
