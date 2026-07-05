package com.github.squi2rel.vp.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.github.squi2rel.vp.DataHolder;
import com.github.squi2rel.vp.video.ClientClockListener;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class YouTubeProvider implements IVideoProvider {
    private static final Pattern REGEX = Pattern.compile("(?i)(?:https?://)?(?:www\\.|m\\.)?(?:youtube\\.com|youtu\\.be)/.+");
    private static final Duration TIMEOUT = Duration.ofSeconds(75);
    private static final String RESTART_SEEK_PARAM = ":videoplayer-restart-seek";

    @Override
    public @Nullable CompletableFuture<VideoInfo> from(String str, IProviderSource source) {
        return from(str, source, "");
    }

    @Override
    public @Nullable CompletableFuture<VideoInfo> from(String str, IProviderSource source, String optionId) {
        if (!REGEX.matcher(str).matches()) return null;
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject root = runYtDlpInfoWithFallback(str);
                Playable playable = findPlayable(root, optionId, hasYoutubeAuth());
                if (playable == null || playable.url.isBlank()) {
                    source.reply("YouTube 未找到可播放的音视频流。直播流会优先尝试 HLS/m3u8；若仍失败，可能需要更新 yt-dlp 或提供可用 Cookie/PO Token。");
                    return null;
                }
                String title = root.has("title") ? root.get("title").getAsString() : "YouTube";
                boolean live = isLive(root, playable.format);
                return new VideoInfo(source.name(), title, playable.url, "", System.currentTimeMillis() + 1000L * 60 * 30, !live, playable.params);
            } catch (Exception e) {
                source.reply("YouTube 解析失败: " + explainError(e.getMessage()));
                return null;
            }
        });
    }

    @Override
    public @Nullable CompletableFuture<List<MediaOption>> options(String str, IProviderSource source) {
        if (!REGEX.matcher(str).matches()) return null;
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject root = runYtDlpInfoWithFallback(str);
                JsonArray formats = root.getAsJsonArray("formats");
                if (formats == null) return List.of(new MediaOption("best", "默认 best", "YouTube yt-dlp 默认选择"));
                List<JsonObject> playable = playableVideoFormats(formats, !hasYoutubeAuth());
                List<MediaOption> options = new ArrayList<>();
                options.add(new MediaOption("best", "默认 best", "YouTube yt-dlp 默认选择"));
                for (JsonObject format : playable) {
                    String id = stringField(format, "format_id");
                    if (id.isBlank() || "best".equals(id)) continue;
                    String protocol = stringField(format, "protocol");
                    String description = isDirectSingleStream(format)
                            ? (protocol.contains("m3u8") ? "YouTube HLS 音视频流" : "YouTube 音视频单流")
                            : "YouTube DASH 视频流 + 最佳音频";
                    options.add(new MediaOption(id, label(format), description));
                    if (options.size() >= 24) break;
                }
                return options;
            } catch (Exception e) {
                source.reply("YouTube 画质查询失败: " + explainError(e.getMessage()));
                return List.of();
            }
        });
    }

    private JsonObject runYtDlpInfoWithFallback(String url) throws Exception {
        Exception authError = null;
        JsonObject root = null;
        if (hasYoutubeAuth()) {
            try {
                root = runYtDlpInfo(url, true);
            } catch (Exception e) {
                authError = e;
            }
        } else {
            root = runYtDlpInfo(url, true);
        }
        JsonArray formats = root == null ? null : root.getAsJsonArray("formats");
        if (root == null || formats == null || playableVideoFormats(formats, false).isEmpty()) {
            try {
                root = runYtDlpInfo(url, false);
            } catch (Exception fallbackError) {
                if (authError != null) throw authError;
                throw fallbackError;
            }
        }
        return root;
    }

    private JsonObject runYtDlpInfo(String url, boolean allowAuth) throws Exception {
        YtDlpManager.getInstance().prepare(false);
        List<String> command = baseCommand();
        command.add("--dump-single-json");
        command.add("--no-playlist");
        command.add("--no-warnings");
        command.add("--no-cache-dir");
        command.addAll(YtDlpManager.getInstance().jsRuntimeArgs());
        boolean usingAuth = false;
        if (allowAuth) {
            Path cookiePath = YtDlpManager.getInstance().cookiePath();
            if (hasUsableCookieFile(cookiePath)) {
                command.add("--cookies");
                command.add(cookiePath.toAbsolutePath().toString());
                usingAuth = true;
            } else {
                String cookie = DataHolder.effectiveYoutubeCookie();
                if (!cookie.isBlank()) {
                command.add("--add-header");
                command.add("Cookie: " + cookie);
                usingAuth = true;
                }
            }
        }
        if (!usingAuth) {
            command.add("--extractor-args");
            command.add("youtube:player_client=android");
        }
        command.add(url);

        Path outputPath = Files.createTempFile("videoplayer-ytdlp-", ".json");
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(outputPath.toFile())
                    .start();
            boolean finished = process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("yt-dlp 超时");
            }
            String output = Files.readString(outputPath, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IllegalStateException(cleanError(output.isBlank() ? "yt-dlp exit " + process.exitValue() : output.trim()));
            }
            return JsonParser.parseString(output).getAsJsonObject();
        } finally {
            Files.deleteIfExists(outputPath);
        }
    }

    private static List<String> baseCommand() {
        List<String> command = new ArrayList<>();
        command.add(YtDlpManager.getInstance().executableCommand());
        command.add("--ignore-config");
        return command;
    }

    private static String cleanError(String output) {
        return output.replaceAll("(?m)^\\s*null\\s*$", "").trim();
    }

    private static String explainError(String message) {
        if (message == null) return "";
        if (message.contains("Sign in to confirm your age")) {
            return "该视频需要完整 YouTube 登录 Cookie 才能确认年龄。当前 youtube-cookies.txt 已被 yt-dlp 判定未登录或不完整，请从已登录且可观看该视频的浏览器重新导出 Netscape 格式 Cookie。原始错误: " + message;
        }
        return message;
    }

    private static boolean hasUsableCookieFile(Path path) throws Exception {
        if (Files.notExists(path) || Files.size(path) == 0) return false;
        String content = Files.readString(path, StandardCharsets.UTF_8).stripLeading();
        if (content.isEmpty()) return false;
        if (content.startsWith("{") || content.startsWith("[")) {
            return false;
        }
        return true;
    }

    private static boolean hasYoutubeAuth() {
        try {
            if (!DataHolder.effectiveYoutubeCookie().isBlank()) return true;
            return hasUsableCookieFile(YtDlpManager.getInstance().cookiePath());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isDirectSingleStream(JsonObject format) {
        String url = stringField(format, "url");
        String acodec = stringField(format, "acodec");
        String vcodec = stringField(format, "vcodec");
        if (isHls(format)) return !url.isBlank() && !"none".equals(vcodec);
        if (!isDirectStream(format)) return false;
        if (url.isBlank() || "none".equals(acodec) || "none".equals(vcodec)) return false;
        return true;
    }

    private static boolean isDirectVideoStream(JsonObject format) {
        String url = stringField(format, "url");
        String vcodec = stringField(format, "vcodec");
        if (!isDirectStream(format)) return false;
        return !url.isBlank() && (!"none".equals(vcodec) || isHls(format) && intField(format, "height") > 0);
    }

    private static boolean isDirectAudioStream(JsonObject format) {
        String url = stringField(format, "url");
        String acodec = stringField(format, "acodec");
        String vcodec = stringField(format, "vcodec");
        if (!isDirectStream(format) || isHls(format)) return false;
        return !url.isBlank() && !"none".equals(acodec) && "none".equals(vcodec);
    }

    private static boolean isDirectStream(JsonObject format) {
        String ext = stringField(format, "ext");
        String protocol = stringField(format, "protocol").toLowerCase(Locale.ROOT);
        if ("mhtml".equalsIgnoreCase(ext)) return false;
        return protocol.isBlank() || protocol.startsWith("http") || protocol.equals("https") || isHls(format);
    }

    private static boolean isHls(JsonObject format) {
        String protocol = stringField(format, "protocol").toLowerCase(Locale.ROOT);
        String url = stringField(format, "url").toLowerCase(Locale.ROOT);
        return protocol.contains("m3u8") || url.contains(".m3u8");
    }

    private static Playable findPlayable(JsonObject root, String formatId, boolean authenticated) {
        String rootUrl = stringField(root, "url");
        JsonArray formats = root.getAsJsonArray("formats");
        if (formats == null) {
            return rootUrl.isBlank() ? null : new Playable(rootUrl, vlcParams(root, root), root);
        }
        JsonObject audio = bestAudioFormat(formats);
        JsonObject video;
        if (formatId == null || formatId.isBlank() || "best".equals(formatId)) {
            List<JsonObject> playable = playableVideoFormats(formats, !authenticated);
            video = playable.isEmpty() ? null : playable.getFirst();
        } else {
            video = null;
            for (JsonElement element : formats) {
                JsonObject format = element.getAsJsonObject();
                if (formatId.equals(stringField(format, "format_id")) && isDirectVideoStream(format)) {
                    video = format;
                    break;
                }
            }
        }
        if (video == null) return null;
        if (!authenticated && !isDirectSingleStream(video)) return null;
        List<String> params = new ArrayList<>(List.of(vlcParams(root, video)));
        if (needsRestartSeek(video)) params.add(RESTART_SEEK_PARAM);
        if (!isDirectSingleStream(video) && !isHls(video) && audio != null) {
            params.add(":input-slave=" + stringField(audio, "url"));
            params.add(ClientClockListener.PARAM);
        }
        return new Playable(stringField(video, "url"), params.toArray(String[]::new), video);
    }

    private static List<JsonObject> playableVideoFormats(JsonArray formats, boolean singleStreamOnly) {
        List<JsonObject> playable = new ArrayList<>();
        for (JsonElement element : formats) {
            JsonObject format = element.getAsJsonObject();
            if (!isDirectVideoStream(format)) continue;
            if (singleStreamOnly && !isDirectSingleStream(format)) continue;
            playable.add(format);
        }
        playable.sort(Comparator
                .comparingInt((JsonObject f) -> intField(f, "height")).reversed()
                .thenComparing(Comparator.comparingInt((JsonObject f) -> codecRank(f)).reversed())
                .thenComparing(Comparator.comparingDouble((JsonObject f) -> doubleField(f, "fps")).reversed())
                .thenComparing(Comparator.comparingInt((JsonObject f) -> isDirectSingleStream(f) ? 1 : 0).reversed())
                .thenComparing(Comparator.comparingDouble((JsonObject f) -> doubleField(f, "tbr")).reversed()));
        return playable;
    }

    private static int codecRank(JsonObject format) {
        String ext = stringField(format, "ext").toLowerCase(Locale.ROOT);
        String vcodec = stringField(format, "vcodec").toLowerCase(Locale.ROOT);
        if ("mp4".equals(ext) && (vcodec.startsWith("avc") || vcodec.startsWith("h264"))) return 5;
        if ("mp4".equals(ext) && (vcodec.startsWith("hev") || vcodec.startsWith("hvc"))) return 4;
        if ("mp4".equals(ext)) return 3;
        if (vcodec.startsWith("vp9") || vcodec.startsWith("vp09")) return 2;
        if (vcodec.startsWith("av01")) return 1;
        return 0;
    }

    private static boolean needsRestartSeek(JsonObject format) {
        if (isHls(format)) return false;
        String ext = stringField(format, "ext").toLowerCase(Locale.ROOT);
        String vcodec = stringField(format, "vcodec").toLowerCase(Locale.ROOT);
        return "webm".equals(ext) || vcodec.startsWith("vp9") || vcodec.startsWith("vp09") || vcodec.startsWith("av01");
    }

    private static boolean isLive(JsonObject root, JsonObject format) {
        if (boolField(root, "is_live") || boolField(format, "is_live")) return true;
        String liveStatus = stringField(root, "live_status").toLowerCase(Locale.ROOT);
        return liveStatus.contains("live") || isHls(format) && doubleField(root, "duration") <= 0;
    }

    private static JsonObject bestAudioFormat(JsonArray formats) {
        List<JsonObject> audios = new ArrayList<>();
        for (JsonElement element : formats) {
            JsonObject format = element.getAsJsonObject();
            if (isDirectAudioStream(format)) audios.add(format);
        }
        audios.sort(Comparator.comparingDouble((JsonObject f) -> doubleField(f, "abr")).reversed()
                .thenComparing(Comparator.comparingDouble((JsonObject f) -> doubleField(f, "tbr")).reversed()));
        return audios.isEmpty() ? null : audios.getFirst();
    }

    private static String label(JsonObject format) {
        int height = intField(format, "height");
        double fps = doubleField(format, "fps");
        String ext = stringField(format, "ext");
        String note = stringField(format, "format_note");
        StringBuilder label = new StringBuilder();
        label.append(height > 0 ? height + "p" : stringField(format, "format_id"));
        if (fps > 31) label.append(" ").append((int) fps).append("fps");
        if (!ext.isBlank()) label.append(" ").append(ext);
        if (!note.isBlank() && !note.equalsIgnoreCase(label.toString())) label.append(" ").append(note);
        return label.toString();
    }

    private static String[] vlcParams(JsonObject root, JsonObject format) {
        List<String> params = new ArrayList<>();
        JsonObject rootHeaders = root.has("http_headers") && root.get("http_headers").isJsonObject()
                ? root.getAsJsonObject("http_headers")
                : null;
        JsonObject formatHeaders = format.has("http_headers") && format.get("http_headers").isJsonObject()
                ? format.getAsJsonObject("http_headers")
                : null;
        String userAgent = headerField(formatHeaders, rootHeaders, "User-Agent");
        String referer = headerField(formatHeaders, rootHeaders, "Referer");
        if (userAgent.isBlank()) {
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36";
        }
        if (referer.isBlank()) {
            referer = "https://www.youtube.com/";
        }
        params.add(":http-user-agent=" + userAgent);
        params.add(":http-referrer=" + referer);
        params.add(":http-origin=https://www.youtube.com");
        params.add(":http-reconnect");
        params.add(":network-caching=3000");
        params.add(":file-caching=3000");
        params.add(":live-caching=3000");
        params.add(":drop-late-frames");
        params.add(":skip-frames");
        return params.toArray(String[]::new);
    }

    private static String headerField(JsonObject first, JsonObject fallback, String key) {
        String value = first == null ? "" : stringField(first, key);
        if (!value.isBlank()) return value;
        return fallback == null ? "" : stringField(fallback, key);
    }

    private static String stringField(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static int intField(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? 0 : element.getAsInt();
    }

    private static double doubleField(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? 0 : element.getAsDouble();
    }

    private static boolean boolField(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && !element.isJsonNull() && element.getAsBoolean();
    }

    private record Playable(String url, String[] params, JsonObject format) {}
}
