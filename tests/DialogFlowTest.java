package com.celllock.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Uses actual windows/lifecycle and fixture results; never confirms a modem write. */
public final class DialogFlowTest extends Instrumentation {
    private int checks;
    private Activity activity;
    private SharedPreferences snapshotPrefs;
    private String snapshotKey,snapshotOriginal,cellKey,cellOriginal;
    private void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static List<TextView> texts(View view){List<TextView> all=new ArrayList<>();if(view instanceof TextView)all.add((TextView)view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)all.addAll(texts(((ViewGroup)view).getChildAt(i)));return all;}
    private Object call(String name,Class<?>[] types,Object... args)throws Exception{Method method=MainActivity.class.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(activity,args);}
    private void main(Runnable action){Throwable[] error={null};runOnMainSync(()->{try{action.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new AssertionError(error[0]);waitForIdleSync();}
    private AlertDialog dialog(){try{return UiFlowTest.field(activity,"messageDialog");}catch(Exception e){throw new AssertionError(e);}}
    private Button button(String text){return (Button)texts(dialog().getWindow().getDecorView()).stream().filter(t->t instanceof Button&&t.getText().toString().equals(text)).findFirst().orElseThrow();}
    private void choice(String title){main(()->{TextView text=texts(dialog().getWindow().getDecorView()).stream().filter(t->t.getText().toString().split("\n",2)[0].equals(title)).findFirst().orElseThrow();((View)text.getParent().getParent()).performClick();});}
    private void snapshots(){main(()->{try{call("chooseRestore",new Class<?>[0]);}catch(Exception e){throw new AssertionError(e);}});choice("读取配置");}
    private void backTo(String kind)throws Exception{
        main(()->button("返回").performClick());
        check(dialog()!=null&&dialog().isShowing(),"return closed the window instead of returning to "+kind);
        check(UiFlowTest.<String>field(activity,"dialogKind").equals(kind),"return restored the wrong level: "+kind);
    }
    private void open(String kind,String title,String body){main(()->{try{call("openDialog",new Class<?>[]{String.class,String.class,String.class},kind,title,body);}catch(Exception e){throw new AssertionError(e);}});}
    private void waitForSim(){
        for(int i=0;i<100;i++){
            boolean[] trusted={false};main(()->{try{trusted[0]=(boolean)call("trustedSim",new Class<?>[0]);}catch(Exception e){throw new AssertionError(e);}});
            if(trusted[0])return;SystemClock.sleep(100);
        }
        throw new AssertionError("trusted SIM metadata not ready");
    }
    private void checkHeader()throws Exception{
        int[] top=new int[3],baseline=new int[3];
        for(int page=0;page<3;page++){final int index=page;main(()->{try{call("showPage",new Class<?>[]{int.class},index);}catch(Exception e){throw new AssertionError(e);}});main(()->{try{View sim=UiFlowTest.field(activity,"simSpinner");int[] point=new int[2];sim.getLocationOnScreen(point);top[index]=point[1];TextView title=UiFlowTest.field(activity,"pageTitle");title.getLocationOnScreen(point);baseline[index]=point[1]+title.getBaseline();View config=UiFlowTest.field(activity,"configurationEntry"),saved=UiFlowTest.field(activity,"savedCellsEntry");check(config.getVisibility()==(index==1?View.VISIBLE:View.GONE)&&saved.getVisibility()==(index==0?View.VISIBLE:View.GONE),"header actions appear only on their page");}catch(Exception e){throw new AssertionError(e);}});}
        check(top[0]==top[1]&&top[1]==top[2],"SIM selector keeps the same top on all three pages");
        check(baseline[0]==baseline[1]&&baseline[1]==baseline[2],"page title baselines match with the bookmark hidden");
        Object session=UiFlowTest.field(activity,"SESSION");for(int i=0;i<400&&UiFlowTest.<Boolean>field(session,"running");i++)SystemClock.sleep(100);
        check(!UiFlowTest.<Boolean>field(session,"running"),"header navigation initial read finished");
        LockPage locks=UiFlowTest.field(activity,"locks");
        for(int section=0;section<3;section++){final int index=section;main(()->{try{call("showPage",new Class<?>[]{int.class},1);UiFlowTest.<Ui.Segmented>field(locks,"sectionTabs").select(index);View config=UiFlowTest.field(activity,"configurationEntry");check(config.isEnabled()&&config.getHeight()>=new Ui(activity).dp(48),"configuration entry remains reachable on each lock tab");config.performClick();check(dialog()!=null&&dialog().isShowing()&&texts(dialog().getWindow().getDecorView()).stream().anyMatch(t->t.getText().toString().equals("保存配置")),"header configuration action opens the original manager");dialog().dismiss();}catch(Exception e){throw new AssertionError(e);}});}
    }
    private void recreate(){
        ActivityMonitor monitor=addMonitor(MainActivity.class.getName(),null,false);
        main(()->activity.recreate());Activity next=waitForMonitorWithTimeout(monitor,7000);removeMonitor(monitor);
        check(next!=null,"activity recreation did not finish");activity=next;waitForIdleSync();
        for(int i=0;i<30&&dialog()==null;i++)SystemClock.sleep(100);
        check(dialog()!=null&&dialog().isShowing(),"dialog missing after rotation/recreation");
        waitForSim();
    }
    private void checkFooter(){
        main(()->{
            AlertDialog dialog=dialog();View decor=dialog.getWindow().getDecorView();
            check(decor.getWidth()>0&&decor.getHeight()>0,"dialog not laid out");
            Button done=(Button)texts(decor).stream().filter(t->t instanceof Button&&t.getText().toString().equals("知道了")).findFirst().orElseThrow();
            int[] pos=new int[2],origin=new int[2];done.getLocationOnScreen(pos);decor.getLocationOnScreen(origin);
            check(pos[1]>=origin[1]&&pos[1]+done.getHeight()<=origin[1]+decor.getHeight(),"footer clipped outside dialog");
            check(done.getLayout().getLineCount()>0&&done.getHeight()>=done.getLayout().getHeight()+done.getPaddingTop()+done.getPaddingBottom(),"footer text clipped");
            ScrollView scroll;
            try{scroll=UiFlowTest.field(activity,"dialogScrollView");}catch(Exception error){throw new AssertionError(error);}
            int[] edge=new int[2];scroll.getLocationOnScreen(edge);
            for(TextView text:texts(scroll))if(text.getVisibility()==View.VISIBLE&&text.isTextSelectable()&&text.getWidth()>0){
                int[] position=new int[2];text.getLocationOnScreen(position);
                check(position[0]+text.getWidth()<=edge[0]+scroll.getWidth()+2,"result text extends beyond dialog body");
            }
        });
    }
    private void checkSnapshots()throws Exception{
        int slot=UiFlowTest.field(activity,"selectedSlot"),sub=UiFlowTest.field(activity,"subscriptionId");
        snapshotPrefs=activity.getSharedPreferences("celllock",0);snapshotKey="snapshot_"+slot+"_"+sub;snapshotOriginal=snapshotPrefs.getString(snapshotKey,null);
        ModemState state=new ModemState();state.readAttempted=state.prefKnown=state.hwKnown=true;state.rat=Rat.LTE|Rat.NR;state.nrMode=0;state.legacyMask="0000000000000000";
        state.lte=new java.util.BitSet();state.lte.set(3);state.hwLte=(java.util.BitSet)state.lte.clone();
        state.nrSa=new java.util.BitSet();state.nrSa.set(78);state.nrNsa=(java.util.BitSet)state.nrSa.clone();state.hwNr=(java.util.BitSet)state.nrSa.clone();
        state.gsm=new java.util.BitSet();state.hwGsm=new java.util.BitSet();state.wcdma=new java.util.BitSet();state.hwWcdma=new java.util.BitSet();
        check(Snapshots.save(snapshotPrefs,slot,sub,state,"弹窗快照 A"),"first fixture save failed");String older=Snapshots.load(snapshotPrefs,slot,sub).optString("id");
        check(Snapshots.save(snapshotPrefs,slot,sub,state,"弹窗快照 B"),"second fixture save failed");String newer=Snapshots.load(snapshotPrefs,slot,sub).optString("id");
        snapshots();recreate();choice("弹窗快照 A");
        main(()->check(texts(dialog().getWindow().getDecorView()).stream().noneMatch(t->t.getText().toString().contains("登记为默认")),"default registration removed without removing ordinary restore"));choice("读取配置");
        main(()->{try{Object task=UiFlowTest.field(activity,"pendingTask");String selected=UiFlowTest.field(task,"restoreSnapshot");check(new org.json.JSONObject(selected).optString("id").equals(older),"read confirmation selected newest instead of chosen older snapshot");}catch(Exception error){throw new AssertionError(error);}});
        recreate();backTo("snapshot");check(new org.json.JSONObject(UiFlowTest.<String>field(activity,"messageBody")).optString("id").equals(older),"return lost selected snapshot");backTo("snapshots");backTo("restore");
        choice("读取配置");choice("弹窗快照 A");choice("编辑备注");
        main(()->{try{UiFlowTest.<EditText>field(activity,"snapshotNoteInput").setText("未保存的备注");}catch(Exception error){throw new AssertionError(error);}});backTo("snapshot");
        check(Snapshots.find(snapshotPrefs,slot,sub,older).optString("note").equals("弹窗快照 A"),"return saved a note draft");
        snapshots();choice("弹窗快照 A");choice("编辑备注");
        main(()->{try{UiFlowTest.<EditText>field(activity,"snapshotNoteInput").setText("旋转后的备注");}catch(Exception error){throw new AssertionError(error);}});recreate();
        main(()->{try{check(UiFlowTest.<EditText>field(activity,"snapshotNoteInput").getText().toString().equals("旋转后的备注"),"note draft lost after recreation");button("保存备注").performClick();}catch(Exception error){throw new AssertionError(error);}});
        check(Snapshots.find(snapshotPrefs,slot,sub,older).optString("note").equals("旋转后的备注"),"note edit changed wrong snapshot");
        choice("旋转后的备注");main(()->button("删除快照").performClick());recreate();
        backTo("snapshot");check(Snapshots.find(snapshotPrefs,slot,sub,older)!=null,"return deleted snapshot");
        snapshots();choice("旋转后的备注");main(()->button("删除快照").performClick());main(()->button("确认删除").performClick());
        check(Snapshots.find(snapshotPrefs,slot,sub,older)==null&&Snapshots.find(snapshotPrefs,slot,sub,newer)!=null,"delete did not preserve other snapshot");main(()->dialog().dismiss());
    }
    private void checkSavedCells()throws Exception{
        int slot=UiFlowTest.field(activity,"selectedSlot"),sub=UiFlowTest.field(activity,"subscriptionId");
        cellKey="cells_"+slot+"_"+sub;cellOriginal=snapshotPrefs.getString(cellKey,null);
        org.json.JSONObject record=new org.json.JSONObject().put("schema",1).put("nr",true).put("arfcn",633984).put("pci",202).put("band",78).put("scs",30).put("seenAt",1).put("source","Root 电话");
        check(SavedCells.save(snapshotPrefs,slot,sub,record,"历史小区测试"),"saved cell fixture persisted");
        main(()->{try{call("chooseSavedCells",new Class<?>[0]);}catch(Exception e){throw new AssertionError(e);}});
        main(()->{List<TextView> labels=texts(dialog().getWindow().getDecorView());check(labels.stream().noneMatch(t->t.getText().toString().matches(".*(Root 电话|最近观测|SCS).*")),"saved list hides metadata like neighbor cards");TextView time=labels.stream().filter(t->t.getText().toString().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")).findFirst().orElseThrow();check(time.getCurrentTextColor()==Ui.HINT&&time.getTextSize()<labels.stream().filter(t->t.getText().toString().equals("历史小区测试")).findFirst().orElseThrow().getTextSize(),"record time is smaller and muted");});
        main(()->{TextView label=texts(dialog().getWindow().getDecorView()).stream().filter(t->t.getText().toString().equals("历史小区测试")).findFirst().orElseThrow();((View)label.getParent().getParent()).performLongClick();});recreate();backTo("cells");
        choice("历史小区测试");main(()->button("删除小区").performClick());recreate();backTo("cell");backTo("cells");
        choice("历史小区测试");choice("编辑备注");recreate();backTo("cell");choice("编辑备注");
        main(()->{try{UiFlowTest.<EditText>field(activity,"snapshotNoteInput").setText("历史备注旋转");}catch(Exception e){throw new AssertionError(e);}});recreate();
        main(()->{try{check(UiFlowTest.<EditText>field(activity,"snapshotNoteInput").getText().toString().equals("历史备注旋转"),"saved cell note survives recreation");button("保存备注").performClick();}catch(Exception e){throw new AssertionError(e);}});
        choice("历史备注旋转");choice("用作锁定目标");
        LockPage locks=UiFlowTest.field(activity,"locks");check(UiFlowTest.<EditText>field(locks,"nrArfcn").getText().toString().equals("633984"),"saved cell fills original form");
    }
    private void checkConfirmationChoices()throws Exception{
        Object session=UiFlowTest.field(activity,"SESSION");
        for(int i=0;i<400&&UiFlowTest.<Boolean>field(session,"running");i++)SystemClock.sleep(100);
        check(!UiFlowTest.<Boolean>field(session,"running"),"initial configuration read did not finish");
        List<String[]> commands=new ArrayList<>();commands.add(new String[]{"cell-nr","633984","202","78","30"});
        java.util.Map<Integer,ModemState> states=UiFlowTest.field(session,"states");int sub=UiFlowTest.field(activity,"subscriptionId");ModemState previous=states.get(sub);
        ModemState excluded=ModemState.parse(new org.json.JSONObject().put("pref",new org.json.JSONObject().put("known",true).put("nrSa",new org.json.JSONArray().put(28)).put("nrNsa",new org.json.JSONArray().put(28))));
        try{
            main(()->{states.put(sub,excluded);try{call("confirmApply",new Class<?>[]{String.class,String.class,List.class,String.class},"锁定小区","历史目标",commands,null);}catch(Exception e){throw new AssertionError(e);}});
            main(()->{check(button("备份并锁定")!=null&&button("直接锁定")!=null&&button("返回")!=null,"unified three choices");check(texts(dialog().getWindow().getDecorView()).stream().noneMatch(t->t instanceof android.widget.CheckBox),"automatic append has no visible option");check(texts(dialog().getWindow().getDecorView()).stream().anyMatch(t->t.getText().toString().contains("锁定时会自动追加")),"automatic append remains explained before confirmation");});
            recreate();main(()->{try{Object task=UiFlowTest.field(activity,"pendingTask");check(UiFlowTest.<Boolean>field(task,"appendBand"),"automatic append survives recreation");check(texts(dialog().getWindow().getDecorView()).stream().noneMatch(t->t instanceof android.widget.CheckBox),"recreation must not show an append option");button("返回").performClick();}catch(Exception e){throw new AssertionError(e);}});
            check(!UiFlowTest.<Boolean>field(session,"running"),"return did not submit an operation");
            main(()->{try{call("confirmApply",new Class<?>[]{String.class,String.class,List.class,String.class},"锁定小区","历史目标",commands,null);check(UiFlowTest.<Boolean>field(UiFlowTest.field(activity,"pendingTask"),"appendBand"),"a new confirmation defaults to append again");button("返回").performClick();excluded.nrSa.set(78);excluded.nrNsa.set(78);call("confirmApply",new Class<?>[]{String.class,String.class,List.class,String.class},"锁定小区","已允许目标",commands,null);check(!UiFlowTest.<Boolean>field(UiFlowTest.field(activity,"pendingTask"),"appendBand")&&texts(dialog().getWindow().getDecorView()).stream().noneMatch(t->t instanceof android.widget.CheckBox),"allowed target keeps direct cell lock without a band step");button("返回").performClick();}catch(Exception e){throw new AssertionError(e);}});
            ModemState allowed=excluded;
            ModemState restricted=ModemState.parse(new org.json.JSONObject().put("pref",new org.json.JSONObject().put("known",true).put("nrSa",new org.json.JSONArray().put(28)).put("nrNsa",new org.json.JSONArray().put(28))));
            for(ModemState cached:new ModemState[]{null,allowed})main(()->{try{
                if(cached==null)states.remove(sub);else states.put(sub,cached);
                call("confirmApply",new Class<?>[]{String.class,String.class,List.class,String.class},"锁定小区","执行前频段变化",commands,null);
                Object task=UiFlowTest.field(activity,"pendingTask");
                Method refresh=task.getClass().getDeclaredMethod("refreshAppendBand",ModemState.class);refresh.setAccessible(true);
                refresh.invoke(task,cached);check(!UiFlowTest.<Boolean>field(task,"appendBand"),"unknown or allowed read does not require a band write");
                refresh.invoke(task,restricted);check(UiFlowTest.<Boolean>field(task,"appendBand"),"fresh exclusion requires append despite unknown or allowed cache");
                check(LockPlan.append(restricted,commands.get(0)).get(0)[2].equals("28,78"),"fresh union keeps allowed bands before locking");
                refresh.invoke(task,(Object)null);check(UiFlowTest.<Boolean>field(task,"appendBand"),"unknown later read keeps the required-append safety check");
                button("返回").performClick();
            }catch(Exception e){throw new AssertionError(e);}});
        }finally{main(()->{if(previous==null)states.remove(sub);else states.put(sub,previous);});}
        main(()->{try{call("chooseRestore",new Class<?>[0]);}catch(Exception e){throw new AssertionError(e);}});choice("开放全部支持频段");
        main(()->{try{Object task=UiFlowTest.field(activity,"pendingTask");List<String[]> actions=UiFlowTest.field(task,"commands");check(actions.size()==1&&actions.get(0)[0].equals("open-bands"),"opening bands cannot call legacy mixed reset");check(button("备份并开放")!=null&&button("直接开放")!=null,"opening uses same backup choices");button("返回").performClick();}catch(Exception e){throw new AssertionError(e);}});
    }
    private void checkClearingChoices()throws Exception{
        Object session=UiFlowTest.field(activity,"SESSION");
        for(String kind:new String[]{"clear-quick","clear-complete"}){
            main(()->{try{call("chooseRestore",new Class<?>[0]);}catch(Exception e){throw new AssertionError(e);}});
            choice("清除全部锁定");
            choice(kind.equals("clear-quick")?"快速清除（不重启基带）":"完整清除（重启基带）");
            main(()->{try{Object task=UiFlowTest.field(activity,"pendingTask");List<String[]> commands=UiFlowTest.field(task,"commands");check(commands.size()==1&&commands.get(0)[0].equals(kind),"clear choice dispatches distinct task without registered defaults");check(button("备份并清除")!=null&&button("直接清除")!=null,"clear routes share backup choices");if(kind.equals("clear-complete"))check(texts(dialog().getWindow().getDecorView()).stream().anyMatch(t->t.getText().toString().contains("两张卡可能短时断网")),"complete clear explains dual SIM restart");}catch(Exception e){throw new AssertionError(e);}});
            recreate();backTo("clear-choice");backTo("restore");check(!UiFlowTest.<Boolean>field(session,"running"),"return does not submit clear/reload");
        }
    }
    private void checkBackNavigation()throws Exception{
        main(()->{try{call("chooseRestore",new Class<?>[0]);}catch(Exception e){throw new AssertionError(e);}});
        choice("清除全部锁定");backTo("restore");
        choice("清除全部锁定");choice("完整清除（重启基带）");recreate();
        main(()->{long now=SystemClock.uptimeMillis();MotionEvent touch=MotionEvent.obtain(now,now,MotionEvent.ACTION_OUTSIDE,0,0,0);try{check(dialog().onTouchEvent(touch),"outside touch was not consumed");}finally{touch.recycle();}});
        check(dialog()==null&&!UiFlowTest.<Boolean>field(activity,"messageVisible")&&UiFlowTest.field(activity,"pendingTask")==null&&UiFlowTest.field(activity,"dialogParent")==null,"outside touch did not close the entire dialog flow");
        main(()->{try{call("chooseRestore",new Class<?>[0]);}catch(Exception e){throw new AssertionError(e);}});
        for(String label:new String[]{"保存配置","开放全部支持频段"}){choice(label);recreate();backTo("restore");}
        main(()->button("关闭").performClick());check(dialog()==null,"top-level close retained dialog history");
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();LogRecord fixture=null;List<LogRecord> logs=null;Throwable failure=null;
        try{
            activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            waitForSim();
            checkHeader();
            checkBackNavigation();
            main(()->{try{call("chooseRestore",new Class<?>[0]);}catch(Exception e){throw new AssertionError(e);}});
            check(dialog()!=null,"restore selector missing for trusted SIM");
            main(()->{for(String label:new String[]{"保存配置","读取配置","清除全部锁定","开放全部支持频段"})check(texts(dialog().getWindow().getDecorView()).stream().anyMatch(t->t.getText().toString().equals(label)),"configuration entry missing: "+label);});
            recreate();
            choice("保存配置");check(button("确认保存")!=null,"save confirmation missing");
            main(()->{try{UiFlowTest.<EditText>field(activity,"snapshotNoteInput").setText("保存备注草稿");}catch(Exception error){throw new AssertionError(error);}});recreate();
            main(()->{check(button("确认保存")!=null,"save confirmation not restored");try{Object task=UiFlowTest.field(activity,"pendingTask");List<String[]> commands=UiFlowTest.field(task,"commands");check(commands.size()==1&&commands.get(0)[0].equals("save-config"),"save confirmation lost its local operation");check(UiFlowTest.<EditText>field(activity,"snapshotNoteInput").getText().toString().equals("保存备注草稿"),"save note draft lost after recreation");}catch(Exception e){throw new AssertionError(e);}});
            main(()->dialog().dismiss());
            checkSnapshots();
            checkSavedCells();
            checkConfirmationChoices();
            checkClearingChoices();
            List<String[]> commands=new ArrayList<>();commands.add(new String[]{"cell-clear","lte"});
            main(()->{try{call("confirmApply",new Class<?>[]{String.class,String.class,List.class,String.class},"解除 LTE 小区锁","解除所选 SIM 的 LTE 小区锁。",commands,null);}catch(Exception e){throw new AssertionError(e);}});
            check(button("备份并清除")!=null,"specific confirmation action missing");recreate();
            main(()->{check(button("备份并清除")!=null,"confirmation not restored");dialog().dismiss();});
            OperationReport report=new OperationReport("弹窗验收示例","SIM 测试");
            report.items.add(new ResultItem("操作前备份","saved","可恢复：LTE 频段、SA 频段、NSA 频段、2G/3G 原始配置、制式、5G 组网、LTE 小区锁、5G 小区/频点锁\n仅记录：设备/固件、EFS 证据（不自动重放）"));
            report.items.add(new ResultItem("LTE 小区锁","unchanged","当前未锁定，无需解除"));
            report.items.add(new ResultItem("5G 小区锁","unknown","请求已接受，锁状态无法确认"));
            report.details="诊断回执\n".repeat(120);
            open("result","弹窗验收 · 部分完成",report.encode());
            checkFooter();main(()->button("展开诊断详情").performClick());checkFooter();
            main(()->{try{ScrollView scroll=UiFlowTest.field(activity,"dialogScrollView");scroll.scrollTo(0,scroll.getChildAt(0).getHeight());}catch(Exception e){throw new AssertionError(e);}});
            recreate();checkFooter();
            main(()->{check(button("收起诊断详情")!=null,"expanded details lost");try{ScrollView scroll=UiFlowTest.field(activity,"dialogScrollView");check(scroll.getScrollY()>0,"scroll position lost");}catch(Exception e){throw new AssertionError(e);}dialog().dismiss();});
            Object session=UiFlowTest.field(activity,"SESSION");logs=UiFlowTest.field(session,"logs");fixture=new LogRecord("12:00:00","弹窗验收示例",report);logs.add(fixture);
            open("logs","操作记录","");checkFooter();
            main(()->button("展开查看详情").performClick());recreate();checkFooter();
            main(()->check(button("收起查看详情")!=null,"log details lost after recreation"));
        }catch(Throwable error){failure=error;}
        finally{
            if(snapshotPrefs!=null&&cellKey!=null){SharedPreferences.Editor cells=snapshotPrefs.edit();if(cellOriginal==null)cells.remove(cellKey);else cells.putString(cellKey,cellOriginal);if(!cells.commit())failure=new AssertionError("saved cell fixtures were not cleaned up");}
            if(snapshotPrefs!=null){SharedPreferences.Editor editor=snapshotPrefs.edit();if(snapshotOriginal==null)editor.remove(snapshotKey);else editor.putString(snapshotKey,snapshotOriginal);if(!editor.commit()){AssertionError error=new AssertionError("snapshot fixtures were not cleaned up");if(failure==null)failure=error;else failure.addSuppressed(error);}}
            try{if(logs!=null)logs.remove(fixture);if(activity!=null)main(()->{if(dialog()!=null)dialog().dismiss();activity.finish();});}
            catch(Throwable error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        }
        result.putString("stream",failure==null?"PASS: "+checks+" actual dialog/lifecycle checks; no modem writes\n":android.util.Log.getStackTraceString(failure));
        finish(failure==null?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
