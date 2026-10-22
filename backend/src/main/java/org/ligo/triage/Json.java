package org.ligo.triage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Thin wrapper around a shared Jackson mapper. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    private static final ObjectMapper COMPACT = new ObjectMapper();

    private Json() {}

    public static ObjectNode object() { return MAPPER.createObjectNode(); }

    public static String pretty(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public static String compact(Object value) {
        try { return COMPACT.writeValueAsString(value); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public static JsonNode tree(Object value) { return MAPPER.valueToTree(value); }

    public static JsonNode parse(String text) {
        try { return MAPPER.readTree(text); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public static JsonNode read(Path path) throws IOException { return MAPPER.readTree(path.toFile()); }

    /** Atomic write: temp file then move, so readers never see a partial document. */
    public static void write(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, pretty(value));
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
