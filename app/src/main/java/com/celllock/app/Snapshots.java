package com.celllock.app;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * 每张 SIM 第一次读到的频段与制式设置。“恢复初始设置”按它原样写回，
 * 这样即使原配置里有本机不支持的位，也能恢复得和改动前一模一样。
 */
final class Snapshots {
    private Snapshots() {}

    private static String key(int slot, int subscriptionId) {
        return "snapshot_" + slot + "_" + subscriptionId;
    }

    static void rememberIfFirst(SharedPreferences prefs, int slot, int subscriptionId, ModemState s) {
        if (!complete(s) || prefs.contains(key(slot, subscriptionId))) return;
        try {
            JSONObject json = new JSONObject()
                    .put("schema", 2)
                    .put("time", System.currentTimeMillis())
                    .put("rat", s.rat)
                    .put("nrMode", s.nrMode)
                    .put("legacyMask", s.legacyMask)
                    .put("lte", s.lte == null ? "" : ModemState.arg(s.lte))
                    .put("nrSa", s.nrSa == null ? "" : ModemState.arg(s.nrSa))
                    .put("nrNsa", s.nrNsa == null ? "" : ModemState.arg(s.nrNsa))
                    .put("gsm", s.gsm == null ? "" : ModemState.arg(s.gsm))
                    .put("wcdma", s.wcdma == null ? "" : ModemState.arg(s.wcdma));
            prefs.edit().putString(key(slot, subscriptionId), json.toString()).commit();
        } catch (Exception ignored) {
        }
    }

    static JSONObject load(SharedPreferences prefs, int slot, int subscriptionId) {
        try {
            String raw = prefs.getString(key(slot, subscriptionId), null);
            return raw == null ? null : new JSONObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    static void forget(SharedPreferences prefs, int slot, int subscriptionId) {
        prefs.edit().remove(key(slot, subscriptionId)).apply();
    }

    private static boolean complete(ModemState s) {
        return s != null && s.prefKnown && s.hwKnown && s.rat > 0 && (s.rat & ~Rat.KNOWN) == 0
                && s.legacyMask != null && s.legacyMask.matches("[0-9a-fA-F]{16}")
                && s.nrMode >= 0 && s.nrMode <= 2
                && restorable(s.lte, s.hwLte) && restorable(s.nrSa, s.hwNr)
                && restorable(s.nrNsa, s.hwNr) && restorable(s.gsm, s.hwGsm) && restorable(s.wcdma, s.hwWcdma);
    }

    private static boolean restorable(BitSet selected, BitSet hardware) {
        if (selected == null || hardware == null) return false;
        return hardware.isEmpty() ? selected.isEmpty() : selected.intersects(hardware);
    }

    static boolean canRestore(JSONObject snap) {
        if (snap == null || snap.optInt("schema") != 2) return false;
        if (!snap.optString("legacyMask", "").matches("[0-9a-fA-F]{16}")) return false;
        int rat = snap.optInt("rat", -1), mode = snap.optInt("nrMode", -1);
        if (rat <= 0 || (rat & ~Rat.KNOWN) != 0 || mode < 0 || mode > 2) return false;
        for (String field : new String[]{"lte", "nrSa", "nrNsa", "gsm", "wcdma"}) {
            if (!snap.has(field) || snap.isNull(field)) return false;
            String value = snap.optString(field);
            if (!value.isEmpty() && !value.matches("[0-9]{1,4}(,[0-9]{1,4})*")) return false;
        }
        return true;
    }

    static boolean matches(JSONObject snap, ModemState current) {
        if (!canRestore(snap) || current == null || !current.prefKnown
                || snap.optInt("rat", -1) != current.rat || snap.optInt("nrMode", -1) != current.nrMode) return false;
        BitSet[] actual = {current.lte, current.nrSa, current.nrNsa, current.gsm, current.wcdma};
        String[] fields = {"lte", "nrSa", "nrNsa", "gsm", "wcdma"};
        for (int i = 0; i < fields.length; i++) {
            if (actual[i] == null || !snap.optString(fields[i]).equals(ModemState.arg(actual[i]))) return false;
        }
        String legacy = snap.optString("legacyMask", "");
        return legacy.equalsIgnoreCase(current.legacyMask);
    }

    /** 恢复快照需要依次执行的后端命令。 */
    static List<String[]> restoreCommands(JSONObject snap, int slot) {
        String s = String.valueOf(slot);
        List<String[]> steps = new ArrayList<>();
        String lte = snap.optString("lte"), sa = snap.optString("nrSa"), nsa = snap.optString("nrNsa");
        String gsm = snap.optString("gsm"), wcdma = snap.optString("wcdma");
        if (!lte.isEmpty()) steps.add(new String[]{"band-lte", s, lte});
        if (!sa.isEmpty() && sa.equals(nsa)) {
            steps.add(new String[]{"band-nr", s, "both", sa});
        } else {
            if (!sa.isEmpty()) steps.add(new String[]{"band-nr", s, "sa", sa});
            if (!nsa.isEmpty()) steps.add(new String[]{"band-nr", s, "nsa", nsa});
        }
        if (!gsm.isEmpty()) steps.add(new String[]{"band-gsm", s, gsm});
        if (!wcdma.isEmpty()) steps.add(new String[]{"band-wcdma", s, wcdma});
        int nrMode = snap.optInt("nrMode", -1);
        if (nrMode >= 0 && nrMode <= 2) steps.add(new String[]{"nr-mode", s, new String[]{"both", "nsa", "sa"}[nrMode]});
        String rats = Rat.arg(snap.optInt("rat", 0));
        if (!rats.isEmpty()) steps.add(new String[]{"rat", s, rats});
        steps.add(new String[]{"cell-clear", s, "all"});
        return steps;
    }

    static String describe(JSONObject snap) {
        BitSet lte = new BitSet();
        for (String b : snap.optString("lte").split(",")) if (!b.isEmpty()) lte.set(Integer.parseInt(b));
        return "制式 " + Rat.describe(snap.optInt("rat")) + "，LTE " + lte.cardinality() + " 个频段位";
    }
}
