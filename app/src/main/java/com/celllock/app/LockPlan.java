package com.celllock.app;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

final class LockPlan {
    private LockPlan() {}
    static int targetBand(String[] command) {
        try { if(command[0].equals("cell-nr"))return Integer.parseInt(command[3]); if(command[0].equals("cell-lte"))return Bands.lteBandForEarfcn(Integer.parseInt(command[1])); }
        catch(Exception ignored) { }
        return 0;
    }
    static boolean excluded(ModemState state,String[] command) {
        int band=targetBand(command); if(state==null||!state.prefKnown||band<=0)return false;
        if(command[0].equals("cell-lte"))return state.lte!=null&&!state.lte.get(band);
        return state.nrSa!=null&&state.nrNsa!=null&&(!state.nrSa.get(band)||!state.nrNsa.get(band));
    }
    static List<String[]> append(ModemState state,String[] command) {
        int band=targetBand(command); List<String[]> steps=new ArrayList<>();
        if(state==null||!state.prefKnown||band<=0)throw new IllegalStateException("无法确认目标频段，已停止追加与锁定");
        BitSet[] masks=command[0].equals("cell-lte")?new BitSet[]{state.lte}:new BitSet[]{state.nrSa,state.nrNsa};
        for(int i=0;i<masks.length;i++) {
            if(masks[i]==null)throw new IllegalStateException("当前完整允许列表未知，已停止追加与锁定");
            if(masks[i].get(band))continue;
            BitSet expanded=(BitSet)masks[i].clone();expanded.set(band);
            steps.add(masks.length==1?new String[]{"restore-lte",ModemState.arg(expanded)}:new String[]{"restore-nr",i==0?"sa":"nsa",ModemState.arg(expanded)});
        }
        if(steps.size()==2&&steps.get(0)[2].equals(steps.get(1)[2])){String value=steps.get(0)[2];steps.clear();steps.add(new String[]{"restore-nr","both",value});}
        return steps;
    }
    static List<String[]> openSupported(ModemState state) {
        if(state==null||!state.hwKnown||state.hwLte==null||state.hwNr==null||state.hwGsm==null||state.hwWcdma==null)throw new IllegalStateException("硬件支持频段未读完整，未执行开放");
        List<String[]> commands=new ArrayList<>();
        if(!state.hwLte.isEmpty())commands.add(new String[]{"band-lte","all"});
        if(!state.hwNr.isEmpty())commands.add(new String[]{"band-nr","both","all"});
        if(!state.hwGsm.isEmpty())commands.add(new String[]{"band-gsm","all"});
        if(!state.hwWcdma.isEmpty())commands.add(new String[]{"band-wcdma","all"});
        return commands;
    }
}
