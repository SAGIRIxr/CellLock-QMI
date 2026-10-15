package com.celllock.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.BitSet;
import java.util.ArrayList;
import java.util.List;

/** 网络制式掩码，与基带 mode preference 的位定义一致。 */
final class Rat {
    static final int GSM = 0x04, WCDMA = 0x08, LTE = 0x10, NR = 0x40;
    static final int KNOWN = GSM | WCDMA | LTE | NR;

    private Rat() {}

    static String describe(int mask) {
        mask &= KNOWN;
        switch (mask) {
            case 0: return "未知";
            case NR: return "仅 5G";
            case LTE: return "仅 4G";
            case WCDMA: return "仅 3G";
            case GSM: return "仅 2G";
            case LTE | NR: return "4G / 5G";
            case WCDMA | LTE: return "3G / 4G";
            case GSM | WCDMA | LTE: return "2G / 3G / 4G";
            case KNOWN: return "全部制式";
            default:
                StringBuilder out = new StringBuilder();
                if ((mask & GSM) != 0) out.append("2G / ");
                if ((mask & WCDMA) != 0) out.append("3G / ");
                if ((mask & LTE) != 0) out.append("4G / ");
                if ((mask & NR) != 0) out.append("5G / ");
                return out.substring(0, out.length() - 3);
        }
    }

    /** 转成后端参数，如 "lte,nr"。 */
    static String arg(int mask) {
        StringBuilder out = new StringBuilder();
        if ((mask & GSM) != 0) out.append("gsm,");
        if ((mask & WCDMA) != 0) out.append("wcdma,");
        if ((mask & LTE) != 0) out.append("lte,");
        if ((mask & NR) != 0) out.append("nr,");
        return out.length() == 0 ? "" : out.substring(0, out.length() - 1);
    }

    static String nrMode(int mode) {
        switch (mode) {
            case 0: return "SA + NSA";
            case 1: return "仅 NSA";
            case 2: return "仅 SA";
            default: return "未知";
        }
    }
}

/** 一次后端调用读到的基带状态。字段为 null 表示没读到，不等于“没有锁定”。 */
final class ModemState {
    /** LTE 小区锁：每项 {earfcn, pci}。 */
    int[][] lteCells;
    boolean lteKnown;

    boolean nrKnown, nrAckOnly;
    int nrType = -1, nrPci = -1, nrBand, nrScs;
    long nrArfcn = -1;

    boolean prefKnown;
    int rat = -1, nrMode = -1;
    String legacyMask;
    BitSet lte, nrSa, nrNsa, gsm, wcdma;

    boolean hwKnown;
    BitSet hwLte, hwNr, hwGsm, hwWcdma;

    static final int NR_LOCK_PCI = 0, NR_LOCK_ARFCN = 1, NR_LOCK_NONE = 2;

