package org.ligo.triage.server;

import org.ligo.triage.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-process event stream for live status. Agents and the orchestrator publish here; the API server fans
 * events out to dashboards over Server-Sent Events, and each event is appended to the run's activity.jsonl.
 */
public final class StatusBus {
    private static final int HISTORY = 600;

    private final ArrayDeque<Map<String, Object>> history = new ArrayDeque<>();
    private final List<Consumer<Map<String, Object>>> subscribers = new CopyOnWriteArrayList<>();
    private long seq;
    private Path activityLog;

    public synchronized void setActivityLog(Path file) { this.activityLog = file; }

    public void publish(String type, String role, String text, Map<String, Object> data) {
        Map<String, Object> e = new LinkedHashMap<>();
        synchronized (this) {
            e.put("seq", ++seq);
            e.put("ts", Instant.now().toString());
            e.put("type", type);
            e.put("role", role);
            e.put("text", text);
            if (data != null && !data.isEmpty()) e.put("data", data);
            history.addLast(e);
            while (history.size() > HISTORY) history.removeFirst();
            if (activityLog != null) {
                try {
                    Files.writeString(activityLog, Json.compact(e) + "\n",
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ignored) {
                    // activity log is best-effort; the in-memory stream is authoritative for live views
                }
            }
        }
        String console = String.format("[%s] %-10s %s", type, role == null ? "" : role, text);
        System.out.println(console.length() > 240 ? console.substring(0, 237) + "..." : console);
        for (Consumer<Map<String, Object>> s : subscribers) {
            try { s.accept(e); } catch (RuntimeException ex) { subscribers.remove(s); }
        }
    }

    public void info(String role, String text) { publish("log", role, text, null); }

    public synchronized List<Map<String, Object>> recent(int limit) {
        List<Map<String, Object>> all = new ArrayList<>(history);
        return all.subList(Math.max(0, all.size() - limit), all.size());
    }

    public Runnable subscribe(Consumer<Map<String, Object>> listener) {
        subscribers.add(listener);
        return () -> subscribers.remove(listener);
    }
}
