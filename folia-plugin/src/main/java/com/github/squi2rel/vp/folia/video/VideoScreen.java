package com.github.squi2rel.vp.folia.video;

import com.github.squi2rel.vp.folia.VideoPlayerFoliaPlugin;
import com.github.squi2rel.vp.folia.network.ByteBufUtils;
import com.github.squi2rel.vp.folia.network.ServerPacketHandler;
import com.github.squi2rel.vp.folia.provider.NamedProviderSource;
import com.github.squi2rel.vp.folia.provider.VideoInfo;
import com.github.squi2rel.vp.folia.provider.VideoProviders;
import io.netty.buffer.ByteBuf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class VideoScreen {
    public static final int MAX_NAME_LENGTH = 32;

    public transient VideoArea area;
    public String name;
    public Vector3f p1, p2, p3, p4;
    public float u1 = 0, v1 = 0, u2 = 1, v2 = 1;
    public boolean fill;
    public float scaleX = 1, scaleY = 1;
    public String source;
    public float skipPercent = 0.5f;
    public Map<String, Integer> meta;
    public transient ArrayDeque<VideoInfo> infos = new ArrayDeque<>();
    private transient IVideoListener now;
    private transient CompletableFuture<IVideoListener> nextTask;
    private transient HashSet<UUID> skipped;
    private transient ReentrantLock lock;
    private transient ScheduledFuture<?> stoppedFuture;
    private transient Map<UUID, ClientSyncState> clientSyncStates;
    private static final long CLIENT_PROGRESS_TTL = 5000;
    private static final long FORCE_SYNC_THRESHOLD = 10000;

    public VideoScreen(VideoArea area, String name, Vector3f p1, Vector3f p2, Vector3f p3, Vector3f p4, String source) {
        this.area = area;
        this.name = name;
        this.p1 = p1;
        this.p2 = p2;
        this.p3 = p3;
        this.p4 = p4;
        this.source = normalizeSource(source);
    }

    public static VideoScreen fromBlockCorners(VideoArea area, String name, Vector3f p1, Vector3f p2, Vector3f p3, Vector3f p4, String source) {
        Vector3f[] points = expandIntegerBlockRectangle(p1, p2, p3, p4);
        return new VideoScreen(area, name, points[0], points[1], points[2], points[3], source);
    }

    private static Vector3f[] expandIntegerBlockRectangle(Vector3f p1, Vector3f p2, Vector3f p3, Vector3f p4) {
        Vector3f[] points = {
                new Vector3f(p1),
                new Vector3f(p2),
                new Vector3f(p3),
                new Vector3f(p4)
        };
        for (int axis = 0; axis < 3; axis++) {
            if (!allInteger(points, axis)) continue;
            float min = component(points[0], axis);
            float max = min;
            for (Vector3f point : points) {
                float value = component(point, axis);
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
            if (max == min) continue;
            for (Vector3f point : points) {
                if (component(point, axis) == max) {
                    setComponent(point, axis, max + 1);
                }
            }
        }
        return points;
    }

    private static boolean allInteger(Vector3f[] points, int axis) {
        for (Vector3f point : points) {
            float value = component(point, axis);
            if (Math.abs(value - Math.round(value)) > 0.0001f) return false;
        }
        return true;
    }

    private static float component(Vector3f point, int axis) {
        return switch (axis) {
            case 0 -> point.x;
            case 1 -> point.y;
            case 2 -> point.z;
            default -> throw new IllegalArgumentException("axis");
        };
    }

    private static void setComponent(Vector3f point, int axis, float value) {
        switch (axis) {
            case 0 -> point.x = value;
            case 1 -> point.y = value;
            case 2 -> point.z = value;
            default -> throw new IllegalArgumentException("axis");
        }
    }

    public void readMeta(ByteBuf buf) {
        short size = buf.readUnsignedByte();
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < size; i++) {
            map.put(ByteBufUtils.readString(buf, 32), buf.readInt());
        }
        meta = map;
    }

    public void writeMeta(ByteBuf buf) {
        buf.writeByte(meta.size());
        for (Map.Entry<String, Integer> entry : meta.entrySet()) {
            ByteBufUtils.writeString(buf, entry.getKey());
            buf.writeInt(entry.getValue());
        }
    }

    public void syncInfo() {
        lock();
        byte[] data = ServerPacketHandler.updatePlaylist(java.util.List.of(this));
        unlock();
        VideoPlayerFoliaPlugin.getInstance().broadcast(area, data);
    }

    public void initServer() {
        source = normalizeSource(source);
        infos = new ArrayDeque<>();
        skipped = new HashSet<>();
        clientSyncStates = new HashMap<>();
        if (meta == null) meta = new HashMap<>();
        lock = new ReentrantLock();
        if (scaleX == 0 || scaleY == 0) {
            fill = false;
            scaleX = 1;
            scaleY = 1;
        }
        playNext();
    }

    public int skipped() {
        return skipped.size();
    }

    public synchronized void addInfo(VideoInfo info) {
        VideoPlayerFoliaPlugin.getInstance().getLogger().info("added info: %s %s pathLength=%d".formatted(info.playerName(), info.name(), info.path() == null ? 0 : info.path().length()));
        lock();
        infos.offer(info);
        unlock();
        playNext();
        syncInfo();
    }

    public synchronized boolean queueAction(int action, int index) {
        lock();
        try {
            if (action == 4) {
                boolean changed = !infos.isEmpty();
                if (now != null) {
                    now.cancel();
                    now = null;
                }
                if (nextTask != null) {
                    nextTask.cancel(true);
                    nextTask = null;
                }
                if (stoppedFuture != null) stoppedFuture.cancel(false);
                infos.clear();
                return changed;
            }
            java.util.ArrayList<VideoInfo> list = new java.util.ArrayList<>(infos);
            if (index < 0 || index >= list.size()) return false;
            if (action == 5) {
                if (index == 0) return false;
                if (now != null) {
                    now.cancel();
                    now = null;
                }
                if (nextTask != null) {
                    nextTask.cancel(true);
                    nextTask = null;
                }
                if (stoppedFuture != null) stoppedFuture.cancel(false);
                VideoInfo selected = list.remove(index);
                list.remove(0);
                list.add(0, selected);
            } else if (action == 3) {
                if (index == 0) {
                    if (now != null) {
                        now.cancel();
                        now = null;
                    }
                    if (nextTask != null) {
                        nextTask.cancel(true);
                        nextTask = null;
                    }
                    if (stoppedFuture != null) stoppedFuture.cancel(false);
                }
                list.remove(index);
            } else if (action == 0 && index > 1) {
                VideoInfo info = list.remove(index);
                list.add(1, info);
            } else if (action == 1 && index > 1) {
                java.util.Collections.swap(list, index, index - 1);
            } else if (action == 2 && index > 0 && index < list.size() - 1) {
                java.util.Collections.swap(list, index, index + 1);
            } else {
                return false;
            }
            infos.clear();
            infos.addAll(list);
            return true;
        } finally {
            unlock();
        }
    }

    public long getProgress() {
        VideoInfo info = infos.peek();
        if (now == null || info == null || !info.seekable()) return -1;
        return now.getProgress();
    }

    public synchronized void voteSkip(UUID uuid) {
        skipped.add(uuid);
        if (shouldSkip()) skip();
    }

    public synchronized void setSkipPercent(float skipPercent) {
        this.skipPercent = skipPercent;
        if (shouldSkip()) skip();
    }

    private boolean shouldSkip() {
        return skipped.size() > area.players() * skipPercent;
    }

    public synchronized void skip() {
        lock();
        if (stoppedFuture != null) stoppedFuture.cancel(false);
        if (nextTask != null) {
            nextTask.cancel(true);
            nextTask = null;
        }
        if (now != null) {
            now.cancel();
            now = null;
        }
        infos.poll();
        unlock();
        playNext();
        syncInfo();
    }

    public synchronized void removePlayer(UUID uuid) {
        skipped.remove(uuid);
        clientSyncStates.remove(uuid);
        if (shouldSkip()) skip();
    }

    public synchronized void remove() {
        lock();
        if (now != null) now.cancel();
        if (nextTask != null) nextTask.cancel(true);
        if (stoppedFuture != null) stoppedFuture.cancel(false);
        now = null;
        nextTask = null;
        clientSyncStates.clear();
        infos.clear();
        unlock();
        syncInfo();
    }

    public void lock() {
        lock.lock();
    }

    public void unlock() {
        lock.unlock();
    }

    public synchronized void playNext() {
        if (nextTask != null && !nextTask.isDone() || now != null && now.isPlaying()) return;
        VideoPlayerFoliaPlugin plugin = VideoPlayerFoliaPlugin.getInstance();
        now = null;
        skipped.clear();
        clientSyncStates.clear();
        nextTask = CompletableFuture.supplyAsync(() -> {
            lock();
            VideoInfo info = infos.peek();
            unlock();
            if (info == null) {
                if (area.hasPlayer()) {
                    plugin.broadcast(area, ServerPacketHandler.skip(this));
                    syncInfo();
                }
                return null;
            }
            plugin.getLogger().info("playing info: %s %s pathLength=%d".formatted(info.playerName(), info.name(), info.path() == null ? 0 : info.path().length()));
            if (info.expire() > 0 && System.currentTimeMillis() > info.expire()) {
                try {
                    info = Objects.requireNonNull(VideoProviders.from(info.rawPath(), new NamedProviderSource(info.playerName()))).get();
                } catch (Exception ignored) {
                }
            }
            if (info == null || info.expire() > 0 && System.currentTimeMillis() > info.expire()) return null;
            if (area.hasPlayer()) {
                plugin.broadcast(area, ServerPacketHandler.request(this, info));
            }
            syncInfo();
            if (!info.rawPath().isEmpty()) {
                try {
                    info = Objects.requireNonNull(VideoProviders.from(info.rawPath(), new NamedProviderSource(info.playerName()))).get();
                } catch (Exception ignored) {
                }
            }
            return now = VideoListeners.from(info);
        }, plugin.getCoreExecutor());
        nextTask.thenAccept(s -> {
            if (s == null) return;
            synchronized (this) {
                nextTask = null;
                s.stopped(() -> stoppedFuture = plugin.getSchedulerExecutor().schedule(() -> {
                    lock();
                    infos.poll();
                    unlock();
                    playNext();
                }, 2, TimeUnit.SECONDS));
                s.listen();
            }
        });
    }

    public VideoInfo currentPlaying() {
        return infos.peek();
    }

    public IVideoListener getListener() {
        return now;
    }

    public synchronized long autoSyncTarget(UUID uuid, long serverProgress, long clientProgress) {
        if (clientSyncStates == null) clientSyncStates = new HashMap<>();
        long nowMs = System.currentTimeMillis();
        clientSyncStates.entrySet().removeIf(entry -> nowMs - entry.getValue().updatedAt > CLIENT_PROGRESS_TTL);
        if (clientProgress > 0) {
            clientSyncStates.put(uuid, new ClientSyncState(clientProgress, nowMs));
        }
        long fastest = Math.max(0, serverProgress);
        for (ClientSyncState state : clientSyncStates.values()) {
            fastest = Math.max(fastest, state.progress);
        }
        if (clientProgress > 0 && fastest - clientProgress > FORCE_SYNC_THRESHOLD) {
            return fastest;
        }
        return serverProgress;
    }

    private static final class ClientSyncState {
        private final long progress;
        private final long updatedAt;

        private ClientSyncState(long progress, long updatedAt) {
            this.progress = progress;
            this.updatedAt = updatedAt;
        }
    }

    public static VideoScreen read(ByteBuf buf, VideoArea area) {
        return new VideoScreen(
                area,
                ByteBufUtils.readString(buf, MAX_NAME_LENGTH),
                ByteBufUtils.readVec3(buf),
                ByteBufUtils.readVec3(buf),
                ByteBufUtils.readVec3(buf),
                ByteBufUtils.readVec3(buf),
                ByteBufUtils.readString(buf, MAX_NAME_LENGTH)
        );
    }

    public static void write(ByteBuf buf, VideoScreen screen) {
        ByteBufUtils.writeString(buf, screen.name);
        ByteBufUtils.writeVec3(buf, screen.p1);
        ByteBufUtils.writeVec3(buf, screen.p2);
        ByteBufUtils.writeVec3(buf, screen.p3);
        ByteBufUtils.writeVec3(buf, screen.p4);
        ByteBufUtils.writeString(buf, normalizeSource(screen.source));
    }

    public static String normalizeSource(String source) {
        if (source == null) return "";
        String value = source.trim();
        if (value.equals("\"\"") || value.equals("''")) return "";
        return value;
    }
}
