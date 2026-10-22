package org.ligo.triage.data;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Downloads, caches and fingerprints the Gravity Spy CSVs (one per detector and observing run). */
public final class Dataset {
    public static final String ZENODO_RECORD = "5649212";
    public static final String DEFAULT = "H1_O1";

    /**
     * MD5 checksums published on the Zenodo record, pinned so upstream changes are detected rather than silently
     * absorbed. Ordered small to large, which is also the campaign's processing order within each run.
     */
    public static final Map<String, String> PUBLISHED_MD5 = new LinkedHashMap<>();
    static {
        PUBLISHED_MD5.put("H1_O1", "91963313b1574e083bc58915e0aa8ca1");
        PUBLISHED_MD5.put("L1_O1", "34c7d08070f302d3b366767610748f40");
        PUBLISHED_MD5.put("H1_O2", "1c3eb9432107c6fee671cc76ad632e69");
        PUBLISHED_MD5.put("L1_O2", "df0a831cb79509e2aae38a4651c796fc");
        PUBLISHED_MD5.put("H1_O3a", "29aea278b622cd97496971f7c07f7d6a");
        PUBLISHED_MD5.put("L1_O3a", "3d3d0409817499c84fb2e0a2b211b5a5");
        PUBLISHED_MD5.put("H1_O3b", "590290fe3c7ddf8cd9fa85c3a09cd5e4");
        PUBLISHED_MD5.put("L1_O3b", "90d0bc16448bca72818ed9abf267979c");
    }

    private Dataset() {}

    public static String sourceUrl(String name) {
        return "https://zenodo.org/records/" + ZENODO_RECORD + "/files/" + name + ".csv?download=1";
    }

    public static String detector(String name) { return name.substring(0, 2); }
    public static String observingRun(String name) { return name.substring(3); }

    /** Ensures the CSV is cached and returns its fingerprint record (written to dataset.json). */
    public static Map<String, Object> ensureCached(String name, Path csv, boolean force) throws IOException, InterruptedException {
        String expectedMd5 = PUBLISHED_MD5.get(name);
        if (expectedMd5 == null) throw new IllegalArgumentException("Unknown dataset " + name + "; known: " + PUBLISHED_MD5.keySet());
        boolean downloaded = false;
        if (force || !Files.exists(csv) || Files.size(csv) == 0) {
            download(sourceUrl(name), csv);
            downloaded = true;
        }
        String sha256 = digest(csv, "SHA-256");
        String md5 = digest(csv, "MD5");
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("dataset", name);
        info.put("detector", detector(name));
        info.put("observing_run", observingRun(name));
        info.put("version", "zenodo-" + ZENODO_RECORD + "/" + name);
        info.put("source_url", sourceUrl(name));
        info.put("path", csv.toString());
        info.put("bytes", Files.size(csv));
        info.put("sha256", sha256);
        info.put("md5", md5);
        info.put("expected_md5", expectedMd5);
        info.put("checksum_matches_pinned", md5.equals(expectedMd5));
        info.put("downloaded_this_run", downloaded);
        info.put("checked_at", Instant.now().toString());
        return info;
    }

    private static void download(String url, Path csv) throws IOException, InterruptedException {
        Files.createDirectories(csv.getParent());
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(30)).GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) throw new IOException("Download failed: HTTP " + resp.statusCode());
        Path tmp = csv.resolveSibling(csv.getFileName() + ".part");
        try (InputStream in = resp.body()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, csv, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static String digest(Path file, String algorithm) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
