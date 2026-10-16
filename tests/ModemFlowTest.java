package com.celllock.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.json.JSONObject;

/** Explicit hardware acceptance; drains pending work before attempting every preference recovery. */
public final class ModemFlowTest extends Instrumentation {
    private Activity activity;
    private int checks,slot,sub;
    private Object session;
    private JSONObject baseline;
    private void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private void main(Runnable action){Throwable[] error={null};runOnMainSync(()->{try{action.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new AssertionError(error[0]);waitForIdleSync();}
    private Object call(Object target,String name,Class<?>[] types,Object... args)throws Exception{Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(target,args);}
    private void invoke(Object target,String name,Class<?>[] types,Object... args){try{call(target,name,types,args);}catch(Exception error){throw new AssertionError(error);}}
    private <T>T field(Object target,String name){try{return UiFlowTest.field(target,name);}catch(Exception error){throw new AssertionError(error);}}
    private static List<TextView> texts(View view){List<TextView> all=new ArrayList<>();if(view instanceof TextView)all.add((TextView)view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)all.addAll(texts(((ViewGroup)view).getChildAt(i)));return all;}
    private AlertDialog dialog(){return field(activity,"messageDialog");}
    private void button(String text){main(()->{Button button=(Button)texts(dialog().getWindow().getDecorView()).stream().filter(t->t instanceof Button&&t.getText().toString().equals(text)).findFirst().orElseThrow();button.performClick();});}
    private void page(int index){main(()->invoke(activity,"showPage",new Class<?>[]{int.class},index));}
    private void waitComplete()throws Exception{for(int i=0;i<400;i++){if(!(boolean)call(session,"running",new Class<?>[0])){waitForIdleSync();return;}SystemClock.sleep(100);}throw new AssertionError("operation timeout");}
    private ModemState state()throws Exception{return (ModemState)call(session,"state",new Class<?>[]{int.class},sub);}
    private OperationReport report(){OperationReport report=OperationReport.decode(field(activity,"messageBody"));check(report!=null,"result dialog missing structured report");return report;}
    private static boolean includes(java.util.BitSet allowed,java.util.BitSet supported){java.util.BitSet missing=(java.util.BitSet)supported.clone();missing.andNot(allowed);return missing.isEmpty();}
    private void capture(String name)throws Exception{
        waitForIdleSync();SystemClock.sleep(2300);Bitmap bitmap=getUiAutomation().takeScreenshot();check(bitmap!=null,"screenshot unavailable");
        try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),"validation-"+name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
    }
    private void choose(boolean all){
        main(()->invoke(activity,"chooseRestore",new Class<?>[0]));
        main(()->{TextView title=texts(dialog().getWindow().getDecorView()).stream().filter(t->t.getText().toString().equals(all?"开放全部支持频段":"读取配置")).findFirst().orElseThrow();((View)title.getParent().getParent()).performClick();});
        if(!all){
            main(()->{ScrollView scroll=field(activity,"dialogScrollView");ViewGroup list=(ViewGroup)scroll.getChildAt(0);check(list.getChildCount()>0,"saved snapshot list is empty");list.getChildAt(0).performClick();});
            main(()->{TextView title=texts(dialog().getWindow().getDecorView()).stream().filter(t->t.getText().toString().equals("读取配置")).findFirst().orElseThrow();((View)title.getParent().getParent()).performClick();});
        }
    }
    private static JSONObject snapshot(ModemState s)throws Exception{return new JSONObject().put("schema",2).put("rat",s.rat).put("nrMode",s.nrMode).put("legacyMask",s.legacyMask).put("lte",ModemState.arg(s.lte)).put("nrSa",ModemState.arg(s.nrSa)).put("nrNsa",ModemState.arg(s.nrNsa)).put("gsm",ModemState.arg(s.gsm)).put("wcdma",ModemState.arg(s.wcdma));}
    private void recover()throws Exception{
        if(baseline==null)return;
        recoverBaseline(field(session,"worker"),baseline,slot,args->Backend.run(getTargetContext(),args));
    }
    static void recoverBaseline(ExecutorService worker,JSONObject baseline,int slot,Function<String[],ModemResult> backend)throws Exception{
        if(baseline==null)return;
        boolean interrupted=Thread.interrupted();
        try{
            // Drain the whole Session task; per-command Backend locking cannot stop its later writes.
            if(worker!=null){
                worker.shutdown();
                while(true)try{if(worker.awaitTermination(Long.MAX_VALUE,TimeUnit.NANOSECONDS))break;}
                catch(InterruptedException ignored){interrupted=true;}
            }
            AssertionError failures=new AssertionError("baseline recovery failed");boolean matches=false;
            String[] read={"state",String.valueOf(slot)};
            try{ModemResult current=backend.apply(read);if(!current.ok)throw new AssertionError("initial recovery read failed: "+current.message);matches=Snapshots.matches(baseline,current.state);}
            catch(Exception|AssertionError error){failures.addSuppressed(error);}
            if(!matches)for(String[] command:Snapshots.restoreCommands(baseline,slot)){
                if(command[0].equals("cell-clear"))continue;
                try{ModemResult result=backend.apply(command);if(!result.ok)throw new AssertionError(command[0]+": "+result.message);}
                catch(Exception|AssertionError error){failures.addSuppressed(error);}
            }
            try{ModemResult result=backend.apply(read);if(!result.ok||!Snapshots.matches(baseline,result.state))throw new AssertionError("original preferences not fully restored: "+String.join(", ",Snapshots.differences(baseline,result.state)));}
            catch(Exception|AssertionError error){failures.addSuppressed(error);}
            if(failures.getSuppressed().length>0)throw failures;
        }finally{
            if(interrupted)Thread.currentThread().interrupt();
        }
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle output=new Bundle();Throwable failure=null;
        try{
            activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));session=field(activity,"SESSION");
            boolean trusted=false;for(int i=0;i<120;i++){trusted=(boolean)call(activity,"trustedSim",new Class<?>[0]);if(trusted)break;SystemClock.sleep(100);}check(trusted,"no trusted SIM");
            sub=field(activity,"subscriptionId");slot=field(activity,"selectedSlot");check(slot==0,"this explicit acceptance expects SIM 1 baseline");
            page(2);waitComplete();check(state()!=null&&state().hwKnown,"device page first entry failed to read configuration");
            baseline=snapshot(state());check(state().nrSa.equals(state().nrNsa),"cannot safely test combined bands with differing baseline SA/NSA");capture("device-auto");
            int probeBand=state().nrSa.cardinality()==1&&state().nrSa.get(41)?1:41;
            String history=(String)call(session,"rawHistory",new Class<?>[0]);page(0);page(2);waitComplete();check(history.equals(call(session,"rawHistory",new Class<?>[0])),"page switches repeated initial configuration read");
            page(1);LockPage locks=field(activity,"locks");
            main(()->{
                Ui.Segmented section=field(locks,"sectionTabs"),bands=field(locks,"bandTabs");section.getChildAt(1).performClick();bands.getChildAt(0).performClick();
                ModemFlowTest.this.<Button>field(locks,"bandNone").performClick();
                List<View> chips=field(locks,"bandChips");TextView target=(TextView)chips.stream().filter(v->v instanceof TextView&&((TextView)v).getText().toString().startsWith("n"+probeBand+"\n")).findFirst().orElseThrow();target.performClick();
                ModemFlowTest.this.<Button>field(locks,"bandApply").performClick();
            });
            capture("band-confirm");button("应用频段");waitComplete();
            check(report().ok()&&state().nrSa.cardinality()==1&&state().nrSa.get(probeBand)&&state().nrSa.equals(state().nrNsa),"App band apply not verified for both modes");capture("band-result");button("知道了");
            check(!(boolean)call(session,"nrCombinedOnly",new Class<?>[]{int.class},sub),"independent NR writes incorrectly classified as combined only");
            main(()->{Ui.Segmented target=field(locks,"nrTargetTabs");check(target.getChildAt(1).isEnabled()&&target.getChildAt(2).isEnabled(),"independent controls disabled despite successful independent writes");});capture("bands-independent");
            main(()->invoke(activity,"chooseRestore",new Class<?>[0]));capture("restore-choice");button("关闭");
            choose(false);capture("restore-confirm");button("应用配置");waitComplete();
            JSONObject saved=Snapshots.load(getTargetContext().getSharedPreferences("celllock",0),slot,sub);
            check(Snapshots.matches(saved,state()),"App restore did not reproduce full saved preferences");
            check(report().outcome().equals("部分完成")&&report().items.stream().anyMatch(i->i.name.equals("快照已知配置核对")&&i.confirmed()),"restored configuration not distinguished from skipped unknown NR lock");capture("restore-result");button("知道了");
            for(boolean nr:new boolean[]{false,true}){
                main(()->{Ui.Segmented cell=field(locks,"cellTabs");cell.select(nr?0:1);invoke(locks,"updateCellUi",new Class<?>[0]);ModemFlowTest.this.<Button>field(locks,"cellClear").performClick();});
                button("直接清除");waitComplete();OperationReport result=report();
                check(nr?result.items.get(0).status.equals("rejected"):result.items.get(0).status.equals("unchanged"),"incorrect unlock result for "+(nr?"NR":"LTE"));capture(nr?"unlock-rejected":"unlock-noop");button("知道了");
            }
            int beforeRat=state().rat,beforeMode=state().nrMode;String beforeLteLock=state().lteCellSummary(),beforeNrLock=state().nrCellSummary();
            choose(true);button("直接开放");waitComplete();
            check(includes(state().lte,state().hwLte)&&includes(state().nrSa,state().hwNr)&&includes(state().nrNsa,state().hwNr),"opening allows all supported bands; already open raw defaults can remain wider");
            check(state().rat==beforeRat&&state().nrMode==beforeMode&&state().lteCellSummary().equals(beforeLteLock)&&state().nrCellSummary().equals(beforeNrLock),"band opening preserves RAT, NR mode and cell locks");capture("open-bands-result");button("知道了");
            page(2);DevicePage device=field(activity,"device");main(()->ModemFlowTest.this.<Button>field(device,"logsButton").performClick());
            main(()->{ScrollView scroll=field(activity,"dialogScrollView");scroll.fullScroll(View.FOCUS_DOWN);});capture("logs");
            main(()->{List<TextView> buttons=texts(dialog().getWindow().getDecorView()).stream().filter(t->t instanceof Button&&t.getText().toString().equals("展开查看详情")).collect(java.util.stream.Collectors.toList());check(!buttons.isEmpty(),"operation log details missing");buttons.get(buttons.size()-1).performClick();ScrollView scroll=field(activity,"dialogScrollView");scroll.fullScroll(View.FOCUS_DOWN);});capture("logs-expanded");button("知道了");
        }catch(Throwable error){failure=error;}
        finally{
            try{recover();}catch(Throwable error){if(failure==null)failure=error;else failure.addSuppressed(error);}
            if(activity!=null)main(()->{if(dialog()!=null)dialog().dismiss();activity.finish();});
        }
        output.putString("stream",failure==null?"PASS: "+checks+" App hardware acceptance checks; original preferences fully restored\n":android.util.Log.getStackTraceString(failure));
        finish(failure==null?Activity.RESULT_OK:Activity.RESULT_CANCELED,output);
    }
}
