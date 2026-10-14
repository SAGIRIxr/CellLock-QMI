package com.celllock.app;

import java.util.BitSet;

/** 频段号、频率提示，以及由频点反推频段。频点范围取自 3GPP 38.101 / 36.101。 */
final class Bands {
    private Bands() {}

    static String lteHint(int band) {
        switch (band) {
            case 1: return "2100";
            case 2: case 25: return "1900";
            case 3: return "1800";
            case 4: case 66: return "AWS";
            case 5: case 18: case 19: case 26: return "850";
            case 7: case 38: return "2600";
            case 8: return "900";
            case 12: case 13: case 14: case 17: case 28: return "700";
            case 20: return "800";
            case 34: return "2000";
            case 39: return "1900";
            case 40: return "2300";
            case 41: return "2500";
            case 42: return "3500";
            case 43: return "3700";
            case 48: return "3600";
            case 71: return "600";
            default: return "";
        }
    }

    static String nrHint(int band) {
        switch (band) {
            case 1: return "2100";
            case 2: case 25: return "1900";
            case 3: return "1800";
            case 5: return "850";
            case 7: case 38: return "2600";
            case 8: return "900";
            case 20: return "800";
            case 28: return "700";
            case 40: return "2300";
            case 41: return "2.6G";
            case 48: return "3.6G";
            case 66: return "AWS";
            case 71: return "600";
            case 77: return "3.7G";
            case 78: return "3.5G";
            case 79: return "4.9G";
            case 257: case 261: return "28G";
            case 258: return "26G";
            case 260: return "39G";
            default: return "";
        }
    }

    static String gsmHint(int mhz) {
        return mhz + "";
    }

    static String wcdmaHint(int band) {
        switch (band) {
            case 1: return "2100";
            case 2: return "1900";
            case 4: return "AWS";
            case 5: case 6: case 19: return "850";
            case 8: return "900";
            default: return "";
        }
    }

    /** {频段, 下行频点起, 止}，重叠时保留全部候选。 */
    private static final int[][] NR_RANGES = {
            {78, 620000, 653333}, {41, 499200, 537999}, {79, 693334, 733333}, {28, 151600, 160600},
            {1, 422000, 434000}, {3, 361000, 376000}, {5, 173800, 178800}, {8, 185000, 192000},
            {77, 620000, 680000}, {40, 460000, 480000}, {38, 514000, 524000}, {39, 376000, 384000},
            {34, 402000, 405000}, {7, 524000, 538000}, {20, 158200, 164200}, {2, 386000, 398000},
            {25, 386000, 399000}, {66, 422000, 440000}, {65, 422000, 440000}, {48, 636667, 646666},
            {12, 145800, 149200}, {14, 151600, 153600}, {18, 172000, 175000}, {26, 171800, 178800},
            {71, 123400, 130400}, {70, 399000, 404000}, {50, 286400, 303400}, {74, 295000, 303600},
            {75, 286400, 303400}, {76, 285400, 286400}, {90, 499200, 538000}, {46, 743334, 795000},
            {96, 795000, 875000}, {257, 2054166, 2104165}, {258, 2016667, 2070832},
            {260, 2229166, 2279165}, {261, 2070833, 2084999},
    };

    private static final int[][] LTE_RANGES = {
            {1, 0, 599}, {2, 600, 1199}, {3, 1200, 1949}, {4, 1950, 2399}, {5, 2400, 2649},
            {7, 2750, 3449}, {8, 3450, 3799}, {12, 5010, 5179}, {13, 5180, 5279}, {14, 5280, 5379},
            {17, 5730, 5849}, {18, 5850, 5999}, {19, 6000, 6149}, {20, 6150, 6449}, {21, 6450, 6599},
            {25, 8040, 8689}, {26, 8690, 9039}, {28, 9210, 9659}, {34, 36200, 36349},
            {38, 37750, 38249}, {39, 38250, 38649}, {40, 38650, 39649}, {41, 39650, 41589},
            {42, 41590, 43589}, {43, 43590, 45589}, {46, 46790, 54539}, {48, 55240, 56739},
            {66, 66436, 67335}, {71, 68586, 68935},
    };

    static BitSet nrCandidates(long arfcn) {
        BitSet result = new BitSet();
        for (int[] range : NR_RANGES) if (arfcn >= range[1] && arfcn <= range[2]) result.set(range[0]);
        return result;
    }

    static boolean knownNrBand(int band) {
        for (int[] range : NR_RANGES) if (range[0] == band) return true;
        return false;
    }

    static int uniqueNrBand(long arfcn) {
        BitSet candidates = nrCandidates(arfcn);
        return candidates.cardinality() == 1 ? candidates.nextSetBit(0) : 0;
    }

    static String nrCandidateLabel(long arfcn) {
        BitSet candidates = nrCandidates(arfcn);
        StringBuilder label = new StringBuilder();
        for (int band = candidates.nextSetBit(0); band >= 0; band = candidates.nextSetBit(band + 1)) {
            if (label.length() > 0) label.append(" / ");
            label.append('n').append(band);
        }
        return label.toString();
    }

    static int lteBandForEarfcn(long earfcn) {
        for (int[] r : LTE_RANGES) if (earfcn >= r[1] && earfcn <= r[2]) return r[0];
        return 0;
    }

    /** SSB 子载波间隔的常见取值：TDD 中高频 30 kHz，FDD 低频 15 kHz，毫米波 120 kHz。 */
    static int defaultScs(int nrBand) {
        if (nrBand >= 257) return 120;
        switch (nrBand) {
            case 38: case 40: case 41: case 48: case 77: case 78: case 79: case 90:
                return 30;
            default:
                return 15;
        }
    }
}
