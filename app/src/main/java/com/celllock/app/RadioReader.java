package com.celllock.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.CellInfo;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** 唯一读取协调处；接口自己负责权限，页面不使用统一电话/定位门槛。 */
final class RadioReader {
    static final class SimMappingChanged extends IllegalStateException {
        SimMappingChanged() { super("所选 SIM 映射改变，已停止操作"); }
    }
    static final class Observation {
        final List<RadioCell> cells;
        final String status;
        final boolean mappingChanged;
        Observation(List<RadioCell> cells, String status, boolean mappingChanged) {
            this.cells = cells; this.status = status; this.mappingChanged = mappingChanged;
        }
    }

    private static volatile List<SimInfo> cachedSims = new ArrayList<>();
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile int generation, metadataGeneration;
    private volatile boolean closed, rootMeasurements;
    private volatile int processor = -1, diagnosticMisses;
    private volatile long nextDiagProbe;
    private volatile boolean diagUnsupported;
    private volatile int diagnosticSubscription=-1;
    private volatile String diagnosticSummary="尚无本次订阅的诊断摘要";
    String diagnosticSummary(int subscription){return diagnosticSubscription==subscription?diagnosticSummary:"当前订阅尚无诊断摘要";}
    void invalidateMeasurements(){cancel();worker.execute(diagnosticCells::clear);diagnosticSubscription=-1;diagnosticSummary="基带已请求重载，观测待刷新";}
    private final DiagnosticCells diagnosticCells = new DiagnosticCells();

    RadioReader(Context context) { this.context = context.getApplicationContext(); }
    boolean rootMeasurements() { return rootMeasurements; }

    static int cachedSlot(int subscription) {
        for (SimInfo sim : cachedSims) if (sim.subscriptionId == subscription && sim.trusted()) return sim.slot;
        throw new IllegalStateException("SIM 归属尚未确认，只允许浏览观测");
    }

    private static void remember(List<SimInfo> sims) {
        Set<Integer> slots = new HashSet<>(), ids = new HashSet<>();
        for (SimInfo sim : sims) {
            if (!slots.add(sim.slot) || sim.subscriptionId >= 0 && !ids.add(sim.subscriptionId))
                throw new IllegalArgumentException("SIM 映射重复");
        }
        cachedSims = new ArrayList<>(sims);
    }

    private static List<SimInfo> normalSims(Context context) {
        List<SimInfo> sims = new ArrayList<>();
        try {
            SubscriptionManager manager = context.getSystemService(SubscriptionManager.class);
            List<SubscriptionInfo> active = manager == null ? null : manager.getActiveSubscriptionInfoList();
            if (active != null) for (SubscriptionInfo info : active)
                if (info.getSimSlotIndex() >= 0) sims.add(SimInfo.from(info, "系统"));
        } catch (SecurityException | UnsupportedOperationException ignored) { }
        return sims;
    }

    private static List<SimInfo> rootSims(JSONObject result) throws Exception {
        if (result.optInt("uid", -1) != 0) throw new IllegalStateException("Root 电话接口不兼容");
        List<SimInfo> sims = new ArrayList<>();
        JSONArray values = result.getJSONArray("sims");
        if (values.length() > 8) throw new IllegalArgumentException("订阅数量超限");
        for (int i = 0; i < values.length(); i++) {
            SimInfo sim = SimInfo.parse(values.getJSONObject(i), "Root 电话");
            if (sim.subscriptionId < 0) throw new IllegalArgumentException("Root 返回无效订阅");
            sims.add(sim);
        }
        return sims;
    }

    static JSONObject phone(Context context, String command, int subscription) throws Exception {
        if (!(command.equals("sims") || command.equals("cells") && subscription >= 0)) throw new IllegalArgumentException();
        String invocation = "CLASSPATH=" + RootShell.quote(context.getApplicationInfo().sourceDir)
                + " app_process /system/bin com.celllock.app.RootPhone " + RootShell.quote(context.getPackageName())
                + " " + command + (command.equals("cells") ? " " + subscription : "");
        String output = RootShell.run(invocation, 12);
        for (String line : output.split("\\r?\\n")) if (line.startsWith("CELLLOCK_PHONE="))
            return new JSONObject(line.substring("CELLLOCK_PHONE=".length()));
        throw new IllegalStateException("Root 电话接口不可用");
    }

