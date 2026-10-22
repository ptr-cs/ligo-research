package org.ligo.triage.agents;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.ligo.triage.Json;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AskServiceTest {

    @Test
    void parsesRankReferences() {
        assertEquals(1, AskService.rankIn("why is this glitch ranked first?"));
        assertEquals(3, AskService.rankIn("explain #3"));
        assertEquals(12, AskService.rankIn("why is rank 12 there"));
        assertNull(AskService.rankIn("did qa find any problems?"));
    }

    /** Records which team file the scripted Reporter consults for each question. */
    private static List<String> route(String question) throws Exception {
        List<String> calls = new ArrayList<>();
        AskService.scriptedAnswer(question, (tool, input) -> {
            calls.add(tool + ":" + input.getOrDefault("name", input.getOrDefault("rank", input.get("gravityspy_id"))));
            JsonNode stub = Json.parse("{\"checks\":[],\"tests\":[],\"stages\":[],\"next_steps\":[],\"class_uncertainty\":[],"
                    + "\"confusion_pairs\":[],\"top_classes\":[],\"limitations\":[]}");
            return stub;
        });
        return calls;
    }

    @Test
    void routesQuestionsToGroundingFiles() throws Exception {
        assertEquals(List.of("read_team_file:runs"), route("What has the team completed?"));
        assertEquals(List.of("read_team_file:qa"), route("Did QA find any problems?"));
        assertEquals(List.of("get_ranked_event:1"), route("Why is this glitch ranked first?"));
        assertEquals(List.of("read_team_file:findings"), route("Which classes generate the most uncertainty?"));
        assertEquals(List.of("read_team_file:findings"), route("What should the team investigate next?"));
        assertTrue(route("What is the dataset checksum?").contains("read_team_file:dataset"));
    }

    @Test
    void gravitySpyIdIsRecognised() throws Exception {
        assertEquals(List.of("get_ranked_event:sd0LHzvDRl"), route("Tell me about sd0LHzvDRl"));
    }
}
