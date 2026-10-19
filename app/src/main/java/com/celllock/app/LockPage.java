package com.celllock.app;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

final class LockPage extends ScrollView {
    interface Host {
        void onRefresh();
        void onApply(String title, String summary, List<String[]> commands);
        void onMessage(String title, String message);
        default void onSavedCells() {}
    }

    private static final int LTE=0, NR_BOTH=1, NR_SA=2, NR_NSA=3, GSM=4, WCDMA=5;
    private static final int[] SCS={15,30,60,120,240};
    private static final String[] NR_ARGS={"both","sa","nsa"};
    private final Activity activity;
    private final Ui ui;
    private final Host host;
    private final List<View> controls=new ArrayList<>();
    private final List<View> bandChips=new ArrayList<>();
    private final List<Button> presetButtons=new ArrayList<>();
    private final List<String> presetArgs=new ArrayList<>();
    private final List<Integer> presetMasks=new ArrayList<>();
    private final BitSet[] bandDrafts=new BitSet[6];
    private final boolean[] bandEdited=new boolean[6];
    private final LinearLayout[] sections=new LinearLayout[3];
    private Ui.Segmented sectionTabs, cellTabs, nrKindTabs, bandTabs, nrTargetTabs, nrModeTabs;
    private LinearLayout lteFields,nrFields,nrBandColumn,pciColumn,bandChoices,ratChoices;
    private TextView lteCellStatus,nrCellStatus,lteBandStatus,nrBandStatus,ratStatus,stateCaption;
    private TextView nrHint,nrUnknownHint,bandCount,bandHint,ratCurrent,ratHint,nrModeHint;
    private EditText lteArfcn,ltePci,nrArfcn,nrPci,nrBand;
    private Spinner scs;
    private Button refresh,cellApply,cellClear,bandApply,bandAll,bandCurrent,bandNone,ratApply;
    private ModemState state;
    private RadioCell serving;
    private boolean busy,changingFields,ratEdited,nrModeEdited,nrCombinedOnly;
    private String ratArg;
    private int chosenRat=-1,chosenNrMode=-1;

    LockPage(Activity activity, Ui ui, Host host) {
        super(activity);
        this.activity=activity;this.ui=ui;this.host=host;
        setFillViewport(true);setVerticalScrollBarEnabled(false);setClipToPadding(false);
        LinearLayout page=ui.column();page.setPadding(ui.dp(20),ui.dp(20),ui.dp(20),ui.dp(24));
        page.setFocusableInTouchMode(true);addView(page);
        buildState(page);ui.gap(page,17);
        sectionTabs=ui.new Segmented(new String[]{"制式","频段","小区"},index->showSection());
        controls.add(sectionTabs);page.addView(sectionTabs,ui.fill(-2));ui.gap(page,15);
        for(int i=0;i<sections.length;i++){sections[i]=ui.column();page.addView(sections[i],ui.fill(-2));}
        buildRat(sections[0]);buildBands(sections[1]);buildCell(sections[2]);
        setState(null);showSection();
    }

    private void buildState(LinearLayout page) {
        LinearLayout card=ui.card(page),heading=ui.row();heading.addView(ui.title("当前设置"),ui.weight(-2));
        refresh=ui.button("刷新",false);refresh.setTextSize(12);refresh.setOnClickListener(v->{if(!busy)host.onRefresh();});
        controls.add(refresh);heading.addView(refresh,new LinearLayout.LayoutParams(ui.dp(65),ui.dp(35)));card.addView(heading);
        ui.gap(card,9);
        nrCellStatus=statusRow(card,"5G 小区");lteCellStatus=statusRow(card,"LTE 小区");
        ui.gap(card,5);card.addView(ui.divider(),ui.fill(1));ui.gap(card,5);
        nrBandStatus=statusRow(card,"5G 频段");lteBandStatus=statusRow(card,"LTE 频段");ratStatus=statusRow(card,"网络制式");
        ui.gap(card,8);stateCaption=ui.text("首次进入时读取当前 SIM 的设置。",11,Ui.MUTED);card.addView(stateCaption);
    }

    private TextView statusRow(LinearLayout parent,String title) {
        LinearLayout row=ui.row();row.setGravity(Gravity.TOP);row.setPadding(0,ui.dp(5),0,ui.dp(5));
        row.addView(ui.text(title,12,Ui.MUTED),new LinearLayout.LayoutParams(ui.dp(78),-2));
        TextView value=ui.text("未读取",12,Ui.INK);value.setGravity(Gravity.END);row.addView(value,ui.weight(-2));parent.addView(row,ui.fill(-2));return value;
    }

