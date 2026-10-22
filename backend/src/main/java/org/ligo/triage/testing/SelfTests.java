package org.ligo.triage.testing;

import com.fasterxml.jackson.databind.JsonNode;
import org.ligo.triage.Json;
import org.ligo.triage.analysis.Analysis;
import org.ligo.triage.analysis.RankingSpec;
import org.ligo.triage.analysis.Scoring;
import org.ligo.triage.data.Dataset;
import org.ligo.triage.data.GlitchTable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Tester's suite: unit checks on entropy, margins and ranking math plus a determinism check that re-scores
 * the raw dataset from scratch and compares it byte-for-byte with the frozen scores.csv.
 * The same checks back the JUnit suite (mvn test).
 */
public final class SelfTests {

    @FunctionalInterface
    interface Check { String run() throws Exception; }

    record Case(String name, String area, Check check) {}

    private SelfTests() {}

    static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    static void close(double actual, double expected, double tol, String what) {
        require(Math.abs(actual - expected) <= tol,
                String.format(Locale.ROOT, "%s: expected %.9f, got %.9f", what, expected, actual));
    }

    /** Unit-level cases that need no data. */
    static List<Case> unitCases() {
        List<Case> cs = new ArrayList<>();
        cs.add(new Case("entropy_uniform_is_max", "entropy", () -> {
            double[] p = new double[22];
            Arrays.fill(p, 1.0 / 22);
            close(Scoring.entropy(p), Math.log(22), 1e-12, "H(uniform)");
            close(Scoring.normalizedEntropy(p), 1.0, 1e-12, "H/ln22");
            return "uniform over 22 classes gives ln(22) nats, normalised 1.0";
        }));
        cs.add(new Case("entropy_one_hot_is_zero", "entropy", () -> {
            double[] p = new double[22];
            p[5] = 1;
            close(Scoring.entropy(p), 0, 0, "H(one-hot)");
            return "one-hot vector gives 0";
        }));
        cs.add(new Case("entropy_known_value", "entropy", () -> {
            close(Scoring.entropy(new double[]{0.5, 0.5}), Math.log(2), 1e-12, "H(0.5,0.5)");
            close(Scoring.entropy(new double[]{0.9, 0.1}), -(0.9 * Math.log(0.9) + 0.1 * Math.log(0.1)), 1e-12, "H(0.9,0.1)");
            return "binary cases match closed form";
        }));
        cs.add(new Case("entropy_renormalises", "entropy", () -> {
            close(Scoring.entropy(new double[]{1, 1}), Math.log(2), 1e-12, "H(1,1)");
            return "unnormalised input is renormalised before computing entropy";
        }));
        cs.add(new Case("margin_basic", "margin", () -> {
            close(Scoring.margin(new double[]{0.1, 0.6, 0.3}), 0.3, 1e-12, "margin");
            int[] t = Scoring.topTwo(new double[]{0.1, 0.6, 0.3});
            require(t[0] == 1 && t[1] == 2, "topTwo order");
            return "p1-p2 = 0.6-0.3 and top-two indices are correct";
        }));
        cs.add(new Case("margin_tie_is_zero", "margin", () -> {
            close(Scoring.margin(new double[]{0.4, 0.4, 0.2}), 0, 0, "tie margin");
            int[] t = Scoring.topTwo(new double[]{0.4, 0.4, 0.2});
            require(t[0] == 0 && t[1] == 1, "ties resolve to lower index first");
            return "exact tie gives margin 0 with deterministic order";
        }));
        cs.add(new Case("robust_z_median_mad", "outlier", () -> {
            double[] v = {1, 2, 3, 4, 100};
            double med = Scoring.median(v);
            close(med, 3, 0, "median");
            close(Scoring.scaledMad(v, med), Scoring.MAD_SCALE * 1, 1e-12, "scaled MAD");
            close(Scoring.robustZ(100, med, Scoring.scaledMad(v, med)), 97 / Scoring.MAD_SCALE, 1e-9, "z");
            return "median 3, MAD 1, extreme value gets large z";
        }));
        cs.add(new Case("outlier_score_saturates", "outlier", () -> {
            close(Scoring.outlierScore(3, 6), 0.5, 1e-12, "half cap");
            close(Scoring.outlierScore(60, 6), 1, 0, "saturated");
            return "outlier score is min(z, cap)/cap";
        }));
        cs.add(new Case("priority_monotonic", "ranking", () -> {
            RankingSpec s = RankingSpec.defaults("test");
            double base = Scoring.priority(s, 0.2, 0.5, 0.1, false);
            require(Scoring.priority(s, 0.4, 0.5, 0.1, false) > base, "higher entropy must raise priority");
            require(Scoring.priority(s, 0.2, 0.3, 0.1, false) > base, "smaller margin must raise priority");
            require(Scoring.priority(s, 0.2, 0.5, 0.3, false) > base, "higher outlier must raise priority");
            require(Scoring.priority(s, 0.2, 0.5, 0.1, true) > base, "label conflict must raise priority");
            return "priority increases with each review signal";
        }));
        cs.add(new Case("rank_order_tiebreak", "ranking", () -> {
            int[] ord = Analysis.rankOrder(new double[]{0.5, 0.9, 0.5, 0.1}, new String[]{"b", "x", "a", "c"});
            require(Arrays.equals(ord, new int[]{1, 2, 0, 3}), "expected [1,2,0,3] got " + Arrays.toString(ord));
            return "sorted by priority desc, ties broken by gravityspy_id";
        }));
        cs.add(new Case("default_spec_valid", "ranking", () -> {
            List<String> errs = RankingSpec.defaults("test").validate();
            require(errs.isEmpty(), "default spec invalid: " + errs);
            return "default ranking spec passes validation";
        }));
        return cs;
    }

