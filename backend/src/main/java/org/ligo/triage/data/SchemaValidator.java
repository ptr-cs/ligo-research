package org.ligo.triage.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Structural validation of the raw CSV, run by the Developer before any scoring. */
public final class SchemaValidator {
    public static final double SUM_TOLERANCE = 1e-3;

    private SchemaValidator() {}

    public static Map<String, Object> validate(GlitchTable t) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        List<String> missingCols = GlitchTable.REQUIRED_COLUMNS.stream().filter(c -> t.col(c) < 0).toList();
        if (!missingCols.isEmpty()) errors.add("Missing required columns: " + missingCols);
        List<String> extraClassLike = t.header().stream()
                .filter(h -> !GlitchTable.REQUIRED_COLUMNS.contains(h))
                .toList();

        int widthMismatch = 0, badProb = 0, badSum = 0, badFeature = 0, unknownLabel = 0, dupIds = 0, emptyCells = 0,
                placeholderUrls = 0, unclassified = 0, extendedLabel = 0;
        double minSum = Double.POSITIVE_INFINITY, maxSum = Double.NEGATIVE_INFINITY;
        Set<String> ids = new HashSet<>();
        if (missingCols.isEmpty()) {
            int[] probCols = GlitchTable.CLASSES.stream().mapToInt(t::col).toArray();
            int[] featCols = GlitchTable.METADATA_FEATURES.stream().mapToInt(t::col).toArray();
            int labelCol = t.col("ml_label"), idCol = t.col("gravityspy_id");
            for (int i = 0; i < t.size(); i++) {
                if (t.width(i) != t.header().size()) widthMismatch++;
                for (String c : GlitchTable.REQUIRED_COLUMNS) if (t.raw(i, c).isEmpty()) emptyCells++;
                if (!t.raw(i, "url1").startsWith("http")) placeholderUrls++;
                if (t.isUnclassified(i)) { unclassified++; continue; }
                double sum = 0;
                boolean rowBad = false;
                for (int c : probCols) {
                    double p = t.number(i, c);
                    if (!(p >= 0 && p <= 1)) rowBad = true;
                    sum += p;
                }
                if (rowBad) badProb++;
                if (Math.abs(sum - 1) > SUM_TOLERANCE) badSum++;
                if (!Double.isNaN(sum)) { minSum = Math.min(minSum, sum); maxSum = Math.max(maxSum, sum); }
                for (int c : featCols) {
                    double v = t.number(i, c);
                    if (!(v > 0) || Double.isInfinite(v)) { badFeature++; break; }
                }
                String label = t.raw(i, labelCol);
                if (GlitchTable.EXTENDED_LABELS.contains(label)) extendedLabel++;
                else if (!GlitchTable.CLASSES.contains(label)) unknownLabel++;
                if (!ids.add(t.raw(i, idCol))) dupIds++;
            }
            if (t.size() == 0) errors.add("Dataset has no rows");
            if (widthMismatch > 0) errors.add(widthMismatch + " rows have the wrong number of fields");
            if (badProb > 0) errors.add(badProb + " rows have a class probability outside [0,1] or non-numeric");
            if (badSum > 0) errors.add(badSum + " rows have probabilities that do not sum to 1 (tolerance " + SUM_TOLERANCE + ")");
            if (unknownLabel > 0) errors.add(unknownLabel + " rows have an ml_label outside the 22 probability classes and the known newer classes " + GlitchTable.EXTENDED_LABELS);
            if (extendedLabel > 0) warnings.add(extendedLabel + " rows are labelled with newer classes " + GlitchTable.EXTENDED_LABELS + " that have no published probability column");
            if (badFeature > 0) warnings.add(badFeature + " rows have a non-positive or missing metadata feature (excluded from outlier stats)");
            if (dupIds > 0) warnings.add(dupIds + " duplicate gravityspy_id values");
            if (emptyCells > 0) warnings.add(emptyCells + " empty cells in required columns");
            if (unclassified > 0) warnings.add(unclassified + " rows are unclassified (all 22 probabilities are 0); they are excluded from scoring");
            if (placeholderUrls > 0) warnings.add(placeholderUrls + " events have placeholder spectrogram URLs ('?') instead of image links");
        }

        double mismatch = missingCols.isEmpty() ? t.confidenceMismatchShare() : 0;
        if (mismatch > GlitchTable.MODEL_MISMATCH_THRESHOLD)
            warnings.add(String.format(java.util.Locale.ROOT, "ml_confidence differs from the highest published probability in %.1f%% of rows: "
                    + "ml_label/ml_confidence appear to come from a different model version than the 22 probability columns", 100 * mismatch));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", errors.isEmpty());
        out.put("extended_label_rows", extendedLabel);
        out.put("confidence_mismatch_share", mismatch);
        out.put("rows", t.size());
        out.put("unclassified_rows", unclassified);
        out.put("scored_rows", t.size() - unclassified);
        out.put("columns", t.header().size());
        out.put("class_columns", GlitchTable.CLASSES.size());
        out.put("probability_sum_range", List.of(minSum, maxSum));
        out.put("extra_columns", extraClassLike);
        out.put("errors", errors);
        out.put("warnings", warnings);
        return out;
    }
}
