package com.celllock.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

import org.json.JSONObject;

import java.io.File;

/** 以 root 身份调用随包的原生命令行 libcelllock_qmi.so，并解析它输出的 JSON。 */
final class Backend {
    private static final int TIMEOUT_SECONDS = 40;

    private Backend() {}

    /** 订阅 ID → 物理卡槽（0 / 1）。 */
    static int slotOf(Context context, int subscriptionId) {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) try {
            SubscriptionManager manager = context.getSystemService(SubscriptionManager.class);
            SubscriptionInfo info = manager == null ? null : manager.getActiveSubscriptionInfo(subscriptionId);
            if (info != null && info.getSimSlotIndex() >= 0 && info.getSimSlotIndex() <= 1) return info.getSimSlotIndex();
        } catch (SecurityException ignored) { }
        return RadioReader.cachedSlot(subscriptionId);
    }

    static synchronized ModemResult run(Context context, String... args) {
        for (String arg : args) {
            if (!arg.matches("[a-z0-9,\\-]+")) return ModemResult.failure("arguments", "参数格式不正确", arg);
        }
        File executable = new File(context.getApplicationInfo().nativeLibraryDir, "libcelllock_qmi.so");
        if (!executable.isFile()) return ModemResult.failure("backend", "原生组件缺失，请重新安装应用", "");

        StringBuilder command = new StringBuilder(RootShell.quote(executable.getAbsolutePath()));
        for (String arg : args) command.append(' ').append(arg);

        String output;
        try {
            output = RootShell.runBackend(command.toString(), TIMEOUT_SECONDS, args.length > 0 && args[0].equals("state"));
        } catch (Exception e) {
            boolean uncertain = e instanceof RootShell.Failure && ((RootShell.Failure)e).started && args.length > 0 && !args[0].equals("state");
            return ModemResult.failure(uncertain ? "unknown" : "root", e.getMessage(), "");
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
        RootShell.authorized();
        return ModemResult.parse(json);
    }

}