    static void verifySlot(Context context, int subscription, int slot) throws Exception {
        List<SimInfo> sims = normalSims(context);
        if (sims.isEmpty()) sims = rootSims(phone(context, "sims", -1));
        remember(sims);
        for (SimInfo sim : sims) if (sim.subscriptionId == subscription && sim.trusted() && sim.slot == slot) return;
        throw new SimMappingChanged();
    }

    private String nativeRead(String library, String args, int timeout) throws Exception {
        File executable = new File(context.getApplicationInfo().nativeLibraryDir, library);
        if (!executable.isFile()) throw new IllegalStateException("读取组件未安装");
        String command = "exec " + RootShell.quote(executable.getAbsolutePath()) + " " + args;
        if (!library.equals("libcelllock_diag.so")) return RootShell.run(command, timeout);
        return RootShell.runDiagnostic(executable, args, timeout);
    }

    void loadSims(boolean allowRoot,Consumer<List<SimInfo>> callback) {
        int token = ++metadataGeneration;
        worker.execute(() -> {
            if (closed || token != metadataGeneration) return;
            List<SimInfo> sims = normalSims(context);
            boolean root = allowRoot && RootShell.probe();
            if (sims.isEmpty() && root) try { sims = rootSims(phone(context, "sims", -1)); } catch (Exception ignored) { }
            if (sims.isEmpty() && root && RootShell.available()) try { sims = MeasurementDecoder.uim(nativeRead("libcelllock_read.so", "sims", 10)); } catch (Exception ignored) { }
            if (sims.isEmpty()) {
                TelephonyManager manager = context.getSystemService(TelephonyManager.class);
                try {
                    if (manager != null) for (int slot = 0; slot < Math.min(8, manager.getActiveModemCount()); slot++)
                        if (manager.getSimState(slot) == TelephonyManager.SIM_STATE_READY)
                            sims.add(new SimInfo(-1, slot, "SIM " + (slot + 1) + " · 订阅读取受限", "系统卡状态"));
                } catch (SecurityException | UnsupportedOperationException ignored) { }
            }
            try { remember(sims); } catch (IllegalArgumentException ignored) { sims = new ArrayList<>(); remember(sims); }
            List<SimInfo> result = sims;
            main.post(() -> { if (!closed && token == metadataGeneration) callback.accept(result); });
        });
    }

    void retry() { RootShell.retry(); processor = -1; diagnosticMisses = 0; diagUnsupported = false; nextDiagProbe = 0; }
    static int[] diagnosticCandidates(int processor, int misses) {
        return processor < 0 ? new int[]{1, 0} : misses >= 2 ? new int[]{processor, 1 - processor} : new int[]{processor};
    }
    void cancel() { generation++; }
    void close() { closed = true; cancel(); metadataGeneration++; worker.shutdown(); }
    private boolean current(int token) { return !closed && token == generation; }

    void read(SimInfo sim, Consumer<Observation> callback) {
        int token = ++generation;
        LocationManager location = context.getSystemService(LocationManager.class);
        boolean normal = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && location != null && location.isLocationEnabled();
        TelephonyManager manager = context.getSystemService(TelephonyManager.class);
        if (!normal || manager == null) { fallback(token, sim, new ArrayList<>(), callback); return; }
        Runnable timeout = () -> fallback(token, sim, new ArrayList<>(), callback);
        main.postDelayed(timeout, 4000);
        try {
            if (sim == null || sim.subscriptionId < 0) {
                main.removeCallbacks(timeout);
                List<RadioCell> cells = new ArrayList<>();
                List<CellInfo> values = manager.getAllCellInfo();
                if (values != null) for (CellInfo info : values) {
                    RadioCell cell = RadioCell.from(info);
                    if (cell != null) cells.add(cell.withSource("系统 · SIM 归属未知", false));
                }
                fallback(token, sim, cells, callback);
            } else manager.createForSubscriptionId(sim.subscriptionId).requestCellInfoUpdate(context.getMainExecutor(),
                    new TelephonyManager.CellInfoCallback() {
                        private boolean completed;
                        @Override public void onCellInfo(List<CellInfo> values) {
                            if (completed || !current(token)) return;
                            completed = true; main.removeCallbacks(timeout);
                            List<RadioCell> cells = new ArrayList<>();
                            List<SimInfo> active = normalSims(context);
                            boolean mapped = sim.trusted() && active.stream().anyMatch(s -> s.key().equals(sim.key()));
                            if (values != null) for (CellInfo info : values) {
                                RadioCell cell = RadioCell.from(info);
                                if (cell != null) cells.add(cell.withSource("系统", mapped));
                            }
                            fallback(token, sim, cells, callback);
                        }
                        @Override public void onError(int code, Throwable error) {
                            if (completed || !current(token)) return;
                            completed = true; main.removeCallbacks(timeout);
                            fallback(token, sim, new ArrayList<>(), callback);
                        }
                    });
        } catch (SecurityException | UnsupportedOperationException | IllegalArgumentException error) {
            main.removeCallbacks(timeout);
            fallback(token, sim, new ArrayList<>(), callback);
        }
    }

