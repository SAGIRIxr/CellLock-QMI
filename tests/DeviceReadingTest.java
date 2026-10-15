package com.celllock.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.telephony.CellInfo;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 显式运行的真机只读验收；使用实际 App UID、权限、Root 和读取协调器，不修改网络设置。 */
public final class DeviceReadingTest extends Instrumentation {
    private int checks;
    private void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); RadioReader[] holder = new RadioReader[1];
        try {
            runOnMainSync(() -> holder[0] = new RadioReader(getTargetContext()));
            RadioReader reader = holder[0];
            CountDownLatch loaded = new CountDownLatch(1);
            @SuppressWarnings("unchecked") List<SimInfo>[] sims = new List[1];
            runOnMainSync(() -> reader.loadSims(value -> { sims[0] = value; loaded.countDown(); }));
            check(loaded.await(20, TimeUnit.SECONDS), "SIM loading timeout");
            check(RootShell.available(), "Root unavailable");
            check(sims[0] != null && !sims[0].isEmpty(), "no usable SIM");
            Method nativeRead = RadioReader.class.getDeclaredMethod("nativeRead", String.class, String.class, int.class);
            nativeRead.setAccessible(true);
            StringBuilder summary = new StringBuilder(); int tested = 0;
            for (SimInfo sim : sims[0]) {
                if (!sim.trusted()) continue;
                RadioReader.verifySlot(getTargetContext(), sim.subscriptionId, sim.slot);
                String raw = (String)nativeRead.invoke(reader, "libcelllock_read.so", "cells " + sim.slot, 8);
                List<org.json.JSONObject> replies = MeasurementDecoder.records(raw);
                check(replies.size() == 6 && replies.stream().allMatch(MeasurementDecoder::success), "QMI query failure SIM " + (sim.slot+1));
                RadioCell qmi = RadioCell.serving(MeasurementDecoder.nas(raw, sim.slot));
                check(qmi != null && qmi.details.containsKey("射频频段"), "no verified RF service SIM " + (sim.slot+1));
                check(qmi.arfcn != CellInfo.UNAVAILABLE && qmi.pci != CellInfo.UNAVAILABLE, "incomplete service identity");
                CountDownLatch read = new CountDownLatch(1);
                RadioReader.Observation[] observation = new RadioReader.Observation[1];
                runOnMainSync(() -> reader.read(sim, value -> { observation[0] = value; read.countDown(); }));
                check(read.await(35, TimeUnit.SECONDS), "App reading timeout");
                check(!observation[0].mappingChanged && !observation[0].cells.isEmpty(), "SIM mapping changed or no App cells");
                RadioCell serving = RadioCell.serving(observation[0].cells);
                check(serving != null, "no App service");
                check(serving.nr == qmi.nr && serving.arfcn == qmi.arfcn && serving.pci == qmi.pci, "App/QMI carrier disagreement");
                List<RadioCell> surrounding = RadioCell.surrounding(observation[0].cells, serving);
                if (serving.sameCell(qmi)) check(serving.band == qmi.band && serving.details.containsKey("射频频段"), "QMI band not integrated into matching App service");
                else check(surrounding.stream().anyMatch(c -> c.registered && c.sameCell(qmi)
                        && !c.isNeighborOf(serving) && !c.hasTarget(serving)), "conflicting QMI service must remain separate and unselectable");
                Throwable[] failure = {null};
                runOnMainSync(() -> {
                    CellsPage page = null;
                    try {
                        Activity activity = new UiFlowTest.TestActivity(getTargetContext());
                        RadioCell[] selected = {null};
                        page = new CellsPage(activity, new Ui(activity), reader, new CellsPage.Host() {
                            public void onTarget(RadioCell cell) { selected[0] = cell; }
                            public void onNeedPermission() { throw new AssertionError("unexpected permission request"); }
                            public void onServingChanged(RadioCell cell) {}
                            public void onSimChanged() {}
                        });
                        Method display = CellsPage.class.getDeclaredMethod("display", RadioReader.Observation.class);
                        display.setAccessible(true); display.invoke(page, observation[0]);
                        long neighbors = surrounding.stream().filter(c -> c.isNeighborOf(serving)).count();
                        check(UiFlowTest.<TextView>field(page, "neighborCount").getText().toString().equals(neighbors + " 个"), "actual UI neighbor count includes unrelated cells");
                        View other = UiFlowTest.field(page, "otherObservations");
                        check(other.getVisibility() == View.GONE, "actual other observations must start collapsed");
                        if (surrounding.stream().anyMatch(c -> RadioCell.validIdentity(c.nr, c.arfcn, c.pci) && !c.isNeighborOf(serving))) {
                            UiFlowTest.<Button>field(page, "otherHeading").performClick();
                            check(other.getVisibility() == View.VISIBLE, "actual other observations cannot expand");
                        }
                        Map<View,RadioCell> targets = UiFlowTest.field(page, "targetViews");
                        check(targets.values().stream().allMatch(c -> c.hasTarget(serving)), "actual UI exposes invalid target");
                        for (var entry : targets.entrySet()) {
                            selected[0] = null; entry.getKey().performClick();
                            check(selected[0] == entry.getValue(), "actual eligible neighbor click failed");
                        }
                    } catch (Throwable error) { failure[0] = error; }
                    finally { if (page != null) page.dispose(); }
                });
                if (failure[0] != null) throw new AssertionError(failure[0]);
                long diagTargets = observation[0].cells.stream().filter(c -> c.source.startsWith("DIAG") && c.hasTarget(serving)).count();
                summary.append("SIM ").append(sim.slot+1).append(": RAT="+(serving.nr?"NR":"LTE")+" ARFCN="+serving.arfcn+" PCI="+serving.pci
                        +" band="+serving.bandLabel()+" RSRP="+serving.rsrp+" SCS="+serving.details.get("SCS")
                        +" cells="+observation[0].cells.size()+" DIAG-targets="+diagTargets
                        +" neighbors="+surrounding.stream().filter(c -> c.isNeighborOf(serving)).count()
                        +" service-identity-agreement="+serving.sameCell(qmi)).append('\n');
                tested++;
            }
            check(tested > 0, "no trusted SIM was tested");
            result.putString("stream", summary+"PASS: "+checks+" actual App/QMI/DIAG reading checks; no network writes\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", android.util.Log.getStackTraceString(error)); finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (holder[0] != null) runOnMainSync(holder[0]::close);
        }
    }
}
