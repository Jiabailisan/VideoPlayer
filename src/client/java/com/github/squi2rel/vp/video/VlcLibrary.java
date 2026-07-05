package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.VideoPlayerMain;
import com.sun.jna.NativeLibrary;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class VlcLibrary {
    private static final String VLC_DIR_NAME = "VideoPlayer-vlc3";
    private static final String RELEASE_BASE = "https://github.com/squi2rel/VideoPlayer-Library/releases/latest/download/";
    private static boolean prepared = false;

    private VlcLibrary() {
    }

    public static synchronized void prepare() {
        if (prepared) return;
        try {
            ensureInstalled(false);
        } catch (Exception e) {
            throw new RuntimeException("Cannot prepare VideoPlayer VLC library", e);
        }
        Path dir = dir();
        Path plugins = pluginDir();
        registerSearchPath(dir);
        deletePluginCache(plugins);
        prepared = true;
    }

    public static synchronized void redownload() throws Exception {
        prepared = false;
        ensureInstalled(true);
    }

    public static String statusText() {
        return "VLC库: " + (libraryLooksInstalled() ? "已安装" : "未安装")
                + " | 目录: " + dir().toAbsolutePath()
                + " | 平台包: " + assetName();
    }

    public static Path dir() {
        return FabricLoader.getInstance().getGameDir().resolve("mods").resolve(VLC_DIR_NAME);
    }

    public static Path pluginDir() {
        return dir().resolve("plugins");
    }

    public static String pluginOption() {
        return "--plugin-path=" + pluginDir().toAbsolutePath();
    }

    private static void ensureInstalled(boolean force) throws Exception {
        if (!force && libraryLooksInstalled()) return;
        if (force) deleteDirectoryContents(dir());
        Files.createDirectories(dir());
        Path temp = dir().resolveSibling(VLC_DIR_NAME + ".zip.tmp");
        URI uri = URI.create(RELEASE_BASE + assetName());
        VideoPlayerMain.LOGGER.info("Downloading VideoPlayer VLC library from {}", uri);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "VideoPlayer")
                .GET()
                .build();
        HttpResponse<InputStream> response = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("GitHub returned HTTP " + response.statusCode());
        }
        try {
            try (InputStream in = response.body()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            unzip(temp, dir());
        } finally {
            Files.deleteIfExists(temp);
        }
        if (!libraryLooksInstalled()) {
            throw new IllegalStateException("Downloaded VLC library does not contain libvlc and plugins");
        }
    }

    private static boolean libraryLooksInstalled() {
        try (Stream<Path> paths = Files.walk(dir(), 3)) {
            return Files.isDirectory(pluginDir()) && paths
                    .anyMatch(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.equals("libvlc.dll") || name.equals("libvlc.so") || name.startsWith("libvlc.so.") || name.equals("libvlc.dylib");
                    });
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteDirectoryContents(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(dir)) Files.deleteIfExists(path);
            }
        }
    }

    private static String assetName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String normalized = arch.equals("amd64") || arch.equals("x86_64") ? "x64"
                : arch.equals("aarch64") || arch.equals("arm64") ? "arm64"
                : arch.contains("86") ? "x86"
                : arch;
        if (os.contains("win")) return "libvlc-windows-" + normalized + ".zip";
        if (os.contains("mac")) return "libvlc-macos-" + normalized + ".zip";
        return "libvlc-linux-" + normalized + ".zip";
    }

    private static void unzip(Path zip, Path targetDir) throws Exception {
        Path root = targetDir.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path out = root.resolve(entry.getName()).normalize();
                if (!out.startsWith(root)) throw new IllegalStateException("Invalid zip entry: " + entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void registerSearchPath(Path dir) {
        String path = dir.toAbsolutePath().toString();
        appendProperty("jna.library.path", path);
        appendProperty("jna.platform.library.path", path);
        NativeLibrary.addSearchPath("vlc", path);
        NativeLibrary.addSearchPath("libvlc", path);
        NativeLibrary.addSearchPath("libvlccore", path);
        VideoPlayerMain.LOGGER.info("VLC native search path: {}", path);
    }

    private static void appendProperty(String key, String path) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            System.setProperty(key, path);
            return;
        }
        for (String part : value.split(java.io.File.pathSeparator)) {
            if (part.equalsIgnoreCase(path)) return;
        }
        System.setProperty(key, value + java.io.File.pathSeparator + path);
    }

    private static void deletePluginCache(Path pluginDir) {
        try {
            Files.deleteIfExists(pluginDir.resolve("plugins.dat"));
        } catch (Exception e) {
            VideoPlayerMain.LOGGER.warn("Cannot delete VLC plugin cache at {}", pluginDir.resolve("plugins.dat"), e);
        }
    }
}