    private void buildCell(LinearLayout parent) {
        cellTabs=ui.new Segmented(new String[]{"5G NR","LTE"},index->updateCellUi());controls.add(cellTabs);parent.addView(cellTabs,ui.fill(-2));ui.gap(parent,13);
        LinearLayout card=ui.card(parent);
        LinearLayout heading=ui.row();heading.addView(ui.title("锁定目标"),ui.weight(-2));
        Button saved=ui.textButton("已保存小区 ›");saved.setTextSize(13);saved.setContentDescription("已保存小区");saved.setOnClickListener(v->{if(!busy)host.onSavedCells();});controls.add(saved);heading.addView(saved,new LinearLayout.LayoutParams(-2,ui.dp(48)));card.addView(heading,ui.fill(-2));ui.gap(card,8);
        lteFields=ui.column();LinearLayout lteRow=ui.row();lteArfcn=ui.labeledInput(lteRow,"EARFCN","0–65535");ui.gap(lteRow,12);ltePci=ui.labeledInput(lteRow,"PCI","0–503");lteFields.addView(lteRow);card.addView(lteFields);
        nrFields=ui.column();nrKindTabs=ui.new Segmented(new String[]{"锁小区 · PCI","锁频点 · ARFCN"},index->updateCellUi());controls.add(nrKindTabs);nrFields.addView(nrKindTabs,ui.fill(-2));ui.gap(nrFields,15);
        LinearLayout nrTop=ui.row();nrArfcn=ui.labeledInput(nrTop,"NR-ARFCN","0–3279165");nrPci=ui.labeledInput(nrTop,"PCI","0–1007");pciColumn=(LinearLayout)nrPci.getParent();((LinearLayout.LayoutParams)pciColumn.getLayoutParams()).leftMargin=ui.dp(12);nrFields.addView(nrTop);ui.gap(nrFields,15);
        LinearLayout nrExtra=ui.row();nrBand=ui.labeledInput(nrExtra,"NR Band","例如 78");nrBandColumn=(LinearLayout)nrBand.getParent();((LinearLayout.LayoutParams)nrBandColumn.getLayoutParams()).rightMargin=ui.dp(12);
        LinearLayout spacing=ui.column();spacing.addView(ui.text("SCS",12,Ui.MUTED));ui.gap(spacing,8);scs=ui.spinner(new String[]{"15 kHz","30 kHz","60 kHz","120 kHz","240 kHz"});scs.setSelection(1);scs.setBackgroundTintList(null);scs.setBackground(ui.shape(Ui.BG,12,0));scs.setContentDescription("SCS（子载波间隔）");spacing.addView(scs,ui.fill(52));nrExtra.addView(spacing,ui.weight(-2));nrFields.addView(nrExtra);ui.gap(nrFields,10);
        nrHint=ui.text("SCS 为子载波间隔。输入频点后会建议频段与 SCS，可手动调整。",11,Ui.MUTED);nrFields.addView(nrHint);card.addView(nrFields);
        nrUnknownHint=ui.text("",11,Ui.AMBER);LinearLayout.LayoutParams notice=ui.fill(-2);notice.topMargin=ui.dp(10);parent.addView(nrUnknownHint,notice);
        for(EditText input:new EditText[]{lteArfcn,ltePci,nrArfcn,nrPci,nrBand}){input.setSaveEnabled(false);controls.add(input);}scs.setSaveEnabled(false);controls.add(scs);
        nrArfcn.addTextChangedListener(watcher(()->suggestNrParameters()));
        nrBand.addTextChangedListener(watcher(()->{if(changingFields)return;int band=InputValidator.parse(value(nrBand),512);if(band>0)selectScs(Bands.defaultScs(band));}));
        ui.gap(parent,12);cellApply=ui.button("锁定",true);cellApply.setOnClickListener(v->applyCell());controls.add(cellApply);parent.addView(cellApply,ui.fill(52));ui.gap(parent,12);
        cellClear=ui.outlineButton("清除解锁");cellClear.setOnClickListener(v->{if(!busy){boolean nr=cellTabs.selected()==0;apply(nr?"清除 5G 小区/频点锁":"清除 LTE 小区锁","只清除所选小区/频点锁；保留频段、网络制式和 5G 组网。",new String[]{"cell-clear",nr?"nr":"lte"});}});controls.add(cellClear);parent.addView(cellClear,ui.fill(48));
    }

