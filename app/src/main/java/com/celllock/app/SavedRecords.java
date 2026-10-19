package com.celllock.app;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** 配置与小区记录共用本地写入、备注及删除，数据校验留在各自模型。 */
final class SavedRecords {
    static final int MAX_NOTE_LENGTH = 200;
    private SavedRecords() {}
    static String key(String kind, int slot, int sub) { return kind + "_" + slot + "_" + sub; }
    static boolean validSim(int slot, int sub) { return slot >= 0 && slot <= 1 && sub >= 0; }
    static boolean validNote(String note) { return note != null && note.length() <= MAX_NOTE_LENGTH; }
    static List<JSONObject> list(SharedPreferences prefs, String key) {
        List<JSONObject> out = new ArrayList<>();
        String raw = prefs.getString(key, null);
        if (raw == null) return out;
        try {
            JSONArray entries = raw.trim().startsWith("[") ? new JSONArray(raw)
                    : new JSONArray().put(new JSONObject(raw).put("id", "legacy"));
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                if (entry.optString("id").isEmpty()) throw new IllegalArgumentException();
                out.add(entry);
            }
            return out;
        } catch (Exception error) { throw new IllegalStateException("保存记录无法读取，已保留原始数据。", error); }
    }
    static boolean store(SharedPreferences prefs, String key, List<JSONObject> records) {
        return prefs.edit().putString(key, new JSONArray(records).toString()).commit();
    }
    static boolean change(SharedPreferences prefs, String key, List<JSONObject> records, String id, String note) throws Exception {
        if (note != null && !validNote(note)) return false;
        for (int i = 0; i < records.size(); i++) if (records.get(i).optString("id").equals(id)) {
            if (note == null) records.remove(i);
            else records.get(i).put("note", note.trim());
            return store(prefs, key, records);
        }
        return false;
    }
}
