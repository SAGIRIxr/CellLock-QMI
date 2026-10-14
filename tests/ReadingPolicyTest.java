package com.celllock.app;

import java.util.Arrays;

/** Entry selection only; no Android services, Root processes, or device access. */
public final class ReadingPolicyTest {
    public static void main(String[] args) {
        int[][] input = {{-1,0},{1,0},{1,1},{1,2},{0,0},{0,2}};
        int[][] expected = {{1,0},{1},{1},{1,0},{0},{0,1}};
        for (int i=0; i<input.length; i++)
            if (!Arrays.equals(RadioReader.diagnosticCandidates(input[i][0],input[i][1]), expected[i]))
                throw new AssertionError("processor selection " + i);
        System.out.println("ReadingPolicy: " + input.length + " checks passed");
    }
}
