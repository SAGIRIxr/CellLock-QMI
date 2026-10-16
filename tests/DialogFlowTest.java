package com.celllock.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Uses actual windows/lifecycle and fixture results; never confirms a modem write. */
public final class DialogFlowTest extends Instrumentation {
    private int checks;
    private Activity activity;
    private void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static List<TextView> texts(View view){List<TextView> all=new ArrayList<>();if(view instanceof TextView)all.add((TextView)view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)all.addAll(texts(((ViewGroup)view).getChildAt(i)));return all;}
    private Object call(String name,Class<?>[] types,Object... args)throws Exception{Method method=MainActivity.class.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(activity,args);}
    private void main(Runnable action){Throwable[] error={null};runOnMainSync(()->{try{action.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new AssertionError(error[0]);waitForIdleSync();}
    private AlertDialog dialog(){try{return UiFlowTest.field(activity,"messageDialog");}catch(Exception e){throw new AssertionError(e);}}
    private Button button(String text){return (Button)texts(dialog().getWindow().getDecorView()).stream().filter(t->t instanceof Button&&t.getText().toString().equals(text)).findFirst().orElseThrow();}
    private void open(String kind,String title,String body){main(()->{try{call("openDialog",new Class<?>[]{String.class,String.class,String.class},kind,title,body);}catch(Exception e){throw new AssertionError(e);}});}
    private void waitForSim(){
        for(int i=0;i<100;i++){
            boolean[] trusted={false};main(()->{try{trusted[0]=(boolean)call("trustedSim",new Class<?>[0]);}catch(Exception e){throw new AssertionError(e);}});
            if(trusted[0])return;SystemClock.sleep(100);
        }
        throw new AssertionError("trusted SIM metadata not ready");
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
        });
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();LogRecord fixture=null;List<LogRecord> logs=null;Throwable failure=null;
        try{
            activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            waitForSim();
            main(()->{try{call("chooseRestore",new Class<?>[0]);}catch(Exception e){throw new AssertionError(e);}});
            check(dialog()!=null,"restore selector missing for trusted SIM");
            main(()->{check(texts(dialog().getWindow().getDecorView()).stream().anyMatch(t->t.getText().toString().equals("读取配置")),"restore card missing");});
            recreate();
            main(()->dialog().dismiss());
            List<String[]> commands=new ArrayList<>();commands.add(new String[]{"cell-clear","lte"});
            main(()->{try{call("confirmApply",new Class<?>[]{String.class,String.class,List.class,String.class},"解除 LTE 小区锁","解除所选 SIM 的 LTE 小区锁。",commands,null);}catch(Exception e){throw new AssertionError(e);}});
            check(button("确认应用")!=null,"specific confirmation action missing");recreate();
            main(()->{check(button("确认应用")!=null,"confirmation not restored");dialog().dismiss();});
            OperationReport report=new OperationReport("弹窗验收示例","SIM 测试");
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
            try{if(logs!=null)logs.remove(fixture);if(activity!=null)main(()->{if(dialog()!=null)dialog().dismiss();activity.finish();});}
            catch(Throwable error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        }
        result.putString("stream",failure==null?"PASS: "+checks+" actual dialog/lifecycle checks; no modem writes\n":android.util.Log.getStackTraceString(failure));
        finish(failure==null?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
