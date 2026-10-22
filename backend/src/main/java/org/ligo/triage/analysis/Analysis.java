package org.ligo.triage.analysis;

import org.ligo.triage.data.GlitchTable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.stream.IntStream;

/**
 * Scores every glitch under a {@link RankingSpec} and renders the ranked list, findings and per-event CSV.
 * Per-row work runs on parallel streams so the Mac mini's cores are used; results are order-independent
 * and therefore deterministic.
 */
public final class Analysis {
    /** Gravity Spy spectrogram windows for url1..url4. */
    public static final List<String> SPECTROGRAM_WINDOWS = List.of("0.5 s", "1.0 s", "2.0 s", "4.0 s");

    public final GlitchTable table;
    public final RankingSpec spec;
    public final int n;
    public final String[] ids, labels;
    public final double[] confidence;
    public final double[][] probs;
    public final double[] entropy, entropyNorm, margin, rmsZ, outlier, priority;
    public final int[] top1, top2;
    public final boolean[] conflict, globalBaseline;
    /** True when ml_label/ml_confidence come from a different model than the published probabilities (O3). */
    public final boolean labelModelMismatch;
    public final double confidenceMismatchShare;
    final double[][] logFeat;      // [feature][row], log10 of metadata
    final double[][] z;            // [row][feature]
    final Baselines baselines;
    public final int[] order;      // row indices, highest priority first
    private final int[] rankOf;
    private final double[] sortedEntropy, sortedMargin, sortedRmsZ;
    private double[] stability;    // fraction of bootstrap resamples where the event stays in the top N
    private Map<String, Object> stabilitySummary;

    private Analysis(GlitchTable table, RankingSpec spec) {
        this.table = table;
        this.spec = spec;
        this.n = table.size();
        this.ids = table.textColumn("gravityspy_id");
        this.labels = table.textColumn("ml_label");
        this.confidence = table.column("ml_confidence");
        this.probs = table.probabilities();

        entropy = new double[n];
        entropyNorm = new double[n];
        margin = new double[n];
        top1 = new int[n];
        top2 = new int[n];
        conflict = new boolean[n];
        confidenceMismatchShare = table.confidenceMismatchShare();
        labelModelMismatch = confidenceMismatchShare > GlitchTable.MODEL_MISMATCH_THRESHOLD;
        IntStream.range(0, n).parallel().forEach(i -> {
            double[] p = probs[i];
            entropy[i] = Scoring.entropy(p);
            entropyNorm[i] = Scoring.normalizedEntropy(p);
            int[] t = Scoring.topTwo(p);
            top1[i] = t[0];
            top2[i] = t[1];
            margin[i] = p[t[0]] - p[t[1]];
            // A label/argmax disagreement is only evidence of a labelling problem when both come from the same model.
            conflict[i] = !labelModelMismatch && GlitchTable.CLASSES.contains(labels[i])
                    && !GlitchTable.CLASSES.get(t[0]).equals(labels[i]);
        });

        List<String> feats = spec.outlierFeatures();
        logFeat = new double[feats.size()][];
        for (int f = 0; f < feats.size(); f++) {
            double[] raw = table.column(feats.get(f));
            logFeat[f] = Arrays.stream(raw).map(v -> v > 0 ? Math.log10(v) : Double.NaN).toArray();
        }
        baselines = Baselines.compute(labels, logFeat, IntStream.range(0, n).toArray(), spec.minClassSize());
        z = new double[n][feats.size()];
        rmsZ = new double[n];
        outlier = new double[n];
        globalBaseline = new boolean[n];
        priority = new double[n];
        IntStream.range(0, n).parallel().forEach(i -> {
            globalBaseline[i] = !baselines.hasClass(labels[i]);
            for (int f = 0; f < logFeat.length; f++) z[i][f] = baselines.z(labels[i], f, logFeat[f][i]);
            rmsZ[i] = Scoring.rms(z[i]);
            outlier[i] = Scoring.outlierScore(rmsZ[i], spec.outlierZCap());
            priority[i] = Scoring.priority(spec, entropyNorm[i], margin[i], outlier[i], conflict[i]);
        });

        order = rankOrder(priority, ids);
        rankOf = new int[n];
        for (int r = 0; r < n; r++) rankOf[order[r]] = r + 1;
        sortedEntropy = entropyNorm.clone(); Arrays.sort(sortedEntropy);
        sortedMargin = margin.clone(); Arrays.sort(sortedMargin);
        sortedRmsZ = rmsZ.clone(); Arrays.sort(sortedRmsZ);
    }

