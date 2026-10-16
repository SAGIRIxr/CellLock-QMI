package com.celllock.app;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.UUID;

/** 快照保留原始频段位；未知项只记录，恢复时跳过。 */
final class Snapshots {
    static final int MAX_NOTE_LENGTH = SavedRecords.MAX_NOTE_LENGTH;
    private static final String[] BANDS = {"lte", "nrSa", "nrNsa", "gsm", "wcdma"};
    private static final String[] NAMES = {"LTE 频段", "5G SA 频段", "5G NSA 频段", "GSM 频段", "WCDMA 频段"};
    private Snapshots() {}
    private static String key(int slot, int sub) { return SavedRecords.key("snapshot", slot, sub); }

    static synchronized void rememberIfFirst(SharedPreferences prefs, int slot, int sub, ModemState state) {
        if (!prefs.contains(key(slot, sub)) && complete(state)) save(prefs, slot, sub, state, "首次自动保存");
    }
    static synchronized boolean save(SharedPreferences prefs, int slot, int sub, ModemState state, String note) {
        return save(prefs, slot, sub, state, note, null);
    }
    static synchronized boolean save(SharedPreferences prefs, int slot, int sub, ModemState state, String note, JSONObject evidence) {
        if (!SavedRecords.validSim(slot, sub) || !SavedRecords.validNote(note) || state == null || !state.readAttempted) return false;
        try {
            List<JSONObject> records = list(prefs, slot, sub);
            JSONObject record = capture(state).put("id", UUID.randomUUID().toString()).put("note", note.trim()).put("time", System.currentTimeMillis());
            if (!valid(record)) return false;
            if (evidence != null) record.put("evidence", evidence);
            records.add(0, record);
            return SavedRecords.store(prefs, key(slot, sub), records);
        } catch (Exception error) { return false; }
    }
    static JSONObject capture(ModemState state) throws Exception {
        JSONObject record = new JSONObject().put("schema", 3);
        record.put("rat", state.prefKnown && state.rat > 0 && (state.rat & ~Rat.KNOWN) == 0 ? state.rat : JSONObject.NULL);
        record.put("nrMode", state.prefKnown && state.nrMode >= 0 && state.nrMode <= 2 ? state.nrMode : JSONObject.NULL);
        record.put("legacyMask", state.prefKnown && state.legacyMask != null && state.legacyMask.matches("[0-9a-fA-F]{16}") ? state.legacyMask : JSONObject.NULL);
        BitSet[] values = {state.lte, state.nrSa, state.nrNsa, state.gsm, state.wcdma};
        for (int i = 0; i < BANDS.length; i++) record.put(BANDS[i], state.prefKnown && values[i] != null ? ModemState.arg(values[i]) : JSONObject.NULL);
        JSONArray cells = new JSONArray();
        if (state.lteKnown && state.lteCells != null) for (int[] cell : state.lteCells) cells.put(new JSONObject().put("earfcn", cell[0]).put("pci", cell[1]));
        record.put("lteCell", new JSONObject().put("known", state.lteKnown).put("cells", cells));
        JSONObject nr = state.nrRecorded == null ? new JSONObject().put("known", false).put("type", -1) : new JSONObject(state.nrRecorded.toString());
        nr.put("known", state.nrKnown);
        if (state.nrKnown && state.nrType == ModemState.NR_LOCK_PCI && state.nrBands != null) {
            JSONArray bands = new JSONArray();
            for (int b = state.nrBands.nextSetBit(1); b >= 0; b = state.nrBands.nextSetBit(b + 1)) bands.put(b);
            nr.put("bands", bands);
        }
        record.put("nrCell", nr);
        return record;
    }
    static JSONObject load(SharedPreferences prefs, int slot, int sub) {
        try { List<JSONObject> records = list(prefs, slot, sub); return records.isEmpty() ? null : records.get(0); }
        catch (Exception error) { return null; }
    }
    static List<JSONObject> list(SharedPreferences prefs, int slot, int sub) {
        List<JSONObject> records = SavedRecords.list(prefs, key(slot, sub));
        for (JSONObject record : records) if (!valid(record)) throw new IllegalStateException("快照列表无法读取，已保留原始数据。");
        return records;
    }
    static JSONObject find(SharedPreferences prefs, int slot, int sub, String id) {
        for (JSONObject record : list(prefs, slot, sub)) if (record.optString("id").equals(id)) return record;
        return null;
    }
    static synchronized boolean updateNote(SharedPreferences prefs, int slot, int sub, String id, String note) {
        if (note == null) return false;
        return change(prefs, slot, sub, id, note);
    }
    static synchronized boolean delete(SharedPreferences prefs, int slot, int sub, String id) { return change(prefs, slot, sub, id, null); }
    private static boolean change(SharedPreferences prefs, int slot, int sub, String id, String note) {
        try { return SavedRecords.change(prefs, key(slot, sub), list(prefs, slot, sub), id, note); }
        catch (Exception error) { return false; }
    }
    private static boolean complete(ModemState s) {
        return s != null && s.prefKnown && s.hwKnown && s.rat > 0 && (s.rat & ~Rat.KNOWN) == 0
                && s.legacyMask != null && s.legacyMask.matches("[0-9a-fA-F]{16}") && s.nrMode >= 0 && s.nrMode <= 2
                && restorable(s.lte, s.hwLte) && restorable(s.nrSa, s.hwNr) && restorable(s.nrNsa, s.hwNr)
                && restorable(s.gsm, s.hwGsm) && restorable(s.wcdma, s.hwWcdma);
    }
    private static boolean restorable(BitSet selected, BitSet hardware) {
        return selected != null && hardware != null && (hardware.isEmpty() ? selected.isEmpty() : selected.intersects(hardware));
    }
    static boolean valid(JSONObject record) {
        if (record == null || record.optInt("schema") < 2 || record.optInt("schema") > 3) return false;
        boolean old = record.optInt("schema") == 2;
        for (String field : new String[]{"rat", "nrMode", "legacyMask", "lte", "nrSa", "nrNsa", "gsm", "wcdma"}) {
            if (!record.has(field) || old && record.isNull(field)) return false;
            if (record.isNull(field)) continue;
            if (field.equals("rat")) { int n = record.optInt(field, -1); if (n <= 0 || (n & ~Rat.KNOWN) != 0) return false; }
            else if (field.equals("nrMode")) { int n = record.optInt(field, -1); if (n < 0 || n > 2) return false; }
            else if (field.equals("legacyMask")) { if (!record.optString(field).matches("[0-9a-fA-F]{16}")) return false; }
            else if (!(record.opt(field) instanceof String) || !validBands(field, record.optString(field))) return false;
        }
        if (!old) {
            JSONObject lte = record.optJSONObject("lteCell"), nr = record.optJSONObject("nrCell");
            if (lte == null || nr == null) return false;
            ModemState locks;
            try { locks = ModemState.parse(new JSONObject().put("lteCell", lte).put("nrCell", nr)); } catch (Exception ignored) { return false; }
            if (lte.optBoolean("known") && !locks.lteKnown || nr.optBoolean("known") && !locks.nrKnown) return false;
        }
        return true;
    }
    private static boolean validBands(String field, String value) {
        if (!value.isEmpty() && !value.matches("[0-9]{1,4}(,[0-9]{1,4})*")) return false;
        int previous = 0;
        for (String part : value.isEmpty() ? new String[0] : value.split(",")) {
            int band = Integer.parseInt(part);
            if (band <= previous || band > (field.equals("lte") ? 256 : field.startsWith("nr") ? 512 : field.equals("wcdma") ? 19 : 1900)) return false;
            if (field.equals("gsm") && band != 850 && band != 900 && band != 1800 && band != 1900) return false;
            previous = band;
        }
        return true;
    }
    private static boolean known(JSONObject record, String key) { return record.has(key) && !record.isNull(key); }
    static boolean canRestore(JSONObject record) {
        if (!valid(record)) return false;
        for (String key : new String[]{"rat", "nrMode", "legacyMask", "lte", "nrSa", "nrNsa"}) if (known(record, key)) return true;
        return record.optJSONObject("lteCell") != null && record.optJSONObject("lteCell").optBoolean("known")
                || replayNr(record.optJSONObject("nrCell"));
    }
    private static boolean replayNr(JSONObject nr) { return nr != null && nr.optBoolean("known") && nr.optInt("type", -1) >= 0 && nr.optInt("type", -1) <= 2; }
    static List<String[]> restoreCommands(JSONObject record, int slot) {
        List<String[]> steps = new ArrayList<>();
        if (!canRestore(record) || slot < 0 || slot > 1) return steps;
        String sim = String.valueOf(slot);
        if (known(record, "lte")) steps.add(new String[]{"restore-lte", sim, maskArg(record.optString("lte"))});
        if (known(record, "nrSa") && known(record, "nrNsa") && record.optString("nrSa").equals(record.optString("nrNsa"))) steps.add(new String[]{"restore-nr", sim, "both", maskArg(record.optString("nrSa"))});
        else for (String[] pair : new String[][]{{"nrSa", "sa"}, {"nrNsa", "nsa"}}) if (known(record, pair[0])) steps.add(new String[]{"restore-nr", sim, pair[1], maskArg(record.optString(pair[0]))});
        if (known(record, "legacyMask")) steps.add(new String[]{"restore-legacy", sim, record.optString("legacyMask").toLowerCase(Locale.ROOT)});
        if (known(record, "nrMode")) steps.add(new String[]{"nr-mode", sim, new String[]{"both", "nsa", "sa"}[record.optInt("nrMode")]});
        if (known(record, "rat")) steps.add(new String[]{"rat", sim, Rat.arg(record.optInt("rat"))});
        JSONObject lte = record.optJSONObject("lteCell"), nr = record.optJSONObject("nrCell");
        if (lte != null && lte.optBoolean("known")) {
            JSONArray cells = lte.optJSONArray("cells");
            if (cells.length() == 0) steps.add(new String[]{"cell-clear", sim, "lte"});
            else { StringBuilder args = new StringBuilder(); for (int i = 0; i < cells.length(); i++) { JSONObject c = cells.optJSONObject(i); if (args.length() > 0) args.append(','); args.append(c.optInt("earfcn")).append(',').append(c.optInt("pci")); } steps.add(new String[]{"cell-lte-list", sim, args.toString()}); }
        }
        if (replayNr(nr)) {
            if (nr.optInt("type") == 2) steps.add(new String[]{"cell-clear", sim, "nr"});
            else if (nr.optInt("type") == 0) { BitSet mask = new BitSet(); JSONArray bands = nr.optJSONArray("bands"); if (bands == null) mask.set(nr.optInt("band")); else for (int i = 0; i < bands.length(); i++) mask.set(bands.optInt(i)); steps.add(new String[]{"cell-nr-mask", sim, String.valueOf(nr.optLong("arfcn")), String.valueOf(nr.optInt("pci")), ModemState.arg(mask), String.valueOf(nr.optInt("scs"))}); }
            else { JSONArray cells = nr.optJSONArray("arfcns"); StringBuilder args = new StringBuilder(); for (int i = 0; i < cells.length(); i++) { JSONObject c = cells.optJSONObject(i); if (args.length() > 0) args.append(','); args.append(c.optInt("arfcn")).append(',').append(c.optInt("scs")); } steps.add(new String[]{"freq-nr-list", sim, args.toString()}); }
        }
        return steps;
    }
    private static String maskArg(String value) { return value.isEmpty() ? "none" : value; }
    static boolean matches(JSONObject record, ModemState current) { return differences(record, current).isEmpty(); }
    static List<String> differences(JSONObject record, ModemState current) {
        List<String> out = new ArrayList<>();
        if (!canRestore(record) || current == null) { out.add("无法核对已保存配置"); return out; }
        BitSet[] values = {current.lte, current.nrSa, current.nrNsa, current.gsm, current.wcdma};
        for (int i = 0; i < BANDS.length; i++) if ((i < 3 || known(record,"legacyMask")) && known(record, BANDS[i]) && (!current.prefKnown || values[i] == null || !record.optString(BANDS[i]).equals(ModemState.arg(values[i])))) out.add(NAMES[i]);
        if (known(record, "legacyMask") && (!current.prefKnown || !record.optString("legacyMask").equalsIgnoreCase(current.legacyMask))) out.add("其他原始频段配置");
        if (known(record, "rat") && (!current.prefKnown || record.optInt("rat") != current.rat)) out.add("网络制式");
        if (known(record, "nrMode") && (!current.prefKnown || record.optInt("nrMode") != current.nrMode)) out.add("5G 组网");
        JSONObject lte = record.optJSONObject("lteCell"), nr = record.optJSONObject("nrCell");
        try {
            JSONObject actual = capture(current);
            if (lte != null && lte.optBoolean("known") && (!current.lteKnown || !pairs(lte.optJSONArray("cells"), "earfcn", "pci").equals(pairs(actual.getJSONObject("lteCell").optJSONArray("cells"), "earfcn", "pci")))) out.add("LTE 小区锁");
            if (replayNr(nr) && (!current.nrKnown || !nrKey(nr).equals(nrKey(actual.getJSONObject("nrCell"))))) out.add("5G 小区/频点锁");
        } catch (Exception error) { out.add("小区锁回读不完整"); }
        return out;
    }
    private static String pairs(JSONArray array, String a, String b) {
        List<String> entries = new ArrayList<>();
        if (array != null) for (int i = 0; i < array.length(); i++) { JSONObject cell = array.optJSONObject(i); entries.add(cell.optLong(a) + ":" + cell.optLong(b)); }
        java.util.Collections.sort(entries);
        return entries.toString();
    }
    private static String nrKey(JSONObject nr) {
        int type = nr.optInt("type", -1);
        if (type == 2) return "none";
        if (type == 1) return "freq:" + pairs(nr.optJSONArray("arfcns"), "arfcn", "scs");
        TreeSet<Integer> bands = new TreeSet<>(); JSONArray mask = nr.optJSONArray("bands");
        if (mask == null) bands.add(nr.optInt("band")); else for (int i = 0; i < mask.length(); i++) bands.add(mask.optInt(i));
        return type + ":" + nr.optLong("arfcn") + ":" + nr.optInt("pci") + ":" + nr.optInt("scs") + ":" + bands;
    }
    static String describe(JSONObject record) {
        List<String> known = new ArrayList<>(), unknown = new ArrayList<>(), only = new ArrayList<>();
        String[] keys = {"lte", "nrSa", "nrNsa", "legacyMask", "rat", "nrMode"};
        String[] labels = {"LTE 频段", "SA 频段", "NSA 频段", "2G/3G 原始配置", "制式", "5G 组网"};
        for (int i = 0; i < keys.length; i++) (known(record, keys[i]) ? known : unknown).add(labels[i]);
        JSONObject lte = record.optJSONObject("lteCell"), nr = record.optJSONObject("nrCell");
        (lte != null && lte.optBoolean("known") ? known : unknown).add("LTE 小区锁");
        if (replayNr(nr)) known.add("5G 小区/频点锁"); else if (nr != null && nr.optBoolean("known")) only.add("5G 锁类型 " + nr.optInt("type")); else unknown.add("5G 小区/频点锁");
        only.add("设备/固件、底层文件（EFS）检查信息（如有；不会自动恢复）");
        return "可恢复：" + (known.isEmpty() ? "无" : String.join("、", known)) + (unknown.isEmpty() ? "" : "\n未知，跳过：" + String.join("、", unknown)) + "\n仅记录：" + String.join("、", only);
    }
}
