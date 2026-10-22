package org.ligo.triage;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.ligo.triage.agents.AgentRunner;
import org.ligo.triage.agents.AskService;
import org.ligo.triage.agents.Campaign;
import org.ligo.triage.agents.ResearchTeam;
import org.ligo.triage.agents.TeamFiles;
import org.ligo.triage.server.ApiServer;
import org.ligo.triage.server.RunLog;
import org.ligo.triage.server.StatusBus;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Fog-server entry point.
 * <pre>
 *   analyze [--root DIR] [--offline]                 run the agent team once; exit 1 if tests or QA fail
 *   serve   [--root DIR] [--port 8787] [--run] [--offline]   serve the dashboard API (optionally start a run)
 *   campaign [--hours 4] [--resume] [--datasets A,B] [--root DIR] [--port 8787] [--offline]
 *            serve, and run the time-boxed multi-dataset campaign (--resume keeps the previous deadline and finished datasets)
 * </pre>
 * Claude agents are used when ANTHROPIC_API_KEY (or ANTHROPIC_AUTH_TOKEN) is set; otherwise every role runs its
 * scripted policy. TRIAGE_MODEL overrides the model (default claude-opus-5-5).
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        String command = args.length > 0 && !args[0].startsWith("--") ? args[0] : "serve";
        Path root = Path.of(".");
        int port = 8787;
        double hours = 4;
        java.util.List<String> datasets = java.util.List.of();
        boolean run = false, offline = false, resume = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--root" -> root = Path.of(args[++i]);
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--run" -> run = true;
                case "--offline" -> offline = true;
                case "--resume" -> resume = true;
                case "--hours" -> hours = Double.parseDouble(args[++i]);
                case "--datasets" -> datasets = java.util.List.of(args[++i].split(","));
                default -> { }
            }
        }
        Workspace ws = new Workspace(root.toAbsolutePath().normalize());
        StatusBus bus = new StatusBus();
        RunLog runLog = new RunLog(ws.runsJson(), bus);

        boolean hasCredentials = System.getenv("ANTHROPIC_API_KEY") != null || System.getenv("ANTHROPIC_AUTH_TOKEN") != null;
        AnthropicClient client = !offline && hasCredentials ? AnthropicOkHttpClient.fromEnv() : null;
        String model = System.getenv().getOrDefault("TRIAGE_MODEL", "claude-opus-5-5");
        AgentRunner runner = new AgentRunner(client, model, bus);
        System.out.println(client == null
                ? "Agent mode: scripted (set ANTHROPIC_API_KEY to staff the team with Claude agents)"
                : "Agent mode: claude (" + model + ", server-side refusal fallbacks enabled)");

        TeamFiles files = new TeamFiles(ws, runLog, bus);
        ResearchTeam team = new ResearchTeam(ws, bus, runLog, runner, files);

        switch (command) {
            case "analyze" -> {
                boolean ok = team.run();
                System.out.println(ok ? "Run complete: tests passed and QA did not fail." : "Run finished with issues; see output/runs.json.");
                ws.latestCompleteRun().ifPresent(d -> System.out.println("Results: " + d));
                System.exit(ok ? 0 : 1);
            }
            case "serve", "campaign" -> {
                ApiServer server = new ApiServer(ws, bus, runLog, team, new AskService(runner, files), runner.mode(), runner.model());
                server.start(port);
                System.out.println("Dashboard: http://localhost:" + port + "   (API: /api/state, live stream: /api/events)");
                if (command.equals("campaign")) {
                    Duration budget = Duration.ofMinutes(Math.round(hours * 60));
                    Campaign campaign = new Campaign(ws, bus, team, runner, files).datasets(datasets);
                    boolean resumeCampaign = resume;
                    System.out.println(resume ? "Campaign: resuming the previous campaign (original deadline kept)"
                            : "Campaign: " + hours + " h budget, deadline " + Instant.now().plus(budget));
                    Thread.ofPlatform().name("campaign").start(() -> campaign.run(budget, resumeCampaign));
                } else if (run) {
                    Thread.ofPlatform().name("team-run").start(team::run);
                }
                Thread.currentThread().join();
            }
            default -> {
                System.err.println("Unknown command '" + command + "'. Use analyze, serve or campaign.");
                System.exit(2);
            }
        }
    }
}
