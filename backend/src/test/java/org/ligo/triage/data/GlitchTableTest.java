package org.ligo.triage.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlitchTableTest {

    @Test
    void parsesQuotedFields() {
        assertEquals(List.of("a", "b,c", "d\"e", ""), GlitchTable.parseLine("a,\"b,c\",\"d\"\"e\","));
    }

    private static String row(String id, String label, double[] p) {
        List<String> cells = new ArrayList<>(List.of("1.0", "H1", id, "1.0", "100", "200", "300", "10", "22.6", "1e-22"));
        for (double v : p) cells.add(Double.toString(v));
        cells.addAll(List.of(label, Double.toString(max(p)), "http://x/1.png", "http://x/2.png", "http://x/3.png", "http://x/4.png"));
        return String.join(",", cells);
    }

    private static double max(double[] p) {
        double m = 0;
        for (double v : p) m = Math.max(m, v);
        return m;
    }

    private static Path writeCsv(Path dir, List<String> rows) throws Exception {
        List<String> header = new ArrayList<>(List.of("event_time", "ifo", "gravityspy_id"));
        header.addAll(GlitchTable.METADATA_FEATURES);
        header.addAll(GlitchTable.CLASSES);
        header.addAll(List.of("ml_label", "ml_confidence", "url1", "url2", "url3", "url4"));
        Path f = dir.resolve("t.csv");
        List<String> lines = new ArrayList<>();
        lines.add(String.join(",", header));
        lines.addAll(rows);
        Files.write(f, lines);
        return f;
    }

    @Test
    void validSchemaPasses(@TempDir Path dir) throws Exception {
        double[] p = new double[22];
        p[3] = 1.0;
        GlitchTable t = GlitchTable.load(writeCsv(dir, List.of(row("aaaaaaaaa1", "Blip", p))));
        Map<String, Object> v = SchemaValidator.validate(t);
        assertTrue((Boolean) v.get("valid"), v.toString());
    }

    @Test
    void badProbabilitySumFails(@TempDir Path dir) throws Exception {
        double[] p = new double[22];
        p[3] = 0.7;
        GlitchTable t = GlitchTable.load(writeCsv(dir, List.of(row("aaaaaaaaa1", "Blip", p))));
        Map<String, Object> v = SchemaValidator.validate(t);
        assertFalse((Boolean) v.get("valid"));
        assertTrue(v.get("errors").toString().contains("do not sum to 1"));
    }

    @Test
    void unknownLabelFails(@TempDir Path dir) throws Exception {
        double[] p = new double[22];
        p[3] = 1.0;
        GlitchTable t = GlitchTable.load(writeCsv(dir, List.of(row("aaaaaaaaa1", "Not_A_Class", p))));
        assertFalse((Boolean) SchemaValidator.validate(t).get("valid"));
    }
}
