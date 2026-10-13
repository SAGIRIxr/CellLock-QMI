package com.celllock.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** 以 root 身份调用随包的原生命令行 libcelllock_qmi.so，并解析它输出的 JSON。 */
final class Backend {
    private static final int MAX_OUTPUT = 64 * 1024;
    private static final int TIMEOUT_SECONDS = 40;

    private Backend() {}

    /** 订阅 ID → 物理卡槽（0 / 1）。 */
    static int slotOf(Context context, int subscriptionId) {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            throw new IllegalStateException("需要电话权限才能确定 SIM 卡槽");
        SubscriptionManager manager = context.getSystemService(SubscriptionManager.class);
        SubscriptionInfo info = manager == null ? null : manager.getActiveSubscriptionInfo(subscriptionId);
        if (info == null || info.getSimSlotIndex() < 0 || info.getSimSlotIndex() > 1)
            throw new IllegalStateException("无法确定所选 SIM 的卡槽");
        return info.getSimSlotIndex();
    }

    static synchronized ModemResult run(Context context, String... args) {
        for (String arg : args) {
            if (!arg.matches("[a-z0-9,\\-]+")) return ModemResult.failure("arguments", "参数格式不正确", arg);
        }
        File executable = new File(context.getApplicationInfo().nativeLibraryDir, "libcelllock_qmi.so");
        if (!executable.isFile()) return ModemResult.failure("backend", "原生组件缺失，请重新安装应用", "");

        StringBuilder command = new StringBuilder("'").append(executable.getAbsolutePath()).append("'");
        for (String arg : args) command.append(' ').append(arg);

        String output;
        try {
            output = su(command.toString());
        } catch (Exception e) {
            return ModemResult.failure("root", e.getMessage(), "");
        }
        JSONObject json = null;
        for (String line : output.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("{")) continue;
            try {
                json = new JSONObject(trimmed);
            } catch (Exception ignored) {
            }
        }
        if (json == null) {
            String hint = output.contains("denied") || output.trim().isEmpty()
                    ? "没有获得 Root 授权。请在 Root 管理器里允许「CellLock」后重试。"
                    : "后端没有返回有效结果";
            return ModemResult.failure("root", hint, output);
        }
        return ModemResult.parse(json);
    }

    private static String su(String command) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    synchronized (captured) {
                        int room = MAX_OUTPUT - captured.size();
                        if (room > 0) captured.write(buffer, 0, Math.min(count, room));
                    }
                }
            } catch (Exception ignored) {
            }
        }, "celllock-su");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("等待 Root 授权超时，请检查 Root 管理器");
            }
            reader.join(1500);
            synchronized (captured) {
                return new String(captured.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            process.destroy();
        }
    }
}
