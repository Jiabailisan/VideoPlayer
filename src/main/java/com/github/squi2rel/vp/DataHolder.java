package com.github.squi2rel.vp;

import com.github.squi2rel.vp.network.ServerPacketHandler;
import com.github.squi2rel.vp.provider.YtDlpManager;
import com.github.squi2rel.vp.provider.bilibili.BiliBiliAuthManager;
import com.github.squi2rel.vp.provider.bilibili.BiliBiliProvider;
import com.github.squi2rel.vp.video.VideoArea;
import com.github.squi2rel.vp.video.VideoScreen;
import com.google.gson.Gson;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

public class DataHolder {
    public static ServerConfig config = new ServerConfig();
    public static ArrayList<UUID> allPlayers = new ArrayList<>();
    public static HashMap<UUID, String> playerDim = new HashMap<>();

    public static final Path dataDir = FabricLoader.getInstance().getConfigDir().resolve("videoplayer");
    public static final Path configPath = dataDir.resolve("videoplayer.json");
    public static final Path legacyConfigPath = FabricLoader.getInstance().getConfigDir().resolve("videoplayer.json");
    public static HashMap<String, HashMap<String, VideoArea>> areas = new HashMap<>();

    private static final Gson gson = new Gson();
    private static final ReentrantLock lock = new ReentrantLock();

    public static MinecraftServer server;

    public static void update() {
        PlayerManager pm = server.getPlayerManager();
        lock();
        for (UUID uuid : allPlayers) {
            ServerPlayerEntity player = pm.getPlayer(uuid);
            if (player == null) continue;
            String dim = player.getEntityWorld().getRegistryKey().getValue().toString();
            HashMap<String, VideoArea> all = areas.get(dim);
            if (all == null || all.isEmpty()) continue;
            for (VideoArea area : all.values()) {
                if (area.inBounds(player.getEntityPos())) {
                    if (area.addPlayer(player.getUuid())) {
                        ServerPacketHandler.sendTo(player, ServerPacketHandler.createArea(area));
                        if (area.screens.isEmpty()) {
                            ServerPacketHandler.sendTo(player, ServerPacketHandler.loadArea(area));
                            continue;
                        }
                        ServerPacketHandler.sendTo(player, ServerPacketHandler.createScreen(area.screens));
                        ServerPacketHandler.sendTo(player, ServerPacketHandler.loadArea(area));
                        ServerPacketHandler.sendTo(player, ServerPacketHandler.updatePlaylist(area.screens));
                        player.sendMessage(Text.literal("进入观影区 " + area.name).formatted(Formatting.DARK_AQUA), true);
                    }
                } else {
                    if (area.removePlayer(player.getUuid())) {
                        ServerPacketHandler.sendTo(player, ServerPacketHandler.unloadArea(area));
                        ServerPacketHandler.sendTo(player, ServerPacketHandler.removeArea(area));
                        player.sendMessage(Text.literal("离开观影区 " + area.name).formatted(Formatting.DARK_AQUA), true);
                    }
                }
            }
        }
        for (Map.Entry<UUID, String> entry : playerDim.entrySet()) {
            ServerPlayerEntity player = pm.getPlayer(entry.getKey());
            if (player == null) continue;
            String dim = player.getEntityWorld().getRegistryKey().getValue().toString();
            if (!dim.equals(entry.getValue())) {
                HashMap<String, VideoArea> map = areas.get(entry.getValue());
                if (map == null) continue;
                for (VideoArea area : map.values()) {
                    if (area.removePlayer(player.getUuid())) {
                        ServerPacketHandler.sendTo(player, ServerPacketHandler.unloadArea(area));
                        ServerPacketHandler.sendTo(player, ServerPacketHandler.removeArea(area));
                    }
                }
            }
        }
        for (ServerPlayerEntity player : PlayerLookup.all(server)) {
            playerDim.put(player.getUuid(), player.getEntityWorld().getRegistryKey().getValue().toString());
        }
        unlock();
    }

    public static void lock() {
        lock.lock();
    }

    public static void unload(MinecraftServer s) {
        PlayerManager pm = s.getPlayerManager();
        lock();
        for (HashMap<String, VideoArea> map : areas.values()) {
            for (VideoArea area : map.values()) {
                if (!area.hasPlayer()) continue;
                byte[] data = ServerPacketHandler.removeArea(area);
                area.forEachPlayer(u -> ServerPacketHandler.sendTo(pm.getPlayer(u), data));
            }
        }
        unlock();
    }

    public static void playerJoin(ServerPlayerEntity player) {
        ServerPacketHandler.sendTo(player, ServerPacketHandler.config(VideoPlayerMain.version, config, ServerPacketHandler.isAdmin(player)));
    }

    public static void playerLeave(UUID uuid) {
        lock();
        allPlayers.remove(uuid);
        playerDim.remove(uuid);
        unlock();
        CompletableFuture.runAsync(() -> {
            lock.lock();
            for (HashMap<String, VideoArea> value : areas.values()) {
                for (VideoArea area : value.values()) {
                    area.removePlayer(uuid);
                }
            }
            lock.unlock();
        });
    }

    public static void unlock() {
        lock.unlock();
    }

    public static void stop(MinecraftServer server) {
        BiliBiliAuthManager.getInstance().shutdown();
        save();
        unload(server);
    }

    public static void save() {
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

    public static void load(MinecraftServer server) {
        DataHolder.server = server;
        lock();
        try {
            Path readPath = Files.exists(configPath) || Files.notExists(legacyConfigPath) ? configPath : legacyConfigPath;
            config = gson.fromJson(readString(readPath), ServerConfig.class);
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
        try {
            YtDlpManager.getInstance().prepareAsync(false);
        } catch (Exception e) {
            VideoPlayerMain.LOGGER.warn("Cannot schedule yt-dlp preparation", e);
        }
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
        unlock();
    }

    private static void dedupeScreens(VideoArea area) {
        LinkedHashMap<String, VideoScreen> unique = new LinkedHashMap<>();
        for (VideoScreen screen : area.screens) {
            screen.source = VideoScreen.normalizeSource(screen.source);
            unique.put(screen.name, screen);
        }
        area.screens.clear();
        area.screens.addAll(unique.values());
    }

    public static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void writeString(Path path, String str) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, str);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Path bilibiliCookiePath() {
        String configured = config == null || config.providerAuth == null ? "" : config.providerAuth.bilibiliCookieFile;
        if (configured == null || configured.isBlank()) configured = "bilibili-cookies.txt";
        Path path = Path.of(configured);
        return path.isAbsolute() ? path.normalize() : dataDir.resolve(path).normalize();
    }

    public static String effectiveBilibiliCookie() {
        String configured = config == null || config.providerAuth == null ? "" : config.providerAuth.bilibiliCookie;
        if (configured != null && !configured.isBlank()) return configured.trim();
        try {
            Path path = bilibiliCookiePath();
            if (Files.notExists(path) || Files.size(path) == 0) return "";
            return normalizeCookie(Files.readString(path));
        } catch (Exception e) {
            VideoPlayerMain.LOGGER.warn("Cannot read Bilibili cookie file", e);
            return "";
        }
    }

    public static String effectiveYoutubeCookie() {
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

    private static void ensureCookieFile(Path path) {
        try {
            Files.createDirectories(path.getParent());
            if (Files.notExists(path)) Files.createFile(path);
        } catch (IOException e) {
            VideoPlayerMain.LOGGER.warn("Cannot create cookie file {}", path, e);
        }
    }
}
