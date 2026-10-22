package org.ligo.triage.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.ligo.triage.Json;
import org.ligo.triage.Workspace;
import org.ligo.triage.agents.AskService;
import org.ligo.triage.agents.ResearchTeam;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * JSON + Server-Sent Events API for the dashboard, and static hosting of the built web app (web/dist).
 * <pre>
 *   GET  /api/state      current run, agent stages, summary
 *   GET  /api/events     live event stream (SSE)
 *   GET  /api/{ranked|findings|qa|tests|dataset|spec|summary}   records of the latest frozen run
 *   GET  /api/runs       run history (runs.json)
 *   GET  /api/campaign   multi-dataset campaign progress, comparison and sensitivity results
 *   (record endpoints accept ?run=RUN_ID to read a specific run instead of the latest)
 *   POST /api/run        start a new team run
 *   POST /api/ask        {"question": "..."} → grounded answer
 * </pre>
 */
public final class ApiServer {
    private final Workspace ws;
    private final StatusBus bus;
    private final RunLog runLog;
    private final ResearchTeam team;
    private final AskService ask;
    private final String agentMode, model;
    private HttpServer server;

    public ApiServer(Workspace ws, StatusBus bus, RunLog runLog, ResearchTeam team, AskService ask, String agentMode, String model) {
        this.ws = ws;
        this.bus = bus;
        this.runLog = runLog;
        this.team = team;
        this.ask = ask;
        this.agentMode = agentMode;
        this.model = model;
    }

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/", this::api);
        server.createContext("/", this::staticFiles);
        server.start();
    }

    private void api(HttpExchange ex) throws IOException {
        try {
            cors(ex);
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath().replaceFirst("^/api/", "").replaceAll("/$", "");
            if (method.equals("OPTIONS")) { send(ex, 204, null); return; }
            switch (method + " " + path) {
                case "GET state" -> sendJson(ex, 200, state());
                case "GET events" -> events(ex);
                case "GET runs" -> sendJson(ex, 200, runLog.document());
                case "GET activity" -> sendJson(ex, 200, bus.recent(300));
                case "GET campaign" -> {
                    if (Files.exists(ws.campaignJson())) send(ex, 200, Files.readAllBytes(ws.campaignJson()));
                    else sendJson(ex, 404, Map.of("error", "No campaign has been run"));
                }
                case "GET ranked", "GET findings", "GET qa", "GET tests", "GET dataset", "GET spec", "GET summary" -> {
                    String runParam = query(ex, "run");
                    Optional<Path> dir = runParam != null ? ws.runById(runParam) : ws.latestCompleteRun();
                    Path f = dir.map(d -> d.resolve(path + ".json")).orElse(null);
                    if (f == null || !Files.exists(f)) sendJson(ex, 404, Map.of("error", path + " not available yet"));
                    else send(ex, 200, Files.readAllBytes(f));
                }
                case "POST run" -> {
                    if (team.isRunning()) { sendJson(ex, 409, Map.of("error", "A run is already in progress")); return; }
                    Thread.ofVirtual().start(team::run);
                    sendJson(ex, 202, Map.of("started", true));
                }
                case "POST ask" -> {
                    JsonNode body = Json.parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    String q = body.path("question").asText("").trim();
                    if (q.isEmpty() || q.length() > 500) { sendJson(ex, 400, Map.of("error", "question must be 1-500 characters")); return; }
                    sendJson(ex, 200, ask.ask(q));
                }
                default -> sendJson(ex, 404, Map.of("error", "Unknown endpoint " + method + " /api/" + path));
            }
        } catch (Exception e) {
            try { sendJson(ex, 500, Map.of("error", String.valueOf(e.getMessage()))); } catch (IOException ignored) { }
        }
    }

    private Map<String, Object> state() throws IOException {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("agent_mode", agentMode);
        s.put("model", model);
        s.put("running", team.isRunning());
        s.put("run", runLog.latestRun());
        Optional<Path> dir = ws.latestCompleteRun();
        s.put("results_run_id", dir.map(d -> d.getFileName().toString()).orElse(null));
        if (dir.isPresent() && Files.exists(dir.get().resolve("summary.json"))) s.put("summary", Json.read(dir.get().resolve("summary.json")));
        return s;
    }

    private void events(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();
        Runnable unsubscribe = bus.subscribe(queue::offer);
        try (OutputStream os = ex.getResponseBody()) {
            for (Map<String, Object> e : bus.recent(80)) os.write(("data: " + Json.compact(e) + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            while (true) {
                Map<String, Object> e = queue.poll(15, TimeUnit.SECONDS);
                os.write((e == null ? ": keep-alive\n\n" : "data: " + Json.compact(e) + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } catch (IOException | InterruptedException ignored) {
            // client disconnected
        } finally {
            unsubscribe.run();
        }
    }

    private void staticFiles(HttpExchange ex) throws IOException {
        Path dist = ws.webDist();
        String p = ex.getRequestURI().getPath();
        Path f = dist.resolve(p.equals("/") ? "index.html" : p.substring(1)).normalize();
        if (!f.startsWith(dist) || !Files.isRegularFile(f)) f = dist.resolve("index.html"); // SPA fallback
        if (!Files.isRegularFile(f)) {
            send(ex, 200, ("Gravity Spy triage API is running. Build the dashboard with `npm run build` in web/, "
                    + "or run `npm run dev` there. API: /api/state").getBytes(StandardCharsets.UTF_8));
            return;
        }
        String name = f.getFileName().toString();
        String type = name.endsWith(".html") ? "text/html; charset=utf-8" : name.endsWith(".js") ? "text/javascript"
                : name.endsWith(".css") ? "text/css" : name.endsWith(".svg") ? "image/svg+xml"
                : name.endsWith(".json") ? "application/json" : "application/octet-stream";
        ex.getResponseHeaders().add("Content-Type", type);
        send(ex, 200, Files.readAllBytes(f));
    }

    private static String query(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return null;
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(key))
                return java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static void cors(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        send(ex, status, Json.pretty(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, byte[] body) throws IOException {
        if (body == null) { ex.sendResponseHeaders(status, -1); ex.close(); return; }
        if (!ex.getResponseHeaders().containsKey("Content-Type")) ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }
}
