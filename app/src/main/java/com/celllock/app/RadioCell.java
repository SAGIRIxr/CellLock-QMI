package com.celllock.app;

import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellSignalStrengthNr;
import java.util.Collections;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import org.json.JSONObject;

/** 系统 CellInfo 的精简版本：只保留锁定和显示需要的字段。 */
final class RadioCell {
    final boolean nr, registered;
    final int arfcn, pci, band, rsrp, level;
    final long timestamp, identity, receivedAt;
    final String source;
    final boolean mapped;
    final Map<String, String> details;
    private static final String[] DETAIL_NAMES = {"RSRQ", "SINR", "RSSNR", "SNR", "RSSI", "CSI-RSRP", "CSI-RSRQ", "CSI-SINR",
            "TAC", "MCC", "MNC", "PLMN", "运营商", "连接角色", "频段", "频段来源", "带宽", "SRXLEV"};

    private RadioCell(boolean nr, boolean registered, int arfcn, int pci, int band, int rsrp, int level,
                      long timestamp, long identity) {
        this(nr, registered, arfcn, pci, band, rsrp, level, timestamp, identity, "系统", true);
    }

    private RadioCell(boolean nr, boolean registered, int arfcn, int pci, int band, int rsrp, int level,
                      long timestamp, long identity, String source, boolean mapped) {
        this(nr, registered, arfcn, pci, band, rsrp, level, timestamp, identity, source, mapped,
                android.os.SystemClock.elapsedRealtime(), Collections.emptyMap());
    }

