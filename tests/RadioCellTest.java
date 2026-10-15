package com.celllock.app;

import android.os.SystemClock;
import android.telephony.CellInfo;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/** 目标资格、部分字段和跨轮缓存的边界；不执行手机或基带操作。 */
public final class RadioCellTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static RadioCell phone(boolean nr, int arfcn, int pci, int band, boolean registered) throws Exception {
        return RadioCell.parse(new JSONObject().put("nr", nr).put("registered", registered).put("arfcn", arfcn)
                .put("pci", pci).put("band", band).put("rsrp", -98).put("timestamp", SystemClock.now), "Root 电话");
    }
    public static void main(String[] args) throws Exception {
        RadioCell serving = phone(true, 154570, CellInfo.UNAVAILABLE, 28, true);
        RadioCell neighbor = RadioCell.measured(true, false, 154570, 139, -100, "DIAG NR");
        check(!neighbor.hasTarget() && neighbor.hasTarget(serving), "same carrier can fill target without mapping or measurement timestamp");
        check(neighbor.timestamp == 0 && !neighbor.mapped, "candidate never gains fabricated provenance");
        check(!serving.hasTarget(serving), "unavailable PCI cannot lock a cell");
        check(neighbor.hasTarget(phone(true, 154570, 1, 41, true)), "correcting wrong service band preserves neighbor eligibility");
        check(!neighbor.hasTarget(phone(true, 504990, 1, 41, true)), "frequency mismatch");
        check(!neighbor.hasTarget(phone(false, 154570, 1, 28, true)), "RAT mismatch");
        check(!neighbor.hasTarget(phone(true, 154570, 1, 28, false)), "neighbor is not a serving reference");
        check(!neighbor.hasTarget(null), "no serving reference");
        RadioCell n77 = phone(true, 640000, 1, 77, true);
        RadioCell overlapping = RadioCell.measured(true, false, 640000, 2, -100, "DIAG NR");
        check(overlapping.band == 0 && overlapping.hasTarget(n77), "ambiguous frequency accepts confirmed same-frequency n77 candidate");
        RadioCell matched = overlapping.withServingBand(n77);
        check(matched.band == 77 && matched.targetBand(n77) == 77 && matched.bandLabel().equals("n77"), "display and target use confirmed serving band");
        check(matched.timestamp == 0 && !matched.mapped && matched.receivedAt == overlapping.receivedAt,
                "band matching does not invent identity or measurement time");
        check(matched.details.get("频段来源").equals("服务频段匹配"), "matched band provenance displayed");
        for (long timestamp : new long[]{SystemClock.now - 60_001, SystemClock.now + 1}) {
            RadioCell stale = RadioCell.parse(n77.json().put("timestamp", timestamp), "Root 电话");
            check(!overlapping.hasTarget(stale), "stale or future service cannot authorize DIAG target");
            check(overlapping.withServingBand(stale).band == 0, "invalid service time cannot resolve band candidates");
            check(!stale.hasTarget(stale) && !stale.hasTarget(n77), "reception time cannot revive an invalid phone target");
        }
        RadioCell boundary = RadioCell.parse(n77.json().put("timestamp", SystemClock.now - 60_000), "Root 电话");
        check(overlapping.hasTarget(boundary), "service measurement remains valid at the 60-second boundary");
        RadioCell nas = RadioCell.measured(true, true, 640000, 1, -98, "QMI NAS");
        check(overlapping.hasTarget(nas) && nas.hasTarget(nas), "untimestamped NAS still uses reception age");
        SystemClock.now += 15_000;
        check(!RadioCell.measured(true, false, 640000, 2, -100, "DIAG NR").hasTarget(nas), "NAS reference expires after 15 seconds");
        SystemClock.now -= 15_000;
        RadioCell reported = RadioCell.parse(overlapping.json().put("band", 78).put("details", new JSONObject().put("频段来源", "系统")), "Root 电话");
        check(!reported.hasTarget(n77) && reported.targetBand(n77) == 78, "explicit reported n78 is not overwritten");
        RadioCell unresolved = phone(true, 640000, 1, 28, true);
        check(unresolved.band == 0 && unresolved.bandLabel().contains("n77 / n78") && overlapping.hasTarget(unresolved),
                "incompatible report becomes candidates and allows manual confirmation");
        check(!RadioCell.measured(true, false, 154570, 65535, -100, "DIAG NR").hasTarget(serving), "native unavailable PCI cannot lock");
        check(!RadioCell.measured(false, false, 70000, 1, -100, "QMI NAS")
                .hasTarget(phone(false, 70000, 2, 1, true)), "backend LTE frequency limit retained");
        RadioCell extended = phone(false, 70000, 1, 66, true);
        check(extended.hasMappedIdentity() && !extended.hasTarget(), "usable measurement and writable target have separate limits");
        check(!serving.hasMappedIdentity(), "partial identity still requires fallback");
        check(neighbor.withDetails(Map.of("RSRQ", "-12 dB")).withSource("缓存", false).details.get("RSRQ").equals("-12 dB"), "copies retain other fields");
        SystemClock.now += 15_000;
        check(!neighbor.hasTarget(phone(true, 154570, 1, 28, true)), "expired candidate disabled even with new reference");
        SystemClock.now -= 15_000;

        JSONObject partial = serving.json().put("identity", 123456L)
                .put("details", new JSONObject().put("TAC", "123").put("MCC", "460").put("MNC", "01")
                        .put("RSRQ", "-12 dB").put("unknown", "do not display"));
        RadioCell kept = RadioCell.parse(partial, "Root 电话");
        check(kept.pci == CellInfo.UNAVAILABLE && kept.arfcn == 154570 && kept.rsrp == -98, "partial root identity keeps frequency and signal");
        check(kept.identity == 123456 && !kept.details.containsKey("unknown") && kept.detailLabel().contains("NCI 123456"), "partial identity keeps NCI and network details");
        check(RadioCell.parse(kept.json(), "Root 电话").details.equals(kept.details), "root JSON round trip");
        partial.put("arfcn", CellInfo.UNAVAILABLE).put("rsrp", CellInfo.UNAVAILABLE);
        kept = RadioCell.parse(partial, "Root 电话");
        check(!kept.hasTarget(serving) && !kept.hasSignal() && kept.details.get("TAC").equals("123"), "missing frequency and signal retain other valid details");
        check(RadioCell.serving(List.of(serving, phone(true, 154570, 139, 28, true))).pci == 139, "prefer completed serving identity");
        check(RadioCell.serving(List.of(serving, RadioCell.measured(false, true, 1650, 111, -90, "QMI NAS"))) == serving,
                "NAS fallback cannot replace partial NR serving data");
        RadioCell lteServing = phone(false, 1650, 111, 3, true);
        check(RadioCell.serving(List.of(RadioCell.measured(true, true, 154570, 139, -100, "QMI NAS"), lteServing)) == lteServing,
                "unmapped NAS NR cannot replace mapped LTE service");
        check(RadioCell.serving(List.of(lteServing, serving)) == serving, "mapped NR still preferred over mapped LTE");
        check(kept.signalLabel().equals("RSRQ -12 dB") && kept.details.containsKey("TAC") && kept.detailLabel().contains("MCC"),
                "neighbor summary hides metadata without deleting original fields");

        RadioCell nr41 = phone(true, 504990, 48, 41, true);
        RadioCell nr41Neighbor = RadioCell.measured(true, false, 504990, 49, -100, "DIAG NR · SIM 归属未知");
        check(nr41Neighbor.isNeighborOf(nr41) && nr41Neighbor.hasTarget(nr41), "same NR carrier retains candidate qualification");
        check(!nr41Neighbor.isNeighborOf(lteServing), "SIM2 LTE cannot claim SIM1 NR observations");
        check(!nr41Neighbor.isNeighborOf(serving), "another NR carrier remains unassigned on dual NR devices");
        check(neighbor.isNeighborOf(serving), "other SIM can independently qualify its NR carrier");
        check(!nr41Neighbor.mapped, "matching never invents SIM ownership");
        RadioCell nasNeighbor = RadioCell.measured(false, false, 1650, 47, -100, "QMI NAS");
        check(nasNeighbor.isNeighborOf(lteServing), "bound NAS neighbor kept");
        RadioCell interFrequency = RadioCell.measured(false, false, 1850, 47, -100, "QMI NAS");
        check(interFrequency.isNeighborOf(lteServing), "bound inter-frequency NAS neighbor kept");
        RadioCell hint = RadioCell.measured(true, false, 504990, CellInfo.UNAVAILABLE, CellInfo.UNAVAILABLE, "QMI NAS · 仅频点");
        check(!hint.isNeighborOf(lteServing) && !hint.isNeighborOf(nr41), "frequency-only hint is never a neighbor");
        RadioCell serviceCopy = RadioCell.measured(true, false, 504990, 48, -100, "DIAG NR");
        check(RadioCell.surrounding(List.of(nr41, serviceCopy, nr41Neighbor, nr41Neighbor), nr41).equals(List.of(nr41Neighbor)),
                "serving DIAG duplicate and repeated neighbor removed");
        RadioCell known = lteServing.withIdentity(100).withDetails(Map.of("TAC", "123"));
        RadioCell conflict = RadioCell.measured(false, true, 1650, 111, -90, "QMI NAS").withIdentity(200).withDetails(Map.of("TAC", "456"));
        check(!conflict.sameCell(known) && !conflict.isNeighborOf(known) && !conflict.hasTarget(known), "conflicting service cannot be merged, classified as neighbor or used as target");
        check(RadioCell.surrounding(List.of(known, conflict), known).equals(List.of(conflict)), "conflicting service retained for inspection");
        RadioCell tacConflict = RadioCell.measured(false, true, 1650, 111, -90, "QMI NAS").withIdentity(100).withDetails(Map.of("TAC", "456"));
        check(!tacConflict.sameCell(known), "matching CI cannot erase TAC conflict");
        RadioCell bandConflict = nr41.withRfBand(90);
        check(!bandConflict.sameCell(nr41), "confirmed overlapping bands remain distinct");
        RadioCell selectedNeighbor = phone(false, 1650, 47, 3, false);
        check(RadioCell.surrounding(List.of(lteServing, nasNeighbor, selectedNeighbor), lteServing).equals(List.of(selectedNeighbor)), "mapped neighbor preferred without duplicate rows");
        RadioCell staleNeighbor = RadioCell.parse(selectedNeighbor.json().put("timestamp", SystemClock.now - 61_000), "Root 电话");
        check(RadioCell.surrounding(List.of(lteServing, staleNeighbor, nasNeighbor), lteServing).equals(List.of(nasNeighbor)), "fresh candidate replaces stale duplicate");

        DiagnosticCells cache = new DiagnosticCells();
        cache.context("1:0", List.of(serving));
        cache.merge(List.of(neighbor, RadioCell.measured(true, false, 154570, 140, -101, "DIAG NR")), SystemClock.now);
        SystemClock.now += 8000;
        cache.merge(List.of(RadioCell.measured(true, false, 154570, 140, -105, "DIAG NR")), SystemClock.now);
        List<RadioCell> combined = cache.values(SystemClock.now);
        check(combined.size() == 2, "combine consecutive rounds");
        check(combined.stream().anyMatch(c -> c.pci == 140 && c.rsrp == -105), "new duplicate updates signal");
        check(combined.stream().anyMatch(c -> c.pci == 139 && c.source.endsWith("8 秒前")), "per-cell reception age");
        check(combined.stream().allMatch(c -> c.timestamp == 0 && !c.mapped), "merging cannot fabricate measurement time or SIM");
        SystemClock.now += 7000;
        check(cache.values(SystemClock.now).size() == 1, "old cell expires at 15 seconds independently");
        cache.context("1:0", List.of(phone(true, 504990, 48, 41, true)));
        check(cache.values(SystemClock.now).isEmpty(), "band change clears cache");
        cache.merge(List.of(neighbor), SystemClock.now);
        cache.context("1:0", List.of(phone(true, 505110, 48, 41, true)));
        check(cache.values(SystemClock.now).isEmpty(), "same-band frequency change clears cache");
        cache.merge(List.of(neighbor), SystemClock.now);
        cache.context("2:1", List.of(serving));
        check(cache.values(SystemClock.now).isEmpty(), "SIM change clears cache");
        cache.merge(List.of(neighbor), SystemClock.now);
        cache.context("2:1", List.of());
        check(cache.values(SystemClock.now).size() == 1, "transient missing service data keeps short cache");
        check(cache.values(SystemClock.now - 1).isEmpty(), "clock rollback clears future observations");
        for (int i = 0; i < 520; i++) cache.merge(List.of(RadioCell.measured(true, false, 154570, i, -100, "DIAG NR")), SystemClock.now);
        check(cache.values(SystemClock.now).size() == 512, "bounded cache");
        check(cache.values(SystemClock.now).stream().noneMatch(c -> c.pci == 0), "overflow discards oldest observations");
        SystemClock.now += 15_000;
        check(cache.values(SystemClock.now).isEmpty(), "all observations expire");
        System.out.println("RadioCell: " + checks + " checks passed");
    }
}
