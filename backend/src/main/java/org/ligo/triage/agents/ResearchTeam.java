package org.ligo.triage.agents;

import com.anthropic.models.messages.OutputConfig;
import com.fasterxml.jackson.databind.JsonNode;
import org.ligo.triage.Json;
import org.ligo.triage.Workspace;
import org.ligo.triage.analysis.Analysis;
import org.ligo.triage.analysis.RankingSpec;
import org.ligo.triage.analysis.Scoring;
import org.ligo.triage.data.Dataset;
import org.ligo.triage.data.GlitchTable;
import org.ligo.triage.data.SchemaValidator;
import org.ligo.triage.qa.QaChecks;
import org.ligo.triage.server.RunLog;
import org.ligo.triage.server.StatusBus;
import org.ligo.triage.testing.SelfTests;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The five-role research team and the order in which it works:
 * <pre>
 *   Developer (ingest) → Researcher (spec) → Developer (score, stabilise, freeze) → Tester ∥ QA → Reporter
 * </pre>
 * Each role is an agent with its own tools. After every stage the orchestrator verifies that the role's
 * required deliverable exists and completes any missing mechanical step itself, recording a warning.
 */
public final class ResearchTeam {
    public static final String RESEARCHER = "Researcher", DEVELOPER = "Developer", TESTER = "Tester",
            QA = "QA", REPORTER = "Reporter";

    static final List<String[]> STAGES = List.of(
            new String[]{DEVELOPER, "ingest", "Download, checksum and validate the dataset"},
            new String[]{RESEARCHER, "spec", "Define the ranking formula and limitations"},
            new String[]{DEVELOPER, "analysis", "Score, rank, bootstrap and freeze outputs"},
            new String[]{TESTER, "tests", "Test entropy, margins, ranking and determinism"},
            new String[]{QA, "qa", "Independent read-only QA of frozen output"},
            new String[]{REPORTER, "summary", "Write the dashboard summary"});

    private static final String TEAM_CONTEXT =
            "You are part of a five-role agent team (Researcher, Developer, Tester, QA, Reporter) triaging public "
                    + "Gravity Spy machine-learning classifications of LIGO glitches. This run covers dataset %s "
                    + "(detector %s, observing run %s). "
                    + "The question: which events most deserve human review because the classifier is uncertain, two "
                    + "classes compete closely, or the event's metadata is unusual for its predicted class? "
                    + "Heavy computation runs in Java on a Mac mini fog server; you act only through your tools. "
                    + "Do not claim discoveries; this is triage, not ground truth. Keep your final message to 2-4 plain sentences.";

    private final Workspace ws;
    private final StatusBus bus;
    private final RunLog runLog;
    private final AgentRunner runner;
    private final TeamFiles files;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Per-run state
    private String dataset = Dataset.DEFAULT;
    private String runId;
    private volatile String lastStatus;
    private Path runDir;
    private Map<String, Object> datasetInfo, schema, testsSuite, summary;
    private GlitchTable table;
    private RankingSpec spec;
    private Analysis analysis;
    private Map<String, Object> stabilitySummary, testReport, qaReport;
    private boolean frozen;
    private final Map<String, Map<String, Object>> qaChecks = new LinkedHashMap<>();

    public ResearchTeam(Workspace ws, StatusBus bus, RunLog runLog, AgentRunner runner, TeamFiles files) {
        this.ws = ws;
        this.bus = bus;
        this.runLog = runLog;
        this.runner = runner;
        this.files = files;
    }

    public boolean isRunning() { return running.get(); }

    public boolean run() { return run(Dataset.DEFAULT); }

    /** Runs the whole team once on one dataset. Returns true when tests pass and QA did not fail. */
    public boolean run(String datasetName) {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("A run is already in progress");
        try {
            reset();
            dataset = datasetName;
            runId = Workspace.newRunId(dataset);
            runDir = ws.runDir(runId);
            Files.createDirectories(runDir);
            bus.setActivityLog(runDir.resolve("activity.jsonl"));
            runLog.startRun(runId, dataset, runner.mode(), runner.model(), STAGES);

            if (!stage(developerIngest())) return finish(false, "failed");
            stage(researcherSpec());
            if (!stage(developerAnalysis())) return finish(false, "failed");

            CompletableFuture<Boolean> tests = CompletableFuture.supplyAsync(() -> stage(testerTask()));
            CompletableFuture<Boolean> qa = CompletableFuture.supplyAsync(() -> stage(qaTask()));
            tests.join();
            qa.join();
            stage(reporterTask());

            boolean testsOk = testReport != null && "pass".equals(testReport.get("verdict"));
            boolean qaOk = qaReport != null && !"failed".equals(qaReport.get("status"));
            return finish(testsOk && qaOk, testsOk && qaOk ? "complete" : "complete_with_issues");
        } catch (Exception e) {
            bus.publish("log", null, "Run aborted: " + e, null);
            return finish(false, "failed");
        } finally {
            running.set(false);
        }
    }

