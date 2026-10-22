package org.ligo.triage.agents;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A capability granted to one agent role. Roles only ever see their own tools, which is how the team's
 * separation of duties (e.g. QA cannot modify developer output) is enforced.
 */
public record AgentTool(String name, String description, Map<String, Object> properties, List<String> required,
                        Handler handler) {

    @FunctionalInterface
    public interface Handler {
        Object apply(JsonNode input) throws Exception;
    }

    public static AgentTool of(String name, String description, Handler handler) {
        return new AgentTool(name, description, Map.of(), List.of(), handler);
    }

    /** Small helpers for JSON-schema property definitions. */
    public static Map<String, Object> prop(String type, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", description);
        return m;
    }

    public static Map<String, Object> enumProp(String description, List<String> values) {
        Map<String, Object> m = prop("string", description);
        m.put("enum", values);
        return m;
    }

    public static Map<String, Object> arrayProp(String description, Map<String, Object> items) {
        Map<String, Object> m = prop("array", description);
        m.put("items", items);
        return m;
    }
}
