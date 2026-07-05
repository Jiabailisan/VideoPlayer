package com.github.squi2rel.vp.folia.network;

import com.github.squi2rel.vp.folia.DataHolder;
import com.github.squi2rel.vp.folia.ServerConfig;
import com.github.squi2rel.vp.folia.VideoPlayerFoliaPlugin;
import com.github.squi2rel.vp.folia.provider.PlayerProviderSource;
import com.github.squi2rel.vp.folia.provider.MediaOption;
import com.github.squi2rel.vp.folia.provider.VideoInfo;
import com.github.squi2rel.vp.folia.provider.VideoProviders;
import com.github.squi2rel.vp.folia.video.IVideoListener;
import com.github.squi2rel.vp.folia.video.VideoArea;
import com.github.squi2rel.vp.folia.video.VideoScreen;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.github.squi2rel.vp.folia.network.ByteBufUtils.readString;
import static com.github.squi2rel.vp.folia.network.ByteBufUtils.writeString;
import static com.github.squi2rel.vp.folia.network.PacketID.*;
import static com.github.squi2rel.vp.folia.video.VideoScreen.MAX_NAME_LENGTH;

public final class ServerPacketHandler {
    private ServerPacketHandler() {
    }

    public static void handle(VideoPlayerFoliaPlugin plugin, Player player, ByteBuf buf) {
        DataHolder dataHolder = plugin.getDataHolder();
        if (buf.readableBytes() < 1) {
            throw new IllegalStateException("empty VideoPlayer packet");
        }
        short type = buf.readUnsignedByte();
        if (dataHolder.config.debug) plugin.getLogger().info("server type: " + type);
        switch (type) {
            case CONFIG -> {
                ByteBufUtils.readString(buf, 16);
                dataHolder.lock();
                try {
                    if (!dataHolder.allPlayers.contains(player.getUniqueId())) {
                        dataHolder.allPlayers.add(player.getUniqueId());
                    }
                } finally {
                    dataHolder.unlock();
                }
            }
            case REQUEST -> {
                if (!player.hasPermission("videoplayer.use")) return;
                VideoArea area = getArea(dataHolder, player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                String url = ByteBufUtils.readString(buf, 16384);
                String optionId = buf.readableBytes() > 0 ? ByteBufUtils.readString(buf, 128) : "";
                if (dataHolder.config.debug) plugin.getLogger().info("request player=%s area=%s screen=%s option=%s url=%s".formatted(player.getName(), area.name, screen.name, optionId, url));
                fetchSource(plugin, player, url, optionId, screen::addInfo);
            }
            case SYNC -> {
                VideoArea area = getArea(dataHolder, player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null || screen.currentPlaying() == null) return;
                plugin.sendTo(player, sync(screen, screen.getProgress()));
            }
            case CREATE_AREA -> {
                if (!player.hasPermission("videoplayer.admin")) return;
                VideoArea area = VideoArea.from(ByteBufUtils.readVec3(buf), ByteBufUtils.readVec3(buf), readName(buf), player.getWorld().getKey().toString());
                area.initServer();
                dataHolder.lock();
                try {
                    dataHolder.areas.computeIfAbsent(area.dim, k -> new HashMap<>()).put(area.name, area);
                } finally {
                    dataHolder.unlock();
                }
                player.sendMessage(Component.text("已成功在世界 " + player.getWorld().getKey() + " 创建名为 " + area.name + " 的观影区!", NamedTextColor.GREEN));
            }
            case REMOVE_AREA -> {
                if (!player.hasPermission("videoplayer.admin")) return;
                VideoArea area = getArea(dataHolder, player, readName(buf));
                if (area == null) return;
                dataHolder.lock();
                try {
                    dataHolder.areas.get(area.dim).remove(area.name).remove();
                    if (area.hasPlayer()) plugin.broadcast(area, removeArea(area));
                } finally {
                    dataHolder.unlock();
                }
                player.sendMessage(Component.text("已成功在世界 " + player.getWorld().getKey() + " 移除名为 " + area.name + " 的观影区!", NamedTextColor.GREEN));
            }
            case CREATE_SCREEN -> {
                if (!player.hasPermission("videoplayer.admin")) return;
                VideoArea area = getArea(dataHolder, player, readName(buf));
                if (area == null) return;
                VideoScreen screen = VideoScreen.read(buf, area);
                screen.initServer();
                dataHolder.lock();
                try {
                    area.addScreen(screen);
                    if (area.hasPlayer()) plugin.broadcast(area, createScreen(List.of(screen)));
                } finally {
                    dataHolder.unlock();
                }
                player.sendMessage(Component.text("已成功在观影区 " + area.name + " 创建名为 " + screen.name + " 的屏幕!", NamedTextColor.GREEN));
            }
            case REMOVE_SCREEN -> {
                if (!player.hasPermission("videoplayer.admin")) return;
                VideoArea area = getArea(dataHolder, player, readName(buf));
                if (area == null) return;
                dataHolder.lock();
                try {
                    VideoScreen screen = area.removeScreen(readName(buf));
                    if (screen != null && area.hasPlayer()) {
                        plugin.broadcast(area, removeScreen(screen));
                        player.sendMessage(Component.text("已成功在观影区 " + area.name + " 移除名为 " + screen.name + " 的屏幕!", NamedTextColor.GREEN));
                    }
                } finally {
                    dataHolder.unlock();
                }
            }
            case SKIP -> {
                VideoArea area = getArea(dataHolder, player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                boolean force = buf.readBoolean();
                if (force) {
                    if (!player.hasPermission("videoplayer.admin")) return;
                    screen.skip();
                    return;
                }
                screen.voteSkip(player.getUniqueId());
                Component msg = Component.text("玩家 %s 已投票跳过 %s 上的视频 还需 %d 个玩家".formatted(
                        player.getName(), screen.name, screen.skipped() == 0 ? 0 : (int) (area.players() * screen.skipPercent - screen.skipped() + 1)
                ));
                player.sendMessage(Component.text("已投票跳过此视频", NamedTextColor.GOLD));
                area.forEachPlayer(uuid -> plugin.sendMessage(uuid, msg));
            }
            case SKIP_PERCENT -> {
                if (!player.hasPermission("videoplayer.admin")) return;
                VideoArea area = getArea(dataHolder, player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                screen.setSkipPercent(buf.readFloat());
                player.sendMessage(Component.text("屏幕 " + screen.name + " 的投票跳过比例已设置为 " + screen.skipPercent, NamedTextColor.GREEN));
            }
            case IDLE_PLAY -> {
                VideoArea area = getArea(dataHolder, player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                readString(buf, 16384);
            }
            case SET_UV -> {
                if (!player.hasPermission("videoplayer.admin")) return;
                VideoArea area = getArea(dataHolder, player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                readUV(buf, screen);
                if (area.hasPlayer()) plugin.broadcast(area, setUV(screen, screen.u1, screen.v1, screen.u2, screen.v2));
            }
            case OPEN_MENU -> {
                VideoArea area = getArea(dataHolder, player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                plugin.sendTo(player, config(plugin.getProtocolVersion(), dataHolder.config, player.hasPermission("videoplayer.admin")));
            }
            case SET_META -> {
                if (!player.hasPermission("videoplayer.admin")) return;
                short id = buf.readUnsignedByte();
                if (id >= Action.VALUES.length) {
                    player.kick(Component.text("Unknown action type: " + id));
                    return;
                }
                Action action = Action.VALUES[id];
                VideoArea area = getArea(dataHolder, player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                int value = buf.readInt();
                if (!action.verify(value)) {
                    player.kick(Component.text("Invalid value: " + value));
                    return;
                }
                action.apply(screen, value);
                if (area.hasPlayer()) plugin.broadcast(area, setMeta(screen, id, value));
            }
            case SET_CUSTOM_META -> {
                if (!player.hasPermission("videoplayer.admin")) return;
                VideoArea area = getArea(dataHolder, player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                String key = readName(buf);
                int value = buf.readInt();
                boolean remove = buf.readBoolean();
                if (remove) screen.meta.remove(key);
                else screen.meta.put(key, value);
                if (area.hasPlayer()) plugin.broadcast(area, setCustomMeta(screen, key, value, remove));
            }
            case SET_SCALE -> {
                if (!player.hasPermission("videoplayer.admin")) return;
                VideoArea area = getArea(dataHolder, player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                boolean fill = buf.readBoolean();
                float scaleX = buf.readFloat();
                float scaleY = buf.readFloat();
                if (scaleX < 0.0625f || scaleX > 16f || scaleY < 0.0625f || scaleY > 16f) {
                    throw new IllegalArgumentException("Invalid scale value: " + scaleX + " " + scaleY);
                }
                screen.fill = fill;
                screen.scaleX = scaleX;
                screen.scaleY = scaleY;
                if (area.hasPlayer()) plugin.broadcast(area, setScale(screen, fill, scaleX, scaleY));
            }
            case AUTO_SYNC -> {
                VideoArea area = getArea(dataHolder, player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                VideoInfo info = screen.currentPlaying();
                if (info == null || !info.seekable()) return;
                long clientTime = buf.readLong();
                IVideoListener listener = screen.getListener();
                if (listener == null) return;
                long progress = listener.getProgress();
                if (progress <= 0) return;
                long clientProgress = buf.readableBytes() >= Long.BYTES ? buf.readLong() : -1;
                progress = screen.autoSyncTarget(player.getUniqueId(), progress, clientProgress);
                plugin.sendTo(player, autoSync(screen, clientTime, progress));
            }
            case MEDIA_OPTIONS -> {
                if (!player.hasPermission("videoplayer.use")) return;
                VideoArea area = getArea(dataHolder, player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                String url = ByteBufUtils.readString(buf, 16384);
                fetchOptions(plugin, player, screen, url);
            }
            case SEEK -> {
                if (!player.hasPermission("videoplayer.use")) return;
                VideoArea area = getArea(dataHolder, player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                VideoInfo info = screen.currentPlaying();
                IVideoListener listener = screen.getListener();
                if (info == null || !info.seekable() || listener == null) return;
                long progress = Math.max(0, buf.readLong());
                if (dataHolder.config.debug) plugin.getLogger().info("seek player=%s area=%s screen=%s progress=%d".formatted(player.getName(), area.name, screen.name, progress));
                listener.setProgress(progress);
                if (area.hasPlayer()) plugin.broadcast(area, sync(screen, progress));
            }
            case QUEUE_ACTION -> {
                if (!player.hasPermission("videoplayer.use")) return;
                VideoArea area = getArea(dataHolder, player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                int action = buf.readUnsignedByte();
                int index = buf.readInt();
                if (action != 5 && !player.hasPermission("videoplayer.admin")) return;
                if (dataHolder.config.debug) plugin.getLogger().info("queue action player=%s area=%s screen=%s action=%d index=%d".formatted(player.getName(), area.name, screen.name, action, index));
                if (screen.queueAction(action, index)) {
                    if ((action == 3 && index == 0) || action == 4 || action == 5) {
                        screen.playNext();
                    }
                    screen.syncInfo();
                }
            }
            default -> player.kick(Component.text("Unknown packet type: " + type));
        }
        if (buf.readableBytes() > 0) {
            player.kick(Component.text("Illegal packet! Remaining: " + buf.readableBytes()));
        }
    }

    private static void fetchSource(VideoPlayerFoliaPlugin plugin, Player player, String url, Consumer<VideoInfo> cb) {
        fetchSource(plugin, player, url, "", cb);
    }

    private static void fetchSource(VideoPlayerFoliaPlugin plugin, Player player, String url, String optionId, Consumer<VideoInfo> cb) {
        CompletableFuture<VideoInfo> video = VideoProviders.from(url, new PlayerProviderSource(plugin, player), optionId);
        if (video == null) {
            player.sendMessage(Component.text("无法解析视频源"));
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                plugin.getLogger().info("start fetch");
                return video.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, plugin.getCoreExecutor()).thenAccept(v -> {
            if (v == null) {
                player.getScheduler().run(plugin, task -> player.sendMessage(Component.text("无法解析视频源")), null);
                return;
            }
            cb.accept(v);
        }).exceptionally(e -> {
            plugin.getLogger().warning("Failed to fetch video source for " + url + ": " + shortError(e));
            player.getScheduler().run(plugin, task -> player.sendMessage(Component.text("解析视频源失败: " + shortError(e), NamedTextColor.RED)), null);
            return null;
        });
    }

    private static void fetchOptions(VideoPlayerFoliaPlugin plugin, Player player, VideoScreen screen, String url) {
        CompletableFuture<List<MediaOption>> options = VideoProviders.options(url, new PlayerProviderSource(plugin, player));
        if (options == null) {
            plugin.sendTo(player, mediaOptions(screen, url, List.of()));
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                return options.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, plugin.getCoreExecutor()).thenAccept(list -> {
            List<MediaOption> safe = list == null ? List.of() : list;
            if (plugin.getDataHolder().config.debug) plugin.getLogger().info("media options player=%s screen=%s count=%d url=%s".formatted(player.getName(), screen.name, safe.size(), url));
            plugin.sendTo(player, mediaOptions(screen, url, safe));
        }).exceptionally(e -> {
            plugin.getLogger().warning("Failed to fetch media options for " + url + ": " + shortError(e));
            player.getScheduler().run(plugin, task -> player.sendMessage(Component.text("画质查询失败: " + shortError(e), NamedTextColor.RED)), null);
            plugin.sendTo(player, mediaOptions(screen, url, List.of()));
            return null;
        });
    }

    private static String shortError(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    private static VideoArea getArea(DataHolder dataHolder, Player player, String name) {
        String dim = player.getWorld().getKey().toString();
        dataHolder.lock();
        try {
            var byDim = dataHolder.areas.get(dim);
            if (byDim == null) return null;
            VideoArea area = byDim.get(name);
            if (area == null) return null;
            return area.containsPlayer(player.getUniqueId()) || player.hasPermission("videoplayer.admin") ? area : null;
        } finally {
            dataHolder.unlock();
        }
    }

    private static String readName(ByteBuf buf) {
        return ByteBufUtils.readString(buf, MAX_NAME_LENGTH);
    }

    private static ByteBuf create(int id) {
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer();
        buf.writeByte((byte) id);
        return buf;
    }

    private static byte[] toByteArray(ByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        buf.release();
        return bytes;
    }

    public static void readUV(ByteBuf buf, VideoScreen screen) {
        screen.u1 = buf.readFloat();
        screen.v1 = buf.readFloat();
        screen.u2 = buf.readFloat();
        screen.v2 = buf.readFloat();
    }

    public static void writeUV(ByteBuf buf, VideoScreen screen) {
        buf.writeFloat(screen.u1);
        buf.writeFloat(screen.v1);
        buf.writeFloat(screen.u2);
        buf.writeFloat(screen.v2);
    }

    public static void readScale(ByteBuf buf, VideoScreen screen) {
        screen.fill = buf.readBoolean();
        screen.scaleX = buf.readFloat();
        screen.scaleY = buf.readFloat();
    }

    public static void writeScale(ByteBuf buf, VideoScreen screen) {
        buf.writeBoolean(screen.fill);
        buf.writeFloat(screen.scaleX);
        buf.writeFloat(screen.scaleY);
    }

    public static byte[] config(String version, ServerConfig config) {
        return config(version, config, true);
    }

    public static byte[] config(String version, ServerConfig config, boolean canManageQueue) {
        ByteBuf buf = create(CONFIG);
        writeString(buf, version);
        writeString(buf, config.remoteControlName);
        buf.writeFloat(config.remoteControlId);
        buf.writeFloat(config.remoteControlRange);
        buf.writeFloat(config.noControlRange);
        buf.writeBoolean(canManageQueue);
        return toByteArray(buf);
    }

    public static byte[] request(VideoScreen screen, VideoInfo info) {
        ByteBuf buf = create(REQUEST);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        VideoInfo.write(buf, info);
        return toByteArray(buf);
    }

    public static byte[] sync(VideoScreen screen, long time) {
        ByteBuf buf = create(SYNC);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        buf.writeLong(time);
        return toByteArray(buf);
    }

    public static byte[] createArea(VideoArea area) {
        ByteBuf buf = create(CREATE_AREA);
        writeString(buf, area.name);
        VideoArea.write(buf, area);
        return toByteArray(buf);
    }

    public static byte[] removeArea(VideoArea area) {
        ByteBuf buf = create(REMOVE_AREA);
        writeString(buf, area.name);
        return toByteArray(buf);
    }

    public static byte[] createScreen(List<VideoScreen> screens) {
        ByteBuf buf = create(CREATE_SCREEN);
        writeString(buf, screens.getFirst().area.name);
        buf.writeByte(screens.size());
        for (VideoScreen screen : screens) {
            VideoScreen.write(buf, screen);
            writeUV(buf, screen);
            writeScale(buf, screen);
            screen.writeMeta(buf);
        }
        return toByteArray(buf);
    }

    public static byte[] removeScreen(VideoScreen screen) {
        ByteBuf buf = create(REMOVE_SCREEN);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        return toByteArray(buf);
    }

    public static byte[] loadArea(VideoArea area) {
        ByteBuf buf = create(LOAD_AREA);
        writeString(buf, area.name);
        for (VideoScreen screen : area.screens) {
            VideoInfo info = screen.currentPlaying();
            if (info == null) continue;
            writeString(buf, screen.name);
            VideoInfo.write(buf, info);
            buf.writeLong(screen.getProgress());
        }
        return toByteArray(buf);
    }

    public static byte[] unloadArea(VideoArea area) {
        ByteBuf buf = create(UNLOAD_AREA);
        writeString(buf, area.name);
        return toByteArray(buf);
    }

    public static byte[] updatePlaylist(List<VideoScreen> screens) {
        ByteBuf buf = create(UPDATE_PLAYLIST);
        writeString(buf, screens.getFirst().area.name);
        buf.writeByte(screens.size());
        for (VideoScreen screen : screens) {
            writeString(buf, screen.name);
            buf.writeByte(screen.infos.size());
            for (VideoInfo info : screen.infos) {
                writeString(buf, info.playerName());
                writeString(buf, info.name());
            }
        }
        return toByteArray(buf);
    }

    public static byte[] skip(VideoScreen screen) {
        ByteBuf buf = create(SKIP);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        return toByteArray(buf);
    }

    public static byte[] execute(String command) {
        ByteBuf buf = create(EXECUTE);
        writeString(buf, command);
        return toByteArray(buf);
    }

    public static byte[] setUV(VideoScreen screen, float u1, float v1, float u2, float v2) {
        ByteBuf buf = create(SET_UV);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        buf.writeFloat(u1);
        buf.writeFloat(v1);
        buf.writeFloat(u2);
        buf.writeFloat(v2);
        return toByteArray(buf);
    }

    public static byte[] setMeta(VideoScreen screen, int actionId, int value) {
        ByteBuf buf = create(SET_META);
        buf.writeByte(actionId);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        buf.writeInt(value);
        return toByteArray(buf);
    }

    public static byte[] setCustomMeta(VideoScreen screen, String key, int value, boolean remove) {
        ByteBuf buf = create(SET_CUSTOM_META);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        ByteBufUtils.writeString(buf, key);
        buf.writeInt(value);
        buf.writeBoolean(remove);
        return toByteArray(buf);
    }

    public static byte[] setScale(VideoScreen screen, boolean fill, float scaleX, float scaleY) {
        ByteBuf buf = create(SET_SCALE);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        buf.writeBoolean(fill);
        buf.writeFloat(scaleX);
        buf.writeFloat(scaleY);
        return toByteArray(buf);
    }

    public static byte[] autoSync(VideoScreen screen, long clientTime, long progress) {
        ByteBuf buf = create(AUTO_SYNC);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        buf.writeLong(clientTime);
        buf.writeLong(progress);
        return toByteArray(buf);
    }

    public static byte[] mediaOptions(VideoScreen screen, String url, List<MediaOption> options) {
        ByteBuf buf = create(MEDIA_OPTIONS);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        writeString(buf, url);
        buf.writeByte(Math.min(options.size(), 255));
        for (int i = 0; i < Math.min(options.size(), 255); i++) {
            MediaOption.write(buf, options.get(i));
        }
        return toByteArray(buf);
    }
}
