package com.celllock.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/** JSON runtime required; creates commands in memory and never executes them. */
public final class SnapshotsTest {
    private static int checks;
    private static final List<String> failures = new ArrayList<>();

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) failures.add(description);
    }

    private static JSONObject snapshot() throws Exception {
        return new JSONObject().put("schema", 2).put("rat", 28).put("nrMode", 0)
                .put("legacyMask", "0000abcd12345678")
                .put("lte", "1,2,3,250").put("nrSa", "1,41,78,257").put("nrNsa", "1,78")
                .put("gsm", "850,900,1800,1900").put("wcdma", "1,2,8");
    }

    private static JSONArray array(int... values) {
        JSONArray out = new JSONArray();
        for (int value : values) out.put(value);
        return out;
    }

    private static JSONObject stateJson() throws Exception {
        return new JSONObject()
                .put("pref", new JSONObject().put("known", true).put("rat", 28).put("nrMode", 0)
                        .put("legacyMask", "0000abcd12345678")
                        .put("lte", array(1, 2, 3, 250)).put("nrSa", array(1, 41, 78, 257))
                        .put("nrNsa", array(1, 78)).put("gsm", array(850, 900, 1800, 1900))
                        .put("wcdma", array(1, 2, 8)))
                .put("hardware", new JSONObject().put("known", true)
                        .put("lte", array(1, 3)).put("nr", array(1, 78))
                        .put("gsm", array(850, 900, 1800)).put("wcdma", array(1, 8)))
                .put("lteCell", new JSONObject().put("known", true).put("cells", array()))
                .put("nrCell", new JSONObject().put("known", false).put("ackOnly", true).put("type", -1));
    }

    private static ModemState state() throws Exception { return ModemState.parse(stateJson()); }

    private static void command(List<String[]> commands, int index, String... expected) {
        check(index < commands.size() && Arrays.equals(commands.get(index), expected),
                "command " + index + " must be " + Arrays.toString(expected));
    }

    private static void testSnapshotValidation() throws Exception {
        check(Snapshots.canRestore(snapshot()), "complete schema2 snapshot is restorable");
        check(!Snapshots.canRestore(null), "null snapshot is rejected");
        check(!Snapshots.canRestore(new JSONObject()), "empty snapshot is rejected");
        for (int schema : new int[]{0, 1, 3})
            check(!Snapshots.canRestore(snapshot().put("schema", schema)), "reject schema " + schema);
        for (int rat : new int[]{-1, 0, 1, 29, 0x100})
            check(!Snapshots.canRestore(snapshot().put("rat", rat)), "reject unsupported RAT mask " + rat);
        for (int mode : new int[]{-1, 3})
            check(!Snapshots.canRestore(snapshot().put("nrMode", mode)), "reject unknown NR mode " + mode);
        for (String field : new String[]{"lte", "nrSa", "nrNsa", "gsm", "wcdma"}) {
            JSONObject missing = snapshot(); missing.remove(field);
            check(!Snapshots.canRestore(missing), "missing " + field + " is unknown, not empty");
            check(!Snapshots.canRestore(snapshot().put(field, JSONObject.NULL)), "null " + field + " is rejected");
            for (String bad : new String[]{"1,,3", "-1", "1;id", "1 3", "1,", "all"})
                check(!Snapshots.canRestore(snapshot().put(field, bad)), "reject malformed " + field + ": " + bad);
        }
        JSONObject missingLegacy = snapshot(); missingLegacy.remove("legacyMask");
        check(!Snapshots.canRestore(missingLegacy), "schema2 requires original legacy mask");
        for (Object bad : new Object[]{JSONObject.NULL, "", "nothex", "0000abcd1234567", "0000abcd123456789"})
            check(!Snapshots.canRestore(snapshot().put("legacyMask", bad)), "reject incomplete legacy mask " + bad);
        check(Snapshots.canRestore(snapshot().put("legacyMask", "0000ABCD12345678")), "hex mask accepts uppercase");
    }

    private static void testExactMatches() throws Exception {
        JSONObject snap = snapshot();
        ModemState current = state();
        check(Snapshots.matches(snap, current), "unchanged full state matches");
        check(current.lte.get(250) && current.lte.get(2), "parse keeps unsupported LTE B250 and B2");
        check(!current.hwLte.get(250) && !current.hwLte.get(2), "test really distinguishes selected and hardware bits");
        check(current.nrSa.get(257) && !current.hwNr.get(257), "parse keeps unsupported NR bit");
        check(current.gsm.get(1900) && !current.hwGsm.get(1900), "parse keeps original unsupported GSM bit");
        check(!Snapshots.matches(snap, null), "null current state cannot confirm restore");

        for (int field = 0; field < 5; field++) {
            current = state();
            BitSet[] fields = {current.lte, current.nrSa, current.nrNsa, current.gsm, current.wcdma};
            int[] removed = {250, 257, 78, 1900, 2};
            fields[field].clear(removed[field]);
            check(!Snapshots.matches(snap, current), "removed original bit in field " + field + " fails exact restore");
        }
        current = state(); current.lte.set(249);
        check(!Snapshots.matches(snap, current), "extra unsupported bit is also a mismatch");
        current = state(); current.hwLte.clear();
        check(Snapshots.matches(snap, current), "matching does not intersect current preferences with hardware");
        current = state(); BitSet swap = current.nrSa; current.nrSa = current.nrNsa; current.nrNsa = swap;
        check(!Snapshots.matches(snap, current), "SA and NSA are not interchangeable");
        current = state(); current.legacyMask = "0000abcd12345679";
        check(!Snapshots.matches(snap, current), "one legacy raw bit difference fails restore");
        current = state(); current.legacyMask = null;
        check(!Snapshots.matches(snap, current), "unknown current legacy mask cannot confirm restore");
        current = state(); current.legacyMask = "0000ABCD12345678";
        check(Snapshots.matches(snap, current), "hex mask comparison ignores text case only");
        current = state(); current.rat |= Rat.NR;
        check(!Snapshots.matches(snap, current), "RAT difference fails restore");
        current = state(); current.nrMode = 1;
        check(!Snapshots.matches(snap, current), "NR mode difference fails restore");
        current = state(); current.prefKnown = false;
        check(!Snapshots.matches(snap, current), "unknown preference cannot confirm restore");
        current = state(); current.nrNsa = null;
        check(!Snapshots.matches(snap, current), "one missing preference family cannot confirm restore");
        JSONObject partial = stateJson(); partial.getJSONObject("pref").remove("lte");
        current = ModemState.parse(partial);
        check(current.lte == null && !Snapshots.matches(snap, current), "partial known preference keeps missing family unknown");
    }

    private static void testRestoreCommands() throws Exception {
        List<String[]> commands = Snapshots.restoreCommands(snapshot(), 1);
        check(commands.size() == 8, "different SA and NSA produce eight explicit restore steps");
        command(commands, 0, "restore-lte", "1", "1,2,3,250");
        command(commands, 1, "restore-nr", "1", "sa", "1,41,78,257");
        command(commands, 2, "restore-nr", "1", "nsa", "1,78");
        command(commands, 3, "restore-legacy", "1", "0000abcd12345678");
        command(commands, 4, "nr-mode", "1", "both");
        command(commands, 5, "rat", "1", "gsm,wcdma,lte");
        command(commands, 6, "cell-clear", "1", "lte");
        command(commands, 7, "cell-clear", "1", "nr");
        for (String[] command : commands) {
            check(command.length >= 3 && "1".equals(command[1]), "every command keeps the requested slot");
            check(!"reset".equals(command[0]), "initial restore never becomes reset");
            if (command[0].startsWith("band-"))
                check(!"all".equals(command[command.length - 1]), "initial band masks never become hardware all");
        }
        JSONObject equal = snapshot().put("nrNsa", "1,41,78,257");
        commands = Snapshots.restoreCommands(equal, 0);
        check(commands.size() == 7, "identical NR settings can use one both command");
        command(commands, 1, "restore-nr", "0", "both", "1,41,78,257");
        for (int mode = 0; mode < 3; mode++) {
            commands = Snapshots.restoreCommands(snapshot().put("nrMode", mode), 0);
            command(commands, 4, "nr-mode", "0", new String[]{"both", "nsa", "sa"}[mode]);
        }
        JSONObject unsupportedFamily = snapshot().put("wcdma", "");
        commands = Snapshots.restoreCommands(unsupportedFamily, 0);
        boolean wcdmaCommand = false;
        for (String[] command : commands) if ("band-wcdma".equals(command[0])) wcdmaCommand = true;
        check(!wcdmaCommand, "empty unsupported family is skipped, never converted to all");
        commands=Snapshots.restoreCommands(snapshot().put("lte", "").put("nrSa", "").put("nrNsa", ""),0);
        command(commands,0,"restore-lte","0","none");
        command(commands,1,"restore-nr","0","both","none");
        check(Snapshots.restoreCommands(new JSONObject(),0).isEmpty(),"invalid snapshot produces no commands");
        for(String field:new String[]{"lte","nrSa","nrNsa","gsm","wcdma"})check(!Snapshots.canRestore(snapshot().put(field,"9999")),"range checked for "+field);
        check(!Snapshots.canRestore(snapshot().put("lte","3,1")),"noncanonical snapshot rejected");
        check(Snapshots.differences(snapshot(),state()).isEmpty(),"matching state has no restore differences");
        ModemState different=state();different.lte.set(249);different.legacyMask="0000abcd12345679";
        check(Snapshots.differences(snapshot(),different).equals(List.of("LTE 频段","其他原始频段配置")),"specific restore differences reported");
    }

    private static void testModemParsing() throws Exception {
        ModemState empty = ModemState.parse(new JSONObject());
        check(!empty.prefKnown && !empty.hwKnown && !empty.lteKnown && !empty.nrKnown, "empty JSON stays unknown");
        check(empty.lte == null && empty.nrSa == null && empty.legacyMask == null, "missing data is not synthesized");
        ModemState ack = ModemState.parse(new JSONObject().put("nrCell", new JSONObject()
                .put("known", false).put("ackOnly", true).put("type", -1)));
        check(!ack.nrKnown && ack.nrAckOnly && !ack.nrCellLocked(), "NR ACK-only stays unknown and not confirmed locked");
        check(!"未锁定".equals(ack.nrCellSummary()), "NR ACK-only summary cannot claim unlocked");

        ModemState lteNone = ModemState.parse(new JSONObject().put("lteCell", new JSONObject().put("known", true).put("cells", array())));
        check(lteNone.lteKnown && !lteNone.lteCellLocked(), "explicit LTE empty array confirms no cell lock");
        ModemState lteMissing = ModemState.parse(new JSONObject().put("lteCell", new JSONObject().put("known", true)));
        check(!lteMissing.lteKnown, "known flag without LTE cells cannot imply unlocked");
        ModemState invalidLte = ModemState.parse(new JSONObject().put("lteCell", new JSONObject().put("known", true)
                .put("cells", new JSONArray().put(new JSONObject().put("earfcn", 1650).put("pci", 504)))));
        check(!invalidLte.lteKnown, "invalid LTE PCI is unknown");
        ModemState nullLte = ModemState.parse(new JSONObject().put("lteCell", new JSONObject().put("known", true)
                .put("cells", new JSONArray().put(JSONObject.NULL))));
        check(!nullLte.lteKnown, "null LTE entry is rejected without crash");
        ModemState nrMissing = ModemState.parse(new JSONObject().put("nrCell", new JSONObject().put("known", true).put("type", 0)));
        check(!nrMissing.nrKnown, "known flag without NR PCI payload remains unknown");
        ModemState nrFreqMissing = ModemState.parse(new JSONObject().put("nrCell", new JSONObject().put("known", true).put("type", 1)));
        check(!nrFreqMissing.nrKnown, "NR frequency type without its list remains unknown");
        ModemState nrNone = ModemState.parse(new JSONObject().put("nrCell", new JSONObject().put("known", true).put("type", 2)));
        check(nrNone.nrKnown && !nrNone.nrCellLocked(), "explicit NR type2 is unlocked");
        ModemState nrValid = ModemState.parse(new JSONObject().put("nrCell", new JSONObject().put("known", true)
                .put("type", 0).put("arfcn", 633984).put("pci", 202).put("band", 78).put("scs", 30)));
        check(nrValid.nrKnown && nrValid.nrCellLocked() && nrValid.nrArfcn == 633984, "complete NR PCI payload is retained");

        for (int bad : new int[]{-1, 0, 1901}) {
            JSONObject json = stateJson(); json.getJSONObject("pref").put("lte", array(1, bad));
            check(ModemState.parse(json).lte == null, "invalid band list rejects value " + bad);
        }
        JSONObject json = stateJson(); json.getJSONObject("pref").put("known", false);
        ModemState unknownPref = ModemState.parse(json);
        check(!unknownPref.prefKnown && unknownPref.lte == null && !Snapshots.matches(snapshot(), unknownPref),
                "unknown preference does not expose or confirm its accompanying arrays");
        ModemResult onlyCell = ModemResult.parse(new JSONObject().put("ok", true).put("lteCell",
                new JSONObject().put("known", true).put("cells", array())));
        check(onlyCell.state != null && onlyCell.state.lteKnown, "cell-only response keeps a parsed state");
        ModemState inherited = ModemState.parse(new JSONObject()); inherited.inheritHardware(state());
        check(inherited.hwKnown && inherited.hwLte.get(1) && !inherited.prefKnown,
                "hardware inheritance does not invent preference success");
    }

    public static void main(String[] args) throws Exception {
        testSnapshotValidation();
        testExactMatches();
        testRestoreCommands();
        testModemParsing();
        OperationReport operation=new OperationReport("恢复初始配置","SIM 1");
        operation.add("LTE",ModemResult.parse(new JSONObject().put("ok",true).put("stage","verified").put("mutated",false)));
        operation.add("5G 小区锁",ModemResult.parse(new JSONObject().put("ok",false).put("stage","unknown").put("message","请求已接受，锁状态无法确认")));
        check(!operation.ok()&&operation.outcome().equals("部分完成"),"unknown lock preserves successful settings");
        OperationReport decoded=OperationReport.decode(operation.encode());
        check(decoded!=null&&decoded.summary().equals(operation.summary())&&decoded.items.get(1).status.equals("unknown"),"structured results survive rotation serialization");
        OperationReport refused=new OperationReport("解除锁定","SIM 2");refused.add("5G 小区锁",ModemResult.parse(new JSONObject().put("stage","rejected").put("message","已成功")));
        check(!refused.ok()&&refused.outcome().equals("请求被拒绝"),"Chinese receipt cannot override rejected stage");
        ModemResult reset=ModemResult.parse(new JSONObject().put("ok",false).put("stage","unverified").put("steps",new JSONArray()
            .put(new JSONObject().put("name","lte").put("status","unchanged"))
            .put(new JSONObject().put("name","nrCell").put("status","rejected"))));
        OperationReport partial=new OperationReport("开放全部支持范围","SIM 1");partial.add("开放",reset);
        check(partial.items.size()==2&&partial.items.get(0).confirmed()&&!partial.items.get(1).confirmed()&&partial.outcome().equals("部分完成"),"reset uses per-field structured checks");
        LogRecord record=new LogRecord("12:00:00","SIM 1 · 恢复",operation);
        check(LogRecord.decode(record.encode()).display().equals(record.display()),"logs preserve time, target, status and details");
        check(LogRecord.decode("旧日志").text.equals("旧日志"),"previous saved logs remain readable");
        operation.restoreStatus="matched";check(operation.dialogTitle().startsWith("配置已恢复")&&OperationReport.decode(operation.encode()).restoreStatus.equals("matched"),"confirmed configuration is distinguished from unlock failure");
        operation.restoreStatus="mismatch";check(operation.dialogTitle().equals("尚未完整恢复初始配置"),"explicit restore mismatch has a specific conclusion");
        check(!ModemResult.parse(new JSONObject().put("nrCombinedOnly",true).put("ok",false)).nrCombinedOnly,"accepted combined write without verification does not establish capability");
        check(ModemResult.parse(new JSONObject().put("nrCombinedOnly",true).put("steps",new JSONArray().put(new JSONObject().put("name","nr").put("status","verified")))).nrCombinedOnly,"verified NR reset step can establish capability even when unlock fails");
        JSONObject report = new JSONObject().put("success", failures.isEmpty()).put("checks", checks)
                .put("failures", new JSONArray(failures)).put("modemCommandsSent", 0);
        System.out.println(report);
        if (!failures.isEmpty()) throw new AssertionError("Snapshots/Models: " + failures.size() + " failures");
    }
}
