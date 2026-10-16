package com.celllock.app;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.BitSet;

/** 保存与规划只使用内存，不访问 Root、设备或网络。 */
public final class SavedCellsTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static SharedPreferences prefs(Map<String,String> store,boolean[] writable){
        return (SharedPreferences)Proxy.newProxyInstance(SavedCellsTest.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(p,m,a)->{
            if(m.getName().equals("getString"))return store.getOrDefault(a[0],(String)a[1]);
            if(m.getName().equals("contains"))return store.containsKey(a[0]);
            if(m.getName().equals("edit")){
                Map<String,String> pending=new HashMap<>();
                return Proxy.newProxyInstance(SavedCellsTest.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(e,em,ea)->{
                    if(em.getName().equals("putString")){pending.put((String)ea[0],(String)ea[1]);return e;}
                    if(em.getName().equals("commit")){if(writable[0])store.putAll(pending);return writable[0];}
                    throw new AssertionError(em.getName());
                });
            }throw new AssertionError(m.getName());
        });
    }
    private static JSONObject cell()throws Exception{return new JSONObject().put("schema",1).put("nr",true).put("arfcn",633984).put("pci",202).put("band",78).put("scs",30).put("identity",-1).put("seenAt",1);}
    private static BitSet bits(int...values){BitSet out=new BitSet();for(int v:values)out.set(v);return out;}
    public static void main(String[] args)throws Exception{
        Map<String,String> store=new HashMap<>();boolean[] writable={true};SharedPreferences prefs=prefs(store,writable);
        check(SavedCells.save(prefs,0,10,cell()," 办公室 "),"save historical target");JSONObject first=SavedCells.list(prefs,0,10).get(0);String id=first.getString("id");long created=first.getLong("createdAt");
        check(first.getString("note").equals("办公室")&&SavedCells.list(prefs,1,20).isEmpty(),"note and SIM isolation");
        check(SavedCells.save(prefs,0,10,cell(),"更新")&&SavedCells.list(prefs,0,10).size()==1,"same target updated instead of duplicated");
        check(SavedCells.find(prefs,0,10,id).getLong("createdAt")==created,"stable identity and created time");
        check(SavedCells.save(prefs,0,10,cell().put("arfcn",634000),"相同 PCI")&&SavedCells.list(prefs,0,10).size()==2,"PCI alone does not deduplicate");
        check(SavedCells.change(prefs,0,10,id,"备注")&&SavedCells.find(prefs,0,10,id).getString("note").equals("备注"),"edit chosen entry");
        check(!SavedCells.change(prefs,1,20,id,null),"cannot delete other SIM");
        writable[0]=false;check(!SavedCells.change(prefs,0,10,id,null)&&!SavedCells.save(prefs,0,10,cell(),"失败"),"commit failures surfaced");writable[0]=true;
        check(SavedCells.change(prefs,0,10,id,null)&&SavedCells.find(prefs,0,10,id)==null,"delete chosen target");
        store.put("cells_0_30","broken");check(!SavedCells.save(prefs,0,30,cell(),"")&&store.get("cells_0_30").equals("broken"),"corrupt data preserved");
        check(!SavedCells.save(prefs,2,10,cell(),"")&&!SavedCells.save(prefs,0,10,cell(),"x".repeat(201)),"boundary validation");
        check(SavedCells.save(prefs,0,10,cell().put("arfcn",Integer.MAX_VALUE).put("pci",Integer.MAX_VALUE).put("band",0).put("scs",0),"不完整"),"不完整参数可保存，表单补齐");
        check(!SavedCells.valid(cell().put("arfcn","633984;id"))&&!SavedCells.valid(cell().put("nr","true"))&&!SavedCells.valid(cell().put("scs",25)),"malformed stored targets rejected");
        ModemState state=new ModemState();state.prefKnown=state.hwKnown=true;state.rat=Rat.LTE|Rat.NR;state.nrMode=0;state.lte=bits(3,250);state.nrSa=bits(28,257);state.nrNsa=bits(28,257);state.hwLte=bits(3);state.hwNr=bits(28,78);state.hwGsm=bits(850);state.hwWcdma=bits(1);
        state.readAttempted=true;state.prefKnown=false;JSONObject unknown=Snapshots.capture(state);check(Snapshots.valid(unknown)&&!Snapshots.canRestore(unknown),"unknown records are valid but never synthesized into restore commands");
        check(Snapshots.save(prefs,0,10,state,"未知标记")&&Snapshots.list(prefs,0,10).size()==1,"D3 permits explicit unknown snapshot");
        JSONObject nr=new JSONObject().put("known",true).put("type",0).put("arfcn",633984).put("pci",202).put("band",41).put("bands",new JSONArray().put(41).put(78).put(257)).put("scs",30);
        JSONArray cells=new JSONArray().put(new JSONObject().put("earfcn",1650).put("pci",42)).put(new JSONObject().put("earfcn",1850).put("pci",43));
        ModemState locked=ModemState.parse(new JSONObject().put("lteCell",new JSONObject().put("known",true).put("cells",cells)).put("nrCell",nr));
        JSONObject saved=Snapshots.capture(locked);List<String[]> restore=Snapshots.restoreCommands(saved,0);
        check(restore.size()==2&&restore.get(0)[0].equals("cell-lte-list")&&restore.get(0)[2].equals("1650,42,1850,43"),"complete LTE list replay");
        check(restore.get(1)[0].equals("cell-nr-mask")&&restore.get(1)[4].equals("41,78,257"),"NR full band mask replay");
        JSONArray reordered=new JSONArray().put(cells.getJSONObject(1)).put(cells.getJSONObject(0));
        ModemState current=ModemState.parse(new JSONObject().put("lteCell",new JSONObject().put("known",true).put("cells",reordered)).put("nrCell",nr));
        check(Snapshots.matches(saved,current),"LTE firmware reordering accepted");current.lteCells[0][1]++;check(!Snapshots.matches(saved,current),"different LTE payload rejected");
        JSONObject nrFreq=new JSONObject().put("known",true).put("type",1).put("arfcns",new JSONArray().put(new JSONObject().put("arfcn",633984).put("scs",30)).put(new JSONObject().put("arfcn",158290).put("scs",15)));
        JSONObject freq=Snapshots.capture(ModemState.parse(new JSONObject().put("nrCell",nrFreq)));
        check(Snapshots.restoreCommands(freq,0).get(0)[2].equals("633984,30,158290,15"),"complete NR frequency/SCS list replay");
        JSONObject unsupported=Snapshots.capture(ModemState.parse(new JSONObject().put("nrCell",new JSONObject().put("known",true).put("type",3))));
        check(Snapshots.valid(unsupported)&&!Snapshots.canRestore(unsupported)&&Snapshots.describe(unsupported).contains("5G 锁类型 3"),"unsupported lock is recorded only");
        JSONObject invalid=Snapshots.capture(locked);invalid.getJSONObject("nrCell").put("bands",new JSONArray().put(513));check(!Snapshots.valid(invalid),"invalid band mask does not fall back to first band");
        JSONArray tooMany=new JSONArray();for(int i=0;i<65;i++)tooMany.put(cells.getJSONObject(0));check(!ModemState.parse(new JSONObject().put("lteCell",new JSONObject().put("known",true).put("cells",tooMany))).lteKnown,"LTE length bounded");
        JSONArray evidence=new JSONArray();for(int i=0;i<8;i++)evidence.put(new JSONObject().put("index",i).put("presence","absent"));check(BandDefaults.allAbsent(new JSONObject().put("efs",evidence)),"all eight absent verified");evidence.getJSONObject(5).put("presence","unknown");check(!BandDefaults.allAbsent(new JSONObject().put("efs",evidence)),"unknown EFS never absence");
        byte[] packet=BandDefaults.response(new JSONObject().put("responseHex","4b132700ffffffff020000000000"),39,14);check(BandDefaults.u32(packet,8)==2,"EFS errno position verified");boolean rejected=false;try{BandDefaults.response(new JSONObject().put("responseHex","4b13080000000000"),39,14);}catch(Exception e){rejected=true;}check(rejected,"EFS wrong command rejected");
        check(!SavedCells.valid(cell().put("arfcn",633984.5)),"fractional saved parameter rejected without truncation");
        check(!ModemState.parse(new JSONObject().put("nrCell",new JSONObject(nr.toString()).put("arfcn",633984.5))).nrKnown,"fractional lock readback cannot be confirmed");
        System.out.println("PASS: "+checks+" saved target / snapshot / operation checks (memory only)");
    }
}
