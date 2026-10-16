package com.celllock.app;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Exercises the production cleanup gates with in-memory EFS replies; no device access. */
public final class BandDefaultsTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static JSONObject reply(int command,int length,int error)throws Exception{
        byte[] bytes=new byte[length];bytes[0]=0x4b;bytes[1]=0x13;bytes[2]=(byte)command;
        bytes[4]=(byte)error;StringBuilder hex=new StringBuilder();for(byte b:bytes)hex.append(String.format("%02x",b&255));
        return new JSONObject().put("responseHex",hex.toString());
    }
    private static final class Efs implements BandDefaults.Request {
        final List<String> calls=new ArrayList<>();final Set<Integer> present=new HashSet<>();
        int reject=-1,statReject=-1;String reloadFailure;boolean syncReject,complete=true,reappear,putReject,restrictCleared,invalidRestrict;
        public JSONObject run(String action,String token)throws Exception{
            calls.add(action);
            if(action.equals("reload-once")){if(reloadFailure!=null)throw new BandDefaults.RequestFailure(reloadFailure,"simulated reload response failure");return new JSONObject().put("responseHex","4b250300");}
            if(action.startsWith("stat-")){int i=Integer.parseInt(action.substring(5));return reply(15,32,i==statReject?13:present.contains(i)?0:2);}
            if(action.startsWith("unlink-")){int i=Integer.parseInt(action.substring(7));if(i==reject)return reply(8,8,13);if(!reappear)present.remove(i);return reply(8,8,0);}
            if(action.equals("put-lte-restrict")){restrictCleared=true;return reply(38,8,putReject?13:0);}
            if(action.equals("get-17"))return new JSONObject().put("responseHex","4b13270024000000000000000000"+(invalidRestrict?"":(restrictCleared?"00":"01")+"00".repeat(35)));
            if(action.equals("sync"))return new JSONObject().put("responseHex",syncReject?"4b13300001000700000032010000":"4b13300001000700000000000000");
            if(action.equals("sync-status")){check("7".equals(token),"sync token forwarded unchanged");return new JSONObject().put("responseHex",complete?"4b13310001000100000000":"4b13310001000200000000");}
            throw new AssertionError("unexpected command "+action);
        }
    }
    public static void main(String[] args)throws Exception{
        Efs e=new Efs();OperationReport report=new OperationReport("clear","");
        check(BandDefaults.clearEfs(e,0,report,()->{}),"all absent completes");
        check(e.calls.stream().noneMatch(c->c.startsWith("unlink")||c.startsWith("sync")||c.startsWith("put")),"all absent does not mutate or sync");
        e=new Efs();e.present.addAll(Set.of(0,8,10,11,12,14,15,17));
        check(BandDefaults.clearEfs(e,0,new OperationReport("", ""),()->{}),"primary fixed cleanup completes");
        check(e.calls.indexOf("sync")>e.calls.indexOf("put-lte-restrict"),"sync follows last write");
        check(e.calls.contains("sync-status")&&e.calls.contains("get-17"),"PUT verified after sync");
        check(e.calls.stream().noneMatch(c->c.equals("unlink-9")||c.equals("unlink-13")||c.equals("unlink-16")),"primary never deletes secondary");
        e=new Efs();e.present.addAll(Set.of(4,5,6,7,9,13,16));
        check(BandDefaults.clearEfs(e,1,new OperationReport("", ""),()->{}),"secondary fixed cleanup completes without device identity gate");
        check(e.calls.stream().noneMatch(c->c.endsWith("-8")||c.endsWith("-10")||c.endsWith("-11")||c.equals("put-lte-restrict")),"secondary does not infer shared path ownership");
        e=new Efs();e.present.add(0);e.reject=0;
        check(!BandDefaults.clearEfs(e,0,new OperationReport("", ""),()->{}),"rejected unlink blocks continuation");
        check(e.calls.size()==2,"no calls after rejection");
        e=new Efs();e.statReject=0;boolean failed=false;
        try{BandDefaults.clearEfs(e,0,new OperationReport("", ""),()->{});}catch(IllegalStateException expected){failed=true;}
        check(failed&&e.calls.size()==1,"unknown presence does not become absence");
        e=new Efs();e.present.add(0);e.syncReject=true;failed=false;
        try{BandDefaults.clearEfs(e,0,new OperationReport("", ""),()->{});}catch(IllegalStateException expected){failed=true;}
        check(failed&&!e.calls.contains("sync-status"),"errno306 is failure");
        e=new Efs();e.present.add(0);e.complete=false;
        check(!BandDefaults.clearEfs(e,0,new OperationReport("", ""),()->{}),"incomplete sync blocks reload gate");
        e=new Efs();e.present.add(0);e.reappear=true;
        check(!BandDefaults.clearEfs(e,0,new OperationReport("", ""),()->{}),"reappeared item fails independent final check");
        e=new Efs();e.present.add(17);e.invalidRestrict=true;failed=false;
        try{BandDefaults.clearEfs(e,0,new OperationReport("", ""),()->{});}catch(IllegalStateException expected){failed=true;}
        check(failed&&!e.calls.contains("put-lte-restrict"),"unknown LTE restrict layout never overwritten");
        e=new Efs();e.present.add(17);e.restrictCleared=true;
        check(BandDefaults.clearEfs(e,0,new OperationReport("", ""),()->{})&&!e.calls.contains("put-lte-restrict")&&!e.calls.contains("sync"),"already zero restrict structure does not mutate or sync");
        e=new Efs();e.present.add(17);e.putReject=true;
        check(!BandDefaults.clearEfs(e,0,new OperationReport("", ""),()->{}),"PUT rejection blocks sync/reload");
        failed=false;try{BandDefaults.cleanupItems(2);}catch(IllegalArgumentException expected){failed=true;}check(failed,"unsupported layout not guessed");
        ModemResult accepted=ModemResult.parse(new JSONObject().put("ok",false).put("stage","unknown").put("writes",1).put("accepted",1).put("result",0).put("error",0));
        check(accepted.acceptedUnknown(),"accepted unknown is not request rejection");
        check(!ModemResult.failure("unknown","", "").acceptedUnknown(),"missing response never means accepted");
        report=new OperationReport("完整清除","");report.recoveryUnverified=true;report.items.add(new ResultItem("恢复状态","unknown",""));
        check(OperationReport.decode(report.encode()).recoveryUnverified&&!report.ok()&&report.dialogTitle().contains("未核验"),"unverified completion survives result encoding");
        ModemState state=new ModemState();state.hwKnown=true;state.hwLte=new java.util.BitSet();state.hwNr=new java.util.BitSet();state.hwGsm=new java.util.BitSet();state.hwWcdma=new java.util.BitSet();
        List<String[]> quick=LockPlan.quickClear(state);check(quick.size()==2&&quick.get(0)[1].equals("lte")&&quick.get(1)[1].equals("nr"),"empty band plan still clears cells in order");
        state.hwLte.set(3);quick=LockPlan.quickClear(state);check(quick.size()==3&&quick.get(0)[0].equals("band-lte"),"quick plan reuses open supported before clear");
        for(String failure:new String[]{null,"unknown","failed","rejected"}) {
            Efs full=new Efs();full.reloadFailure=failure;full.present.add(8);
            ModemState marked=new ModemState();marked.prefKnown=true;marked.nrMode=2;marked.lteKnown=true;marked.lteCells=new int[][]{{1650,111}};
            OperationReport completed=new OperationReport("完整清除","");
            List<String> order=new ArrayList<>();int[] invalidations={0};
            BandDefaults.clearComplete(full,0,marked,completed,command->{
                String cmd=String.join(" ",command);order.add(cmd);check(full.calls.isEmpty(),"all QMI before EFS cleanup");
                ModemState result=new ModemState();result.prefKnown=true;result.nrMode=0;
                return new ModemResult(true,"verified","",false,result,"");
            },()->{check(!full.calls.contains("reload-once"),"no verification after SSR attempt");},()->{invalidations[0]++;order.add("invalidate");});
            check(order.equals(List.of("nr-mode both","state","cell-clear lte","invalidate")),"group and known LTE runtime clear precede EFS/reload boundary");
            check(full.calls.get(full.calls.size()-1).equals("reload-once")&&full.calls.stream().filter(c->c.equals("reload-once")).count()==1,"one SSR and no recovery reads, even on failure");
            check(completed.recoveryUnverified&&invalidations[0]==1,"cache invalidated at SSR boundary");
            String expected=failure==null?"accepted":failure;
            check(completed.items.stream().anyMatch(i->i.name.equals("基带重载请求")&&i.status.equals(expected)),"reload outcome classification");
        }
        Efs stopped=new Efs();ModemState noGroup=new ModemState();boolean blocked=false;
        try{BandDefaults.clearComplete(stopped,0,noGroup,new OperationReport("", ""),command->{throw new AssertionError("unknown group must not write");},()->{},()->{});}catch(IllegalStateException expected){blocked=true;}
        check(blocked&&stopped.calls.isEmpty(),"unknown 5G capability stops before mutation");
        noGroup.hwKnown=true;noGroup.hwNr=new java.util.BitSet();OperationReport no5g=new OperationReport("", "");
        BandDefaults.clearComplete(stopped,0,noGroup,no5g,command->{throw new AssertionError("confirmed no 5G must not set group");},()->{},()->{});
        check(no5g.items.stream().anyMatch(i->i.name.equals("5G 组网")&&i.status.equals("not_applicable")),"explicit no 5G is not applicable");
        Efs denied=new Efs();ModemState group=new ModemState();group.prefKnown=true;group.nrMode=1;blocked=false;
        try{BandDefaults.clearComplete(denied,0,group,new OperationReport("", ""),command->ModemResult.failure("rejected","no", ""),()->{},()->{});}catch(IllegalStateException expected){blocked=true;}
        check(blocked&&denied.calls.isEmpty(),"group rejection blocks EFS and SSR");
        Efs lteDenied=new Efs();group.lteKnown=true;group.lteCells=new int[][]{{1650,111}};blocked=false;
        try{BandDefaults.clearComplete(lteDenied,0,group,new OperationReport("", ""),command->{
            if(command[0].equals("cell-clear"))return ModemResult.failure("rejected","LTE clear refused","");
            ModemState ready=new ModemState();ready.nrMode=0;return new ModemResult(true,"verified","",false,ready,"");
        },()->{},()->{});}catch(IllegalStateException expected){blocked=true;}
        check(blocked&&lteDenied.calls.isEmpty(),"known LTE runtime clear rejection blocks persistent writes/SSR");
        System.out.println("BandDefaults: "+checks+" checks passed");
    }
}
