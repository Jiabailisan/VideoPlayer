package com.github.squi2rel.vp.folia;

import com.github.squi2rel.vp.folia.network.ServerPacketHandler;
import com.github.squi2rel.vp.folia.provider.bilibili.BiliBiliProvider;
import com.github.squi2rel.vp.folia.video.VideoArea;
import com.github.squi2rel.vp.folia.video.VideoScreen;
import com.google.gson.Gson;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

public class DataHolder {
    public ServerConfig config = new ServerConfig();
    public final ArrayList<UUID> allPlayers = new ArrayList<>();
    public final HashMap<UUID, String> playerDim = new HashMap<>();
    public final HashMap<String, HashMap<String, VideoArea>> areas = new HashMap<>();

    private final VideoPlayerFoliaPlugin plugin;
    private final Gson gson = new Gson();
    private final ReentrantLock lock = new ReentrantLock();
    private final Path configPath;

    public DataHolder(VideoPlayerFoliaPlugin plugin) {
        this.plugin = plugin;
        this.configPath = plugin.getDataFolder().toPath().resolve("videoplayer.json");
    }

    public void updatePlayer(Player player) {
        if (!allPlayers.contains(player.getUniqueId())) return;
        String dim = player.getWorld().getKey().toString();
        lock();
        try {
            HashMap<String, VideoArea> all = areas.get(dim);
            if (all != null && !all.isEmpty()) {
                for (VideoArea area : all.values()) {
                    if (area.inBounds(player.getLocation())) {
                        if (area.addPlayer(player.getUniqueId())) {
                            plugin.sendTo(player, ServerPacketHandler.createArea(area));
                            if (!area.screens.isEmpty()) {
                                plugin.sendTo(player, ServerPacketHandler.createScreen(area.screens));
                            }
                            plugin.sendTo(player, ServerPacketHandler.loadArea(area));
                            if (!area.screens.isEmpty()) {
                                plugin.sendTo(player, ServerPacketHandler.updatePlaylist(area.screens));
                            }
                            player.sendActionBar(Component.text("进入观影区 " + area.name, NamedTextColor.DARK_AQUA));
                        }
                    } else if (area.removePlayer(player.getUniqueId())) {
                        plugin.sendTo(player, ServerPacketHandler.unloadArea(area));
                        plugin.sendTo(player, ServerPacketHandler.removeArea(area));
                        player.sendActionBar(Component.text("离开观影区 " + area.name, NamedTextColor.DARK_AQUA));
                    }
                }
            }

            String oldDim = playerDim.get(player.getUniqueId());
            if (oldDim != null && !oldDim.equals(dim)) {
                HashMap<String, VideoArea> map = areas.get(oldDim);
                if (map != null) {
                    for (VideoArea area : map.values()) {
                        if (area.removePlayer(player.getUniqueId())) {
                            plugin.sendTo(player, ServerPacketHandler.unloadArea(area));
                            plugin.sendTo(player, ServerPacketHandler.removeArea(area));
                        }
                    }
                }
            }
            playerDim.put(player.getUniqueId(), dim);
        } finally {
            unlock();
        }
    }

    public void playerJoin(Player player) {
        plugin.sendTo(player, ServerPacketHandler.config(plugin.getProtocolVersion(), config, player.hasPermission("videoplayer.admin")));
    }

    public void playerLeave(UUID uuid) {
        lock();
        try {
            allPlayers.remove(uuid);
            playerDim.remove(uuid);
        } finally {
            unlock();
        }
        CompletableFuture.runAsync(() -> {
            lock();
            try {
                for (HashMap<String, VideoArea> value : areas.values()) {
                    for (VideoArea area : value.values()) {
                        area.removePlayer(uuid);
                    }
                }
            } finally {
                unlock();
            }
        }, plugin.getCoreExecutor());
    }

    public void unload() {
        lock();
        try {
            for (HashMap<String, VideoArea> map : areas.values()) {
                for (VideoArea area : map.values()) {
                    if (!area.hasPlayer()) continue;
                    byte[] data = ServerPacketHandler.removeArea(area);
                    area.forEachPlayer(u -> {
                        Player player = Bukkit.getPlayer(u);
                        if (player != null) plugin.sendTo(player, data);
                    });
                }
            }
        } finally {
            unlock();
        }
    }

    public void save() {
        lock();
        try {
            ArrayList<VideoArea> all = new ArrayList<>();
            for (HashMap<String, VideoArea> child : areas.values()) {
                all.addAll(child.values());
            }
            config.areas = all;
            writeString(configPath, gson.toJson(config));
        } finally {
            unlock();
        }
    }