    static ModemState parse(JSONObject json) {
        ModemState s = new ModemState();
        JSONObject lte = json.optJSONObject("lteCell");
        if (lte != null && lte.optBoolean("known")) {
            s.lteKnown = true;
            JSONArray cells = lte.optJSONArray("cells");
            if (cells == null) s.lteKnown = false;
            int n = cells == null ? 0 : cells.length();
            s.lteCells = new int[n][];
            for (int i = 0; i < n; i++) {
                JSONObject c = cells.optJSONObject(i);
                if (c == null || c.optInt("earfcn", -1) < 0 || c.optInt("earfcn", -1) > 65535 || c.optInt("pci", -1) < 0 || c.optInt("pci", -1) > 503) {
                    s.lteKnown = false;
                    s.lteCells = null;
                    break;
                }
                s.lteCells[i] = new int[]{c.optInt("earfcn"), c.optInt("pci")};
            }
        }
        JSONObject nr = json.optJSONObject("nrCell");
        if (nr != null) {
            s.nrKnown = nr.optBoolean("known");
            s.nrAckOnly = nr.optBoolean("ackOnly");
            s.nrType = nr.optInt("type", -1);
            if (s.nrType == NR_LOCK_PCI) {
                s.nrArfcn = nr.optLong("arfcn", -1);
                s.nrPci = nr.optInt("pci", -1);
                s.nrBand = nr.optInt("band");
                s.nrScs = nr.optInt("scs");
            } else if (s.nrType == NR_LOCK_ARFCN) {
                JSONArray list = nr.optJSONArray("arfcns");
                JSONObject first = list == null ? null : list.optJSONObject(0);
                if (first != null) {
                    s.nrArfcn = first.optLong("arfcn", -1);
                    s.nrScs = first.optInt("scs");
                }
            }
            if (s.nrKnown && s.nrType == NR_LOCK_PCI)
                s.nrKnown = s.nrArfcn >= 0 && s.nrArfcn <= 3279165 && s.nrPci >= 0 && s.nrPci <= 1007
                        && s.nrBand > 0 && s.nrBand <= 512 && validScs(s.nrScs);
            if (s.nrKnown && s.nrType == NR_LOCK_ARFCN) {
                JSONArray list = nr.optJSONArray("arfcns");
                s.nrKnown = list != null && list.length() > 0;
                for (int i = 0; s.nrKnown && i < list.length(); i++) {
                    JSONObject cell = list.optJSONObject(i);
                    s.nrKnown = cell != null && cell.optLong("arfcn", -1) >= 0 && cell.optLong("arfcn", -1) <= 3279165
                            && validScs(cell.optInt("scs", -1));
                }
            }
            if (s.nrType < 0 || s.nrType > 4) s.nrKnown = false;
        }
        JSONObject pref = json.optJSONObject("pref");
        if (pref != null && pref.optBoolean("known")) {
            s.prefKnown = true;
            s.rat = pref.optInt("rat", -1);
            s.nrMode = pref.optInt("nrMode", -1);
            s.legacyMask = pref.isNull("legacyMask") ? null : pref.optString("legacyMask", null);
            s.lte = bits(pref, "lte");
            s.nrSa = bits(pref, "nrSa");
            s.nrNsa = bits(pref, "nrNsa");
            s.gsm = bits(pref, "gsm");
            s.wcdma = bits(pref, "wcdma");
        }
        JSONObject hw = json.optJSONObject("hardware");
        if (hw != null && hw.optBoolean("known")) {
            s.hwKnown = true;
            s.hwLte = bits(hw, "lte");
            s.hwNr = bits(hw, "nr");
            s.hwGsm = bits(hw, "gsm");
            s.hwWcdma = bits(hw, "wcdma");
        }
        return s;
    }

    /** 后端有些命令不读硬件频段，沿用上一次的结果。 */
    void inheritHardware(ModemState previous) {
        if (hwKnown || previous == null || !previous.hwKnown) return;
        hwKnown = true;
        hwLte = previous.hwLte;
        hwNr = previous.hwNr;
        hwGsm = previous.hwGsm;
        hwWcdma = previous.hwWcdma;
    }

    boolean hasCellData() {
        return lteKnown || nrKnown || prefKnown;
    }

    boolean lteCellLocked() {
        return lteKnown && lteCells.length > 0;
    }

    boolean nrCellLocked() {
        return nrKnown && nrType != NR_LOCK_NONE;
    }

    String lteCellSummary() {
        if (!lteKnown) return "未知";
        if (lteCells.length == 0) return "未锁定";
        StringBuilder out = new StringBuilder();
        for (int[] c : lteCells) out.append(out.length() == 0 ? "" : "、").append(c[0]).append(" / PCI ").append(c[1]);
        return out.toString();
    }

    String nrCellSummary() {
        if (!nrKnown) return nrAckOnly ? "未锁定或无法读取" : "未知";
        switch (nrType) {
            case NR_LOCK_NONE: return "未锁定";
            case NR_LOCK_PCI: return nrArfcn + " / PCI " + nrPci + " · n" + nrBand;
            case NR_LOCK_ARFCN: return "频点 " + nrArfcn + " · " + nrScs + " kHz";
            default: return "其他锁定方式（" + nrType + "）";
        }
    }

