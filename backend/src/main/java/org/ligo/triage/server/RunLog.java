package org.ligo.triage.server;

import com.fasterxml.jackson.core.type.TypeReference;
import org.ligo.triage.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent record of every run and each agent stage within it (output/runs.json).
 * Every mutation is saved immediately and announced on the bus so dashboards update live.
 */
public final class RunLog {
    private static final int KEEP = 20;

    private final Path file;
    private final StatusBus bus;
    private final List<Map<String, Object>> runs;
    private Map<String, Object> current;

    public RunLog(Path file, StatusBus bus) {
        this.file = file;
        this.bus = bus;
        List<Map<String, Object>> loaded = new ArrayList<>();
        try {
            if (Files.exists(file)) {
                Map<String, Object> doc = Json.MAPPER.readValue(file.toFile(), new TypeReference<>() {});
                Object r = doc.get("runs");
                if (r instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) loaded.add(castMap(m));
            }
        } catch (IOException ignored) {
            // a corrupt runs.json starts a fresh history rather than blocking the server
        }
        // A run left "running" by a previous process was interrupted.
        for (Map<String, Object> r : loaded) if ("running".equals(r.get("status"))) r.put("status", "interrupted");
        this.runs = loaded;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) { return (Map<String, Object>) m; }

    public synchronized void startRun(String runId, String dataset, String agentMode, String model, List<String[]> plannedStages) {
        current = new LinkedHashMap<>();
        current.put("run_id", runId);
        current.put("dataset", dataset);
        current.put("status", "running");
        current.put("started_at", Instant.now().toString());
        current.put("finished_at", null);
        current.put("agent_mode", agentMode);
        current.put("model", model);
        current.put("host", Map.of(
                "cores", Runtime.getRuntime().availableProcessors(),
                "os", System.getProperty("os.name") + " " + System.getProperty("os.arch"),
                "java", System.getProperty("java.version")));
        List<Map<String, Object>> stages = new ArrayList<>();
        for (String[] s : plannedStages) {
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("role", s[0]);
            st.put("phase", s[1]);
            st.put("title", s[2]);
            st.put("status", "pending");
            stages.add(st);
        }
        current.put("stages", stages);
        current.put("outcome", new LinkedHashMap<>());
        runs.add(0, current);
        while (runs.size() > KEEP) runs.remove(runs.size() - 1);
        saveAndAnnounce("Run " + runId + " started");
    }

    @SuppressWarnings("unchecked")
    private synchronized Map<String, Object> stage(String role, String phase) {
        for (Map<String, Object> s : (List<Map<String, Object>>) current.get("stages"))
            if (role.equals(s.get("role")) && phase.equals(s.get("phase"))) return s;
        throw new IllegalArgumentException("Unknown stage " + role + "/" + phase);
    }

    public synchronized void stageStarted(String role, String phase) {
        Map<String, Object> s = stage(role, phase);
        s.put("status", "running");
        s.put("started_at", Instant.now().toString());
        saveAndAnnounce(role + " started " + s.get("title"));
    }

    public synchronized void stageFinished(String role, String phase, String status, Map<String, Object> details) {
        Map<String, Object> s = stage(role, phase);
        s.putAll(details);
        s.remove("agent_status");
        if (details.containsKey("status")) s.put("agent_status", details.get("status"));
        s.put("status", status);
        Instant end = Instant.now();
        s.put("finished_at", end.toString());
        if (s.get("started_at") != null)
            s.put("seconds", Duration.between(Instant.parse((String) s.get("started_at")), end).toMillis() / 1000.0);
        saveAndAnnounce(role + " " + status + ": " + s.get("title"));
    }

    @SuppressWarnings("unchecked")
    public synchronized void outcome(String key, Object value) {
        ((Map<String, Object>) current.get("outcome")).put(key, value);
        save();
    }

    public synchronized void finishRun(String status) {
        current.put("status", status);
        current.put("finished_at", Instant.now().toString());
        saveAndAnnounce("Run " + current.get("run_id") + " " + status);
    }

    public synchronized boolean isRunning() { return current != null && "running".equals(current.get("status")); }

    /** Deep copy of the current (or most recent) run for API responses. */
    public synchronized Map<String, Object> latestRun() {
        Map<String, Object> r = current != null ? current : runs.isEmpty() ? null : runs.get(0);
        return r == null ? null : Json.MAPPER.convertValue(r, new TypeReference<Map<String, Object>>() {});
    }

    public synchronized Map<String, Object> document() {
        return Json.MAPPER.convertValue(Map.of("runs", runs), new TypeReference<Map<String, Object>>() {});
    }

    private synchronized void save() {
        try { Json.write(file, Map.of("runs", runs)); }
        catch (IOException e) { System.err.println("Could not write runs.json: " + e.getMessage()); }
    }

    private void saveAndAnnounce(String text) {
        save();
        bus.publish("state", null, text, null);
    }
}