    public void load() {
        lock();
        try {
            try {
                config = gson.fromJson(readString(configPath), ServerConfig.class);
            } catch (Exception e) {
                config = new ServerConfig();
                save();
            }
            if (config.providerAuth == null) config.providerAuth = new ServerConfig.ProviderAuth();
            if (config.providerAuth.ytDlpPath == null || config.providerAuth.ytDlpPath.isBlank() || "yt-dlp".equals(config.providerAuth.ytDlpPath)) {
                config.providerAuth.ytDlpPath = "";
            }
            if (config.providerAuth.youtubeCookieFile == null || config.providerAuth.youtubeCookieFile.isBlank()) {
                config.providerAuth.youtubeCookieFile = "youtube-cookies.txt";
            }
            if (config.providerAuth.bilibiliCookieFile == null || config.providerAuth.bilibiliCookieFile.isBlank()) {
                config.providerAuth.bilibiliCookieFile = "bilibili-cookies.txt";
            }
            if (config.providerAuth.bilibiliCookie == null) config.providerAuth.bilibiliCookie = "";
            if (config.providerAuth.youtubeCookie == null) config.providerAuth.youtubeCookie = "";
            ensureCookieFile(bilibiliCookiePath());
            BiliBiliProvider.configureAuthCookie(effectiveBilibiliCookie());
            areas.clear();
            if (config.areas == null) config.areas = new ArrayList<>();
            for (VideoArea area : config.areas) {
                dedupeScreens(area);
                for (VideoScreen screen : area.screens) {
                    if (screen.meta == null) screen.meta = new HashMap<>();
                }
                area.initServer();
                area.afterLoad();
                areas.computeIfAbsent(area.dim, k -> new HashMap<>()).put(area.name, area);
            }
            config.areas = null;
        } finally {
            unlock();
        }
    }

    private static void dedupeScreens(VideoArea area) {
        java.util.LinkedHashMap<String, VideoScreen> unique = new java.util.LinkedHashMap<>();
        for (VideoScreen screen : area.screens) {
            screen.source = VideoScreen.normalizeSource(screen.source);
            unique.put(screen.name, screen);
        }
        area.screens.clear();
        area.screens.addAll(unique.values());
    }

    public void lock() {
        lock.lock();
    }

    public void unlock() {
        lock.unlock();
    }

    public String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void writeString(Path path, String str) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, str);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Path bilibiliCookiePath() {
        String configured = config == null || config.providerAuth == null ? "" : config.providerAuth.bilibiliCookieFile;
        if (configured == null || configured.isBlank()) configured = "bilibili-cookies.txt";
        Path path = Path.of(configured);
        return path.isAbsolute() ? path.normalize() : plugin.getDataFolder().toPath().resolve(path).normalize();
    }

    public String effectiveBilibiliCookie() {
        String configured = config == null || config.providerAuth == null ? "" : config.providerAuth.bilibiliCookie;
        if (configured != null && !configured.isBlank()) return configured.trim();
        try {
            Path path = bilibiliCookiePath();
            if (Files.notExists(path) || Files.size(path) == 0) return "";
            return normalizeCookie(Files.readString(path));
        } catch (Exception e) {
            plugin.getLogger().warning("Cannot read Bilibili cookie file: " + e);
            return "";
        }
    }

    public String effectiveYoutubeCookie() {
        String configured = config == null || config.providerAuth == null ? "" : config.providerAuth.youtubeCookie;
        return configured == null ? "" : configured.trim();
    }

    public static String normalizeCookie(String content) {
        if (content == null) return "";
        String text = content.trim();
        if (text.isBlank()) return "";
        if (!text.startsWith("#") && !text.contains("\t")) return text;
        ArrayList<String> pairs = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.startsWith("#")) continue;
            String[] parts = trimmed.split("\\t");
            if (parts.length < 7) continue;
            String name = parts[5].trim();
            String value = parts[6].trim();
            if (!name.isBlank()) pairs.add(name + "=" + value);
        }
        return String.join("; ", pairs);
    }

    private void ensureCookieFile(Path path) {
        try {
            Files.createDirectories(path.getParent());
            if (Files.notExists(path)) Files.createFile(path);
        } catch (IOException e) {
            plugin.getLogger().warning("Cannot create cookie file " + path + ": " + e);
        }
    }

    public VideoArea findArea(Player player, String name) {
        String dim = player.getWorld().getKey().toString();
        lock();
        try {
            Map<String, VideoArea> byDim = areas.get(dim);
            if (byDim == null) return null;
            VideoArea area = byDim.get(name);
            return area != null && area.containsPlayer(player.getUniqueId()) ? area : null;
        } finally {
            unlock();
        }
    }
}