    /** 当前偏好相对硬件支持的频段，用于“全部 / 已锁 N 个”的摘要。 */
    static String bandSummary(BitSet selected, BitSet hardware, String prefix) {
        if (selected == null) return "未知";
        BitSet effective = (BitSet) selected.clone();
        if (hardware != null && !hardware.isEmpty()) {
            effective.and(hardware);
            if (effective.equals(hardware)) return "全部";
        }
        if (effective.isEmpty()) return "无";
        return join(effective, prefix);
    }

    static String join(BitSet set, String prefix) {
        StringBuilder out = new StringBuilder();
        for (int b = set.nextSetBit(0); b >= 0; b = set.nextSetBit(b + 1))
            out.append(out.length() == 0 ? "" : " ").append(prefix).append(b);
        return out.toString();
    }

    static String arg(BitSet set) {
        StringBuilder out = new StringBuilder();
        for (int b = set.nextSetBit(0); b >= 0; b = set.nextSetBit(b + 1))
            out.append(out.length() == 0 ? "" : ",").append(b);
        return out.toString();
    }

    private static BitSet bits(JSONObject parent, String key) {
        JSONArray list = parent.optJSONArray(key);
        if (list == null) return null;
        BitSet set = new BitSet();
        for (int i = 0; i < list.length(); i++) {
            int band = list.optInt(i, -1);
            if (band <= 0 || band > 1900) return null;
            set.set(band);
        }
        return set;
    }

    private static boolean validScs(int scs) {
        return scs == 15 || scs == 30 || scs == 60 || scs == 120 || scs == 240;
    }
}

/** 后端一次调用的结果。 */
final class ModemResult {
    final boolean ok;
    final String stage, message, raw;
    final boolean mutated;
    final ModemState state;
    boolean nrCombinedOnly;
    final List<ResultItem> items = new ArrayList<>();

    ModemResult(boolean ok, String stage, String message, boolean mutated, ModemState state, String raw) {
        this.ok = ok;
        this.stage = stage;
        this.message = message == null ? "未返回说明" : message;
        this.mutated = mutated;
        this.state = state;
        this.raw = raw == null ? "" : raw;
    }

    static ModemResult failure(String stage, String message, String raw) {
        return new ModemResult(false, stage, message, false, null, raw);
    }

    static ModemResult parse(JSONObject json) {
        ModemState state = json.has("pref") || json.has("hardware") || json.has("lteCell") || json.has("nrCell") ? ModemState.parse(json) : null;
        ModemResult result = new ModemResult(json.optBoolean("ok"), json.optString("stage"), json.optString("message"),
                json.optBoolean("mutated"), state, json.toString());
        boolean nrConfirmed = result.ok;
        JSONArray steps = json.optJSONArray("steps");
        if (steps != null) for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step != null) {
                ResultItem item = new ResultItem(stepName(step.optString("name")), step.optString("status", "unknown"), "");
                result.items.add(item);
                if (step.optString("name").equals("nr") && item.confirmed()) nrConfirmed = true;
            }
        }
        result.nrCombinedOnly = json.optBoolean("nrCombinedOnly") && nrConfirmed;
        return result;
    }

    static String stepName(String name) {
        switch (name) {
            case "legacy": return "2G / 3G 频段";
            case "lte": return "LTE 频段";
            case "nr": return "5G SA＋NSA 频段";
            case "nrMode": return "5G 组网";
            case "rat": return "网络制式";
            case "lteCell": return "LTE 小区锁";
            case "nrCell": return "5G 小区锁";
            default: return name;
        }
    }
}

final class ResultItem {
    final String name, status, detail;
    ResultItem(String name, String status, String detail) { this.name=name; this.status=status; this.detail=detail; }
    boolean confirmed() { return status.equals("verified") || status.equals("unchanged") || status.equals("read"); }
    String label() {
        switch (status) {
            case "verified": return "回读已确认";
            case "unchanged": return "无需操作";
            case "read": return "已读取";
            case "rejected": return "请求被拒绝";
            case "unverified": case "mismatch": return "回读与目标不一致";
            case "unknown": case "transport": case "interrupted": return "状态未确认";
            default: return "未完成";
        }
    }
    JSONObject json() throws org.json.JSONException { return new JSONObject().put("name",name).put("status",status).put("detail",detail); }
}

