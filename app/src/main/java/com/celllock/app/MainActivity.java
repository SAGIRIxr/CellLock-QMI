package com.celllock.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS=100,REQUEST_EXPORT=200;
    private static final Session SESSION=new Session();
    private static final String[] TITLES={"小区","锁定","设备"};
    private static final String[] SUBTITLES={"看清连接，选择目标。","小区、频段与网络制式。","设备状态与操作记录。"};
    private final ArrayList<SimInfo> subscriptions=new ArrayList<>();
    private RadioReader reader;
    private int selectedSlot=-1;
    private final LinearLayout[] navigation=new LinearLayout[3];
    private final TextView[] navLabels=new TextView[3];
    private final LineIcon[] navIcons=new LineIcon[3];
    private Ui ui;
    private SharedPreferences prefs;
    private Spinner simSpinner;
    private TextView pageTitle,pageSubtitle;
    private CellsPage cells;
    private LockPage locks;
    private DevicePage device;
    private View[] pages;
    private boolean ready,resumed;
    private int selectedPage,subscriptionId=SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private SimStatus simStatus=SimStatus.PERMISSION_REQUIRED;
    private long consumed=-1;
    private String exportText="";
    private String messageTitle="",messageBody="";
    private boolean messageVisible;
    private AlertDialog messageDialog;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        ui=new Ui(this);prefs=getSharedPreferences("celllock",MODE_PRIVATE);reader=new RadioReader(this);
        if(saved!=null){subscriptionId=saved.getInt("main.sim",SubscriptionManager.INVALID_SUBSCRIPTION_ID);selectedSlot=saved.getInt("main.slot",-1);selectedPage=Math.max(0,Math.min(2,saved.getInt("main.page")));exportText=saved.getString("main.export","");consumed=SESSION.alive()?Math.max(saved.getLong("main.consumed",-1),SESSION.deliveredId()):-1;SESSION.restoreLogs(saved.getStringArrayList("main.logs"));}
        else consumed=SESSION.deliveredId();
        if(saved!=null){messageVisible=saved.getBoolean("main.messageVisible");messageTitle=saved.getString("main.messageTitle","");messageBody=saved.getString("main.messageBody","");}
        buildUi();ready=true;
        if(saved!=null){cells.restore(saved);locks.restore(saved);device.post(()->device.scrollTo(0,saved.getInt("main.deviceScroll")));}
        if(saved!=null&&saved.getBoolean("main.wasBusy")&&!SESSION.alive())SESSION.log("应用进程已重建，上次操作结果未知，请重新读取确认。");
        SESSION.attach(this);loadSubscriptions();refreshUi();showPage(selectedPage);
        if(saved==null)SESSION.log("CellLock "+version()+" 已就绪。");
    }

    private void buildUi() {
        LinearLayout root=ui.column();root.setBackgroundColor(Ui.BG);
        LinearLayout header=ui.column();header.setPadding(ui.dp(22),ui.dp(16),ui.dp(22),ui.dp(13));
        LinearLayout titleRow=ui.row();pageTitle=ui.text(TITLES[selectedPage],29,Ui.INK);pageTitle.setTypeface(null,Typeface.BOLD);titleRow.addView(pageTitle,ui.weight(-2));TextView brand=ui.text("CellLock",14,Ui.GREEN);brand.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));titleRow.addView(brand);header.addView(titleRow);ui.gap(header,5);
        pageSubtitle=ui.text(SUBTITLES[selectedPage],12,Ui.MUTED);header.addView(pageSubtitle);ui.gap(header,14);
        LinearLayout sim=ui.row();sim.setPadding(ui.dp(12),0,ui.dp(7),0);sim.setBackground(ui.shape(Ui.CARD,14,0));sim.addView(new LineIcon(this,LineIcon.SIM,Ui.GREEN),new LinearLayout.LayoutParams(ui.dp(19),ui.dp(23)));ui.gap(sim,7);
        simSpinner=ui.spinner(new String[]{"授权后选择 SIM"});simSpinner.setContentDescription("选择操作的 SIM 卡");sim.addView(simSpinner,ui.weight(46));header.addView(sim,ui.fill(-2));root.addView(header);
        simSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View view,int index,long id){if(!ready||SESSION.running()||index<0||index>=subscriptions.size())return;SimInfo next=subscriptions.get(index);if(next.subscriptionId!=subscriptionId||next.slot!=selectedSlot)selectSim(next);}
        });
        FrameLayout pageHost=new FrameLayout(this);root.addView(pageHost,new LinearLayout.LayoutParams(-1,0,1));
        locks=new LockPage(this,ui,new LockPage.Host(){
            public void onRefresh(){readState(true);}
            public void onApply(String title,String summary,List<String[]> commands){confirmApply(title,summary,commands,null);}
            public void onRestore(){chooseRestore();}
            public void onMessage(String title,String message){showMessage(title,message);}
        });
        cells=new CellsPage(this,ui,reader,new CellsPage.Host(){
            public void onTarget(RadioCell cell){if(!SESSION.running()){locks.fillTarget(cell);showPage(1);}}
            public void onNeedPermission(){requestPermissionsManually(true);}
            public void onSimChanged(){loadSubscriptions();}
            public void onServingChanged(RadioCell cell){locks.setServingCell(cell);}
        });
        device=new DevicePage(this,ui,new DevicePage.Host(){
            public void onReadState(){readState(true);}
            public void onPermissions(){requestPermissionsManually(true);}
            public void onLocation(){startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));}
            public void onExport(){exportLogs();}
            public void onClearLogs(){if(!SESSION.running()){SESSION.clearLogs();refreshUi();}}
            public void onShowLogs(){showMessage("操作记录",String.join("\n\n",SESSION.logs()));}
        });
        pages=new View[]{cells,locks,device};for(View page:pages)pageHost.addView(page,new FrameLayout.LayoutParams(-1,-1));
        root.addView(ui.divider(),ui.fill(1));LinearLayout nav=ui.row();nav.setPadding(ui.dp(18),ui.dp(8),ui.dp(18),ui.dp(7));nav.setBackgroundColor(Ui.CARD);
        String[] icons={LineIcon.RADIO,LineIcon.LOCK,LineIcon.DEVICE};
        for(int i=0;i<3;i++){final int index=i;LinearLayout tab=ui.column();tab.setGravity(Gravity.CENTER);tab.setPadding(0,ui.dp(5),0,ui.dp(5));navIcons[i]=new LineIcon(this,icons[i],Ui.MUTED);tab.addView(navIcons[i],new LinearLayout.LayoutParams(ui.dp(24),ui.dp(24)));ui.gap(tab,4);navLabels[i]=ui.text(TITLES[i],11,Ui.MUTED);navLabels[i].setGravity(Gravity.CENTER);tab.addView(navLabels[i]);tab.setOnClickListener(v->showPage(index));tab.setContentDescription(TITLES[i]+"页");navigation[i]=tab;LinearLayout.LayoutParams lp=ui.weight(58);lp.setMargins(ui.dp(7),0,ui.dp(7),0);nav.addView(tab,lp);}
        root.addView(nav);setContentView(root);
    }

    private void showPage(int page) {
        selectedPage=page;pageTitle.setText(TITLES[page]);pageSubtitle.setText(SUBTITLES[page]);
        for(int i=0;i<3;i++){boolean selected=i==page;pages[i].setVisibility(selected?View.VISIBLE:View.GONE);navigation[i].setBackground(ui.shape(selected?Ui.SOFT_GREEN:Ui.CARD,16,0));navLabels[i].setTextColor(selected?Ui.GREEN:Ui.MUTED);navIcons[i].setColor(selected?Ui.GREEN:Ui.MUTED);}
        View focus=getCurrentFocus();if(focus!=null){InputMethodManager keyboard=getSystemService(InputMethodManager.class);if(keyboard!=null)keyboard.hideSoftInputFromWindow(focus.getWindowToken(),0);focus.clearFocus();}
        cells.setForeground(resumed&&page==0);
        maybeReadInitialState();
    }

    private boolean phonePermission(){return checkSelfPermission(Manifest.permission.READ_PHONE_STATE)==PackageManager.PERMISSION_GRANTED;}
    private void loadSubscriptions() {
        if(!ready)return;
        reader.loadSims(active->{
            if(isDestroyed())return;
            int previous=subscriptionId,previousSlot=selectedSlot;subscriptions.clear();subscriptions.addAll(active);
            boolean trusted=active.stream().anyMatch(SimInfo::trusted);
            simStatus=SimStatus.resolve(phonePermission(),trusted,hasReadySim(),!active.isEmpty()&&!trusted);
            ArrayList<String> labels=new ArrayList<>();int position=0;
            for(int i=0;i<active.size();i++){SimInfo info=active.get(i);labels.add(info.label+(info.source.equals("系统")?"":" · "+info.source));if(info.slot==previousSlot&&(previous<0||info.subscriptionId==previous))position=i;}
            SimInfo next=active.isEmpty()?null:active.get(position);
            if(labels.isEmpty())labels.add(simStatus.emptyLabel);
            ready=false;simSpinner.setAdapter(ui.adapter(labels.toArray(new String[0])));simSpinner.setSelection(position);ready=true;
            if(next==null){if(subscriptionId!=-1||selectedSlot!=-1)selectSim(null);else{cells.setSim(null);refreshUi();}}
            else if(next.subscriptionId!=previous||next.slot!=previousSlot)selectSim(next);
            else{cells.setSim(next);refreshUi();}
            maybeReadInitialState();
        });
    }

    private boolean hasReadySim() {
        TelephonyManager phone=getSystemService(TelephonyManager.class);
        if(phone==null)return false;
        try{for(int slot=0;slot<phone.getActiveModemCount();slot++)if(phone.getSimState(slot)==TelephonyManager.SIM_STATE_READY)return true;}
        catch(SecurityException|UnsupportedOperationException ignored){}
        return false;
    }

    private void selectSim(SimInfo next) {
        subscriptionId=next==null?-1:next.subscriptionId;selectedSlot=next==null?-1:next.slot;
        locks.resetForSubscription();cells.setSim(next);refreshUi();
        if(next!=null)SESSION.log("已选择 "+next.label+"（"+next.source+"）。");
        maybeReadInitialState();
    }

    private boolean trustedSim(){return subscriptions.stream().anyMatch(s->s.subscriptionId==subscriptionId&&s.slot==selectedSlot&&s.trusted());}
    private String simName(int sub) {for(SimInfo info:subscriptions)if(info.subscriptionId==sub&&info.slot==selectedSlot)return info.label;return "订阅 "+sub;}
    private void maybeReadInitialState(){if(ready&&resumed&&selectedPage==1&&!SESSION.running()&&trustedSim()&&!SESSION.attempted(subscriptionId))readState(false);}

    private void readState(boolean manual) {
        if(SESSION.running())return;
        if(!trustedSim()){if(manual)requestPermissionsManually(false);return;}
        if(!SubscriptionManager.isValidSubscriptionId(subscriptionId)){if(manual)toast("请先选择可用 SIM");return;}
        try{int slot=Backend.slotOf(this,subscriptionId);Task task=new Task(subscriptionId,slot,simName(subscriptionId),"读取设置",new ArrayList<>(),null);if(SESSION.start(getApplicationContext(),task))refreshUi();}
        catch(Exception error){if(manual)showMessage("无法读取",message(error));}
    }

    private void confirmApply(String title,String summary,List<String[]> commands,String restoreSnapshot) {
        if(SESSION.running())return;
        if(!trustedSim()){requestPermissionsManually(false);return;}
        final int sub=subscriptionId;final int slot;
        try{slot=Backend.slotOf(this,sub);}catch(Exception error){showMessage("无法确定 SIM",message(error));return;}
        final Task task=new Task(sub,slot,simName(sub),title,commands,restoreSnapshot);
        if(task.commands.isEmpty()){showMessage("没有可执行的设置","请重新选择参数。");return;}
        String explanation=task.target+"\n\n"+summary+"\n\n可能短暂断网；离开目标覆盖区前记得恢复。";
        new AlertDialog.Builder(this).setTitle(title).setMessage(explanation).setNegativeButton("取消",null).setPositiveButton("确认",(dialog,which)->{
            if(SESSION.running()||subscriptionId!=task.subscriptionId){toast("当前 SIM 已改变，请重新确认");return;}
            try{if(Backend.slotOf(this,task.subscriptionId)!=task.slot)throw new IllegalStateException("SIM 卡槽已改变，请重新确认");if(SESSION.start(getApplicationContext(),task))refreshUi();}
            catch(Exception error){showMessage("无法执行",message(error));}
        }).show();
    }

    private void chooseRestore() {
        if(SESSION.running())return;
        if(!trustedSim()){requestPermissionsManually(false);return;}
        final int sub=subscriptionId;final int slot;
        try{slot=Backend.slotOf(this,sub);}catch(Exception error){showMessage("无法恢复",message(error));return;}
        new AlertDialog.Builder(this).setTitle("恢复设置").setItems(new String[]{"恢复初始频段与制式，并清除小区锁","全部开放，并清除小区锁"},(dialog,which)->{
            if(sub!=subscriptionId||SESSION.running()){toast("当前 SIM 已改变，请重新选择");return;}
            try{if(Backend.slotOf(this,sub)!=slot)throw new IllegalStateException("SIM 卡槽已改变，请重新选择。");}catch(Exception error){showMessage("无法恢复",message(error));return;}
            if(which==1){ArrayList<String[]> commands=new ArrayList<>();commands.add(new String[]{"reset"});confirmApply("全部开放","开放本机支持的频段与制式，并解除小区锁定。",commands,null);return;}
            JSONObject snapshot=Snapshots.load(prefs,slot,sub);
            if(!Snapshots.canRestore(snapshot)){showMessage("暂无可恢复的初始设置","请先读取当前 SIM 的完整设置。初始设置保存后才允许写入。");return;}
            List<String[]> commands=new ArrayList<>();for(String[] withSlot:Snapshots.restoreCommands(snapshot,slot)){if(withSlot.length<2)continue;String[] command=new String[withSlot.length-1];command[0]=withSlot[0];if(withSlot.length>2)System.arraycopy(withSlot,2,command,1,withSlot.length-2);commands.add(command);}
            confirmApply("恢复初始设置","恢复此 SIM 首次保存的频段与制式，并清除小区锁。\n"+Snapshots.describe(snapshot),commands,snapshot.toString());
        }).setNegativeButton("取消",null).show();
    }

    private void refreshUi() {
        if(!ready)return;boolean busy=SESSION.running();Ui.enable(simSpinner,!busy);locks.setState(SESSION.state(subscriptionId));locks.setBusy(busy);cells.setBusy(busy);
        device.update(SESSION.state(subscriptionId),SESSION.status(subscriptionId),SESSION.lastRead(subscriptionId),busy,SESSION.logs(),simStatus);
    }

    private void receive(Completion completion) {
        if(!ready||isDestroyed())return;refreshUi();
        if(!resumed||completion==null||completion.id<=consumed)return;
        consumed=completion.id;SESSION.markDelivered(consumed);
        if(!completion.readOnly)showMessage(completion.title+(completion.ok?" · 已确认":" · 未全部确认"),completion.target+"\n\n"+completion.message);
        maybeReadInitialState();
    }

    private void requestPermissionsManually(boolean measurement) {
        ArrayList<String> needed=new ArrayList<>();
        if(!trustedSim()&&!phonePermission())needed.add(Manifest.permission.READ_PHONE_STATE);
        boolean fine=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
        if(measurement&&!reader.rootMeasurements()&&!fine){needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);needed.add(Manifest.permission.ACCESS_FINE_LOCATION);}
        if(needed.isEmpty()){
            if(!trustedSim()&&simStatus==SimStatus.RESTRICTED){toast("真实订阅读取受限，当前观测不能用于锁定");openPermissionSettings();return;}
            android.location.LocationManager location=getSystemService(android.location.LocationManager.class);
            if(measurement&&!reader.rootMeasurements()&&location!=null&&!location.isLocationEnabled()){toast("系统小区接口需要开启定位；Root 来源当前不可用");startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));return;}
            loadSubscriptions();return;
        }
        for(String permission:needed)if(needsPermissionSettings(prefs,permission,checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED,shouldShowRequestPermissionRationale(permission))){openPermissionSettings();return;}
        SharedPreferences.Editor editor=prefs.edit().remove("permissionsAsked");
        for(String permission:needed)editor.putBoolean("permissionAsked:"+permission,true);
        editor.apply();requestPermissions(needed.toArray(new String[0]),REQUEST_PERMISSIONS);
    }
    static boolean needsPermissionSettings(SharedPreferences prefs,String permission,boolean granted,boolean rationale) {
        return !granted&&prefs.getBoolean("permissionAsked:"+permission,false)&&!rationale;
    }
    private void openPermissionSettings(){startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==REQUEST_PERMISSIONS){loadSubscriptions();refreshUi();cells.setForeground(resumed&&selectedPage==0);maybeReadInitialState();}}
    @Override protected void onResume(){super.onResume();resumed=true;if(ready){loadSubscriptions();refreshUi();cells.setForeground(selectedPage==0);if(messageVisible&&(messageDialog==null||!messageDialog.isShowing()))showMessage(messageTitle,messageBody);SESSION.deliver();maybeReadInitialState();}}
    @Override protected void onPause(){resumed=false;if(cells!=null)cells.setForeground(false);super.onPause();}
    @Override protected void onDestroy(){if(cells!=null)cells.dispose();if(reader!=null)reader.close();if(messageDialog!=null){messageDialog.setOnDismissListener(null);messageDialog.dismiss();messageDialog=null;}SESSION.detach(this);super.onDestroy();}

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);out.putInt("main.sim",subscriptionId);out.putInt("main.slot",selectedSlot);out.putInt("main.page",selectedPage);out.putString("main.export",exportText);out.putLong("main.consumed",consumed);out.putBoolean("main.wasBusy",SESSION.running());out.putStringArrayList("main.logs",new ArrayList<>(SESSION.logs()));out.putInt("main.deviceScroll",device.getScrollY());cells.save(out);locks.save(out);
        out.putBoolean("main.messageVisible",messageVisible);out.putString("main.messageTitle",messageTitle);out.putString("main.messageBody",messageBody);
    }
    private void exportLogs() {
        if(SESSION.running())return;
        exportText="CellLock "+version()+" 诊断\n生成："+dateTime()+"\n设备："+Build.MANUFACTURER+" "+Build.MODEL+" / Android "+Build.VERSION.RELEASE+"\n目标："+simName(subscriptionId)+"\n\n"+SESSION.status(subscriptionId)+"\n\n操作记录\n"+String.join("\n\n",SESSION.logs())+"\n\n后端原始回执\n"+SESSION.rawHistory();
        Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT);save.addCategory(Intent.CATEGORY_OPENABLE);save.setType("text/plain");save.putExtra(Intent.EXTRA_TITLE,"CellLock-诊断-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date())+".txt");
        try{startActivityForResult(save,REQUEST_EXPORT);}catch(android.content.ActivityNotFoundException error){showMessage("无法导出","系统未提供文件保存器。");}
    }
    @Override protected void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);if(code!=REQUEST_EXPORT||result!=RESULT_OK||data==null||data.getData()==null)return;try(OutputStream stream=getContentResolver().openOutputStream(data.getData())){if(stream==null)throw new java.io.IOException("保存位置不可写");stream.write(exportText.getBytes(StandardCharsets.UTF_8));SESSION.log("诊断文本已导出。");toast("已导出");}catch(Exception error){showMessage("导出失败",message(error));}refreshUi();}

    private void showMessage(String title,String message){
        if(isDestroyed()||isFinishing())return;messageTitle=title;messageBody=message==null?"未返回说明":message;messageVisible=true;
        if(!resumed)return;
        if(messageDialog!=null){messageDialog.setOnDismissListener(null);messageDialog.dismiss();}
        ScrollView scroll=new ScrollView(this);TextView content=ui.text(messageBody,13,Ui.INK);content.setPadding(ui.dp(23),ui.dp(17),ui.dp(23),ui.dp(12));content.setLineSpacing(ui.dp(5),1);content.setTextIsSelectable(true);scroll.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(messageTitle).setView(scroll).setPositiveButton("知道了",null).create();messageDialog=dialog;
        dialog.setOnDismissListener(ignored->{if(messageDialog==dialog){messageDialog=null;messageVisible=false;messageTitle="";messageBody="";}});dialog.show();
    }
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_SHORT).show();}
    private String version(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(PackageManager.NameNotFoundException ignored){return "未知";}}
    private static String message(Throwable error){return error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();}
    private static String clock(){return new SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date());}
    private static String dateTime(){return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.ROOT).format(new Date());}
    private static List<String[]> copyCommands(List<String[]> input){ArrayList<String[]> output=new ArrayList<>();if(input!=null)for(String[] command:input)if(command!=null&&command.length>0)output.add(command.clone());return output;}

    private static final class Task {
        final int subscriptionId,slot;final String target,title,restoreSnapshot;final List<String[]> commands;
        Task(int subscriptionId,int slot,String target,String title,List<String[]> commands,String restoreSnapshot){this.subscriptionId=subscriptionId;this.slot=slot;this.target=target;this.title=title;this.commands=copyCommands(commands);this.restoreSnapshot=restoreSnapshot;}
        boolean readOnly(){return commands.isEmpty();}
    }
    private static final class Completion {
        final long id;final boolean readOnly,ok;final String target,title,message;
        Completion(long id,Task task,boolean ok,String message){this.id=id;this.readOnly=task.readOnly();this.ok=ok;this.target=task.target;this.title=task.title;this.message=message;}
    }

    private static final class Session {
        private final Handler main=new Handler(Looper.getMainLooper());
        private final Map<Integer,ModemState> states=new HashMap<>();
        private final Map<Integer,String> statuses=new HashMap<>(),readTimes=new HashMap<>();
        private final Set<Integer> attempted=new HashSet<>();
        private final ArrayList<String> logs=new ArrayList<>(),raw=new ArrayList<>();
        private WeakReference<MainActivity> owner=new WeakReference<>(null);
        private ExecutorService worker;
        private boolean running;
        private long sequence,delivered=-1;
        private Completion latest;
        synchronized boolean running(){return running;}
        synchronized boolean alive(){return running||latest!=null;}
        synchronized long deliveredId(){return delivered;}
        synchronized void markDelivered(long id){delivered=Math.max(delivered,id);}
        synchronized boolean attempted(int sub){return attempted.contains(sub);}
        synchronized ModemState state(int sub){return states.get(sub);}
        synchronized String status(int sub){return statuses.getOrDefault(sub,"");}
        synchronized String lastRead(int sub){return readTimes.getOrDefault(sub,"");}
        synchronized List<String> logs(){return new ArrayList<>(logs);}
        synchronized String rawHistory(){return String.join("\n\n",raw);}
        synchronized void log(String text){logs.add(clock()+"  "+text);while(logs.size()>100)logs.remove(0);}
        synchronized void clearLogs(){logs.clear();raw.clear();}
        synchronized void restoreLogs(ArrayList<String> previous){if(logs.isEmpty()&&previous!=null){logs.addAll(previous);while(logs.size()>100)logs.remove(0);}}
        synchronized void attach(MainActivity activity){owner=new WeakReference<>(activity);deliver();}
        synchronized void detach(MainActivity activity){if(owner.get()!=activity)return;owner.clear();if(worker!=null)worker.shutdown();}
        void deliver(){main.post(()->{MainActivity activity;Completion completion;synchronized(this){activity=owner.get();completion=latest;}if(activity!=null&&!activity.isDestroyed())activity.receive(completion);});}
        synchronized boolean start(Context context,Task task) {
            if(running)return false;running=true;attempted.add(task.subscriptionId);final long id=++sequence;final Context app=context.getApplicationContext();
            if(worker==null||worker.isShutdown())worker=Executors.newSingleThreadExecutor();
            log(task.target+" · "+(task.readOnly()?"开始读取设置":"开始"+task.title));
            worker.execute(()->execute(app,task,id));return true;
        }
        private void verifySlot(Context context,Task task){
            try{RadioReader.verifySlot(context,task.subscriptionId,task.slot);return;}catch(Exception ignored){}
            synchronized(this){states.remove(task.subscriptionId);}
            throw new IllegalStateException("所选 SIM 的卡槽已改变或无法确认，已停止后续操作。操作结果需重新确认，请重新读取。");
        }
        private ModemResult command(Context context,Task task,String[] command) {
            verifySlot(context,task);String[] args=new String[command.length+1];args[0]=command[0];args[1]=String.valueOf(task.slot);if(command.length>1)System.arraycopy(command,1,args,2,command.length-1);
            ModemResult result=Backend.run(context,args);
            synchronized(this){raw.add(dateTime()+" · "+task.target+"（slot "+task.slot+"） · "+command[0]+"\n"+(result.raw!=null&&!result.raw.isEmpty()?result.raw:result.stage+"："+result.message));while(raw.size()>12)raw.remove(0);}
            verifySlot(context,task);
            synchronized(this){if(result.state!=null){result.state.inheritHardware(states.get(task.subscriptionId));states.put(task.subscriptionId,result.state);readTimes.put(task.subscriptionId,clock());}else if("state".equals(command[0]))states.remove(task.subscriptionId);statuses.put(task.subscriptionId,(result.state!=null&&result.state.hasCellData()?"Root 已授权 · ":"")+result.message);}
            return result;
        }
        private void execute(Context context,Task task,long id) {
            boolean ok=false;StringBuilder report=new StringBuilder();
            try{
                SharedPreferences prefs=context.getSharedPreferences("celllock",Context.MODE_PRIVATE);
                ModemResult initial=command(context,task,new String[]{"state"});
                if(initial.ok&&initial.state!=null)Snapshots.rememberIfFirst(prefs,task.slot,task.subscriptionId,initial.state);
                if(task.readOnly()){ok=initial.ok;report.append(initial.message);}
                else {
                    JSONObject snapshot=Snapshots.load(prefs,task.slot,task.subscriptionId);
                    if(!initial.ok||initial.state==null)throw new IllegalStateException("写入前读取未完成，未执行写入。\n"+initial.message);
                    if(!Snapshots.canRestore(snapshot))throw new IllegalStateException("尚未保存可恢复的初始设置，未执行写入。请先读取完整设置。");
                    boolean restoring=task.restoreSnapshot!=null;JSONObject restore=restoring?new JSONObject(task.restoreSnapshot):null;
                    if(restoring&&!Snapshots.canRestore(restore))throw new IllegalStateException("初始设置不完整，未执行恢复。");
                    ok=true;
                    for(String[] action:task.commands){ModemResult result=command(context,task,action);appendReport(report,label(action[0])+"："+result.message);if(!result.ok){ok=false;if(!restoring)break;}}
                    ModemResult finalRead=command(context,task,new String[]{"state"});
                    if(!finalRead.ok){ok=false;appendReport(report,"最终读取："+finalRead.message);}
                    if(restoring){boolean matches=finalRead.ok&&Snapshots.matches(restore,finalRead.state);if(!matches)ok=false;appendReport(report,matches?"频段与制式已与初始设置完整比对一致。":"频段与制式尚未与初始设置完整比对一致。");}
                }
            }catch(Exception error){ok=false;appendReport(report,message(error));synchronized(this){statuses.put(task.subscriptionId,message(error));}}
            catch(Throwable error){ok=false;appendReport(report,"操作中断："+message(error));synchronized(this){statuses.put(task.subscriptionId,"操作状态未知，请重新读取。");}}
            String message=report.length()==0?"未返回操作结果":report.toString();
            synchronized(this){log(task.target+" · "+task.title+(ok?"：":"（未全部确认）：")+message);latest=new Completion(id,task,ok,message);running=false;if(owner.get()==null&&worker!=null)worker.shutdown();}
            deliver();
        }
        private void appendReport(StringBuilder report,String text){if(report.length()>0)report.append("\n\n");report.append(text);}
        private String label(String command){switch(command){case "cell-lte":return "LTE 小区";case "cell-nr":return "5G 小区";case "freq-nr":return "5G 频点";case "cell-clear":return "解除小区锁";case "band-lte":return "LTE 频段";case "band-nr":return "5G 频段";case "band-gsm":return "GSM 频段";case "band-wcdma":return "WCDMA 频段";case "nr-mode":return "5G 组网";case "rat":return "网络制式";case "reset":return "全部开放";default:return command;}}
    }
}
