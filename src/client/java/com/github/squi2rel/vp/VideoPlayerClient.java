package com.github.squi2rel.vp;

import com.github.squi2rel.vp.network.PacketID;
import com.github.squi2rel.vp.network.VideoPayload;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.video.*;
import com.github.squi2rel.vp.vivecraft.Vivecraft;
import com.google.gson.Gson;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.BossBarS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.util.profiler.Profilers;
import org.apache.commons.lang3.StringUtils;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;
import static com.github.squi2rel.vp.VideoPlayerMain.error;

@SuppressWarnings({"DataFlowIssue"})
public class VideoPlayerClient implements ClientModInitializer {
    public static final Path configPath = FabricLoader.getInstance().getConfigDir().resolve("videoplayer-client.json");
    public static final MinecraftClient client = MinecraftClient.getInstance();
    public static Config config;
    private static final Gson gson = new Gson();

    public static final HashMap<String, ClientVideoArea> areas = new HashMap<>();
    public static final ArrayList<ClientVideoScreen> screens = new ArrayList<>();
    private static final TouchHandler touchHandler = new TouchHandler();
    private static ClientVideoScreen currentLooking, currentScreen;
    private static boolean isInArea = false;
    private static BossBar bossBar = null;
    private static boolean bossBarAdded = false;
    private static boolean keyPressed = false;

    public static boolean connected = false;
    public static String remoteControlName = "minecraft:iron_ingot";
    public static float remoteControlId = -1;
    public static float remoteControlRange = 64;
    public static float noControlRange = 16;
    public static boolean remoteControl = false;
    public static boolean canManageQueue = true;

    public static boolean updated = false;
    public static Runnable disconnectHandler = () -> {};

