package com.celllock.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.telephony.CellInfo;
import java.lang.reflect.Method;
import java.util.List;
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
                check(serving.nr == qmi.nr && serving.arfcn == qmi.arfcn && serving.pci == qmi.pci
                        && (serving.identity < 0 || qmi.identity < 0 || serving.identity == qmi.identity), "App/QMI identity disagreement");
                check(serving.band == qmi.band && serving.details.containsKey("射频频段"), "QMI band not integrated into App");
                long diagTargets = observation[0].cells.stream().filter(c -> c.source.startsWith("DIAG") && c.hasTarget(serving)).count();
                summary.append("SIM ").append(sim.slot+1).append(": RAT="+(serving.nr?"NR":"LTE")+" ARFCN="+serving.arfcn+" PCI="+serving.pci
                        +" band="+serving.bandLabel()+" RSRP="+serving.rsrp+" SCS="+serving.details.get("SCS")
                        +" cells="+observation[0].cells.size()+" DIAG-targets="+diagTargets).append('\n');
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
