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
        int i8() { need(1); return data.get(); }
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
        List<JSONObject> queries = new ArrayList<>();
        boolean bound = false;
        for (JSONObject record : records(output)) {
            if (record.optInt("service") != 3 || record.optInt("slot", -1) != slot) continue;
            if (record.optInt("message") == 0x45) { bound = success(record); queries.clear(); continue; }
            if (!bound) continue;
            queries.add(record);
            if (!success(record) || record.optInt("message") != 0x43) continue;
            int start = cells.size();
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
            long received = record.optLong("elapsed");
            if (received > 0) for (int i = start; i < cells.size(); i++) cells.set(i, cells.get(i).withReceivedAt(received));
        }
        try { enrichNas(cells, queries); }
        catch (IllegalArgumentException | org.json.JSONException ignored) { /* 可选查询格式异常不丢弃原有位置观测。 */ }
        return unique(cells);
    }

    private static final int[] RF_NR_BANDS = {1,2,3,5,7,8,20,28,38,41,50,51,66,70,71,74,75,76,77,78,79,
            80,81,82,83,84,85,257,258,259,260,261,12,25,34,39,40,65,86,48,14,13,18,26,30,29,53};
    private static final int[] RF_LTE_BANDS = {1,2,3,4,5,6,7,8,9,10,11,12,13,14,17,33,34,35,36,37,38,39,40,
            18,19,20,21,24,25,41,42,43,23,26,32,125,126,127,28,29,30,66,250,46,27,31,71,47,48,67,68,49,85,72,73,86};
    private static final String[] RF_WIDTHS = {"1.4","3","5","10","15","20","5","10","15","20","25","30",
            "40","50","60","80","90","100","200","400","0.2","","5","10","70"};

    private static final class NasService {
        int status = -1, arfcn = CellInfo.UNAVAILABLE, pci = CellInfo.UNAVAILABLE;
        long identity = -1;
        final Map<String, String> details = new LinkedHashMap<>();

        boolean stable(NasService other) {
            return status == 2 && other.status == 2 && identity >= 0 && identity == other.identity
                    && pci == other.pci && arfcn == other.arfcn && details.equals(other.details);
        }

        boolean matches(RadioCell cell) {
            return !(identity >= 0 && cell.identity >= 0 && identity != cell.identity
                    || pci != CellInfo.UNAVAILABLE && cell.pci != CellInfo.UNAVAILABLE && pci != cell.pci
                    || arfcn != CellInfo.UNAVAILABLE && cell.arfcn != CellInfo.UNAVAILABLE && arfcn != cell.arfcn
                    || RadioCell.conflictingIdentityDetails(details, cell.details));
        }
    }

    private static Bytes field(JSONObject tlvs, String tag) throws Exception {
        return new Bytes(hex(tlvs.getString(tag)));
    }

    private static NasService system(JSONObject tlvs, boolean nr) throws Exception {
        NasService service = new NasService();
        String status = nr ? "4a" : "14", common = nr ? "4b" : "19";
        if (tlvs.has(status)) {
            Bytes b = field(tlvs, status);
            service.status = b.u8(); b.u8(); // true status 在部分设备上为 0，不能替代 service status。
        }
        if (tlvs.has(common)) {
            Bytes b = field(tlvs, common); b.need(26); b.skip(11);
            boolean valid = b.u8() == 1; long identity = b.u32(); b.skip(3);
            boolean network = b.u8() == 1;
            String mcc = asciiDigits(b), mnc = asciiDigits(b);
            if (!nr && valid && identity <= 268435455L) service.identity = identity;
            if (network && mcc.matches("[0-9]{3}") && mnc.matches("[0-9]{2,3}")) {
                service.details.put("MCC", mcc); service.details.put("MNC", mnc);
            }
            if (!nr && b.data.remaining() >= 3) {
                valid = b.u8() == 1; int tac = b.u16();
                if (valid) RadioCell.metric(service.details, "TAC", tac, 0, 65534, 1, "");
            }
        }
        if (nr) {
            if (tlvs.has("54")) {
                Bytes b = field(tlvs, "54"); int pci = b.u16(); b.done();
                if (pci <= 1007) service.pci = pci;
            }
            if (tlvs.has("60")) {
                Bytes b = field(tlvs, "60"); service.arfcn = frequency(b.u32()); b.done();
            }
            if (tlvs.has("5e")) {
                Bytes b = field(tlvs, "5e"); long nci = b.i64(); b.done();
                if (nci >= 0 && nci <= 68719476735L) service.identity = nci;
            }
            if (tlvs.has("50")) {
                Bytes b = field(tlvs, "50"); int tac = b.u8() << 16 | b.u8() << 8 | b.u8(); b.done();
                RadioCell.metric(service.details, "TAC", tac, 0, 16777214, 1, "");
            }
            if (tlvs.has("5d")) {
                Bytes b = field(tlvs, "5d"); long scs = b.u32(); b.done();
                if (scs <= 4) service.details.put("SCS", new int[]{15,30,60,120,240}[(int)scs] + " kHz");
            }
        }
        return service;
    }

    private static String asciiDigits(Bytes b) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            int digit = b.u8();
            if (digit != 255) value.append((char)digit);
        }
        return value.toString();
    }

    private static List<RadioCell> rf(JSONObject tlvs) throws Exception {
        Map<Integer, String> widths = new LinkedHashMap<>();
        if (tlvs.has("12")) {
            Bytes b = field(tlvs, "12"); int count = b.u8();
            for (int i = 0; i < count; i++) {
                int rat = b.u8(); long width = b.u32();
                if (width < RF_WIDTHS.length && !RF_WIDTHS[(int)width].isEmpty()) widths.put(rat, RF_WIDTHS[(int)width] + " MHz");
            }
            b.done();
        }
        List<RadioCell> result = new ArrayList<>();
        boolean extended = tlvs.has("11");
        if (!extended && !tlvs.has("01")) return result;
        Bytes b = field(tlvs, extended ? "11" : "01"); int count = b.u8();
        for (int i = 0; i < count; i++) {
            int rat = b.u8(), enumeration = b.u16();
            int arfcn = extended ? frequency(b.u32()) : b.u16();
            boolean nr = rat == 12;
            // 旧 16 位 NR 频道会截断；没有扩展字段时不用于校正。
            if (rat != 8 && !nr || nr && !extended) continue;
            int offset = enumeration - (nr ? 250 : 120); int[] bands = nr ? RF_NR_BANDS : RF_LTE_BANDS;
            if (offset < 0 || offset >= bands.length || !RadioCell.validIdentity(nr, arfcn, 0)) continue;
            int band = bands[offset];
            if (nr ? !Bands.nrCandidates(arfcn).get(band) : Bands.lteBandForEarfcn(arfcn) != band) continue;
            Map<String, String> details = new LinkedHashMap<>();
            details.put("射频频段", (nr ? "n" : "B") + band);
            if (widths.containsKey(rat)) details.put("带宽", widths.get(rat));
            result.add(RadioCell.measured(nr, false, arfcn, CellInfo.UNAVAILABLE, CellInfo.UNAVAILABLE, "QMI RF")
                    .withDetails(details).withRfBand(band));
        }
        b.done(); return result;
    }

    private static RadioCell signal(RadioCell cell, JSONObject tlvs) throws Exception {
        Map<String, String> details = new LinkedHashMap<>();
        int rsrp = CellInfo.UNAVAILABLE;
        if (cell.nr && tlvs.has("17")) {
            Bytes b = field(tlvs, "17"); rsrp = b.i16(); int snr = b.i16(); b.done();
            RadioCell.metric(details, "SNR", snr, -230, 400, 10, "dB");
        }
        if (cell.nr && tlvs.has("18")) {
            Bytes b = field(tlvs, "18"); int rsrq = b.i16(); b.done();
            RadioCell.metric(details, "RSRQ", rsrq, -43, 20, 1, "dB");
        }
        if (!cell.nr && tlvs.has("14")) {
            Bytes b = field(tlvs, "14"); int rssi = b.i8(), rsrq = b.i8(); rsrp = b.i16(); int snr = b.i16(); b.done();
            RadioCell.metric(details, "RSSI", rssi, -160, -20, 1, "dBm");
            RadioCell.metric(details, "RSRQ", rsrq, -34, 3, 1, "dB");
            RadioCell.metric(details, "SNR", snr, -200, 300, 10, "dB");
        }
        if (!details.isEmpty()) details.put("补充来源", "QMI SYS / RF / SIG");
        return cell.withSignal(rsrp, details);
    }

    private static void enrichNas(List<RadioCell> cells, List<JSONObject> queries) throws Exception {
        if (queries.size() != 5 || queries.get(0).optInt("message") != 0x4d
                || queries.get(1).optInt("message") != 0x43 || queries.get(2).optInt("message") != 0x31
                || queries.get(3).optInt("message") != 0x4f || queries.get(4).optInt("message") != 0x4d
                || !success(queries.get(0)) || !success(queries.get(4))) return;
        long begin = queries.get(0).optLong("elapsed"), end = queries.get(4).optLong("elapsed");
        if (begin > 0 && (end < begin || end - begin > 5000)) return;
        JSONObject before = queries.get(0).getJSONObject("tlvs"), after = queries.get(4).getJSONObject("tlvs");
        List<RadioCell> radios = new ArrayList<>();
        if (success(queries.get(2))) try { radios = rf(queries.get(2).getJSONObject("tlvs")); }
        catch (IllegalArgumentException | org.json.JSONException ignored) { /* 各可选 GET 独立失败，保留其他已核验字段。 */ }
        for (boolean nr : new boolean[]{false, true}) {
            NasService first, last;
            try { first = system(before, nr); last = system(after, nr); }
            catch (IllegalArgumentException | org.json.JSONException ignored) { continue; }
            if (first.status != 2 && last.status != 2 && first.status >= 0 && last.status >= 0) {
                for (int i = 0; i < cells.size(); i++) if (cells.get(i).nr == nr) cells.set(i, cells.get(i).withRegistered(false));
                continue;
            }
            if (!first.stable(last)) continue;
            if (cells.stream().noneMatch(c -> c.nr == nr && c.registered) && nr && first.pci != CellInfo.UNAVAILABLE) {
                List<RadioCell> candidates = new ArrayList<>();
                for (RadioCell radio : radios) if (radio.nr && (first.arfcn == CellInfo.UNAVAILABLE || first.arfcn == radio.arfcn)) candidates.add(radio);
                if (candidates.size() == 1) {
                    RadioCell service = RadioCell.measured(true, true, candidates.get(0).arfcn, first.pci,
                            CellInfo.UNAVAILABLE, "QMI NAS").withIdentity(first.identity);
                    cells.add(begin > 0 ? service.withReceivedAt(begin) : service);
                }
            }
            for (int i = 0; i < cells.size(); i++) {
                RadioCell cell = cells.get(i);
                if (cell.nr != nr || !cell.registered || !first.matches(cell)) continue;
                List<RadioCell> matching = new ArrayList<>();
                for (RadioCell radio : radios) if (radio.nr == nr
                        && (cell.arfcn != CellInfo.UNAVAILABLE ? radio.arfcn == cell.arfcn : first.arfcn == CellInfo.UNAVAILABLE || radio.arfcn == first.arfcn)) matching.add(radio);
                if (matching.size() > 1) continue;
                if (matching.isEmpty() && radios.stream().anyMatch(r -> r.nr == nr)) continue;
                RadioCell radio = matching.isEmpty() ? null : matching.get(0);
                if (radio == null && cell.arfcn == CellInfo.UNAVAILABLE) continue;
                Map<String, String> details = new LinkedHashMap<>(cell.details);
                for (Map.Entry<String, String> field : first.details.entrySet()) details.putIfAbsent(field.getKey(), field.getValue());
                if (radio != null) for (Map.Entry<String, String> field : radio.details.entrySet())
                    if (!field.getKey().equals("频段来源") && !field.getKey().equals("候选频段")) details.putIfAbsent(field.getKey(), field.getValue());
                details.put("补充来源", "QMI SYS / RF");
                RadioCell enriched = RadioCell.measured(nr, true, radio == null ? cell.arfcn : radio.arfcn,
                        cell.pci == CellInfo.UNAVAILABLE ? first.pci : cell.pci, cell.rsrp, "QMI NAS")
                        .withIdentity(cell.identity < 0 ? first.identity : cell.identity).withDetails(details)
                        .withReceivedAt(cell.receivedAt);
                if (radio != null) enriched = enriched.withRfBand(radio.band);
                if (success(queries.get(3))) try { enriched = signal(enriched, queries.get(3).getJSONObject("tlvs")); }
                catch (IllegalArgumentException | org.json.JSONException ignored) { /* 保留 SYS / RF，等待下一轮信号。 */ }
                cells.set(i, enriched);
            }
        }
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
        StringBuilder packed = new StringBuilder();
        boolean valid = true;
        for (int i = 0; i < 3; i++) {
            int value = b.u8();
            for (int shift = 0; shift <= 4; shift += 4) {
                int digit = value >> shift & 15;
                packed.append(Character.forDigit(digit, 16));
                if (digit <= 9) digits.append(digit);
                else if (digit != 15) valid = false;
            }
        }
        Map<String, String> details = new LinkedHashMap<>();
        if (valid && digits.length() >= 5 && !digits.toString().startsWith("000")) {
            details.put("PLMN", digits.toString());
            if (packed.toString().matches("[0-9]{3}[0-9f][0-9]{2}")) {
                details.put("MCC", packed.substring(0, 3));
                details.put("MNC", packed.substring(4, 6) + (packed.charAt(3) == 'f' ? "" : packed.substring(3, 4)));
            }
        }
        return details;
    }

    private static final class BatchLimit extends IllegalArgumentException { BatchLimit(String message){super(message);} }
    static final class NrBatch {
        final List<RadioCell> cells=new ArrayList<>();
        int packets,bytes,valid,unsupported,damaged;
        String reasons="";
        boolean usable(){return valid>0;}
        String summary(){return "收到 "+packets+" 包 / "+bytes+" 字节，解码 "+valid+"，未知版本 "+unsupported+"，损坏 "+damaged+"，去重后 "+cells.size()+" 个小区"+(reasons.isEmpty()?"":"；"+reasons);}
    }
    static NrBatch nrBatch(JSONObject result) throws Exception {
        NrBatch batch=new NrBatch();
        if(result.has("error")){batch.reasons="通道返回错误："+result.optString("error");return batch;}
        JSONArray logs=result.optJSONArray("logs");
        if(logs==null||logs.length()>32)throw new IllegalArgumentException("诊断批次封装或包数量无效");
        batch.packets=logs.length();
        // 先检查整批原始预算，避免未知版本或坏包绕过容量限制。
        for(int i=0;i<logs.length();i++) {
            Object raw=logs.get(i);if(!(raw instanceof String))throw new IllegalArgumentException("诊断包不是十六进制字符串");
            int length=((String)raw).length();
            if(length>131070||length>196608-batch.bytes*2)throw new IllegalArgumentException("诊断数据超限");
            batch.bytes+=(length+1)/2;
        }
        LinkedHashMap<String,RadioCell> cells=new LinkedHashMap<>();int decoded=0;
        for(int i=0;i<logs.length();i++) {
            String raw=logs.getString(i),reason="";
            try {
                byte[] packet=hex(raw);Bytes header=new Bytes(packet);
                if(header.u16()!=packet.length||header.u16()!=0xb97f)throw new IllegalArgumentException("非法 NR 日志头");
                header.skip(8);int minor=header.u16(),major=header.u16();
                if(major!=2||minor!=7){batch.unsupported++;reason="日志 0xB97F 版本 "+major+"."+minor+" 未支持";}
                else {
                    List<RadioCell> parsed=nr(packet);decoded+=parsed.size();
                    if(decoded>512)throw new BatchLimit("诊断小区总数超限");
                    for(RadioCell cell:parsed)cells.put(cell.key(),cell);
                    batch.valid++;
                }
            } catch(BatchLimit error){throw error;}
            catch(IllegalArgumentException error){batch.damaged++;reason=error.getMessage();}
            if(!reason.isEmpty()&&batch.reasons.length()<600)batch.reasons+=(batch.reasons.isEmpty()?"":"；")+"包 "+i+" "+reason+" ["+raw.substring(0,Math.min(64,raw.length()))+"]";
        }
        batch.cells.addAll(cells.values());return batch;
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
