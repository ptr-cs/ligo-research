package org.ligo.triage.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Columnar, immutable view of the Gravity Spy CSV. Raw cell text is kept so the
 * schema validator and QA can inspect exactly what was published.
 */
public final class GlitchTable {

    public static final List<String> CLASSES = List.of(
            "1400Ripples", "1080Lines", "Air_Compressor", "Blip", "Chirp", "Extremely_Loud", "Helix",
            "Koi_Fish", "Light_Modulation", "Low_Frequency_Burst", "Low_Frequency_Lines", "No_Glitch",
            "None_of_the_Above", "Paired_Doves", "Power_Line", "Repeating_Blips", "Scattered_Light",
            "Scratchy", "Tomte", "Violin_Mode", "Wandering_Line", "Whistle");

    /**
     * Labels the newer (O3) Gravity Spy model can assign but for which the published files carry no probability column.
     */
    public static final List<String> EXTENDED_LABELS = List.of("Blip_Low_Frequency", "Fast_Scattering");

    /** Above this share of rows with ml_confidence != max published probability, labels came from a different model. */
    public static final double MODEL_MISMATCH_THRESHOLD = 0.5;

    /** Metadata columns usable as outlier features (all strictly positive, compared on log10 scale). */
    public static final List<String> METADATA_FEATURES = List.of(
            "duration", "peak_frequency", "central_freq", "bandwidth", "snr", "q_value", "amplitude");

    public static final List<String> REQUIRED_COLUMNS;
    static {
        List<String> cols = new ArrayList<>(List.of("event_time", "ifo", "gravityspy_id"));
        cols.addAll(METADATA_FEATURES);
        cols.addAll(CLASSES);
        cols.addAll(List.of("ml_label", "ml_confidence", "url1", "url2", "url3", "url4"));
        REQUIRED_COLUMNS = List.copyOf(cols);
    }

    private final List<String> header;
    private final List<String[]> rows;

    private GlitchTable(List<String> header, List<String[]> rows) {
        this.header = List.copyOf(header);
        this.rows = rows;
    }

    public static GlitchTable load(Path csv) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String first = r.readLine();
            if (first == null) throw new IOException("Empty CSV: " + csv);
            List<String> header = parseLine(first);
            List<String[]> rows = new ArrayList<>(16_000);
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isBlank()) continue;
                rows.add(parseLine(line).toArray(String[]::new));
            }
            return new GlitchTable(header, rows);
        }
    }

    /** Minimal RFC 4180 line parser (quoted fields, doubled quotes); the published file has no embedded newlines. */
    static List<String> parseLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (c == '"') quoted = false;
                else cur.append(c);
            } else if (c == '"') quoted = true;
            else if (c == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    /** Gravity Spy publishes some events with every class probability set to 0: they were never classified. */
    public boolean isUnclassified(int row) {
        for (String c : CLASSES) if (number(row, col(c)) != 0) return false;
        return true;
    }

    /** A copy without unclassified rows; this is the population every role scores and tests. */
    public GlitchTable classifiedOnly() {
        List<String[]> kept = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) if (!isUnclassified(i)) kept.add(rows.get(i));
        return kept.size() == rows.size() ? this : new GlitchTable(header, kept);
    }

    /** Share of classified rows whose ml_confidence differs from their highest published class probability. */
    public double confidenceMismatchShare() {
        int confCol = col("ml_confidence"), n = 0, mismatch = 0;
        int[] probCols = CLASSES.stream().mapToInt(this::col).toArray();
        for (int i = 0; i < size(); i++) {
            double max = 0;
            for (int c : probCols) max = Math.max(max, number(i, c));
            if (max == 0) continue;
            n++;
            if (Math.abs(max - number(i, confCol)) > 1e-6) mismatch++;
        }
        return n == 0 ? 0 : mismatch / (double) n;
    }

    public static GlitchTable loadClassified(Path csv) throws IOException { return load(csv).classifiedOnly(); }

    public List<String> header() { return header; }
    public int size() { return rows.size(); }
    public int col(String name) { return header.indexOf(name); }

    public String raw(int row, int col) {
        String[] r = rows.get(row);
        return col >= 0 && col < r.length ? r[col] : "";
    }

    public String raw(int row, String column) { return raw(row, col(column)); }

    public int width(int row) { return rows.get(row).length; }

    public double number(int row, int col) {
        String s = raw(row, col);
        if (s.isEmpty()) return Double.NaN;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return Double.NaN; }
    }

    /** Probability matrix [row][class] in {@link #CLASSES} order. */
    public double[][] probabilities() {
        int[] idx = CLASSES.stream().mapToInt(this::col).toArray();
        double[][] p = new double[size()][idx.length];
        for (int i = 0; i < size(); i++)
            for (int k = 0; k < idx.length; k++) p[i][k] = number(i, idx[k]);
        return p;
    }

    public double[] column(String name) {
        int c = col(name);
        double[] v = new double[size()];
        for (int i = 0; i < size(); i++) v[i] = number(i, c);
        return v;
    }

    public String[] textColumn(String name) {
        int c = col(name);
        String[] v = new String[size()];
        for (int i = 0; i < size(); i++) v[i] = raw(i, c);
        return v;
    }

    @Override public String toString() {
        return "GlitchTable[" + size() + " rows, columns=" + Arrays.toString(header.toArray()) + "]";
    }
}
