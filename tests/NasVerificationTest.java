package com.celllock.app;

import android.os.SystemClock;
import android.telephony.CellInfo;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** 同卡关联、频段校正及脱敏真机回包重放；不连接手机或基带。 */
public final class NasVerificationTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static ByteBuffer bytes(int size) { return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format("%02x", b & 255));
        return result.toString();
    }
    private static JSONObject record(int message, int slot, JSONObject tlvs) throws Exception {
        return new JSONObject().put("service", 3).put("message", message).put("slot", slot)
                .put("transport", 0).put("result", 0).put("error", 0).put("tlvs", tlvs);
    }
    private static JSONObject system(long nci, int pci) throws Exception {
        return new JSONObject().put("4a", "0200").put("54", hex(bytes(2).putShort((short)pci).array()))
                .put("5e", hex(bytes(8).putLong(nci).array())).put("5d", "01000000");
    }
    private static JSONObject location(int arfcn, int pci, long nci, int rsrp) throws Exception {
        ByteBuffer b = bytes(22); b.position(6);
        b.putLong(nci).putShort((short)pci).putShort((short)-32768).putShort((short)rsrp).putShort((short)-32768);
        return new JSONObject().put("2e", hex(bytes(4).putInt(arfcn).array())).put("2f", hex(b.array()));
    }
    private static JSONObject rf(int arfcn, int enumeration) throws Exception {
        return new JSONObject().put("01", hex(bytes(6).put((byte)1).put((byte)12).putShort((short)enumeration).putShort((short)arfcn).array()))
                .put("11", hex(bytes(8).put((byte)1).put((byte)12).putShort((short)enumeration).putInt(arfcn).array()))
                .put("12", hex(bytes(6).put((byte)1).put((byte)12).putInt(17).array()));
    }
    private static String batch(int slot, JSONObject first, JSONObject loc, JSONObject rf, JSONObject sig, JSONObject last) throws Exception {
        return record(0x45, slot, new JSONObject()) + "\n" + record(0x4d, slot, first) + "\n"
                + record(0x43, slot, loc) + "\n" + record(0x31, slot, rf) + "\n"
                + record(0x4f, slot, sig) + "\n" + record(0x4d, slot, last);
    }
    private static RadioCell phone(int arfcn, int pci, int band, long nci) throws Exception {
        return RadioCell.parse(new JSONObject().put("nr", true).put("registered", true).put("arfcn", arfcn)
                .put("pci", pci).put("band", band).put("rsrp", -100).put("identity", nci).put("timestamp", SystemClock.now)
                .put("details", new JSONObject().put("频段来源", "系统")), "Root 电话");
    }
    public static void main(String[] args) throws Exception {
        JSONObject sys = system(123456, 139), loc = location(154570, 139, 123456, -32768);
        JSONObject radio = rf(154570, 257), sig = new JSONObject().put("17", hex(bytes(4).putShort((short)-97).putShort((short)81).array())).put("18", "f5ff");
        String raw = batch(0, sys, loc, radio, sig, sys);
        RadioCell qmi = MeasurementDecoder.nas(raw, 0).get(0);
        check(qmi.band == 28 && qmi.pci == 139 && qmi.identity == 123456 && qmi.registered, "verified NR service identity and RF enum n28");
        check(qmi.rsrp == -97 && qmi.details.get("SNR").equals("8.1 dB") && qmi.details.get("RSRQ").equals("-11 dB"), "independent NR signal");
        check(qmi.details.get("SCS").equals("30 kHz") && qmi.details.get("带宽").equals("100 MHz"), "SYS SCS and RF width");
        check(qmi.timestamp == 0 && !qmi.mapped, "QMI does not invent timestamps or phone mapping");
        RadioCell wrong = phone(154570, 139, 41, 123456);
        check(wrong.band == 28 && wrong.bandLabel().equals("n28（推算）") && wrong.details.get("原报频段").equals("n41"), "old reported mismatch corrected once in shared model");
        List<RadioCell> cells = new ArrayList<>(List.of(wrong));
        RadioReader.supplementReadings(cells, List.of(qmi));
        RadioCell corrected = cells.get(0);
        check(cells.size() == 1 && corrected.bandLabel().equals("n28（校正）") && corrected.bandNotice().contains("系统原报 n41"), "QMI correction merges into primary phone cell");
        check(corrected.rsrp == -100 && corrected.timestamp == wrong.timestamp && corrected.receivedAt == wrong.receivedAt && corrected.mapped, "existing phone signal/time/provenance retained");
        check(!corrected.details.containsKey("信号来源") && corrected.details.get("频段").equals("28"), "unused QMI RSRP does not suppress phone history or retain wrong detail band");
        RadioCell neighbor = RadioCell.measured(true, false, 154570, 78, -110, "DIAG NR").withServingBand(corrected);
        check(neighbor.hasTarget(corrected) && neighbor.targetBand(corrected) == 28 && !neighbor.mapped && neighbor.timestamp == 0, "corrected neighbor remains eligible without invented SIM or timestamp");
        check(RadioCell.parse(corrected.json(), "Root 电话").bandLabel().equals("n28（校正）"), "correction survives Root JSON boundary");
        RadioCell partial = RadioCell.parse(wrong.json().put("pci", CellInfo.UNAVAILABLE).put("rsrp", CellInfo.UNAVAILABLE), "Root 电话");
        RadioCell filled = partial.supplement(qmi);
        check(filled.pci == 139 && filled.rsrp == -97 && filled.timestamp == partial.timestamp && filled.details.containsKey("信号来源"), "missing signal and PCI supplemented without new measurement time");
        check(wrong.supplement(qmi.withIdentity(999)) == wrong, "conflicting NCI never merged");
        check(wrong.withDetails(java.util.Map.of("TAC","123")).supplement(qmi.withDetails(java.util.Map.of("TAC","456")))
                .details.get("TAC").equals("123"), "conflicting tracking area never merged");
        for (String name : new String[]{"MCC", "MNC", "PLMN"}) {
            RadioCell primary = RadioCell.parse(wrong.json().put("rsrp", CellInfo.UNAVAILABLE), "Root 电话")
                    .withDetails(java.util.Map.of(name, "001"));
            RadioCell conflicting = qmi.withDetails(java.util.Map.of(name, "002", "补充来源", "QMI SYS / RF"));
            check(primary.supplement(conflicting) == primary, name + " conflict rejects supplemental signal and identity");
            List<RadioCell> separate = new ArrayList<>(List.of(primary));
            RadioReader.supplementReadings(separate, List.of(conflicting));
            check(separate.equals(List.of(primary, conflicting)), name + " conflict survives reader deduplication");
            check(RadioCell.surrounding(separate, primary).equals(List.of(conflicting)) && !conflicting.hasTarget(primary),
                    name + " conflict stays visible without becoming a lock target");
            RadioCell matching = qmi.withDetails(java.util.Map.of(name, "001", "补充来源", "QMI SYS / RF"));
            List<RadioCell> merged = new ArrayList<>(List.of(primary));
            RadioReader.supplementReadings(merged, List.of(matching));
            check(merged.size() == 1 && merged.get(0).pci == 139 && merged.get(0).rsrp == -97,
                    name + " match still supplements missing fields");
        }
        ByteBuffer common = bytes(26); common.position(19);
        common.put((byte)1).put(new byte[]{'4','6','0','0','1',(byte)255});
        JSONObject networkSys = new JSONObject(sys.toString()).put("4b", hex(common.array()));
        JSONObject networkLoc = new JSONObject(loc.toString());
        String locBody = loc.getString("2f");
        for (String[] network : new String[][]{{"64f010", "460", "01"}, {"643021", "460", "123"}}) {
            networkLoc.put("2f", network[0] + locBody.substring(6));
            RadioCell decoded = MeasurementDecoder.nas(batch(0, sys, networkLoc, radio, sig, sys), 0).get(0);
            check(network[1].equals(decoded.details.get("MCC")) && network[2].equals(decoded.details.get("MNC")),
                    "LOC decodes MCC and two/three-digit MNC for phone comparison");
        }
        networkLoc.put("2f", "64f010" + locBody.substring(6));
        check(MeasurementDecoder.nas(batch(0, networkSys, networkLoc, radio, sig, networkSys), 0).get(0).details.containsKey("SCS"),
                "matching SYS and LOC operator still permits enrichment");
        networkLoc.put("2f", "64f020" + locBody.substring(6));
        RadioCell otherOperator = MeasurementDecoder.nas(batch(0, networkSys, networkLoc, radio, sig, networkSys), 0).get(0);
        check(!otherOperator.details.containsKey("SCS") && !otherOperator.hasSignal(),
                "SYS and LOC operator conflict rejects unrelated metadata and signal");
        RadioCell selectedOperator = partial.withDetails(java.util.Map.of("MCC", "460", "MNC", "01"));
        List<RadioCell> differentOperators = new ArrayList<>(List.of(selectedOperator));
        RadioReader.supplementReadings(differentOperators, List.of(otherOperator));
        check(differentOperators.equals(List.of(selectedOperator, otherOperator)), "raw LOC from another operator is retained separately");
        RadioCell stale = RadioCell.parse(wrong.json().put("timestamp", SystemClock.now - 60_001), "Root 电话");
        check(stale.supplement(qmi) == stale && !stale.hasTarget(stale), "fresh QMI cannot revive stale phone identity");
        RadioCell n77 = phone(640000, 1, 77, 123);
        check(n77.withRfBand(78).band == 77, "legal reported n77 retained in overlap");
        RadioCell ambiguous = phone(640000, 1, 28, 123);
        check(ambiguous.band == 0 && ambiguous.bandLabel().contains("n77 / n78") && ambiguous.hasTarget(ambiguous), "ambiguous correction keeps manual lock form available");
        check(ambiguous.withRfBand(77).band == 77, "RF can resolve overlap");
        check(phone(504990, 48, 41, 99).bandLabel().equals("n41"), "normal n41 is not corrected");
        check(MeasurementDecoder.nas(raw, 1).isEmpty(), "slot mismatch rejected");
        check(MeasurementDecoder.nas(batch(1, sys, loc, radio, sig, sys), 1).get(0).band == 28, "SIM2 NR equally supported");
        JSONObject second = system(777, 48);
        RadioCell card2 = MeasurementDecoder.nas(batch(1, second, location(504990, 48, 777, -990), rf(504990, 259), sig, second), 1).get(0);
        check(card2.band == 41 && card2.arfcn == 504990 && card2.rsrp == -99, "independent SIM2 NR and existing LOC signal retained");
        check(MeasurementDecoder.nas(raw, 0).get(0).band == 28, "SIM2 decode does not cache band into SIM1");
        RadioCell oldRf = MeasurementDecoder.nas(batch(1, second, location(504990, 48, 777, -990), new JSONObject(rf(504990,259).toString()).put("11", JSONObject.NULL), sig, second),1).get(0);
        check(oldRf.band == 0, "malformed extension never falls back to truncated NR channel");
        JSONObject oldOnly = rf(504990,259); oldOnly.remove("11");
        check(MeasurementDecoder.nas(batch(1,second,location(504990,48,777,-990),oldOnly,sig,second),1).get(0).band == 0, "old 16-bit NR field ignored");
        check(MeasurementDecoder.nas(batch(0,sys,loc,radio,sig,system(999,139)),0).get(0).details.get("SCS") == null, "handover between bookends rejects supplemental data");
        check(!MeasurementDecoder.nas(batch(0,sys,loc,rf(504990,259),sig,sys),0).get(0).details.containsKey("SCS"), "RF frequency conflict rejects unassociated signal and metadata");
        List<JSONObject> failedRecords = MeasurementDecoder.records(raw);
        failedRecords.get(3).put("error",3);
        StringBuilder failedBatch = new StringBuilder();
        for (JSONObject frame : failedRecords) failedBatch.append(frame).append('\n');
        RadioCell unsupported = MeasurementDecoder.nas(failedBatch.toString(),0).get(0);
        check(unsupported.rsrp == -97 && unsupported.details.containsKey("SCS") && !unsupported.details.containsKey("射频频段"), "unsupported RF query retains SYS, SIG and location fallback");
        JSONObject noService = new JSONObject(sys.toString()).put("4a", "0000");
        RadioCell inactive = MeasurementDecoder.nas(batch(0,noService,loc,radio,sig,noService),0).get(0);
        check(!inactive.registered && !inactive.details.containsKey("SCS"), "inactive RAT cannot become serving from residual fields");
        JSONObject invalidSig = new JSONObject().put("17", "00800080").put("18", "0080");
        RadioCell invalid = MeasurementDecoder.nas(batch(0,sys,loc,radio,invalidSig,sys),0).get(0);
        check(!invalid.hasSignal() && !invalid.details.containsKey("SNR") && !invalid.details.containsKey("RSRQ"), "invalid signal placeholders ignored");
        check(MeasurementDecoder.nas(batch(0,sys,loc,radio,new JSONObject().put("17","00"),sys),0).get(0).details.containsKey("射频频段"), "malformed SIG preserves verified SYS and RF");
        RadioCell noLoc = MeasurementDecoder.nas(batch(0,sys,new JSONObject(),radio,sig,sys),0).get(0);
        check(noLoc.registered && noLoc.pci == 139 && noLoc.identity == 123456 && noLoc.band == 28, "stable SYS plus RF can supply missing service");
        String failed = raw.replace("\"message\":69", "\"message\":0");
        check(MeasurementDecoder.nas(failed,0).isEmpty(), "no successful bind means no data");
        if (args.length >= 1) replay(Path.of(args[0]));
        if (args.length >= 2) replayConflict(Path.of(args[1]));
        System.out.println("NasVerification: " + checks + " checks passed");
    }
    private static void replay(Path path) throws Exception {
        List<JSONObject> records = MeasurementDecoder.records(Files.readString(path));
        for (int at = 0; at < records.size(); at += 6) {
            int slot = records.get(at).getInt("slot"); StringBuilder raw = new StringBuilder();
            for (int i = 0; i < 6; i++) raw.append(records.get(at+i)).append('\n');
            List<RadioCell> cells = MeasurementDecoder.nas(raw.toString(), slot);
            RadioCell serving = RadioCell.serving(cells);
            check(serving != null && serving.nr == (slot == 0), "captured NR/LTE service status");
            check(serving.arfcn == (slot == 0 ? 504990 : 1650) && serving.pci == (slot == 0 ? 48 : 111), "captured full channel and PCI");
            check(serving.band == (slot == 0 ? 41 : 3) && serving.details.containsKey("带宽"), "captured RF band and width");
            if (slot == 1) check(cells.stream().filter(c -> c.nr).noneMatch(c -> c.registered || c.hasSignal()), "captured SIM2 residual NR not serving");
        }
    }

    private static void replayConflict(Path path) throws Exception {
        JSONArray samples = new JSONArray(Files.readString(path));
        check(samples.length() == 2, "conflict capture contains both SIM contexts");
        long previousTime = SystemClock.now;
        try {
            for (int slot = 0; slot < samples.length(); slot++) {
                JSONObject sample = samples.getJSONObject(slot), phone = sample.getJSONObject("phone");
                JSONArray frames = sample.getJSONArray("qmi");
                int capturedSlot = sample.getInt("slot");
                check(capturedSlot == slot && frames.length() == 6, "captured bound query batch");
                SystemClock.now = Math.max(phone.getLong("timestamp"), frames.getJSONObject(5).getLong("elapsed"));
                String raw = frames.join("\n");
                check(MeasurementDecoder.records(raw).stream().allMatch(r -> r.getInt("slot") == capturedSlot && MeasurementDecoder.success(r)),
                        "captured queries succeed in selected SIM context");
                List<RadioCell> decoded = MeasurementDecoder.nas(raw, slot);
                RadioCell qmi = RadioCell.serving(decoded), original = RadioCell.parse(phone, "Root 电话");
                check(qmi != null && original.sameCell(qmi), "redacted phone and QMI identities still match");
                check(qmi.identity == (slot == 0 ? 101 : 202) && qmi.details.get("TAC").equals(slot == 0 ? "101" : "202")
                        && qmi.details.get("MCC").equals("001") && qmi.details.get("MNC").equals(slot == 0 ? "01" : "02"),
                        "captured network identifiers replaced consistently with fictional values");
                List<RadioCell> cells = new ArrayList<>(List.of(original));
                RadioReader.supplementReadings(cells, decoded);
                RadioCell serving = RadioCell.serving(cells);
                RadioCell neighbor = RadioCell.measured(true, false, 154570, 78, -110, "DIAG NR");
                if (slot == 0) {
                    check(original.bandLabel().equals("n28（推算）") && original.details.get("原报频段").equals("n41"),
                            "actual RootPhone capture retains original n41 after frequency correction");
                    check(serving.bandLabel().equals("n28（校正）") && serving.bandNotice().contains("系统原报 n41")
                            && serving.details.get("射频频段").equals("n28"), "captured QMI confirms n28 display");
                    check(serving.arfcn == 154570 && serving.pci == 139 && serving.hasTarget(serving) && serving.targetBand(serving) == 28,
                            "captured corrected serving supplies n28 lock target");
                    check(neighbor.isNeighborOf(serving) && neighbor.hasTarget(serving) && neighbor.targetBand(serving) == 28
                            && neighbor.withServingBand(serving).bandLabel().equals("n28"), "captured correction preserves neighbor display and lock qualification");
                    check(serving.timestamp == original.timestamp && serving.receivedAt == original.receivedAt && serving.rsrp == original.rsrp,
                            "captured enrichment preserves phone signal and measurement age");
                    MeasurementDecoder.Bytes legacy = new MeasurementDecoder.Bytes(MeasurementDecoder.hex(frames.getJSONObject(3).getJSONObject("tlvs").getString("01")));
                    legacy.skip(4);
                    check(legacy.u16() == 23498 && qmi.arfcn == 154570, "captured truncated legacy frequency cannot override full channel");
                    JSONArray oldOnly = new JSONArray(frames.toString());
                    oldOnly.getJSONObject(3).getJSONObject("tlvs").remove("11");
                    RadioCell unverified = original.supplement(RadioCell.serving(MeasurementDecoder.nas(oldOnly.join("\n"), slot)));
                    check(!unverified.details.containsKey("射频频段") && unverified.bandLabel().equals("n28（推算）"),
                            "captured legacy-only NR response cannot claim RF-confirmed correction");
                } else {
                    check(!serving.nr && serving.band == 3 && serving.arfcn == 1650 && serving.pci == 111,
                            "captured SIM2 remains LTE B3");
                    check(decoded.stream().anyMatch(c -> c.nr) && decoded.stream().filter(c -> c.nr).noneMatch(c -> c.registered || c.hasSignal() || c.hasTarget(serving)),
                            "captured residual NR frequency is neither service nor target");
                    check(!neighbor.isNeighborOf(serving) && !neighbor.hasTarget(serving), "SIM1 NR candidate cannot target captured SIM2 LTE service");
                    JSONObject invalidSig = frames.getJSONObject(4).getJSONObject("tlvs");
                    check(invalidSig.getString("17").equals("00800080") && invalidSig.getString("18").equals("0080"),
                            "capture retains actual invalid NR signal placeholders");
                    JSONObject sys = system(101, 139);
                    RadioCell invalid = RadioCell.serving(MeasurementDecoder.nas(batch(0, sys, location(154570,139,101,-32768),
                            rf(154570,257), invalidSig, sys), 0));
                    check(!invalid.hasSignal() && !invalid.details.containsKey("SNR") && !invalid.details.containsKey("RSRQ"),
                            "actual captured invalid SIG also stays invalid with an active NR context");
                }
            }
        } finally { SystemClock.now = previousTime; }
    }
}
