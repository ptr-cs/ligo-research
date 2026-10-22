package org.ligo.triage.agents;

import com.anthropic.models.messages.OutputConfig;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Ask the research team": the Reporter answers status questions using only the read-only team-file tools,
 * so every answer is grounded in runs.json, qa.json, tests.json, findings.json and ranked.json.
 */
public final class AskService {
    private static final List<String> ORDINALS =
            List.of("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth");
    private static final Pattern RANK = Pattern.compile("(?:#|rank(?:ed)?\\s*(?:#|no\\.?|number)?\\s*)(\\d{1,3})");
    private static final Pattern GS_ID = Pattern.compile("\\b([A-Za-z0-9]{10})\\b");

    private final AgentRunner runner;
    private final TeamFiles files;

    public AskService(AgentRunner runner, TeamFiles files) {
        this.runner = runner;
        this.files = files;
    }

    public Map<String, Object> ask(String question) {
        AgentRunner.Task task = new AgentRunner.Task(ResearchTeam.REPORTER, "ask",
                "You are the Reporter for a five-role agent team (Researcher, Developer, Tester, QA, Reporter) that triages "
                        + "Gravity Spy LIGO glitch classifications (Hanford H1 and Livingston L1, observing runs O1-O3b) for human review. Answer questions about the team's work "
                        + "using only the structured records your tools return. Read the relevant record(s) before answering. "
                        + "If the records do not contain the answer, say so. Name the files you relied on. "
                        + "Answer in at most 120 words of plain text.",
                question, files.tools(), OutputConfig.Effort.LOW, 6, t -> scriptedAnswer(question, t));
        Map<String, Object> rec = runner.run(task);
        Set<String> sources = new LinkedHashSet<>();
        if (rec.get("tool_calls") instanceof List<?> calls)
            for (Object o : calls) {
                Map<?, ?> c = (Map<?, ?>) o;
                JsonNode in = (JsonNode) c.get("input");
                if ("read_team_file".equals(c.get("tool"))) {
                    String n = in.path("name").asText();
                    sources.add(n.equals("activity") ? "activity.jsonl" : n + ".json");
                } else if ("get_ranked_event".equals(c.get("tool"))) sources.add("ranked.json");
            }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("question", question);
        out.put("answer", rec.get("summary"));
        out.put("sources", sources);
        out.put("mode", rec.get("mode"));
        out.put("asked_at", Instant.now().toString());
        return out;
    }

