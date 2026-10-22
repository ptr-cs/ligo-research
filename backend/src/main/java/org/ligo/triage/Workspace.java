package org.ligo.triage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/** Filesystem layout shared by the pipeline, the agents and the API server. */
public record Workspace(Path root) {

    public Path cacheDir() { return root.resolve("data/cache"); }
    public Path datasetCsv(String dataset) { return cacheDir().resolve(dataset + ".csv"); }
    public Path outputDir() { return root.resolve("output"); }
    public Path runsDir() { return outputDir().resolve("runs"); }
    public Path runsJson() { return outputDir().resolve("runs.json"); }
    public Path latestPointer() { return outputDir().resolve("latest.txt"); }
    public Path webDist() { return root.resolve("web/dist"); }
    public Path campaignDir() { return outputDir().resolve("campaign"); }
    public Path campaignJson() { return campaignDir().resolve("campaign.json"); }

    public Path runDir(String runId) { return runsDir().resolve(runId); }

    /** Timestamp first so lexical order is chronological; dataset suffix keeps campaign runs distinguishable. */
    public static String newRunId(String dataset) {
        return ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")) + "_" + dataset;
    }

    /** Resolves a run id from an API request, rejecting anything that could escape the runs directory. */
    public Optional<Path> runById(String runId) {
        if (runId == null || !runId.matches("[0-9A-Za-z_]+")) return Optional.empty();
        Path dir = runDir(runId);
        return Files.isDirectory(dir) ? Optional.of(dir) : Optional.empty();
    }

    /** The most recent run whose developer output has been frozen (ranked.json exists). */
    public Optional<Path> latestCompleteRun() {
        try {
            if (Files.exists(latestPointer())) {
                Path dir = runDir(Files.readString(latestPointer()).trim());
                if (Files.exists(dir.resolve("ranked.json"))) return Optional.of(dir);
            }
            if (!Files.isDirectory(runsDir())) return Optional.empty();
            try (var s = Files.list(runsDir())) {
                return s.filter(d -> Files.exists(d.resolve("ranked.json")))
                        .max(Path::compareTo);
            }
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void markLatest(String runId) throws IOException {
        Files.createDirectories(outputDir());
        Files.writeString(latestPointer(), runId);
    }
}
