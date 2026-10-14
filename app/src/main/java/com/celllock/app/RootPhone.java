package com.celllock.app;

import android.content.Context;
import android.os.Looper;
import android.os.Process;
import android.telephony.CellInfo;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** APK 自带的有界 app_process 入口，不改变主 App 的权限或系统设置。 */
public final class RootPhone {
    public static void main(String[] args) {
        JSONObject result = new JSONObject();
        try {
            if (Process.myUid() != 0 || args.length < 2 || args.length > 3
                    || !args[0].equals("com.celllock.app")
                    || !(args[1].equals("sims") && args.length == 2
                    || args[1].equals("cells") && args.length == 3 && args[2].matches("[0-9]{1,10}")))
                throw new IllegalArgumentException("无效 Root 电话请求");
            Looper.prepareMainLooper();
            Class<?> initializer = Class.forName("android.telephony.TelephonyFrameworkInitializer");
            Class<?> manager = Class.forName("android.os.TelephonyServiceManager");
            initializer.getDeclaredMethod("setTelephonyServiceManager", manager)
                    .invoke(null, manager.getDeclaredConstructor().newInstance());
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object thread = threadClass.getDeclaredMethod("systemMain").invoke(null);
            Context context = ((Context) threadClass.getDeclaredMethod("getSystemContext").invoke(thread))
                    .createPackageContext(args[0], 0);
            List<SubscriptionInfo> active = context.getSystemService(SubscriptionManager.class).getActiveSubscriptionInfoList();
            JSONArray sims = new JSONArray();
            SubscriptionInfo selected = null;
            int requested = args.length == 3 ? Integer.parseInt(args[2]) : -1;
            if (active != null) for (SubscriptionInfo info : active) {
                if (info.getSimSlotIndex() < 0) continue;
                sims.put(SimInfo.from(info, "Root 电话").json());
                if (info.getSubscriptionId() == requested) selected = info;
            }
            result.put("uid", 0).put("sims", sims);
            if (args[1].equals("cells")) {
                if (selected == null) throw new IllegalStateException("订阅已改变或不可用");
                result.put("sim", SimInfo.from(selected, "Root 电话").json());
                CountDownLatch pending = new CountDownLatch(1);
                AtomicBoolean completed = new AtomicBoolean();
                context.getSystemService(TelephonyManager.class).createForSubscriptionId(requested)
                        .requestCellInfoUpdate(Runnable::run, new TelephonyManager.CellInfoCallback() {
                            @Override public void onCellInfo(List<CellInfo> cells) {
                                synchronized (result) {
                                    if (completed.getAndSet(true)) return;
                                    try {
                                        JSONArray values = new JSONArray();
                                        if (cells != null) for (CellInfo info : cells) {
                                            RadioCell cell = RadioCell.from(info);
                                            if (cell != null) values.put(cell.json());
                                        }
                                        result.put("cells", values);
                                    } catch (Exception error) {
                                        try { result.put("error", "电话测量格式不兼容"); } catch (Exception ignored) { }
                                    }
                                    pending.countDown();
                                }
                            }
                            @Override public void onError(int code, Throwable detail) {
                                synchronized (result) {
                                    if (completed.getAndSet(true)) return;
                                    try { result.put("error", "电话测量暂不可用（" + code + "）"); } catch (Exception ignored) { }
                                    pending.countDown();
                                }
                            }
                        });
                if (!pending.await(6, TimeUnit.SECONDS)) synchronized (result) {
                    if (!completed.getAndSet(true)) result.put("error", "电话测量超时");
                }
            }
        } catch (SecurityException error) {
            try { result.put("error", "Root 电话接口拒绝访问"); } catch (Exception ignored) { }
        } catch (Throwable error) {
            try { result.put("error", error.getClass().getSimpleName()); } catch (Exception ignored) { }
        }
        System.out.println("CELLLOCK_PHONE=" + result);
        System.exit(0);
    }
}
