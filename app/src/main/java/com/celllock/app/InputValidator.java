package com.celllock.app;

/** 锁小区参数的格式检查。返回错误提示，格式正确返回 null。 */
final class InputValidator {
    static final int MAX_EARFCN = 65535;
    static final int MAX_NR_ARFCN = 3279165;

    private InputValidator() {}

    static String lteCell(String earfcn, String pci) {
        if (parse(earfcn, MAX_EARFCN) < 0) return "EARFCN 需为 0–65535 的整数";
        if (parse(pci, 503) < 0) return "LTE PCI 需为 0–503";
        return null;
    }

    static String nrCell(String arfcn, String pci, String band, int scs) {
        if (parse(arfcn, MAX_NR_ARFCN) < 0) return "NR-ARFCN 需为 0–3279165 的整数";
        if (parse(pci, 1007) < 0) return "NR PCI 需为 0–1007";
        if (parse(band, 512) <= 0) return "请填写 NR 频段（例如 78）";
        return validScs(scs) ? null : "请选择子载波间隔";
    }

    static String nrFrequency(String arfcn, int scs) {
        if (parse(arfcn, MAX_NR_ARFCN) < 0) return "NR-ARFCN 需为 0–3279165 的整数";
        return validScs(scs) ? null : "请选择子载波间隔";
    }

    private static boolean validScs(int scs) {
        return scs == 15 || scs == 30 || scs == 60 || scs == 120 || scs == 240;
    }

    /** 非负整数且不超过 max，否则返回 -1。 */
    static int parse(String text, int max) {
        if (text == null || !text.trim().matches("[0-9]{1,7}")) return -1;
        int value = Integer.parseInt(text.trim());
        return value <= max ? value : -1;
    }
}