    public static Analysis run(GlitchTable table, RankingSpec spec) {
        return new Analysis(table, spec);
    }

    /** Priority descending, gravityspy_id ascending as a deterministic tie-break. */
    public static int[] rankOrder(double[] priority, String[] ids) {
        return IntStream.range(0, priority.length).boxed()
                .sorted(Comparator.<Integer>comparingDouble(i -> -priority[i]).thenComparing(i -> ids[i]))
                .mapToInt(Integer::intValue).toArray();
    }

    public int rankOf(int row) { return rankOf[row]; }

    public boolean isAmbiguous(int row) { return margin[row] < spec.ambiguousMargin(); }

    public int ambiguousCount() {
        int c = 0;
        for (int i = 0; i < n; i++) if (isAmbiguous(i)) c++;
        return c;
    }

    // ---------------------------------------------------------------- bootstrap rank stability

    /**
     * Resamples the dataset with replacement, rebuilds the per-class metadata baselines from each resample and
     * re-ranks every event. Reports how often each top-N event stays in the top N. This is the computationally
     * heavy step and is parallelised across resamples.
     */
    public Map<String, Object> runStability(int resamples, long seed) {
        int topN = Math.min(spec.topN(), n);
        AtomicIntegerArray hits = new AtomicIntegerArray(n);
        long t0 = System.nanoTime();
        IntStream.range(0, resamples).parallel().forEach(b -> {
            SplittableRandom rnd = new SplittableRandom(seed + b * 0x9E3779B97F4A7C15L);
            int[] sample = new int[n];
            for (int i = 0; i < n; i++) sample[i] = rnd.nextInt(n);
            Baselines bl = Baselines.compute(labels, logFeat, sample, spec.minClassSize());
            double[] pr = new double[n];
            double[] zz = new double[logFeat.length];
            for (int i = 0; i < n; i++) {
                for (int f = 0; f < logFeat.length; f++) zz[f] = bl.z(labels[i], f, logFeat[f][i]);
                double o = Scoring.outlierScore(Scoring.rms(zz), spec.outlierZCap());
                pr[i] = Scoring.priority(spec, entropyNorm[i], margin[i], o, conflict[i]);
            }
            int[] ord = rankOrder(pr, ids);
            for (int r = 0; r < topN; r++) hits.incrementAndGet(ord[r]);
        });
        double seconds = (System.nanoTime() - t0) / 1e9;
        stability = new double[n];
        for (int i = 0; i < n; i++) stability[i] = hits.get(i) / (double) resamples;
        double[] topStab = Arrays.stream(order, 0, topN).mapToDouble(i -> stability[i]).toArray();
        stabilitySummary = new LinkedHashMap<>();
        stabilitySummary.put("resamples", resamples);
        stabilitySummary.put("seed", seed);
        stabilitySummary.put("threads", Runtime.getRuntime().availableProcessors());
        stabilitySummary.put("seconds", round(seconds, 2));
        stabilitySummary.put("row_rescorings", (long) resamples * n);
        stabilitySummary.put("mean_top_n_stability", round(Arrays.stream(topStab).average().orElse(0), 3));
        stabilitySummary.put("unstable_in_top_n", Arrays.stream(topStab).filter(s -> s < 0.5).count());
        return stabilitySummary;
    }

    public boolean hasStability() { return stability != null; }

    // ---------------------------------------------------------------- outputs

