package org.ligo.triage.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import org.ligo.triage.data.GlitchTable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The review-priority formula, as defined by the Researcher:
 * <pre>
 *   priority = wEntropy * H/ln(22) + wMargin * (1 - (p1 - p2)) + wOutlier * min(rmsZ, cap)/cap
 *              + labelConflictBonus * [ml_label != argmax]
 * </pre>
 */
public record RankingSpec(
        double wEntropy,
        double wMargin,
        double wOutlier,
        double labelConflictBonus,
        List<String> outlierFeatures,
        double outlierZCap,
        double ambiguousMargin,
        int topN,
        int minClassSize,
        String rationale,
        List<String> limitations,
        String author) {

    public static RankingSpec defaults(String author) {
        return new RankingSpec(0.40, 0.40, 0.20, 0.05,
                List.of("duration", "peak_frequency", "bandwidth", "snr", "q_value"),
                6.0, 0.20, 25, 30,
                "Classifier uncertainty (entropy) and close competition (top-two margin) are the primary reasons a "
                        + "human should look; metadata atypicality relative to the predicted class is a secondary "
                        + "signal because it can also reflect genuine within-class diversity.",
                List.of(
                        "Scores reflect the published Gravity Spy model only; they do not establish ground truth.",
                        "Entropy and margin are correlated, so the two uncertainty terms partially double-count.",
                        "Per-class metadata baselines are unreliable for rare classes; classes below the minimum size use global baselines.",
                        "Outlier scores use log10 metadata and robust z-scores (median/MAD); multi-modal classes can look anomalous.",
                        "Bootstrap stability resamples only the metadata baselines; for small classes those baselines are noisy, so stability estimates there are pessimistic.",
                        "Each ranking covers one detector and one observing run; patterns may not transfer to the other detector or other runs (see the campaign comparison)."),
                author);
    }

    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        for (double w : new double[]{wEntropy, wMargin, wOutlier})
            if (!(w >= 0 && w <= 1)) errors.add("weights must each be in [0,1]");
        double sum = wEntropy + wMargin + wOutlier;
        if (Math.abs(sum - 1) > 0.01) errors.add("wEntropy + wMargin + wOutlier must sum to 1 (got " + sum + ")");
        if (!(labelConflictBonus >= 0 && labelConflictBonus <= 0.3)) errors.add("label_conflict_bonus must be in [0, 0.3]");
        if (outlierFeatures == null || outlierFeatures.isEmpty()) errors.add("at least one outlier feature is required");
        else for (String f : outlierFeatures)
            if (!GlitchTable.METADATA_FEATURES.contains(f)) errors.add("unknown outlier feature '" + f + "'; allowed: " + GlitchTable.METADATA_FEATURES);
        if (!(outlierZCap >= 2 && outlierZCap <= 20)) errors.add("outlier_z_cap must be in [2, 20]");
        if (!(ambiguousMargin > 0 && ambiguousMargin < 1)) errors.add("ambiguous_margin_threshold must be in (0,1)");
        if (topN < 10 || topN > 100) errors.add("top_n must be in [10, 100]");
        if (minClassSize < 5 || minClassSize > 500) errors.add("min_class_size must be in [5, 500]");
        if (limitations == null || limitations.isEmpty()) errors.add("at least one limitation must be stated");
        return errors;
    }

    public static RankingSpec fromToolInput(JsonNode in, String author) {
        RankingSpec d = defaults(author);
        JsonNode w = in.path("weights");
        List<String> feats = new ArrayList<>();
        in.path("outlier_features").forEach(n -> feats.add(n.asText()));
        List<String> lim = new ArrayList<>();
        in.path("limitations").forEach(n -> lim.add(n.asText()));
        return new RankingSpec(
                w.path("entropy").asDouble(Double.NaN),
                w.path("margin").asDouble(Double.NaN),
                w.path("outlier").asDouble(Double.NaN),
                in.path("label_conflict_bonus").asDouble(d.labelConflictBonus()),
                feats.isEmpty() ? d.outlierFeatures() : List.copyOf(feats),
                in.path("outlier_z_cap").asDouble(d.outlierZCap()),
                in.path("ambiguous_margin_threshold").asDouble(d.ambiguousMargin()),
                in.path("top_n").asInt(d.topN()),
                in.path("min_class_size").asInt(d.minClassSize()),
                in.path("rationale").asText(d.rationale()),
                List.copyOf(lim),
                author);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("formula", "priority = w_entropy*H/ln(22) + w_margin*(1-(p1-p2)) + w_outlier*min(rms_z,cap)/cap + label_conflict_bonus*[ml_label != argmax]");
        m.put("weights", Map.of("entropy", wEntropy, "margin", wMargin, "outlier", wOutlier));
        m.put("label_conflict_bonus", labelConflictBonus);
        m.put("outlier_features", outlierFeatures);
        m.put("outlier_z_cap", outlierZCap);
        m.put("ambiguous_margin_threshold", ambiguousMargin);
        m.put("top_n", topN);
        m.put("min_class_size", minClassSize);
        m.put("rationale", rationale);
        m.put("limitations", limitations);
        m.put("author", author);
        return m;
    }
}