    private void buildBands(LinearLayout parent) {
        bandTabs=ui.new Segmented(new String[]{"5G NR","LTE","2G · 3G"},index->renderBandChoices());controls.add(bandTabs);parent.addView(bandTabs,ui.fill(-2));ui.gap(parent,12);
        nrTargetTabs=ui.new Segmented(new String[]{"SA + NSA","仅 SA","仅 NSA"},index->renderBandChoices());controls.add(nrTargetTabs);parent.addView(nrTargetTabs,ui.fill(-2));
        LinearLayout card=ui.card(parent);LinearLayout heading=ui.row();heading.addView(ui.title("选择频段"),ui.weight(-2));bandCount=ui.text("",12,Ui.GREEN);heading.addView(bandCount);card.addView(heading);ui.gap(card,7);
        bandHint=ui.caption("读取后显示本机支持的频段。");card.addView(bandHint);ui.gap(card,12);
        LinearLayout helpers=ui.row();bandAll=smallButton(helpers,"全选",()->editVisibleBands(0));ui.gap(helpers,6);bandCurrent=smallButton(helpers,"仅当前频段",()->editVisibleBands(1));ui.gap(helpers,6);bandNone=smallButton(helpers,"清空",()->editVisibleBands(2));card.addView(helpers);ui.gap(card,15);
        bandChoices=ui.column();card.addView(bandChoices,ui.fill(-2));ui.gap(parent,14);bandApply=ui.button("应用频段",true);bandApply.setOnClickListener(v->applyBands());controls.add(bandApply);parent.addView(bandApply,ui.fill(52));
    }

    private Button smallButton(LinearLayout row,String text,Runnable action) {
        Button button=ui.button(text,false);button.setTextSize(12);button.setPadding(ui.dp(5),0,ui.dp(5),0);button.setOnClickListener(v->{if(!busy)action.run();});row.addView(button,ui.weight(37));controls.add(button);return button;
    }

    private void buildRat(LinearLayout parent) {
        LinearLayout card=ui.card(parent);card.addView(ui.title("优先使用的网络"));ui.gap(card,7);ratCurrent=ui.caption("读取后显示当前制式。");card.addView(ratCurrent);ui.gap(card,13);ratChoices=ui.column();card.addView(ratChoices);
        ratHint=ui.text("",11,Ui.AMBER);ui.gap(card,9);card.addView(ratHint);ui.gap(parent,13);
        LinearLayout modeCard=ui.card(parent);modeCard.addView(ui.title("5G 组网"));ui.gap(modeCard,12);nrModeTabs=ui.new Segmented(new String[]{"SA + NSA","仅 SA","仅 NSA"},index->{chosenNrMode=index==1?2:index==2?1:0;nrModeEdited=true;updateEnabled();});controls.add(nrModeTabs);modeCard.addView(nrModeTabs,ui.fill(-2));ui.gap(modeCard,8);nrModeHint=ui.text("所选制式包含 5G 时可用。",11,Ui.MUTED);modeCard.addView(nrModeHint);ui.gap(parent,15);
        ratApply=ui.button("应用制式",true);ratApply.setOnClickListener(v->applyRat());controls.add(ratApply);parent.addView(ratApply,ui.fill(52));
    }

    void setState(ModemState next) {
        state=next;
        if(next==null){lteCellStatus.setText("未读取");nrCellStatus.setText("未读取");lteBandStatus.setText("未读取");nrBandStatus.setText("未读取");ratStatus.setText("未读取");}
        else {
            lteCellStatus.setText(next.lteCellSummary());nrCellStatus.setText(next.nrCellSummary());
            lteBandStatus.setText(ModemState.bandSummary(next.lte,next.hwLte,"B"));
            String sa=ModemState.bandSummary(next.nrSa,next.hwNr,"n"),nsa=ModemState.bandSummary(next.nrNsa,next.hwNr,"n");
            nrBandStatus.setText(sa.equals(nsa)?sa:"SA "+sa+"\nNSA "+nsa);
            ratStatus.setText(!next.prefKnown?"未知":Rat.describe(next.rat)+((next.rat&Rat.NR)!=0?" · "+Rat.nrMode(next.nrMode):""));
            initializeBandDrafts();
            if(!nrModeEdited)chosenNrMode=next.prefKnown?next.nrMode:-1;
        }
        stateCaption.setText(next==null?"读取当前 SIM 的设置后再选择。":!next.hasCellData()?"暂时无法读取状态，可刷新重试或读取已保存配置。":"设置后会重新读取，确认是否生效。");
        renderBandChoices();renderRatChoices();updateCellUi();updateEnabled();
    }

    void setBusy(boolean value) {busy=value;updateEnabled();}
    void setNrCombinedOnly(boolean value) {
        nrCombinedOnly=value;
        if(value)nrTargetTabs.select(0);
        renderBandChoices();
    }
    void setServingCell(RadioCell cell) {serving=cell;updateEnabled();}

