package com.celllock.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** 只合并短窗口内的诊断观测；接收时间不冒充基带测量时间。 */
final class DiagnosticCells {
    static final long WINDOW_MS = 15_000;
    private final LinkedHashMap<String, RadioCell> cells = new LinkedHashMap<>();
    private String simKey;
    private Set<String> carriers = new TreeSet<>();

    void context(String sim, List<RadioCell> current) {
        Set<String> next = new TreeSet<>();
        for (RadioCell cell : current) if (cell.registered && cell.band > 0 && cell.arfcn != Integer.MAX_VALUE)
            next.add((cell.nr ? "NR:" : "LTE:") + cell.band + ":" + cell.arfcn);
        if (!sim.equals(simKey) || !next.isEmpty() && !carriers.isEmpty() && !next.equals(carriers)) clear();
        simKey = sim;
        if (!next.isEmpty()) carriers = next;
    }

    void clear() { cells.clear(); carriers.clear(); }

    void merge(List<RadioCell> batch, long now) {
        trim(now);
        for (RadioCell cell : batch) {
            cells.remove(cell.key());
            cells.put(cell.key(), cell.withReceivedAt(now));
        }
        while (cells.size() > 512) cells.remove(cells.keySet().iterator().next());
    }

    List<RadioCell> values(long now) {
        trim(now);
        List<RadioCell> result = new ArrayList<>();
        for (RadioCell cell : cells.values())
            result.add(cell.withSource(cell.source + " · 接收 " + (now - cell.receivedAt) / 1000 + " 秒前", false));
        return result;
    }

    private void trim(long now) {
        cells.values().removeIf(cell -> cell.receivedAt <= 0 || now < cell.receivedAt || now - cell.receivedAt >= WINDOW_MS);
    }
}