    private boolean finish(boolean ok, String status) {
        if (runId != null) runLog.finishRun(status);
        lastStatus = status;
        return ok;
    }

    public String lastRunId() { return runId; }
    public String lastStatus() { return lastStatus; }

    private void reset() {
        datasetInfo = schema = testsSuite = summary = stabilitySummary = testReport = qaReport = null;
        table = null;
        spec = null;
        analysis = null;
        frozen = false;
        qaChecks.clear();
    }

    /** A stage = one agent task plus a post-condition check the orchestrator enforces. */
    private record Stage(AgentRunner.Task task, PostCheck post) {}

    @FunctionalInterface
    private interface PostCheck {
        /** Returns warnings; throws if the stage cannot be completed. */
        List<String> verify() throws Exception;
    }

    private boolean stage(Stage s) {
        String role = s.task().role(), phase = s.task().phase();
        runLog.stageStarted(role, phase);
        Map<String, Object> rec = runner.run(s.task());
        List<String> warnings = new ArrayList<>();
        String status;
        try {
            warnings.addAll(s.post().verify());
            status = "error".equals(rec.get("status")) ? "failed" : warnings.isEmpty() ? "complete" : "complete_with_warnings";
        } catch (Exception e) {
            warnings.add(e.getMessage());
            status = "failed";
        }
        for (String w : warnings) bus.publish("agent", role, "⚠ " + w, Map.of("kind", "warning"));
        rec.put("warnings", warnings);
        runLog.stageFinished(role, phase, status, rec);
        return !"failed".equals(status);
    }

    private String system(String roleBrief) {
        return String.format(TEAM_CONTEXT, dataset, Dataset.detector(dataset), Dataset.observingRun(dataset)) + "\n\n" + roleBrief;
    }

    // ======================================================================== Developer: ingest

    private Stage developerIngest() {
        List<AgentTool> tools = List.of(
                new AgentTool("download_dataset",
                        "Ensure the Gravity Spy " + dataset + " CSV (Zenodo record " + Dataset.ZENODO_RECORD + ") is downloaded and cached; "
                                + "returns path, size, SHA-256/MD5 and whether they match the pinned published checksums.",
                        Map.of("force", AgentTool.prop("boolean", "Re-download even if cached (default false)")),
                        List.of(),
                        in -> {
                            datasetInfo = Dataset.ensureCached(dataset, ws.datasetCsv(dataset), in.path("force").asBoolean(false));
                            return datasetInfo;
                        }),
                AgentTool.of("validate_schema",
                        "Load the cached CSV and validate required columns, 22 probability columns in [0,1] summing to 1, "
                                + "known labels, positive metadata and unique ids.",
                        in -> {
                            if (datasetInfo == null) throw new IllegalStateException("Call download_dataset first");
                            table = GlitchTable.load(ws.datasetCsv(dataset));
                            schema = SchemaValidator.validate(table);
                            table = table.classifiedOnly();
                            return schema;
                        }));
        AgentRunner.Task task = new AgentRunner.Task(DEVELOPER, "ingest",
                system("You are the Developer. In this phase you own ingestion: make sure the dataset is cached with a "
                        + "verified checksum, then validate its schema. If validation fails, report it plainly; never work around it."),
                "Ingest the Gravity Spy " + dataset + " dataset: download/cache it, then validate the schema. Report the checksum status and validation result.",
                tools, OutputConfig.Effort.LOW, 6,
                t -> {
                    JsonNode d = t.call("download_dataset", Map.of());
                    JsonNode v = t.call("validate_schema", Map.of());
                    return String.format(Locale.ROOT, "Dataset cached (%,d bytes, sha256 %s…, %s pinned checksum). Schema %s: %d rows, %d columns, %d errors, %d warnings.",
                            d.path("bytes").asLong(), d.path("sha256").asText().substring(0, 12),
                            d.path("checksum_matches_pinned").asBoolean() ? "matches" : "DOES NOT match",
                            v.path("valid").asBoolean() ? "valid" : "INVALID", v.path("rows").asInt(), v.path("columns").asInt(),
                            v.path("errors").size(), v.path("warnings").size());
                });
        return new Stage(task, () -> {
            List<String> w = new ArrayList<>();
            if (datasetInfo == null) { datasetInfo = Dataset.ensureCached(dataset, ws.datasetCsv(dataset), false); w.add("Orchestrator downloaded the dataset (agent did not)"); }
            if (schema == null) { table = GlitchTable.load(ws.datasetCsv(dataset)); schema = SchemaValidator.validate(table); table = table.classifiedOnly(); w.add("Orchestrator validated the schema (agent did not)"); }
            if (!Boolean.TRUE.equals(datasetInfo.get("checksum_matches_pinned"))) w.add("Dataset checksum differs from the pinned published version");
            if (!Boolean.TRUE.equals(schema.get("valid"))) throw new IllegalStateException("Schema validation failed: " + schema.get("errors"));
            return w;
        });
    }

