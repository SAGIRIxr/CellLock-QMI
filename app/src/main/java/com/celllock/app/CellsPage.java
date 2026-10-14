package com.celllock.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.CellInfo;
import android.telephony.SubscriptionManager;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class CellsPage extends ScrollView {
    interface Host {
        void onTarget(RadioCell cell);
        void onNeedPermission();
        void onServingChanged(RadioCell cell);
        void onSimChanged();
    }

    private static final long INTERVAL_MS = 5_000, TIMEOUT_MS = 30_000;
    private final Activity activity;
    private final Ui ui;
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SignalHistory history = new SignalHistory();
    private final Map<View,RadioCell> targetViews = new LinkedHashMap<>();
    private final RadioReader reader;
    private SimInfo sim;
    private String restoredKey;
    private boolean hasObservations;
    private long receivedAt;
    private final Runnable autoRefresh = () -> {
        scheduled = false;
        readCells(true);
    };
    private Runnable timeout;
    private int subscriptionId = SubscriptionManager.INVALID_SUBSCRIPTION_ID, generation;
    private boolean foreground, busy, disposed, reading, scheduled, cached;
    private RadioCell serving;
    private TextView autoStatus, rat, servingCaption, signal, quality, reference, servingDetails;
    private TextView arfcn, pci, band, observationStatus, neighborCount, trendRange, trendStatus;
    private Button refreshButton, targetButton;
    private LinearLayout neighbors;
    private LineIcon bars;
    private SignalChartView chart;

    CellsPage(Activity activity, Ui ui, RadioReader reader, Host host) {
        super(activity);
        this.activity = activity;
        this.ui = ui;
        this.host = host;
        this.reader = reader;
        setFillViewport(true);
        setClipToPadding(false);
        setVerticalScrollBarEnabled(false);
        LinearLayout page = ui.column();
        page.setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20));
        addView(page);
        build(page);
        renderServing();
        emptyNeighbors("等待可用的小区观测");
        renderTrend();
        updatePolicy();
    }

    private void build(LinearLayout page) {
        LinearLayout heading = ui.row();
        heading.addView(ui.title("当前连接"), ui.weight(-2));
        autoStatus = ui.text("自动 · 5 秒", 10, Ui.GREEN);
        heading.addView(autoStatus);
        ui.gap(heading, 10);
        refreshButton = ui.button("刷新", false);
        refreshButton.setTextSize(13);
        heading.addView(refreshButton, new LinearLayout.LayoutParams(ui.dp(62), ui.dp(36)));
        refreshButton.setOnClickListener(v -> refresh());
        page.addView(heading);
        ui.gap(page, 9);

        LinearLayout hero = ui.card(page);
        hero.setPadding(ui.dp(19), ui.dp(17), ui.dp(19), ui.dp(15));
        LinearLayout top = ui.row();
        rat = ui.pill("待刷新", Ui.GREEN, Ui.SOFT_GREEN);
        top.addView(rat);
        servingCaption = ui.text("未读取服务小区", 12, Ui.MUTED);
        servingCaption.setGravity(Gravity.END);
        top.addView(servingCaption, ui.weight(-2));
        hero.addView(top);
        ui.gap(hero, 9);
        LinearLayout readingRow = ui.row();
        LinearLayout numberRow = ui.row();
        numberRow.setGravity(Gravity.BOTTOM);
        signal = ui.text("—", 57, Ui.INK);
        signal.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        signal.setLetterSpacing(-.045f);
        numberRow.addView(signal);
        TextView unit = ui.text("dBm", 13, Ui.MUTED);
        LinearLayout.LayoutParams unitLayout = new LinearLayout.LayoutParams(-2, -2);
        unitLayout.setMargins(ui.dp(6), 0, 0, ui.dp(10));
        numberRow.addView(unit, unitLayout);
        readingRow.addView(numberRow, ui.weight(-2));
        LinearLayout strength = ui.column();
        strength.setGravity(Gravity.CENTER);
        bars = new LineIcon(activity, LineIcon.BARS, Ui.GREEN);
        strength.addView(bars, new LinearLayout.LayoutParams(ui.dp(39), ui.dp(34)));
        ui.gap(strength, 5);
        quality = ui.text("信号未知", 11, Ui.MUTED);
        strength.addView(quality);
        readingRow.addView(strength);
        hero.addView(readingRow);
        reference = ui.text("RSRP · 强弱为应用参考", 10, Ui.MUTED);
        hero.addView(reference);
        ui.gap(hero, 13);
        hero.addView(ui.divider(), ui.fill(1));
        ui.gap(hero, 12);
        LinearLayout metrics = ui.row();
        arfcn = metric(metrics, "频点");
        pci = metric(metrics, "PCI");
        band = metric(metrics, "频段");
        hero.addView(metrics);
        ui.gap(hero, 8);
        servingDetails = ui.text("", 10, Ui.MUTED);
        hero.addView(servingDetails);
        ui.gap(hero, 13);
        targetButton = ui.button("用作锁定目标", true);
        hero.addView(targetButton, ui.fill(41));
        targetButton.setOnClickListener(v -> selectTarget(serving));
        ui.gap(page, 8);
        observationStatus = ui.text("等待可用的小区观测", 10, Ui.MUTED);
        observationStatus.setGravity(Gravity.CENTER);
        page.addView(observationStatus);
        ui.gap(page, 13);

        LinearLayout trend = ui.card(page);
        trend.setPadding(ui.dp(15), ui.dp(15), ui.dp(15), ui.dp(12));
        LinearLayout trendHeading = ui.row();
        TextView trendTitle = ui.text("信号趋势", 14, Ui.INK);
        trendTitle.setTypeface(null, Typeface.BOLD);
        trendHeading.addView(trendTitle, ui.weight(-2));
        trendRange = ui.text("最近 5 分钟", 10, Ui.MUTED);
        trendHeading.addView(trendRange);
        trend.addView(trendHeading);
        ui.gap(trend, 4);
        chart = new SignalChartView(activity);
        trend.addView(chart, ui.fill(132));
        ui.gap(trend, 5);
        trendStatus = ui.text("等待真实信号采样", 10, Ui.MUTED);
        trend.addView(trendStatus);
        ui.gap(trend, 4);
        trend.addView(ui.text("dBm 数值越大，信号越强 · 最多 60 点", 10, Ui.MUTED));
        ui.gap(page, 18);
        LinearLayout neighborHeading = ui.row();
        neighborHeading.addView(ui.title("周边小区"), ui.weight(-2));
        neighborCount = ui.text("0 个", 12, Ui.MUTED);
        neighborHeading.addView(neighborCount);
        page.addView(neighborHeading);
        ui.gap(page, 12);
        neighbors = ui.column();
        neighbors.setBackground(ui.shape(Ui.CARD, 20, 0));
        page.addView(neighbors, ui.fill(-2));
        ui.gap(page, 13);
        page.addView(ui.caption("邻区合并最近 15 秒；与服务频段、频点相同的观测可填入所选 SIM 的锁定目标。"));
    }

    private TextView metric(LinearLayout parent, String label) {
        LinearLayout column = ui.column();
        column.addView(ui.text(label, 11, Ui.MUTED));
        ui.gap(column, 6);
        TextView value = ui.text("—", 21, Ui.INK);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        column.addView(value);
        parent.addView(column, ui.weight(-2));
        return value;
    }

    void setSim(SimInfo next) {
        if(disposed)return;
        boolean same=sim!=null&&next!=null&&sim.key().equals(next.key());
        sim=next;
        if(same)return;
        stopSchedule();cancelRead();
        subscriptionId=next==null?-1:next.subscriptionId;
        if(next==null||!next.key().equals(restoredKey))history.clear();
        restoredKey=null;cached=false;
        showEmpty("正在等待小区观测");updatePolicy();requestSoon();
    }

    void setForeground(boolean foreground) {
        if (disposed) return;
        boolean changed = this.foreground != foreground;
        this.foreground = foreground;
        if (!foreground) {
            stopSchedule();
            cancelRead();
        }
        renderTrend();
        updatePolicy();
        if (changed && foreground) requestSoon();
    }

    void setBusy(boolean busy) {
        if (disposed) return;
        boolean changed = this.busy != busy;
        this.busy = busy;
        if(busy){stopSchedule();cancelRead();}
        updatePolicy();
        if (changed && !busy) requestSoon();
    }

    void refresh() {
        reader.retry();
        if (sim == null || sim.subscriptionId < 0) host.onSimChanged();
        readCells(false);
    }

    private boolean canRefresh() {
        return !disposed && foreground && !busy && !activity.isDestroyed();
    }

    private void stopSchedule() {
        handler.removeCallbacks(autoRefresh);
        scheduled = false;
    }

    private void schedule(long delay) {
        if (!canRefresh() || reading || scheduled) return;
        scheduled = true;
        handler.postDelayed(autoRefresh, delay);
    }

    private void requestSoon() {
        stopSchedule();
        schedule(0);
    }

    private void updatePolicy() {
        if (!canRefresh()) {
            stopSchedule();
            if (reading) cancelRead();
            autoStatus.setText(busy ? "操作中 · 暂停" : "当前页已暂停");
        } else {
            autoStatus.setText(reading ? "正在更新" : "自动 · 5 秒");
            schedule(INTERVAL_MS);
        }
        Ui.enable(refreshButton, !disposed && !busy && !reading);
        Ui.enable(targetButton, !disposed && !busy && serving != null && serving.hasTarget(serving));
        for (Map.Entry<View,RadioCell> entry : targetViews.entrySet())
            Ui.enable(entry.getKey(), !disposed && !busy && entry.getValue().hasTarget(serving));
    }

    private void readCells(boolean automatic) {
        if(!canRefresh()||reading)return;
        stopSchedule();
        final int token=++generation,requestedSubscription=subscriptionId;
        reading=true;refreshButton.setText("读取中");updatePolicy();
        timeout=()->{
            if(!current(token,requestedSubscription))return;
            reader.cancel();generation++;finishRead();showMissing("读取超时，将自动重试");updatePolicy();
        };
        handler.postDelayed(timeout,TIMEOUT_MS);
        reader.read(sim,result->{
            if(!current(token,requestedSubscription))return;
            finishRead();
            if(result.mappingChanged){showEmpty(result.status);host.onSimChanged();}
            else if(result.cells.isEmpty()){
                showMissing(result.status);
                if(!automatic)host.onNeedPermission();
            }else display(result);
            updatePolicy();
        });
    }

    private boolean current(int token, int subscription) {
        return !disposed && !activity.isDestroyed() && foreground && !busy && reading
                && token == generation && subscription == subscriptionId;
    }

    private void finishRead() {
        if (timeout != null) {
            handler.removeCallbacks(timeout);
            timeout = null;
        }
        reading = false;
        refreshButton.setText("刷新");
    }

    private void cancelRead() {
        reader.cancel();
        generation++;
        finishRead();
        history.markGap();
    }

    private void display(RadioReader.Observation observation) {
        List<RadioCell> cells=observation.cells;
        hasObservations=true;receivedAt=SystemClock.elapsedRealtime();
        int nrCount=0;for(RadioCell cell:cells)if(cell.nr)nrCount++;
        serving = RadioCell.serving(cells);
        host.onServingChanged(serving);
        recordSignal();
        renderServing();
        neighbors.removeAllViews();
        targetViews.clear();
        int count = 0;
        for (RadioCell cell : cells) {
            if (cell == serving) continue;
            if (count++ > 0) {
                LinearLayout.LayoutParams divider = ui.fill(1);
                divider.setMargins(ui.dp(16), 0, ui.dp(16), 0);
                neighbors.addView(ui.divider(), divider);
            }
            addNeighbor(cell.withServingBand(serving));
        }
        neighborCount.setText(count + " 个");
        if (count == 0) emptyNeighbors("本次没有更多可见小区");
        observationStatus.setText(observation.status+" · LTE "+(cells.size()-nrCount)+" / NR "+nrCount+" · "+sampleAge(serving));
    }

    private void renderServing() {
        rat.setText(serving == null ? "未注册" : serving.nr ? "5G NR" : "LTE");
        servingCaption.setText(serving == null ? "未读到可信服务小区" : serving.mapped ? "当前服务小区 · "+serving.source : "服务角色待核实 · "+serving.source);
        if (serving != null && !RadioCell.validIdentity(serving.nr, serving.arfcn, serving.pci))
            servingCaption.setText("当前服务信号 · 小区身份不完整");
        int value = serving == null ? CellInfo.UNAVAILABLE : serving.rsrp;
        int color = SignalHistory.color(value);
        signal.setText(serving == null || !serving.hasSignal() ? "—" : String.valueOf(value));
        signal.setTextColor(color);
        quality.setText(SignalHistory.strength(value));
        quality.setTextColor(color);
        bars.setLevel(serving == null ? 0 : serving.level);
        bars.setColor(color);
        reference.setText((serving != null && serving.nr ? "NR SS-RSRP" : "LTE RSRP") + " · 强弱为应用参考");
        arfcn.setText(serving == null ? "—" : number(serving.arfcn));
        pci.setText(serving == null ? "—" : number(serving.pci));
        band.setText(serving == null ? "—" : serving.bandLabel());
        servingDetails.setText(serving == null ? "" : serving.detailLabel());
        Ui.enable(targetButton, !disposed && !busy && serving != null && serving.hasTarget(serving));
    }

    private void addNeighbor(RadioCell cell) {
        LinearLayout item = ui.row();
        item.setPadding(ui.dp(14), ui.dp(12), ui.dp(12), ui.dp(12));
        item.setBackground(ui.ripple(Ui.CARD, 16));
        item.addView(ui.pill(cell.nr ? "NR" : "LTE", cell.nr ? Ui.GREEN : Ui.MUTED,
                cell.nr ? Ui.SOFT_GREEN : Ui.BG));
        ui.gap(item, 12);
        LinearLayout identity = ui.column();
        TextView identityTitle = ui.text("PCI " + number(cell.pci), 16, Ui.INK);
        identityTitle.setTypeface(null, Typeface.BOLD);
        identity.addView(identityTitle);
        ui.gap(identity, 4);
        identity.addView(ui.text("频点 " + number(cell.arfcn)
                + (cell.registered&&cell.mapped ? " · 已注册" : cell.band > 0 ? " · " + cell.bandLabel() : ""), 11, Ui.MUTED));
        if(!cell.detailLabel().isEmpty())identity.addView(ui.text(cell.detailLabel(),10,Ui.MUTED));
        if(!cell.source.equals("系统"))identity.addView(ui.text(cell.source
                + (cell.hasTarget(serving) && !cell.hasTarget() ? " · 同频候选" : ""),10,Ui.MUTED));
        item.addView(identity, ui.weight(-2));
        LinearLayout strength = ui.column();
        strength.setGravity(Gravity.END);
        strength.addView(ui.text(cell.hasSignal() ? cell.rsrp + " dBm · " + SignalHistory.strength(cell.rsrp)
                : "信号未知", 11, SignalHistory.color(cell.rsrp)));
        ui.gap(strength, 7);
        strength.addView(new SignalChartView.StrengthBar(activity, cell.rsrp),
                new LinearLayout.LayoutParams(ui.dp(77), ui.dp(4)));
        item.addView(strength);
        ui.gap(item, 8);
        item.addView(new LineIcon(activity, LineIcon.ARROW, Ui.MUTED),
                new LinearLayout.LayoutParams(ui.dp(12), ui.dp(16)));
        item.setContentDescription((cell.nr ? "5G" : "LTE") + "小区，频点" + number(cell.arfcn)
                + "，PCI" + number(cell.pci) + "，" + (cell.hasSignal() ? cell.rsrp + " dBm" : "信号未知")
                + "，来源 " + cell.source + "，" + cell.detailLabel() + (cell.hasTarget(serving)?"，填入目标":"，仅供观测"));
        if (cell.hasTarget(serving)) {
            targetViews.put(item,cell);
            item.setOnClickListener(v -> selectTarget(cell));
            Ui.enable(item, !busy);
        } else item.setAlpha(.55f);
        neighbors.addView(item, ui.fill(-2));
    }

    private void selectTarget(RadioCell cell) {
        if (!disposed && !busy && cell != null && cell.hasTarget(serving)) host.onTarget(cell);
    }

    private void showEmpty(String message) {
        hasObservations=false;receivedAt=0;
        serving = null;
        cached = false;
        history.markGap();
        host.onServingChanged(null);
        renderServing();
        targetViews.clear();
        neighbors.removeAllViews();
        emptyNeighbors(message);
        neighborCount.setText("0 个");
        observationStatus.setText(message);
        renderTrend();
    }

    private void showMissing(String message) {
        if(!hasObservations){showEmpty(message);return;}
        history.markGap();renderTrend();
        targetViews.clear();neighbors.removeAllViews();emptyNeighbors(message);neighborCount.setText("0 个");
        observationStatus.setText("上次观测 · 接收 "+Math.max(0,(SystemClock.elapsedRealtime()-receivedAt)/1000)+" 秒前 · "+message);
        host.onServingChanged(serving);
    }

    private void emptyNeighbors(String message) {
        TextView empty = ui.caption(message);
        empty.setPadding(ui.dp(19), ui.dp(23), ui.dp(19), ui.dp(23));
        neighbors.addView(empty, ui.fill(-2));
    }

    private void recordSignal() {
        if (serving == null || !serving.mapped || serving.timestamp<=0 || !serving.hasSignal()) {
            history.markGap();
            cached = false;
        } else {
            cached = !history.add(serving.timestamp, SystemClock.elapsedRealtime(), System.currentTimeMillis(),
                    serving.rsrp, serving.key(), (serving.nr ? "NR" : "LTE") + " PCI " + number(serving.pci));
        }
        renderTrend();
    }

    private String sampleAge(RadioCell cell) {
        long now = SystemClock.elapsedRealtime();
        if (cell == null || cell.timestamp <= 0 || cell.timestamp > now) return "测量时间未知，不绘制趋势";
        long age = (now - cell.timestamp) / 1000;
        if (age <= 1) return "系统采样刚更新";
        return (age > 5 || cached ? "缓存 " : "采样 ") + age + " 秒前";
    }

    private void renderTrend() {
        long now = SystemClock.elapsedRealtime();
        history.trim(now);
        List<SignalHistory.Sample> samples = history.samples();
        chart.setSamples(samples, now, System.currentTimeMillis());
        if (samples.isEmpty()) {
            trendRange.setText("最近 5 分钟");
            trendStatus.setText(serving == null ? "等待真实信号采样" : "缺少有效采样时间，暂不绘点");
            return;
        }
        int weakest = 0, strongest = -999;
        for (SignalHistory.Sample sample : samples) {
            weakest = Math.min(weakest, sample.rsrp);
            strongest = Math.max(strongest, sample.rsrp);
        }
        trendRange.setText("弱 " + weakest + " / 强 " + strongest);
        SignalHistory.Sample last = samples.get(samples.size() - 1);
        String time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(last.wallMillis));
        trendStatus.setText("采样 " + time + " · " + samples.size() + " 点 · "
                + (serving == null ? "待新观测" : sampleAge(serving)));
    }

    void save(Bundle out) {
        Bundle state = new Bundle();
        state.putInt("subscription", subscriptionId);
        state.putInt("slot", sim==null?-1:sim.slot);
        state.putInt("scroll", getScrollY());
        state.putLong("savedElapsed", SystemClock.elapsedRealtime());
        List<SignalHistory.Sample> samples = history.samples();
        int count = samples.size();
        long[] times = new long[count], walls = new long[count];
        int[] values = new int[count];
        String[] keys = new String[count], labels = new String[count];
        boolean[] breaks = new boolean[count];
        for (int i = 0; i < count; i++) {
            SignalHistory.Sample sample = samples.get(i);
            times[i] = sample.elapsedMillis;
            walls[i] = sample.wallMillis;
            values[i] = sample.rsrp;
            keys[i] = sample.key;
            labels[i] = sample.label;
            breaks[i] = sample.breakBefore;
        }
        state.putLongArray("times", times);
        state.putLongArray("walls", walls);
        state.putIntArray("values", values);
        state.putStringArray("keys", keys);
        state.putStringArray("labels", labels);
        state.putBooleanArray("breaks", breaks);
        out.putBundle("cellsPage", state);
    }

    void restore(Bundle in) {
        Bundle state = in == null ? null : in.getBundle("cellsPage");
        if (state == null || disposed) return;
        int savedSubscription = state.getInt("subscription", SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        if (SubscriptionManager.isValidSubscriptionId(subscriptionId) && subscriptionId != savedSubscription) return;
        subscriptionId = savedSubscription;
        int savedSlot=state.getInt("slot",-1);
        restoredKey=savedSubscription>=0&&savedSlot>=0?savedSubscription+":"+savedSlot:null;
        history.clear();
        long now = SystemClock.elapsedRealtime();
        long[] times = state.getLongArray("times"), walls = state.getLongArray("walls");
        int[] values = state.getIntArray("values");
        String[] keys = state.getStringArray("keys"), labels = state.getStringArray("labels");
        boolean[] breaks = state.getBooleanArray("breaks");
        if (now >= state.getLong("savedElapsed", Long.MAX_VALUE) && times != null && walls != null
                && values != null && keys != null && labels != null && breaks != null) {
            int count = Math.min(times.length, Math.min(walls.length, Math.min(values.length,
                    Math.min(keys.length, Math.min(labels.length, breaks.length)))));
            for (int i = 0; i < count; i++) if (keys[i] != null && labels[i] != null)
                history.restore(new SignalHistory.Sample(times[i], walls[i], values[i], keys[i], labels[i], breaks[i]), now);
        }
        renderTrend();
        int scroll = state.getInt("scroll", 0);
        post(() -> { if (!disposed) scrollTo(0, scroll); });
    }

    void dispose() {
        disposed = true;
        foreground = false;
        stopSchedule();
        cancelRead();
        handler.removeCallbacksAndMessages(null);
    }

    private static String number(int value) { return value < 0 || value == CellInfo.UNAVAILABLE ? "—" : String.valueOf(value); }
}