    /** Keyword router used when no Claude client is configured. */
    static String scriptedAnswer(String question, AgentRunner.Invoker t) throws Exception {
        String q = question.toLowerCase(Locale.ROOT);
        Integer rank = rankIn(q);
        Matcher id = GS_ID.matcher(question);
        String idMatch = null;
        while (id.find()) if (id.group(1).chars().anyMatch(Character::isDigit) && id.group(1).chars().anyMatch(Character::isUpperCase)) idMatch = id.group(1);

        if (rank != null || idMatch != null || (q.contains("why") && (q.contains("rank") || q.contains("top")))) {
            Map<String, Object> in = idMatch != null ? Map.of("gravityspy_id", idMatch) : Map.of("rank", rank == null ? 1 : rank);
            JsonNode e = t.call("get_ranked_event", in);
            JsonNode top = e.path("top_classes");
            StringBuilder sb = new StringBuilder();
            sb.append(e.path("gravityspy_id").asText()).append(" (predicted ").append(e.path("ml_label").asText()).append("): ");
            sb.append(e.path("explanation").asText());
            if (top.size() >= 3)
                sb.append(String.format(Locale.ROOT, " Top three classes: %s %.2f, %s %.2f, %s %.2f.",
                        top.get(0).path("class").asText(), top.get(0).path("probability").asDouble(),
                        top.get(1).path("class").asText(), top.get(1).path("probability").asDouble(),
                        top.get(2).path("class").asText(), top.get(2).path("probability").asDouble()));
            return sb.toString();
        }
        if (q.matches(".*\\b(qa|quality|problem|problems|issue|issues|broken|wrong|error)\\b.*")) {
            JsonNode qa = t.call("read_team_file", Map.of("name", "qa"));
            List<String> notes = new ArrayList<>();
            for (JsonNode c : qa.path("checks"))
                if (!"pass".equals(c.path("status").asText()))
                    notes.add(c.path("check").asText() + " (" + c.path("status").asText() + "): " + c.path("detail").asText());
            return "QA status: " + qa.path("status").asText() + ". "
                    + (notes.isEmpty() ? "All " + qa.path("checks").size() + " checks passed." : String.join(" ", notes));
        }
        if (q.matches(".*\\btests?\\b.*|.*\\btester\\b.*")) {
            JsonNode ts = t.call("read_team_file", Map.of("name", "tests"));
            List<String> failed = new ArrayList<>();
            for (JsonNode c : ts.path("tests")) if (!c.path("passed").asBoolean()) failed.add(c.path("name").asText());
            return String.format(Locale.ROOT, "Tests: %d of %d passed (verdict %s). %s", ts.path("passed").asInt(), ts.path("total").asInt(),
                    ts.path("verdict").asText(), failed.isEmpty() ? ts.path("summary").asText() : "Failed: " + String.join(", ", failed) + ".");
        }
        if (q.contains("class") && (q.contains("uncertain") || q.contains("ambig") || q.contains("confus"))) {
            JsonNode f = t.call("read_team_file", Map.of("name", "findings"));
            List<String> rows = new ArrayList<>();
            for (int i = 0; i < Math.min(5, f.path("class_uncertainty").size()); i++) {
                JsonNode c = f.path("class_uncertainty").get(i);
                rows.add(String.format(Locale.ROOT, "%s %d of %d (%.1f%%)", c.path("class").asText(), c.path("ambiguous").asInt(),
                        c.path("events").asInt(), 100 * c.path("ambiguous_share").asDouble()));
            }
            List<String> pairs = new ArrayList<>();
            for (int i = 0; i < Math.min(3, f.path("confusion_pairs").size()); i++)
                pairs.add(f.path("confusion_pairs").get(i).path("pair").asText() + " (" + f.path("confusion_pairs").get(i).path("ambiguous_events").asInt() + ")");
            return "Ambiguous events (" + f.path("ambiguous_definition").asText() + ") by predicted class: " + String.join("; ", rows)
                    + ". Most frequent competing pairs: " + String.join(", ", pairs) + ".";
        }
        if (q.matches(".*\\b(next|investigate|should|follow.?up|recommend).*")) {
            JsonNode f = t.call("read_team_file", Map.of("name", "findings"));
            List<String> steps = new ArrayList<>();
            int k = 1;
            for (JsonNode s : f.path("next_steps")) steps.add((k++) + ". " + s.asText());
            return String.join(" ", steps);
        }
        if (q.matches(".*\\b(dataset|checksum|sha|version|download|source|zenodo)\\b.*")) {
            JsonNode d = t.call("read_team_file", Map.of("name", "dataset"));
            return String.format(Locale.ROOT, "Dataset %s from %s: %,d bytes, sha256 %s, md5 %s (%s the pinned published checksum).",
                    d.path("version").asText(), d.path("source_url").asText(), d.path("bytes").asLong(), d.path("sha256").asText(),
                    d.path("md5").asText(), d.path("checksum_matches_pinned").asBoolean() ? "matches" : "does NOT match");
        }
        if (q.matches(".*\\b(formula|weights?|method|score|scoring|limitations?|how)\\b.*")) {
            JsonNode s = t.call("read_team_file", Map.of("name", "spec"));
            return s.path("formula").asText() + " Weights: " + s.path("weights") + ". Rationale: " + s.path("rationale").asText()
                    + " Limitations: " + String.join(" ", jsonStrings(s.path("limitations")));
        }
        JsonNode run = t.call("read_team_file", Map.of("name", "runs"));
        List<String> parts = new ArrayList<>();
        for (JsonNode s : run.path("stages"))
            parts.add(s.path("role").asText() + " " + s.path("phase").asText() + ": " + s.path("status").asText());
        return "Run " + run.path("run_id").asText() + " is " + run.path("status").asText() + " (" + run.path("agent_mode").asText()
                + " agents). " + String.join("; ", parts) + ". Ask about QA, tests, why an event is ranked, uncertain classes, or next steps.";
    }

    static Integer rankIn(String q) {
        Matcher m = RANK.matcher(q);
        if (m.find()) return Integer.parseInt(m.group(1));
        for (int i = 0; i < ORDINALS.size(); i++)
            if (q.matches(".*\\b" + ORDINALS.get(i) + "\\b.*") && (q.contains("rank") || q.contains("glitch") || q.contains("event")))
                return i + 1;
        return null;
    }

    private static List<String> jsonStrings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }
}
