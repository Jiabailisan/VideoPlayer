package com.github.squi2rel.vp.provider.bilibili;

import com.github.squi2rel.vp.provider.IProviderSource;
import com.github.squi2rel.vp.provider.MediaOption;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.video.ClientClockListener;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BiliBiliVideoProvider extends BiliBiliProvider {
    public static final String FETCH_URL = "https://api.bilibili.com/x/web-interface/view?bvid=%s";
    public static final String PLAY_URL = "https://api.bilibili.com/x/player/playurl?bvid=%s&cid=%s&qn=%d&fnval=4048&fourk=1";
    public static final int HIGHEST_QUALITY = 127;
    public static final int HDR_QUALITY = 125;
    private static final int[] PROBE_QUALITIES = {127, 126, 120, 116, 112, 80, 74, 64, 32, 16, 6};
    public static final Pattern REGEX = Pattern.compile("(?<=^|/)(BV[0-9A-Za-z]{10})/?(?:\\?[^#]*?p=(\\d+))?");
    private static final Cache<String, VideoCache> CACHE = CacheBuilder.newBuilder().expireAfterWrite(10, TimeUnit.MINUTES).maximumSize(1024).build();

    @Override
    public @Nullable CompletableFuture<VideoInfo> from(String str, IProviderSource source) {
        return from(str, source, "");
    }

    @Override
    public @Nullable CompletableFuture<VideoInfo> from(String str, IProviderSource source, String optionId) {
        Matcher matcher = REGEX.matcher(str);
        if (!matcher.find()) return null;
        String bvid = matcher.group(1);
        Integer p = matcher.group(2) != null ? Integer.valueOf(matcher.group(2)) : null;
        int requestedQuality = parseQuality(optionId);
        String qualityKey = requestedQuality > 0 ? "#qn=" + requestedQuality : "#auto";
        String key = bvid + "?p=" + p + qualityKey + (hasAuthCookie() ? "#auth" : "");
        VideoCache cache = CACHE.getIfPresent(key);
        if (cache != null && System.currentTimeMillis() < cache.expireTime) {
            return CompletableFuture.completedFuture(new VideoInfo(source.name(), cache.title, cache.url, "", cache.expireTime, true, cache.params));
        }
        return CompletableFuture.supplyAsync(() -> {
            try (HttpClient client = HttpClient.newHttpClient()) {
                HttpResponse<String> response = client.send(makeRequest(String.format(FETCH_URL, bvid)), HttpResponse.BodyHandlers.ofString());
                JsonObject root = requireData(response.body());
                String cid;
                if (p == null) {
                    cid = root.get("cid").getAsString();
                } else {
                    cid = root.getAsJsonArray("pages").get(p - 1).getAsJsonObject().get("cid").getAsString();
                }
                return new VideoMeta(root.get("title").getAsString(), cid);
            } catch (Exception e) {
                source.reply(e.toString());
                return null;
            }
        }).thenApply(meta -> {
            if (meta == null) return null;
            try (HttpClient client = HttpClient.newHttpClient()) {
                int initialQuality = requestedQuality > 0 ? requestedQuality : bestAvailableQuality(client, bvid, meta.cid());
                JsonObject data = fetchPlayableData(client, bvid, meta.cid(), initialQuality);
                if (requestedQuality > 0 && data.get("quality").getAsInt() != requestedQuality) {
                    throw new IllegalStateException("Bilibili 当前登录态不可播放 " + qualityName(requestedQuality));
                }
                if (requestedQuality <= 0 && data.get("quality").getAsInt() == HDR_QUALITY) {
                    for (int quality : fallbackQualities(data)) {
                        data = fetchPlayableData(client, bvid, meta.cid(), quality);
                        if (data.get("quality").getAsInt() != HDR_QUALITY) break;
                    }
                }
                Playable playable = selectPlayable(data, initialQuality);
                long expire = System.currentTimeMillis() + 1000 * 60 * 60 * 2;
                CACHE.put(key, new VideoCache(meta.title(), playable.url, playable.params, expire));
                return new VideoInfo(source.name(), meta.title(), playable.url, "", expire, true, playable.params);
            } catch (Exception e) {
                source.reply(e.toString());
                return null;
            }
        });
    }

    @Override
    public @Nullable CompletableFuture<List<MediaOption>> options(String str, IProviderSource source) {
        Matcher matcher = REGEX.matcher(str);
        if (!matcher.find()) return null;
        String bvid = matcher.group(1);
        Integer p = matcher.group(2) != null ? Integer.valueOf(matcher.group(2)) : null;
        return CompletableFuture.supplyAsync(() -> {
            try (HttpClient client = HttpClient.newHttpClient()) {
                HttpResponse<String> response = client.send(makeRequest(String.format(FETCH_URL, bvid)), HttpResponse.BodyHandlers.ofString());
                JsonObject root = requireData(response.body());
                String cid = p == null ? root.get("cid").getAsString() : root.getAsJsonArray("pages").get(p - 1).getAsJsonObject().get("cid").getAsString();
                List<Integer> qualities = discoverQualities(client, bvid, cid);
                List<MediaOption> options = new ArrayList<>();
                for (int quality : qualities) {
                    options.add(new MediaOption(Integer.toString(quality), qualityName(quality), "Bilibili 客户端本地可用"));
                }
                return options;
            } catch (Exception e) {
                source.reply(e.toString());
                return List.of();
            }
        });
    }

    private static int parseQuality(String optionId) {
        try {
            return optionId == null || optionId.isBlank() ? -1 : Integer.parseInt(optionId);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String qualityName(int quality) {
        return switch (quality) {
            case 127 -> "8K 超高清";
            case 126 -> "杜比视界";
            case 125 -> "HDR 真彩";
            case 120 -> "4K 超清";
            case 116 -> "1080P 60帧";
            case 112 -> "1080P 高码率";
            case 80 -> "1080P 高清";
            case 74 -> "720P 60帧";
            case 64 -> "720P 高清";
            case 32 -> "480P 清晰";
            case 16 -> "360P 流畅";
            case 6 -> "240P 极速";
            default -> "Bilibili qn=" + quality;
        };
    }

    private static JsonObject fetchPlayableData(HttpClient client, String bvid, String cid, int quality) throws Exception {
        HttpResponse<String> response = client.send(makeRequest(String.format(PLAY_URL, bvid, cid, quality)), HttpResponse.BodyHandlers.ofString());
        return requireData(response.body());
    }

    private static int bestAvailableQuality(HttpClient client, String bvid, String cid) throws Exception {
        List<Integer> qualities = discoverQualities(client, bvid, cid);
        return qualities.isEmpty() ? 16 : qualities.getFirst();
    }

    private static List<Integer> discoverQualities(HttpClient client, String bvid, String cid) throws Exception {
        Set<Integer> qualities = new LinkedHashSet<>();
        for (int requested : PROBE_QUALITIES) {
            try {
                JsonObject data = fetchPlayableData(client, bvid, cid, requested);
                int actual = data.get("quality").getAsInt();
                if (actual == requested && hasPlayableUrl(data)) qualities.add(requested);
            } catch (Exception ignored) {
            }
        }
        addExpectedLowerQualities(qualities);
        ArrayList<Integer> out = new ArrayList<>(qualities);
        out.removeIf(q -> q == HDR_QUALITY);
        out.sort(Comparator.reverseOrder());
        return out;
    }

    private static void addExpectedLowerQualities(Set<Integer> qualities) {
        int max = qualities.stream().mapToInt(Integer::intValue).max().orElse(0);
        if (max >= 64) {
            qualities.add(64);
            qualities.add(32);
            qualities.add(16);
        } else if (max >= 32) {
            qualities.add(32);
            qualities.add(16);
        }
        if (max >= 80) qualities.add(80);
        if (max >= 112) qualities.add(112);
        if (max >= 116) qualities.add(116);
    }

    private static Playable selectPlayable(JsonObject data, int requestedQuality) {
        JsonObject video = selectDashVideo(data, requestedQuality);
        JsonObject audio = selectDashAudio(data);
        if (video != null) {
            String url = urlOf(video);
            List<String> params = new ArrayList<>(List.of(VLC_PARAMS));
            if (audio != null) params.add(":input-slave=" + urlOf(audio));
            params.add(ClientClockListener.PARAM);
            return new Playable(url, params.toArray(String[]::new));
        }
        if (hasDurl(data)) {
            return new Playable(data.getAsJsonArray("durl").get(0).getAsJsonObject().get("url").getAsString(), VLC_PARAMS);
        }
        throw new IllegalStateException("Bilibili 未返回可播放的 DASH 或 durl");
    }

    private static JsonObject selectDashVideo(JsonObject data, int requestedQuality) {
        if (!data.has("dash") || !data.get("dash").isJsonObject()) return null;
        JsonArray videos = data.getAsJsonObject("dash").getAsJsonArray("video");
        if (videos == null || videos.isEmpty()) return null;
        List<JsonObject> candidates = new ArrayList<>();
        for (var element : videos) {
            JsonObject video = element.getAsJsonObject();
            if (video.get("id").getAsInt() == requestedQuality && !urlOf(video).isBlank()) candidates.add(video);
        }
        if (candidates.isEmpty()) {
            for (var element : videos) {
                JsonObject video = element.getAsJsonObject();
                if (!urlOf(video).isBlank()) candidates.add(video);
            }
        }
        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator
                .comparingInt((JsonObject f) -> codecRank(f)).reversed()
                .thenComparing(Comparator.comparingDouble((JsonObject f) -> doubleField(f, "bandwidth")).reversed()));
        return candidates.getFirst();
    }

    private static JsonObject selectDashAudio(JsonObject data) {
        if (!data.has("dash") || !data.get("dash").isJsonObject()) return null;
        JsonArray audios = data.getAsJsonObject("dash").getAsJsonArray("audio");
        if (audios == null || audios.isEmpty()) return null;
        List<JsonObject> candidates = new ArrayList<>();
        for (var element : audios) {
            JsonObject audio = element.getAsJsonObject();
            if (!urlOf(audio).isBlank()) candidates.add(audio);
        }
        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator
                .comparingInt((JsonObject f) -> audioCodecRank(f)).reversed()
                .thenComparing(Comparator.comparingDouble((JsonObject f) -> doubleField(f, "bandwidth")).reversed()));
        return candidates.getFirst();
    }

    private static int audioCodecRank(JsonObject format) {
        String codecs = stringField(format, "codecs");
        if (codecs.startsWith("mp4a")) return 4;
        if (codecs.startsWith("aac")) return 3;
        if (codecs.startsWith("ec-3") || codecs.startsWith("ac-3")) return 2;
        if (codecs.startsWith("flac")) return 1;
        return 0;
    }

    private static int codecRank(JsonObject format) {
        String codecs = stringField(format, "codecs");
        if (codecs.startsWith("avc")) return 3;
        if (codecs.startsWith("hev") || codecs.startsWith("hvc")) return 2;
        if (codecs.startsWith("av01")) return 1;
        return 0;
    }

    private static String urlOf(JsonObject format) {
        List<String> urls = urlsOf(format);
        for (String url : urls) {
            if (!isProblematicCdn(url)) return url;
        }
        return urls.isEmpty() ? "" : urls.getFirst();
    }

    private static List<String> urlsOf(JsonObject format) {
        List<String> urls = new ArrayList<>();
        String url = stringField(format, "baseUrl");
        if (!url.isBlank()) urls.add(url);
        url = stringField(format, "base_url");
        if (!url.isBlank()) urls.add(url);
        url = stringField(format, "url");
        if (!url.isBlank()) urls.add(url);
        addUrls(format, "backupUrl", urls);
        addUrls(format, "backup_url", urls);
        return urls.stream().distinct().toList();
    }

    private static void addUrls(JsonObject format, String key, List<String> urls) {
        var element = format.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonArray()) return;
        for (var url : element.getAsJsonArray()) {
            if (!url.isJsonNull() && !url.getAsString().isBlank()) urls.add(url.getAsString());
        }
    }

    private static boolean isProblematicCdn(String url) {
        return url.contains(".mcdn.bilivideo.cn") || url.contains("mcdn.bilivideo.cn");
    }

    private static boolean hasPlayableUrl(JsonObject data) {
        return selectDashVideo(data, data.get("quality").getAsInt()) != null || hasDurl(data);
    }

    private static boolean hasDurl(JsonObject data) {
        return data.has("durl") && data.get("durl").isJsonArray()
                && data.getAsJsonArray("durl").size() > 0
                && !stringField(data.getAsJsonArray("durl").get(0).getAsJsonObject(), "url").isBlank();
    }

    private static JsonObject requireData(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has("data") || root.get("data").isJsonNull()) {
            String message = root.has("message") ? root.get("message").getAsString() : root.toString();
            int code = root.has("code") ? root.get("code").getAsInt() : 0;
            throw new IllegalStateException("Bilibili API code=" + code + " message=" + message);
        }
        return root.getAsJsonObject("data");
    }

    private static List<Integer> fallbackQualities(JsonObject data) {
        List<Integer> qualities = new ArrayList<>();
        if (data.has("accept_quality")) {
            data.getAsJsonArray("accept_quality").forEach(element -> {
                int quality = element.getAsInt();
                if (quality != HDR_QUALITY) qualities.add(quality);
            });
        }
        qualities.sort(Comparator.reverseOrder());
        return qualities;
    }

    private static String stringField(JsonObject object, String key) {
        var element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static double doubleField(JsonObject object, String key) {
        var element = object.get(key);
        return element == null || element.isJsonNull() ? 0 : element.getAsDouble();
    }

    private record Playable(String url, String[] params) {}

    private record VideoCache(String title, String url, String[] params, long expireTime) {}
}