    void fillTarget(RadioCell cell) {
        if(busy||cell==null||!cell.hasTarget(serving))return;
        sectionTabs.select(2);cellTabs.select(cell.nr?0:1);changingFields=true;
        if(cell.nr){nrKindTabs.select(0);nrArfcn.setText(String.valueOf(cell.arfcn));nrPci.setText(String.valueOf(cell.pci));int band=cell.targetBand(serving);nrBand.setText(band>0?String.valueOf(band):"");if(band>0)selectScs(Bands.defaultScs(band));nrHint.setText(band>0?"已填入 n"+band+"，请核对 SCS（子载波间隔）。":"候选 "+Bands.nrCandidateLabel(cell.arfcn)+"，请确认频段与 SCS（子载波间隔）。");}
        else{lteArfcn.setText(String.valueOf(cell.arfcn));ltePci.setText(String.valueOf(cell.pci));}
        if(cell.nr&&!cell.mapped&&cell.targetBand(serving)>0)nrHint.setText("已填入当前 SIM 的同频候选小区，请确认频段与 SCS（子载波间隔）。");
        // shortcut: QMI SCS 尚未验证与锁定命令的对应关系，实测锁定通过后再自动带入。
        if(cell.nr&&cell.details.containsKey("SCS"))nrHint.append(" 当前读到的 SCS 为 "+cell.details.get("SCS")+"，请与填写值核对。");
        changingFields=false;showSection();updateCellUi();smoothScrollTo(0,0);
    }

    void fillSaved(org.json.JSONObject cell) {
        if(busy||!SavedCells.valid(cell))return;
        sectionTabs.select(2);cellTabs.select(cell.optBoolean("nr")?0:1);changingFields=true;
        boolean nr=cell.optBoolean("nr");int arfcn=cell.optInt("arfcn",-1),pci=cell.optInt("pci",-1),band=cell.optInt("band");
        if(nr){nrKindTabs.select(0);nrArfcn.setText(arfcn>=0&&arfcn<=3279165?String.valueOf(arfcn):"");nrPci.setText(pci>=0&&pci<=1007?String.valueOf(pci):"");nrBand.setText(band>0?String.valueOf(band):"");selectScs(cell.optInt("scs")>0?cell.optInt("scs"):Bands.defaultScs(band));nrHint.setText("来自保存小区，请核对历史参数及 SCS（子载波间隔）后锁定。当前未观测到该小区也可尝试。");}
        else{lteArfcn.setText(arfcn>=0&&arfcn<=65535?String.valueOf(arfcn):"");ltePci.setText(pci>=0&&pci<=503?String.valueOf(pci):"");}
        changingFields=false;showSection();updateCellUi();smoothScrollTo(0,0);
    }

    void resetForSubscription() {
        state=null;serving=null;ratArg=null;chosenRat=-1;chosenNrMode=-1;ratEdited=false;nrModeEdited=false;
        for(int i=0;i<bandDrafts.length;i++){bandDrafts[i]=null;bandEdited[i]=false;}
        changingFields=true;for(EditText input:new EditText[]{lteArfcn,ltePci,nrArfcn,nrPci,nrBand})input.setText("");scs.setSelection(1);changingFields=false;nrHint.setText("SCS 为子载波间隔。输入频点后会建议频段与 SCS，可手动调整。");
        nrTargetTabs.select(0);setState(null);
    }

    private void initializeBandDrafts() {
        for(int group=0;group<bandDrafts.length;group++){
            if(!bandEdited[group])bandDrafts[group]=ModemState.bandSelection(currentBands(group),hardwareBands(group));
        }
    }

    private BitSet currentBands(int group) {
        if(state==null||!state.prefKnown)return null;
        switch(group){case LTE:return state.lte;case NR_SA:return state.nrSa;case NR_NSA:return state.nrNsa;case GSM:return state.gsm;case WCDMA:return state.wcdma;default:if(state.nrSa==null||state.nrNsa==null)return null;BitSet common=(BitSet)state.nrSa.clone();common.and(state.nrNsa);return common;}
    }

    private BitSet hardwareBands(int group) {
        if(state==null||!state.hwKnown)return null;
        switch(group){case LTE:return state.hwLte;case GSM:return state.hwGsm;case WCDMA:return state.hwWcdma;default:return state.hwNr;}
    }

    private int nrGroup(){return nrTargetTabs.selected()==1?NR_SA:nrTargetTabs.selected()==2?NR_NSA:NR_BOTH;}
    private int[] visibleGroups(){return bandTabs.selected()==0?new int[]{nrGroup()}:bandTabs.selected()==1?new int[]{LTE}:new int[]{GSM,WCDMA};}
    private boolean present(BitSet set){return set!=null&&!set.isEmpty();}

