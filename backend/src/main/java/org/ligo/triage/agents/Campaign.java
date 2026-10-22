package org.ligo.triage.agents;

import com.anthropic.models.messages.OutputConfig;
import com.fasterxml.jackson.databind.JsonNode;
import org.ligo.triage.Json;
import org.ligo.triage.Workspace;
import org.ligo.triage.analysis.Analysis;
import org.ligo.triage.analysis.RankingSpec;
import org.ligo.triage.analysis.Sensitivity;
import org.ligo.triage.data.Dataset;
import org.ligo.triage.data.GlitchTable;
import org.ligo.triage.server.StatusBus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A time-boxed, multi-dataset campaign on the fog server:
 * <ol>
 *   <li>the full team pipeline on every Gravity Spy dataset (H1/L1 × O1, O2, O3a, O3b);</li>
 *   <li>a cross-dataset comparison (detector and observing-run patterns), summarised by the Reporter;</li>
 *   <li>ranking-sensitivity sweep rounds of increasing resolution, run by the Researcher, until the deadline.</li>
 * </ol>
 * Progress is persisted to output/campaign/campaign.json after every step so it survives restarts and feeds the dashboard.
 */
public final class Campaign {
    private static final Pattern PLACEHOLDER = Pattern.compile("(\\d+) events have placeholder");

    private final Workspace ws;
    private final StatusBus bus;
    private final ResearchTeam team;
    private final AgentRunner runner;
    private final TeamFiles files;
    private final Map<String, Object> state = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> datasetRows = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> sweepLatest = new TreeMap<>();
    private Instant deadline;

    public Campaign(Workspace ws, StatusBus bus, ResearchTeam team, AgentRunner runner, TeamFiles files) {
        this.ws = ws;
        this.bus = bus;
        this.team = team;
        this.runner = runner;
        this.files = files;
    }

    private List<String> only = List.of();

    /** Restricts the campaign to the named datasets (for smoke tests). */
    public Campaign datasets(List<String> names) { this.only = names; return this; }

    public void run(Duration budget) { run(budget, false); }

