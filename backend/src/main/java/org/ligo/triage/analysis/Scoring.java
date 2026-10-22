package org.ligo.triage.analysis;

import java.util.Arrays;

/** Pure scoring math. Everything here is deterministic and side-effect free so the Tester can pin it down. */
public final class Scoring {
    /** Converts MAD to a standard-deviation-equivalent for normally distributed data. */
    public static final double MAD_SCALE = 1.4826;
    private static final double MIN_SPREAD = 1e-6;

    private Scoring() {}

    /** Shannon entropy in nats of a probability vector, renormalised to sum to 1. */
    public static double entropy(double[] p) {
        double sum = 0;
        for (double v : p) sum += v;
        if (!(sum > 0)) return Double.NaN;
        double h = 0;
        for (double v : p) {
            double q = v / sum;
            if (q > 0) h -= q * Math.log(q);
        }
        return h;
    }

    /** Entropy divided by its maximum, ln(K): 0 = certain, 1 = uniform. */
    public static double normalizedEntropy(double[] p) {
        return p.length < 2 ? 0 : entropy(p) / Math.log(p.length);
    }

    /** Indices of the highest and second-highest probability; ties resolve to the lower index. */
    public static int[] topTwo(double[] p) {
        int a = -1, b = -1;
        for (int i = 0; i < p.length; i++) {
            if (a < 0 || p[i] > p[a]) { b = a; a = i; }
            else if (b < 0 || p[i] > p[b]) b = i;
        }
        return new int[]{a, b};
    }

    /** p1 - p2: small values mean two classes are competing. */
    public static double margin(double[] p) {
        int[] t = topTwo(p);
        return t[1] < 0 ? 1 : p[t[0]] - p[t[1]];
    }

    /** Median of the finite values; NaN when there are none. */
    public static double median(double[] values) {
        double[] v = Arrays.stream(values).filter(Double::isFinite).sorted().toArray();
        if (v.length == 0) return Double.NaN;
        int m = v.length / 2;
        return v.length % 2 == 1 ? v[m] : (v[m - 1] + v[m]) / 2;
    }

    /** Median absolute deviation scaled to a standard-deviation equivalent. */
    public static double scaledMad(double[] values, double median) {
        double[] dev = Arrays.stream(values).filter(Double::isFinite).map(x -> Math.abs(x - median)).toArray();
        return MAD_SCALE * median(dev);
    }

    public static double robustZ(double x, double median, double spread) {
        if (!Double.isFinite(x) || !Double.isFinite(median)) return 0;
        return (x - median) / Math.max(spread, MIN_SPREAD);
    }

    /** Root-mean-square of finite z-scores. */
    public static double rms(double[] z) {
        double s = 0;
        int n = 0;
        for (double v : z) if (Double.isFinite(v)) { s += v * v; n++; }
        return n == 0 ? 0 : Math.sqrt(s / n);
    }

    public static double outlierScore(double rmsZ, double cap) {
        return Math.min(Math.max(rmsZ, 0), cap) / cap;
    }

    public static double priority(RankingSpec s, double entropyNorm, double margin, double outlier, boolean labelConflict) {
        return s.wEntropy() * entropyNorm
                + s.wMargin() * (1 - margin)
                + s.wOutlier() * outlier
                + (labelConflict ? s.labelConflictBonus() : 0);
    }
}