    private void fallback(int token, SimInfo sim, List<RadioCell> ordinary, Consumer<Observation> callback) {
        if (!current(token)) return;
        // Timeout 与迟到 callback 只能提交一次后续读取。
        generation++;
        int workToken = generation;
        worker.execute(() -> {
            if (!current(workToken)) return;
            List<RadioCell> cells = new ArrayList<>(ordinary);
            boolean changed = false;
            StringBuilder diagnostic=new StringBuilder("采集上下文："+(sim==null?"无订阅":sim.key())+"，普通观测 "+ordinary.size());
            String status = cells.isEmpty() ? "当前权限或设备接口暂未返回小区" : "系统观测";
            if (RootShell.probe()) {
                if (cells.stream().noneMatch(RadioCell::hasMappedIdentity) && sim != null && sim.subscriptionId >= 0) try {
                    JSONObject result = phone(context, "cells", sim.subscriptionId);
                    List<SimInfo> fresh = rootSims(result); remember(fresh);
                    changed = fresh.stream().noneMatch(s -> s.key().equals(sim.key()))
                            || result.has("sim") && !SimInfo.parse(result.getJSONObject("sim"), "Root 电话").key().equals(sim.key());
                    if (!changed && !result.has("error") && SimInfo.parse(result.getJSONObject("sim"), "Root 电话").key().equals(sim.key())) {
                        JSONArray values = result.getJSONArray("cells");
                        if (values.length() > 512) throw new IllegalArgumentException("电话小区数量超限");
                        List<RadioCell> updated = new ArrayList<>();
                        for (int i = 0; i < values.length(); i++)
                            updated.add(RadioCell.parse(values.getJSONObject(i), "Root 电话").withSource("Root 电话", sim.trusted()));
                        if (!updated.isEmpty()) { appendReadings(cells, updated); status = "Root 电话观测"; }
                    }
                    if (cells.isEmpty() && result.has("error")) status = result.optString("error");
                } catch (Exception ignored) { }
                if (!current(workToken)) return;
                if (RootShell.available() && !changed && sim != null && sim.slot <= 1) try {
                    verifySlot(context, sim.subscriptionId, sim.slot);
                    List<RadioCell> measured = MeasurementDecoder.nas(nativeRead("libcelllock_read.so", "cells " + sim.slot, 8), sim.slot);
                    if (!current(workToken)) return;
                    verifySlot(context, sim.subscriptionId, sim.slot);
                    diagnostic.append("\nNAS 解码 ").append(measured.size());
                    if (!measured.isEmpty()) {
                        status = cells.isEmpty() ? "QMI NAS 观测" : status + " · NAS 补充";
                        supplementReadings(cells, measured);
                    }
                } catch (SimMappingChanged error) { changed = true; }
                catch (Exception ignored) { }
                if (!current(workToken)) return;
                if (changed) diagnosticCells.clear();
                else diagnosticCells.context(sim == null ? "无 SIM" : sim.key(), cells);
                if (RootShell.available() && !changed && !diagUnsupported && SystemClock.elapsedRealtime() >= nextDiagProbe) {
                    boolean received = false;
                    int[] candidates = diagnosticCandidates(processor, diagnosticMisses);
                    for (int candidate : candidates) {
                        if (!current(workToken) || !RootShell.available()) break;
                        try {
                            List<JSONObject> records = MeasurementDecoder.records(nativeRead("libcelllock_diag.so", String.valueOf(candidate), 12));
                            if (records.isEmpty()){diagnostic.append("\nprocessor ").append(candidate).append("：没有完成回执");continue;}
                            JSONObject result = records.get(records.size() - 1);
                            if(result.has("logs")&&!result.has("error")) {
                                MeasurementDecoder.NrBatch batch=MeasurementDecoder.nrBatch(result);
                                diagnostic.append("\nprocessor ").append(candidate).append("：").append(batch.summary());
                                if(batch.usable()) {
                                    diagnosticCells.merge(batch.cells,SystemClock.elapsedRealtime());
                                    processor=candidate;diagnosticMisses=0;received=true;break;
                                }
                            } else {
                                String error=result.optString("error");
                                diagnostic.append("\nprocessor ").append(candidate).append(error.equals("no_log")?"：无日志 ":"：通道 ").append(error).append(" / ").append(result.optString("stage"));
                                if(error.equals("library")||error.equals("api")||error.equals("cleanup")){diagUnsupported=true;break;}
                            }
                        } catch(IllegalArgumentException error){diagnostic.append("\nprocessor ").append(candidate).append("：批次无效 / ").append(error.getMessage());}
                        catch(Exception error){diagnostic.append("\nprocessor ").append(candidate).append("：读取失败 / ").append(error.getMessage());}
                    }
                    if (!received) {
                        diagnosticMisses = Math.min(2, diagnosticMisses + 1);
                        nextDiagProbe = SystemClock.elapsedRealtime() + 5_000;
                    }
                }
                if(diagUnsupported)diagnostic.append("\nDIAG 通道已暂停，手动刷新可重试；格式错误不会触发该暂停");
                else if(SystemClock.elapsedRealtime()<nextDiagProbe)diagnostic.append("\nDIAG 沿用既有有界退避");
                if (RootShell.available() && !changed) {
                    List<RadioCell> recent = diagnosticCells.values(SystemClock.elapsedRealtime());
                    cells.addAll(recent);
                    if (!recent.isEmpty()) status += " · NR 最近 15 秒观测";
                }
            } else {diagnosticCells.clear();diagnostic.append("\nRoot 当前不可用，未采集 QMI / DIAG");}
            rootMeasurements = RootShell.available() && cells.stream().anyMatch(c -> !c.source.startsWith("系统"));
            if (changed) { cells.clear(); status = "SIM 映射已改变，请重新选择"; }
            diagnostic.append("\n合并前输出 ").append(cells.size()).append("，可靠归属 ").append(cells.stream().filter(c->c.mapped).count()).append("；设备级 DIAG 不推断 SIM。缓存窗口 15 秒。");
            if(current(workToken)){diagnosticSubscription=sim==null?-1:sim.subscriptionId;diagnosticSummary=diagnostic.substring(0,Math.min(2400,diagnostic.length()));}
            Observation result = new Observation(cells, status, changed);
            main.post(() -> { if (current(workToken)) callback.accept(result); });
        });
    }

    private static void appendReadings(List<RadioCell> cells, List<RadioCell> updated) {
        for (RadioCell next : updated) {
            cells.removeIf(old -> old.nr == next.nr && old.arfcn == next.arfcn && old.pci == next.pci
                    && old.identity == next.identity && old.source.equals(next.source));
            cells.add(next);
        }
    }

    static void supplementReadings(List<RadioCell> cells, List<RadioCell> measured) {
        for (RadioCell next : measured) {
            if (next.registered && next.details.containsKey("补充来源"))
                for (int i = 0; i < cells.size(); i++) cells.set(i, cells.get(i).supplement(next));
            boolean duplicate = next.arfcn != CellInfo.UNAVAILABLE && cells.stream().anyMatch(old -> old.nr == next.nr && old.arfcn == next.arfcn
                    && (old.pci == next.pci && next.pci != CellInfo.UNAVAILABLE
                        || next.pci == CellInfo.UNAVAILABLE && !next.hasSignal() && next.identity < 0 && next.details.isEmpty())
                    && (old.identity < 0 || next.identity < 0 || old.identity == next.identity)
                    && !RadioCell.conflictingIdentityDetails(old.details, next.details));
            if (!duplicate) cells.add(next);
        }
    }
}