/** UI、日志与旋转恢复共用逐项结果，不从中文回执反向判断成败。 */
final class OperationReport {
    final String title, target;
    final List<ResultItem> items = new ArrayList<>();
    String details = "";
    String restoreStatus = "";
    OperationReport(String title, String target) { this.title=title; this.target=target; }
    void add(String name, ModemResult result) {
        if (result.items.isEmpty()) {
            String status=result.ok ? (result.stage.equals("state") ? "read" : result.mutated ? "verified" : "unchanged") : result.stage;
            items.add(new ResultItem(name,status,result.message));
        } else items.addAll(result.items);
        details += (details.isEmpty()?"":"\n\n") + name + "\n" + (result.raw.isEmpty()?result.stage+"："+result.message:result.raw);
        if(details.length()>24000)details="较早回执已省略，可导出诊断查看近期完整回执。\n"+details.substring(details.length()-24000);
    }
    boolean ok() { return !items.isEmpty() && items.stream().allMatch(ResultItem::confirmed); }
    String outcome() {
        if (ok()) return items.stream().allMatch(i->i.status.equals("read"))?"已读取":"已确认";
        if (items.stream().anyMatch(ResultItem::confirmed)) return "部分完成";
        if (!items.isEmpty() && items.stream().allMatch(i->i.status.equals("rejected"))) return "请求被拒绝";
        return items.stream().anyMatch(i->i.status.equals("unknown")||i.status.equals("transport")||i.status.equals("interrupted")) ? "结果未确认" : "未完成";
    }
    String dialogTitle() {
        if(restoreStatus.equals("matched"))return ok()?"初始配置已恢复":"配置已恢复 · 仍有未完成项";
        if(restoreStatus.equals("mismatch"))return "尚未完整恢复初始配置";
        return title+" · "+outcome();
    }
    String summary() {
        StringBuilder text=new StringBuilder();
        for (ResultItem item:items) { if (text.length()>0) text.append('\n'); text.append(item.name).append("：").append(item.label()); if (!item.detail.isEmpty()) text.append(" · ").append(item.detail); }
        return text.toString();
    }
    String encode() {
        try { JSONArray rows=new JSONArray();for(ResultItem item:items)rows.put(item.json());return new JSONObject().put("title",title).put("target",target).put("items",rows).put("details",details).put("restoreStatus",restoreStatus).toString(); }
        catch(org.json.JSONException error) { throw new IllegalStateException(error); }
    }
    static OperationReport decode(String raw) {
        try { JSONObject json=new JSONObject(raw);OperationReport report=new OperationReport(json.optString("title"),json.optString("target"));report.details=json.optString("details");report.restoreStatus=json.optString("restoreStatus");JSONArray rows=json.optJSONArray("items");if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null)report.items.add(new ResultItem(row.optString("name"),row.optString("status","unknown"),row.optString("detail")));}return report; }
        catch(org.json.JSONException error) { return null; }
    }
}

final class LogRecord {
    final String time, text;
    final OperationReport report;
    LogRecord(String time,String text,OperationReport report) { this.time=time;this.text=text;this.report=report; }
    String display() { return time+"  "+text+(report==null?"":" · "+report.outcome()+"\n"+report.summary()); }
    String encode() {
        try { return new JSONObject().put("time",time).put("text",text).put("report",report==null?"":report.encode()).toString(); }
        catch(org.json.JSONException error) { throw new IllegalStateException(error); }
    }
    static LogRecord decode(String raw) {
        try { JSONObject json=new JSONObject(raw);return new LogRecord(json.optString("time"),json.optString("text"),OperationReport.decode(json.optString("report"))); }
        catch(org.json.JSONException error) { return new LogRecord("",raw,null); }
    }
}
