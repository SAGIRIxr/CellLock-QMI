package com.celllock.app;

import android.telephony.CellInfo;
import org.json.JSONObject;
import org.json.JSONArray;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 固定公开格式的有界解码，不从单独频点猜测 NR 小区或 SIM。 */
final class MeasurementDecoder {
    static final class Bytes {
        final ByteBuffer data;
        Bytes(byte[] bytes) { data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }
        int u8() { need(1); return data.get() & 255; }
        int u16() { need(2); return data.getShort() & 65535; }
        int i16() { need(2); return data.getShort(); }
        long u32() { need(4); return data.getInt() & 0xffffffffL; }
        long i64() { need(8); return data.getLong(); }
        void skip(int count) { need(count); data.position(data.position() + count); }
        void need(int count) { if (count < 0 || count > data.remaining()) throw new IllegalArgumentException("测量包截断"); }
        void done() { if (data.hasRemaining()) throw new IllegalArgumentException("测量包有多余数据"); }
    }

    static byte[] hex(String text) {
        if (text.length() > 131070 || (text.length() & 1) != 0) throw new IllegalArgumentException("非法数据长度");
        byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int hi = Character.digit(text.charAt(i * 2), 16), lo = Character.digit(text.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("非法十六进制数据");
            bytes[i] = (byte) (hi * 16 + lo);
        }
        return bytes;
    }

    static boolean success(JSONObject record) {
        return record.optInt("transport", -1) == 0 && record.optInt("result", -1) == 0 && record.optInt("error", -1) == 0;
    }

    static List<JSONObject> records(String output) throws Exception {
        List<JSONObject> values = new ArrayList<>();
        for (String line : output.split("\\r?\\n")) if (line.trim().startsWith("{")) values.add(new JSONObject(line));
        return values;
    }

    static List<SimInfo> uim(String output) throws Exception {
        List<SimInfo> sims = new ArrayList<>();
        for (JSONObject record : records(output)) {
            if (!success(record) || record.optInt("service") != 11 || record.optInt("message") != 0x47) continue;
            JSONObject tlvs = record.getJSONObject("tlvs");
            if (!tlvs.has("10")) continue;
            Bytes b = new Bytes(hex(tlvs.getString("10")));
            int count = b.u8();
            if (count > 8) throw new IllegalArgumentException("卡槽数量超限");
            for (int slot = 0; slot < count; slot++) {
                long card = b.u32(), active = b.u32();
                b.u8(); b.skip(b.u8());
                if (card == 2 && active == 1) sims.add(new SimInfo(-1, slot, "SIM " + (slot + 1) + " · 卡已插入", "QMI UIM"));
            }
            b.done();
        }
        return sims;
    }

    static List<RadioCell> nas(String output, int slot) throws Exception {
        List<RadioCell> cells = new ArrayList<>();
        boolean bound = false;
        for (JSONObject record : records(output)) {
            if (record.optInt("service") != 3 || record.optInt("slot", -1) != slot) continue;
            if (record.optInt("message") == 0x45) { bound = success(record); continue; }
            if (!bound || !success(record) || record.optInt("message") != 0x43) continue;
            JSONObject tlvs = record.getJSONObject("tlvs");
            if (tlvs.has("13")) {
                Bytes b = new Bytes(hex(tlvs.getString("13")));
                b.u8();
                Map<String, String> network = plmn(b);
                int tac = b.u16(); long identity = b.u32();
                RadioCell.metric(network, "TAC", tac, 0, 65534, 1, "");
                int arfcn = b.u16(), serving = b.u16(); b.skip(4);
                if (tlvs.has("27")) { Bytes ext = new Bytes(hex(tlvs.getString("27"))); arfcn = frequency(ext.u32()); ext.done(); }
                int count = b.u8();
                boolean found = false;
                for (int i = 0; i < count; i++) {
                    RadioCell cell = lte(b, arfcn, serving);
                    if (cell.pci == serving || serving == 65535 && cell.pci == CellInfo.UNAVAILABLE) {
                        Map<String, String> details = new LinkedHashMap<>(cell.details);
                        details.putAll(network);
                        cell = cell.withIdentity(identity).withDetails(details); found = true;
                    }
                    cells.add(cell);
                }
                b.done();
                if (!found) cells.add(RadioCell.measured(false, true, arfcn, serving, CellInfo.UNAVAILABLE, "QMI NAS")
                        .withIdentity(identity).withDetails(network));
            }
            if (tlvs.has("14")) {
                Bytes b = new Bytes(hex(tlvs.getString("14")));
                b.u8(); int count = b.u8();
                Bytes ext = tlvs.has("28") ? new Bytes(hex(tlvs.getString("28"))) : null;
                if (ext != null && ext.u8() != count) throw new IllegalArgumentException("频点数量不一致");
                for (int i = 0; i < count; i++) {
                    int arfcn = b.u16(); b.skip(3);
                    if (ext != null) arfcn = frequency(ext.u32());
                    int measured = b.u8();
                    for (int j = 0; j < measured; j++) cells.add(lte(b, arfcn, -1));
                }
                b.done(); if (ext != null) ext.done();
            }
            if (tlvs.has("2f")) {
                int arfcn = CellInfo.UNAVAILABLE;
                if (tlvs.has("2e")) {
                    Bytes frequency = new Bytes(hex(tlvs.getString("2e")));
                    arfcn = frequency(frequency.u32()); frequency.done();
                }
                Bytes b = new Bytes(hex(tlvs.getString("2f")));
                Map<String, String> details = plmn(b);
                int tac = b.u8() << 16 | b.u8() << 8 | b.u8(); long identity = b.i64();
                RadioCell.metric(details, "TAC", tac, 0, 16777214, 1, "");
                int pci = b.u16(), rsrq = b.i16(), rsrp = Math.round(b.i16() / 10f), snr = b.i16(); b.done();
                RadioCell.metric(details, "RSRQ", rsrq, -430, 200, 10, "dB");
                RadioCell.metric(details, "SNR", snr, -230, 400, 10, "dB");
                cells.add(RadioCell.measured(true, true, arfcn, pci, rsrp, "QMI NAS").withIdentity(identity).withDetails(details));
            } else if (tlvs.has("2e")) {
                Bytes frequency = new Bytes(hex(tlvs.getString("2e")));
                int arfcn = frequency(frequency.u32()); frequency.done();
                cells.add(RadioCell.measured(true, false, arfcn, CellInfo.UNAVAILABLE, CellInfo.UNAVAILABLE, "QMI NAS · 仅频点"));
            }
        }
        return unique(cells);
    }

