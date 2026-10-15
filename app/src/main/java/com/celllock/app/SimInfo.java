package com.celllock.app;

import android.telephony.SubscriptionInfo;
import org.json.JSONObject;

/** Android 订阅和 modem 卡槽分别保存，UIM-only 身份不伪造 subId。 */
final class SimInfo {
    final int subscriptionId, slot;
    final String label, source;

    SimInfo(int subscriptionId, int slot, String label, String source) {
        if (subscriptionId < -1 || slot < 0 || slot > 7) throw new IllegalArgumentException("无效 SIM 上下文");
        this.subscriptionId = subscriptionId;
        this.slot = slot;
        this.label = label == null || label.trim().isEmpty() ? "SIM " + (slot + 1) : label;
        this.source = source;
    }

    static SimInfo from(SubscriptionInfo info, String source) {
        CharSequence carrier = info.getCarrierName();
        return new SimInfo(info.getSubscriptionId(), info.getSimSlotIndex(),
                "SIM " + (info.getSimSlotIndex() + 1) + (carrier == null || carrier.length() == 0 ? "" : " · " + carrier), source);
    }

    JSONObject json() throws Exception {
        return new JSONObject().put("subscription", subscriptionId).put("slot", slot).put("label", label);
    }

    static SimInfo parse(JSONObject json, String source) throws Exception {
        return new SimInfo(json.getInt("subscription"), json.getInt("slot"), json.optString("label"), source);
    }

    boolean trusted() { return subscriptionId >= 0 && slot <= 1; }
    String key() { return subscriptionId + ":" + slot; }
}