    private void renderBandChoices() {
        if(bandChoices==null)return;bandChoices.removeAllViews();bandChips.clear();boolean nr=bandTabs.selected()==0;nrTargetTabs.setVisibility(nr?VISIBLE:GONE);
        if(state==null||!state.hwKnown){bandChoices.addView(ui.caption("先刷新，读取硬件支持的频段。"));bandHint.setText("只显示本机硬件支持的频段。");bandCount.setText("");updateEnabled();return;}
        boolean any=false;for(int group:visibleGroups()){
            BitSet hardware=hardwareBands(group);if(!present(hardware))continue;any=true;
            if(group==GSM||group==WCDMA){if(bandChoices.getChildCount()>0)ui.gap(bandChoices,15);bandChoices.addView(ui.title(group==GSM?"GSM · 2G":"WCDMA · 3G"));ui.gap(bandChoices,9);}
            FlowLayout flow=new FlowLayout(activity,ui.dp(8));
            for(int band=hardware.nextSetBit(0);band>=0;band=hardware.nextSetBit(band+1)){
                final int selectedBand=band,selectedGroup=group;String prefix=group==LTE?"B":group==GSM?"":group==WCDMA?"B":"n";
                String hint=group==LTE?Bands.lteHint(band):group==GSM?Bands.gsmHint(band):group==WCDMA?Bands.wcdmaHint(band):Bands.nrHint(band);
                TextView chip=ui.chip(prefix+band,hint);chip.setSelected(bandDrafts[group]!=null&&bandDrafts[group].get(band));chip.setContentDescription((group==GSM?"GSM ":group==WCDMA?"WCDMA ":group==LTE?"LTE ":"5G ")+prefix+band+(hint.isEmpty()?"":"，频率 "+hint));
                chip.setOnClickListener(v->{if(busy)return;if(bandDrafts[selectedGroup]==null)bandDrafts[selectedGroup]=new BitSet();bandDrafts[selectedGroup].flip(selectedBand);bandEdited[selectedGroup]=true;chip.setSelected(bandDrafts[selectedGroup].get(selectedBand));updateBandCount();updateEnabled();});
                flow.addView(chip);bandChips.add(chip);
            }
            bandChoices.addView(flow,ui.fill(-2));
        }
        if(!any)bandChoices.addView(ui.caption("硬件未提供此制式的可选频段。"));
        boolean different=nr&&nrTargetTabs.selected()==0&&state.prefKnown&&state.nrSa!=null&&state.nrNsa!=null&&!state.nrSa.equals(state.nrNsa);
        bandHint.setText(nr&&nrCombinedOnly?"SA＋NSA 同步设置：本机不支持独立设置 SA 或 NSA 频段。":different?"SA 与 NSA 当前不同；默认显示共有的受限频段，可分别切换查看。":"未限制频段时默认不选；已限制时勾选对应频段。");updateBandCount();updateEnabled();
    }

    private void updateBandCount() {int chosen=0,total=0;for(int group:visibleGroups()){BitSet hw=hardwareBands(group);if(hw!=null){total+=hw.cardinality();if(bandDrafts[group]!=null){BitSet selected=(BitSet)bandDrafts[group].clone();selected.and(hw);chosen+=selected.cardinality();}}}bandCount.setText(total==0?"":"已选 "+chosen+" / "+total);}

    private void editVisibleBands(int action) {
        if(busy)return;
        if(action==1){int group=bandTabs.selected()==0?nrGroup():LTE;if(serving==null||serving.band<=0||bandTabs.selected()==2||serving.nr!=(bandTabs.selected()==0)||!present(hardwareBands(group))||!hardwareBands(group).get(serving.band)){host.onMessage("当前频段不可用","小区页尚未读到此制式的受支持服务频段。");return;}bandDrafts[group]=new BitSet();bandDrafts[group].set(serving.band);bandEdited[group]=true;}
        else for(int group:visibleGroups()){BitSet hardware=hardwareBands(group);if(hardware==null)continue;bandDrafts[group]=action==0?(BitSet)hardware.clone():new BitSet();bandEdited[group]=true;}
        renderBandChoices();
    }

