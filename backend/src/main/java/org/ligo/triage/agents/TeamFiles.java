package org.ligo.triage.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.ligo.triage.Json;
import org.ligo.triage.Workspace;
import org.ligo.triage.server.RunLog;
import org.ligo.triage.server.StatusBus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only access to the team's structured records (runs.json, qa.json, tests.json, findings.json, ...).
 * The Reporter and the question interface may only answer from what these tools return.
 */
public final class TeamFiles {
    public static final List<String> NAMES =
            List.of("runs", "qa", "tests", "findings", "ranked", "dataset", "spec", "summary", "activity", "campaign");

    private final Workspace ws;
    private final RunLog runLog;
    private final StatusBus bus;

    public TeamFiles(Workspace ws, RunLog runLog, StatusBus bus) {
        this.ws = ws;
        this.runLog = runLog;
        this.bus = bus;
    }

    public Path runDir() throws IOException {
        return ws.latestCompleteRun().orElseThrow(() -> new IOException("No completed run yet"));
    }

    /** Returns the named record, condensed where the raw file is large. */
    public Object read(String name) throws IOException {
        return switch (name) {
            case "runs" -> condensedRun();
            case "campaign" -> {
                if (!Files.exists(ws.campaignJson())) throw new IOException("No multi-dataset campaign has been run");
                yield Json.read(ws.campaignJson());
            }
            case "ranked" -> condensedRanked();
            case "activity" -> bus.recent(400).stream()
                    .filter(e -> "agent".equals(e.get("type")))
                    .map(e -> e.get("role") + ": " + e.get("text"))
                    .toList().reversed().stream().limit(40).toList().reversed();
            case "qa", "tests", "findings", "dataset", "spec", "summary" -> {
                Path f = runDir().resolve(name + ".json");
                if (!Files.exists(f)) throw new IOException(name + ".json has not been written yet for this run");
                yield Json.read(f);
            }
            default -> throw new IllegalArgumentException("Unknown file '" + name + "'; choose one of " + NAMES);
        };
    }

    @SuppressWarnings("unchecked")
    private Object condensedRun() {
        Map<String, Object> run = runLog.latestRun();
        if (run == null) return Map.of("note", "No runs recorded yet");
        List<Map<String, Object>> stages = new ArrayList<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) run.get("stages")) {
            Map<String, Object> c = new LinkedHashMap<>();
            for (String k : List.of("role", "phase", "title", "status", "mode", "seconds", "summary", "warnings"))
                if (s.get(k) != null) c.put(k, s.get(k));
            Object calls = s.get("tool_calls");
            if (calls instanceof List<?> l)
                c.put("tools_used", l.stream().map(o -> ((Map<String, Object>) o).get("tool") + (Boolean.TRUE.equals(((Map<String, Object>) o).get("ok")) ? "" : " (error)")).toList());
            stages.add(c);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("run_id", "dataset", "status", "started_at", "finished_at", "agent_mode", "model", "host", "outcome"))
            out.put(k, run.get(k));
        out.put("stages", stages);
        return out;
    }

    private Object condensedRanked() throws IOException {
        JsonNode ranked = Json.read(runDir().resolve("ranked.json"));
        ArrayNode events = Json.MAPPER.createArrayNode();
        for (JsonNode e : ranked.path("events")) {
            ObjectNode c = events.addObject();
            c.put("rank", e.path("rank").asInt());
            c.put("gravityspy_id", e.path("gravityspy_id").asText());
            c.put("ml_label", e.path("ml_label").asText());
            JsonNode top = e.path("top_classes");
            c.put("alternative", top.size() > 1 ? top.get(1).path("class").asText() : "");
            c.put("priority", e.path("scores").path("priority").asDouble());
            c.set("drivers", e.path("drivers"));
            c.put("explanation", e.path("explanation").asText());
        }
        ObjectNode out = Json.object();
        out.put("events_scored", ranked.path("events_scored").asInt());
        out.put("top_n", ranked.path("top_n").asInt());
        out.set("formula", ranked.path("spec").path("formula"));
        out.set("events", events);
        return out;
    }

    public Object rankedEvent(JsonNode input) throws IOException {
        JsonNode ranked = Json.read(runDir().resolve("ranked.json"));
        int rank = input.path("rank").asInt(0);
        String id = input.path("gravityspy_id").asText("");
        for (JsonNode e : ranked.path("events"))
            if (e.path("rank").asInt() == rank || (!id.isEmpty() && e.path("gravityspy_id").asText().equals(id))) return e;
        throw new IllegalArgumentException("No ranked event with rank " + rank + (id.isEmpty() ? "" : " or id " + id)
                + "; ranked events cover ranks 1-" + ranked.path("top_n").asInt());
    }

    public List<AgentTool> tools() {
        return List.of(
                new AgentTool("read_team_file",
                        "Read one of the team's structured records for the latest run. runs = agent stages and outcomes; "
                                + "qa = QA checks and verdict; tests = automated test results; findings = class uncertainty, "
                                + "confusion pairs, next steps, limitations; ranked = top-N ranked glitches with explanations; "
                                + "dataset = source URL and checksums; spec = ranking formula; summary = reporter summary; "
                                + "activity = recent agent log lines; campaign = multi-dataset campaign progress, the H1/L1 and O1-O3b comparison "
                                + "and ranking-sensitivity sweep results.",
                        Map.of("name", AgentTool.enumProp("Which record to read", NAMES)),
                        List.of("name"),
                        in -> read(in.path("name").asText())),
                new AgentTool("get_ranked_event",
                        "Full detail for one ranked glitch (scores, percentiles, competing classes, metadata, spectrogram URLs, explanation). "
                                + "Give either rank or gravityspy_id.",
                        Map.of("rank", AgentTool.prop("integer", "Rank in the review list, 1 = highest priority"),
                                "gravityspy_id", AgentTool.prop("string", "Gravity Spy event id")),
                        List.of(),
                        this::rankedEvent));
    }
}
