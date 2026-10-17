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
    static final class TestActivity extends Activity {
        TestActivity(Context context) { attachBaseContext(new android.view.ContextThemeWrapper(context,android.R.style.Theme_Material_Light_NoActionBar)); }
        @Override public android.content.res.Resources.Theme getTheme() { return getBaseContext().getTheme(); }
        @Override public Object getSystemService(String name) { return getBaseContext().getSystemService(name); }
    }
    private static final class Host implements LockPage.Host {
        List<String[]> commands;
        int savedCalls;
        public void onRefresh() { throw new AssertionError("unexpected refresh"); }
        public void onApply(String title,String summary,List<String[]> value) { commands=value; }
        public void onSavedCells() { savedCalls++; }
        public void onMessage(String title,String message) { throw new AssertionError(title+": "+message); }
    }
    private static void check(boolean value,String message) { checks++; if(!value)throw new AssertionError(message); }
    @SuppressWarnings("unchecked") static <T> T field(Object object,String name) throws Exception {
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
        for(float scale:new float[]{1f,1.3f})for(int widthDp:new int[]{120,320,360}){
            Configuration config=new Configuration(app.getResources().getConfiguration());config.fontScale=scale;
            Ui gridUi=new Ui(app.createConfigurationContext(config));FlowLayout grid=new FlowLayout(gridUi.context,gridUi.dp(8));grid.setPadding(gridUi.dp(3),gridUi.dp(3),gridUi.dp(3),gridUi.dp(3));
            for(String label:new String[]{"n1","n3","n28","n41","n77","n78"})grid.addView(gridUi.chip(label,"2100"));
            grid.getChildAt(1).setVisibility(View.GONE);int width=gridUi.dp(widthDp);
            grid.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));grid.layout(0,0,width,grid.getMeasuredHeight());
            View previous=null;List<View> first=new ArrayList<>();
            for(int i=0;i<grid.getChildCount();i++){View child=grid.getChildAt(i);if(child.getVisibility()==View.GONE)continue;if(child.getTop()==grid.getPaddingTop())first.add(child);
                check(child.getLeft()>=grid.getPaddingLeft()&&child.getRight()<=width-grid.getPaddingRight(),"frequency grid stays inside padding");
                if(previous!=null)check(child.getTop()==previous.getTop()?child.getLeft()-previous.getRight()==gridUi.dp(8):child.getTop()-previous.getBottom()==gridUi.dp(8),"grid spacing stays uniform after hidden chips");previous=child;
            }
            check(first.get(first.size()-1).getRight()==width-grid.getPaddingRight(),"complete frequency row fills available width");
            check(first.stream().allMatch(v->Math.abs(v.getWidth()-first.get(0).getWidth())<=1),"frequency columns have equal width");
            grid.removeAllViews();grid.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));check(grid.getMeasuredHeight()==grid.getPaddingTop()+grid.getPaddingBottom(),"empty frequency grid has no phantom row");
        }
        Activity activity=new TestActivity(app);Ui ui=new Ui(activity);Host host=new Host();
        LockPage page=new LockPage(activity,ui,host);
        android.widget.Spinner scs=field(page,"scs");
        check(scs.getBackgroundTintList()==null,"SCS custom background must not inherit the system dark tint");
        check(texts(page).stream().anyMatch(t->t.getText().toString().equals("SCS"))&&texts(page).stream().anyMatch(t->t.getText().toString().equals("NR Band")),"lock parameters use consistent technical labels");
        LineIcon bookmark=new LineIcon(activity,LineIcon.BOOKMARK,Ui.INK);bookmark.setPadding(12,12,12,12);bookmark.layout(0,0,48,48);
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(48,48,android.graphics.Bitmap.Config.ARGB_8888);bookmark.draw(new android.graphics.Canvas(bitmap));
        boolean drawn=false,outside=false;for(int y=0;y<48;y++)for(int x=0;x<48;x++)if(android.graphics.Color.alpha(bitmap.getPixel(x,y))>0){drawn=true;if(x<12||x>=36||y<12||y>=36)outside=true;}
        check(!outside,"bookmark drawing must respect its padded touch area");check(drawn,"bookmark remains visible");bitmap.recycle();
        ModemState state=ModemState.parse(new JSONObject("{\"lteCell\":{\"known\":true,\"cells\":[]},\"nrCell\":{\"known\":false},"
                +"\"pref\":{\"known\":true,\"rat\":92,\"nrMode\":0,\"lte\":[3,8],\"nrSa\":[28,78],\"nrNsa\":[28,78],\"gsm\":[900],\"wcdma\":[1],\"legacyMask\":\"0000000000000000\"},"
                +"\"hardware\":{\"known\":true,\"lte\":[3,8],\"nr\":[28,78],\"gsm\":[900],\"wcdma\":[1]}}"));
        page.setState(state);
        check(java.util.Arrays.stream(UiFlowTest.<java.util.BitSet[]>field(page,"bandDrafts")).allMatch(java.util.BitSet::isEmpty),"known preferences must start with no draft selection");
        ModemState unknown=new ModemState();unknown.inheritHardware(state);page.setState(unknown);
        check(java.util.Arrays.stream(UiFlowTest.<java.util.BitSet[]>field(page,"bandDrafts")).allMatch(java.util.BitSet::isEmpty),"unknown preferences must not select all hardware bands");
        state.nrSa.clear(28);state.nrNsa.clear(28);page.setState(state);
        command(page,host,"bandApply","band-nr","both","78");
        check(UiFlowTest.<java.util.BitSet[]>field(page,"bandDrafts")[2].get(78)&&UiFlowTest.<java.util.BitSet[]>field(page,"bandDrafts")[3].get(78),"n78 restriction selected for SA and NSA");
        state.nrSa.set(28);page.setState(state);
        check(UiFlowTest.<java.util.BitSet[]>field(page,"bandDrafts")[2].isEmpty()&&UiFlowTest.<java.util.BitSet[]>field(page,"bandDrafts")[3].get(78),"SA and NSA default selections follow their separate restrictions");
        state.nrNsa.set(28);page.setState(state);
        check(UiFlowTest.<java.util.BitSet[]>field(page,"bandDrafts")[1].isEmpty(),"opening all bands clears untouched default selection");
        page.setState(state);
        UiFlowTest.<List<TextView>>field(page,"bandChips").get(0).performClick();
        command(page,host,"bandApply","band-nr","both","28");
        page.setState(state);
        command(page,host,"bandApply","band-nr","both","28");
        Bundle draft=new Bundle();page.save(draft);LockPage draftRestored=new LockPage(activity,ui,new Host());draftRestored.setState(state);draftRestored.restore(draft);
        check(UiFlowTest.<java.util.BitSet[]>field(draftRestored,"bandDrafts")[1].equals(UiFlowTest.<java.util.BitSet[]>field(page,"bandDrafts")[1]),"selected draft survives recreation");
        page.resetForSubscription();page.setState(state);
        check(java.util.Arrays.stream(UiFlowTest.<java.util.BitSet[]>field(page,"bandDrafts")).allMatch(java.util.BitSet::isEmpty),"SIM switch clears selected draft");
        check(!UiFlowTest.<Button>field(page,"bandApply").isEnabled(),"empty draft cannot write empty modem bands");
        Ui.Segmented section=field(page,"sectionTabs"),cell=field(page,"cellTabs"),band=field(page,"bandTabs");
        check(((Button)section.getChildAt(0)).getText().toString().equals("制式")&&((Button)section.getChildAt(2)).getText().toString().equals("小区"),"section order");
        check(((Button)cell.getChildAt(0)).getText().toString().equals("5G NR")&&((Button)band.getChildAt(0)).getText().toString().equals("5G NR"),"NR first");
        check(UiFlowTest.<TextView>field(page,"nrCellStatus").getText().toString().equals("未知"),"unknown cannot imply unlocked");
        check(UiFlowTest.<Button>field(page,"cellApply").getText().toString().equals("锁定")&&UiFlowTest.<Button>field(page,"cellClear").getText().toString().equals("清除 5G 小区/频点锁"),"specific NR unlock label");
        RadioCell nr=target(true),lte=target(false);page.setServingCell(nr);page.fillTarget(nr);
        for(RadioCell target:List.of(nr,lte))for(String name:new String[]{"MCC","MNC","PLMN"}) {
            RadioCell primary=RadioCell.parse(target.json().put("rsrp",android.telephony.CellInfo.UNAVAILABLE),"Root 电话")
                    .withDetails(Map.of(name,"001"));
            RadioCell other=RadioCell.measured(target.nr,true,target.arfcn,target.pci,-95,"QMI NAS")
                    .withDetails(Map.of(name,"002","补充来源","QMI SYS / RF"));
            List<RadioCell> readings=new ArrayList<>(List.of(primary));
            RadioReader.supplementReadings(readings,List.of(other));
            check(readings.equals(List.of(primary,other))&&!primary.hasSignal()&&!other.hasTarget(primary),"different operator must remain separate on Android: "+name);
            readings=new ArrayList<>(List.of(primary));
            RadioReader.supplementReadings(readings,List.of(other.withDetails(Map.of(name,"001","补充来源","QMI SYS / RF"))));
            check(readings.size()==1&&readings.get(0).rsrp==-95,"matching operator still supplements on Android: "+name);
        }
        check(section.selected()==2&&cell.selected()==0,"NR target opens NR cell form");
        command(page,host,"cellApply","cell-nr","154570","139","28","15");
        command(page,host,"cellClear","cell-clear","nr");
        UiFlowTest.<Ui.Segmented>field(page,"nrKindTabs").select(1);invoke(page,"updateCellUi");
        command(page,host,"cellApply","freq-nr","154570","15");
        page.fillTarget(lte);check(cell.selected()==1,"LTE target opens LTE form");
        check(UiFlowTest.<Button>field(page,"cellClear").getText().toString().equals("清除 LTE 小区锁"),"LTE unlock label follows selected radio");
        command(page,host,"cellApply","cell-lte","1650","111");command(page,host,"cellClear","cell-clear","lte");
        page.fillSaved(new JSONObject().put("schema",1).put("nr",true).put("arfcn",633984).put("pci",202).put("band",78).put("scs",30).put("seenAt",1));
        check(section.selected()==2&&cell.selected()==0,"historical target enters the same form without recent timestamp");
        command(page,host,"cellApply","cell-nr","633984","202","78","30");
        check(UiFlowTest.<TextView>field(page,"nrHint").getText().toString().contains("保存小区"),"historical source remains visible");
        page.fillSaved(new JSONObject().put("schema",1).put("nr",true).put("arfcn",Integer.MAX_VALUE).put("pci",Integer.MAX_VALUE).put("band",0).put("scs",0));
        check(UiFlowTest.<android.widget.EditText>field(page,"nrArfcn").getText().length()==0&&UiFlowTest.<android.widget.EditText>field(page,"nrPci").getText().length()==0,"incomplete historical parameters require form completion");
        page.fillTarget(lte);
        band.select(0);invoke(page,"renderBandChoices");UiFlowTest.<Button>field(page,"bandAll").performClick();command(page,host,"bandApply","band-nr","both","28,78");
        band.select(1);invoke(page,"renderBandChoices");UiFlowTest.<Button>field(page,"bandAll").performClick();command(page,host,"bandApply","band-lte","3,8");
        band.select(2);invoke(page,"renderBandChoices");UiFlowTest.<Button>field(page,"bandAll").performClick();command(page,host,"bandApply","band-gsm","900");
        check(host.commands.size()==2&&host.commands.get(1)[0].equals("band-wcdma"),"legacy group remains mapped");
        band.select(0);Ui.Segmented nrTarget=field(page,"nrTargetTabs");nrTarget.select(1);page.setNrCombinedOnly(true);
        check(nrTarget.selected()==0&&!nrTarget.getChildAt(1).isEnabled()&&!nrTarget.getChildAt(2).isEnabled(),"confirmed combined-only firmware disables independent frequency controls");
        command(page,host,"bandApply","band-nr","both","28,78");
        page.setNrCombinedOnly(false);check(nrTarget.getChildAt(1).isEnabled()&&nrTarget.getChildAt(2).isEnabled(),"capability can differ after switching SIM");
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
            Host savedHost=new Host();LockPage compact=new LockPage(scaled,scaledUi,savedHost);compact.setState(state);
            Ui.Segmented compactCell=field(compact,"cellTabs"),compactKind=field(compact,"nrKindTabs");
            for(int widthDp:new int[]{320,360})for(int targetKind=0;targetKind<3;targetKind++){
                compact.fillSaved(new JSONObject().put("schema",1).put("nr",true).put("arfcn",633984).put("pci",202).put("band",78).put("scs",30));
                compactCell.select(targetKind==2?1:0);compactKind.select(targetKind==1?1:0);invoke(compact,"updateCellUi");layout(compact,scaledUi.dp(widthDp));
                Button savedEntry=(Button)texts(compact).stream().filter(t->t.getText().toString().equals("已保存小区 ›")).findFirst().orElseThrow();
                TextView heading=texts(compact).stream().filter(t->t.getText().toString().equals("锁定目标")).findFirst().orElseThrow();
                check(savedEntry.getParent()==heading.getParent()&&savedEntry.getLeft()>=heading.getRight(),"saved entry stays beside the target heading");
                check(savedEntry.getHeight()>=scaledUi.dp(48)&&savedEntry.getWidth()>=savedEntry.getPaint().measureText(savedEntry.getText().toString())+savedEntry.getPaddingLeft()+savedEntry.getPaddingRight(),"saved entry keeps a readable 48dp touch area at "+widthDp+"/"+scale);
                Button apply=field(compact,"cellApply"),clear=field(compact,"cellClear");
                check(clear.getTop()-apply.getBottom()==scaledUi.dp(12),"cell actions keep a consistent 12dp gap");
                if(targetKind==1){View arfcn=(View)UiFlowTest.<View>field(compact,"nrArfcn").getParent(),spacing=(View)UiFlowTest.<View>field(compact,"scs").getParent();check(arfcn.getLeft()==spacing.getLeft()&&arfcn.getWidth()==spacing.getWidth(),"frequency-only fields align after unused columns are hidden");}
                if(targetKind==2){View notice=field(compact,"nrUnknownHint"),card=(View)heading.getParent().getParent();check(notice.getVisibility()==View.GONE&&apply.getTop()-card.getBottom()==scaledUi.dp(12),"hidden NR notice leaves no extra LTE gap");}
                int before=savedHost.savedCalls;savedEntry.performClick();check(savedHost.savedCalls==before+1,"saved entry opens the shared list for each target kind");
                compact.setBusy(true);savedEntry.performClick();check(savedHost.savedCalls==before+1&&!savedEntry.isEnabled(),"saved entry cannot open while a task is running");compact.setBusy(false);
                check(texts(compact).stream().noneMatch(t->t.getText().toString().equals("配置管理")),"configuration navigation does not compete with form actions");
            }
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
                check(labels.stream().anyMatch(t->t.getText().toString().equals("DIAG NR"))&&labels.stream().anyMatch(t->t.getText().toString().equals("RSRQ -12 dB")),"source and quality retained without unassigned SIM suffix");
                check(labels.stream().noneMatch(t->t.getText().toString().matches(".*(SIM 归属未知|连接角色|频段来源|TAC|NCI|Root 电话).*")),"metadata hidden from cards");
                display.invoke(cells,new RadioReader.Observation(List.of(lte,neighbor),"SIM 2",false));
                check(UiFlowTest.<TextView>field(cells,"neighborCount").getText().toString().equals("0 个"),"SIM2 LTE count excludes unassigned NR");
                check(texts(UiFlowTest.<View>field(cells,"neighbors")).stream().noneMatch(t->t.getText().toString().equals("PCI 78")),"unassigned NR cannot appear in current SIM neighbors");
                View other=field(cells,"otherObservations"); Button otherHeading=field(cells,"otherHeading");
                check(otherHeading.getVisibility()==View.VISIBLE&&other.getVisibility()==View.GONE,"other observations collapsed by default");
                otherHeading.performClick();
                check(other.getVisibility()==View.VISIBLE&&texts(other).stream().anyMatch(t->t.getText().toString().equals("PCI 78")),"unassigned NR available after expansion");
                TextView otherNotice=field(cells,"otherNotice");layout(cells,scaledUi.dp(320));
                check(otherNotice.getTop()-otherHeading.getBottom()==scaledUi.dp(12)&&other.getTop()-otherNotice.getBottom()==scaledUi.dp(12),"notice has balanced spacing even when wrapped at scale "+scale);
                check(otherNotice.getLeft()==other.getLeft()&&otherNotice.getRight()==other.getRight(),"notice aligns with observation card");
                otherHeading.performClick();check(otherNotice.getVisibility()==View.GONE&&other.getVisibility()==View.GONE,"collapse removes notice and its margins");otherHeading.performClick();
                check(UiFlowTest.<Map<View,RadioCell>>field(cells,"targetViews").isEmpty(),"unrelated NR cannot fill SIM2 target");
                RadioCell hint=RadioCell.measured(true,false,504990,android.telephony.CellInfo.UNAVAILABLE,android.telephony.CellInfo.UNAVAILABLE,"QMI NAS · 仅频点");
                RadioCell lteIdentity=lte.withIdentity(100),conflict=RadioCell.measured(false,true,1650,111,-92,"QMI NAS").withIdentity(200);
                display.invoke(cells,new RadioReader.Observation(List.of(lteIdentity,conflict,hint,hint.withSource("Root 电话",true)),"SIM 2",false));
                check(UiFlowTest.<TextView>field(cells,"neighborCount").getText().toString().equals("0 个")&&texts(other).stream().anyMatch(t->t.getText().toString().contains("服务记录 · 身份待核对")),"conflicting service not misrepresented as neighbor");
                check(texts(cells).stream().noneMatch(t->t.getText().toString().contains("504990")||t.getText().toString().contains("频点提示")),"incomplete frequency records stay hidden");
                display.invoke(cells,new RadioReader.Observation(List.of(nr,RadioCell.measured(true,false,154570,139,-100,"DIAG NR"),neighbor),"SIM 1",false));
                check(UiFlowTest.<TextView>field(cells,"neighborCount").getText().toString().equals("1 个")&&otherHeading.getVisibility()==View.GONE,"switching to NR removes serving duplicate and retains eligible neighbor");
                check(UiFlowTest.<Map<View,RadioCell>>field(cells,"targetViews").size()==1,"NR candidate stays clickable after grouping");
                display.invoke(cells,new RadioReader.Observation(List.of(nr,RadioCell.measured(true,false,154570,android.telephony.CellInfo.UNAVAILABLE,-100,"QMI NAS · 仅频点")),"SIM 1",false));
                check(UiFlowTest.<TextView>field(cells,"neighborCount").getText().toString().equals("0 个")&&otherHeading.getVisibility()==View.GONE,"incomplete same-frequency record adds no rows");
                var missing=CellsPage.class.getDeclaredMethod("showMissing",String.class);missing.setAccessible(true);missing.invoke(cells,"temporarily unavailable");
                check(otherHeading.getVisibility()==View.GONE&&texts(other).isEmpty(),"missing data clears supplementary display");
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