    /** Data-level cases against the frozen run directory. */
    static List<Case> dataCases(Path runDir) {
        List<Case> cs = new ArrayList<>();
        Object[] state = new Object[2]; // spec, analysis
        cs.add(new Case("deterministic_output", "determinism", () -> {
            JsonNode ds = Json.read(runDir.resolve("dataset.json"));
            JsonNode specJson = Json.read(runDir.resolve("spec.json"));
            RankingSpec spec = RankingSpec.fromToolInput(specJson, specJson.path("author").asText());
            GlitchTable t = GlitchTable.loadClassified(Path.of(ds.path("path").asText()));
            Analysis a1 = Analysis.run(t, spec);
            Analysis a2 = Analysis.run(t, spec);
            String h1 = Dataset.sha256(a1.scoresCsv().getBytes(StandardCharsets.UTF_8));
            String h2 = Dataset.sha256(a2.scoresCsv().getBytes(StandardCharsets.UTF_8));
            require(h1.equals(h2), "two in-process runs differ");
            String frozen = Json.read(runDir.resolve("manifest.json")).path("files").path("scores.csv").asText();
            require(h1.equals(frozen), "re-scored output " + h1.substring(0, 12) + " differs from frozen scores.csv " + frozen.substring(0, 12));
            state[0] = spec;
            state[1] = a1;
            return "two independent re-scorings match the frozen scores.csv (sha256 " + h1.substring(0, 12) + "...)";
        }));
        cs.add(new Case("ranked_sorted_and_contiguous", "ranking", () -> {
            JsonNode ranked = Json.read(runDir.resolve("ranked.json"));
            double prev = Double.POSITIVE_INFINITY;
            int r = 1;
            for (JsonNode e : ranked.path("events")) {
                require(e.path("rank").asInt() == r, "rank gap at " + r);
                double p = e.path("scores").path("priority").asDouble();
                require(p <= prev, "priority not sorted at rank " + r);
                prev = p;
                r++;
            }
            require(r - 1 == ranked.path("top_n").asInt(), "wrong number of ranked events");
            return (r - 1) + " ranked events are contiguous and sorted";
        }));
        cs.add(new Case("entropy_and_margin_in_range", "entropy", () -> {
            Analysis a = (Analysis) state[1];
            require(a != null, "requires deterministic_output to have loaded the data");
            for (int i = 0; i < a.n; i++) {
                require(a.entropyNorm[i] >= -1e-12 && a.entropyNorm[i] <= 1 + 1e-12, "entropy_norm out of [0,1] for " + a.ids[i]);
                require(a.margin[i] >= 0 && a.margin[i] <= 1, "margin out of [0,1] for " + a.ids[i]);
            }
            return "all " + a.n + " events have entropy_norm and margin in [0,1]";
        }));
        cs.add(new Case("ambiguous_events_rank_above_confident", "ranking", () -> {
            Analysis a = (Analysis) state[1];
            require(a != null, "requires deterministic_output to have loaded the data");
            double ambMean = 0, confMean = 0;
            int na = 0, nc = 0;
            for (int i = 0; i < a.n; i++) {
                if (a.isAmbiguous(i)) { ambMean += a.rankOf(i); na++; }
                else if (a.margin[i] > 0.9) { confMean += a.rankOf(i); nc++; }
            }
            require(na > 0 && nc > 0, "need both ambiguous and confident events");
            ambMean /= na;
            confMean /= nc;
            require(ambMean < confMean, "ambiguous events should rank higher on average");
            return String.format(Locale.ROOT, "mean rank: ambiguous %.0f vs confident %.0f", ambMean, confMean);
        }));
        return cs;
    }

    public static Map<String, Object> run(Path runDir) {
        List<Case> all = new ArrayList<>(unitCases());
        all.addAll(dataCases(runDir));
        List<Map<String, Object>> results = new ArrayList<>();
        int passed = 0;
        for (Case c : all) {
            long t0 = System.nanoTime();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", c.name());
            r.put("area", c.area());
            try {
                r.put("detail", c.check().run());
                r.put("passed", true);
                passed++;
            } catch (Throwable e) {
                r.put("detail", e.getClass().getSimpleName() + ": " + e.getMessage());
                r.put("passed", false);
            }
            r.put("ms", (System.nanoTime() - t0) / 1_000_000);
            results.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", all.size());
        out.put("passed", passed);
        out.put("failed", all.size() - passed);
        out.put("tests", results);
        return out;
    }
}
