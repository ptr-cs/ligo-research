package org.ligo.triage.analysis;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SensitivityTest {

    @Test
    void simplexGridSizesAndSums() {
        assertEquals(66, Sensitivity.grid(0.1).size());
        assertEquals(231, Sensitivity.grid(0.05).size());
        for (double[] w : Sensitivity.grid(0.05)) assertEquals(1.0, w[0] + w[1] + w[2], 1e-12);
    }

    @Test
    void topNMatchesFullRankingIncludingTies() {
        double[] p = {0.5, 0.9, 0.5, 0.1, 0.7, 0.5};
        String[] ids = {"f", "a", "b", "c", "d", "e"};
        int[] full = Analysis.rankOrder(p, ids);
        for (int n = 1; n <= p.length; n++) {
            int[] top = Sensitivity.topN(p, ids, n);
            Arrays.sort(top);
            int[] expected = Arrays.copyOf(full, n);
            Arrays.sort(expected);
            assertArrayEquals(expected, top, "top " + n);
        }
        assertEquals(List.of(1, 4, 2), Arrays.stream(Arrays.copyOf(full, 3)).boxed().toList());
    }
}