    private static RadioCell lte(Bytes b, int arfcn, int serving) {
        int pci = b.u16(), rsrq = b.i16(), rsrp = Math.round(b.i16() / 10f), rssi = b.i16(), rxlev = b.i16();
        Map<String, String> details = new LinkedHashMap<>();
        RadioCell.metric(details, "RSRQ", rsrq, -340, 30, 10, "dB");
        RadioCell.metric(details, "RSSI", rssi, -1600, -200, 10, "dBm");
        RadioCell.metric(details, "SRXLEV", rxlev, -32767, 32767, 1, "");
        return RadioCell.measured(false, pci == serving && pci <= 503, arfcn, pci, rsrp, "QMI NAS").withDetails(details);
    }

    private static int frequency(long value) {
        return value == 0xffffffffL ? CellInfo.UNAVAILABLE : Math.toIntExact(value);
    }

    private static Map<String, String> plmn(Bytes b) {
        StringBuilder digits = new StringBuilder();
        boolean valid = true;
        for (int i = 0; i < 3; i++) {
            int value = b.u8();
            for (int shift = 0; shift <= 4; shift += 4) {
                int digit = value >> shift & 15;
                if (digit <= 9) digits.append(digit);
                else if (digit != 15) valid = false;
            }
        }
        Map<String, String> details = new LinkedHashMap<>();
        if (valid && digits.length() >= 5 && !digits.toString().startsWith("000")) details.put("PLMN", digits.toString());
        return details;
    }

    static List<RadioCell> nrBatch(JSONObject result) throws Exception {
        if (result.has("error")) return new ArrayList<>();
        JSONArray logs = result.getJSONArray("logs");
        if (logs.length() > 32) throw new IllegalArgumentException("诊断包数量超限");
        LinkedHashMap<String, RadioCell> cells = new LinkedHashMap<>();
        int bytes = 0;
        for (int i = 0; i < logs.length(); i++) {
            byte[] packet = hex(logs.getString(i));
            bytes += packet.length;
            if (bytes > 96 * 1024) throw new IllegalArgumentException("诊断数据超限");
            for (RadioCell cell : nr(packet)) cells.put(cell.key(), cell);
            if (cells.size() > 512) throw new IllegalArgumentException("诊断小区数量超限");
        }
        return new ArrayList<>(cells.values());
    }

    static List<RadioCell> nr(byte[] packet) {
        Bytes b = new Bytes(packet);
        if (b.u16() != packet.length || b.u16() != 0xb97f) throw new IllegalArgumentException("非法 NR 日志头");
        b.skip(8);
        if (b.u16() != 7 || b.u16() != 2) throw new IllegalArgumentException("尚未支持的 NR 测量版本");
        int carriers = b.u8(); b.skip(11);
        if (carriers < 1 || carriers > 8) throw new IllegalArgumentException("非法载波数量");
        List<RadioCell> cells = new ArrayList<>();
        for (int layer = 0; layer < carriers; layer++) {
            int arfcn = frequency(b.u32()), count = b.u8(), index = b.u8(), serving = b.u16(); b.skip(24);
            if (!(arfcn == CellInfo.UNAVAILABLE || RadioCell.validIdentity(true, arfcn, 0))
                    || !(serving == 65535 || serving <= 1007)) throw new IllegalArgumentException("非法载波身份");
            if (count == 0 || count == 255) count = index > 0 && index < 255 ? index : 0;
            if (count > 64) throw new IllegalArgumentException("小区数量超限");
            for (int i = 0; i < count; i++) {
                int pci = b.u16(); b.u16(); int beams = b.u8(); b.skip(3);
                long rsrp = b.u32(), rsrq = b.u32();
                if (beams > 64) throw new IllegalArgumentException("波束数量超限");
                b.skip(beams * 44);
                Map<String, String> details = new LinkedHashMap<>();
                if (rsrq != 0) RadioCell.metric(details, "RSRQ", (short) rsrq, -5504, 2560, 128, "dB");
                // shortcut: NR 日志没有可靠 SIM/主辅角色，同频匹配只允许填入候选，不冒充服务小区。
                cells.add(RadioCell.measured(true, false, arfcn, pci,
                        rsrp == 0 ? CellInfo.UNAVAILABLE : Math.round((short) rsrp / 128f), "DIAG NR · SIM 归属未知").withDetails(details));
            }
        }
        b.done();
        return unique(cells);
    }

    private static List<RadioCell> unique(List<RadioCell> cells) {
        LinkedHashMap<String, RadioCell> unique = new LinkedHashMap<>();
        for (RadioCell cell : cells) unique.put(cell.key(), cell);
        return new ArrayList<>(unique.values());
    }
}
