package com.celllock.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import org.json.JSONObject;

/** Android layout/command checks with a recording Host; never invokes the modem. */
public final class UiFlowTest extends Instrumentation {
    private static int checks;
    private static final class TestActivity extends Activity {
        TestActivity(Context context) { attachBaseContext(new android.view.ContextThemeWrapper(context,android.R.style.Theme_Material_Light_NoActionBar)); }
        @Override public android.content.res.Resources.Theme getTheme() { return getBaseContext().getTheme(); }
        @Override public Object getSystemService(String name) { return getBaseContext().getSystemService(name); }
    }
    private static final class Host implements LockPage.Host {
        List<String[]> commands;
        public void onRefresh() { throw new AssertionError("unexpected refresh"); }
        public void onApply(String title,String summary,List<String[]> value) { commands=value; }
        public void onRestore() { throw new AssertionError("unexpected restore"); }
        public void onMessage(String title,String message) { throw new AssertionError(title+": "+message); }
    }
    private static void check(boolean value,String message) { checks++; if(!value)throw new AssertionError(message); }
    @SuppressWarnings("unchecked") private static <T> T field(Object object,String name) throws Exception {
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return (T)f.get(object);
    }
    private static void invoke(Object object,String name) throws Exception {
        var method=object.getClass().getDeclaredMethod(name);method.setAccessible(true);method.invoke(object);
    }
    private static RadioCell target(boolean nr) throws Exception {
        return RadioCell.parse(new JSONObject().put("nr",nr).put("registered",true).put("arfcn",nr?154570:1650)
                .put("pci",nr?139:111).put("band",nr?28:3).put("rsrp",-100).put("timestamp",SystemClock.elapsedRealtime()),"Root 电话");
    }
    private static void command(LockPage page,Host host,String button,String... expected) throws Exception {
        host.commands=null;UiFlowTest.<Button>field(page,button).performClick();
        check(host.commands!=null&&java.util.Arrays.equals(host.commands.get(0),expected),"wrong command from "+button);
    }
    private static void layout(View view,int width) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2200,View.MeasureSpec.EXACTLY));
        view.layout(0,0,width,2200);
    }
    private static List<TextView> texts(View view) {
        List<TextView> result=new ArrayList<>();
        if(view instanceof TextView)result.add((TextView)view);
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)result.addAll(texts(((ViewGroup)view).getChildAt(i)));
        return result;
    }
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments);start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            Throwable[] failure={null};
            runOnMainSync(()->{try{run(getTargetContext());}catch(Throwable error){failure[0]=error;}});
            if(failure[0]!=null)throw new AssertionError(failure[0]);
            result.putString("stream","PASS: "+checks+" Android UI/command checks; no modem writes\n");finish(Activity.RESULT_OK,result);
        } catch(Throwable error) {
            result.putString("stream",android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,result);
        }
    }
    private static void run(Context app) throws Exception {
        Activity activity=new TestActivity(app);Ui ui=new Ui(activity);Host host=new Host();
        LockPage page=new LockPage(activity,ui,host);
        ModemState state=ModemState.parse(new JSONObject("{\"lteCell\":{\"known\":true,\"cells\":[]},\"nrCell\":{\"known\":false},"
                +"\"pref\":{\"known\":true,\"rat\":92,\"nrMode\":0,\"lte\":[3,8],\"nrSa\":[28,78],\"nrNsa\":[28,78],\"gsm\":[900],\"wcdma\":[1],\"legacyMask\":\"0000000000000000\"},"
                +"\"hardware\":{\"known\":true,\"lte\":[3,8],\"nr\":[28,78],\"gsm\":[900],\"wcdma\":[1]}}"));
        page.setState(state);
        Ui.Segmented section=field(page,"sectionTabs"),cell=field(page,"cellTabs"),band=field(page,"bandTabs");
        check(((Button)section.getChildAt(0)).getText().toString().equals("制式")&&((Button)section.getChildAt(2)).getText().toString().equals("小区"),"section order");
        check(((Button)cell.getChildAt(0)).getText().toString().equals("5G NR")&&((Button)band.getChildAt(0)).getText().toString().equals("5G NR"),"NR first");
        check(UiFlowTest.<TextView>field(page,"nrCellStatus").getText().toString().equals("未知"),"unknown cannot imply unlocked");
        check(UiFlowTest.<Button>field(page,"cellApply").getText().toString().equals("锁定")&&UiFlowTest.<Button>field(page,"cellClear").getText().toString().equals("清除解锁"),"short labels");
        RadioCell nr=target(true),lte=target(false);page.setServingCell(nr);page.fillTarget(nr);
        check(section.selected()==2&&cell.selected()==0,"NR target opens NR cell form");
        command(page,host,"cellApply","cell-nr","154570","139","28","15");
        command(page,host,"cellClear","cell-clear","nr");
        UiFlowTest.<Ui.Segmented>field(page,"nrKindTabs").select(1);invoke(page,"updateCellUi");
        command(page,host,"cellApply","freq-nr","154570","15");
        page.fillTarget(lte);check(cell.selected()==1,"LTE target opens LTE form");
        command(page,host,"cellApply","cell-lte","1650","111");command(page,host,"cellClear","cell-clear","lte");
        band.select(0);invoke(page,"renderBandChoices");command(page,host,"bandApply","band-nr","both","28,78");
        band.select(1);invoke(page,"renderBandChoices");command(page,host,"bandApply","band-lte","3,8");
        band.select(2);invoke(page,"renderBandChoices");command(page,host,"bandApply","band-gsm","900");
        check(host.commands.size()==2&&host.commands.get(1)[0].equals("band-wcdma"),"legacy group remains mapped");
        Bundle saved=new Bundle();page.save(saved);LockPage restored=new LockPage(activity,ui,new Host());restored.setState(state);restored.restore(saved);
        check(UiFlowTest.<Ui.Segmented>field(restored,"sectionTabs").selected()==2&&UiFlowTest.<Ui.Segmented>field(restored,"cellTabs").selected()==1,"form survives recreation");

        RadioCell n77=RadioCell.parse(nr.json().put("arfcn",640000).put("band",77),"Root 电话");
        RadioCell candidate=RadioCell.measured(true,false,640000,2,-100,"DIAG NR");
        for(long time:new long[]{SystemClock.elapsedRealtime()-120_000,SystemClock.elapsedRealtime()+60_000}) {
            RadioCell invalid=RadioCell.parse(n77.json().put("timestamp",time),"Root 电话");
            check(!candidate.hasTarget(invalid),"invalid service measurement cannot authorize DIAG target");
            check(candidate.withServingBand(invalid).band==0,"invalid service measurement cannot resolve n77");
            check(!invalid.hasTarget(invalid)&&!invalid.hasTarget(n77),"invalid phone target cannot use reception fallback");
            page.setServingCell(invalid);page.fillTarget(candidate);
            check(cell.selected()==1,"invalid DIAG target cannot open NR lock form");
        }
        page.setServingCell(n77);page.fillTarget(candidate);
        check(cell.selected()==0&&UiFlowTest.<TextView>field(page,"nrBand").getText().toString().equals("77"),"fresh service still fills n77 target");
        RadioCell wrong=RadioCell.parse(nr.json().put("band",41),"Root 电话");
        RadioCell corrected=wrong.withRfBand(28);
        RadioCell correctedNeighbor=RadioCell.measured(true,false,154570,78,-110,"DIAG NR").withServingBand(corrected);
        page.setServingCell(corrected);page.fillTarget(correctedNeighbor);
        command(page,host,"cellApply","cell-nr","154570","78","28","15");
        check(corrected.bandLabel().equals("n28（校正）")&&correctedNeighbor.hasTarget(corrected),"corrected band used by neighbor lock command");
        RadioCell ambiguous=RadioCell.parse(n77.json().put("band",28),"Root 电话");
        page.setServingCell(ambiguous);page.fillTarget(candidate);
        check(UiFlowTest.<TextView>field(page,"nrBand").getText().length()==0&&UiFlowTest.<TextView>field(page,"nrHint").getText().toString().contains("n77 / n78"),"ambiguous band opens manual confirmation without defaulting n78");
        RadioCell nas=RadioCell.measured(true,true,640000,1,-98,"QMI NAS");
        check(candidate.hasTarget(nas),"untimestamped NAS service still supports fresh candidates");
        check(!candidate.hasTarget(nas.withReceivedAt(SystemClock.elapsedRealtime()-15_000)),"expired NAS reference cannot authorize target");

        for(float scale:new float[]{1f,1.3f}) {
            Configuration config=new Configuration(app.getResources().getConfiguration());config.fontScale=scale;
            Activity scaled=new TestActivity(app.createConfigurationContext(config));Ui scaledUi=new Ui(scaled);RadioReader reader=new RadioReader(scaled);
            CellsPage cells=new CellsPage(scaled,scaledUi,reader,new CellsPage.Host(){
                public void onTarget(RadioCell c){}public void onNeedPermission(){}public void onServingChanged(RadioCell c){}public void onSimChanged(){}
            });
            try {
                layout(cells,scaledUi.dp(320));TextView quality=field(cells,"quality");
                check(quality.getWidth()>=quality.getPaint().measureText(quality.getText().toString()),"unknown signal clipped at scale "+scale);
                var display=CellsPage.class.getDeclaredMethod("display",RadioReader.Observation.class);display.setAccessible(true);
                RadioCell neighbor=RadioCell.measured(true,false,154570,78,-110,"DIAG NR · SIM 归属未知")
                        .withDetails(Map.of("RSRQ","-12 dB","TAC","123","连接角色","未连接","频段来源","频点推算"));
                RadioCell phoneNeighbor=RadioCell.parse(lte.json().put("registered",false),"Root 电话");
                display.invoke(cells,new RadioReader.Observation(List.of(nr,neighbor,phoneNeighbor),"Root 电话观测",false));layout(cells,scaledUi.dp(320));
                List<TextView> labels=texts(cells);
                for(String text:new String[]{"-110 dBm","较弱"}) {
                    TextView strength=labels.stream().filter(t->t.getText().toString().equals(text)).findFirst().orElseThrow();
                    check(strength.getPaint().measureText(text)<=strength.getWidth(),"neighbor strength clipped at scale "+scale+": "+text);
                }
                check(labels.stream().anyMatch(t->t.getText().toString().equals("DIAG NR"))&&labels.stream().anyMatch(t->t.getText().toString().equals("RSRQ -12 dB")),"source and quality retained");
                check(labels.stream().noneMatch(t->t.getText().toString().matches(".*(SIM 归属未知|同频候选|连接角色|频段来源|TAC|NCI|Root 电话).*")),"metadata hidden from cards");
                display.invoke(cells,new RadioReader.Observation(List.of(corrected,correctedNeighbor),"QMI 校正",false));layout(cells,scaledUi.dp(320));
                TextView correctedLabel=field(cells,"band"),notice=field(cells,"bandNotice");
                check(correctedLabel.getText().toString().equals("n28（校正）")&&notice.getText().toString().contains("系统原报 n41"),"correction and original report visible");
                check(correctedLabel.getPaint().measureText(correctedLabel.getText().toString())<=correctedLabel.getWidth(),"corrected band clipped at scale "+scale);
                check(UiFlowTest.<Map<View,RadioCell>>field(cells,"targetViews").size()==1,"corrected neighbor remains clickable");
                display.invoke(cells,new RadioReader.Observation(List.of(nr,neighbor,phoneNeighbor),"Root 电话观测",false));layout(cells,scaledUi.dp(320));
                Map<View,RadioCell> targets=field(cells,"targetViews");
                View phoneRow=targets.entrySet().stream().filter(e->e.getValue()==phoneNeighbor).findFirst().orElseThrow().getKey();
                check(phoneRow.isEnabled()&&!phoneRow.getContentDescription().toString().contains("Root 电话"),"fresh phone neighbor enabled without source announcement");
                Field timestamp=RadioCell.class.getDeclaredField("timestamp");timestamp.setAccessible(true);
                timestamp.setLong(phoneNeighbor,SystemClock.elapsedRealtime()-61_000);
                cells.setForeground(false);cells.setForeground(true);
                check(!phoneRow.isEnabled(),"expired neighbor cannot fill target after returning");
                Field reading=CellsPage.class.getDeclaredField("reading");reading.setAccessible(true);reading.setBoolean(cells,true);
                invoke(cells,"updatePolicy");TextView status=field(cells,"observationStatus");
                TextView count=field(cells,"neighborCount");
                check(status.getVisibility()==View.VISIBLE&&status.getText().toString().contains("正在更新小区观测")
                        &&count.getText().toString().equals("2 个 · 更新中"),"return refresh explains unavailable old observations next to neighbors");
                cells.setBusy(true);
                check(status.getVisibility()==View.VISIBLE&&status.getText().toString().contains("小区采集暂停")
                        &&count.getText().toString().equals("2 个 · 已暂停"),"settings work explains temporarily disabled targets next to neighbors");
            } finally { cells.dispose();reader.close(); }
        }
    }
}
