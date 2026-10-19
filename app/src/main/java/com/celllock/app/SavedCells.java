package com.celllock.app;

import android.content.SharedPreferences;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.List;
import java.util.UUID;

final class SavedCells {
    private SavedCells() {}
    private static String key(int slot, int sub) { return SavedRecords.key("cells", slot, sub); }
    static List<JSONObject> list(SharedPreferences prefs, int slot, int sub) {
        List<JSONObject> records = SavedRecords.list(prefs, key(slot, sub));
        for (JSONObject record : records) if (!valid(record)) throw new IllegalStateException("保存小区无法读取，已保留原始数据。");
        return records;
    }
    static JSONObject find(SharedPreferences prefs, int slot, int sub, String id) {
        for (JSONObject record : list(prefs, slot, sub)) if (record.optString("id").equals(id)) return record;
        return null;
    }
    static JSONObject observation(RadioCell cell, RadioCell serving) throws Exception {
        long now = SystemClock.elapsedRealtime();
        long seen = cell.timestamp > 0 && cell.timestamp <= now ? System.currentTimeMillis() - (now - cell.timestamp) : 0;
        String scs = cell.details.getOrDefault("SCS", "").replaceAll("[^0-9]", "");
        int spacing = scs.matches("15|30|60|120|240") ? Integer.parseInt(scs) : 0;
        return new JSONObject().put("schema", 1).put("nr", cell.nr).put("arfcn", cell.arfcn)
                .put("pci", cell.pci).put("band", cell.targetBand(serving)).put("scs", spacing)
                .put("source", cell.source).put("seenAt", seen).put("identity", cell.identity)
                .put("rsrp", cell.rsrp).put("details", new JSONObject(cell.details));
    }
    static synchronized boolean save(SharedPreferences prefs, int slot, int sub, JSONObject observation, String note) {
        if (!SavedRecords.validSim(slot, sub) || !SavedRecords.validNote(note) || !valid(observation)) return false;
        try {
            List<JSONObject> records = list(prefs, slot, sub);
            JSONObject record = new JSONObject(observation.toString());
            String identity = identity(record);
            for (int i = 0; i < records.size(); i++) if (identity(records.get(i)).equals(identity)) {
                JSONObject previous = records.remove(i);
                record.put("id", previous.getString("id")).put("createdAt", previous.optLong("createdAt"));
                break;
            }
            long now = System.currentTimeMillis();
            if (!record.has("id")) record.put("id", UUID.randomUUID().toString()).put("createdAt", now);
            record.put("updatedAt", now).put("time", now).put("note", note.trim());
            records.add(0, record);
            return SavedRecords.store(prefs, key(slot, sub), records);
        } catch (Exception error) { return false; }
    }
    static synchronized boolean change(SharedPreferences prefs, int slot, int sub, String id, String note) {
        try { return SavedRecords.change(prefs, key(slot, sub), list(prefs, slot, sub), id, note); }
        catch (Exception error) { return false; }
    }
    static String identity(JSONObject record) {
        JSONObject details = record.optJSONObject("details");
        return record.optBoolean("nr") + ":" + record.optInt("arfcn", -1) + ":" + record.optInt("pci", -1)
                + ":" + record.optInt("band") + ":" + record.optInt("scs") + ":" + record.optLong("identity", -1)
                + ":" + (details == null ? "" : details.optString("PLMN", details.optString("MCC") + details.optString("MNC")))
                + ":" + (details == null ? "" : details.optString("TAC"));
    }
    static boolean valid(JSONObject record) {
        if (record == null || record.optInt("schema") != 1 || !(record.opt("nr") instanceof Boolean)) return false;
        int scs = record.optInt("scs", -1);
        return ModemState.integer(record.opt("arfcn"),-1,Integer.MAX_VALUE) && ModemState.integer(record.opt("pci"),-1,Integer.MAX_VALUE)
                && ModemState.integer(record.opt("band"),0,record.optBoolean("nr")?512:256) && ModemState.integer(record.opt("scs"),0,240)
                && (scs == 0 || scs == 15 || scs == 30 || scs == 60 || scs == 120 || scs == 240);
    }
    private static String parameter(JSONObject record,String key,int max){int value=record.optInt(key,-1);return value>=0&&value<=max?String.valueOf(value):"待补充";}
    static String describe(JSONObject record) {
        String band = record.optInt("band") > 0 ? (record.optBoolean("nr") ? "n" : "B") + record.optInt("band") : "频段待确认";
        return (record.optBoolean("nr") ? "5G" : "LTE") + " · " + band + " · 频点 " + parameter(record,"arfcn",record.optBoolean("nr")?3279165:65535)
                + " · PCI " + parameter(record,"pci",record.optBoolean("nr")?1007:503);
    }
}
