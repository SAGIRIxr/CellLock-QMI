package com.celllock.app;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import android.telephony.CellInfo;

/** Entry selection and supplemental measurements; no Android services or device access. */
public final class ReadingPolicyTest {
    public static void main(String[] args) {
        int[][] input = {{-1,0},{1,0},{1,1},{1,2},{0,0},{0,2}};
        int[][] expected = {{1,0},{1},{1},{1,0},{0},{0,1}};
        for (int i=0; i<input.length; i++)
            if (!Arrays.equals(RadioReader.diagnosticCandidates(input[i][0],input[i][1]), expected[i]))
                throw new AssertionError("processor selection " + i);
        RadioCell phone = RadioCell.measured(false, true, 1650, 111, -90, "Root 电话").withIdentity(123).withSource("Root 电话", true);
        List<RadioCell> cells = new ArrayList<>(List.of(phone));
        RadioCell same = RadioCell.measured(false, true, 1650, 111, -95, "QMI NAS").withIdentity(123);
        RadioCell otherBand = RadioCell.measured(false, false, 3740, 4, -100, "QMI NAS");
        RadioReader.supplementReadings(cells, List.of(same, otherBand));
        if(cells.size()!=2 || cells.get(0)!=phone || cells.get(1)!=otherBand)
            throw new AssertionError("complete phone service must retain primary data and gain NAS inter-band neighbor");
        RadioReader.supplementReadings(cells, List.of(otherBand, RadioCell.measured(false, false, 1650, CellInfo.UNAVAILABLE, CellInfo.UNAVAILABLE, "QMI NAS")));
        if(cells.size()!=2)throw new AssertionError("duplicate cells or frequency-only hints must not add duplicate rows");
        RadioCell conflict = same.withIdentity(456);
        RadioReader.supplementReadings(cells, List.of(conflict));
        if(!cells.contains(conflict))throw new AssertionError("different known cell identity must not be collapsed");
        RadioCell unknown = RadioCell.measured(true, false, CellInfo.UNAVAILABLE, CellInfo.UNAVAILABLE, -105, "QMI NAS");
        RadioReader.supplementReadings(cells, List.of(unknown));
        if(!cells.contains(unknown))throw new AssertionError("partial supplemental signal must be preserved");
        RadioCell partial = RadioCell.measured(false, false, 1650, CellInfo.UNAVAILABLE, -105, "QMI NAS");
        RadioReader.supplementReadings(cells, List.of(partial));
        if(!cells.contains(partial))throw new AssertionError("unavailable PCI must not discard a valid signal on a known frequency");
        List<RadioCell> empty = new ArrayList<>();
        RadioReader.supplementReadings(empty, List.of(same, otherBand));
        if(empty.size()!=2 || empty.stream().anyMatch(c -> c.mapped || c.timestamp>0))
            throw new AssertionError("NAS-only fallback must preserve readings without invented mapping or timestamps");
        System.out.println("ReadingPolicy: " + (input.length+6) + " checks passed");
    }
}
