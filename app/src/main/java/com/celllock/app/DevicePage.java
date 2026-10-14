package com.celllock.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.BitSet;
import java.util.List;

final class DevicePage extends ScrollView {
    interface Host {
        void onReadState();
        void onPermissions();
        void onLocation();
        void onExport();
        void onClearLogs();
        void onShowLogs();
    }

    private final Activity activity;
    private final Ui ui;
    private TextView status, lastReadValue, lteBands, nrBands, phonePermission, locationPermission, locationToggle, logsPreview;
    private Button readButton, exportButton, clearButton, logsButton;

    DevicePage(Activity activity, Ui ui, Host host) {
        super(activity);
        this.activity = activity;
        this.ui = ui;
        setFillViewport(true);
        setClipToPadding(false);
        setVerticalScrollBarEnabled(false);
        LinearLayout page = ui.column();
        page.setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20));
        addView(page);

        LinearLayout device = ui.card(page);
        device.addView(ui.title("设备信息"));
        ui.gap(device, 9);
        row(device, "机型", Build.MANUFACTURER + " " + Build.MODEL);
        row(device, "SoC", Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : Build.HARDWARE);
        row(device, "Android", Build.VERSION.RELEASE);
        row(device, "应用版本", version());
        ui.gap(page, 17);

        LinearLayout backend = ui.card(page);
        LinearLayout heading = ui.row();
        heading.addView(ui.title("基带与硬件"), ui.weight(-2));
        readButton = ui.button("读取", true);
        readButton.setTextSize(13);
        heading.addView(readButton, new LinearLayout.LayoutParams(ui.dp(70), ui.dp(36)));
        readButton.setOnClickListener(v -> host.onReadState());
        backend.addView(heading);
        ui.gap(backend, 12);
        status = ui.caption("尚未读取，首次使用需授权 Root");
        backend.addView(status);
        lastReadValue = row(backend, "上次读取", "—");
        backend.addView(ui.divider(), ui.fill(1));
        ui.gap(backend, 12);
        backend.addView(ui.text("LTE 硬件频段", 12, Ui.INK));
        ui.gap(backend, 6);
        lteBands = ui.caption("待读取");
        backend.addView(lteBands);
        ui.gap(backend, 12);
        backend.addView(ui.text("5G NR 硬件频段", 12, Ui.INK));
        ui.gap(backend, 6);
        nrBands = ui.caption("待读取");
        backend.addView(nrBands);
        ui.gap(page, 17);

        LinearLayout permissions = ui.card(page);
        permissions.addView(ui.title("读取权限"));
        ui.gap(permissions, 8);
        phonePermission = row(permissions, "电话信息", "未授予");
        locationPermission = row(permissions, "精确位置", "未授予");
        locationToggle = row(permissions, "系统定位", "未开启");
        ui.gap(permissions, 11);
        LinearLayout permissionActions = ui.row();
        Button grant = ui.button("授予权限", false);
        permissionActions.addView(grant, ui.weight(42));
        grant.setOnClickListener(v -> host.onPermissions());
        ui.gap(permissionActions, 9);
        Button location = ui.button("定位设置", false);
        permissionActions.addView(location, ui.weight(42));
        location.setOnClickListener(v -> host.onLocation());
        permissions.addView(permissionActions);
        ui.gap(page, 17);

        LinearLayout records = ui.card(page);
        LinearLayout recordsHeading = ui.row();
        recordsHeading.addView(ui.title("操作记录"), ui.weight(-2));
        logsButton = ui.button("查看全部", false);
        logsButton.setTextSize(12);
        recordsHeading.addView(logsButton, new LinearLayout.LayoutParams(ui.dp(88), ui.dp(34)));
        logsButton.setOnClickListener(v -> host.onShowLogs());
        records.addView(recordsHeading);
        ui.gap(records, 13);
        logsPreview = ui.text("暂无记录", 11, Ui.MUTED);
        logsPreview.setLineSpacing(ui.dp(5), 1);
        records.addView(logsPreview);
        ui.gap(records, 14);
        LinearLayout recordActions = ui.row();
        exportButton = ui.button("导出诊断", false);
        exportButton.setOnClickListener(v -> host.onExport());
        recordActions.addView(exportButton, ui.weight(40));
        ui.gap(recordActions, 9);
        clearButton = ui.button("清空记录", false);
        clearButton.setOnClickListener(v -> host.onClearLogs());
        recordActions.addView(clearButton, ui.weight(40));
        records.addView(recordActions);
        update(null, "", "", false, null, SimStatus.PERMISSION_REQUIRED);
    }

    void update(ModemState state, String backendStatus, String lastRead, boolean busy, List<String> logs, SimStatus simStatus) {
        status.setText(busy ? "正在与基带通信…" : backendStatus == null || backendStatus.isEmpty()
                ? "尚未读取，首次使用需授权 Root" : backendStatus);
        lastReadValue.setText(lastRead == null || lastRead.isEmpty() ? "—" : lastRead);
        lteBands.setText(hardwareBands(state == null ? null : state.hwLte, state != null && state.hwKnown, "B"));
        nrBands.setText(hardwareBands(state == null ? null : state.hwNr, state != null && state.hwKnown, "n"));
        readButton.setText(busy ? "读取中" : "读取");
        Ui.enable(readButton, !busy);
        boolean phoneGranted = activity.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
        permission(phonePermission, phoneGranted && simStatus != SimStatus.RESTRICTED,
                "已授予", !phoneGranted && simStatus == SimStatus.AVAILABLE ? "未授予 · Root 可读取" : simStatus.permissionLabel);
        permission(locationPermission, activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED, "已授予", "未授予");
        LocationManager location = activity.getSystemService(LocationManager.class);
        permission(locationToggle, location != null && location.isLocationEnabled(), "已开启", "未开启");
        StringBuilder preview = new StringBuilder();
        if (logs != null) for (int i = Math.max(0, logs.size() - 4); i < logs.size(); i++) {
            String entry = logs.get(i);
            if (entry == null) continue;
            int end = entry.indexOf('\n');
            if (preview.length() > 0) preview.append("\n\n");
            preview.append(end >= 0 ? entry.substring(0, end) : entry);
        }
        logsPreview.setText(preview.length() == 0 ? "暂无记录" : preview.toString());
        boolean hasLogs = logs != null && !logs.isEmpty();
        Ui.enable(logsButton, hasLogs);
        Ui.enable(clearButton, !busy && hasLogs);
        Ui.enable(exportButton, !busy);
    }

    private TextView row(LinearLayout parent, String label, String value) {
        LinearLayout row = ui.row();
        row.setPadding(0, ui.dp(9), 0, ui.dp(9));
        row.addView(ui.text(label, 13, Ui.INK), new LinearLayout.LayoutParams(ui.dp(86), -2));
        TextView field = ui.text(value, 13, Ui.MUTED);
        field.setGravity(Gravity.END);
        row.addView(field, ui.weight(-2));
        parent.addView(row, ui.fill(-2));
        return field;
    }

    private String hardwareBands(BitSet bands, boolean known, String prefix) {
        if (!known || bands == null) return "待读取";
        return bands.isEmpty() ? "未报告支持频段" : ModemState.join(bands, prefix);
    }

    private void permission(TextView view, boolean granted, String yes, String no) {
        view.setText(granted ? yes : no);
        view.setTextColor(granted ? Ui.GREEN : Ui.AMBER);
    }

    private String version() {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "未知";
        }
    }
}