    // ======================================================================== Researcher

    private Map<String, Object> datasetProfile() {
        int n = table.size();
        double[][] p = table.probabilities();
        double[] margins = new double[n], ent = new double[n], conf = table.column("ml_confidence");
        String[] labels = table.textColumn("ml_label");
        int conflicts = 0;
        for (int i = 0; i < n; i++) {
            margins[i] = Scoring.margin(p[i]);
            ent[i] = Scoring.normalizedEntropy(p[i]);
            if (!GlitchTable.CLASSES.get(Scoring.topTwo(p[i])[0]).equals(labels[i])) conflicts++;
        }
        Map<String, Integer> counts = new TreeMap<>();
        for (String l : labels) counts.merge(l, 1, Integer::sum);
        Map<String, Object> feats = new LinkedHashMap<>();
        for (String f : GlitchTable.METADATA_FEATURES) {
            double[] v = Arrays.stream(table.column(f)).filter(x -> x > 0).map(Math::log10).toArray();
            feats.put(f, Map.of("log10_p05", r3(quantile(v, .05)), "log10_p50", r3(quantile(v, .5)), "log10_p95", r3(quantile(v, .95)),
                    "distinct_values", Arrays.stream(v).distinct().count()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", n);
        out.put("predicted_label_counts", counts);
        out.put("ml_confidence_quantiles", Map.of("p10", r3(quantile(conf, .1)), "p25", r3(quantile(conf, .25)), "p50", r3(quantile(conf, .5))));
        out.put("top_two_margin_quantiles", Map.of("p05", r3(quantile(margins, .05)), "p10", r3(quantile(margins, .1)), "p25", r3(quantile(margins, .25)), "p50", r3(quantile(margins, .5))));
        Map<String, Long> below = new LinkedHashMap<>();
        for (double th : new double[]{0.05, 0.1, 0.2, 0.3}) below.put("margin_below_" + th, Arrays.stream(margins).filter(m -> m < th).count());
        out.put("events_by_margin_threshold", below);
        out.put("normalized_entropy_quantiles", Map.of("p50", r3(quantile(ent, .5)), "p90", r3(quantile(ent, .9)), "p99", r3(quantile(ent, .99))));
        out.put("label_argmax_conflicts", conflicts);
        out.put("metadata_features", feats);
        out.put("spec_parameter_ranges", Map.of(
                "weights", "entropy, margin, outlier each in [0,1], summing to 1",
                "label_conflict_bonus", "[0, 0.3]", "outlier_z_cap", "[2, 20]",
                "ambiguous_margin_threshold", "(0, 1)", "top_n", "[10, 100]", "min_class_size", "[5, 500]",
                "allowed_outlier_features", GlitchTable.METADATA_FEATURES));
        return out;
    }

    private Stage researcherSpec() {
        String author = RESEARCHER + " (" + runner.mode() + ")";
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("weights", Map.of("type", "object", "description", "Weights for the three terms; must sum to 1",
                "properties", Map.of("entropy", AgentTool.prop("number", "weight on normalised entropy"),
                        "margin", AgentTool.prop("number", "weight on 1 - (p1 - p2)"),
                        "outlier", AgentTool.prop("number", "weight on metadata outlier score")),
                "required", List.of("entropy", "margin", "outlier")));
        props.put("label_conflict_bonus", AgentTool.prop("number", "Bonus added when ml_label differs from the argmax class, in [0, 0.3]"));
        props.put("outlier_features", AgentTool.arrayProp("Metadata features for the outlier score",
                AgentTool.enumProp("feature", GlitchTable.METADATA_FEATURES)));
        props.put("outlier_z_cap", AgentTool.prop("number", "RMS robust-z at which the outlier score saturates to 1"));
        props.put("ambiguous_margin_threshold", AgentTool.prop("number", "An event is 'ambiguous' when p1 - p2 is below this"));
        props.put("top_n", AgentTool.prop("integer", "How many events go on the human review list"));
        props.put("min_class_size", AgentTool.prop("integer", "Classes smaller than this use global metadata baselines"));
        props.put("rationale", AgentTool.prop("string", "Why these choices, grounded in the profile"));
        props.put("limitations", AgentTool.arrayProp("Honest limitations of this ranking", AgentTool.prop("string", "limitation")));
        List<AgentTool> tools = List.of(
                AgentTool.of("inspect_dataset_profile",
                        "Summary statistics of the validated dataset: label counts, confidence/margin/entropy quantiles, "
                                + "events below candidate margin thresholds, label/argmax conflicts, metadata feature ranges and allowed spec parameter ranges.",
                        in -> datasetProfile()),
                new AgentTool("submit_ranking_spec",
                        "Submit the review-priority formula: priority = w_entropy*H/ln(22) + w_margin*(1-(p1-p2)) + "
                                + "w_outlier*min(rms_z,cap)/cap + label_conflict_bonus*[ml_label != argmax]. Validated; fix and resubmit on error.",
                        props, List.of("weights", "outlier_features", "ambiguous_margin_threshold", "top_n", "rationale", "limitations"),
                        in -> {
                            RankingSpec s = RankingSpec.fromToolInput(in, author);
                            List<String> errs = s.validate();
                            if (!errs.isEmpty()) throw new IllegalArgumentException("Spec rejected: " + String.join("; ", errs));
                            spec = s;
                            return Map.of("accepted", true, "spec", s.toMap());
                        }));
        AgentRunner.Task task = new AgentRunner.Task(RESEARCHER, "spec",
                system("You are the Researcher. You define the ranking formula the Developer will implement and you state its "
                        + "limitations honestly. Inspect the dataset profile, choose weights and parameters you can justify from it, "
                        + "and submit the spec exactly once (resubmit only if it is rejected)."),
                "Define the review-priority ranking for this dataset. Inspect the profile, then submit_ranking_spec with a short rationale and at least three limitations.",
                tools, OutputConfig.Effort.MEDIUM, 6,
                t -> {
                    JsonNode prof = t.call("inspect_dataset_profile", Map.of());
                    RankingSpec d = RankingSpec.defaults(author);
                    Map<String, Object> in = new LinkedHashMap<>(d.toMap());
                    in.remove("formula");
                    in.remove("author");
                    t.call("submit_ranking_spec", in);
                    return String.format(Locale.ROOT, "Submitted the default spec: entropy %.2f, margin %.2f, outlier %.2f, label-conflict bonus %.2f; "
                                    + "ambiguous means margin < %.2f (%d events in the profile). %d limitations recorded.",
                            d.wEntropy(), d.wMargin(), d.wOutlier(), d.labelConflictBonus(), d.ambiguousMargin(),
                            prof.path("events_by_margin_threshold").path("margin_below_" + d.ambiguousMargin()).asLong(),
                            d.limitations().size());
                });
        return new Stage(task, () -> {
            if (spec != null) return List.of();
            spec = RankingSpec.defaults(RESEARCHER + " (orchestrator default)");
            return List.of("Researcher did not submit a valid spec; default spec applied");
        });
    }

    // ======================================================================== Developer: analysis

    private Map<String, Object> computeScores() {
        if (spec == null) throw new IllegalStateException("No ranking spec yet");
        long t0 = System.nanoTime();
        analysis = Analysis.run(table, spec);
        stabilitySummary = null;
        List<Map<String, Object>> top = new ArrayList<>();
        for (int r = 0; r < Math.min(5, analysis.n); r++) {
            int i = analysis.order[r];
            top.add(Map.of("rank", r + 1, "gravityspy_id", analysis.ids[i], "ml_label", analysis.labels[i],
                    "alternative", GlitchTable.CLASSES.get(analysis.top2[i]), "priority", Analysis.round(analysis.priority[i], 4)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events_scored", analysis.n);
        out.put("ambiguous_events", analysis.ambiguousCount());
        out.put("ambiguous_definition", "margin < " + spec.ambiguousMargin());
        out.put("top_5", top);
        out.put("seconds", Analysis.round((System.nanoTime() - t0) / 1e9, 3));
        out.put("threads", Runtime.getRuntime().availableProcessors());
        return out;
    }

    private Map<String, Object> freezeOutputs() throws IOException {
        if (analysis == null) throw new IllegalStateException("Call compute_scores first");
        if (frozen) throw new IllegalStateException("Outputs are already frozen for this run");
        Map<String, byte[]> outputs = new LinkedHashMap<>();
        outputs.put("dataset.json", Json.pretty(datasetInfo).getBytes(StandardCharsets.UTF_8));
        outputs.put("schema.json", Json.pretty(schema).getBytes(StandardCharsets.UTF_8));
        outputs.put("spec.json", Json.pretty(spec.toMap()).getBytes(StandardCharsets.UTF_8));
        outputs.put("scores.csv", analysis.scoresCsv().getBytes(StandardCharsets.UTF_8));
        outputs.put("ranked.json", Json.pretty(analysis.rankedJson(runId)).getBytes(StandardCharsets.UTF_8));
        outputs.put("findings.json", Json.pretty(analysis.findingsJson(runId)).getBytes(StandardCharsets.UTF_8));
        Map<String, String> sums = new LinkedHashMap<>();
        for (var e : outputs.entrySet()) {
            Path f = runDir.resolve(e.getKey());
            Files.write(f, e.getValue());
            sums.put(e.getKey(), Dataset.sha256(e.getValue()));
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("run_id", runId);
        manifest.put("frozen_at", Instant.now().toString());
        manifest.put("frozen_by", DEVELOPER);
        manifest.put("dataset_sha256", datasetInfo.get("sha256"));
        manifest.put("files", sums);
        Files.writeString(runDir.resolve("manifest.json"), Json.pretty(manifest));
        List<String> frozenFiles = new ArrayList<>(sums.keySet());
        frozenFiles.add("manifest.json");
        for (String name : frozenFiles)
            Files.setPosixFilePermissions(runDir.resolve(name), PosixFilePermissions.fromString("r--r--r--"));
        frozen = true;
        ws.markLatest(runId);
        runLog.outcome("dataset_sha256", datasetInfo.get("sha256"));
        runLog.outcome("ambiguous_events", analysis.ambiguousCount());
        bus.publish("state", DEVELOPER, "Developer output frozen; ranked list published", Map.of("kind", "frozen"));
        return Map.of("frozen", true, "run_dir", runDir.toString(), "files", sums);
    }

    private Stage developerAnalysis() {
        List<AgentTool> tools = List.of(
                AgentTool.of("compute_scores",
                        "Score all events with the Researcher's spec: entropy, top-two margin, per-class robust metadata outlier score, "
                                + "combined priority and ranking (parallel on all cores). Returns a summary and the top 5.",
                        in -> computeScores()),
                new AgentTool("run_rank_stability",
                        "Bootstrap rank-stability: resample the dataset with replacement, rebuild per-class metadata baselines and "
                                + "re-rank every event for each resample; reports how often each top-N event stays in the top N. CPU-heavy.",
                        Map.of("resamples", AgentTool.prop("integer", "Number of bootstrap resamples, 50-1000 (1000 is a good default on this machine)")),
                        List.of(),
                        in -> {
                            if (analysis == null) throw new IllegalStateException("Call compute_scores first");
                            if (frozen) throw new IllegalStateException("Outputs already frozen");
                            int b = Math.max(50, Math.min(1000, in.path("resamples").asInt(1000)));
                            stabilitySummary = analysis.runStability(b, 20151214L);
                            return stabilitySummary;
                        }),
                AgentTool.of("freeze_outputs",
                        "Write dataset.json, schema.json, spec.json, scores.csv, ranked.json and findings.json to the run directory, "
                                + "record SHA-256 checksums in manifest.json and make them read-only so QA inspects immutable output. Call once, last.",
                        in -> freezeOutputs()));
        AgentRunner.Task task = new AgentRunner.Task(DEVELOPER, "analysis",
                system("You are the Developer. In this phase you implement the Researcher's ranking: score every event, run the "
                        + "bootstrap rank-stability analysis, then freeze the outputs for QA. Freezing is final; do it last."),
                "The Researcher's spec is ready:\n" + Json.pretty(spec == null ? Map.of() : spec.toMap())
                        + "\n\nScore all events, run rank stability, then freeze the outputs. Report what you produced.",
                tools, OutputConfig.Effort.LOW, 8,
                t -> {
                    JsonNode s = t.call("compute_scores", Map.of());
                    JsonNode b = t.call("run_rank_stability", Map.of("resamples", 1000));
                    t.call("freeze_outputs", Map.of());
                    return String.format(Locale.ROOT, "Scored %,d events (%,d ambiguous, %s). Bootstrap: %d resamples in %.1fs on %d threads; mean top-N stability %.2f. Outputs frozen read-only with checksums.",
                            s.path("events_scored").asInt(), s.path("ambiguous_events").asInt(), s.path("ambiguous_definition").asText(),
                            b.path("resamples").asInt(), b.path("seconds").asDouble(), b.path("threads").asInt(), b.path("mean_top_n_stability").asDouble());
                });
        return new Stage(task, () -> {
            List<String> w = new ArrayList<>();
            if (analysis == null) { computeScores(); w.add("Orchestrator computed scores (agent did not)"); }
            if (stabilitySummary == null && !frozen) w.add("Rank stability was not run; rankings carry no stability estimate");
            if (!frozen) { freezeOutputs(); w.add("Orchestrator froze outputs (agent did not)"); }
            return w;
        });
    }

    // ======================================================================== Tester

    private Stage testerTask() {
        List<AgentTool> tools = List.of(
                AgentTool.of("run_test_suite",
                        "Run the automated suite: entropy, margin, robust-z, priority monotonicity, rank tie-breaking, spec validity, "
                                + "ranked-list ordering and a determinism check that re-scores the raw data and compares it with the frozen scores.csv.",
                        in -> {
                            testsSuite = SelfTests.run(runDir);
                            return testsSuite;
                        }),
                new AgentTool("submit_test_report",
                        "Record your verdict on the test results. A 'pass' verdict is overridden to 'fail' if any test failed.",
                        Map.of("verdict", AgentTool.enumProp("Overall verdict", List.of("pass", "fail")),
                                "summary", AgentTool.prop("string", "One or two sentences on what was tested and the outcome"),
                                "concerns", AgentTool.arrayProp("Coverage gaps or concerns worth recording", AgentTool.prop("string", "concern"))),
                        List.of("verdict", "summary"),
                        in -> writeTestReport(in.path("verdict").asText(), in.path("summary").asText(), strings(in.path("concerns")))));
        AgentRunner.Task task = new AgentRunner.Task(TESTER, "tests",
                system("You are the Tester. You verify the scoring math, the ranking and that output is deterministic. You cannot "
                        + "modify code or outputs. Run the suite, read any failures carefully, then submit an honest report."),
                "Run the automated test suite against the frozen output of run " + runId + " and submit your test report.",
                tools, OutputConfig.Effort.LOW, 5,
                t -> {
                    JsonNode r = t.call("run_test_suite", Map.of());
                    boolean ok = r.path("failed").asInt() == 0;
                    String s = String.format(Locale.ROOT, "%d of %d tests passed.", r.path("passed").asInt(), r.path("total").asInt());
                    t.call("submit_test_report", Map.of("verdict", ok ? "pass" : "fail", "summary", s, "concerns",
                            List.of("Tests cover scoring math and determinism, not whether the ranking matches expert judgement.")));
                    return s + (ok ? " Verdict: pass." : " Verdict: fail.");
                });
        return new Stage(task, () -> {
            List<String> w = new ArrayList<>();
            if (testsSuite == null) { testsSuite = SelfTests.run(runDir); w.add("Orchestrator ran the test suite (agent did not)"); }
            if (testReport == null) {
                writeTestReport(((int) testsSuite.get("failed")) == 0 ? "pass" : "fail", "Recorded by orchestrator.", List.of());
                w.add("Tester did not submit a report; mechanical verdict recorded");
            }
            if (!"pass".equals(testReport.get("verdict"))) w.add(testsSuite.get("failed") + " test(s) failed");
            return w;
        });
    }

    private Map<String, Object> writeTestReport(String verdict, String text, List<String> concerns) throws IOException {
        if (testsSuite == null) throw new IllegalStateException("Run the test suite first");
        int failed = (int) testsSuite.get("failed");
        Map<String, Object> rep = new LinkedHashMap<>();
        rep.put("run_id", runId);
        rep.put("verdict", failed > 0 ? "fail" : verdict);
        rep.put("agent_verdict", verdict);
        rep.put("summary", text);
        rep.put("concerns", concerns);
        rep.putAll(testsSuite);
        rep.put("tested_at", Instant.now().toString());
        Json.write(runDir.resolve("tests.json"), rep);
        testReport = rep;
        runLog.outcome("tests", Map.of("verdict", rep.get("verdict"), "passed", testsSuite.get("passed"), "total", testsSuite.get("total")));
        return Map.of("recorded", true, "verdict", rep.get("verdict"));
    }

    // ======================================================================== QA

    private Stage qaTask() {
        QaChecks qa = new QaChecks(runDir);
        List<AgentTool> tools = new ArrayList<>(List.of(
                AgentTool.of("check_probability_sums", "Verify each raw event's 22 class probabilities sum to 1.",
                        in -> qaCheck("probability_sums", qa::probabilitySums)),
                AgentTool.of("check_missing_values", "Find empty/NaN cells in required raw columns and gaps in ranked output fields.",
                        in -> qaCheck("missing_values", qa::missingValues)),
                AgentTool.of("check_label_argmax", "Check that ml_label equals the argmax class and ml_confidence equals the max probability.",
                        in -> qaCheck("label_argmax_agreement", qa::labelArgmax)),
                new AgentTool("check_image_urls", "HEAD-request the four spectrogram URLs of the top ranked events; report broken links.",
                        Map.of("max_events", AgentTool.prop("integer", "How many top events to check (default: all ranked)")), List.of(),
                        in -> qaCheck("spectrogram_urls", () -> qa.imageUrls(in.path("max_events").asInt(spec.topN())))),
                AgentTool.of("verify_output_integrity", "Confirm the frozen developer files still match manifest checksums and are read-only.",
                        in -> qaCheck("output_integrity", qa::outputIntegrity)),
                AgentTool.of("check_ranking_consistency", "Check ranks are contiguous, priorities non-increasing, every event has an explanation and 4 spectrograms, and ranked.json agrees with scores.csv.",
                        in -> qaCheck("ranking_consistency", qa::rankingConsistency))));
        tools.add(new AgentTool("submit_qa_report",
                "Record the QA report. The status is computed from the check results (any fail → failed, any warn → passed_with_warnings); your summary and issues are recorded alongside it.",
                Map.of("summary", AgentTool.prop("string", "Plain-language assessment for the team"),
                        "issues", AgentTool.arrayProp("Problems found", Map.of("type", "object", "properties", Map.of(
                                "severity", AgentTool.enumProp("severity", List.of("info", "warning", "error")),
                                "check", AgentTool.prop("string", "check name"),
                                "detail", AgentTool.prop("string", "what is wrong and why it matters")),
                                "required", List.of("severity", "check", "detail")))),
                List.of("summary"),
                in -> writeQaReport(qa, in.path("summary").asText(), Json.MAPPER.convertValue(in.path("issues"), List.class))));
        AgentRunner.Task task = new AgentRunner.Task(QA, "qa",
                system("You are the QA agent. You independently inspect the Developer's frozen, read-only output and the raw dataset. "
                        + "You cannot modify anything. Run every check, then submit a QA report that states real problems plainly, "
                        + "neither downplaying nor exaggerating them, and says what each means for the ranking."),
                "Run all six QA checks on the frozen output, then submit your QA report.",
                tools, OutputConfig.Effort.LOW, 8,
                t -> {
                    List<String> issues = new ArrayList<>();
                    List<Map<String, Object>> issueObjs = new ArrayList<>();
                    for (String check : List.of("check_probability_sums", "check_missing_values", "check_label_argmax",
                            "check_image_urls", "verify_output_integrity", "check_ranking_consistency")) {
                        JsonNode r = t.call(check, Map.of());
                        if (!QaChecks.PASS.equals(r.path("status").asText())) {
                            issues.add(r.path("check").asText());
                            issueObjs.add(Map.of("severity", QaChecks.FAIL.equals(r.path("status").asText()) ? "error" : "warning",
                                    "check", r.path("check").asText(), "detail", r.path("detail").asText()));
                        }
                    }
                    String s = issues.isEmpty() ? "All six checks passed." : "Checks needing attention: " + String.join(", ", issues) + ".";
                    JsonNode rep = t.call("submit_qa_report", Map.of("summary", s, "issues", issueObjs));
                    return "QA " + rep.path("status").asText() + ". " + s;
                });
        return new Stage(task, () -> {
            List<String> w = new ArrayList<>();
            if (qaReport == null) {
                writeQaReport(qa, "Recorded by orchestrator; the QA agent did not submit a report.", List.of());
                w.add("QA agent did not submit a report; mechanical verdict recorded");
            }
            if ("failed".equals(qaReport.get("status"))) w.add("QA failed");
            return w;
        });
    }

    @FunctionalInterface
    private interface CheckFn { Map<String, Object> run() throws Exception; }

    private Map<String, Object> qaCheck(String name, CheckFn fn) throws Exception {
        Map<String, Object> r = fn.run();
        synchronized (qaChecks) { qaChecks.put(name, r); }
        return r;
    }

    private synchronized Map<String, Object> writeQaReport(QaChecks qa, String text, List<?> issues) throws Exception {
        Map<String, CheckFn> all = new LinkedHashMap<>();
        all.put("probability_sums", qa::probabilitySums);
        all.put("missing_values", qa::missingValues);
        all.put("label_argmax_agreement", qa::labelArgmax);
        all.put("spectrogram_urls", () -> qa.imageUrls(spec.topN()));
        all.put("output_integrity", qa::outputIntegrity);
        all.put("ranking_consistency", qa::rankingConsistency);
        List<String> ranByOrchestrator = new ArrayList<>();
        for (var e : all.entrySet())
            if (!qaChecks.containsKey(e.getKey())) { qaCheck(e.getKey(), e.getValue()); ranByOrchestrator.add(e.getKey()); }
        List<Map<String, Object>> checks = all.keySet().stream().map(qaChecks::get).toList();
        Map<String, Object> rep = new LinkedHashMap<>();
        rep.put("run_id", runId);
        rep.put("status", QaChecks.verdict(checks));
        rep.put("summary", text);
        rep.put("issues", issues == null ? List.of() : issues);
        rep.put("checks", checks);
        rep.put("checks_run_by_orchestrator", ranByOrchestrator);
        rep.put("inspected", Map.of("run_dir", runDir.toString(), "manifest", "manifest.json", "mode", "read-only"));
        rep.put("inspected_at", Instant.now().toString());
        Json.write(runDir.resolve("qa.json"), rep);
        qaReport = rep;
        runLog.outcome("qa", Map.of("status", rep.get("status"),
                "warnings", checks.stream().filter(c -> QaChecks.WARN.equals(c.get("status"))).count(),
                "failures", checks.stream().filter(c -> QaChecks.FAIL.equals(c.get("status"))).count()));
        return Map.of("recorded", true, "status", rep.get("status"));
    }

    // ======================================================================== Reporter

    private Stage reporterTask() {
        List<AgentTool> tools = new ArrayList<>(files.tools());
        tools.add(new AgentTool("submit_summary",
                "Publish the dashboard summary. Every number must come from a file you read.",
                Map.of("headline", AgentTool.prop("string", "One-sentence status of the research"),
                        "highlights", AgentTool.arrayProp("3-5 key results", AgentTool.prop("string", "highlight")),
                        "next_steps", AgentTool.arrayProp("What the team should investigate next", AgentTool.prop("string", "step"))),
                List.of("headline", "highlights"),
                in -> writeSummary(in.path("headline").asText(), strings(in.path("highlights")), strings(in.path("next_steps")))));
        AgentRunner.Task task = new AgentRunner.Task(REPORTER, "summary",
                system("You are the Reporter. You turn the team's structured records into a short, accurate dashboard summary. "
                        + "Read runs, findings, qa and tests before writing. Report QA warnings and failures honestly."),
                "Write the dashboard summary for this run: read the records you need, then call submit_summary.",
                tools, OutputConfig.Effort.LOW, 8,
                t -> {
                    JsonNode f = t.call("read_team_file", Map.of("name", "findings"));
                    JsonNode q = t.call("read_team_file", Map.of("name", "qa"));
                    JsonNode ts = t.call("read_team_file", Map.of("name", "tests"));
                    String headline = String.format(Locale.ROOT, "%,d events checked; %,d ambiguous; QA %s; tests %d/%d passed.",
                            f.path("events_checked").asInt(), f.path("ambiguous_events").asInt(), q.path("status").asText(),
                            ts.path("passed").asInt(), ts.path("total").asInt());
                    List<String> hl = new ArrayList<>();
                    hl.add("Most common predicted class: " + f.path("most_common_class").asText() + ".");
                    hl.add("Most ambiguous events by count: " + f.path("top_uncertain_class_by_count").asText()
                            + "; by share: " + f.path("top_uncertain_class_by_share").asText() + ".");
                    if (f.path("confusion_pairs").size() > 0)
                        hl.add("Top confusion pair: " + f.path("confusion_pairs").get(0).path("pair").asText() + " ("
                                + f.path("confusion_pairs").get(0).path("ambiguous_events").asInt() + " ambiguous events).");
                    hl.add("Label/argmax conflicts in the published data: " + f.path("label_conflicts").path("count").asInt() + ".");
                    t.call("submit_summary", Map.of("headline", headline, "highlights", hl, "next_steps", strings(f.path("next_steps"))));
                    return headline;
                });
        return new Stage(task, () -> {
            if (summary != null) return List.of();
            JsonNode f = Json.read(runDir.resolve("findings.json"));
            writeSummary("Summary generated by orchestrator.", List.of(), strings(f.path("next_steps")));
            return List.of("Reporter did not submit a summary; orchestrator wrote a minimal one");
        });
    }

    private Map<String, Object> writeSummary(String headline, List<String> highlights, List<String> next) throws IOException {
        JsonNode f = Json.read(runDir.resolve("findings.json"));
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("run_id", runId);
        s.put("headline", headline);
        s.put("highlights", highlights);
        s.put("next_steps", next.isEmpty() ? strings(f.path("next_steps")) : next);
        s.put("stats", Map.of(
                "events_checked", f.path("events_checked").asInt(),
                "ambiguous_events", f.path("ambiguous_events").asInt(),
                "ambiguous_definition", f.path("ambiguous_definition").asText(),
                "most_common_class", f.path("most_common_class").asText(),
                "top_uncertain_class", f.path("top_uncertain_class_by_count").asText(),
                "qa_status", qaReport == null ? "pending" : qaReport.get("status"),
                "tests", testReport == null ? "pending" : testReport.get("passed") + "/" + testReport.get("total")));
        s.put("written_by", REPORTER + " (" + runner.mode() + ")");
        s.put("written_at", Instant.now().toString());
        Json.write(runDir.resolve("summary.json"), s);
        summary = s;
        return Map.of("recorded", true);
    }

    // ======================================================================== helpers

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null) arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static double quantile(double[] v, double q) {
        double[] s = Arrays.stream(v).filter(Double::isFinite).sorted().toArray();
        if (s.length == 0) return Double.NaN;
        return s[(int) Math.min(s.length - 1, Math.floor(q * (s.length - 1)))];
    }

    private static double r3(double v) { return Analysis.round(v, 3); }
}