    private void applyBands() {
        if(busy)return;List<String[]> commands=new ArrayList<>();StringBuilder summary=new StringBuilder();
        for(int group:visibleGroups()){
            BitSet hardware=hardwareBands(group);if(!present(hardware))continue;BitSet selected=bandDrafts[group]==null?new BitSet():(BitSet)bandDrafts[group].clone();selected.and(hardware);
            String name=group==LTE?"LTE":group==GSM?"GSM":group==WCDMA?"WCDMA":"5G "+(nrTargetTabs.selected()==0?"SA + NSA":nrTargetTabs.selected()==1?"SA":"NSA");
            if(selected.isEmpty()){host.onMessage("选择频段",name+" 至少选择一个频段。");return;}
            if(group==LTE)commands.add(new String[]{"band-lte",ModemState.arg(selected)});else if(group==GSM)commands.add(new String[]{"band-gsm",ModemState.arg(selected)});else if(group==WCDMA)commands.add(new String[]{"band-wcdma",ModemState.arg(selected)});else commands.add(new String[]{"band-nr",NR_ARGS[nrTargetTabs.selected()],ModemState.arg(selected)});
            if(summary.length()>0)summary.append('\n');summary.append(name).append("：").append(ModemState.join(selected,group==GSM?"":group>=NR_BOTH&&group<=NR_NSA?"n":"B"));
        }
        if(commands.isEmpty()){host.onMessage("暂无可选频段","请先刷新硬件支持信息。");return;}host.onApply("应用频段",summary.toString(),commands);
    }

    private int hardwareRat() {
        if(state==null||!state.hwKnown)return 0;return (present(state.hwGsm)?Rat.GSM:0)|(present(state.hwWcdma)?Rat.WCDMA:0)|(present(state.hwLte)?Rat.LTE:0)|(present(state.hwNr)?Rat.NR:0);
    }

    private void renderRatChoices() {
        if(ratChoices==null)return;ratChoices.removeAllViews();presetButtons.clear();presetArgs.clear();presetMasks.clear();int hardware=hardwareRat();
        if(hardware==0){ratChoices.addView(ui.caption("先刷新，读取可用网络制式。"));ratCurrent.setText("当前制式未读取");updateEnabled();return;}
        addPreset("自动 · 全部",hardware,"all");addPresetIfSupported("4G / 5G",Rat.LTE|Rat.NR,hardware);addPresetIfSupported("仅 5G",Rat.NR,hardware);addPresetIfSupported("仅 4G",Rat.LTE,hardware);addPresetIfSupported("2G / 3G / 4G",Rat.GSM|Rat.WCDMA|Rat.LTE,hardware);addPresetIfSupported("仅 3G",Rat.WCDMA,hardware);addPresetIfSupported("仅 2G",Rat.GSM,hardware);
        if(!ratEdited){ratArg=null;chosenRat=-1;if(state.prefKnown){for(int i=0;i<presetMasks.size();i++)if(presetMasks.get(i)==state.rat){ratArg=presetArgs.get(i);chosenRat=state.rat;break;}}}
        boolean custom=state.prefKnown;for(int mask:presetMasks)if(state.prefKnown&&mask==state.rat){custom=false;break;}
        ratCurrent.setText(!state.prefKnown?"当前制式未知":"当前："+(custom?"自定义 · ":"")+Rat.describe(state.rat));renderRatSelection();
    }

