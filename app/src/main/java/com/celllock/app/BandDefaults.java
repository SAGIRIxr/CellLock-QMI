package com.celllock.app;

import android.content.Context;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;

/** 固定清理操作与快照证据共用传输；不接收任意路径、内容或 DIAG 帧。 */
final class BandDefaults {
    private BandDefaults() {}
    static String scope() { return Build.FINGERPRINT + "|" + Build.getRadioVersion(); }
    static final class RequestFailure extends IllegalStateException {
        final String status;
        RequestFailure(String status,String message){super(message);this.status=status;}
    }
    static JSONObject request(Context context, String action, String token) throws Exception {
        if (!action.matches("(?:get|stat|unlink)-(?:[0-9]|1[0-7])|put-lte-restrict|reload-once|sync|sync-status") || token != null && !token.matches("[0-9]{1,10}")) throw new IllegalArgumentException("EFS 参数无效");
        File file = new File(context.getApplicationInfo().nativeLibraryDir, "libcelllock_efs.so");
        if (!file.isFile()) throw new IllegalStateException("缺少固定清理组件");
        String output = RootShell.runDiagnostic(file, action + (token == null ? "" : " " + token), 22);
        JSONObject result = null;
        for (String line : output.split("\\r?\\n")) if (line.trim().startsWith("{")) try { result = new JSONObject(line); } catch (Exception ignored) { }
        if(result==null)throw new RequestFailure("unknown","未收到 EFS 完成回执："+output);
        if(!result.optString("error").isEmpty()||result.optInt("releaseCode",-1)!=1001||result.optInt("deinitCode",-1)!=1) {
            String status=result.optInt("sendCode",-1)==-1?"failed":"unknown";
            throw new RequestFailure(status,"请求或会话退出未确认："+result);
        }
        if(!result.optBoolean("success"))throw new RequestFailure(result.optInt("sendCode",-1)==1001?"unknown":"rejected","固定请求未完成："+result);

        return result;
    }
    static byte[] response(JSONObject result, int command, int min) throws Exception {
        String hex = result.optString("responseHex");
        if (!hex.matches("[0-9a-f]+") || hex.length() % 2 != 0) throw new IllegalStateException("EFS 回包格式无效");
        byte[] bytes = new byte[hex.length()/2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
        if (bytes.length < min || command!=39 && bytes.length!=min || bytes[0] != 0x4b || bytes[1] != 0x13 || (bytes[2]&255) != command || bytes[3] != 0) throw new IllegalStateException("EFS 回包类型或长度不符");
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
    interface Request { JSONObject run(String action,String token) throws Exception; }
    private static String itemName(int index) {
        if(index<8)return (index<4?"主订阅 ":"副订阅 ")+new String[]{"NR SA 频段偏好","NR NSA 频段偏好","LTE 频段偏好","LTE 扩展频段偏好"}[index%4];
        return new String[]{"主订阅制式偏好","副订阅制式偏好","LTE 持久 PCI 锁","NR 持久 PCI 锁","主订阅 NR 持久频点锁","副订阅 NR 持久频点锁","GSM 持久频点锁","WCDMA 持久频点锁","副订阅 WCDMA PSC 锁","LTE 限制结构"}[index-8];
    }
    static int[] cleanupItems(int slot) {
        if(slot==0)return new int[]{0,1,2,3,8,10,11,12,14,15};
        if(slot==1)return new int[]{4,5,6,7,9,13,16};
        throw new IllegalArgumentException("固定清理布局只支持已明确映射的两个订阅");
    }
    static boolean clearEfs(Request request,int slot,OperationReport report,Runnable verify) throws Exception {
        boolean changed=false;
        for(int index:cleanupItems(slot)) {
            verify.run();
            byte[] stat=response(request.run("stat-"+index,null),15,32);
            long error=u32(stat,4);
            if(error==2){report.items.add(new ResultItem(itemName(index),"unchanged","固定项原本缺失"));continue;}
            if(error!=0)throw new IllegalStateException(itemName(index)+" 无法核验，errno="+error);
            verify.run();
            byte[] data=response(request.run("unlink-"+index,null),8,8);error=u32(data,4);
            report.items.add(new ResultItem(itemName(index),error==0?"accepted":error==2?"unchanged":"rejected","UNLINK errno="+error));
            if(error!=0&&error!=2)return false;
            changed|=error==0;
        }
        if(slot==0) {
            verify.run();
            byte[] stat=response(request.run("stat-17",null),15,32);long error=u32(stat,4);
            if(error!=0&&error!=2)throw new IllegalStateException("LTE 限制结构不可读，errno="+error);
            boolean written=false;
            // 仅清零已确认的36字节结构；未知布局停止，不新建缺失项。
            if(error==0) {
                verify.run();byte[] original=response(request.run("get-17",null),39,50);
                if(original.length!=50||u32(original,4)!=36||u32(original,8)!=0||original[12]!=0||original[13]!=0)throw new IllegalStateException("LTE 限制结构布局未确认，未覆盖未知内容");
                for(int i=14;i<50;i++)written|=original[i]!=0;
                if(written) {
                    verify.run();byte[] put=response(request.run("put-lte-restrict",null),38,8);
                    if(u32(put,4)!=0){report.items.add(new ResultItem("LTE 限制结构","rejected","PUT errno="+u32(put,4)));return false;}
                    changed=true;
                }
            }
            report.items.add(new ResultItem("LTE 限制结构",written?"accepted":"unchanged",written?"固定零结构写入已接受，待同步回读":error==2?"原本缺失":"已是 36 字节零结构，无需写入"));
        } else report.items.add(new ResultItem("无明确副订阅映射的持久项","unknown","未操作主项 LTE PCI / 限制结构、NR PCI、GSM 及 WCDMA 频点项；这些项的副订阅布局未确认，不据此宣称全部锁类已清除。"));
        if(changed) {
            verify.run();
            byte[] sync=response(request.run("sync",null),48,14);
            if(sync[4]!=1||sync[5]!=0||u32(sync,10)!=0)throw new IllegalStateException("EFS 同步请求未确认");
            String token=String.valueOf(u32(sync,6));boolean complete=false;
            for(int i=0;i<8;i++) {
                verify.run();
                byte[] status=response(request.run("sync-status",token),49,11);
                if(status[4]!=1||status[5]!=0||u32(status,7)!=0)break;
                if(status[6]==1){complete=true;break;}
                if(status[6]!=0)break;
                Thread.sleep(200);
            }
            report.items.add(new ResultItem("EFS 持久化",complete?"verified":"unknown",complete?"SYNC＋STATUS 已完成":"同步尚未确认，不发送重载"));
            if(!complete)return false;
        } else report.items.add(new ResultItem("EFS 持久化","unchanged","无持久变更，无需同步；完整清除仍发送一次重载以解除运行时限制。"));
        for(int index:cleanupItems(slot)) {
            verify.run();byte[] stat=response(request.run("stat-"+index,null),15,32);
            if(u32(stat,4)!=2){report.items.add(new ResultItem(itemName(index)+"回读","unknown","删除后未确认缺失，不发送重载"));return false;}
        }
        if(slot==0) {
            verify.run();byte[] stat=response(request.run("stat-17",null),15,32);
            if(u32(stat,4)==0) {
                verify.run();byte[] get=response(request.run("get-17",null),39,50);
                if(get.length!=50||u32(get,4)!=36||u32(get,8)!=0||get[12]!=0||get[13]!=0)return false;
                for(int i=14;i<50;i++)if(get[i]!=0)return false;
            } else if(u32(stat,4)!=2)return false;
        }
        verify.run();report.items.add(new ResultItem("固定持久项核对","verified","目标订阅已覆盖项缺失或已清零；不代表其他未知锁项已清除。"));return true;
    }
    private static void reload(Request request,OperationReport report) throws Exception {
        JSONObject result=request.run("reload-once",null);
        if(!"4b250300".equals(result.optString("responseHex")))throw new IllegalStateException("重载回执不完整，结果未知；未重复发送");
        report.items.add(new ResultItem("基带重载请求","accepted","固定请求已收到回包，诊断会话安全退出；未等待基带恢复。"));
    }
    static void clearComplete(Request request,int slot,ModemState before,OperationReport report,
            java.util.function.Function<String[],ModemResult> modem,Runnable verify,Runnable beforeReload) throws Exception {
        Request transport=request;
        request=(action,token)->{try{JSONObject reply=transport.run(action,token);report.detail("EFS "+action,reply.toString());return reply;}catch(Exception error){report.detail("EFS "+action,error.toString());throw error;}};
        cleanupItems(slot);
        if(before.hwKnown&&before.hwNr!=null&&before.hwNr.isEmpty())report.items.add(new ResultItem("5G 组网","not_applicable","硬件已明确无 5G 能力"));
        else if(before.prefKnown&&before.nrMode>=0&&before.nrMode<=2) {
            ModemResult both=modem.apply(new String[]{"nr-mode","both"});report.add("重载前解除组网",both);
            if(!both.ok)throw new IllegalStateException("组网解除未确认，已停止后续清理。");
            ModemResult read=modem.apply(new String[]{"state"});
            if(!read.ok||read.state==null||read.state.nrMode!=0)throw new IllegalStateException("重载前组网回读未知或非 SA＋NSA，已停止清理。");
        } else throw new IllegalStateException("5G 组网能力未知，未假定为无 5G；已停止清理。");
        // 本机实测SSR会保留QMI LTE锁；已知存在时先复用运行时清除。
        if(before.lteCellLocked()) {
            ModemResult clear=modem.apply(new String[]{"cell-clear","lte"});report.add("重载前清除 LTE 小区锁",clear);
            if(!clear.ok)throw new IllegalStateException("已知 LTE 小区锁未解除，已停止后续清理。");
        }
        if(!clearEfs(request,slot,report,verify))throw new IllegalStateException("固定清理、同步或回读未完成，未发送重载。");
        verify.run();
        // SSR一旦尝试，收尾不得再触发订阅核验或服务恢复读取。
        beforeReload.run();report.recoveryUnverified=true;
        try{reload(request,report);}
        catch(Exception error){
            String status=error instanceof RequestFailure?((RequestFailure)error).status:error instanceof RootShell.Failure&&!((RootShell.Failure)error).started?"failed":"unknown";
            report.items.add(new ResultItem("基带重载请求",status,error.getMessage()==null?error.toString():error.getMessage()));
        }
        report.items.add(new ResultItem("恢复状态","unknown","清理已完成；重载请求及安全退出结果见上方。未等待基带恢复，未核验最终锁态或联网。"));
    }

}
