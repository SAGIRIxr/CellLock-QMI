package com.celllock.app;

import android.content.Context;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;

/** 仅开放经过实测的频段 EFS 白名单；不接收任意路径或内容。 */
final class BandDefaults {
    private BandDefaults() {}
    static String scope() { return Build.FINGERPRINT + "|" + Build.getRadioVersion(); }
    static JSONObject request(Context context, String action, String token) throws Exception {
        if (!action.matches("get-[0-7]|unlink-[0-3]|sync|sync-status") || token != null && !token.matches("[0-9]{1,10}")) throw new IllegalArgumentException("EFS 参数无效");
        File file = new File(context.getApplicationInfo().nativeLibraryDir, "libcelllock_efs.so");
        if (!file.isFile()) throw new IllegalStateException("缺少频段清除组件");
        String output = RootShell.runDiagnostic(file, action + (token == null ? "" : " " + token), 22);
        JSONObject result = null;
        for (String line : output.split("\\r?\\n")) if (line.trim().startsWith("{")) try { result = new JSONObject(line); } catch (Exception ignored) { }
        if (result == null || !result.optBoolean("success") || !result.optString("error").isEmpty()) throw new IllegalStateException("EFS 请求或退出未确认：" + (result == null ? output : result.optString("message")));
        return result;
    }
    static byte[] response(JSONObject result, int command, int min) throws Exception {
        String hex = result.optString("responseHex");
        if (!hex.matches("[0-9a-f]+") || hex.length() % 2 != 0) throw new IllegalStateException("EFS 回包格式无效");
        byte[] bytes = new byte[hex.length()/2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
        if (bytes.length < min || bytes[0] != 0x4b || bytes[1] != 0x13 || (bytes[2]&255) != command || bytes[3] != 0) throw new IllegalStateException("EFS 回包类型或长度不符");
        return bytes;
    }
    static long u32(byte[] bytes, int offset) { long value = 0; for (int i = 0; i < 4; i++) value |= (long)(bytes[offset+i]&255) << (i*8); return value; }
    static JSONObject evidence(Context context) {
        JSONObject result = new JSONObject(); JSONArray items = new JSONArray();
        try {
            result.put("scope", scope());
            for (int i = 0; i < 8; i++) {
                JSONObject item = new JSONObject().put("index", i).put("presence", "unknown");
                try {
                    JSONObject raw = request(context, "get-"+i, null); byte[] data = response(raw,39,14); long error = u32(data,8), length = u32(data,4);
                    int expected = i%4<2 ? 64 : i%4==2 ? 8 : 24;
                    if ((data[12]&255) != 0 || data[13] != 0) throw new IllegalStateException("EFS 序列不符");
                    if (error == 2) item.put("presence","absent");
                    else if (error == 0 && length == expected && data.length == 14+expected) item.put("presence","present").put("bytes",raw.optString("responseHex").substring(28));
                    else item.put("error",error);
                } catch (Exception error) { item.put("message",error.getMessage()); items.put(item); break; }
                items.put(item);
            }
            result.put("efs",items);
        } catch (Exception ignored) { }
        return result;
    }
    static boolean allAbsent(JSONObject evidence) {
        JSONArray items = evidence == null ? null : evidence.optJSONArray("efs");
        if (items == null || items.length()!=8) return false;
        for (int i=0;i<8;i++) if (items.optJSONObject(i)==null || items.optJSONObject(i).optInt("index",-1)!=i || !items.optJSONObject(i).optString("presence").equals("absent")) return false;
        return true;
    }
}