    public String scoresCsv() {
        StringBuilder sb = new StringBuilder(n * 160);
        sb.append("rank,gravityspy_id,ml_label,ml_confidence,argmax_label,second_label,p_top1,p_top2,margin,"
                + "entropy_nats,entropy_norm,outlier_rms_z,outlier_score,label_conflict,priority\n");
        for (int r = 0; r < n; r++) {
            int i = order[r];
            sb.append(r + 1).append(',').append(ids[i]).append(',').append(labels[i]).append(',')
                    .append(fmt(confidence[i])).append(',')
                    .append(GlitchTable.CLASSES.get(top1[i])).append(',').append(GlitchTable.CLASSES.get(top2[i])).append(',')
                    .append(fmt(probs[i][top1[i]])).append(',').append(fmt(probs[i][top2[i]])).append(',')
                    .append(fmt(margin[i])).append(',').append(fmt(entropy[i])).append(',').append(fmt(entropyNorm[i])).append(',')
                    .append(fmt(rmsZ[i])).append(',').append(fmt(outlier[i])).append(',')
                    .append(conflict[i]).append(',').append(fmt(priority[i])).append('\n');
        }
        return sb.toString();
    }

    public Map<String, Object> rankedJson(String runId) {
        List<Map<String, Object>> events = new ArrayList<>();
        int topN = Math.min(spec.topN(), n);
        for (int r = 0; r < topN; r++) events.add(event(order[r]));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", runId);
        out.put("events_scored", n);
        out.put("top_n", topN);
        out.put("spec", spec.toMap());
        out.put("events", events);
        return out;
    }

    public Map<String, Object> event(int i) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("rank", rankOf[i]);
        e.put("gravityspy_id", ids[i]);
        e.put("event_time", table.raw(i, "event_time"));
        e.put("ifo", table.raw(i, "ifo"));
        e.put("ml_label", labels[i]);
        e.put("ml_confidence", round(confidence[i], 6));
        e.put("argmax_label", GlitchTable.CLASSES.get(top1[i]));
        e.put("label_conflict", conflict[i]);
        e.put("top_classes", topClasses(i, 3));

        Map<String, Object> sc = new LinkedHashMap<>();
        sc.put("priority", round(priority[i], 4));
        sc.put("entropy_nats", round(entropy[i], 4));
        sc.put("entropy_norm", round(entropyNorm[i], 4));
        sc.put("margin", round(margin[i], 4));
        sc.put("outlier_rms_z", round(rmsZ[i], 3));
        sc.put("outlier_score", round(outlier[i], 4));
        sc.put("contributions", contributions(i));
        Map<String, Object> pct = new LinkedHashMap<>();
        pct.put("entropy_higher_than", round(fractionBelow(sortedEntropy, entropyNorm[i]), 4));
        pct.put("margin_closer_than", round(1 - fractionBelow(sortedMargin, margin[i]), 4));
        pct.put("outlier_higher_than", round(fractionBelow(sortedRmsZ, rmsZ[i]), 4));
        sc.put("percentiles", pct);
        e.put("scores", sc);

