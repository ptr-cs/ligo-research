package org.ligo.triage.agents;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import org.ligo.triage.Json;
import org.ligo.triage.server.StatusBus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs one agent role for one task. In "claude" mode a Claude model drives the role's tools through a manual
 * tool-use loop; in "scripted" mode a fixed policy calls the same tools. Both modes go through
 * {@link #invoke}, so the activity log, tool permissions and results are identical in shape.
 */
public final class AgentRunner {
    private static final int MAX_RESULT_CHARS = 12_000;

    private final AnthropicClient client; // null => scripted mode
    private final String model;
    private final StatusBus bus;
    private volatile boolean fallbacksEnabled = true;

    /** A deterministic policy used when no Claude client is configured, or if the Claude loop fails. */
    @FunctionalInterface
    public interface ScriptedPolicy {
        String run(Invoker tools) throws Exception;
    }

    @FunctionalInterface
    public interface Invoker {
        JsonNode call(String tool, Map<String, Object> input) throws Exception;
    }

    static final class RefusalException extends RuntimeException {
        RefusalException() { super("model declined the request (stop_reason=refusal)"); }
    }

    public record Task(String role, String phase, String system, String prompt, List<AgentTool> tools,
                       OutputConfig.Effort effort, int maxTurns, ScriptedPolicy scripted) {}

    public AgentRunner(AnthropicClient client, String model, StatusBus bus) {
        this.client = client;
        this.model = model;
        this.bus = bus;
    }

    public String mode() { return client == null ? "scripted" : "claude"; }
    public String model() { return client == null ? null : model; }

    public Map<String, Object> run(Task task) {
        Map<String, Object> rec = new LinkedHashMap<>();
        List<Map<String, Object>> calls = new ArrayList<>();
        rec.put("mode", mode());
        rec.put("model", model());
        rec.put("tool_calls", calls);
        long[] tokens = new long[2];
        bus.publish("agent", task.role(), "Starting: " + task.phase(), Map.of("kind", "start", "mode", mode()));
        String finalText;
        try {
            if (client != null) {
                try {
                    finalText = runClaude(task, calls, tokens);
                } catch (AnthropicException | RefusalException e) {
                    bus.publish("agent", task.role(), "Claude call failed (" + e.getMessage() + "); falling back to scripted policy",
                            Map.of("kind", "error"));
                    rec.put("mode", "scripted (fallback: " + e.getClass().getSimpleName() + ")");
                    finalText = task.scripted().run((tool, input) -> invoke(task, tool, Json.tree(input), calls));
                }
            } else {
                finalText = task.scripted().run((tool, input) -> invoke(task, tool, Json.tree(input), calls));
            }
            rec.put("status", "ok");
        } catch (Exception e) {
            finalText = "Stage failed: " + e.getMessage();
            rec.put("status", "error");
            rec.put("error", String.valueOf(e.getMessage()));
        }
        rec.put("summary", finalText);
        rec.put("tokens", Map.of("input", tokens[0], "output", tokens[1]));
        bus.publish("agent", task.role(), finalText, Map.of("kind", "finish"));
        return rec;
    }

    private String runClaude(Task task, List<Map<String, Object>> calls, long[] tokens) throws Exception {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(task.system())
                .outputConfig(OutputConfig.builder().effort(task.effort()).build())
                .addUserMessage(task.prompt());
        for (AgentTool t : task.tools()) params.addTool(toSdkTool(t));

        StringBuilder transcript = new StringBuilder();
        for (int turn = 0; turn < task.maxTurns(); turn++) {
            Message resp = create(params);
            tokens[0] += resp.usage().inputTokens();
            tokens[1] += resp.usage().outputTokens();
            params.addMessage(resp);

            List<ToolUseBlock> uses = new ArrayList<>();
            for (ContentBlock block : resp.content()) {
                block.text().ifPresent(t -> {
                    if (!t.text().isBlank()) {
                        transcript.setLength(0);
                        transcript.append(t.text().trim());
                        bus.publish("agent", task.role(), t.text().trim(), Map.of("kind", "say"));
                    }
                });
                block.toolUse().ifPresent(uses::add);
            }
            StopReason stop = resp.stopReason().orElse(StopReason.END_TURN);
            if (stop.equals(StopReason.REFUSAL)) throw new RefusalException();
            if (stop.equals(StopReason.PAUSE_TURN)) continue;
            if (!stop.equals(StopReason.TOOL_USE) || uses.isEmpty()) return transcript.toString();

            // All results for this turn go back in a single user message.
            List<ContentBlockParam> results = new ArrayList<>();
            for (ToolUseBlock use : uses) {
                ToolResultBlockParam.Builder r = ToolResultBlockParam.builder().toolUseId(use.id());
                try {
                    JsonNode input = use._input().convert(JsonNode.class);
                    r.content(truncate(Json.compact(invoke(task, use.name(), input, calls))));
                } catch (Exception e) {
                    r.content("Error: " + e.getMessage()).isError(true);
                }
                results.add(ContentBlockParam.ofToolResult(r.build()));
            }
            params.addMessage(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build());
        }
        return transcript.isEmpty() ? "Stopped after " + task.maxTurns() + " turns." : transcript.toString();
    }

    /** Sends the request with server-side refusal fallbacks; drops them once if the account rejects the parameter. */
    private Message create(MessageCreateParams.Builder params) {
        if (fallbacksEnabled) {
            MessageCreateParams withFallback = params.build().toBuilder()
                    .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                    .build();
            try {
                return client.messages().create(withFallback);
            } catch (BadRequestException e) {
                if (!String.valueOf(e.getMessage()).toLowerCase().contains("fallback")) throw e;
                fallbacksEnabled = false;
                bus.info(null, "Server-side fallbacks not available for this account; continuing without them");
            }
        }
        return client.messages().create(params.build());
    }

    /** Executes a tool on behalf of a role, enforcing that the role was granted it. */
    private JsonNode invoke(Task task, String toolName, JsonNode input, List<Map<String, Object>> calls) throws Exception {
        AgentTool tool = task.tools().stream().filter(t -> t.name().equals(toolName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(task.role() + " has no tool named " + toolName));
        bus.publish("agent", task.role(), "→ " + toolName + (input.isEmpty() ? "" : " " + truncate(Json.compact(input), 200)),
                Map.of("kind", "tool_call", "tool", toolName));
        long t0 = System.nanoTime();
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("tool", toolName);
        call.put("input", input);
        try {
            JsonNode out = Json.tree(tool.handler().apply(input));
            call.put("ok", true);
            call.put("ms", (System.nanoTime() - t0) / 1_000_000);
            calls.add(call);
            bus.publish("agent", task.role(), "← " + toolName + " ok (" + call.get("ms") + " ms)",
                    Map.of("kind", "tool_result", "tool", toolName, "ok", true));
            return out;
        } catch (Exception e) {
            call.put("ok", false);
            call.put("error", String.valueOf(e.getMessage()));
            call.put("ms", (System.nanoTime() - t0) / 1_000_000);
            calls.add(call);
            bus.publish("agent", task.role(), "← " + toolName + " error: " + e.getMessage(),
                    Map.of("kind", "tool_result", "tool", toolName, "ok", false));
            throw e;
        }
    }

    private static Tool toSdkTool(AgentTool t) {
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        t.properties().forEach((k, v) -> props.putAdditionalProperty(k, JsonValue.from(v)));
        return Tool.builder()
                .name(t.name())
                .description(t.description())
                .inputSchema(Tool.InputSchema.builder().properties(props.build()).required(t.required()).build())
                .build();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String truncate(String s) { return truncate(s, MAX_RESULT_CHARS); }
}
