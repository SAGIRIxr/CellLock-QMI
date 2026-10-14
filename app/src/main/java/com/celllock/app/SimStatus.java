package com.celllock.app;

enum SimStatus {
    PERMISSION_REQUIRED("授权后选择 SIM", "未授予"),
    RESTRICTED("SIM 信息读取受限，请检查权限", "系统限制"),
    NONE("没有可用的 SIM 卡", "已授予"),
    AVAILABLE("", "已授予");

    final String emptyLabel, permissionLabel;

    SimStatus(String emptyLabel, String permissionLabel) {
        this.emptyLabel = emptyLabel;
        this.permissionLabel = permissionLabel;
    }

    static SimStatus resolve(boolean phoneGranted, boolean hasSubscriptions, boolean readySim, boolean readDenied) {
        if (!phoneGranted) return PERMISSION_REQUIRED;
        if (hasSubscriptions) return AVAILABLE;
        return readySim || readDenied ? RESTRICTED : NONE;
    }
}