        e.put("baseline", globalBaseline[i] ? "global (class has < " + spec.minClassSize() + " events)" : "class:" + labels[i]);
        e.put("outlier_features", outlierFeatures(i));
        Map<String, Object> meta = new LinkedHashMap<>();
        for (String f : GlitchTable.METADATA_FEATURES) meta.put(f, table.number(i, table.col(f)));
        e.put("metadata", meta);
        List<Map<String, Object>> specs = new ArrayList<>();
        for (int k = 0; k < 4; k++)
            specs.add(Map.of("window", SPECTROGRAM_WINDOWS.get(k), "url", table.raw(i, "url" + (k + 1))));
        e.put("spectrograms", specs);
        e.put("stability", stability == null ? null : round(stability[i], 3));
        e.put("drivers", drivers(i));
        e.put("explanation", explain(i));
        return e;
    }

    private List<Map<String, Object>> topClasses(int i, int k) {
        Integer[] idx = IntStream.range(0, GlitchTable.CLASSES.size()).boxed().toArray(Integer[]::new);
        Arrays.sort(idx, Comparator.<Integer>comparingDouble(c -> -probs[i][c]).thenComparing(c -> c));
        List<Map<String, Object>> out = new ArrayList<>();
        for (int j = 0; j < k; j++)
            out.add(Map.of("class", GlitchTable.CLASSES.get(idx[j]), "probability", round(probs[i][idx[j]], 4)));
        return out;
    }

    private Map<String, Object> contributions(int i) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("entropy", round(spec.wEntropy() * entropyNorm[i], 4));
        c.put("margin", round(spec.wMargin() * (1 - margin[i]), 4));
        c.put("outlier", round(spec.wOutlier() * outlier[i], 4));
        c.put("label_conflict", conflict[i] ? spec.labelConflictBonus() : 0.0);
        return c;
    }

    private List<Map<String, Object>> outlierFeatures(int i) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int f = 0; f < logFeat.length; f++) {
            String name = spec.outlierFeatures().get(f);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("feature", name);
            m.put("value", table.number(i, table.col(name)));
            m.put("baseline_median", round(Math.pow(10, baselines.median(labels[i], f)), 6));
            m.put("robust_z", round(z[i][f], 2));
            out.add(m);
        }
        out.sort(Comparator.comparingDouble(m -> -Math.abs((double) m.get("robust_z"))));
        return out;
    }

    /** Ranking drivers relative to an average event, largest first. */
    public List<String> drivers(int i) {
        Map<String, Double> lift = new LinkedHashMap<>();
        lift.put("uncertainty", spec.wEntropy() * entropyNorm[i]);
        lift.put("close competition", spec.wMargin() * (1 - margin[i]));
        lift.put("metadata outlier", spec.wOutlier() * outlier[i]);
        if (conflict[i]) lift.put("label/argmax conflict", spec.labelConflictBonus());
        return lift.entrySet().stream().filter(en -> en.getValue() > 0.005)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey).toList();
    }

    public String explain(int i) {
        String c1 = GlitchTable.CLASSES.get(top1[i]), c2 = GlitchTable.CLASSES.get(top2[i]);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Ranked #%d of %,d (priority %.3f). ", rankOf[i], n, priority[i]));
        sb.append(String.format(Locale.ROOT, "Competing classes: %s %.2f vs %s %.2f (margin %.3f, closer than %.1f%% of events). ",
                c1, probs[i][top1[i]], c2, probs[i][top2[i]], margin[i],
                100 * (1 - fractionBelow(sortedMargin, margin[i]))));
        sb.append(String.format(Locale.ROOT, "Entropy is %.2f of maximum (higher than %.1f%% of events). ",
                entropyNorm[i], 100 * fractionBelow(sortedEntropy, entropyNorm[i])));
        List<Map<String, Object>> of = outlierFeatures(i);
        Map<String, Object> worst = of.get(0);
        double wz = (double) worst.get("robust_z");
        String base = globalBaseline[i] ? "the global" : "the " + labels[i];
        if (Math.abs(wz) >= 2) {
            sb.append(String.format(Locale.ROOT, "Metadata is unusual: %s is %.1f robust SD %s %s median (RMS z %.2f). ",
                    worst.get("feature"), Math.abs(wz), wz > 0 ? "above" : "below", base, rmsZ[i]));
        } else {
            sb.append(String.format(Locale.ROOT, "Metadata is typical for %s baseline (RMS z %.2f). ", base.substring(4), rmsZ[i]));
        }
        if (conflict[i])
            sb.append("Published ml_label (").append(labels[i]).append(") differs from the argmax of the published probabilities (")
                    .append(c1).append("). ");
        if (stability != null && stability[i] >= 0.5)
            sb.append(String.format(Locale.ROOT, "Stays in the top %d in %.0f%% of bootstrap resamples. ",
                    spec.topN(), 100 * stability[i]));
        else if (stability != null)
            sb.append(String.format(Locale.ROOT, "Rank is sensitive to the metadata baseline: it stays in the top %d in only %.0f%% "
                    + "of bootstrap resamples. ", spec.topN(), 100 * stability[i]));
        if (!table.raw(i, "url1").startsWith("http")) sb.append("No spectrogram images were published for this event. ");
        List<String> d = drivers(i);
        if (!d.isEmpty()) sb.append("Main driver: ").append(d.get(0)).append('.');
        return sb.toString().trim();
    }

    public Map<String, Object> findingsJson(String runId) {
        Map<String, int[]> perClass = new TreeMap<>(); // count, ambiguous
        Map<String, double[]> perClassSums = new HashMap<>(); // entropyNorm, margin, priority
        Map<String, Integer> pairs = new HashMap<>();
        int ambiguous = 0, conflicts = 0;
        List<String> conflictExamples = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int[] c = perClass.computeIfAbsent(labels[i], k -> new int[2]);
            double[] s = perClassSums.computeIfAbsent(labels[i], k -> new double[3]);
            c[0]++;
            s[0] += entropyNorm[i]; s[1] += margin[i]; s[2] += priority[i];
            if (isAmbiguous(i)) {
                c[1]++;
                ambiguous++;
                String a = GlitchTable.CLASSES.get(top1[i]), b = GlitchTable.CLASSES.get(top2[i]);
                String key = a.compareTo(b) < 0 ? a + " <> " + b : b + " <> " + a;
                pairs.merge(key, 1, Integer::sum);
            }
            if (conflict[i]) {
                conflicts++;
                if (conflictExamples.size() < 5)
                    conflictExamples.add(ids[i] + " (ml_label " + labels[i] + ", argmax " + GlitchTable.CLASSES.get(top1[i]) + ")");
            }
        }
        List<Map<String, Object>> classRows = new ArrayList<>();
        perClass.forEach((label, c) -> {
            double[] s = perClassSums.get(label);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("class", label);
            m.put("events", c[0]);
            m.put("ambiguous", c[1]);
            m.put("ambiguous_share", round(c[1] / (double) c[0], 4));
            m.put("mean_entropy_norm", round(s[0] / c[0], 4));
            m.put("mean_margin", round(s[1] / c[0], 4));
            m.put("mean_priority", round(s[2] / c[0], 4));
            classRows.add(m);
        });
        classRows.sort(Comparator.<Map<String, Object>>comparingInt(m -> -(int) m.get("ambiguous"))
                .thenComparing(m -> (String) m.get("class")));
        List<Map<String, Object>> pairRows = pairs.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(10)
                .map(en -> Map.<String, Object>of("pair", en.getKey(), "ambiguous_events", en.getValue()))
                .toList();
        String mostCommon = perClass.entrySet().stream().max(Comparator.comparingInt(en -> en.getValue()[0])).map(Map.Entry::getKey).orElse("");
        Map<String, Object> byShare = classRows.stream().filter(m -> (int) m.get("events") >= spec.minClassSize())
                .max(Comparator.comparingDouble(m -> (double) m.get("ambiguous_share"))).orElse(null);

        int topN = Math.min(spec.topN(), n);
        Map<String, Integer> topLabels = new TreeMap<>();
        Map<String, Integer> topDrivers = new TreeMap<>();
        for (int r = 0; r < topN; r++) {
            int i = order[r];
            topLabels.merge(labels[i], 1, Integer::sum);
            List<String> d = drivers(i);
            if (!d.isEmpty()) topDrivers.merge(d.get(0), 1, Integer::sum);
        }

        long argmaxDisagree = IntStream.range(0, n).filter(i -> !GlitchTable.CLASSES.get(top1[i]).equals(labels[i])).count();
        List<String> next = new ArrayList<>();
        if (!pairRows.isEmpty()) {
            Map<String, Object> p = pairRows.get(0);
            next.add(String.format(Locale.ROOT, "Review the %s boundary first: %d ambiguous events have these as their top two classes.",
                    p.get("pair"), (int) p.get("ambiguous_events")));
        }
        if (byShare != null)
            next.add(String.format(Locale.ROOT, "%s has the highest ambiguous share among classes with at least %d events (%.1f%%); inspect whether its definition overlaps neighbouring classes.",
                    byShare.get("class"), spec.minClassSize(), 100 * (double) byShare.get("ambiguous_share")));
        if (labelModelMismatch)
            next.add(String.format(Locale.ROOT, "Labels in this dataset come from a newer model than the published probabilities "
                    + "(%.0f%% of events disagree with the argmax); cross-check ranked events against the newer model's labels before review.",
                    100.0 * argmaxDisagree / n));
        if (conflicts > 0)
            next.add(String.format(Locale.ROOT, "Investigate the %d events whose published ml_label differs from the argmax of the published probabilities (possible post-processing or versioning mismatch).", conflicts));
        if (stabilitySummary != null && ((Number) stabilitySummary.get("unstable_in_top_n")).longValue() > 0)
            next.add(String.format(Locale.ROOT, "%s of the top %d events drop out in most bootstrap resamples; treat their metadata-outlier contribution with caution.",
                    stabilitySummary.get("unstable_in_top_n"), topN));
        next.add("Compare with the other detector and observing runs (campaign comparison) to test whether these uncertainty patterns are detector- or run-specific.");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", runId);
        out.put("events_checked", n);
        out.put("ambiguous_definition", "top-two margin < " + spec.ambiguousMargin());
        out.put("ambiguous_events", ambiguous);
        out.put("ambiguous_share", round(ambiguous / (double) n, 4));
        out.put("most_common_class", mostCommon);
        out.put("top_uncertain_class_by_count", classRows.isEmpty() ? null : classRows.get(0).get("class"));
        out.put("top_uncertain_class_by_share", byShare == null ? null : byShare.get("class"));
        out.put("class_uncertainty", classRows);
        out.put("confusion_pairs", pairRows);
        out.put("label_conflicts", Map.of("count", conflicts, "examples", conflictExamples));
        long extended = java.util.Arrays.stream(labels).filter(GlitchTable.EXTENDED_LABELS::contains).count();
        out.put("label_provenance", Map.of(
                "labels_from_different_model", labelModelMismatch,
                "confidence_mismatch_share", round(confidenceMismatchShare, 4),
                "label_argmax_disagreement_share", round(argmaxDisagree / (double) n, 4),
                "extended_label_rows", extended,
                "note", labelModelMismatch
                        ? "ml_label/ml_confidence come from a newer model than the 22 published probabilities; entropy and margin "
                        + "describe the published-probability model, and the label-conflict bonus is disabled."
                        : "ml_label/ml_confidence are consistent with the published probabilities."));
        out.put("top_n_composition", Map.of("labels", topLabels, "primary_drivers", topDrivers));
        out.put("rank_stability", stabilitySummary);
        out.put("next_steps", next);
        out.put("limitations", spec.limitations());
        return out;
    }

    // ---------------------------------------------------------------- helpers

    static double fractionBelow(double[] sorted, double v) {
        int lo = 0, hi = sorted.length;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (sorted[mid] < v) lo = mid + 1; else hi = mid; }
        return sorted.length == 0 ? 0 : lo / (double) sorted.length;
    }

    static String fmt(double v) { return String.format(Locale.ROOT, "%.6g", v); }

    public static double round(double v, int places) {
        if (!Double.isFinite(v)) return v;
        double s = Math.pow(10, places);
        return Math.round(v * s) / s;
    }

    /** Per-class robust (median / scaled MAD) baselines of log10 metadata, with a global fallback for rare classes. */
    static final class Baselines {
        private final Map<String, double[][]> perClass; // label -> [feature][median, spread]
        private final double[][] global;

        private Baselines(Map<String, double[][]> perClass, double[][] global) {
            this.perClass = perClass;
            this.global = global;
        }

        static Baselines compute(String[] labels, double[][] logFeat, int[] sample, int minClassSize) {
            Map<String, List<Integer>> byClass = new HashMap<>();
            for (int i : sample) byClass.computeIfAbsent(labels[i], k -> new ArrayList<>()).add(i);
            double[][] global = stats(logFeat, sample);
            Map<String, double[][]> per = new HashMap<>();
            byClass.forEach((label, rows) -> {
                if (rows.size() >= minClassSize) per.put(label, stats(logFeat, rows.stream().mapToInt(Integer::intValue).toArray()));
            });
            return new Baselines(per, global);
        }

        private static double[][] stats(double[][] logFeat, int[] rows) {
            double[][] s = new double[logFeat.length][2];
            for (int f = 0; f < logFeat.length; f++) {
                double[] v = new double[rows.length];
                for (int j = 0; j < rows.length; j++) v[j] = logFeat[f][rows[j]];
                double med = Scoring.median(v);
                s[f][0] = med;
                s[f][1] = Scoring.scaledMad(v, med);
            }
            return s;
        }

        boolean hasClass(String label) { return perClass.containsKey(label); }

        double median(String label, int f) { return perClass.getOrDefault(label, global)[f][0]; }

        double z(String label, int f, double x) {
            double[] s = perClass.getOrDefault(label, global)[f];
            // A class with near-zero spread on a feature (e.g. discrete q_value) borrows the global spread.
            double spread = s[1] > 1e-3 ? s[1] : global[f][1];
            return Scoring.robustZ(x, s[0], spread);
        }
    }
}
