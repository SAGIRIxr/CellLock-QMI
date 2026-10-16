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
import android.text.InputFilter;
import android.text.InputType;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.EditText;
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
    private final ArrayList<SimInfo> subscriptions=new ArrayList<>();
    private RadioReader reader;
    private int selectedSlot=-1;
    private final LinearLayout[] navigation=new LinearLayout[3];
    private final TextView[] navLabels=new TextView[3];
    private final LineIcon[] navIcons=new LineIcon[3];
    private Ui ui;
    private SharedPreferences prefs;
    private Spinner simSpinner;
    private TextView pageTitle;
    private View savedCellsEntry,configurationEntry;
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
    private String dialogKind="message";
    private Task pendingTask;
    private ScrollView dialogScrollView;
    private int dialogScroll;
    private EditText snapshotNoteInput;
    private String snapshotNoteDraft="";
    private final Set<String> expandedDetails=new HashSet<>();

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        ui=new Ui(this);prefs=getSharedPreferences("celllock",MODE_PRIVATE);reader=new RadioReader(this);
        if(saved!=null){subscriptionId=saved.getInt("main.sim",SubscriptionManager.INVALID_SUBSCRIPTION_ID);selectedSlot=saved.getInt("main.slot",-1);selectedPage=Math.max(0,Math.min(2,saved.getInt("main.page")));exportText=saved.getString("main.export","");consumed=SESSION.alive()?Math.max(saved.getLong("main.consumed",-1),SESSION.deliveredId()):-1;SESSION.restoreLogs(saved.getStringArrayList("main.logs"));}
        else consumed=SESSION.deliveredId();
        if(saved!=null)restoreDialog(saved);
        buildUi();ready=true;
        if(saved!=null){cells.restore(saved);locks.restore(saved);device.post(()->device.scrollTo(0,saved.getInt("main.deviceScroll")));}
        if(saved!=null&&saved.getBoolean("main.wasBusy")&&!SESSION.alive())SESSION.log("应用进程已重建，上次操作结果未知，请重新读取确认。");
        SESSION.attach(this);loadSubscriptions();refreshUi();showPage(selectedPage);
        if(saved==null)SESSION.log("CellLock "+version()+" 已就绪。");
    }

    private void buildUi() {
        LinearLayout root=ui.column();root.setBackgroundColor(Ui.BG);
        LinearLayout header=ui.column();header.setPadding(ui.dp(22),ui.dp(16),ui.dp(22),ui.dp(13));
        pageTitle=ui.text(TITLES[selectedPage],24,Ui.INK);pageTitle.setTypeface(null,Typeface.BOLD);LinearLayout titleRow=ui.row();titleRow.setMinimumHeight(ui.dp(48));titleRow.addView(pageTitle,ui.weight(-2));
        LineIcon bookmark=new LineIcon(this,LineIcon.BOOKMARK,Ui.INK);bookmark.setPadding(ui.dp(12),ui.dp(12),ui.dp(12),ui.dp(12));bookmark.setBackground(ui.ripple(android.graphics.Color.TRANSPARENT,24));bookmark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);bookmark.setContentDescription("已保存小区");bookmark.setOnClickListener(v->chooseSavedCells());savedCellsEntry=bookmark;titleRow.addView(bookmark,new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));
        configurationEntry=ui.textButton("配置管理");configurationEntry.setOnClickListener(v->chooseRestore());titleRow.addView(configurationEntry,new LinearLayout.LayoutParams(-2,ui.dp(48)));header.addView(titleRow,ui.fill(-2));ui.gap(header,14);
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
            public void onSavedCells(){chooseSavedCells();}
            public void onMessage(String title,String message){showMessage(title,message);}
        });
        cells=new CellsPage(this,ui,reader,new CellsPage.Host(){
            public void onSaveCell(RadioCell cell,RadioCell serving){saveCell(cell,serving);}
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
            public void onShowLogs(){openDialog("logs","操作记录","");}
        });
        pages=new View[]{cells,locks,device};for(View page:pages)pageHost.addView(page,new FrameLayout.LayoutParams(-1,-1));
        root.addView(ui.divider(),ui.fill(1));LinearLayout nav=ui.row();nav.setPadding(ui.dp(18),ui.dp(8),ui.dp(18),ui.dp(7));nav.setBackgroundColor(Ui.CARD);
        String[] icons={LineIcon.RADIO,LineIcon.LOCK,LineIcon.DEVICE};
        for(int i=0;i<3;i++){final int index=i;LinearLayout tab=ui.column();tab.setGravity(Gravity.CENTER);tab.setPadding(0,ui.dp(5),0,ui.dp(5));navIcons[i]=new LineIcon(this,icons[i],Ui.MUTED);tab.addView(navIcons[i],new LinearLayout.LayoutParams(ui.dp(24),ui.dp(24)));ui.gap(tab,4);navLabels[i]=ui.text(TITLES[i],11,Ui.MUTED);navLabels[i].setGravity(Gravity.CENTER);tab.addView(navLabels[i]);tab.setOnClickListener(v->showPage(index));tab.setContentDescription(TITLES[i]+"页");navigation[i]=tab;LinearLayout.LayoutParams lp=ui.weight(58);lp.setMargins(ui.dp(7),0,ui.dp(7),0);nav.addView(tab,lp);}
        root.addView(nav);setContentView(root);
    }

    private void showPage(int page) {
        selectedPage=page;pageTitle.setText(TITLES[page]);savedCellsEntry.setVisibility(page==0?View.VISIBLE:View.GONE);configurationEntry.setVisibility(page==1?View.VISIBLE:View.GONE);
        for(int i=0;i<3;i++){boolean selected=i==page;pages[i].setVisibility(selected?View.VISIBLE:View.GONE);navigation[i].setBackground(ui.shape(selected?Ui.SOFT_GREEN:Ui.CARD,16,0));navLabels[i].setTextColor(selected?Ui.GREEN:Ui.MUTED);navIcons[i].setColor(selected?Ui.GREEN:Ui.MUTED);}
        View focus=getCurrentFocus();if(focus!=null){InputMethodManager keyboard=getSystemService(InputMethodManager.class);if(keyboard!=null)keyboard.hideSoftInputFromWindow(focus.getWindowToken(),0);focus.clearFocus();}
        cells.setForeground(resumed&&page==0);
        maybeReadInitialState();
    }

    private boolean phonePermission(){return checkSelfPermission(Manifest.permission.READ_PHONE_STATE)==PackageManager.PERMISSION_GRANTED;}
    private void loadSubscriptions() {
        if(!ready||SESSION.running())return;
        reader.loadSims(active->{
            if(isDestroyed())return;
            int previous=subscriptionId,previousSlot=selectedSlot;subscriptions.clear();subscriptions.addAll(active);
            boolean trusted=active.stream().anyMatch(SimInfo::trusted);
            simStatus=SimStatus.resolve(phonePermission(),trusted,hasReadySim(),!active.isEmpty()&&!trusted);
            ArrayList<String> labels=new ArrayList<>();int position=0;
            for(int i=0;i<active.size();i++){SimInfo info=active.get(i);labels.add(info.label);if(info.slot==previousSlot&&(previous<0||info.subscriptionId==previous))position=i;}
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
    private void maybeReadInitialState(){if(ready&&resumed&&(selectedPage==1||selectedPage==2)&&!SESSION.running()&&trustedSim()&&!SESSION.attempted(subscriptionId))readState(false);}

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
        task.refreshAppendBand(SESSION.state(sub));
        pendingTask=task;openDialog("confirm",title,summary);
    }

    private void chooseRestore() {
        if(SESSION.running())return;
        if(!trustedSim()){requestPermissionsManually(false);return;}
        final int sub=subscriptionId;final int slot;
        try{slot=Backend.slotOf(this,sub);}catch(Exception error){showMessage("无法读取配置",message(error));return;}
        pendingTask=new Task(sub,slot,simName(sub),"配置管理",new ArrayList<>(),null);openDialog("restore","配置管理","");
    }

    private boolean validConfigTask(Task task) {
        if(task==null||task.subscriptionId!=subscriptionId||SESSION.running()||!trustedSim()){toast("当前 SIM 已改变或正在操作，请重新选择");return false;}
        try{if(Backend.slotOf(this,task.subscriptionId)!=task.slot)throw new IllegalStateException("SIM 卡槽已改变，请重新选择。");}catch(Exception error){showMessage("无法操作配置",message(error));return false;}
        return true;
    }

    private void chooseSavedCells() {
        if(SESSION.running()||!trustedSim())return;
        try{pendingTask=new Task(subscriptionId,Backend.slotOf(this,subscriptionId),simName(subscriptionId),"已保存小区",new ArrayList<>(),null);openDialog("cells","已保存小区","");}
        catch(Exception error){showMessage("无法读取保存小区",message(error));}
    }
    private void saveCell(RadioCell cell,RadioCell serving) {
        if(SESSION.running()||!trustedSim())return;
        try{pendingTask=new Task(subscriptionId,Backend.slotOf(this,subscriptionId),simName(subscriptionId),"保存小区",new ArrayList<>(),null);JSONObject observation=SavedCells.observation(cell,serving);
            for(JSONObject saved:SavedCells.list(prefs,pendingTask.slot,pendingTask.subscriptionId))if(SavedCells.identity(saved).equals(SavedCells.identity(observation))){observation.put("note",saved.optString("note"));break;}
            openDialog("cell-save","保存小区",observation.toString());}
        catch(Exception error){showMessage("无法保存小区",message(error));}
    }
    private void updateCell(Task task,JSONObject cell,String note,boolean save) {
        if(!validConfigTask(task))return;
        boolean ok=save?SavedCells.save(prefs,task.slot,task.subscriptionId,cell,note):SavedCells.change(prefs,task.slot,task.subscriptionId,cell.optString("id"),note);
        if(!ok){showMessage("保存小区更新未确认","原始记录已保留，请重新打开列表确认。");return;}
        pendingTask=task;openDialog("cells","已保存小区","");
    }
    private void restoreChoice(Task task,int choice) {
        if(!validConfigTask(task))return;
        List<String[]> commands=new ArrayList<>();
        if(choice==0){commands.add(new String[]{"save-config"});confirmApply("保存配置","重新读取当前 SIM 的设置，保存为一份配置快照。能读到的配置可恢复，读不到的项目会标记为未知，恢复时跳过。底层文件（EFS）的检查结果仅供参考，不会自动恢复。此操作不改变网络设置。",commands,null);return;}
        if(choice==1){openDialog("snapshots","快照列表","");return;}
        commands.add(new String[]{"open-bands"});confirmApply("开放全部支持频段","将设备支持的所有频段设为可用；已经全部可用的部分保持原样。\n保留小区/频点锁、网络制式和 5G 组网。开放支持频段与清除全部锁定的作用不同，也不会恢复完整默认频段范围。",commands,null);
    }

    private void snapshotAction(Task task,JSONObject selected,int choice) {
        if(!validConfigTask(task))return;
        final JSONObject snapshot;
        try{snapshot=Snapshots.find(prefs,task.slot,task.subscriptionId,selected.optString("id"));}
        catch(Exception error){showMessage("无法读取快照",message(error));return;}
        if(snapshot==null){showMessage("快照已不存在","请重新打开快照列表。");return;}
        if(choice==1||choice==2){openDialog(choice==1?"snapshot-note":"snapshot-delete",choice==1?"编辑备注":"删除快照",snapshot.toString());return;}
        if(!Snapshots.canRestore(snapshot)){showMessage("没有可自动恢复的项目",Snapshots.describe(snapshot));return;}
        List<String[]> commands=new ArrayList<>();for(String[] withSlot:Snapshots.restoreCommands(snapshot,task.slot)){String[] command=new String[withSlot.length-1];command[0]=withSlot[0];System.arraycopy(withSlot,2,command,1,withSlot.length-2);commands.add(command);}
        confirmApply("读取配置","仅应用快照中可恢复的已知配置，未知项跳过，小区锁按保存内容恢复。\n"+snapshotLabel(snapshot)+"\n"+Snapshots.describe(snapshot),commands,snapshot.toString());
    }

    private void updateSnapshot(Task task,JSONObject snapshot,String note) {
        if(!validConfigTask(task))return;
        boolean ok=note==null?Snapshots.delete(prefs,task.slot,task.subscriptionId,snapshot.optString("id")):Snapshots.updateNote(prefs,task.slot,task.subscriptionId,snapshot.optString("id"),note);
        if(!ok){showMessage("快照更新未确认","快照可能已不存在或本地保存失败，请重新打开列表确认。");return;}
        SESSION.log(task.target+" · "+(note==null?"已删除快照 · "+snapshotLabel(snapshot):"快照备注已更新为："+(note.trim().isEmpty()?"（无备注）":note.trim())));
        pendingTask=task;openDialog("snapshots","快照列表","");
    }

    private static String snapshotLabel(JSONObject snapshot) {
        String note=snapshot.optString("note").trim();return note.isEmpty()?recordTime(snapshot):note+"\n"+recordTime(snapshot);
    }

    private static String recordTime(JSONObject record) {
        return record.optLong("time")>0?new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.ROOT).format(new Date(record.optLong("time"))):"保存时间未知";
    }

    private void recordHeading(LinearLayout body,JSONObject record) {
        String note=record.optString("note").trim();if(!note.isEmpty()){body.addView(ui.title(note));ui.gap(body,6);}
        if(record.has("time")){body.addView(ui.text(recordTime(record),11,Ui.HINT));ui.gap(body,12);}
    }

    private EditText snapshotNote(LinearLayout body) {
        body.addView(ui.caption("备注（选填，最多 "+Snapshots.MAX_NOTE_LENGTH+" 个字符）"));ui.gap(body,8);
        EditText input=ui.numberInput("例如：日常使用、n78 测试");input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);input.setSingleLine(false);input.setMinLines(2);input.setMaxLines(4);input.setTextSize(14);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(Snapshots.MAX_NOTE_LENGTH)});input.setText(snapshotNoteDraft);body.addView(input,ui.fill(-2));snapshotNoteInput=input;return input;
    }

    private void refreshUi() {
        if(!ready)return;boolean busy=SESSION.running();Ui.enable(simSpinner,!busy);Ui.enable(configurationEntry,!busy);Ui.enable(savedCellsEntry,!busy&&trustedSim());locks.setNrCombinedOnly(SESSION.nrCombinedOnly(subscriptionId));locks.setState(SESSION.state(subscriptionId));locks.setBusy(busy);cells.setBusy(busy);
        device.update(SESSION.state(subscriptionId),SESSION.status(subscriptionId),SESSION.lastRead(subscriptionId),busy,SESSION.logs(),simStatus);
    }

    private void receive(Completion completion) {
        if(!ready||isDestroyed())return;
        refreshUi();
        if(!resumed||completion==null||completion.id<=consumed)return;
        consumed=completion.id;SESSION.markDelivered(consumed);
        if(!completion.readOnly)openDialog("result",completion.report.dialogTitle(),completion.report.encode());
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
    @Override protected void onResume(){super.onResume();resumed=true;if(ready){loadSubscriptions();refreshUi();cells.setForeground(selectedPage==0);if(messageVisible&&(messageDialog==null||!messageDialog.isShowing()))renderDialog();SESSION.deliver();maybeReadInitialState();}}
    @Override protected void onPause(){resumed=false;if(cells!=null)cells.setForeground(false);super.onPause();}
    @Override protected void onDestroy(){if(cells!=null)cells.dispose();if(reader!=null)reader.close();if(messageDialog!=null){messageDialog.setOnDismissListener(null);messageDialog.dismiss();messageDialog=null;}SESSION.detach(this);super.onDestroy();}

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);out.putInt("main.sim",subscriptionId);out.putInt("main.slot",selectedSlot);out.putInt("main.page",selectedPage);out.putString("main.export",exportText);out.putLong("main.consumed",consumed);out.putBoolean("main.wasBusy",SESSION.running());out.putStringArrayList("main.logs",SESSION.savedLogs());out.putInt("main.deviceScroll",device.getScrollY());cells.save(out);locks.save(out);
        out.putAll(dialogState());
        
    }
    private Bundle dialogState(){
        Bundle out=new Bundle();out.putBoolean("main.messageVisible",messageVisible);out.putString("main.messageTitle",messageTitle);out.putString("main.messageBody",messageBody);
        out.putString("main.snapshotNote",snapshotNoteInput==null?snapshotNoteDraft:snapshotNoteInput.getText().toString());
        out.putString("main.dialogKind",dialogKind);out.putInt("main.dialogScroll",dialogScrollView==null?dialogScroll:dialogScrollView.getScrollY());out.putStringArrayList("main.expanded",new ArrayList<>(expandedDetails));if(pendingTask!=null)out.putBundle("main.pendingTask",pendingTask.save());
        return out;
    }
    private void restoreDialog(Bundle saved){
        messageVisible=saved.getBoolean("main.messageVisible");messageTitle=saved.getString("main.messageTitle","");messageBody=saved.getString("main.messageBody","");dialogKind=saved.getString("main.dialogKind","message");dialogScroll=saved.getInt("main.dialogScroll");snapshotNoteDraft=saved.getString("main.snapshotNote","");expandedDetails.clear();ArrayList<String> expanded=saved.getStringArrayList("main.expanded");if(expanded!=null)expandedDetails.addAll(expanded);pendingTask=Task.restore(saved.getBundle("main.pendingTask"));
    }
    private void exportLogs() {
        if(SESSION.running())return;
        exportText="CellLock "+version()+" 诊断\n生成："+dateTime()+"\n设备："+Build.MANUFACTURER+" "+Build.MODEL+" / Android "+Build.VERSION.RELEASE+"\n目标："+simName(subscriptionId)+"\n\n"+SESSION.status(subscriptionId)+"\n\n操作记录\n"+String.join("\n\n",SESSION.logs())+"\n\n后端原始回执\n"+SESSION.rawHistory();
        Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT);save.addCategory(Intent.CATEGORY_OPENABLE);save.setType("text/plain");save.putExtra(Intent.EXTRA_TITLE,"CellLock-诊断-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date())+".txt");
        try{startActivityForResult(save,REQUEST_EXPORT);}catch(android.content.ActivityNotFoundException error){showMessage("无法导出","系统未提供文件保存器。");}
    }
    @Override protected void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);if(code!=REQUEST_EXPORT||result!=RESULT_OK||data==null||data.getData()==null)return;try(OutputStream stream=getContentResolver().openOutputStream(data.getData())){if(stream==null)throw new java.io.IOException("保存位置不可写");stream.write(exportText.getBytes(StandardCharsets.UTF_8));SESSION.log("诊断文本已导出。");toast("已导出");}catch(Exception error){showMessage("导出失败",message(error));}refreshUi();}

    private void showMessage(String title,String message){openDialog("message",title,message==null?"未返回说明":message);}
    private void openDialog(String kind,String title,String body) {
        if(isDestroyed()||isFinishing())return;
        dialogKind=kind;messageTitle=title;messageBody=body;messageVisible=true;dialogScroll=0;expandedDetails.clear();
        snapshotNoteDraft="";if(kind.equals("snapshot-note")||kind.equals("cell-note")||kind.equals("cell-save"))try{snapshotNoteDraft=new JSONObject(body).optString("note");}catch(Exception ignored){}
        if(!kind.equals("restore")&&!kind.equals("confirm")&&!kind.startsWith("snapshot")&&!kind.startsWith("cell")&&!kind.equals("clear-choice"))pendingTask=null;
        if(resumed)renderDialog();
    }
    private void renderDialog() {
        if(isDestroyed()||isFinishing())return;
        if(messageDialog!=null){messageDialog.setOnDismissListener(null);messageDialog.dismiss();}
        snapshotNoteInput=null;
        LinearLayout body=ui.column();String subtitle="",action="知道了",cancel=null,direct=null;Runnable apply=null,runDirect=null;
        final Task task=pendingTask;
        switch(dialogKind){
            case "restore":
                subtitle=task==null?"请核对所选 SIM":task.target;
                ui.choice(body,"保存配置","重新读取当前设置，新增快照并可填写备注，不改变基带设置。",()->restoreChoice(task,0));ui.gap(body,12);
                ui.choice(body,"读取配置","选择此 SIM 的快照，可读取、编辑备注或删除。",()->restoreChoice(task,1));ui.gap(body,12);
                ui.choice(body,"开放全部支持频段","允许设备支持的全部频段；保留小区/频点锁、网络制式和 5G 组网。",()->restoreChoice(task,3));ui.gap(body,14);
                body.addView(ui.caption("配置按 SIM 分别保存。首次完整读取会自动备份。每份快照都会列出可恢复的设置、恢复时会跳过的未知项，以及仅供参考的信息。"));action="关闭";break;
            case "cells":
                subtitle=task==null?"":task.target;
                if(task!=null)try{List<JSONObject> records=SavedCells.list(prefs,task.slot,task.subscriptionId);
                    if(records.isEmpty())body.addView(ui.caption("长按当前服务小区卡片或周边小区条目，填写备注后保存。"));
                    for(JSONObject record:records){LinearLayout row=ui.choice(body,record.optString("note").trim().isEmpty()?"保存小区":record.optString("note").trim(),SavedCells.describe(record),recordTime(record),()->openDialog("cell","保存小区详情",record.toString()));row.setPadding(ui.dp(14),ui.dp(12),ui.dp(14),ui.dp(12));row.setBackground(ui.ripple(Ui.BG,14));row.setOnLongClickListener(v->{openDialog("cell-note","编辑备注",record.toString());return true;});ui.gap(body,8);}
                }catch(Exception error){body.addView(ui.caption(message(error)));}action="关闭";break;
            case "cell":case "cell-save":case "cell-note":case "cell-delete":{
                subtitle=task==null?"":task.target;
                try{JSONObject record=new JSONObject(messageBody);recordHeading(body,record);body.addView(ui.caption(SavedCells.describe(record)));if(record.optBoolean("nr")&&record.optInt("scs")>0){ui.gap(body,6);body.addView(ui.caption("SCS "+record.optInt("scs")+" kHz"));}ui.gap(body,12);body.addView(ui.caption(cells.currentlyObserved(record)?"当前观测中发现参数匹配的小区，可供锁定前参考。":"当前未观测到该小区，你仍可填入参数尝试锁定。历史记录不能保证该小区当前可用。"));ui.gap(body,16);
                    if(dialogKind.equals("cell")){
                        ui.choice(body,"用作锁定目标","填入现有表单，由你核对后手动锁定；历史记录不保证仍在覆盖范围。",()->{if(validConfigTask(task)){locks.fillSaved(record);showPage(1);messageDialog.dismiss();}});ui.gap(body,12);
                        ui.choice(body,"编辑备注","仅修改本地备注。",()->openDialog("cell-note","编辑备注",record.toString()));ui.gap(body,12);
                        android.widget.Button delete=ui.dangerButton("删除小区");delete.setOnClickListener(v->openDialog("cell-delete","删除小区",record.toString()));body.addView(delete,ui.fill(48));action="关闭";
                    }else if(dialogKind.equals("cell-delete")){body.addView(ui.caption("确认删除这条保存小区？不改变当前锁定。"));action="确认删除";cancel="返回";apply=()->updateCell(task,record,null,false);}
                    else{EditText input=snapshotNote(body);boolean saving=dialogKind.equals("cell-save");action=saving?"保存小区":"保存备注";cancel="返回";apply=()->updateCell(task,record,input.getText().toString(),saving);}
                }catch(Exception error){body.addView(ui.caption("保存小区无法读取，请重新打开列表。"));}break;
            }
            case "snapshots":
                subtitle=task==null?"请核对所选 SIM":task.target;
                if(task!=null)try{
                    List<JSONObject> snapshots=Snapshots.list(prefs,task.slot,task.subscriptionId);
                    if(snapshots.isEmpty())body.addView(ui.caption("暂无快照，请先保存配置。"));
                    for(JSONObject snapshot:snapshots){ui.choice(body,snapshot.optString("note").trim().isEmpty()?"配置快照":snapshot.optString("note").trim(),Snapshots.describe(snapshot),recordTime(snapshot),()->openDialog("snapshot","快照详情",snapshot.toString()));ui.gap(body,12);}
                }catch(Exception error){body.addView(ui.caption(message(error)));}
                action="关闭";break;
            case "snapshot":case "snapshot-note":case "snapshot-delete":{
                subtitle=task==null?"请核对所选 SIM":task.target;
                try{
                    JSONObject snapshot=new JSONObject(messageBody);recordHeading(body,snapshot);body.addView(selectable(Snapshots.describe(snapshot),14,Ui.INK));ui.gap(body,16);
                    if(dialogKind.equals("snapshot")){
                        ui.choice(body,"读取配置","只恢复已知项目，未知项跳过；详情列出实际恢复范围。",()->snapshotAction(task,snapshot,0));ui.gap(body,12);
                        ui.choice(body,"编辑备注","仅修改这份快照的备注，不改变保存的配置。",()->snapshotAction(task,snapshot,1));ui.gap(body,12);
                        android.widget.Button delete=ui.dangerButton("删除快照");delete.setOnClickListener(v->snapshotAction(task,snapshot,2));body.addView(delete,ui.fill(48));action="关闭";
                    }else if(dialogKind.equals("snapshot-note")){
                        EditText input=snapshotNote(body);action="保存备注";cancel="取消";apply=()->updateSnapshot(task,snapshot,input.getText().toString());
                    }else{
                        body.addView(ui.caption("确认删除这份快照？删除后无法恢复，不改变当前基带设置。"));action="确认删除";cancel="取消";apply=()->updateSnapshot(task,snapshot,null);
                    }
                }catch(Exception error){body.addView(ui.caption("快照无法读取，请重新打开列表。"));}
                break;
            }
            case "confirm":{
                subtitle=task==null?"":task.target;body.addView(selectable(messageBody,14,Ui.INK));ui.gap(body,18);
                final EditText note=task!=null&&task.saving()?snapshotNote(body):null;if(note!=null)ui.gap(body,18);
                if(task!=null&&(task.backupEligible()||task.saving())){
                    ModemState cached=SESSION.state(task.subscriptionId);
                    try{ui.gap(body,10);body.addView(ui.caption(cached==null?"暂时无法确定可恢复的设置；执行时会重新读取，并将读不到的项目标为未知。":Snapshots.describe(Snapshots.capture(cached))));ui.gap(body,8);body.addView(ui.caption("以上为最近一次读取的结果，备份时会重新读取。未知项目恢复时会跳过，底层文件（EFS）的检查信息仅供参考，不会自动恢复。"));ui.gap(body,16);}catch(Exception ignored){}
                }
                TextView warning=selectable(note!=null?"保存会新增一份快照，已有快照会保留。":"可能短暂断网；请核对操作范围，并选择是否先备份。",12,Ui.AMBER);warning.setPadding(ui.dp(13),ui.dp(12),ui.dp(13),ui.dp(12));warning.setBackground(ui.shape(Ui.SOFT_AMBER,12,0));body.addView(warning);
                if(task!=null&&task.backupEligible()){
                    String[] first=task.commands.get(0);
                    if(task.appendBand){ui.gap(body,12);body.addView(ui.caption("目标频段尚未开放，锁定时会自动追加，并保留原已允许的频段、网络制式和 5G 组网。"));}
                    String verb=first[0].equals("open-bands")?"开放":first[0].startsWith("clear-")||first[0].equals("cell-clear")?"清除":"锁定";
                    action="备份并"+verb;direct="直接"+verb;cancel="返回";apply=()->{task.backup=true;startConfirmed(task);};runDirect=()->{task.backup=false;startConfirmed(task);};
                }else{
                    action=task!=null&&task.saving()?"确认保存":task!=null&&task.restoreSnapshot!=null?"应用配置":task!=null&&task.commands.get(0)[0].startsWith("band-")?"应用频段":"确认应用";
                    cancel="返回";apply=()->{if(note==null)startConfirmed(task);else{List<String[]> commands=new ArrayList<>();commands.add(new String[]{"save-config",note.getText().toString()});startConfirmed(new Task(task.subscriptionId,task.slot,task.target,task.title,commands,null));}};
                }break;
            }
            case "result":
                OperationReport report=OperationReport.decode(messageBody);
                if(report!=null){
                    subtitle=report.target;
                    if(!report.restoreStatus.isEmpty()){
                        boolean matched=report.restoreStatus.equals("matched");
                        TextView conclusion=selectable(matched?"可恢复的已知项目已与快照一致；未知项未改动。":report.restoreStatus.equals("mismatch")?"部分配置仍与保存的原值不同，具体差异见下方完整核对。":"配置回读不完整，暂时无法核对已保存配置。",13,matched?Ui.GREEN:Ui.AMBER);
                        conclusion.setPadding(ui.dp(12),ui.dp(12),ui.dp(12),ui.dp(12));conclusion.setBackground(ui.shape(matched?Ui.SOFT_GREEN:Ui.SOFT_AMBER,12,0));body.addView(conclusion);ui.gap(body,10);
                    }
                    for(ResultItem item:report.items)resultRow(body,item);details(body,"诊断详情",report.details,"result");
                }
                else body.addView(selectable("结果无法恢复，请重新读取确认。",13,Ui.AMBER));break;
            case "logs":
                subtitle="包含全部 SIM 的操作";List<LogRecord> records=SESSION.records();
                if(records.isEmpty())body.addView(ui.caption("暂无记录"));
                for(int i=0;i<records.size();i++){
                    LogRecord record=records.get(i);LinearLayout heading=ui.row();heading.addView(ui.text(record.time,12,Ui.MUTED),ui.weight(-2));
                    if(record.report!=null){boolean ok=record.report.ok();heading.addView(ui.pill(record.report.outcome(),ok?Ui.GREEN:Ui.AMBER,ok?Ui.SOFT_GREEN:Ui.SOFT_AMBER));}
                    body.addView(heading);ui.gap(body,7);body.addView(selectable(record.text,14,Ui.INK));
                    if(record.report!=null){ui.gap(body,6);body.addView(selectable(record.report.summary(),12,Ui.MUTED));details(body,"查看详情",record.report.details,"log"+i);}
                    if(i<records.size()-1){ui.gap(body,14);body.addView(ui.divider(),ui.fill(1));ui.gap(body,14);}
                }
                break;
            default:body.addView(selectable(messageBody,13,Ui.INK));
        }
        AlertDialog dialog=ui.dialog(messageTitle,subtitle,body,action,apply,cancel,direct,runDirect);messageDialog=dialog;
        dialog.setOnDismissListener(ignored->{if(messageDialog==dialog){messageDialog=null;messageVisible=false;messageTitle="";messageBody="";pendingTask=null;dialogScrollView=null;snapshotNoteInput=null;expandedDetails.clear();}});
        dialog.show();dialogScrollView=(ScrollView)body.getParent();dialogScrollView.post(()->{if(dialogScrollView!=null)dialogScrollView.scrollTo(0,dialogScroll);});
    }
    private TextView selectable(String value,int size,int color){TextView text=ui.text(value,size,color);text.setTextIsSelectable(true);return text;}
    private void resultRow(LinearLayout parent,ResultItem item){
        LinearLayout row=ui.row();row.setGravity(Gravity.TOP);row.setPadding(0,ui.dp(11),0,ui.dp(11));boolean good=item.confirmed();
        row.addView(ui.pill(good?"✓":"!",good?Ui.GREEN:Ui.AMBER,good?Ui.SOFT_GREEN:Ui.SOFT_AMBER));ui.gap(row,11);
        LinearLayout labels=ui.column();labels.addView(selectable(item.name+" · "+item.label(),14,Ui.INK));if(!item.detail.isEmpty()){ui.gap(labels,5);labels.addView(selectable(item.detail,12,Ui.MUTED));}row.addView(labels,ui.weight(-2));parent.addView(row,ui.fill(-2));parent.addView(ui.divider(),ui.fill(1));
    }
    private void details(LinearLayout parent,String label,String text,String key){
        if(text==null||text.isEmpty())return;ui.gap(parent,12);
        android.widget.Button toggle=ui.button(label,false);toggle.setTextSize(12);toggle.setMinHeight(ui.dp(48));parent.addView(toggle,ui.fill(-2));
        TextView content=selectable(text,11,Ui.MUTED);content.setTypeface(Typeface.MONOSPACE);content.setPadding(ui.dp(11),ui.dp(11),ui.dp(11),ui.dp(11));content.setBackground(ui.shape(Ui.BG,10,0));content.setVisibility(expandedDetails.contains(key)?View.VISIBLE:View.GONE);parent.addView(content,ui.fill(-2));
        toggle.setText((content.getVisibility()==View.VISIBLE?"收起":"展开")+label);toggle.setOnClickListener(v->{boolean open=content.getVisibility()!=View.VISIBLE;content.setVisibility(open?View.VISIBLE:View.GONE);if(open)expandedDetails.add(key);else expandedDetails.remove(key);toggle.setText((open?"收起":"展开")+label);});
    }
    private void startConfirmed(Task task){
        if(task==null||SESSION.running()||subscriptionId!=task.subscriptionId||!trustedSim()){toast("当前 SIM 已改变，请重新确认");return;}
        try{if(Backend.slotOf(this,task.subscriptionId)!=task.slot)throw new IllegalStateException("SIM 卡槽已改变，请重新确认");if(SESSION.start(getApplicationContext(),task))refreshUi();}
        catch(Exception error){showMessage("无法执行",message(error));}
    }
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_SHORT).show();}
    private String version(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(PackageManager.NameNotFoundException ignored){return "未知";}}
    private static String message(Throwable error){return error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();}
    private static String clock(){return new SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date());}
    private static String dateTime(){return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.ROOT).format(new Date());}
    private static List<String[]> copyCommands(List<String[]> input){ArrayList<String[]> output=new ArrayList<>();if(input!=null)for(String[] command:input)if(command!=null&&command.length>0)output.add(command.clone());return output;}

    private static final class Task {
        final int subscriptionId,slot;final String target,title,restoreSnapshot;final List<String[]> commands;boolean backup,appendBand;
        Task(int subscriptionId,int slot,String target,String title,List<String[]> commands,String restoreSnapshot){this.subscriptionId=subscriptionId;this.slot=slot;this.target=target;this.title=title;this.commands=copyCommands(commands);this.restoreSnapshot=restoreSnapshot;}
        boolean readOnly(){return commands.isEmpty();}
        boolean saving(){return commands.size()==1&&commands.get(0)[0].equals("save-config");}
        boolean backupEligible(){if(commands.size()!=1)return false;String cmd=commands.get(0)[0];return cmd.equals("cell-lte")||cmd.equals("cell-nr")||cmd.equals("freq-nr")||cmd.equals("cell-clear")||cmd.equals("open-bands")||cmd.equals("clear-quick")||cmd.equals("clear-complete");}
        void refreshAppendBand(ModemState state){appendBand|=backupEligible()&&LockPlan.excluded(state,commands.get(0));}
        Bundle save(){Bundle out=new Bundle();out.putBoolean("backup",backup);out.putBoolean("append",appendBand);out.putInt("sub",subscriptionId);out.putInt("slot",slot);out.putString("target",target);out.putString("title",title);out.putString("snapshot",restoreSnapshot);out.putInt("count",commands.size());for(int i=0;i<commands.size();i++)out.putStringArray("command"+i,commands.get(i));return out;}
        static Task restore(Bundle in){if(in==null)return null;List<String[]> commands=new ArrayList<>();for(int i=0;i<in.getInt("count");i++)commands.add(in.getStringArray("command"+i));Task task=new Task(in.getInt("sub"),in.getInt("slot"),in.getString("target",""),in.getString("title",""),commands,in.getString("snapshot"));task.backup=in.getBoolean("backup");task.appendBand=in.getBoolean("append");return task;}
    }
    private static final class Completion {
        final long id;final boolean readOnly;final OperationReport report;
        Completion(long id,Task task,OperationReport report){this.id=id;this.readOnly=task.readOnly();this.report=report;}
    }

    private static final class Session {
        private final Handler main=new Handler(Looper.getMainLooper());
        private final Map<Integer,ModemState> states=new HashMap<>();
        private final Map<Integer,String> statuses=new HashMap<>(),readTimes=new HashMap<>();
        private final Set<Integer> attempted=new HashSet<>();
        private final ArrayList<LogRecord> logs=new ArrayList<>();
        private final ArrayList<String> raw=new ArrayList<>();
        private final Set<Integer> nrCombined=new HashSet<>();
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
        synchronized List<String> logs(){ArrayList<String> text=new ArrayList<>();for(LogRecord entry:logs)text.add(entry.display());return text;}
        synchronized List<LogRecord> records(){return new ArrayList<>(logs);}
        synchronized ArrayList<String> savedLogs(){ArrayList<String> text=new ArrayList<>();for(LogRecord entry:logs)text.add(entry.encode());return text;}
        synchronized boolean nrCombinedOnly(int sub){return nrCombined.contains(sub);}
        synchronized String rawHistory(){return String.join("\n\n",raw);}
        synchronized void log(String text){logs.add(new LogRecord(clock(),text,null));trimLogs();}
        private void trimLogs(){int size=0;for(LogRecord entry:logs)size+=entry.encode().length();while(logs.size()>100||size>180000&&logs.size()>1)size-=logs.remove(0).encode().length();}
        synchronized void clearLogs(){logs.clear();raw.clear();}
        synchronized void restoreLogs(ArrayList<String> previous){if(logs.isEmpty()&&previous!=null){for(String text:previous)logs.add(LogRecord.decode(text));trimLogs();}}
        synchronized void attach(MainActivity activity){owner=new WeakReference<>(activity);deliver();}
        synchronized void detach(MainActivity activity){if(owner.get()!=activity)return;owner.clear();if(worker!=null)worker.shutdown();}
        void deliver(){main.post(()->{MainActivity activity;Completion completion;synchronized(this){activity=owner.get();completion=latest;}if(activity!=null&&!activity.isDestroyed())activity.receive(completion);});}
        synchronized boolean start(Context context,Task task) {
            if(task.commands.stream().anyMatch(command->command[0].equals("reset")||command[0].equals("reset-combined")||command[0].equals("clear-all")||command[0].equals("register-default")))throw new IllegalStateException("旧版任务已失效，请重新选择操作并确认范围。");
            if(running)return false;running=true;attempted.add(task.subscriptionId);final long id=++sequence;final Context app=context.getApplicationContext();
            if(worker==null||worker.isShutdown())worker=Executors.newSingleThreadExecutor();
            log(task.target+" · "+(task.readOnly()?"开始读取设置":"开始"+task.title));
            worker.execute(()->execute(app,task,id));return true;
        }
        private void verifySlot(Context context,Task task){
            try{RadioReader.verifySlot(context,task.subscriptionId,task.slot);return;}catch(Exception ignored){}
            synchronized(this){states.remove(task.subscriptionId);nrCombined.remove(task.subscriptionId);}
            throw new IllegalStateException("所选 SIM 的卡槽已改变或无法确认，已停止后续操作。操作结果需重新确认，请重新读取。");
        }
        private ModemResult command(Context context,Task task,String[] command) {
            command=command.clone();
            if(nrCombinedOnly(task.subscriptionId)){
                if((command[0].equals("band-nr")||command[0].equals("restore-nr"))&&command.length>1&&command[1].equals("both"))command[0]+="-combined";

            }
            verifySlot(context,task);String[] args=new String[command.length+1];args[0]=command[0];args[1]=String.valueOf(task.slot);if(command.length>1)System.arraycopy(command,1,args,2,command.length-1);
            ModemResult result=Backend.run(context,args);
            synchronized(this){raw.add(dateTime()+" · "+task.target+"（slot "+task.slot+"） · "+command[0]+"\n"+(result.raw!=null&&!result.raw.isEmpty()?result.raw:result.stage+"："+result.message));while(raw.size()>12)raw.remove(0);}
            verifySlot(context,task);
            synchronized(this){
                if(result.nrCombinedOnly)nrCombined.add(task.subscriptionId);
                if(result.state!=null){result.state.inheritHardware(states.get(task.subscriptionId));states.put(task.subscriptionId,result.state);readTimes.put(task.subscriptionId,clock());}else if("state".equals(command[0]))states.remove(task.subscriptionId);
                statuses.put(task.subscriptionId,result.state!=null&&result.state.prefKnown&&result.state.hwKnown?"Root 已授权 · 配置已读取"+(!result.state.nrKnown?" · 5G 小区锁未知":""):result.message);
            }
            return result;
        }
        private void execute(Context context,Task task,long id) {
            OperationReport report=new OperationReport(task.title,task.target);
            try{
                SharedPreferences prefs=context.getSharedPreferences("celllock",Context.MODE_PRIVATE);
                ModemResult initial=command(context,task,new String[]{"state"});
                if(task.readOnly()&&initial.ok&&initial.state!=null)Snapshots.rememberIfFirst(prefs,task.slot,task.subscriptionId,initial.state);
                if(task.saving()){
                    String note=task.commands.get(0).length>1?task.commands.get(0)[1]:"";
                    if(!saveSnapshot(context,prefs,task,initial.state,note))throw new IllegalStateException("配置读取或本地保存失败；未确认保存成功。");
                    report.items.add(new ResultItem("保存配置","saved","已新增快照。\n"+Snapshots.describe(Snapshots.load(prefs,task.slot,task.subscriptionId))));
                }else if(task.readOnly()){report.add("读取设置",initial);}
                else{
                    if(!initial.ok||initial.state==null)throw new IllegalStateException("写入前读取未完成，未执行写入。\n"+initial.message);
                    String kind=task.commands.get(0)[0];
                    task.refreshAppendBand(initial.state);
                    if(task.backup){String note="操作前备份 · "+task.target+" · "+task.title+" · "+commandSummary(task.commands.get(0))+(task.appendBand?" · 追加目标频段":"");if(!saveSnapshot(context,prefs,task,initial.state,note))throw new IllegalStateException("备份失败，已停止后续写入。请重新选择操作。");report.items.add(new ResultItem("操作前备份","saved",Snapshots.describe(Snapshots.load(prefs,task.slot,task.subscriptionId))));}
                    {
                        if(task.appendBand){ModemResult fresh=command(context,task,new String[]{"state"});if(!fresh.ok||fresh.state==null)throw new IllegalStateException("追加前重新读取失败，已停止写入。");for(String[] action:LockPlan.append(fresh.state,task.commands.get(0))){ModemResult result=command(context,task,action);report.add("追加目标频段",result);if(!result.ok)throw new IllegalStateException("追加结果未确认，已停止小区锁定。");}}
                        List<String[]> actions=kind.equals("open-bands")?LockPlan.openSupported(initial.state):task.commands;
                        boolean restoring=task.restoreSnapshot!=null;JSONObject restore=restoring?new JSONObject(task.restoreSnapshot):null;
                        if(restoring&&!Snapshots.canRestore(restore))throw new IllegalStateException("这份快照没有可恢复的已知配置。");
                        for(String[] action:actions){ModemResult result=command(context,task,action);report.add(label(action),result);if(!result.ok&&!restoring)break;}
                        ModemResult finalRead=command(context,task,new String[]{"state"});
                        if(!finalRead.ok)report.add("最终读取",finalRead);
                        if(kind.equals("open-bands"))preservation(initial.state,finalRead.state,report,kind.equals("open-bands"));
                        if(restoring){boolean matches=finalRead.ok&&Snapshots.matches(restore,finalRead.state);report.restoreStatus=matches?"matched":finalRead.ok?"mismatch":"unknown";report.items.add(new ResultItem("快照已知配置核对",matches?"verified":report.restoreStatus,"仍需核对："+(matches?"无；未知项目已跳过":String.join("、",Snapshots.differences(restore,finalRead.state)))));addSkipped(restore,report);}
                    }
                }
            }catch(Exception error){
                report.items.add(new ResultItem("操作","failed",message(error)));
                synchronized(this){statuses.put(task.subscriptionId,message(error));}
            }
            catch(Throwable error){report.items.add(new ResultItem("操作","interrupted",message(error)));synchronized(this){statuses.put(task.subscriptionId,"操作状态未知，请重新读取。");}}
            synchronized(this){logs.add(new LogRecord(clock(),task.target+" · "+task.title,report));trimLogs();latest=new Completion(id,task,report);running=false;if(owner.get()==null&&worker!=null)worker.shutdown();}
            deliver();
        }
        private boolean saveSnapshot(Context context,SharedPreferences prefs,Task task,ModemState state,String note) {
            if(state==null||!state.readAttempted)return false;
            verifySlot(context,task);JSONObject evidence=BandDefaults.evidence(context);
            verifySlot(context,task);return Snapshots.save(prefs,task.slot,task.subscriptionId,state,note.length()>Snapshots.MAX_NOTE_LENGTH?note.substring(0,Snapshots.MAX_NOTE_LENGTH):note,evidence);
        }
        private void preservation(ModemState before,ModemState after,OperationReport report,boolean cells) throws Exception {
            boolean same=after!=null&&after.prefKnown&&before.prefKnown&&before.rat>=0&&before.nrMode>=0&&after.rat==before.rat&&after.nrMode==before.nrMode;
            report.items.add(new ResultItem("制式与 5G 组网",same?"unchanged":"unknown",same?"已确认保留":"无法确认保留，请刷新核对"));
            if(cells){JSONObject locks=Snapshots.capture(before);for(String key:new String[]{"rat","nrMode","legacyMask","lte","nrSa","nrNsa","gsm","wcdma"})locks.put(key,JSONObject.NULL);if(Snapshots.canRestore(locks))report.items.add(new ResultItem("原小区/频点锁",Snapshots.matches(locks,after)?"unchanged":"unknown","本操作未发送清除命令；按已知的原锁参数回读核对。"));else report.items.add(new ResultItem("原小区/频点锁","skipped","原状态未知；本操作未发送清除命令。"));}
        }
        private void addSkipped(JSONObject snapshot,OperationReport report){
            for(String field:new String[]{"lte","nrSa","nrNsa","legacyMask","rat","nrMode"})if(snapshot.isNull(field))report.items.add(new ResultItem("未知 "+field,"skipped","未改动此项目"));
            if(snapshot.has("evidence"))report.items.add(new ResultItem("EFS 与设备证据","recorded","未自动重放持久存储内容或缺失态"));
            if(snapshot.optInt("schema")==2||snapshot.optJSONObject("lteCell")==null||!snapshot.optJSONObject("lteCell").optBoolean("known"))report.items.add(new ResultItem("未知 LTE 小区锁","skipped","未清除或重放"));
            JSONObject nr=snapshot.optJSONObject("nrCell");if(nr==null||!nr.optBoolean("known"))report.items.add(new ResultItem("未知 5G 小区/频点锁","skipped","未清除或重放"));else if(nr.optInt("type")>2)report.items.add(new ResultItem("5G 锁类型 "+nr.optInt("type"),"recorded","未验证重放，已跳过"));
        }
        private String commandSummary(String[] command){return String.join(" ",command);}
        private String label(String[] action){switch(action[0]){case "cell-lte-list":case "cell-lte":return "LTE 小区";case "cell-nr-mask":case "cell-nr":return "5G 小区";case "freq-nr-list":case "freq-nr":return "5G 频点";case "cell-clear":return action.length>1&&action[1].equals("nr")?"清除 5G 小区/频点锁":"清除 LTE 小区锁";case "restore-lte":case "band-lte":return "LTE 频段";case "restore-nr":case "band-nr":return "5G "+(action[1].equals("both")?"SA＋NSA":action[1].toUpperCase(Locale.ROOT))+" 频段";case "restore-legacy":return "原始 2G / 3G 配置";case "band-gsm":return "GSM 频段";case "band-wcdma":return "WCDMA 频段";case "nr-mode":return "5G 组网";case "rat":return "网络制式";default:return action[0];}}
    }
}