    /**
     * Runs the campaign. With resume, the previous campaign's id and deadline are kept and datasets it already
     * completed are reused rather than re-run.
     */
    @SuppressWarnings("unchecked")
    public void run(Duration budget, boolean resume) {
        Instant start = Instant.now();
        Map<String, Map<String, Object>> previous = new LinkedHashMap<>();
        JsonNode prior = null;
        if (resume && Files.exists(ws.campaignJson())) {
            try {
                prior = Json.read(ws.campaignJson());
                for (JsonNode d : prior.path("datasets")) {
                    String st = d.path("status").asText();
                    String runId = d.path("run_id").asText("");
                    if ((st.equals("complete") || st.equals("complete_with_issues")) && !runId.isEmpty()
                            && Files.exists(ws.runDir(runId).resolve("findings.json")))
                        previous.put(d.path("dataset").asText(), Json.MAPPER.convertValue(d, Map.class));
                }
            } catch (IOException e) {
                prior = null;
            }
        }
        if (prior != null) {
            deadline = Instant.parse(prior.path("deadline").asText());
            state.put("campaign_id", prior.path("campaign_id").asText());
            state.put("started_at", prior.path("started_at").asText());
            state.put("resumed_at", start.toString());
            state.put("hours", prior.path("hours").asDouble());
        } else {
            deadline = start.plus(budget);
            state.put("campaign_id", Workspace.newRunId("campaign"));
            state.put("started_at", start.toString());
            state.put("hours", budget.toMinutes() / 60.0);
        }
        state.put("status", "running");
        state.put("deadline", deadline.toString());
        state.put("agent_mode", runner.mode());
        for (String name : Dataset.PUBLISHED_MD5.keySet()) {
            if (!only.isEmpty() && !only.contains(name)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            if (previous.containsKey(name)) {
                row.putAll(previous.get(name));
                row.put("reused_from_previous_process", true);
            } else {
                row.put("dataset", name);
                row.put("detector", Dataset.detector(name));
                row.put("observing_run", Dataset.observingRun(name));
                row.put("status", "pending");
            }
            datasetRows.put(name, row);
        }
        try {
            phase("datasets", "Running the team on all " + datasetRows.size() + " Gravity Spy datasets");
            for (String name : datasetRows.keySet()) {
                Map<String, Object> row = datasetRows.get(name);
                if (Boolean.TRUE.equals(row.get("reused_from_previous_process"))) continue;
                if (Instant.now().isAfter(deadline)) { row.put("status", "skipped (deadline)"); continue; }
                row.put("status", "running");
                row.put("started_at", Instant.now().toString());
                save();
                long t0 = System.nanoTime();
                try {
                    team.run(name);
                    row.put("status", team.lastStatus());
                    row.put("run_id", team.lastRunId());
                    row.putAll(datasetStats(ws.runDir(team.lastRunId())));
                } catch (Exception e) {
                    row.put("status", "failed");
                    row.put("error", String.valueOf(e.getMessage()));
                }
                row.put("seconds", Math.round((System.nanoTime() - t0) / 1e8) / 10.0);
                save();
            }

            phase("comparison", "Comparing detectors and observing runs");
            state.put("comparison", comparison());
            save();
            reporterSummary();

            phase("sensitivity", "Ranking-sensitivity sweep until " + deadline);
            sweep();
            state.put("status", "complete");
        } catch (Exception e) {
            state.put("status", "failed");
            state.put("error", String.valueOf(e));
        }
        state.put("finished_at", Instant.now().toString());
        phase("complete", "Campaign " + state.get("status"));
    }

    private void phase(String phase, String text) {
        state.put("phase", phase);
        save();
        bus.publish("state", null, "Campaign: " + text, Map.of("kind", "campaign", "phase", phase));
    }

    private synchronized void save() {
        state.put("datasets", new ArrayList<>(datasetRows.values()));
        state.put("sensitivity", sweepLatest);
        state.put("updated_at", Instant.now().toString());
        try {
            Json.write(ws.campaignJson(), state);
        } catch (IOException e) {
            System.err.println("Could not write campaign.json: " + e.getMessage());
        }
    }

    // ================================================================ per-dataset stats

    private static Map<String, Object> datasetStats(Path runDir) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!Files.exists(runDir.resolve("findings.json"))) return m;
        JsonNode f = Json.read(runDir.resolve("findings.json"));
        m.put("events", f.path("events_checked").asInt());
        m.put("ambiguous", f.path("ambiguous_events").asInt());
        m.put("ambiguous_share", f.path("ambiguous_share").asDouble());
        m.put("label_conflicts", f.path("label_conflicts").path("count").asInt());
        m.put("most_common_class", f.path("most_common_class").asText());
        m.put("top_uncertain_class_by_share", f.path("top_uncertain_class_by_share").asText());
        JsonNode pairs = f.path("confusion_pairs");
        m.put("top_confusion_pair", pairs.size() > 0 ? pairs.get(0).path("pair").asText() : null);
        m.put("top_confusion_pair_events", pairs.size() > 0 ? pairs.get(0).path("ambiguous_events").asInt() : 0);
        m.put("mean_top_n_stability", f.path("rank_stability").path("mean_top_n_stability").asDouble(Double.NaN));
        Map<String, Double> shares = new TreeMap<>();
        for (JsonNode c : f.path("class_uncertainty"))
            if (c.path("events").asInt() >= 30) shares.put(c.path("class").asText(), c.path("ambiguous_share").asDouble());
        m.put("class_ambiguous_share", shares);
        if (Files.exists(runDir.resolve("schema.json"))) {
            Matcher mt = PLACEHOLDER.matcher(Json.read(runDir.resolve("schema.json")).path("warnings").toString());
            m.put("placeholder_url_events", mt.find() ? Integer.parseInt(mt.group(1)) : 0);
        }
        if (Files.exists(runDir.resolve("qa.json"))) m.put("qa_status", Json.read(runDir.resolve("qa.json")).path("status").asText());
        if (Files.exists(runDir.resolve("tests.json"))) {
            JsonNode t = Json.read(runDir.resolve("tests.json"));
            m.put("tests", t.path("passed").asInt() + "/" + t.path("total").asInt());
        }
        return m;
    }

    // ================================================================ comparison

    private Map<String, Object> comparison() {
        List<Map<String, Object>> done = datasetRows.values().stream().filter(r -> r.containsKey("events")).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("datasets_compared", done.size());
        out.put("total_events", done.stream().mapToInt(r -> (int) r.get("events")).sum());
        out.put("total_ambiguous", done.stream().mapToInt(r -> (int) r.get("ambiguous")).sum());

        // Detector comparison within each observing run.
        List<Map<String, Object>> byRun = new ArrayList<>();
        int lHigher = 0, pairsCompared = 0;
        for (String run : List.of("O1", "O2", "O3a", "O3b")) {
            Map<String, Object> h = datasetRows.get("H1_" + run), l = datasetRows.get("L1_" + run);
            if (h == null || l == null || !h.containsKey("events") || !l.containsKey("events")) continue;
            double hs = (double) h.get("ambiguous_share"), ls = (double) l.get("ambiguous_share");
            pairsCompared++;
            if (ls > hs) lHigher++;
            byRun.add(Map.of("observing_run", run, "H1_ambiguous_share", hs, "L1_ambiguous_share", ls,
                    "H1_events", h.get("events"), "L1_events", l.get("events")));
        }
        out.put("detector_comparison", byRun);

        // Observing-run trend for each detector.
        Map<String, List<Map<String, Object>>> trend = new LinkedHashMap<>();
        for (String det : List.of("H1", "L1")) {
            List<Map<String, Object>> t = new ArrayList<>();
            for (String run : List.of("O1", "O2", "O3a", "O3b")) {
                Map<String, Object> r = datasetRows.get(det + "_" + run);
                if (r != null && r.containsKey("events"))
                    t.add(Map.of("observing_run", run, "ambiguous_share", r.get("ambiguous_share"), "events", r.get("events"),
                            "label_conflicts", r.get("label_conflicts")));
            }
            trend.put(det, t);
        }
        out.put("run_trend", trend);

        // Which competing pair dominates where; which classes are consistently ambiguous.
        Map<String, Integer> pairCounts = new TreeMap<>();
        for (Map<String, Object> r : done) if (r.get("top_confusion_pair") != null) pairCounts.merge((String) r.get("top_confusion_pair"), 1, Integer::sum);
        out.put("top_confusion_pair_by_dataset_count", pairCounts);
        Map<String, Map<String, Double>> classShares = new TreeMap<>();
        for (Map<String, Object> r : done) {
            @SuppressWarnings("unchecked") Map<String, Double> s = (Map<String, Double>) r.get("class_ambiguous_share");
            s.forEach((cls, share) -> classShares.computeIfAbsent(cls, k -> new LinkedHashMap<>()).put((String) r.get("dataset"), share));
        }
        out.put("class_ambiguous_share", classShares);

        List<String> highlights = new ArrayList<>();
        if (pairsCompared > 0)
            highlights.add(String.format(Locale.ROOT, "Livingston has a higher ambiguous share than Hanford in %d of %d observing runs compared.", lHigher, pairsCompared));
        trend.forEach((det, t) -> {
            if (t.size() >= 2)
                highlights.add(String.format(Locale.ROOT, "%s ambiguous share: %s.", det, String.join(" → ",
                        t.stream().map(x -> String.format(Locale.ROOT, "%s %.1f%%", x.get("observing_run"), 100 * (double) x.get("ambiguous_share"))).toList())));
        });
        pairCounts.entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(e ->
                highlights.add(String.format(Locale.ROOT, "%s is the most frequent competing pair in %d of %d datasets.", e.getKey(), e.getValue(), done.size())));
        int conflicts = done.stream().mapToInt(r -> (int) r.get("label_conflicts")).sum();
        highlights.add(String.format(Locale.ROOT, "%,d events across all datasets have a published ml_label that differs from their argmax class.", conflicts));
        out.put("highlights", highlights);
        return out;
    }

    private void reporterSummary() {
        List<AgentTool> tools = new ArrayList<>(files.tools());
        tools.add(new AgentTool("submit_campaign_summary",
                "Publish the campaign summary for the dashboard. Every number must come from the campaign record.",
                Map.of("headline", AgentTool.prop("string", "One-sentence summary of the cross-dataset results"),
                        "highlights", AgentTool.arrayProp("3-6 key cross-dataset findings", AgentTool.prop("string", "finding")),
                        "caveats", AgentTool.arrayProp("What the comparison cannot show", AgentTool.prop("string", "caveat"))),
                List.of("headline", "highlights"),
                in -> {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("headline", in.path("headline").asText());
                    s.put("highlights", Json.MAPPER.convertValue(in.path("highlights"), List.class));
                    s.put("caveats", in.has("caveats") ? Json.MAPPER.convertValue(in.path("caveats"), List.class) : List.of());
                    s.put("written_by", ResearchTeam.REPORTER + " (" + runner.mode() + ")");
                    state.put("campaign_summary", s);
                    save();
                    return Map.of("recorded", true);
                }));
        runner.run(new AgentRunner.Task(ResearchTeam.REPORTER, "campaign summary",
                "You are the Reporter for a five-role agent team that has ranked ambiguous Gravity Spy LIGO glitches in every "
                        + "public dataset (Hanford H1 and Livingston L1, observing runs O1-O3b). Read the campaign record and write a "
                        + "short, accurate cross-dataset summary. Differences in ambiguous share reflect the classifier's uncertainty, "
                        + "not necessarily detector behaviour; say so where relevant. Keep your final message to 2-3 sentences.",
                "Summarise the cross-dataset comparison: read_team_file('campaign'), then submit_campaign_summary.",
                tools, OutputConfig.Effort.LOW, 6,
                t -> {
                    JsonNode c = t.call("read_team_file", Map.of("name", "campaign")).path("comparison");
                    List<String> hl = Json.MAPPER.convertValue(c.path("highlights"), List.class);
                    String headline = String.format(Locale.ROOT, "%,d glitches across %d datasets; %,d ambiguous (top-two margin < 0.2).",
                            c.path("total_events").asInt(), c.path("datasets_compared").asInt(), c.path("total_ambiguous").asInt());
                    t.call("submit_campaign_summary", Map.of("headline", headline, "highlights", hl, "caveats", List.of(
                            "Ambiguity measures the published classifier's uncertainty, which also depends on how each run's training data covered its glitch population.",
                            "Datasets differ greatly in size; small classes are noisy.")));
                    return headline;
                }));
    }

    // ================================================================ sensitivity sweep

    /** Round r uses a finer grid and more resamples as time allows. */
    static double stepFor(int round) { return round < 2 ? 0.1 : 0.05; }
    static int resamplesFor(int round) {
        return switch (round) { case 0 -> 50; case 1, 2 -> 200; case 3 -> 500; case 4 -> 1000; default -> 2000; };
    }

    private void sweep() throws IOException {
        List<String> ready = datasetRows.values().stream().filter(r -> r.get("run_id") != null && r.containsKey("events"))
                .map(r -> (String) r.get("dataset")).toList();
        if (ready.isEmpty()) return;
        Files.createDirectories(ws.campaignDir().resolve("sensitivity"));
        int round = 0;
        while (Instant.now().isBefore(deadline)) {
            state.put("sweep_round", round);
            save();
            for (String name : ready) {
                if (Instant.now().isAfter(deadline)) break;
                sweepTask(name, round);
            }
            round++;
        }
        state.put("sweep_rounds_completed", round);
        save();
    }

    private void sweepTask(String name, int round) {
        String runId = (String) datasetRows.get(name).get("run_id");
        Path runDir = ws.runDir(runId);
        long seed = 1_000_003L * (round + 1) + name.hashCode();
        AgentTool tool = new AgentTool("run_sensitivity_round",
                "Re-derive the top-N review list for " + name + " under every weight combination on a simplex grid, with and "
                        + "without bootstrap-resampled metadata baselines, and compare with the Researcher's list. "
                        + "Returns overlap statistics and the consensus review list. CPU-heavy; stops at the campaign deadline.",
                Map.of("grid_step", AgentTool.enumProp("Weight grid resolution", List.of("0.1", "0.05")),
                        "resamples", AgentTool.prop("integer", "Bootstrap resamples, 20-2000")),
                List.of("grid_step", "resamples"),
                in -> {
                    double step = Double.parseDouble(in.path("grid_step").asText("0.1"));
                    int b = Math.max(20, Math.min(2000, in.path("resamples").asInt(resamplesFor(round))));
                    JsonNode ds = Json.read(runDir.resolve("dataset.json"));
                    JsonNode sp = Json.read(runDir.resolve("spec.json"));
                    Analysis base = Analysis.run(GlitchTable.loadClassified(Path.of(ds.path("path").asText())),
                            RankingSpec.fromToolInput(sp, sp.path("author").asText()));
                    Map<String, Object> res = new LinkedHashMap<>();
                    res.put("dataset", name);
                    res.put("run_id", runId);
                    res.put("round", round);
                    res.put("finished_at", null);
                    res.putAll(Sensitivity.round(base, step, b, seed, deadline));
                    res.put("finished_at", Instant.now().toString());
                    recordSweep(name, res);
                    Map<String, Object> brief = new LinkedHashMap<>(res);
                    brief.put("consensus_top", ((List<?>) res.get("consensus_top")).subList(0, Math.min(5, ((List<?>) res.get("consensus_top")).size())));
                    return brief;
                });
        runner.run(new AgentRunner.Task(ResearchTeam.RESEARCHER, "sensitivity " + name + " r" + round,
                "You are the Researcher on a five-role agent team ranking ambiguous Gravity Spy LIGO glitches. You chose the ranking "
                        + "weights; now you test how much the human review list depends on that choice. Run one sensitivity round "
                        + "for the dataset you are given and interpret it in 2 sentences: is the review list robust, and which "
                        + "weightings change it most?",
                String.format(Locale.ROOT, "Dataset %s, sweep round %d. Suggested settings for this round: grid_step %s, resamples %d "
                                + "(rounds get finer as time allows; %d minutes remain in the campaign). Run run_sensitivity_round once and interpret it.",
                        name, round, stepFor(round), resamplesFor(round), Math.max(0, Duration.between(Instant.now(), deadline).toMinutes())),
                List.of(tool), OutputConfig.Effort.LOW, 4,
                t -> {
                    JsonNode r = t.call("run_sensitivity_round", Map.of("grid_step", String.valueOf(stepFor(round)), "resamples", resamplesFor(round)));
                    return String.format(Locale.ROOT, "%s round %d: across %d weightings the review list keeps %.0f%% of its events on average "
                                    + "(worst %.0f%%); with bootstrap (%d resamples) %.0f%%. Consensus list shares %.0f%% with the default.",
                            name, round, r.path("weight_combinations").asInt(),
                            100 * r.path("weights_only").path("mean_overlap_with_default").asDouble(),
                            100 * r.path("weights_only").path("min_overlap_with_default").asDouble(),
                            r.path("resamples_completed").asInt(),
                            100 * r.path("weights_and_bootstrap").path("mean_overlap_with_default").asDouble(),
                            100 * r.path("consensus_overlap_with_default").asDouble());
                }));
    }

    private void recordSweep(String name, Map<String, Object> res) throws IOException {
        Path f = ws.campaignDir().resolve("sensitivity").resolve(name + ".json");
        List<Object> history = new ArrayList<>();
        if (Files.exists(f)) history.addAll(Json.MAPPER.convertValue(Json.read(f).path("rounds"), List.class));
        history.add(res);
        Json.write(f, Map.of("dataset", name, "rounds", history));
        Map<String, Object> brief = new LinkedHashMap<>();
        for (String k : List.of("round", "grid_step", "weight_combinations", "resamples_completed", "top_n_rankings_evaluated",
                "seconds", "weights_only", "weights_and_bootstrap", "consensus_overlap_with_default", "finished_at"))
            brief.put(k, res.get(k));
        List<?> cons = (List<?>) res.get("consensus_top");
        brief.put("consensus_top_5", cons.subList(0, Math.min(5, cons.size())));
        synchronized (this) { sweepLatest.put(name, brief); }
        save();
    }
}
