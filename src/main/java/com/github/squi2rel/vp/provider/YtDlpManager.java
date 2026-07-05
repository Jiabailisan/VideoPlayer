package com.github.squi2rel.vp.provider;

import com.github.squi2rel.vp.DataHolder;
import com.github.squi2rel.vp.VideoPlayerMain;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class YtDlpManager {
    private static final String RELEASE_BASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/";
    private static final URI NODE_INDEX = URI.create("https://nodejs.org/dist/index.json");
    private static final Pattern NODE_LTS_VERSION = Pattern.compile("\\{\"version\":\"(v[^\"]+)\"[^}]*\"lts\":\"[^\"]+\"");
    private static final YtDlpManager INSTANCE = new YtDlpManager();

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public static YtDlpManager getInstance() {
        return INSTANCE;
    }

    public void prepareAsync(boolean forceDownload) {
        CompletableFuture.runAsync(() -> {
            try {
                prepare(forceDownload);
            } catch (Exception e) {
                VideoPlayerMain.LOGGER.warn("Failed to prepare yt-dlp", e);
            }
        }, VideoPlayerMain.scheduler);
    }

    public synchronized Path prepare(boolean forceDownload) throws Exception {
        Files.createDirectories(dataDir());
        Files.createDirectories(toolsDir());
        Path executable = executablePath();
        if (forceDownload || Files.notExists(executable) || Files.size(executable) == 0) {
            download(executable);
        }
        try {
            prepareNodeRuntime(forceDownload);
        } catch (Exception e) {
            VideoPlayerMain.LOGGER.warn("Failed to prepare Node.js runtime for yt-dlp", e);
        }
        executable.toFile().setExecutable(true, false);
        Path cookies = cookiePath();
        if (Files.notExists(cookies)) {
            Files.createFile(cookies);
        }
        DataHolder.config.providerAuth.ytDlpPath = dataDir().relativize(executable).toString();
        DataHolder.config.providerAuth.youtubeCookieFile = dataDir().relativize(cookies).toString();
        DataHolder.save();
        return executable;
    }

    public Path executablePath() {
        String configured = DataHolder.config == null || DataHolder.config.providerAuth == null ? "" : DataHolder.config.providerAuth.ytDlpPath;
        if (configured != null && !configured.isBlank()) {
            return resolveInData(configured);
        }
        return toolsDir().resolve(assetName());
    }

    public Path cookiePath() {
        String configured = DataHolder.config == null || DataHolder.config.providerAuth == null ? "" : DataHolder.config.providerAuth.youtubeCookieFile;
        if (configured == null || configured.isBlank()) configured = "youtube-cookies.txt";
        return resolveInData(configured);
    }

    public String executableCommand() {
        return executablePath().toAbsolutePath().toString();
    }

    public String cookieCommand() {
        return cookiePath().toAbsolutePath().toString();
    }

    public Path cacheDir() {
        return dataDir().resolve("youtube-cache");
    }

    public List<String> jsRuntimeArgs() {
        Path node = nodeExecutablePath();
        if (Files.exists(node)) {
            return List.of("--js-runtimes", "node:" + node.toAbsolutePath());
        }
        return List.of();
    }

    private void download(Path output) throws Exception {
        downloadFirstAvailable(ytDlpAssetNames(), output, Duration.ofMinutes(3));
    }

    private void downloadFirstAvailable(List<String> assets, Path output, Duration timeout) throws Exception {
        Exception last = null;
        for (String asset : assets) {
            URI uri = URI.create(RELEASE_BASE + asset);
            try {
                downloadUri(uri, output, timeout);
                return;
            } catch (Exception e) {
                last = e;
                VideoPlayerMain.LOGGER.warn("Failed to download {}, trying next candidate if available", uri, e);
            }
        }
        throw last == null ? new IllegalStateException("No download candidates") : last;
    }

    private void downloadUri(URI uri, Path output, Duration timeout) throws Exception {
        Path temp = output.resolveSibling(output.getFileName() + ".tmp");
        VideoPlayerMain.LOGGER.info("Downloading yt-dlp from {}", uri);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", "VideoPlayer")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("GitHub returned HTTP " + response.statusCode());
        }
        try (InputStream in = response.body()) {
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
    }

    private void prepareNodeRuntime(boolean forceDownload) throws Exception {
        if (!isWindows()) return;
        Path node = nodeExecutablePath();
        if (!forceDownload && Files.exists(node) && Files.size(node) > 0) return;
        Files.createDirectories(node.getParent());

        String version = latestLtsNodeVersion();
        String asset = nodeAssetName(version);
        URI uri = URI.create("https://nodejs.org/dist/" + version + "/" + asset);
        Path temp = toolsDir().resolve(asset + ".tmp");
        VideoPlayerMain.LOGGER.info("Downloading Node.js runtime from {}", uri);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "VideoPlayer")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("nodejs.org returned HTTP " + response.statusCode());
        }
        try (InputStream in = response.body()) {
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        extractNodeRuntime(temp, node);
        Files.deleteIfExists(temp);
    }

    private String latestLtsNodeVersion() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(NODE_INDEX)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "VideoPlayer")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("nodejs.org index returned HTTP " + response.statusCode());
        }
        Matcher matcher = NODE_LTS_VERSION.matcher(response.body());
        if (!matcher.find()) throw new IllegalStateException("No LTS Node.js version found");
        return matcher.group(1);
    }

    private static void extractNodeRuntime(Path archive, Path output) throws Exception {
        String name = archive.getFileName().toString();
        if (name.endsWith(".zip") || name.endsWith(".zip.tmp")) {
            extractNodeFromZip(archive, output);
        } else {
            extractNodeFromTarGz(archive, output);
        }
    }

    private static void extractNodeFromZip(Path zip, Path output) throws Exception {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (!entry.isDirectory() && isNodeExecutableEntry(name)) {
                    Files.copy(in, output, StandardCopyOption.REPLACE_EXISTING);
                    output.toFile().setExecutable(true, false);
                    return;
                }
            }
        }
        throw new IllegalStateException("node executable not found in Node.js zip");
    }

    private static void extractNodeFromTarGz(Path tarGz, Path output) throws Exception {
        try (InputStream file = Files.newInputStream(tarGz);
             GZIPInputStream gzip = new GZIPInputStream(file)) {
            byte[] header = new byte[512];
            while (readFully(gzip, header, 0, 512) == 512) {
                if (isTarEnd(header)) break;
                String name = tarName(header);
                long size = tarSize(header);
                if (isNodeExecutableEntry(name)) {
                    Files.copy(new BoundedInputStream(gzip, size), output, StandardCopyOption.REPLACE_EXISTING);
                    output.toFile().setExecutable(true, false);
                    return;
                }
                skipFully(gzip, roundUpTar(size));
            }
        }
        throw new IllegalStateException("node executable not found in Node.js tar.gz");
    }

    private static boolean isNodeExecutableEntry(String name) {
        return name.endsWith("/node.exe") || name.endsWith("/bin/node");
    }

    private static int readFully(InputStream in, byte[] buffer, int off, int len) throws Exception {
        int total = 0;
        while (total < len) {
            int read = in.read(buffer, off + total, len - total);
            if (read < 0) break;
            total += read;
        }
        return total;
    }

    private static boolean isTarEnd(byte[] header) {
        for (byte b : header) {
            if (b != 0) return false;
        }
        return true;
    }

    private static String tarName(byte[] header) {
        int len = 0;
        while (len < 100 && header[len] != 0) len++;
        return new String(header, 0, len).replace('\\', '/');
    }

    private static long tarSize(byte[] header) {
        long size = 0;
        for (int i = 124; i < 136; i++) {
            byte b = header[i];
            if (b == 0 || b == ' ') continue;
            size = size * 8 + (b - '0');
        }
        return size;
    }

    private static long roundUpTar(long size) {
        return ((size + 511) / 512) * 512;
    }

    private static void skipFully(InputStream in, long bytes) throws Exception {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) break;
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private Path dataDir() {
        return DataHolder.dataDir;
    }

    private Path toolsDir() {
        return dataDir().resolve("tools");
    }

    private Path nodeExecutablePath() {
        return toolsDir().resolve("node").resolve(isWindows() ? "node.exe" : "node");
    }

    private Path resolveInData(String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            path = dataDir().resolve(path);
        }
        return path.normalize();
    }

    private static List<String> ytDlpAssetNames() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = normalizedArch();
        if (os.contains("win")) return List.of("yt-dlp.exe");
        if (os.contains("mac")) return List.of("yt-dlp_macos", "yt-dlp");
        if (arch.equals("arm64")) return List.of("yt-dlp_linux_aarch64", "yt-dlp");
        if (arch.equals("x64")) return List.of("yt-dlp_linux", "yt-dlp");
        return List.of("yt-dlp");
    }

    private static String assetName() {
        return ytDlpAssetNames().getFirst();
    }

    private static String nodeAssetName(String version) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = normalizedArch();
        if (os.contains("win")) return "node-" + version + "-win-" + arch + ".zip";
        if (os.contains("mac")) return "node-" + version + "-darwin-" + arch + ".tar.gz";
        return "node-" + version + "-linux-" + arch + ".tar.gz";
    }

    private static String normalizedArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.equals("amd64") || arch.equals("x86_64")) return "x64";
        if (arch.equals("aarch64") || arch.equals("arm64")) return "arm64";
        if (arch.startsWith("arm")) return "armv7l";
        return arch;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static class BoundedInputStream extends InputStream {
        private final InputStream delegate;
        private long remaining;

        private BoundedInputStream(InputStream delegate, long remaining) {
            this.delegate = delegate;
            this.remaining = remaining;
        }

        @Override
        public int read() throws java.io.IOException {
            if (remaining <= 0) return -1;
            int value = delegate.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws java.io.IOException {
            if (remaining <= 0) return -1;
            int read = delegate.read(b, off, (int) Math.min(len, remaining));
            if (read > 0) remaining -= read;
            return read;
        }
    }
}
