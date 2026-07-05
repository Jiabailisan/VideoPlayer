package com.github.squi2rel.vp.network;

import com.github.squi2rel.vp.ServerConfig;
import com.github.squi2rel.vp.VideoPlayerMain;
import com.github.squi2rel.vp.provider.MediaOption;
import com.github.squi2rel.vp.provider.PlayerProviderSource;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.provider.VideoProviders;
import com.github.squi2rel.vp.video.IVideoListener;
import com.github.squi2rel.vp.video.VideoArea;
import com.github.squi2rel.vp.DataHolder;
import com.github.squi2rel.vp.video.VideoScreen;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;
import static com.github.squi2rel.vp.network.ByteBufUtils.readString;
import static com.github.squi2rel.vp.network.ByteBufUtils.writeString;
import static com.github.squi2rel.vp.video.VideoScreen.MAX_NAME_LENGTH;
import static com.github.squi2rel.vp.network.PacketID.*;

public class ServerPacketHandler {
    public static void handle(ServerPlayerEntity player, ByteBuf buf) {
        if (buf.readableBytes() < 1) {
            throw new IllegalStateException("empty VideoPlayer packet");
        }
        short type = buf.readUnsignedByte();
        if (DataHolder.config.debug) LOGGER.info("server type: {}", type);
        switch (type) {
            case CONFIG -> {
                ByteBufUtils.readString(buf, 16);
                DataHolder.lock();
                DataHolder.allPlayers.add(player.getUuid());
                DataHolder.unlock();
            }
            case REQUEST -> {
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                String url = ByteBufUtils.readString(buf, 16384);
                String optionId = buf.readableBytes() > 0 ? ByteBufUtils.readString(buf, 128) : "";
                if (DataHolder.config.debug) LOGGER.info("request player={} area={} screen={} option={} url={}", player.getName().getString(), area.name, screen.name, optionId, url);
                if (fetchSource(player, url, optionId, screen::addInfo)) return;
            }
            case SYNC -> {
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null || screen.currentPlaying() == null) return;
                sendTo(player, sync(screen, screen.getProgress()));
            }
            case CREATE_AREA -> {
                if (!isAdmin(player)) return;
                VideoArea area = VideoArea.from(ByteBufUtils.readVec3(buf), ByteBufUtils.readVec3(buf), readName(buf), player.getEntityWorld().getRegistryKey().getValue().toString());
                area.initServer();
                DataHolder.lock();
                DataHolder.areas.computeIfAbsent(area.dim, k -> new HashMap<>()).put(area.name, area);
                DataHolder.unlock();
                player.sendMessage(Text.literal("已成功在世界 " + player.getEntityWorld().getRegistryKey().getValue() + " 创建名为 " + area.name + " 的观影区!").formatted(Formatting.GREEN));
            }
            case REMOVE_AREA -> {
                if (!isAdmin(player)) return;
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                DataHolder.lock();
                DataHolder.areas.get(area.dim).remove(area.name).remove();
                if (area.hasPlayer()) {
                    byte[] data = removeArea(area);
                    PlayerManager pm = player.getEntityWorld().getServer().getPlayerManager();
                    area.forEachPlayer(p -> sendTo(pm.getPlayer(p), data));
                }
                DataHolder.unlock();
                player.sendMessage(Text.literal("已成功在世界 " + player.getEntityWorld().getRegistryKey().getValue() + " 移除名为 " + area.name + " 的观影区!").formatted(Formatting.GREEN));
            }
            case CREATE_SCREEN -> {
                if (!isAdmin(player)) return;
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = VideoScreen.read(buf, area);
                screen.initServer();
                DataHolder.lock();
                area.addScreen(screen);
                if (area.hasPlayer()) {
                    byte[] data = createScreen(List.of(screen));
                    PlayerManager pm = player.getEntityWorld().getServer().getPlayerManager();
                    area.forEachPlayer(p -> sendTo(pm.getPlayer(p), data));
                }
                DataHolder.unlock();
                player.sendMessage(Text.literal("已成功在观影区 " + area.name + " 创建名为 " + screen.name + " 的屏幕!").formatted(Formatting.GREEN));
            }
            case REMOVE_SCREEN -> {
                if (!isAdmin(player)) return;
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                DataHolder.lock();
                VideoScreen screen = area.removeScreen(readName(buf));
                if (screen != null && area.hasPlayer()) {
                    byte[] data = removeScreen(screen);
                    PlayerManager pm = player.getEntityWorld().getServer().getPlayerManager();
                    area.forEachPlayer(p -> sendTo(pm.getPlayer(p), data));
                    player.sendMessage(Text.literal("已成功在观影区 " + area.name + " 移除名为 " + screen.name + " 的屏幕!").formatted(Formatting.GREEN));
                }
                DataHolder.unlock();
            }
            case SKIP -> {
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                boolean force = buf.readBoolean();
                if (force) {
                    if (!isAdmin(player)) return;
                    screen.skip();
                    return;
                }
                screen.voteSkip(player.getUuid());
                Text s = Text.literal("玩家 %s 已投票跳过 %s 上的视频 还需 %d 个玩家".formatted(
                        player.getName(), screen.name, screen.skipped() == 0 ? 0 : (int) (area.players() * screen.skipPercent - screen.skipped() + 1)
                ));
                player.sendMessage(Text.literal("已投票跳过此视频").formatted(Formatting.GOLD));
                PlayerManager pm = player.getEntityWorld().getServer().getPlayerManager();
                area.forEachPlayer(p -> Objects.requireNonNull(pm.getPlayer(p)).sendMessage(s));
            }
            case SKIP_PERCENT -> {
                if (!isAdmin(player)) return;
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                screen.setSkipPercent(buf.readFloat());
                player.sendMessage(Text.literal("屏幕 " + screen.name + " 的投票跳过比例已设置为 " + screen.skipPercent).formatted(Formatting.GREEN));
            }
            case IDLE_PLAY -> {
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                readString(buf, 1024);
            }
            case SET_UV -> {
                if (!isAdmin(player)) return;
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                readUV(buf, screen);
                if (area.hasPlayer()) {
                    byte[] data = setUV(screen, screen.u1, screen.v1, screen.u2, screen.v2);
                    PlayerManager pm = player.getEntityWorld().getServer().getPlayerManager();
                    area.forEachPlayer(p -> sendTo(pm.getPlayer(p), data));
                }
            }
            case OPEN_MENU -> {
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                sendTo(player, config(VideoPlayerMain.version, DataHolder.config, isAdmin(player)));
            }
            case SET_META -> {
                if (!isAdmin(player)) return;
                short id = buf.readUnsignedByte();
                if (id >= Action.VALUES.length) {
                    player.networkHandler.disconnect(Text.of("Unknown action type: " + id));
                    return;
                }
                Action action = Action.VALUES[id];
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                int value = buf.readInt();
                if (!action.verify(value)) {
                    player.networkHandler.disconnect(Text.of("Invalid value: " + value));
                    return;
                }
                action.apply(screen, value);
                if (area.hasPlayer()) {
                    byte[] data = setMeta(screen, id, value);
                    PlayerManager pm = player.getEntityWorld().getServer().getPlayerManager();
                    area.forEachPlayer(p -> sendTo(pm.getPlayer(p), data));
                }
            }
            case SET_CUSTOM_META -> {
                if (!isAdmin(player)) return;
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                String key = readName(buf);
                int value = buf.readInt();
                boolean remove = buf.readBoolean();
                if (remove) {
                    screen.meta.remove(key);
                } else {
                    screen.meta.put(key, value);
                }
                if (area.hasPlayer()) {
                    byte[] data = setCustomMeta(screen, key, value, remove);
                    PlayerManager pm = player.getEntityWorld().getServer().getPlayerManager();
                    area.forEachPlayer(p -> sendTo(pm.getPlayer(p), data));
                }
            }
            case SET_SCALE -> {
                if (!isAdmin(player)) return;
                VideoArea area = getArea(player, readName(buf));
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
                if (area.hasPlayer()) {
                    byte[] data = setScale(screen, fill, scaleX, scaleY);
                    PlayerManager pm = player.getEntityWorld().getServer().getPlayerManager();
                    area.forEachPlayer(p -> sendTo(pm.getPlayer(p), data));
                }
            }
            case AUTO_SYNC -> {
                VideoArea area = getArea(player, readName(buf));
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
                progress = screen.autoSyncTarget(player.getUuid(), progress, clientProgress);
                sendTo(player, autoSync(screen, clientTime, progress));
            }
            case MEDIA_OPTIONS -> {
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                String url = ByteBufUtils.readString(buf, 16384);
                fetchOptions(player, screen, url);
            }
            case SEEK -> {
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                VideoInfo info = screen.currentPlaying();
                IVideoListener listener = screen.getListener();
                if (info == null || !info.seekable() || listener == null) return;
                long progress = Math.max(0, buf.readLong());
                if (DataHolder.config.debug) LOGGER.info("seek player={} area={} screen={} progress={}", player.getName().getString(), area.name, screen.name, progress);
                listener.setProgress(progress);
                if (area.hasPlayer()) {
                    byte[] data = sync(screen, progress);
                    PlayerManager pm = player.getEntityWorld().getServer().getPlayerManager();
                    area.forEachPlayer(p -> sendTo(pm.getPlayer(p), data));
                }
            }
            case QUEUE_ACTION -> {
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                int action = buf.readUnsignedByte();
                int index = buf.readInt();
                if (action != 5 && !isAdmin(player)) return;
                if (DataHolder.config.debug) LOGGER.info("queue action player={} area={} screen={} action={} index={}", player.getName().getString(), area.name, screen.name, action, index);
                if (screen.queueAction(action, index)) {
                    if ((action == 3 && index == 0) || action == 4 || action == 5) {
                        screen.playNext();
                    }
                    screen.syncInfo();
                }
            }
            default -> player.networkHandler.disconnect(Text.of("Unknown packet type: " + type));
        }
        if (buf.readableBytes() > 0) {
            player.networkHandler.disconnect(Text.of("Illegal packet! Remaining: " + buf.readableBytes()));
        }
    }

    private static boolean fetchSource(ServerPlayerEntity player, String url, Consumer<VideoInfo> cb) {
        return fetchSource(player, url, "", cb);
    }

    private static boolean fetchSource(ServerPlayerEntity player, String url, String optionId, Consumer<VideoInfo> cb) {
        CompletableFuture<VideoInfo> video = VideoProviders.from(url, new PlayerProviderSource(player), optionId);
        if (video == null) {
            player.sendMessage(Text.of("无法解析视频源"));
            return true;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                LOGGER.info("start fetch");
                return video.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).thenAccept(v -> {
            try {
                if (v == null) {
                    player.sendMessage(Text.of("无法解析视频源"));
                    return;
                }
                cb.accept(v);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).exceptionally(e -> {
            LOGGER.warn("Failed to fetch video source for {}", url, e);
            player.sendMessage(Text.literal("解析视频源失败: " + shortError(e)).formatted(Formatting.RED));
            return null;
        });
        return false;
    }

    private static void fetchOptions(ServerPlayerEntity player, VideoScreen screen, String url) {
        CompletableFuture<List<MediaOption>> options = VideoProviders.options(url, new PlayerProviderSource(player));
        if (options == null) {
            sendTo(player, mediaOptions(screen, url, List.of()));
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                return options.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).thenAccept(list -> {
            List<MediaOption> safe = list == null ? List.of() : list;
            if (DataHolder.config.debug) LOGGER.info("media options player={} screen={} count={} url={}", player.getName().getString(), screen.name, safe.size(), url);
            sendTo(player, mediaOptions(screen, url, safe));
        }).exceptionally(e -> {
            LOGGER.warn("Failed to fetch media options for {}", url, e);
            player.sendMessage(Text.literal("画质查询失败: " + shortError(e)).formatted(Formatting.RED));
            sendTo(player, mediaOptions(screen, url, List.of()));
            return null;
        });
    }

    private static String shortError(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    private static VideoArea getArea(ServerPlayerEntity player, String name) {
        String dim = player.getEntityWorld().getRegistryKey().getValue().toString();
        DataHolder.lock();
        VideoArea area;
        try {
            HashMap<String, VideoArea> byDim = DataHolder.areas.get(dim);
            area = byDim == null ? null : byDim.get(name);
        } finally {
            DataHolder.unlock();
        }
        return area != null && (area.containsPlayer(player.getUuid()) || isAdmin(player)) ? area : null;
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

    public static void sendTo(ServerPlayerEntity player, byte[] bytes) {
        ServerPlayNetworking.send(player, new VideoPayload(bytes));
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

    public static boolean isAdmin(ServerPlayerEntity player) {
        try {
            Object result = ServerPlayerEntity.class.getMethod("hasPermissionLevel", int.class).invoke(player, 2);
            return result instanceof Boolean allowed && allowed;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
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
