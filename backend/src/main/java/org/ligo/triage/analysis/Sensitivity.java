package org.ligo.triage.analysis;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.stream.IntStream;

/**
 * How much does the human review list depend on the Researcher's choices? For every weight combination on a
 * simplex grid, and for every bootstrap resample of the metadata baselines, the top-N list is re-derived and
 * compared with the list produced by the Researcher's spec. Events that stay on the list across most
 * combinations and resamples form a "consensus" review list that is robust to the formula.
 */
public final class Sensitivity {
    private Sensitivity() {}

    /** All (entropy, margin, outlier) weight triples that are multiples of step and sum to 1. */
    public static List<double[]> grid(double step) {
        int k = (int) Math.round(1 / step);
        List<double[]> out = new ArrayList<>();
        for (int a = 0; a <= k; a++)
            for (int b = 0; a + b <= k; b++)
                out.add(new double[]{a / (double) k, b / (double) k, (k - a - b) / (double) k});
        return out;
    }

    /** Top-N row indices under a priority vector (priority desc, id asc), via a bounded heap. */
    static int[] topN(double[] priority, String[] ids, int n) {
        Comparator<Integer> worstFirst = Comparator.<Integer>comparingDouble(i -> priority[i])
                .thenComparing((Integer i) -> ids[i], Comparator.reverseOrder());
        PriorityQueue<Integer> heap = new PriorityQueue<>(n + 1, worstFirst);
        for (int i = 0; i < priority.length; i++) {
            if (heap.size() < n) heap.add(i);
            else if (worstFirst.compare(i, heap.peek()) > 0) { heap.poll(); heap.add(i); }
        }
        return heap.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int overlap(int[] top, Set<Integer> reference) {
        int c = 0;
        for (int i : top) if (reference.contains(i)) c++;
        return c;
    }

    /**
     * One sweep round. Stops starting new resamples once the deadline passes and reports how many completed.
     */
    public static Map<String, Object> round(Analysis base, double step, int resamples, long seed, Instant deadline) {
        long t0 = System.nanoTime();
        RankingSpec spec = base.spec;
        int n = base.n, topN = Math.min(spec.topN(), n);
        List<double[]> combos = grid(step);
        Set<Integer> reference = new HashSet<>();
        for (int r = 0; r < topN; r++) reference.add(base.order[r]);

        // 1. Pure weight sensitivity on the original baselines.
        double[] weightOverlap = new double[combos.size()];
        IntStream.range(0, combos.size()).parallel().forEach(c -> {
            double[] w = combos.get(c);
            double[] pr = new double[n];
            for (int i = 0; i < n; i++)
                pr[i] = w[0] * base.entropyNorm[i] + w[1] * (1 - base.margin[i]) + w[2] * base.outlier[i]
                        + (base.conflict[i] ? spec.labelConflictBonus() : 0);
            weightOverlap[c] = overlap(topN(pr, base.ids, topN), reference) / (double) topN;
        });

        // 2. Weights x bootstrap: rebuild baselines per resample, then evaluate every combination.
        AtomicIntegerArray hits = new AtomicIntegerArray(n);
        DoubleAdder bootOverlap = new DoubleAdder();
        AtomicInteger completed = new AtomicInteger();
        IntStream.range(0, resamples).parallel().forEach(b -> {
            if (Instant.now().isAfter(deadline)) return;
            SplittableRandom rnd = new SplittableRandom(seed + b * 0x9E3779B97F4A7C15L);
            int[] sample = new int[n];
            for (int i = 0; i < n; i++) sample[i] = rnd.nextInt(n);
            Analysis.Baselines bl = Analysis.Baselines.compute(base.labels, base.logFeat, sample, spec.minClassSize());
            double[] outlier = new double[n];
            double[] zz = new double[base.logFeat.length];
            for (int i = 0; i < n; i++) {
                for (int f = 0; f < zz.length; f++) zz[f] = bl.z(base.labels[i], f, base.logFeat[f][i]);
                outlier[i] = Scoring.outlierScore(Scoring.rms(zz), spec.outlierZCap());
            }
            double[] pr = new double[n];
            for (double[] w : combos) {
                for (int i = 0; i < n; i++)
                    pr[i] = w[0] * base.entropyNorm[i] + w[1] * (1 - base.margin[i]) + w[2] * outlier[i]
                            + (base.conflict[i] ? spec.labelConflictBonus() : 0);
                int[] top = topN(pr, base.ids, topN);
                for (int i : top) hits.incrementAndGet(i);
                bootOverlap.add(overlap(top, reference) / (double) topN);
            }
            completed.incrementAndGet();
        });

        int done = completed.get();
        long trials = (long) done * combos.size();
        Integer[] byHits = IntStream.range(0, n).boxed().toArray(Integer[]::new);
        Arrays.sort(byHits, Comparator.<Integer>comparingInt(i -> -hits.get(i)).thenComparing(i -> base.ids[i]));
        List<Map<String, Object>> consensus = new ArrayList<>();
        int consensusOnDefault = 0;
        for (int r = 0; r < topN && trials > 0; r++) {
            int i = byHits[r];
            if (reference.contains(i)) consensusOnDefault++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("gravityspy_id", base.ids[i]);
            m.put("ml_label", base.labels[i]);
            m.put("frequency", Analysis.round(hits.get(i) / (double) trials, 3));
            m.put("default_rank", base.rankOf(i));
            consensus.add(m);
        }
        int worst = 0;
        for (int c = 1; c < combos.size(); c++) if (weightOverlap[c] < weightOverlap[worst]) worst = c;
        double robustShare = Arrays.stream(weightOverlap).filter(o -> o >= 0.8).count() / (double) combos.size();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("grid_step", step);
        out.put("weight_combinations", combos.size());
        out.put("resamples_requested", resamples);
        out.put("resamples_completed", done);
        out.put("top_n_rankings_evaluated", trials + combos.size());
        out.put("seconds", Analysis.round((System.nanoTime() - t0) / 1e9, 1));
        out.put("weights_only", Map.of(
                "mean_overlap_with_default", Analysis.round(Arrays.stream(weightOverlap).average().orElse(0), 3),
                "min_overlap_with_default", Analysis.round(weightOverlap[worst], 3),
                "least_similar_weights", Map.of("entropy", combos.get(worst)[0], "margin", combos.get(worst)[1], "outlier", combos.get(worst)[2]),
                "share_of_weightings_with_overlap_ge_0_8", Analysis.round(robustShare, 3)));
        out.put("weights_and_bootstrap", Map.of(
                "mean_overlap_with_default", trials == 0 ? 0 : Analysis.round(bootOverlap.sum() / trials, 3)));
        out.put("consensus_top", consensus);
        out.put("consensus_overlap_with_default", trials == 0 ? null : Analysis.round(consensusOnDefault / (double) topN, 3));
        return out;
    }
}
