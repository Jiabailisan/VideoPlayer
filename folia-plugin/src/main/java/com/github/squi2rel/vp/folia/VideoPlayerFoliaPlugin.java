package com.github.squi2rel.vp.folia;

import com.github.squi2rel.vp.folia.network.ServerPacketHandler;
import com.github.squi2rel.vp.folia.provider.PlayerProviderSource;
import com.github.squi2rel.vp.folia.provider.VideoInfo;
import com.github.squi2rel.vp.folia.provider.VideoProviders;
import com.github.squi2rel.vp.folia.provider.YtDlpManager;
import com.github.squi2rel.vp.folia.provider.bilibili.BiliBiliAuthManager;
import com.github.squi2rel.vp.folia.provider.bilibili.BiliBiliProvider;
import com.github.squi2rel.vp.folia.video.VideoArea;
import com.github.squi2rel.vp.folia.video.VideoScreen;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class VideoPlayerFoliaPlugin extends JavaPlugin implements Listener, PluginMessageListener {
    public static final String CHANNEL = "videoplayer:video";
    private static VideoPlayerFoliaPlugin instance;

    private final ExecutorService coreExecutor = Executors.newCachedThreadPool(VideoPlayerFoliaPlugin::newDaemon);
    private final ScheduledExecutorService schedulerExecutor = Executors.newScheduledThreadPool(1, VideoPlayerFoliaPlugin::newDaemon);
    private final Map<UUID, ScheduledTask> playerTasks = new ConcurrentHashMap<>();
    private DataHolder dataHolder;
    private BiliBiliAuthManager bilibiliAuthManager;
    private YtDlpManager ytDlpManager;
    private String protocolVersion;

    public static VideoPlayerFoliaPlugin getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        protocolVersion = getPluginMeta().getVersion();
        dataHolder = new DataHolder(this);

        getLogger().info("Folia core uses client-side VLC playback; no server-side VLC library is required.");

        dataHolder.load();
        bilibiliAuthManager = new BiliBiliAuthManager(this);
        ytDlpManager = new YtDlpManager(this);
        ytDlpManager.prepareAsync(false);
        VideoProviders.register(this);

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);

        for (Player player : Bukkit.getOnlinePlayers()) {
            startPlayerTask(player);
            player.getScheduler().runDelayed(this, task -> dataHolder.playerJoin(player), null, 20L);
        }

        getLogger().info("VideoPlayer Folia bridge enabled for protocol " + protocolVersion);
    }

    @Override
    public void onDisable() {
        for (ScheduledTask task : playerTasks.values()) {
            task.cancel();
        }
        playerTasks.clear();
        if (dataHolder != null) {
            dataHolder.save();
            dataHolder.unload();
        }
        if (bilibiliAuthManager != null) {
            bilibiliAuthManager.shutdown();
        }
        Bukkit.getMessenger().unregisterIncomingPluginChannel(this, CHANNEL, this);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL);
        coreExecutor.shutdownNow();
        schedulerExecutor.shutdownNow();
        getLogger().info("VideoPlayer Folia bridge disabled");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        startPlayerTask(player);
        player.getScheduler().runDelayed(this, task -> dataHolder.playerJoin(player), null, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        ScheduledTask task = playerTasks.remove(uuid);
        if (task != null) task.cancel();
        dataHolder.playerLeave(uuid);
    }

    private void startPlayerTask(Player player) {
        ScheduledTask old = playerTasks.remove(player.getUniqueId());
        if (old != null) old.cancel();
        ScheduledTask task = player.getScheduler().runAtFixedRate(this, scheduledTask -> {
            if (!player.isOnline()) {
                scheduledTask.cancel();
                return;
            }
            dataHolder.updatePlayer(player);
        }, null, 1L, 1L);
        playerTasks.put(player.getUniqueId(), task);
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (!CHANNEL.equals(channel)) return;
        player.getScheduler().run(this, task -> {
            ByteBuf buf = Unpooled.wrappedBuffer(message);
            try {
                ServerPacketHandler.handle(this, player, buf);
            } catch (Exception e) {
                getLogger().warning("Exception while handling VideoPlayer packet from " + player.getName() + ": " + e);
                player.kick(Component.text(e.toString()));
            } finally {
                buf.release();
            }
        }, null);
    }

    public void sendTo(Player player, byte[] bytes) {
        player.getScheduler().run(this, task -> {
            if (player.isOnline()) {
                player.sendPluginMessage(this, CHANNEL, bytes);
            }
        }, null);
    }

    public void sendMessage(UUID uuid, Component message) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return;
        player.getScheduler().run(this, task -> {
            if (player.isOnline()) player.sendMessage(message);
        }, null);
    }

    public void broadcast(VideoArea area, byte[] bytes) {
        area.forEachPlayer(uuid -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) sendTo(player, bytes);
        });
    }

    public DataHolder getDataHolder() {
        return dataHolder;
    }

    public ExecutorService getCoreExecutor() {
        return coreExecutor;
    }

    public ScheduledExecutorService getSchedulerExecutor() {
        return schedulerExecutor;
    }

    public YtDlpManager getYtDlpManager() {
        return ytDlpManager;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "save" -> {
                if (!requirePermission(sender, "videoplayer.admin")) return true;
                dataHolder.save();
                sender.sendMessage(Component.text("VideoPlayer 配置已保存", NamedTextColor.GREEN));
            }
            case "reload" -> {
                if (!requirePermission(sender, "videoplayer.admin")) return true;
                dataHolder.save();
                dataHolder.unload();
                dataHolder.load();
                BiliBiliProvider.configureAuthCookie(dataHolder.effectiveBilibiliCookie());
                sender.sendMessage(Component.text("VideoPlayer 配置已重载", NamedTextColor.GREEN));
            }
            case "debug" -> {
                if (requirePermission(sender, "videoplayer.admin")) handleDebug(sender, args);
            }
            case "menu" -> {
                if (requirePermission(sender, "videoplayer.use")) handleMenu(sender);
            }
            case "auth" -> {
                if (requirePermission(sender, "videoplayer.admin")) handleAuthCommand(sender, args);
            }
            case "createarea" -> {
                if (requirePermission(sender, "videoplayer.admin")) handleCreateArea(sender, args);
            }
            case "removearea" -> {
                if (requirePermission(sender, "videoplayer.admin")) handleRemoveArea(sender, args);
            }
            case "createscreen" -> {
                if (requirePermission(sender, "videoplayer.admin")) handleCreateScreen(sender, args);
            }
            case "createscreenhelper" -> {
                if (requirePermission(sender, "videoplayer.admin")) handleCreateScreenHelper(sender, args);
            }
            case "removescreen" -> {
                if (requirePermission(sender, "videoplayer.admin")) handleRemoveScreen(sender, args);
            }
            case "skippercent" -> {
                if (requirePermission(sender, "videoplayer.admin")) handleSkipPercent(sender, args);
            }
            case "slice" -> {
                if (requirePermission(sender, "videoplayer.admin")) handleSlice(sender, args);
            }
            case "scale" -> {
                if (requirePermission(sender, "videoplayer.admin")) handleScale(sender, args);
            }
            case "play" -> {
                if (requirePermission(sender, "videoplayer.use")) handlePlay(sender, args);
            }
            case "skip" -> {
                if (requirePermission(sender, "videoplayer.use")) handleSkip(sender, args);
            }
            case "list" -> {
                if (requirePermission(sender, "videoplayer.use")) handleList(sender, args);
            }
            case "sync" -> {
                if (requirePermission(sender, "videoplayer.use")) handleSync(sender, args);
            }
            default -> sendUsage(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        if (args.length == 0) return List.of();
        boolean admin = sender.hasPermission("videoplayer.admin");
        boolean use = sender.hasPermission("videoplayer.use");
        if (args.length == 1) {
            List<String> commands = admin ? List.of(
                    "menu", "play", "createArea", "removeArea", "createScreen", "removeScreen",
                    "skip", "skipPercent", "list", "sync", "slice", "scale",
                    "auth", "debug", "save", "reload", "createScreenHelper"
            ) : (use ? List.of("menu", "play", "skip", "list", "sync") : List.of());
            return filter(args[0], commands);
        }

        String sub = args[0].toLowerCase();
        boolean adminOnly = List.of("createarea", "removearea", "createscreen", "createscreenhelper",
                "removescreen", "skippercent", "slice", "scale", "auth", "debug", "save", "reload").contains(sub);
        boolean useOnly = List.of("menu", "play", "skip", "list", "sync").contains(sub);
        if (adminOnly && !admin) return List.of();
        if (useOnly && !use) return List.of();
        if (sub.equals("createarea")) {
            if (args.length >= 2 && args.length <= 7) return targetCoordinate(sender, args.length - 1);
            return List.of();
        }
        if (sub.equals("createscreen")) {
            if (args.length == 2) return areaNames(sender, args[1]);
            if (args.length >= 4 && args.length <= 15) return targetCoordinate(sender, args.length - 3);
            return List.of();
        }
        if (sub.equals("createscreenhelper")) {
            if (args.length == 2) return areaNames(sender, args[1]);
            if (args.length >= 3 && args.length <= 5) return targetCoordinate(sender, args.length - 2);
            return List.of();
        }
        if (sub.equals("removearea")) {
            return args.length == 2 ? areaNames(sender, args[1]) : List.of();
        }
        if (List.of("play", "removescreen", "skip", "skippercent", "list", "sync", "slice", "scale").contains(sub)) {
            if (args.length == 2) return areaNames(sender, args[1]);
            if (args.length == 3) return screenNames(sender, args[1], args[2]);
        }
        if (sub.equals("skip") && args.length == 4 && admin) return filter(args[3], List.of("false", "true"));
        if (sub.equals("debug") && args.length == 2) return filter(args[1], List.of("false", "true"));
        if (sub.equals("scale") && args.length == 4) return filter(args[3], List.of("stretch", "auto", "set"));
        if (sub.equals("auth")) return authSuggestions(args);
        return List.of();
    }

    private List<String> authSuggestions(String[] args) {
        if (args.length == 2) return filter(args[1], List.of("bilibili", "youtube"));
        if (args.length == 3) {
            if ("bilibili".equalsIgnoreCase(args[1])) return filter(args[2], List.of("status", "cookie", "cookiefile"));
            if ("youtube".equalsIgnoreCase(args[1])) return filter(args[2], List.of("status", "cookie", "cookiefile", "redownload"));
        }
        if (args.length == 4 && "cookiefile".equalsIgnoreCase(args[2])) return filter(args[3], List.of("reload", "clear"));
        return List.of();
    }

    private List<String> targetCoordinate(CommandSender sender, int coordinateIndex) {
        if (!(sender instanceof Player player)) return List.of();
        Block block = player.getTargetBlockExact(64);
        if (block == null) return List.of();
        int axis = (coordinateIndex - 1) % 3;
        int value = switch (axis) {
            case 0 -> block.getX();
            case 1 -> block.getY();
            case 2 -> block.getZ();
            default -> 0;
        };
        return List.of(Integer.toString(value));
    }

    private List<String> areaNames(CommandSender sender, String prefix) {
        if (!(sender instanceof Player player)) return List.of();
        Map<String, VideoArea> byDim = dataHolder.areas.get(player.getWorld().getKey().toString());
        if (byDim == null) return List.of();
        return filter(prefix, byDim.keySet());
    }

    private List<String> screenNames(CommandSender sender, String areaName, String prefix) {
        if (!(sender instanceof Player player)) return List.of();
        VideoArea area = areaInWorld(player, areaName);
        if (area == null) return List.of();
        List<String> names = new ArrayList<>();
        for (VideoScreen screen : area.screens) names.add(screen.name);
        return filter(prefix, names);
    }

    private static List<String> filter(String prefix, Iterable<String> values) {
        String lower = prefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase().startsWith(lower)) out.add(value);
        }
        Collections.sort(out);
        return out;
    }

    private void sendUsage(CommandSender sender) {
        if (!sender.hasPermission("videoplayer.admin")) {
            sender.sendMessage(Component.text("/vlc-core menu", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("/vlc-core play <area> <screen> <url>", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("/vlc-core skip <area> <screen>", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("/vlc-core list <area> <screen>", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("/vlc-core sync <area> <screen>", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text("/vlc-core createArea <x1> <y1> <z1> <x2> <y2> <z2> <name>", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/vlc-core createScreen <area> <name> <x1 y1 z1> <x2 y2 z2> <x3 y3 z3> <x4 y4 z4> [source]", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/vlc-core createScreenHelper <area> <左下x> <左下y> <左下z> <length> <height> <name> [source]", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/vlc-core menu", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/vlc-core play <area> <screen> <url>", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/vlc-core <removeArea|removeScreen|skip|skipPercent|list|sync|slice|scale|debug|save|reload|auth>", NamedTextColor.YELLOW));
    }

    private boolean requirePermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        sender.sendMessage(Component.text("没有权限: " + permission, NamedTextColor.RED));
        return false;
    }

    private void handleCreateArea(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (args.length != 8) {
            sender.sendMessage(Component.text("/vlc-core createArea <x1> <y1> <z1> <x2> <y2> <z2> <name>", NamedTextColor.YELLOW));
            return;
        }
        try {
            VideoArea area = VideoArea.from(
                    vec(args, 1),
                    vec(args, 4),
                    args[7],
                    player.getWorld().getKey().toString()
            );
            area.initServer();
            dataHolder.lock();
            try {
                dataHolder.areas.computeIfAbsent(area.dim, k -> new HashMap<>()).put(area.name, area);
            } finally {
                dataHolder.unlock();
            }
            dataHolder.save();
            sender.sendMessage(Component.text("已创建观影区 " + area.name, NamedTextColor.GREEN));
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("坐标必须是数字", NamedTextColor.RED));
        }
    }

    private void handleMenu(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        sendTo(player, ServerPacketHandler.execute("vlc-client menu"));
    }

    private void handleDebug(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            dataHolder.config.debug = Boolean.parseBoolean(args[1]);
            dataHolder.save();
        }
        sender.sendMessage(Component.text("VideoPlayer debug: " + (dataHolder.config.debug ? "开启" : "关闭"),
                dataHolder.config.debug ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
    }

    private void handleRemoveArea(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (args.length != 2) {
            sender.sendMessage(Component.text("/vlc-core removeArea <name>", NamedTextColor.YELLOW));
            return;
        }
        String dim = player.getWorld().getKey().toString();
        dataHolder.lock();
        try {
            Map<String, VideoArea> byDim = dataHolder.areas.get(dim);
            VideoArea area = byDim == null ? null : byDim.remove(args[1]);
            if (area == null) {
                sender.sendMessage(Component.text("观影区不存在: " + args[1], NamedTextColor.RED));
                return;
            }
            area.remove();
            if (area.hasPlayer()) broadcast(area, ServerPacketHandler.removeArea(area));
        } finally {
            dataHolder.unlock();
        }
        dataHolder.save();
        sender.sendMessage(Component.text("已移除观影区 " + args[1], NamedTextColor.GREEN));
    }

    private void handleCreateScreen(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (args.length != 15 && args.length != 16) {
            sender.sendMessage(Component.text("/vlc-core createScreen <area> <name> <x1 y1 z1> <x2 y2 z2> <x3 y3 z3> <x4 y4 z4> [source]", NamedTextColor.YELLOW));
            return;
        }
        try {
            VideoArea area = areaInWorld(player, args[1]);
            if (area == null) {
                sender.sendMessage(Component.text("观影区不存在: " + args[1], NamedTextColor.RED));
                return;
            }
            String source = VideoScreen.normalizeSource(args.length == 16 ? args[15] : "");
            VideoScreen screen = VideoScreen.fromBlockCorners(area, args[2], vec(args, 3), vec(args, 6), vec(args, 9), vec(args, 12), source);
            createScreen(sender, area, screen);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("坐标必须是数字", NamedTextColor.RED));
        }
    }

    private void handleCreateScreenHelper(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (args.length != 8 && args.length != 9) {
            sender.sendMessage(Component.text("/vlc-core createScreenHelper <area> <左下x> <左下y> <左下z> <length> <height> <name> [source]", NamedTextColor.YELLOW));
            return;
        }
        try {
            VideoArea area = areaInWorld(player, args[1]);
            if (area == null) {
                sender.sendMessage(Component.text("观影区不存在: " + args[1], NamedTextColor.RED));
                return;
            }
            Vector3f lowerLeft = vec(args, 2);
            int length = Integer.parseInt(args[5]);
            int height = Integer.parseInt(args[6]);
            if (length < 1 || height < 1 || length > 256 || height > 256) {
                sender.sendMessage(Component.text("length/height 必须在 1 到 256 之间", NamedTextColor.RED));
                return;
            }
            Vector3f horizontal = rightVector(player.getFacing(), length);
            Vector3f vertical = new Vector3f(0, height, 0);
            Vector3f p2 = new Vector3f(lowerLeft);
            Vector3f p1 = new Vector3f(lowerLeft).add(vertical);
            Vector3f p3 = new Vector3f(lowerLeft).add(horizontal);
            Vector3f p4 = new Vector3f(p3).add(vertical);
            String source = VideoScreen.normalizeSource(args.length == 9 ? args[8] : "");
            createScreen(sender, area, new VideoScreen(area, args[7], p1, p2, p3, p4, source));
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("坐标、length、height 必须是数字", NamedTextColor.RED));
        }
    }

    private void createScreen(CommandSender sender, VideoArea area, VideoScreen screen) {
        screen.initServer();
        dataHolder.lock();
        try {
            for (VideoScreen old : area.removeScreens(screen.name)) {
                if (area.hasPlayer()) broadcast(area, ServerPacketHandler.removeScreen(old));
            }
            area.addScreen(screen);
            if (area.hasPlayer()) broadcast(area, ServerPacketHandler.createScreen(List.of(screen)));
        } finally {
            dataHolder.unlock();
        }
        dataHolder.save();
        sender.sendMessage(Component.text("已创建屏幕 " + screen.name, NamedTextColor.GREEN));
    }

    private void handleRemoveScreen(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (args.length != 3) {
            sender.sendMessage(Component.text("/vlc-core removeScreen <area> <screen>", NamedTextColor.YELLOW));
            return;
        }
        VideoArea area = areaInWorld(player, args[1]);
        if (area == null) {
            sender.sendMessage(Component.text("观影区不存在: " + args[1], NamedTextColor.RED));
            return;
        }
        dataHolder.lock();
        try {
            VideoScreen screen = area.removeScreen(args[2]);
            if (screen == null) {
                sender.sendMessage(Component.text("屏幕不存在: " + args[2], NamedTextColor.RED));
                return;
            }
            if (area.hasPlayer()) broadcast(area, ServerPacketHandler.removeScreen(screen));
        } finally {
            dataHolder.unlock();
        }
        dataHolder.save();
        sender.sendMessage(Component.text("已移除屏幕 " + args[2], NamedTextColor.GREEN));
    }

    private void handlePlay(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (args.length < 4) {
            sender.sendMessage(Component.text("/vlc-core play <area> <screen> <url>", NamedTextColor.YELLOW));
            return;
        }
        VideoScreen screen = screenInWorld(player, args[1], args[2]);
        if (screen == null) {
            sender.sendMessage(Component.text("屏幕不存在", NamedTextColor.RED));
            return;
        }
        String url = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
        CompletableFuture<VideoInfo> video = VideoProviders.from(url, new PlayerProviderSource(this, player));
        if (video == null) {
            sender.sendMessage(Component.text("无法解析视频源", NamedTextColor.RED));
            return;
        }
        video.thenAccept(info -> {
            if (info == null) {
                player.getScheduler().run(this, task -> player.sendMessage(Component.text("无法解析视频源", NamedTextColor.RED)), null);
                return;
            }
            screen.addInfo(info);
        });
        sender.sendMessage(Component.text("已提交播放请求", NamedTextColor.GREEN));
    }

    private void handleSkip(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (args.length < 3) {
            sender.sendMessage(Component.text("/vlc-core skip <area> <screen> [force]", NamedTextColor.YELLOW));
            return;
        }
        VideoScreen screen = screenInWorld(player, args[1], args[2]);
        if (screen == null) {
            sender.sendMessage(Component.text("屏幕不存在", NamedTextColor.RED));
            return;
        }
        boolean force = args.length >= 4 && Boolean.parseBoolean(args[3]);
        if (force) screen.skip();
        else screen.voteSkip(player.getUniqueId());
        sender.sendMessage(Component.text(force ? "已强制跳过" : "已投票跳过", NamedTextColor.GREEN));
    }

    private void handleSkipPercent(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (args.length != 4) {
            sender.sendMessage(Component.text("/vlc-core skipPercent <area> <screen> <percent>", NamedTextColor.YELLOW));
            return;
        }
        VideoScreen screen = screenInWorld(player, args[1], args[2]);
        if (screen == null) {
            sender.sendMessage(Component.text("屏幕不存在", NamedTextColor.RED));
            return;
        }
        try {
            float percent = Float.parseFloat(args[3]);
            if (percent < 0 || percent > 1.01f) {
                sender.sendMessage(Component.text("percent 必须在 0 到 1.01 之间", NamedTextColor.RED));
                return;
            }
            screen.setSkipPercent(percent);
            sender.sendMessage(Component.text("投票跳过比例已设置为 " + percent, NamedTextColor.GREEN));
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("percent 必须是数字", NamedTextColor.RED));
        }
    }

    private void handleList(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (args.length != 3) {
            sender.sendMessage(Component.text("/vlc-core list <area> <screen>", NamedTextColor.YELLOW));
            return;
        }
        VideoScreen screen = screenInWorld(player, args[1], args[2]);
        if (screen == null) {
            sender.sendMessage(Component.text("屏幕不存在", NamedTextColor.RED));
            return;
        }
        if (screen.infos.isEmpty()) {
            sender.sendMessage(Component.text("队列无视频", NamedTextColor.GOLD));
            return;
        }
        for (VideoInfo info : screen.infos) {
            sender.sendMessage(Component.text(info.name() + " 请求玩家: " + info.playerName(), NamedTextColor.GOLD));
        }
    }

    private void handleSync(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (args.length != 3) {
            sender.sendMessage(Component.text("/vlc-core sync <area> <screen>", NamedTextColor.YELLOW));
            return;
        }
        VideoScreen screen = screenInWorld(player, args[1], args[2]);
        if (screen == null || screen.currentPlaying() == null) {
            sender.sendMessage(Component.text("屏幕无正在播放视频", NamedTextColor.RED));
            return;
        }
        sendTo(player, ServerPacketHandler.sync(screen, screen.getProgress()));
    }

    private void handleSlice(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (args.length != 7) {
            sender.sendMessage(Component.text("/vlc-core slice <area> <screen> <u1> <v1> <u2> <v2>", NamedTextColor.YELLOW));
            return;
        }
        VideoScreen screen = screenInWorld(player, args[1], args[2]);
        if (screen == null) {
            sender.sendMessage(Component.text("屏幕不存在", NamedTextColor.RED));
            return;
        }
        try {
            float u1 = Float.parseFloat(args[3]);
            float v1 = Float.parseFloat(args[4]);
            float u2 = Float.parseFloat(args[5]);
            float v2 = Float.parseFloat(args[6]);
            screen.u1 = u1;
            screen.v1 = v1;
            screen.u2 = u2;
            screen.v2 = v2;
            if (screen.area.hasPlayer()) broadcast(screen.area, ServerPacketHandler.setUV(screen, u1, v1, u2, v2));
            dataHolder.save();
            sender.sendMessage(Component.text("屏幕裁切已更新", NamedTextColor.GREEN));
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("UV 参数必须是数字", NamedTextColor.RED));
        }
    }

    private void handleScale(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (args.length < 4) {
            sender.sendMessage(Component.text("/vlc-core scale <area> <screen> <stretch|auto|set <scaleX> <scaleY>>", NamedTextColor.YELLOW));
            return;
        }
        VideoScreen screen = screenInWorld(player, args[1], args[2]);
        if (screen == null) {
            sender.sendMessage(Component.text("屏幕不存在", NamedTextColor.RED));
            return;
        }
        switch (args[3].toLowerCase()) {
            case "stretch" -> setScale(sender, screen, true, 1f, 1f);
            case "auto" -> setScale(sender, screen, false, 1f, 1f);
            case "set" -> {
                if (args.length != 6) {
                    sender.sendMessage(Component.text("/vlc-core scale <area> <screen> set <scaleX> <scaleY>", NamedTextColor.YELLOW));
                    return;
                }
                try {
                    float scaleX = Float.parseFloat(args[4]);
                    float scaleY = Float.parseFloat(args[5]);
                    if (scaleX < 0.0625f || scaleX > 16f || scaleY < 0.0625f || scaleY > 16f) {
                        sender.sendMessage(Component.text("scaleX/scaleY 必须在 0.0625 到 16 之间", NamedTextColor.RED));
                        return;
                    }
                    setScale(sender, screen, false, scaleX, scaleY);
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("scaleX/scaleY 必须是数字", NamedTextColor.RED));
                }
            }
            default -> sender.sendMessage(Component.text("/vlc-core scale <area> <screen> <stretch|auto|set <scaleX> <scaleY>>", NamedTextColor.YELLOW));
        }
    }

    private void setScale(CommandSender sender, VideoScreen screen, boolean fill, float scaleX, float scaleY) {
        screen.fill = fill;
        screen.scaleX = scaleX;
        screen.scaleY = scaleY;
        if (screen.area.hasPlayer()) broadcast(screen.area, ServerPacketHandler.setScale(screen, fill, scaleX, scaleY));
        dataHolder.save();
        sender.sendMessage(Component.text("屏幕缩放已更新", NamedTextColor.GREEN));
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) return player;
        sender.sendMessage(Component.text("该指令必须由玩家执行", NamedTextColor.RED));
        return null;
    }

    private VideoArea areaInWorld(Player player, String name) {
        Map<String, VideoArea> byDim = dataHolder.areas.get(player.getWorld().getKey().toString());
        return byDim == null ? null : byDim.get(name);
    }

    private VideoScreen screenInWorld(Player player, String areaName, String screenName) {
        VideoArea area = areaInWorld(player, areaName);
        return area == null ? null : area.getScreen(screenName);
    }

    private static Vector3f vec(String[] args, int start) {
        return new Vector3f(Float.parseFloat(args[start]), Float.parseFloat(args[start + 1]), Float.parseFloat(args[start + 2]));
    }

    private static Vector3f rightVector(BlockFace facing, int length) {
        return switch (facing) {
            case NORTH -> new Vector3f(length, 0, 0);
            case SOUTH -> new Vector3f(-length, 0, 0);
            case EAST -> new Vector3f(0, 0, length);
            case WEST -> new Vector3f(0, 0, -length);
            default -> new Vector3f(length, 0, 0);
        };
    }

    private void handleAuthCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("/vpfolia auth bilibili <status|cookie <cookie>|cookiefile <reload|clear>>", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("/vpfolia auth youtube <status|cookie <cookie>|cookiefile <reload|clear>|redownload>", NamedTextColor.YELLOW));
            return;
        }
        switch (args[1].toLowerCase()) {
            case "bilibili" -> handleBilibiliAuthCommand(sender, args);
            case "youtube" -> handleYoutubeAuthCommand(sender, args);
            default -> sender.sendMessage(Component.text("/vpfolia auth <bilibili|youtube> ...", NamedTextColor.YELLOW));
        }
    }

    private void handleYoutubeAuthCommand(CommandSender sender, String[] args) {
        switch (args[2].toLowerCase()) {
            case "status" -> {
                boolean hasAuth = youtubeHasAuth();
                sender.sendMessage(Component.text(hasAuth ? "YouTube 登录凭据已配置" : "YouTube 登录凭据未配置", hasAuth ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("YouTube Cookie 配置项: " + (dataHolder.effectiveYoutubeCookie().isBlank() ? "未设置" : "已设置"), NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("YouTube Cookie 文件: " + ytDlpManager.cookiePath().toAbsolutePath(), NamedTextColor.AQUA));
                sender.sendMessage(Component.text("Cookie 文件状态: " + cookieFileStatus(ytDlpManager.cookiePath()), NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("yt-dlp: " + ytDlpManager.executablePath().toAbsolutePath(), NamedTextColor.AQUA));
            }
            case "cookie" -> {
                if (args.length < 4) {
                    sender.sendMessage(Component.text("/vpfolia auth youtube cookie <cookie>", NamedTextColor.YELLOW));
                    return;
                }
                String cookie = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)).trim();
                if (cookie.isEmpty()) {
                    sender.sendMessage(Component.text("Cookie 不能为空", NamedTextColor.RED));
                    return;
                }
                dataHolder.config.providerAuth.youtubeCookie = DataHolder.normalizeCookie(cookie);
                dataHolder.save();
                sender.sendMessage(Component.text("YouTube Cookie 已保存到服务端配置。不会发送给客户端。", NamedTextColor.GREEN));
            }
            case "redownload" -> {
                if (sender instanceof Player) {
                    sender.sendMessage(Component.text("只允许在服务器控制台重新下载 yt-dlp。", NamedTextColor.RED));
                    return;
                }
                sender.sendMessage(Component.text("开始重新下载 yt-dlp...", NamedTextColor.YELLOW));
                coreExecutor.execute(() -> {
                    try {
                        ytDlpManager.prepare(true);
                        sender.sendMessage(Component.text("yt-dlp 已下载到 " + ytDlpManager.executablePath().toAbsolutePath(), NamedTextColor.GREEN));
                    } catch (Exception e) {
                        sender.sendMessage(Component.text("yt-dlp 下载失败: " + e, NamedTextColor.RED));
                    }
                });
            }
            case "cookiefile" -> {
                if (args.length < 4) {
                    sender.sendMessage(Component.text("/vpfolia auth youtube cookiefile <reload|clear>", NamedTextColor.YELLOW));
                    return;
                }
                if ("reload".equalsIgnoreCase(args[3])) {
                    try {
                        ytDlpManager.prepare(false);
                        boolean hasAuth = youtubeHasAuth();
                        sender.sendMessage(Component.text(hasAuth ? "YouTube Cookie 已加载" : "YouTube Cookie 文件为空或配置项未设置", hasAuth ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
                        sender.sendMessage(Component.text("YouTube Cookie 文件已重新确认: " + ytDlpManager.cookiePath().toAbsolutePath(), NamedTextColor.GREEN));
                        sender.sendMessage(Component.text("Cookie 文件状态: " + cookieFileStatus(ytDlpManager.cookiePath()), NamedTextColor.YELLOW));
                    } catch (Exception e) {
                        sender.sendMessage(Component.text("重新加载 YouTube Cookie 文件失败: " + e, NamedTextColor.RED));
                    }
                } else if ("clear".equalsIgnoreCase(args[3])) {
                    if (sender instanceof Player) {
                        sender.sendMessage(Component.text("只允许在服务器控制台清空 YouTube Cookie 文件。", NamedTextColor.RED));
                        return;
                    }
                    try {
                        java.nio.file.Files.writeString(ytDlpManager.cookiePath(), "");
                        dataHolder.config.providerAuth.youtubeCookie = "";
                        dataHolder.save();
                        sender.sendMessage(Component.text("YouTube Cookie 文件与配置项已清空: " + ytDlpManager.cookiePath().toAbsolutePath(), NamedTextColor.GREEN));
                    } catch (Exception e) {
                        sender.sendMessage(Component.text("清空 YouTube Cookie 文件失败: " + e, NamedTextColor.RED));
                    }
                } else {
                    sender.sendMessage(Component.text("/vpfolia auth youtube cookiefile <reload|clear>", NamedTextColor.YELLOW));
                }
            }
            default -> sender.sendMessage(Component.text("/vpfolia auth youtube <status|cookie <cookie>|cookiefile <reload|clear>|redownload>", NamedTextColor.YELLOW));
        }
    }

    private void handleBilibiliAuthCommand(CommandSender sender, String[] args) {
        switch (args[2].toLowerCase()) {
            case "status" -> {
                String cookie = dataHolder.effectiveBilibiliCookie();
                BiliBiliProvider.configureAuthCookie(cookie);
                sender.sendMessage(Component.text(
                        !cookie.isBlank()
                                ? "Bilibili 登录凭据已配置"
                                : (bilibiliAuthManager.isPolling() ? "Bilibili 扫码登录进行中" : "Bilibili 登录凭据未配置"),
                        !cookie.isBlank() ? NamedTextColor.GREEN : NamedTextColor.YELLOW
                ));
                sender.sendMessage(Component.text("Bilibili Cookie 配置项: " + (dataHolder.config.providerAuth.bilibiliCookie.isBlank() ? "未设置" : "已设置"), NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("Bilibili Cookie 文件: " + dataHolder.bilibiliCookiePath().toAbsolutePath(), NamedTextColor.AQUA));
                sender.sendMessage(Component.text("Cookie 文件状态: " + cookieFileStatus(dataHolder.bilibiliCookiePath()), NamedTextColor.YELLOW));
            }
            case "cookie" -> {
                if (sender instanceof Player) {
                    sender.sendMessage(Component.text("为避免泄露凭据，只允许在服务器控制台写入 Cookie。", NamedTextColor.RED));
                    return;
                }
                if (args.length < 4) {
                    sender.sendMessage(Component.text("/vpfolia auth bilibili cookie <cookie>", NamedTextColor.YELLOW));
                    return;
                }
                String cookie = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)).trim();
                if (cookie.isEmpty()) {
                    sender.sendMessage(Component.text("Cookie 不能为空", NamedTextColor.RED));
                    return;
                }
                dataHolder.config.providerAuth.bilibiliCookie = DataHolder.normalizeCookie(cookie);
                BiliBiliProvider.configureAuthCookie(dataHolder.config.providerAuth.bilibiliCookie);
                dataHolder.save();
                sender.sendMessage(Component.text("Bilibili Cookie 已保存。不会发送给客户端。", NamedTextColor.GREEN));
            }
            case "cookiefile" -> {
                if (args.length < 4) {
                    sender.sendMessage(Component.text("/vpfolia auth bilibili cookiefile <reload|clear>", NamedTextColor.YELLOW));
                    return;
                }
                if ("reload".equalsIgnoreCase(args[3])) {
                    String cookie = dataHolder.effectiveBilibiliCookie();
                    BiliBiliProvider.configureAuthCookie(cookie);
                    sender.sendMessage(Component.text(cookie.isBlank() ? "Bilibili Cookie 文件为空或不可用" : "Bilibili Cookie 文件已加载", cookie.isBlank() ? NamedTextColor.YELLOW : NamedTextColor.GREEN));
                    sender.sendMessage(Component.text("Bilibili Cookie 文件: " + dataHolder.bilibiliCookiePath().toAbsolutePath(), NamedTextColor.AQUA));
                    sender.sendMessage(Component.text("Cookie 文件状态: " + cookieFileStatus(dataHolder.bilibiliCookiePath()), NamedTextColor.YELLOW));
                } else if ("clear".equalsIgnoreCase(args[3])) {
                    try {
                        java.nio.file.Files.writeString(dataHolder.bilibiliCookiePath(), "");
                        dataHolder.config.providerAuth.bilibiliCookie = "";
                        BiliBiliProvider.configureAuthCookie("");
                        dataHolder.save();
                        sender.sendMessage(Component.text("Bilibili Cookie 文件与配置项已清空: " + dataHolder.bilibiliCookiePath().toAbsolutePath(), NamedTextColor.GREEN));
                    } catch (Exception e) {
                        sender.sendMessage(Component.text("清空 Bilibili Cookie 文件失败: " + e, NamedTextColor.RED));
                    }
                } else {
                    sender.sendMessage(Component.text("/vpfolia auth bilibili cookiefile <reload|clear>", NamedTextColor.YELLOW));
                }
            }
            default -> sender.sendMessage(Component.text("/vpfolia auth bilibili <status|cookie <cookie>|cookiefile <reload|clear>>", NamedTextColor.YELLOW));
        }
    }

    private boolean youtubeHasAuth() {
        if (!dataHolder.effectiveYoutubeCookie().isBlank()) return true;
        try {
            return java.nio.file.Files.exists(ytDlpManager.cookiePath()) && java.nio.file.Files.size(ytDlpManager.cookiePath()) > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String cookieFileStatus(java.nio.file.Path path) {
        try {
            if (java.nio.file.Files.notExists(path)) return "不存在";
            long size = java.nio.file.Files.size(path);
            return size == 0 ? "空文件" : size + " bytes";
        } catch (Exception e) {
            return "无法读取: " + e;
        }
    }

    private static Thread newDaemon(Runnable task) {
        Thread thread = new Thread(task, "VideoPlayer-Folia");
        thread.setDaemon(true);
        return thread;
    }
}