    private RadioCell(boolean nr, boolean registered, int arfcn, int pci, int band, int rsrp, int level,
                      long timestamp, long identity, String source, boolean mapped, long receivedAt,
                      Map<String, String> details) {
        this.nr = nr;
        this.registered = registered;
        this.arfcn = available(arfcn, 0, nr ? InputValidator.MAX_NR_ARFCN : 262143);
        this.pci = available(pci, 0, nr ? 1007 : 503);
        this.band = band > 0 && band <= 512 ? band : 0;
        this.rsrp = SignalHistory.validSignal(rsrp) ? rsrp : CellInfo.UNAVAILABLE;
        this.level = this.rsrp == CellInfo.UNAVAILABLE ? 0 : Math.max(0, Math.min(4, level));
        this.timestamp = timestamp;
        this.identity = identity >= 0 && identity <= (nr ? 68719476735L : 268435455L) ? identity : -1;
        this.source = source;
        this.mapped = mapped;
        this.receivedAt = receivedAt;
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    static RadioCell from(CellInfo info) {
        if (info instanceof CellInfoLte) {
            CellInfoLte lte = (CellInfoLte) info;
            CellIdentityLte id = lte.getCellIdentity();
            int[] bands = id.getBands();
            int band = bands.length > 0 ? bands[0] : Bands.lteBandForEarfcn(id.getEarfcn());
            Map<String, String> details = new LinkedHashMap<>();
            metric(details, "RSRQ", lte.getCellSignalStrength().getRsrq(), -34, 3, 1, "dB");
            metric(details, "RSSNR", lte.getCellSignalStrength().getRssnr(), -20, 30, 1, "dB");
            metric(details, "RSSI", lte.getCellSignalStrength().getRssi(), -113, -51, 1, "dBm");
            metric(details, "TAC", id.getTac(), 0, 65535, 1, "");
            metric(details, "带宽", id.getBandwidth(), 1, 20000, 1, "kHz");
            network(details, info, id.getMccString(), id.getMncString(), id.getOperatorAlphaLong(), bands);
            return new RadioCell(false, info.isRegistered(), id.getEarfcn(), id.getPci(), band,
                    lte.getCellSignalStrength().getRsrp(), lte.getCellSignalStrength().getLevel(),
                    info.getTimestampMillis(), id.getCi()).withDetails(details);
        }
        if (info instanceof CellInfoNr) {
            CellInfoNr nr = (CellInfoNr) info;
            CellIdentityNr id = (CellIdentityNr) nr.getCellIdentity();
            CellSignalStrengthNr signal = (CellSignalStrengthNr) nr.getCellSignalStrength();
            int[] bands = id.getBands();
            int band = bands.length > 0 ? bands[0] : Bands.nrBandForArfcn(id.getNrarfcn(), null);
            Map<String, String> details = new LinkedHashMap<>();
            metric(details, "RSRQ", signal.getSsRsrq(), -43, 20, 1, "dB");
            metric(details, "SINR", signal.getSsSinr(), -23, 40, 1, "dB");
            metric(details, "CSI-RSRP", signal.getCsiRsrp(), -156, -31, 1, "dBm");
            metric(details, "CSI-RSRQ", signal.getCsiRsrq(), -43, 20, 1, "dB");
            metric(details, "CSI-SINR", signal.getCsiSinr(), -23, 40, 1, "dB");
            metric(details, "TAC", id.getTac(), 0, 16777215, 1, "");
            network(details, info, id.getMccString(), id.getMncString(), id.getOperatorAlphaLong(), bands);
            return new RadioCell(true, info.isRegistered(), id.getNrarfcn(), id.getPci(), band,
                    signal.getSsRsrp(), signal.getLevel(), info.getTimestampMillis(), id.getNci()).withDetails(details);
        }
        return null;
    }

    boolean fresh() {
        long now = android.os.SystemClock.elapsedRealtime();
        return timestamp > 0 && timestamp <= now && now - timestamp <= 60_000;
    }

    boolean hasTarget() {
        return hasMappedIdentity() && (nr || arfcn <= InputValidator.MAX_EARFCN);
    }

    boolean hasMappedIdentity() { return mapped && fresh() && validIdentity(nr, arfcn, pci); }

    boolean hasTarget(RadioCell serving) {
        if (!validIdentity(nr, arfcn, pci) || !nr && arfcn > InputValidator.MAX_EARFCN) return false;
        if (hasTarget()) return true;
        return recent() && serving != null && serving.registered && serving.recent()
                && nr == serving.nr && serving.band > 0 && targetBand(serving) == serving.band && arfcn == serving.arfcn;
    }

    int targetBand(RadioCell serving) {
        if (!nr || "系统".equals(details.get("频段来源")) || serving == null || !serving.nr
                || !serving.registered || !serving.recent() || serving.band <= 0 || arfcn != serving.arfcn) return band;
        BitSet preferred = new BitSet();
        preferred.set(serving.band);
        int matched = Bands.nrBandForArfcn(arfcn, preferred);
        return matched == serving.band ? matched : band;
    }

    RadioCell withServingBand(RadioCell serving) {
        int matched = targetBand(serving);
        if (matched == band) return this;
        Map<String, String> values = new LinkedHashMap<>(details);
        values.put("频段来源", "服务频段匹配");
        return new RadioCell(nr, registered, arfcn, pci, matched, rsrp, level, timestamp, identity,
                source, mapped, receivedAt, values);
    }

    boolean recent() {
        long now = android.os.SystemClock.elapsedRealtime();
        return receivedAt > 0 && receivedAt <= now && now - receivedAt < DiagnosticCells.WINDOW_MS;
    }

    static RadioCell serving(java.util.List<RadioCell> cells) {
        RadioCell best = null;
        for (RadioCell cell : cells) if (cell.registered && (best == null || cell.rank() > best.rank())) best = cell;
        return best;
    }

    private int rank() { return (nr ? 4 : 0) + (mapped ? 2 : 0) + (validIdentity(nr, arfcn, pci) ? 1 : 0); }

    private static int available(int value, int min, int max) {
        return value >= min && value <= max ? value : CellInfo.UNAVAILABLE;
    }

    static void metric(Map<String, String> details, String name, int value, int min, int max, int scale, String unit) {
        if (value >= min && value <= max)
            details.put(name, (scale == 1 ? String.valueOf(value) : String.format(Locale.ROOT, "%.1f", value / (float) scale))
                    + (unit.isEmpty() ? "" : " " + unit));
    }

    private static void network(Map<String, String> details, CellInfo info, String mcc, String mnc,
                                CharSequence operator, int[] bands) {
        if (mcc != null && mcc.matches("[0-9]{3}")) details.put("MCC", mcc);
        if (mnc != null && mnc.matches("[0-9]{2,3}")) details.put("MNC", mnc);
        if (operator != null && operator.length() > 0 && operator.length() <= 128) details.put("运营商", operator.toString());
        int connection = info.getCellConnectionStatus();
        if (connection >= 0 && connection <= 2) details.put("连接角色", new String[]{"未连接", "主服务", "辅服务"}[connection]);
        StringBuilder label = new StringBuilder();
        for (int band : bands) if (band > 0 && band <= 512) {
            if (label.length() > 0) label.append(" / ");
            label.append(band);
        }
        if (label.length() > 0) details.put("频段", label.toString());
        details.put("频段来源", label.length() > 0 ? "系统" : "频点推算");
    }

    static boolean validIdentity(boolean nr, int arfcn, int pci) {
        return arfcn >= 0 && arfcn <= (nr ? InputValidator.MAX_NR_ARFCN : 262143) && pci >= 0 && pci <= (nr ? 1007 : 503);
    }

    static RadioCell measured(boolean nr, boolean registered, int arfcn, int pci, int rsrp, String source) {
        if (!(arfcn == CellInfo.UNAVAILABLE || arfcn >= 0 && arfcn <= (nr ? InputValidator.MAX_NR_ARFCN : 262143))
                || !(pci == CellInfo.UNAVAILABLE || pci == 65535 || pci >= 0 && pci <= (nr ? 1007 : 503)))
            throw new IllegalArgumentException("非法小区身份");
        int signal = SignalHistory.validSignal(rsrp) ? rsrp : CellInfo.UNAVAILABLE;
        int level = signal == CellInfo.UNAVAILABLE ? 0 : signal >= -85 ? 4 : signal >= -95 ? 3 : signal >= -105 ? 2 : 1;
        return new RadioCell(nr, registered, arfcn, pci,
                nr ? Bands.nrBandForArfcn(arfcn, null) : Bands.lteBandForEarfcn(arfcn),
                signal, level, 0, -1, source, false);
    }

    JSONObject json() throws Exception {
        return new JSONObject().put("nr", nr).put("registered", registered).put("arfcn", arfcn).put("pci", pci)
                .put("band", band).put("rsrp", rsrp).put("level", level).put("timestamp", timestamp).put("identity", identity)
                .put("details", new JSONObject(details));
    }

    static RadioCell parse(JSONObject value, String source) throws Exception {
        boolean nr = value.getBoolean("nr");
        int arfcn = value.getInt("arfcn"), pci = value.getInt("pci");
        Map<String, String> details = new LinkedHashMap<>();
        JSONObject extra = value.optJSONObject("details");
        if (extra != null) for (String name : DETAIL_NAMES) {
            Object field = extra.opt(name);
            if (field instanceof String && !((String) field).isEmpty() && ((String) field).length() <= 128)
                details.put(name, (String) field);
        }
        return new RadioCell(nr, value.getBoolean("registered"), arfcn, pci, value.optInt("band"),
                value.optInt("rsrp", CellInfo.UNAVAILABLE), Math.max(0, Math.min(4, value.optInt("level"))),
                value.getLong("timestamp"), value.optLong("identity", -1), source, true).withDetails(details);
    }

    RadioCell withSource(String source, boolean mapped) {
        return copy(source, mapped, receivedAt, details);
    }

    RadioCell withReceivedAt(long time) { return copy(source, mapped, time, details); }
    RadioCell withDetails(Map<String, String> values) { return copy(source, mapped, receivedAt, values); }
    RadioCell withIdentity(long value) {
        return new RadioCell(nr, registered, arfcn, pci, band, rsrp, level, timestamp, value,
                source, mapped, receivedAt, details);
    }
    private RadioCell copy(String source, boolean mapped, long time, Map<String, String> values) {
        return new RadioCell(nr, registered, arfcn, pci, band, rsrp, level, timestamp, identity, source, mapped, time, values);
    }

    String detailLabel() {
        StringBuilder text = new StringBuilder();
        if (identity >= 0) text.append(nr ? "NCI " : "CI ").append(identity);
        for (Map.Entry<String, String> field : details.entrySet()) {
            if (text.length() > 0) text.append(" · ");
            text.append(field.getKey()).append(" ").append(field.getValue());
        }
        return text.toString();
    }

    boolean hasSignal() {
        return SignalHistory.validSignal(rsrp);
    }

    String key() {
        return source + ":" + (nr ? "NR" : "LTE") + ":" + arfcn + ":" + pci + ":" + identity;
    }

    String bandLabel() {
        return band <= 0 ? "—" : (nr ? "n" : "B") + band;
    }
}
