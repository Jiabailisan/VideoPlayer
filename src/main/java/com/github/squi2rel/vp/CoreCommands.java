package com.github.squi2rel.vp;

import com.github.squi2rel.vp.network.ServerPacketHandler;
import com.github.squi2rel.vp.provider.PlayerProviderSource;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.provider.VideoProviders;
import com.github.squi2rel.vp.provider.YtDlpManager;
import com.github.squi2rel.vp.provider.bilibili.BiliBiliAuthManager;
import com.github.squi2rel.vp.provider.bilibili.BiliBiliProvider;
import com.github.squi2rel.vp.video.VideoArea;
import com.github.squi2rel.vp.video.VideoScreen;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.joml.Vector3f;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class CoreCommands {
    private CoreCommands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("vlc-core")
                .executes(ctx -> usage(ctx.getSource()))
                .then(literal("save")
                        .requires(CoreCommands::admin)
                        .executes(ctx -> save(ctx.getSource())))
                .then(literal("reload")
                        .requires(CoreCommands::admin)
                        .executes(ctx -> reload(ctx.getSource())))
                .then(literal("debug")
                        .requires(CoreCommands::admin)
                        .executes(ctx -> debug(ctx.getSource(), null))
                        .then(argument("enabled", BoolArgumentType.bool())
                                .executes(ctx -> debug(ctx.getSource(), BoolArgumentType.getBool(ctx, "enabled")))))
                .then(literal("menu")
                        .executes(ctx -> menu(ctx.getSource().getPlayerOrThrow())))
                .then(literal("play")
                        .then(argument("area", StringArgumentType.string())
                                .then(argument("screen", StringArgumentType.string())
                                        .then(argument("url", StringArgumentType.greedyString())
                                                .executes(ctx -> play(ctx.getSource().getPlayerOrThrow(),
                                                        StringArgumentType.getString(ctx, "area"),
                                                        StringArgumentType.getString(ctx, "screen"),
                                                        StringArgumentType.getString(ctx, "url")))))))
                .then(literal("createArea")
                        .requires(CoreCommands::admin)
                        .then(argument("x1", FloatArgumentType.floatArg()).suggests(targetCoord('x'))
                                .then(argument("y1", FloatArgumentType.floatArg()).suggests(targetCoord('y'))
                                        .then(argument("z1", FloatArgumentType.floatArg()).suggests(targetCoord('z'))
                                                .then(argument("x2", FloatArgumentType.floatArg()).suggests(targetCoord('x'))
                                                        .then(argument("y2", FloatArgumentType.floatArg()).suggests(targetCoord('y'))
                                                                .then(argument("z2", FloatArgumentType.floatArg()).suggests(targetCoord('z'))
                                                                        .then(argument("name", StringArgumentType.string())
                                                                                .executes(ctx -> createArea(ctx.getSource().getPlayerOrThrow(),
                                                                                        FloatArgumentType.getFloat(ctx, "x1"),
                                                                                        FloatArgumentType.getFloat(ctx, "y1"),
                                                                                        FloatArgumentType.getFloat(ctx, "z1"),
                                                                                        FloatArgumentType.getFloat(ctx, "x2"),
                                                                                        FloatArgumentType.getFloat(ctx, "y2"),
                                                                                        FloatArgumentType.getFloat(ctx, "z2"),
                                                                                        StringArgumentType.getString(ctx, "name")))))))))))
                .then(literal("removeArea")
                        .requires(CoreCommands::admin)
                        .then(argument("name", StringArgumentType.string())
                                .executes(ctx -> removeArea(ctx.getSource().getPlayerOrThrow(), StringArgumentType.getString(ctx, "name")))))
                .then(literal("createScreen")
                        .requires(CoreCommands::admin)
                        .then(argument("area", StringArgumentType.string())
                                .then(argument("name", StringArgumentType.string())
                                        .then(argument("x1", FloatArgumentType.floatArg()).suggests(targetCoord('x'))
                                                .then(argument("y1", FloatArgumentType.floatArg()).suggests(targetCoord('y'))
                                                        .then(argument("z1", FloatArgumentType.floatArg()).suggests(targetCoord('z'))
                                                                .then(argument("x2", FloatArgumentType.floatArg()).suggests(targetCoord('x'))
                                                                        .then(argument("y2", FloatArgumentType.floatArg()).suggests(targetCoord('y'))
                                                                                .then(argument("z2", FloatArgumentType.floatArg()).suggests(targetCoord('z'))
                                                                                        .then(argument("x3", FloatArgumentType.floatArg()).suggests(targetCoord('x'))
                                                                                                .then(argument("y3", FloatArgumentType.floatArg()).suggests(targetCoord('y'))
                                                                                                        .then(argument("z3", FloatArgumentType.floatArg()).suggests(targetCoord('z'))
                                                                                                                .then(argument("x4", FloatArgumentType.floatArg()).suggests(targetCoord('x'))
                                                                                                                        .then(argument("y4", FloatArgumentType.floatArg()).suggests(targetCoord('y'))
                                                                                                                                .then(argument("z4", FloatArgumentType.floatArg()).suggests(targetCoord('z'))
                                                                                                                                        .executes(ctx -> createScreen(ctx.getSource().getPlayerOrThrow(),
                                                                                                                                                StringArgumentType.getString(ctx, "area"),
                                                                                                                                                StringArgumentType.getString(ctx, "name"),
                                                                                                                                                v(ctx, "x1", "y1", "z1"),
                                                                                                                                                v(ctx, "x2", "y2", "z2"),
                                                                                                                                                v(ctx, "x3", "y3", "z3"),
                                                                                                                                                v(ctx, "x4", "y4", "z4"),
                                                                                                                                                ""))
                                                                                                                                        .then(argument("source", StringArgumentType.string())
                                                                                                                                                .executes(ctx -> createScreen(ctx.getSource().getPlayerOrThrow(),
                                                                                                                                                        StringArgumentType.getString(ctx, "area"),
                                                                                                                                                        StringArgumentType.getString(ctx, "name"),
                                                                                                                                                        v(ctx, "x1", "y1", "z1"),
                                                                                                                                                        v(ctx, "x2", "y2", "z2"),
                                                                                                                                                        v(ctx, "x3", "y3", "z3"),
                                                                                                                                                        v(ctx, "x4", "y4", "z4"),
                                                                                        StringArgumentType.getString(ctx, "source")))))))))))))))))))
                .then(literal("createScreenHelper")
                        .requires(CoreCommands::admin)
                        .then(argument("area", StringArgumentType.string())
                                .then(argument("x", FloatArgumentType.floatArg()).suggests(targetCoord('x'))
                                        .then(argument("y", FloatArgumentType.floatArg()).suggests(targetCoord('y'))
                                                .then(argument("z", FloatArgumentType.floatArg()).suggests(targetCoord('z'))
                                                        .then(argument("length", IntegerArgumentType.integer(1, 256))
                                                                .then(argument("height", IntegerArgumentType.integer(1, 256))
                                                                        .then(argument("name", StringArgumentType.string())
                                                                                .executes(ctx -> createScreenHelper(ctx.getSource().getPlayerOrThrow(),
                                                                                        StringArgumentType.getString(ctx, "area"),
                                                                                        v(ctx, "x", "y", "z"),
                                                                                        IntegerArgumentType.getInteger(ctx, "length"),
                                                                                        IntegerArgumentType.getInteger(ctx, "height"),
                                                                                        StringArgumentType.getString(ctx, "name"),
                                                                                        ""))
                                                                                .then(argument("source", StringArgumentType.string())
                                                                                        .executes(ctx -> createScreenHelper(ctx.getSource().getPlayerOrThrow(),
                                                                                                StringArgumentType.getString(ctx, "area"),
                                                                                                v(ctx, "x", "y", "z"),
                                                                                                IntegerArgumentType.getInteger(ctx, "length"),
                                                                                                IntegerArgumentType.getInteger(ctx, "height"),
                                                                                                StringArgumentType.getString(ctx, "name"),
                                                                                                StringArgumentType.getString(ctx, "source"))))))))))))
                .then(literal("removeScreen")
                        .requires(CoreCommands::admin)
                        .then(argument("area", StringArgumentType.string())
                                .then(argument("screen", StringArgumentType.string())
                                        .executes(ctx -> removeScreen(ctx.getSource().getPlayerOrThrow(),
                                                StringArgumentType.getString(ctx, "area"),
                                                StringArgumentType.getString(ctx, "screen"))))))
                .then(literal("skip")
                        .then(argument("area", StringArgumentType.string())
                                .then(argument("screen", StringArgumentType.string())
                                        .executes(ctx -> skip(ctx.getSource().getPlayerOrThrow(),
                                                StringArgumentType.getString(ctx, "area"),
                                                StringArgumentType.getString(ctx, "screen"),
                                                false))
                                        .then(argument("force", BoolArgumentType.bool())
                                                .requires(CoreCommands::admin)
                                                .executes(ctx -> skip(ctx.getSource().getPlayerOrThrow(),
                                                        StringArgumentType.getString(ctx, "area"),
                                                        StringArgumentType.getString(ctx, "screen"),
                                                        BoolArgumentType.getBool(ctx, "force")))))))
                .then(literal("skipPercent")
                        .requires(CoreCommands::admin)
                        .then(argument("area", StringArgumentType.string())
                                .then(argument("screen", StringArgumentType.string())
                                        .then(argument("percent", FloatArgumentType.floatArg(0, 1.01f))
                                                .executes(ctx -> skipPercent(ctx.getSource().getPlayerOrThrow(),
                                                        StringArgumentType.getString(ctx, "area"),
                                                        StringArgumentType.getString(ctx, "screen"),
                                                        FloatArgumentType.getFloat(ctx, "percent")))))))
                .then(literal("list")
                        .then(argument("area", StringArgumentType.string())
                                .then(argument("screen", StringArgumentType.string())
                                        .executes(ctx -> list(ctx.getSource().getPlayerOrThrow(),
                                                StringArgumentType.getString(ctx, "area"),
                                                StringArgumentType.getString(ctx, "screen"))))))
                .then(literal("sync")
                        .then(argument("area", StringArgumentType.string())
                                .then(argument("screen", StringArgumentType.string())
                                        .executes(ctx -> sync(ctx.getSource().getPlayerOrThrow(),
                                                StringArgumentType.getString(ctx, "area"),
                                                StringArgumentType.getString(ctx, "screen"))))))
                .then(literal("slice")
                        .requires(CoreCommands::admin)
                        .then(argument("area", StringArgumentType.string())
                                .then(argument("screen", StringArgumentType.string())
                                        .then(argument("u1", FloatArgumentType.floatArg())
                                                .then(argument("v1", FloatArgumentType.floatArg())
                                                        .then(argument("u2", FloatArgumentType.floatArg())
                                                                .then(argument("v2", FloatArgumentType.floatArg())
                                                                        .executes(ctx -> slice(ctx.getSource().getPlayerOrThrow(),
                                                                                StringArgumentType.getString(ctx, "area"),
                                                                                StringArgumentType.getString(ctx, "screen"),
                                                                                FloatArgumentType.getFloat(ctx, "u1"),
                                                                                FloatArgumentType.getFloat(ctx, "v1"),
                                                                                FloatArgumentType.getFloat(ctx, "u2"),
                                                                                FloatArgumentType.getFloat(ctx, "v2"))))))))))
                .then(literal("scale")
                        .requires(CoreCommands::admin)
                        .then(argument("area", StringArgumentType.string())
                                .then(argument("screen", StringArgumentType.string())
                                        .then(literal("stretch")
                                                .executes(ctx -> scale(ctx.getSource().getPlayerOrThrow(),
                                                        StringArgumentType.getString(ctx, "area"),
                                                        StringArgumentType.getString(ctx, "screen"),
                                                        true, 1, 1)))
                                        .then(literal("auto")
                                                .executes(ctx -> scale(ctx.getSource().getPlayerOrThrow(),
                                                        StringArgumentType.getString(ctx, "area"),
                                                        StringArgumentType.getString(ctx, "screen"),
                                                        false, 1, 1)))
                                        .then(literal("set")
                                                .then(argument("scaleX", FloatArgumentType.floatArg(0.0625f, 16f))
                                                        .then(argument("scaleY", FloatArgumentType.floatArg(0.0625f, 16f))
                                                                .executes(ctx -> scale(ctx.getSource().getPlayerOrThrow(),
                                                                        StringArgumentType.getString(ctx, "area"),
                                                                        StringArgumentType.getString(ctx, "screen"),
                                                                        false,
                                                                        FloatArgumentType.getFloat(ctx, "scaleX"),
                                                                        FloatArgumentType.getFloat(ctx, "scaleY")))))))))
                .then(literal("auth")
                        .requires(CoreCommands::admin)
                        .then(literal("bilibili")
                                .then(literal("status")
                                        .executes(ctx -> bilibiliStatus(ctx.getSource())))
                                .then(literal("cookie")
                                        .then(argument("cookie", StringArgumentType.greedyString())
                                                .executes(ctx -> bilibiliCookie(ctx.getSource(), StringArgumentType.getString(ctx, "cookie")))))
                                .then(literal("cookiefile")
                                        .then(literal("reload")
                                                .executes(ctx -> bilibiliCookieReload(ctx.getSource())))
                                        .then(literal("clear")
                                                .executes(ctx -> bilibiliCookieFileClear(ctx.getSource())))))
                        .then(literal("youtube")
                                .then(literal("status")
                                        .executes(ctx -> youtubeStatus(ctx.getSource())))
                                .then(literal("cookie")
                                        .then(argument("cookie", StringArgumentType.greedyString())
                                                .executes(ctx -> youtubeCookie(ctx.getSource(), StringArgumentType.getString(ctx, "cookie")))))
                                .then(literal("redownload")
                                        .executes(ctx -> youtubeRedownload(ctx.getSource())))
                                .then(literal("cookiefile")
                                        .then(literal("reload")
                                                .executes(ctx -> youtubeCookieReload(ctx.getSource())))
                                        .then(literal("clear")
                                                .executes(ctx -> youtubeCookieClear(ctx.getSource())))))));
    }

    private static int usage(ServerCommandSource source) {
        if (!admin(source)) {
            source.sendFeedback(() -> Text.literal("/vlc-core menu").formatted(Formatting.YELLOW), false);
            source.sendFeedback(() -> Text.literal("/vlc-core play <area> <screen> <url>").formatted(Formatting.YELLOW), false);
            source.sendFeedback(() -> Text.literal("/vlc-core skip <area> <screen>").formatted(Formatting.YELLOW), false);
            source.sendFeedback(() -> Text.literal("/vlc-core list <area> <screen>").formatted(Formatting.YELLOW), false);
            source.sendFeedback(() -> Text.literal("/vlc-core sync <area> <screen>").formatted(Formatting.YELLOW), false);
            return 1;
        }
        source.sendFeedback(() -> Text.literal("/vlc-core createArea <x1> <y1> <z1> <x2> <y2> <z2> <name>").formatted(Formatting.YELLOW), false);
        source.sendFeedback(() -> Text.literal("/vlc-core createScreen <area> <name> <左上> <左下> <右下> <右上> [source]").formatted(Formatting.YELLOW), false);
        source.sendFeedback(() -> Text.literal("/vlc-core createScreenHelper <area> <左下x y z> <length> <height> <name> [source]").formatted(Formatting.YELLOW), false);
        source.sendFeedback(() -> Text.literal("/vlc-core menu").formatted(Formatting.YELLOW), false);
        source.sendFeedback(() -> Text.literal("/vlc-core play <area> <screen> <url>").formatted(Formatting.YELLOW), false);
        source.sendFeedback(() -> Text.literal("/vlc-core <removeArea|removeScreen|skip|skipPercent|list|sync|slice|scale|debug|save|reload|auth>").formatted(Formatting.YELLOW), false);
        return 1;
    }

    private static boolean admin(ServerCommandSource source) {
        if (source.getPlayer() == null) return true;
        for (String method : List.of("hasPermissionLevel", "hasPermission")) {
            try {
                Object result = ServerCommandSource.class.getMethod(method, int.class).invoke(source, 2);
                if (result instanceof Boolean allowed) return allowed;
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return false;
    }

    private static int save(ServerCommandSource source) {
        DataHolder.save();
        source.sendFeedback(() -> Text.literal("VideoPlayer 配置已保存").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int menu(ServerPlayerEntity player) {
        ServerPacketHandler.sendTo(player, ServerPacketHandler.execute("vlc-client menu"));
        return 1;
    }

    private static int debug(ServerCommandSource source, Boolean enabled) {
        if (enabled != null) {
            DataHolder.config.debug = enabled;
            DataHolder.save();
        }
        source.sendFeedback(() -> Text.literal("VideoPlayer debug: " + (DataHolder.config.debug ? "开启" : "关闭")).formatted(DataHolder.config.debug ? Formatting.GREEN : Formatting.YELLOW), false);
        return 1;
    }

    private static int reload(ServerCommandSource source) {
        MinecraftServer server = source.getServer();
        DataHolder.save();
        DataHolder.unload(server);
        DataHolder.load(server);
        BiliBiliProvider.configureAuthCookie(DataHolder.effectiveBilibiliCookie());
        source.sendFeedback(() -> Text.literal("VideoPlayer 配置已重载").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static SuggestionProvider<ServerCommandSource> targetCoord(char axis) {
        return (context, builder) -> {
            try {
                ServerPlayerEntity player = context.getSource().getPlayer();
                HitResult hit = player.raycast(64, 1.0f, false);
                if (hit instanceof BlockHitResult blockHit) {
                    BlockPos pos = blockHit.getBlockPos();
                    int value = switch (axis) {
                        case 'x' -> pos.getX();
                        case 'y' -> pos.getY();
                        case 'z' -> pos.getZ();
                        default -> 0;
                    };
                    builder.suggest(Integer.toString(value));
                }
            } catch (Exception ignored) {
            }
            return builder.buildFuture();
        };
    }

    private static int bilibiliStatus(ServerCommandSource source) {
        String cookie = DataHolder.effectiveBilibiliCookie();
        BiliBiliProvider.configureAuthCookie(cookie);
        String status = !cookie.isBlank()
                ? "Bilibili 登录凭据已配置"
                : "Bilibili 登录凭据未配置";
        source.sendFeedback(() -> Text.literal(status).formatted(!cookie.isBlank() ? Formatting.GREEN : Formatting.YELLOW), false);
        source.sendFeedback(() -> Text.literal("Bilibili Cookie 配置项: " + (DataHolder.config.providerAuth.bilibiliCookie.isBlank() ? "未设置" : "已设置")).formatted(Formatting.YELLOW), false);
        source.sendFeedback(() -> Text.literal("Bilibili Cookie 文件: " + DataHolder.bilibiliCookiePath().toAbsolutePath()).formatted(Formatting.AQUA), false);
        source.sendFeedback(() -> Text.literal("Cookie 文件状态: " + bilibiliCookieStatus()).formatted(Formatting.YELLOW), false);
        return 1;
    }

    private static int bilibiliCookie(ServerCommandSource source, String cookie) {
        cookie = cookie.trim();
        if (cookie.isEmpty()) {
            source.sendError(Text.literal("Cookie 不能为空"));
            return 0;
        }
        DataHolder.config.providerAuth.bilibiliCookie = DataHolder.normalizeCookie(cookie);
        BiliBiliProvider.configureAuthCookie(DataHolder.config.providerAuth.bilibiliCookie);
        DataHolder.save();
        source.sendFeedback(() -> Text.literal("Bilibili Cookie 已保存。不会发送给客户端。").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int bilibiliCookieReload(ServerCommandSource source) {
        String cookie = DataHolder.effectiveBilibiliCookie();
        BiliBiliProvider.configureAuthCookie(cookie);
        source.sendFeedback(() -> Text.literal(cookie.isBlank() ? "Bilibili Cookie 文件为空或不可用" : "Bilibili Cookie 文件已加载").formatted(cookie.isBlank() ? Formatting.YELLOW : Formatting.GREEN), false);
        source.sendFeedback(() -> Text.literal("Bilibili Cookie 文件: " + DataHolder.bilibiliCookiePath().toAbsolutePath()).formatted(Formatting.AQUA), false);
        return cookie.isBlank() ? 0 : 1;
    }

    private static int bilibiliCookieFileClear(ServerCommandSource source) {
        try {
            Files.writeString(DataHolder.bilibiliCookiePath(), "");
            DataHolder.config.providerAuth.bilibiliCookie = "";
            BiliBiliProvider.configureAuthCookie("");
            DataHolder.save();
            source.sendFeedback(() -> Text.literal("Bilibili Cookie 文件与配置项已清空: " + DataHolder.bilibiliCookiePath().toAbsolutePath()).formatted(Formatting.GREEN), false);
            return 1;
        } catch (Exception e) {
            source.sendError(Text.literal("清空 Bilibili Cookie 文件失败: " + e));
            return 0;
        }
    }

    private static int youtubeStatus(ServerCommandSource source) {
        YtDlpManager manager = YtDlpManager.getInstance();
        boolean hasAuth = youtubeHasAuth();
        source.sendFeedback(() -> Text.literal(hasAuth ? "YouTube 登录凭据已配置" : "YouTube 登录凭据未配置").formatted(hasAuth ? Formatting.GREEN : Formatting.YELLOW), false);
        source.sendFeedback(() -> Text.literal("YouTube Cookie 配置项: " + (DataHolder.effectiveYoutubeCookie().isBlank() ? "未设置" : "已设置")).formatted(Formatting.YELLOW), false);
        source.sendFeedback(() -> Text.literal("YouTube Cookie 文件: " + manager.cookiePath().toAbsolutePath()).formatted(Formatting.AQUA), false);
        source.sendFeedback(() -> Text.literal("Cookie 文件状态: " + cookieStatus()).formatted(Formatting.YELLOW), false);
        source.sendFeedback(() -> Text.literal("yt-dlp: " + manager.executablePath().toAbsolutePath()).formatted(Formatting.AQUA), false);
        return 1;
    }

    private static int youtubeCookie(ServerCommandSource source, String cookie) {
        cookie = cookie.trim();
        if (cookie.isEmpty()) {
            source.sendError(Text.literal("Cookie 不能为空"));
            return 0;
        }
        DataHolder.config.providerAuth.youtubeCookie = DataHolder.normalizeCookie(cookie);
        DataHolder.save();
        source.sendFeedback(() -> Text.literal("YouTube Cookie 已保存到服务端配置。不会发送给客户端。").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int youtubeRedownload(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("开始重新下载 yt-dlp...").formatted(Formatting.YELLOW), false);
        CompletableFuture.runAsync(() -> {
            try {
                YtDlpManager.getInstance().prepare(true);
                source.sendFeedback(() -> Text.literal("yt-dlp 已下载到 " + YtDlpManager.getInstance().executablePath().toAbsolutePath()).formatted(Formatting.GREEN), false);
            } catch (Exception e) {
                source.sendError(Text.literal("yt-dlp 下载失败: " + e));
            }
        }, VideoPlayerMain.scheduler);
        return 1;
    }

    private static int youtubeCookieClear(ServerCommandSource source) {
        try {
            Files.writeString(YtDlpManager.getInstance().cookiePath(), "");
            DataHolder.config.providerAuth.youtubeCookie = "";
            DataHolder.save();
            source.sendFeedback(() -> Text.literal("YouTube Cookie 文件与配置项已清空: " + YtDlpManager.getInstance().cookiePath().toAbsolutePath()).formatted(Formatting.GREEN), false);
            return 1;
        } catch (Exception e) {
            source.sendError(Text.literal("清空 YouTube Cookie 文件失败: " + e));
            return 0;
        }
    }

    private static int youtubeCookieReload(ServerCommandSource source) {
        try {
            YtDlpManager.getInstance().prepare(false);
            boolean hasAuth = youtubeHasAuth();
            source.sendFeedback(() -> Text.literal(hasAuth ? "YouTube Cookie 已加载" : "YouTube Cookie 文件为空或配置项未设置").formatted(hasAuth ? Formatting.GREEN : Formatting.YELLOW), false);
            source.sendFeedback(() -> Text.literal("YouTube Cookie 文件已重新确认: " + YtDlpManager.getInstance().cookiePath().toAbsolutePath()).formatted(Formatting.GREEN), false);
            source.sendFeedback(() -> Text.literal("Cookie 文件状态: " + cookieStatus()).formatted(Formatting.YELLOW), false);
            return hasAuth ? 1 : 0;
        } catch (Exception e) {
            source.sendError(Text.literal("重新加载 YouTube Cookie 文件失败: " + e));
            return 0;
        }
    }

    private static boolean youtubeHasAuth() {
        if (!DataHolder.effectiveYoutubeCookie().isBlank()) return true;
        try {
            return Files.exists(YtDlpManager.getInstance().cookiePath()) && Files.size(YtDlpManager.getInstance().cookiePath()) > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String cookieStatus() {
        try {
            if (Files.notExists(YtDlpManager.getInstance().cookiePath())) return "不存在";
            long size = Files.size(YtDlpManager.getInstance().cookiePath());
            return size == 0 ? "空文件" : size + " bytes";
        } catch (Exception e) {
            return "无法读取: " + e;
        }
    }

    private static String bilibiliCookieStatus() {
        try {
            if (Files.notExists(DataHolder.bilibiliCookiePath())) return "不存在";
            long size = Files.size(DataHolder.bilibiliCookiePath());
            return size == 0 ? "空文件" : size + " bytes";
        } catch (Exception e) {
            return "无法读取: " + e;
        }
    }

    private static int createArea(ServerPlayerEntity player, float x1, float y1, float z1, float x2, float y2, float z2, String name) {
        VideoArea area = VideoArea.from(new Vector3f(x1, y1, z1), new Vector3f(x2, y2, z2), name, dim(player));
        area.initServer();
        DataHolder.lock();
        try {
            DataHolder.areas.computeIfAbsent(area.dim, k -> new HashMap<>()).put(area.name, area);
        } finally {
            DataHolder.unlock();
        }
        DataHolder.save();
        player.sendMessage(Text.literal("已创建观影区 " + name).formatted(Formatting.GREEN));
        return 1;
    }

    private static int removeArea(ServerPlayerEntity player, String name) {
        DataHolder.lock();
        try {
            HashMap<String, VideoArea> map = DataHolder.areas.get(dim(player));
            if (map == null || !map.containsKey(name)) return fail(player, "观影区不存在: " + name);
            VideoArea area = map.remove(name);
            area.remove();
            broadcast(area, ServerPacketHandler.removeArea(area), player);
        } finally {
            DataHolder.unlock();
        }
        DataHolder.save();
        player.sendMessage(Text.literal("已移除观影区 " + name).formatted(Formatting.GREEN));
        return 1;
    }

    private static int createScreen(ServerPlayerEntity player, String areaName, String name, Vector3f p1, Vector3f p2, Vector3f p3, Vector3f p4, String source) {
        VideoArea area = area(player, areaName);
        if (area == null) return fail(player, "观影区不存在: " + areaName);
        source = VideoScreen.normalizeSource(source);
        VideoScreen screen = VideoScreen.fromBlockCorners(area, name, p1, p2, p3, p4, source);
        return createScreen(player, area, screen);
    }

    private static int createScreenHelper(ServerPlayerEntity player, String areaName, Vector3f lowerLeft, int length, int height, String name, String source) {
        VideoArea area = area(player, areaName);
        if (area == null) return fail(player, "观影区不存在: " + areaName);
        Direction right = player.getHorizontalFacing().rotateYClockwise();
        Vector3f horizontal = new Vector3f(right.getOffsetX() * length, 0, right.getOffsetZ() * length);
        Vector3f vertical = new Vector3f(0, height, 0);
        Vector3f p2 = new Vector3f(lowerLeft);
        Vector3f p1 = new Vector3f(lowerLeft).add(vertical);
        Vector3f p3 = new Vector3f(lowerLeft).add(horizontal);
        Vector3f p4 = new Vector3f(p3).add(vertical);
        VideoScreen screen = new VideoScreen(area, name, p1, p2, p3, p4, VideoScreen.normalizeSource(source));
        return createScreen(player, area, screen);
    }

    private static int createScreen(ServerPlayerEntity player, VideoArea area, VideoScreen screen) {
        screen.initServer();
        DataHolder.lock();
        try {
            for (VideoScreen old : area.removeScreens(screen.name)) {
                if (area.hasPlayer()) broadcast(area, ServerPacketHandler.removeScreen(old), player);
            }
            area.addScreen(screen);
            if (area.hasPlayer()) broadcast(area, ServerPacketHandler.createScreen(List.of(screen)), player);
        } finally {
            DataHolder.unlock();
        }
        DataHolder.save();
        player.sendMessage(Text.literal("已创建屏幕 " + screen.name).formatted(Formatting.GREEN));
        return 1;
    }

    private static int removeScreen(ServerPlayerEntity player, String areaName, String screenName) {
        VideoArea area = area(player, areaName);
        if (area == null) return fail(player, "观影区不存在: " + areaName);
        DataHolder.lock();
        try {
            VideoScreen screen = area.removeScreen(screenName);
            if (screen == null) return fail(player, "屏幕不存在: " + screenName);
            if (area.hasPlayer()) broadcast(area, ServerPacketHandler.removeScreen(screen), player);
        } finally {
            DataHolder.unlock();
        }
        DataHolder.save();
        player.sendMessage(Text.literal("已移除屏幕 " + screenName).formatted(Formatting.GREEN));
        return 1;
    }

    private static int play(ServerPlayerEntity player, String areaName, String screenName, String url) {
        VideoScreen screen = screen(player, areaName, screenName);
        if (screen == null) return fail(player, "屏幕不存在");
        CompletableFuture<VideoInfo> video = VideoProviders.from(url, new PlayerProviderSource(player));
        if (video == null) return fail(player, "无法解析视频源");
        video.thenAccept(info -> {
            if (info == null) {
                player.sendMessage(Text.literal("无法解析视频源").formatted(Formatting.RED));
                return;
            }
            screen.addInfo(info);
        });
        return 1;
    }

    private static int skip(ServerPlayerEntity player, String areaName, String screenName, boolean force) {
        VideoScreen screen = screen(player, areaName, screenName);
        if (screen == null) return fail(player, "屏幕不存在");
        if (force) screen.skip();
        else screen.voteSkip(player.getUuid());
        return 1;
    }

    private static int skipPercent(ServerPlayerEntity player, String areaName, String screenName, float percent) {
        VideoScreen screen = screen(player, areaName, screenName);
        if (screen == null) return fail(player, "屏幕不存在");
        screen.setSkipPercent(percent);
        player.sendMessage(Text.literal("投票跳过比例已设置为 " + percent).formatted(Formatting.GREEN));
        return 1;
    }

    private static int list(ServerPlayerEntity player, String areaName, String screenName) {
        VideoScreen screen = screen(player, areaName, screenName);
        if (screen == null) return fail(player, "屏幕不存在");
        String queue = screen.infos.stream()
                .map(info -> "%s 请求玩家: %s".formatted(info.name(), info.playerName()))
                .collect(Collectors.joining("\n"));
        player.sendMessage(Text.literal(queue.isEmpty() ? "队列无视频" : queue).formatted(Formatting.GOLD));
        return 1;
    }

    private static int sync(ServerPlayerEntity player, String areaName, String screenName) {
        VideoScreen screen = screen(player, areaName, screenName);
        if (screen == null || screen.currentPlaying() == null) return fail(player, "屏幕无正在播放视频");
        ServerPacketHandler.sendTo(player, ServerPacketHandler.sync(screen, screen.getProgress()));
        return 1;
    }

    private static int slice(ServerPlayerEntity player, String areaName, String screenName, float u1, float v1, float u2, float v2) {
        VideoScreen screen = screen(player, areaName, screenName);
        if (screen == null) return fail(player, "屏幕不存在");
        screen.u1 = u1;
        screen.v1 = v1;
        screen.u2 = u2;
        screen.v2 = v2;
        broadcast(screen.area, ServerPacketHandler.setUV(screen, u1, v1, u2, v2), player);
        DataHolder.save();
        return 1;
    }

    private static int scale(ServerPlayerEntity player, String areaName, String screenName, boolean fill, float scaleX, float scaleY) {
        VideoScreen screen = screen(player, areaName, screenName);
        if (screen == null) return fail(player, "屏幕不存在");
        screen.fill = fill;
        screen.scaleX = scaleX;
        screen.scaleY = scaleY;
        broadcast(screen.area, ServerPacketHandler.setScale(screen, fill, scaleX, scaleY), player);
        DataHolder.save();
        return 1;
    }

    private static VideoArea area(ServerPlayerEntity player, String name) {
        HashMap<String, VideoArea> map = DataHolder.areas.get(dim(player));
        return map == null ? null : map.get(name);
    }

    private static VideoScreen screen(ServerPlayerEntity player, String areaName, String screenName) {
        VideoArea area = area(player, areaName);
        return area == null ? null : area.getScreen(screenName);
    }

    private static void broadcast(VideoArea area, byte[] data, ServerPlayerEntity player) {
        PlayerManager pm = player.getEntityWorld().getServer().getPlayerManager();
        area.forEachPlayer(uuid -> {
            ServerPlayerEntity target = pm.getPlayer(uuid);
            if (target != null) ServerPacketHandler.sendTo(target, data);
        });
    }

    private static String dim(ServerPlayerEntity player) {
        return player.getEntityWorld().getRegistryKey().getValue().toString();
    }

    private static int fail(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(message).formatted(Formatting.RED));
        return 0;
    }

    private static Vector3f v(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx, String x, String y, String z) {
        return new Vector3f(FloatArgumentType.getFloat(ctx, x), FloatArgumentType.getFloat(ctx, y), FloatArgumentType.getFloat(ctx, z));
    }
}