    private void addPresetIfSupported(String label,int mask,int hardware){if((hardware&mask)==mask)addPreset(label,mask,Rat.arg(mask));}
    private void addPreset(String label,int mask,String arg) {
        Button choice=ui.button(label,false);choice.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);choice.setPadding(ui.dp(14),0,ui.dp(14),0);choice.setTag(label);
        choice.setOnClickListener(v->{if(busy)return;ratArg=arg;chosenRat=mask;ratEdited=true;renderRatSelection();});
        if(!presetButtons.isEmpty())ui.gap(ratChoices,7);ratChoices.addView(choice,ui.fill(45));presetButtons.add(choice);presetArgs.add(arg);presetMasks.add(mask);
    }

    private void renderRatSelection() {
        for(int i=0;i<presetButtons.size();i++){Button choice=presetButtons.get(i);boolean selected=presetArgs.get(i).equals(ratArg);choice.setText((selected?"✓   ":"")+choice.getTag());choice.setBackground(ui.ripple(selected?Ui.SOFT_GREEN:Ui.BG,12));choice.setTextColor(selected?Ui.GREEN:Ui.INK);}
        ratHint.setText(chosenRat==Rat.NR?"仅 5G 时，未开通 VoNR 可能无法通话。":"");ratHint.setVisibility(chosenRat==Rat.NR?VISIBLE:GONE);
        nrModeTabs.select(chosenNrMode==0?0:chosenNrMode==2?1:chosenNrMode==1?2:-1);updateEnabled();
    }

    private void applyRat() {
        if(busy)return;List<String[]> commands=new ArrayList<>();int mask=chosenRat>=0?chosenRat:state!=null&&state.prefKnown?state.rat:0;
        if(ratArg!=null)commands.add(new String[]{"rat",ratArg});
        if((mask&Rat.NR)!=0){if(chosenNrMode<0){host.onMessage("选择 5G 组网","请选择 SA + NSA、仅 SA 或仅 NSA。");return;}commands.add(new String[]{"nr-mode",chosenNrMode==0?"both":chosenNrMode==1?"nsa":"sa"});}
        if(commands.isEmpty()){host.onMessage("选择网络制式","请先选择一个制式预设。");return;}
        String summary=(ratArg==null?"保持当前制式":"all".equals(ratArg)?"自动使用硬件支持的全部制式":Rat.describe(mask))+((mask&Rat.NR)!=0?"\n5G 组网："+Rat.nrMode(chosenNrMode):"");
        if(mask==Rat.NR)summary+="\n未开通 VoNR 时可能无法通话。";host.onApply("应用制式",summary,commands);
    }

    private void applyCell() {
        if(busy)return;boolean nr=cellTabs.selected()==0,freq=nr&&nrKindTabs.selected()==1;int spacing=SCS[Math.max(0,scs.getSelectedItemPosition())];
        String error=nr?(freq?InputValidator.nrFrequency(value(nrArfcn),spacing):InputValidator.nrCell(value(nrArfcn),value(nrPci),value(nrBand),spacing)):InputValidator.lteCell(value(lteArfcn),value(ltePci));
        if(error!=null){host.onMessage("检查目标参数",error);return;}
        if(!nr)apply("锁定 LTE 小区","EARFCN "+value(lteArfcn)+" / PCI "+value(ltePci),new String[]{"cell-lte",value(lteArfcn),value(ltePci)});
        else if(freq)apply("锁定 5G 频点","NR-ARFCN "+value(nrArfcn)+" / "+spacing+" kHz",new String[]{"freq-nr",value(nrArfcn),String.valueOf(spacing)});
        else apply("锁定 5G 小区","NR-ARFCN "+value(nrArfcn)+" / PCI "+value(nrPci)+"\nn"+value(nrBand)+" / "+spacing+" kHz",new String[]{"cell-nr",value(nrArfcn),value(nrPci),value(nrBand),String.valueOf(spacing)});
    }

    private void apply(String title,String summary,String[] command){if(busy)return;List<String[]> commands=new ArrayList<>();commands.add(command);host.onApply(title,summary,commands);}
    private String value(EditText input){return input.getText().toString().trim();}
    private TextWatcher watcher(Runnable action){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){}public void afterTextChanged(Editable text){if(!changingFields)action.run();}};}
    private void suggestNrParameters() {
        if(changingFields)return;int arfcn=InputValidator.parse(value(nrArfcn),InputValidator.MAX_NR_ARFCN);if(arfcn<0)return;
        int band=Bands.uniqueNrBand(arfcn);changingFields=true;nrBand.setText(band>0?String.valueOf(band):"");if(band>0)selectScs(Bands.defaultScs(band));changingFields=false;
        String candidates=Bands.nrCandidateLabel(arfcn);
        nrHint.setText(band>0?"建议 n"+band+" · SCS "+Bands.defaultScs(band)+" kHz，可调整。":candidates.isEmpty()?"未识别频段，请手动确认频段与 SCS（子载波间隔）。":"候选 "+candidates+"，请手动确认频段与 SCS（子载波间隔）。");
    }
    private void selectScs(int value){for(int i=0;i<SCS.length;i++)if(SCS[i]==value){scs.setSelection(i);return;}}

    private void showSection(){for(int i=0;i<sections.length;i++)sections[i].setVisibility(sectionTabs.selected()==i?VISIBLE:GONE);updateEnabled();}
    private void updateCellUi() {
        boolean nr=cellTabs.selected()==0,freq=nrKindTabs.selected()==1;lteFields.setVisibility(nr?GONE:VISIBLE);nrFields.setVisibility(nr?VISIBLE:GONE);pciColumn.setVisibility(freq?GONE:VISIBLE);nrBandColumn.setVisibility(freq?GONE:VISIBLE);
        cellClear.setText(nr?"清除 5G 小区/频点锁":"清除 LTE 小区锁");
        nrUnknownHint.setVisibility(nr&&state!=null&&!state.nrKnown?VISIBLE:GONE);nrUnknownHint.setText("暂未读到完整的 5G 锁定状态，设置后会再次读取核对。");updateEnabled();
    }
    private void updateEnabled() {
        if(cellApply==null||bandApply==null||ratApply==null)return;
        for(View control:controls)Ui.enable(control,!busy);for(View chip:bandChips)Ui.enable(chip,!busy);for(Button preset:presetButtons)Ui.enable(preset,!busy);
        if(nrCombinedOnly){Ui.enable(nrTargetTabs.getChildAt(1),false);Ui.enable(nrTargetTabs.getChildAt(2),false);}
        refresh.setText(busy?"处理中":"刷新");boolean available=false,hasSelection=false;for(int group:visibleGroups()){available|=present(hardwareBands(group));hasSelection|=present(bandDrafts[group]);}
        Ui.enable(bandAll,!busy&&available);Ui.enable(bandNone,!busy&&available);Ui.enable(bandApply,!busy&&available&&hasSelection);
        int group=bandTabs.selected()==0?nrGroup():LTE;boolean current=bandTabs.selected()!=2&&serving!=null&&serving.band>0&&serving.nr==(bandTabs.selected()==0)&&present(hardwareBands(group))&&hardwareBands(group).get(serving.band);Ui.enable(bandCurrent,!busy&&current);
        int rat=chosenRat>=0?chosenRat:state!=null&&state.prefKnown?state.rat:0;boolean nr=(rat&Rat.NR)!=0;Ui.enable(nrModeTabs,!busy&&nr&&((hardwareRat()&Rat.NR)!=0));nrModeHint.setText(nr?chosenNrMode<0?"当前组网未知，请选择。":"应用制式时同时使用此组网设置。":"所选制式包含 5G 时可用。");
        Ui.enable(ratApply,!busy&&hardwareRat()!=0&&(ratArg!=null||(nr&&nrModeEdited)));
    }

    void save(Bundle out) {
        out.putInt("lock.section",sectionTabs.selected());out.putInt("lock.cell",cellTabs.selected());out.putInt("lock.nrKind",nrKindTabs.selected());out.putInt("lock.bands",bandTabs.selected());out.putInt("lock.nrTarget",nrTargetTabs.selected());
        out.putString("lock.lteArfcn",value(lteArfcn));out.putString("lock.ltePci",value(ltePci));out.putString("lock.nrArfcn",value(nrArfcn));out.putString("lock.nrPci",value(nrPci));out.putString("lock.nrBand",value(nrBand));out.putInt("lock.scs",scs.getSelectedItemPosition());
        out.putString("lock.ratArg",ratArg);out.putInt("lock.rat",chosenRat);out.putInt("lock.nrMode",chosenNrMode);out.putBoolean("lock.ratEdited",ratEdited);out.putBoolean("lock.nrModeEdited",nrModeEdited);out.putBooleanArray("lock.bandEdited",bandEdited.clone());
        for(int i=0;i<bandDrafts.length;i++)if(bandDrafts[i]!=null)out.putLongArray("lock.bandDraft"+i,bandDrafts[i].toLongArray());out.putInt("lock.scroll",getScrollY());
    }

    void restore(Bundle in) {
        if(in==null)return;changingFields=true;sectionTabs.select(bounded(in.getInt("lock.section"),2));cellTabs.select(bounded(in.getInt("lock.cell"),1));nrKindTabs.select(bounded(in.getInt("lock.nrKind"),1));bandTabs.select(bounded(in.getInt("lock.bands"),2));nrTargetTabs.select(bounded(in.getInt("lock.nrTarget"),2));
        lteArfcn.setText(in.getString("lock.lteArfcn",""));ltePci.setText(in.getString("lock.ltePci",""));nrArfcn.setText(in.getString("lock.nrArfcn",""));nrPci.setText(in.getString("lock.nrPci",""));nrBand.setText(in.getString("lock.nrBand",""));scs.setSelection(bounded(in.getInt("lock.scs",1),4));changingFields=false;
        ratArg=in.getString("lock.ratArg");chosenRat=in.getInt("lock.rat",-1);chosenNrMode=in.getInt("lock.nrMode",-1);ratEdited=in.getBoolean("lock.ratEdited");nrModeEdited=in.getBoolean("lock.nrModeEdited");boolean[] edited=in.getBooleanArray("lock.bandEdited");
        for(int i=0;i<bandDrafts.length;i++){long[] bits=in.getLongArray("lock.bandDraft"+i);bandDrafts[i]=bits==null?null:BitSet.valueOf(bits);bandEdited[i]=edited!=null&&i<edited.length&&edited[i];}
        initializeBandDrafts();showSection();renderBandChoices();renderRatChoices();updateCellUi();post(()->scrollTo(0,in.getInt("lock.scroll",0)));
    }
    private int bounded(int value,int max){return Math.max(0,Math.min(max,value));}
}