    private static final SuggestionProvider<FabricClientCommandSource> SUGGEST_AREAS = (context, builder) -> {
        for (ClientVideoArea a : areas.values()) {
            if (a.name.startsWith(builder.getRemaining())) {
                builder.suggest("\"" + a.name.replace("\\", "\\\\") + "\"");
            }
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<FabricClientCommandSource> SUGGEST_SCREENS = (context, builder) -> {
        ClientVideoArea area = areas.get(context.getArgument("area", String.class));
        if (area == null) return Suggestions.empty();
        for (VideoScreen screen : area.screens) {
            if (!((ClientVideoScreen) screen).interactable) continue;
            if (screen.name.startsWith(builder.getRemaining())) {
                builder.suggest("\"" + screen.name.replace("\\", "\\\\") + "\"");
            }
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<FabricClientCommandSource> SUGGEST_REAL_SCREENS = (context, builder) -> {
        ClientVideoArea area = areas.get(context.getArgument("area", String.class));
        if (area == null) return Suggestions.empty();
        for (VideoScreen screen : area.screens) {
            if (!screen.source.isEmpty() || !((ClientVideoScreen) screen).interactable) continue;
            if (screen.name.startsWith(builder.getRemaining())) {
                builder.suggest("\"" + screen.name.replace("\\", "\\\\") + "\"");
            }
        }
        return builder.buildFuture();
    };

    @Override
    public void onInitializeClient() {
        if (error != null) {
            ClientPlayConnectionEvents.JOIN.register((h, s, c) -> c.player.sendMessage(Text.literal("VideoPlayer错误: libVLC库加载失败\n" + error + "\n查看日志获取更多信息").formatted(Formatting.RED), false));
        }
        loadConfig();
        disconnectHandler = () -> client.execute(() -> {
            connected = false;
            for (ClientVideoArea area : areas.values()) {
                area.remove();
            }
            areas.clear();
            for (ClientVideoScreen screen : screens) {
                screen.cleanup();
            }
            screens.clear();
            currentLooking = null;
        });
        if (Vivecraft.loaded) LOGGER.info("Found Vivecraft");
        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> {
            if (client.isInSingleplayer()) ClientPacketHandler.config(VideoPlayerMain.version);
        });
        WorldRenderEvents.START_MAIN.register(e -> VideoPlayerClient.update());
        WorldRenderEvents.AFTER_ENTITIES.register(ScreenRenderer::render);
        WorldRenderEvents.END_MAIN.register(e -> VideoPlayerClient.postUpdate());
        ClientPlayNetworking.registerGlobalReceiver(VideoPayload.ID, (p, c) -> client.execute(() -> {
            ByteBuf buf = Unpooled.wrappedBuffer(p.data());
            try {
                ClientPacketHandler.handle(buf);
            } catch (Throwable e) {
                LOGGER.error("Exception while handling packet", e);
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("VideoPlayer 客户端处理数据包失败: " + ClientPacketHandler.shortError(e)).formatted(Formatting.RED), false);
                }
            } finally {
                buf.release();
            }
        }));
        ClientCommandRegistrationCallback.EVENT.register((d, c) -> d.register(ClientCommandManager.literal("vlc-client")
                .then(ClientCommandManager.literal("menu")
                        .executes(s -> openClientMenu(s.getSource())))
                .then(ClientCommandManager.literal("volume")
                        .then(ClientCommandManager.argument("volume", IntegerArgumentType.integer(0, 100))
                                .executes(s -> setClientVolume(s.getSource(), s.getArgument("volume", Integer.class)))))
                .then(ClientCommandManager.literal("brightness")
                        .then(ClientCommandManager.argument("brightness", IntegerArgumentType.integer(0, 100))
                                .executes(s -> setClientBrightness(s.getSource(), s.getArgument("brightness", Integer.class)))))
                .then(ClientCommandManager.literal("status")
                        .executes(s -> showClientStatus(s.getSource())))
                .then(ClientCommandManager.literal("redownload")
                        .executes(s -> redownloadVlc(s.getSource())))));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null || client.currentScreen != null) return;
            ClientVideoScreen target = currentLooking != null ? currentLooking : currentScreen;
            if (target == null) return;
            boolean pressed = client.options.useKey.isPressed();
            if (pressed && !keyPressed) {
                keyPressed = true;
                if (remoteControl) {
                    if (hasCoreConnection()) ClientPacketHandler.openMenu(target);
                    VideoControlScreen.open(target);
                }
            } else if (!pressed) {
                keyPressed = false;
            }
        });
        bossBar = new ClientBossBar(UUID.randomUUID(), Text.of(""), 0, BossBar.Color.WHITE, BossBar.Style.PROGRESS, false, false, false);
    }

    private static int openClientMenu(FabricClientCommandSource source) {
        ClientVideoScreen target = currentLooking != null ? currentLooking : currentScreen;
        if (target == null) {
            if (isInArea) {
                source.sendFeedback(Text.literal("当前观影区未存在可操作屏幕。").formatted(Formatting.YELLOW));
            } else {
                source.sendFeedback(Text.literal("当前不在观影区内。").formatted(Formatting.YELLOW));
            }
            source.sendFeedback(Text.literal("创建区域: /vlc-core createArea <x1> <y1> <z1> <x2> <y2> <z2> <name>").formatted(Formatting.GRAY));
            source.sendFeedback(Text.literal("创建屏幕: /vlc-core createScreen <area> <name> <x1 y1 z1> <x2 y2 z2> <x3 y3 z3> <x4 y4 z4> [source]").formatted(Formatting.GRAY));
            return 1;
        }
        if (hasCoreConnection()) ClientPacketHandler.openMenu(target);
        client.execute(() -> VideoControlScreen.open(target));
        return 1;
    }

    private static int setClientVolume(FabricClientCommandSource source, int volume) {
        applyClientVolume(volume);
        source.sendFeedback(Text.literal("音量已设置为 " + volume + "%").formatted(Formatting.GREEN));
        return 1;
    }

    private static int setClientBrightness(FabricClientCommandSource source, int brightness) {
        applyClientBrightness(brightness);
        source.sendFeedback(Text.literal("亮度已设置为 " + brightness + "%").formatted(Formatting.GREEN));
        return 1;
    }

    private static int showClientStatus(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal(VlcLibrary.statusText()).formatted(Formatting.AQUA));
        source.sendFeedback(Text.literal("Core连接: " + (hasCoreConnection() ? "已连接" : "未连接")).formatted(hasCoreConnection() ? Formatting.GREEN : Formatting.YELLOW));
        return 1;
    }

    private static int redownloadVlc(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("开始重新下载 VLC 本地库...").formatted(Formatting.YELLOW));
        CompletableFuture.runAsync(() -> {
            try {
                VlcLibrary.redownload();
                client.execute(() -> source.sendFeedback(Text.literal("VLC 本地库已重新下载: " + VlcLibrary.dir().toAbsolutePath()).formatted(Formatting.GREEN)));
            } catch (Exception e) {
                LOGGER.error("Failed to redownload VLC library", e);
                client.execute(() -> source.sendFeedback(Text.literal("VLC 本地库重新下载失败: " + e).formatted(Formatting.RED)));
            }
        });
        return 1;
    }

    public static void applyClientVolume(int volume) {
        config.volume = Math.max(0, Math.min(100, volume));
        saveConfig();
        for (ClientVideoScreen screen : screens) {
            if (screen.player instanceof VideoPlayer) {
                screen.player.setVolume(config.volume);
            }
        }
    }

    public static void applyClientBrightness(int brightness) {
        config.brightness = Math.max(0, Math.min(100, brightness));
        saveConfig();
    }

    private ClientVideoArea getArea(CommandContext<FabricClientCommandSource> s) {
        if (checkInvalid(s, false)) return null;
        String name = s.getArgument("area", String.class);
        ClientVideoArea area = areas.get(name);
        if (area == null) {
            s.getSource().sendFeedback(Text.literal("没有名为 " + name + " 的观影区").formatted(Formatting.RED));
            return null;
        }
        return area;
    }

    private ClientVideoScreen getScreen(CommandContext<FabricClientCommandSource> s) {
        if (checkInvalid(s, false)) return null;
        ClientVideoArea area = getArea(s);
        if (area == null) return null;
        String name = s.getArgument("screen", String.class);
        ClientVideoScreen screen = area.getScreen(name);
        if (screen == null) {
            s.getSource().sendFeedback(Text.literal("屏幕未找到").formatted(Formatting.RED));
            return null;
        }
        return screen;
    }

    private boolean checkInvalid(CommandContext<FabricClientCommandSource> s, boolean checkScreen) {
        if (!hasCoreConnection()) {
            s.getSource().sendFeedback(Text.literal("未连接到服务器").formatted(Formatting.RED));
            return true;
        }
        if (checkScreen && currentScreen == null) {
            if (isInArea) {
                s.getSource().sendFeedback(Text.literal("当前观影区没有主屏幕").formatted(Formatting.RED));
            } else {
                s.getSource().sendFeedback(Text.literal("当前没有在观影区内").formatted(Formatting.RED));
            }
            return true;
        }
        return false;
    }

    private boolean checkInvalidLooking(CommandContext<FabricClientCommandSource> s) {
        if (!hasCoreConnection()) {
            s.getSource().sendFeedback(Text.literal("未连接到服务器").formatted(Formatting.RED));
            return true;
        }
        if (currentLooking == null) {
            s.getSource().sendFeedback(Text.literal("当前没有看向屏幕").formatted(Formatting.RED));
            return true;
        }
        return false;
    }

    private boolean checkInvalidForPlayback(CommandContext<FabricClientCommandSource> s) {
        if (currentScreen == null) {
            if (isInArea) {
                s.getSource().sendFeedback(Text.literal("当前观影区没有主屏幕").formatted(Formatting.RED));
            } else {
                s.getSource().sendFeedback(Text.literal("当前没有在观影区内").formatted(Formatting.RED));
            }
            return true;
        }
        return false;
    }

    private static void updateBossBar() {
        ClientVideoScreen target = currentLooking != null ? currentLooking : currentScreen;
        if (target != null) {
            ClientPlayNetworkHandler handler = client.getNetworkHandler();
            if (!bossBarAdded) {
                handler.onBossBar(BossBarS2CPacket.add(bossBar));
                bossBarAdded = true;
            }
            ClientVideoScreen screen = target.getScreen();
            VideoInfo info = screen.infos.peek();
            if (info != null && screen.player != null) {
                String name = info.name();
                long progress = Math.max(0, screen.player.getProgress());
                long totalProgress = screen.player.getTotalProgress();
                String time;
                if (totalProgress > 0) {
                    boolean showHour = progress >= 3600000 || totalProgress >= 3600000;
                    time = formatDuration(progress, showHour) + "/" + formatDuration(totalProgress, showHour);
                    bossBar.setPercent(Math.clamp((float) progress / totalProgress, 0f, 1f));
                } else {
                    time = formatDuration(progress, progress >= 3600000) + "/LIVE";
                    bossBar.setPercent(0);
                }
                bossBar.setName(Text.of(name + " " + time));
            } else {
                bossBar.setName(Text.of("无"));
                bossBar.setPercent(1);
            }
            handler.onBossBar(BossBarS2CPacket.updateName(bossBar));
            handler.onBossBar(BossBarS2CPacket.updateProgress(bossBar));
        } else if (bossBarAdded) {
            ClientPlayNetworkHandler handler = client.getNetworkHandler();
            handler.onBossBar(BossBarS2CPacket.remove(bossBar.getUuid()));
            bossBarAdded = false;
        }
    }

    private static void checkInteract() {
        MinecraftClient client = VideoPlayerClient.client;
        if (client == null) return;

        isInArea = false;
        currentLooking = null;
        currentScreen = null;
        if (screens.isEmpty()) {
            touchHandler.handle(null);
            return;
        }

        float delta = VideoPlayerClient.client.getRenderTickCounter().getTickProgress(true);
        Vec3d eyePos = client.player.getCameraPosVec(delta);
        Vec3d lookVec = client.player.getRotationVec(delta);

        Vector3f lineStart = new Vector3f(eyePos.toVector3f());

        remoteControl = false;
        for (ItemStack item : List.of(client.player.getMainHandStack(), client.player.getOffHandStack())) {
            if (!Registries.ITEM.getId(item.getItem()).toString().equals(remoteControlName)) continue;
            if (remoteControlId < 0) {
                remoteControl = true;
                break;
            }
            CustomModelDataComponent data = item.getComponents().get(DataComponentTypes.CUSTOM_MODEL_DATA);
            if (data == null) continue;
            List<Float> id = data.floats();
            if (id.isEmpty() || !id.contains(remoteControlId)) continue;
            remoteControl = true;
            break;
        }
        Vector3f lineEnd = eyePos.add(lookVec.multiply(remoteControl ? remoteControlRange : noControlRange)).toVector3f();

        ArrayList<Intersection.Result> list = new ArrayList<>();
        for (ClientVideoScreen s : screens) {
            if (!s.interactable) continue;
            ClientVideoScreen screen = s.getTrackingScreen();
            if (screen == null)  continue;
            Intersection.Result result = Intersection.intersect(lineStart, lineEnd, screen);
            if (result.intersects) list.add(result);
        }
        Intersection.Result target = list.isEmpty() ? null : Collections.min(list, Comparator.comparing(s -> s.distance));
        currentLooking = target == null || target.screen == null ? null : target.screen;
        touchHandler.handle(target);

        if (currentLooking != null) {
            currentScreen = currentLooking;
            return;
        }

        currentScreen = null;
        for (ClientVideoArea area : areas.values()) {
            if (!area.loaded) continue;
            isInArea = true;
            for (VideoScreen screen : area.screens) {
                ClientVideoScreen s = (ClientVideoScreen) screen;
                if (s.interactable) {
                    currentScreen = s;
                    break;
                }
            }
        }
    }

    public static boolean checkVersion(String v) {
        String[] p1 = StringUtils.split(v, '.');
        String[] p2 = StringUtils.split(VideoPlayerMain.version, '.');
        if (p1.length < 2 || p2.length < 2) return false;
        return p1[0].equals(p2[0]) && p1[1].equals(p2[1]);
    }

    public static void update() {
        if (updated) return;
        Profiler profiler = Profilers.get();
        profiler.push("video");
        profiler.push("updateFrame");
        for (ClientVideoScreen screen : screens) {
            if (screen.isPostUpdate()) continue;
            screen.swapTexture();
            screen.update();
        }
        profiler.swap("checkInteract");
        checkInteract();
        profiler.swap("updateBossBar");
        updateBossBar();
        profiler.pop();
        profiler.pop();
    }

    public static void postUpdate() {
        if (updated) return;
        updated = true;
        Profiler profiler = Profilers.get();
        profiler.push("video");
        profiler.push("updateFrame");
        for (ClientVideoScreen screen : screens) {
            if (!screen.isPostUpdate()) continue;
            screen.update();
        }
        profiler.pop();
        profiler.pop();
    }

    private static String formatDuration(long millis, boolean showHour) {
        long all = millis / 1000;
        long hours = all / 3600;
        long minutes = (all % 3600) / 60;
        long seconds = all % 60;

        if (showHour) {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%02d:%02d", minutes, seconds);
        }
    }

    private static void requestOrPlayLocal(ClientVideoScreen screen, String url) {
        if (hasCoreConnection()) {
            ClientPacketHandler.request(screen, url);
            return;
        }
        client.player.sendMessage(Text.literal("未连接到 VideoPlayer Core").formatted(Formatting.RED), false);
    }

    public static boolean hasCoreConnection() {
        return connected || client.isInSingleplayer();
    }

    public static void saveConfig() {
        try {
            Files.writeString(configPath, gson.toJson(config));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void loadConfig() {
        try {
            config = gson.fromJson(Files.readString(configPath), Config.class);
        } catch (Exception e) {
            config = new Config();
            try {
                saveConfig();
            } catch (Exception e1) {
                e1.addSuppressed(e);
                throw new RuntimeException(e);
            }
        }
    }
}
