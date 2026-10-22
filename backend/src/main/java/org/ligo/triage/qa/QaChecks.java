package org.ligo.triage.qa;

import com.fasterxml.jackson.databind.JsonNode;
import org.ligo.triage.Json;
import org.ligo.triage.data.Dataset;
import org.ligo.triage.data.GlitchTable;
import org.ligo.triage.data.SchemaValidator;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

/**
 * Independent, read-only checks over the Developer's frozen run directory and the raw dataset.
 * QA re-loads the raw CSV itself (after verifying its checksum) instead of trusting in-memory developer state.
 */
public final class QaChecks {
    public static final String PASS = "pass", WARN = "warn", FAIL = "fail";

    private final Path runDir;
    private GlitchTable raw;

    public QaChecks(Path runDir) { this.runDir = runDir; }

    private GlitchTable raw() throws IOException {
        if (raw == null) {
            JsonNode ds = Json.read(runDir.resolve("dataset.json"));
            Path csv = Path.of(ds.path("path").asText());
            String sha = Dataset.digest(csv, "SHA-256");
            if (!sha.equals(ds.path("sha256").asText()))
                throw new IOException("Raw dataset changed since the developer ran: sha256 " + sha);
            raw = GlitchTable.load(csv);
        }
        return raw;
    }

    private static Map<String, Object> result(String check, String status, String detail, long affected, List<String> examples) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("check", check);
        m.put("status", status);
        m.put("detail", detail);
        m.put("affected", affected);
        m.put("examples", examples);
        return m;
    }

    public Map<String, Object> probabilitySums() throws IOException {
        GlitchTable t = raw();
        double[][] p = t.probabilities();
        long bad = 0, unclassified = 0;
        double worst = 0;
        List<String> ex = new ArrayList<>();
        for (int i = 0; i < p.length; i++) {
            if (t.isUnclassified(i)) { unclassified++; continue; }
            double s = 0;
            for (double v : p[i]) s += v;
            double dev = Math.abs(s - 1);
            worst = Math.max(worst, Double.isNaN(dev) ? Double.POSITIVE_INFINITY : dev);
            if (!(dev <= SchemaValidator.SUM_TOLERANCE)) {
                bad++;
                if (ex.size() < 5) ex.add(t.raw(i, "gravityspy_id") + " sum=" + s);
            }
        }
        long classified = p.length - unclassified;
        return result("probability_sums", bad > 0 ? FAIL : unclassified > 0 ? WARN : PASS,
                String.format("%d of %d classified rows sum to 1 within %.0e; max deviation %.2e. %d rows are unclassified "
                                + "(all probabilities 0) and were excluded from scoring.", classified - bad, classified,
                        SchemaValidator.SUM_TOLERANCE, worst, unclassified), bad + unclassified, ex);
    }

    public Map<String, Object> missingValues() throws IOException {
        GlitchTable t = raw();
        long missing = 0, placeholderUrlEvents = 0;
        List<String> ex = new ArrayList<>();
        for (int i = 0; i < t.size(); i++) {
            boolean placeholder = false;
            for (String c : GlitchTable.REQUIRED_COLUMNS) {
                String v = t.raw(i, c);
                if (v.isEmpty() || v.equalsIgnoreCase("nan") || v.equals("?")) {
                    missing++;
                    if (c.startsWith("url")) placeholder = true;
                    if (ex.size() < 5) ex.add(t.raw(i, "gravityspy_id") + "." + c + "='" + v + "'");
                }
            }
            if (placeholder) placeholderUrlEvents++;
        }
        long outputGaps = 0;
        JsonNode ranked = Json.read(runDir.resolve("ranked.json"));
        for (JsonNode e : ranked.path("events")) {
            for (String f : List.of("gravityspy_id", "ml_label", "explanation", "scores", "spectrograms", "top_classes"))
                if (e.path(f).isMissingNode() || e.path(f).isNull() || (e.path(f).isTextual() && e.path(f).asText().isEmpty())) {
                    outputGaps++;
                    if (ex.size() < 5) ex.add("ranked:" + e.path("gravityspy_id").asText() + "." + f);
                }
            for (Iterator<Map.Entry<String, JsonNode>> it = e.path("scores").fields(); it.hasNext(); ) {
                JsonNode v = it.next().getValue();
                if (v.isNumber() && !Double.isFinite(v.asDouble())) outputGaps++;
            }
        }
        long total = missing + outputGaps;
        // Gaps in our own output are failures; gaps in the published dataset are recorded as warnings.
        String status = outputGaps > 0 ? FAIL : missing > 0 ? WARN : PASS;
        return result("missing_values", status,
                String.format("%d empty/NaN/'?' cells across %d required raw columns (%d events have placeholder '?' "
                                + "spectrogram URLs); %d gaps in ranked output fields.",
                        missing, GlitchTable.REQUIRED_COLUMNS.size(), placeholderUrlEvents, outputGaps), total, ex);
    }

    public Map<String, Object> labelArgmax() throws IOException {
        GlitchTable t = raw();
        double[][] p = t.probabilities();
        long disagree = 0, confMismatch = 0, extended = 0;
        List<String> ex = new ArrayList<>();
        int labelCol = t.col("ml_label"), confCol = t.col("ml_confidence");
        int scored = 0;
        for (int i = 0; i < p.length; i++) {
            if (t.isUnclassified(i)) continue;
            if (GlitchTable.EXTENDED_LABELS.contains(t.raw(i, labelCol))) { extended++; continue; }
            scored++;
            int a = 0;
            for (int k = 1; k < p[i].length; k++) if (p[i][k] > p[i][a]) a = k;
            if (!GlitchTable.CLASSES.get(a).equals(t.raw(i, labelCol))) {
                disagree++;
                if (ex.size() < 5) ex.add(t.raw(i, "gravityspy_id") + ": ml_label=" + t.raw(i, labelCol)
                        + " argmax=" + GlitchTable.CLASSES.get(a));
            }
            if (Math.abs(p[i][a] - t.number(i, confCol)) > 1e-6) confMismatch++;
        }
        boolean differentModel = scored > 0 && confMismatch / (double) scored > GlitchTable.MODEL_MISMATCH_THRESHOLD;
        // Sporadic confidence mismatches mean corrupted rows; systematic ones mean labels from another model version.
        String status = confMismatch > 0 && !differentModel ? FAIL : (disagree > 0 || extended > 0 || differentModel) ? WARN : PASS;
        String detail = String.format("%d of %d events (%.2f%%) have an ml_label that differs from the argmax class; "
                        + "ml_confidence equals the max probability in %d of %d events.",
                disagree, scored, 100.0 * disagree / Math.max(1, scored), scored - confMismatch, scored);
        if (extended > 0) detail += String.format(" %d events carry newer labels %s with no published probability column.", extended, GlitchTable.EXTENDED_LABELS);
        detail += differentModel
                ? " The mismatch is systematic: ml_label/ml_confidence come from a different model version than the published probabilities, so the ranking disables the label-conflict bonus for this dataset."
                : " Disagreements are a property of the published dataset; the ranking flags them rather than correcting them.";
        return result("label_argmax_agreement", status, detail, disagree + confMismatch + extended, ex);
    }

    public Map<String, Object> imageUrls(int maxEvents) throws IOException {
        JsonNode ranked = Json.read(runDir.resolve("ranked.json"));
        List<String> urls = new ArrayList<>();
        int events = 0;
        for (JsonNode e : ranked.path("events")) {
            if (events++ >= maxEvents) break;
            for (JsonNode s : e.path("spectrograms")) urls.add(s.path("url").asText());
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        Semaphore gate = new Semaphore(12);
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (String url : urls) {
            if (!url.startsWith("http")) {
                futures.add(CompletableFuture.completedFuture("placeholder URL '" + url + "' (no spectrogram published)"));
                continue;
            }
            futures.add(CompletableFuture.supplyAsync(() -> {
                gate.acquireUninterruptibly();
                try {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
                    HttpResponse<Void> r = http.send(req, HttpResponse.BodyHandlers.discarding());
                    String type = r.headers().firstValue("content-type").orElse("");
                    return r.statusCode() == 200 && type.startsWith("image/") ? null : url + " -> HTTP " + r.statusCode() + " " + type;
                } catch (Exception ex) {
                    return url + " -> " + ex.getClass().getSimpleName();
                } finally {
                    gate.release();
                }
            }));
        }
        List<String> broken = futures.stream().map(CompletableFuture::join).filter(s -> s != null).toList();
        String status = broken.isEmpty() ? PASS : broken.size() * 4 > urls.size() ? FAIL : WARN;
        return result("spectrogram_urls", status,
                String.format("%d of %d spectrogram URLs for the top %d events returned HTTP 200 with an image content type.",
                        urls.size() - broken.size(), urls.size(), Math.min(events, maxEvents)),
                broken.size(), broken.stream().limit(5).toList());
    }

    public Map<String, Object> outputIntegrity() throws IOException {
        JsonNode manifest = Json.read(runDir.resolve("manifest.json"));
        List<String> problems = new ArrayList<>();
        int files = 0;
        for (Iterator<Map.Entry<String, JsonNode>> it = manifest.path("files").fields(); it.hasNext(); ) {
            var en = it.next();
            files++;
            Path f = runDir.resolve(en.getKey());
            if (!Files.exists(f)) { problems.add(en.getKey() + " missing"); continue; }
            String sha = Dataset.digest(f, "SHA-256");
            if (!sha.equals(en.getValue().asText())) problems.add(en.getKey() + " checksum changed");
            if (Files.isWritable(f)) problems.add(en.getKey() + " is writable (expected read-only)");
        }
        return result("output_integrity", problems.isEmpty() ? PASS : FAIL,
                String.format("%d frozen developer files checked against manifest checksums and read-only permissions.", files),
                problems.size(), problems);
    }

    public Map<String, Object> rankingConsistency() throws IOException {
        JsonNode ranked = Json.read(runDir.resolve("ranked.json"));
        List<String> problems = new ArrayList<>();
        List<String[]> csvTop = new ArrayList<>();
        int topN = ranked.path("top_n").asInt();
        try (BufferedReader r = Files.newBufferedReader(runDir.resolve("scores.csv"))) {
            String header = r.readLine();
            List<String> cols = List.of(header.split(","));
            int idCol = cols.indexOf("gravityspy_id"), prCol = cols.indexOf("priority");
            for (String line; (line = r.readLine()) != null && csvTop.size() < topN; ) {
                String[] f = line.split(",");
                csvTop.add(new String[]{f[idCol], f[prCol]});
            }
        }
        double prev = Double.POSITIVE_INFINITY;
        int expectedRank = 1;
        for (JsonNode e : ranked.path("events")) {
            String id = e.path("gravityspy_id").asText();
            if (e.path("rank").asInt() != expectedRank) problems.add(id + " has rank " + e.path("rank").asInt() + ", expected " + expectedRank);
            double p = e.path("scores").path("priority").asDouble();
            if (p > prev + 1e-9) problems.add(id + " priority increases down the list");
            prev = p;
            if (e.path("explanation").asText().length() < 40) problems.add(id + " lacks a ranking explanation");
            if (e.path("spectrograms").size() != 4) problems.add(id + " does not have 4 spectrogram views");
            if (expectedRank <= csvTop.size()) {
                String[] row = csvTop.get(expectedRank - 1);
                if (!row[0].equals(id)) problems.add("rank " + expectedRank + " differs between ranked.json and scores.csv");
                else if (Math.abs(Double.parseDouble(row[1]) - p) > 1e-3) problems.add(id + " priority differs between ranked.json and scores.csv");
            }
            expectedRank++;
        }
        if (expectedRank - 1 != topN) problems.add("ranked.json lists " + (expectedRank - 1) + " events but top_n is " + topN);
        return result("ranking_consistency", problems.isEmpty() ? PASS : FAIL,
                String.format("%d ranked events checked for contiguous ranks, non-increasing priority, explanations, "
                        + "4 spectrogram views and agreement with scores.csv.", expectedRank - 1),
                problems.size(), problems.stream().limit(5).toList());
    }

    /** Mechanical verdict: any failing check fails QA; any warning downgrades a pass. */
    public static String verdict(List<Map<String, Object>> checks) {
        boolean fail = checks.stream().anyMatch(c -> FAIL.equals(c.get("status")));
        boolean warn = checks.stream().anyMatch(c -> WARN.equals(c.get("status")));
        return fail ? "failed" : warn ? "passed_with_warnings" : "passed";
    }
}
