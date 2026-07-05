package com.github.squi2rel.vp.provider.bilibili;

import com.github.squi2rel.vp.DataHolder;
import com.github.squi2rel.vp.VideoPlayerMain;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class BiliBiliAuthManager {
    private static final BiliBiliAuthManager INSTANCE = new BiliBiliAuthManager();
    private static final String GENERATE_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate";
    private static final String POLL_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=%s";
    private static final Set<String> STORED_COOKIE_NAMES = Set.of(
            "SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid", "buvid3", "buvid4", "b_nut"
    );

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private ScheduledFuture<?> pollingTask;
    private String qrcodeKey;

    public static BiliBiliAuthManager getInstance() {
        return INSTANCE;
    }

    public synchronized void startQrLogin(ServerCommandSource source) {
        if (pollingTask != null && !pollingTask.isDone()) {
            send(source, "已有 Bilibili 扫码登录正在进行，请先执行 /vlc-core auth bilibili cancel", Formatting.YELLOW);
            return;
        }
        VideoPlayerMain.scheduler.execute(() -> {
            try {
                HttpResponse<String> response = httpClient.send(baseRequest(GENERATE_URL).GET().build(), HttpResponse.BodyHandlers.ofString());
                JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                if (!isOk(root)) throw new IllegalStateException(root.toString());
                JsonObject data = root.getAsJsonObject("data");
                String url = data.get("url").getAsString();
                synchronized (this) {
                    qrcodeKey = data.get("qrcode_key").getAsString();
                }
                Path urlPath = writeLoginUrl(qrcodeKey, url);
                send(source, "Bilibili 扫码登录已创建，有效期约 180 秒。", Formatting.GREEN);
                send(source, "登录 URL 已保存: " + urlPath.toAbsolutePath(), Formatting.AQUA);
                send(source, "二维码内容 URL: " + url, Formatting.GRAY);
                schedulePolling(source);
            } catch (Exception e) {
                send(source, "创建 Bilibili 二维码失败: " + e, Formatting.RED);
            }
        });
    }

    public synchronized void cancel(ServerCommandSource source) {
        if (pollingTask != null) {
            pollingTask.cancel(false);
            pollingTask = null;
            qrcodeKey = null;
        }
        send(source, "Bilibili 扫码登录已取消", Formatting.GREEN);
    }

    public synchronized boolean isPolling() {
        return pollingTask != null && !pollingTask.isDone();
    }

    public synchronized void shutdown() {
        if (pollingTask != null) pollingTask.cancel(false);
        pollingTask = null;
        qrcodeKey = null;
    }

    private synchronized void schedulePolling(ServerCommandSource source) {
        pollingTask = VideoPlayerMain.scheduler.scheduleAtFixedRate(() -> poll(source), 2, 2, TimeUnit.SECONDS);
    }

    private void poll(ServerCommandSource source) {
        String key;
        synchronized (this) {
            key = qrcodeKey;
        }
        if (key == null) return;
        try {
            HttpResponse<String> response = httpClient.send(baseRequest(String.format(POLL_URL, key)).GET().build(), HttpResponse.BodyHandlers.ofString());
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject data = root.has("data") && root.get("data").isJsonObject() ? root.getAsJsonObject("data") : null;
            int code = data != null && data.has("code") ? data.get("code").getAsInt() : Integer.MIN_VALUE;
            if (code == 0) {
                completeLogin(source, response);
            } else if (code == 86038) {
                expire(source);
            } else if (code == 86090) {
                send(source, "Bilibili 二维码已扫描，等待手机确认...", Formatting.YELLOW);
            } else if (code != 86101) {
                String message = root.has("message") ? root.get("message").getAsString() : root.toString();
                send(source, "Bilibili 登录状态: " + message, Formatting.GRAY);
            }
        } catch (Exception e) {
            send(source, "轮询 Bilibili 登录失败: " + e, Formatting.RED);
        }
    }

    private void completeLogin(ServerCommandSource source, HttpResponse<String> response) {
        String cookie = extractCookie(response.headers().allValues("set-cookie"));
        if (cookie.isBlank()) {
            send(source, "扫码成功，但未收到可保存的 Cookie。", Formatting.RED);
            stopPolling();
            return;
        }
        DataHolder.config.providerAuth.bilibiliCookie = cookie;
        BiliBiliProvider.configureAuthCookie(cookie);
        DataHolder.save();
        send(source, "Bilibili 扫码登录成功，Cookie 已保存到服务端配置。", Formatting.GREEN);
        stopPolling();
    }

    private void expire(ServerCommandSource source) {
        send(source, "Bilibili 二维码已过期，请重新执行 /vlc-core auth bilibili qr", Formatting.RED);
        stopPolling();
    }

    private synchronized void stopPolling() {
        if (pollingTask != null) pollingTask.cancel(false);
        pollingTask = null;
        qrcodeKey = null;
    }

    private Path writeLoginUrl(String key, String url) throws Exception {
        Files.createDirectories(DataHolder.dataDir);
        Path output = DataHolder.dataDir.resolve("bilibili-login-url-" + key + ".txt");
        Files.writeString(output, url);
        Files.writeString(DataHolder.dataDir.resolve("bilibili-login-url.txt"), url);
        return output;
    }

    private static HttpRequest.Builder baseRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", BiliBiliProvider.UA)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Referer", "https://www.bilibili.com");
    }

    private static boolean isOk(JsonObject root) {
        return root.has("code") && root.get("code").getAsInt() == 0;
    }

    private static String extractCookie(List<String> setCookieHeaders) {
        Map<String, String> cookies = new LinkedHashMap<>();
        for (String header : setCookieHeaders) {
            String first = header.split(";", 2)[0];
            int idx = first.indexOf('=');
            if (idx <= 0) continue;
            String name = first.substring(0, idx);
            String value = first.substring(idx + 1);
            if (STORED_COOKIE_NAMES.contains(name)) cookies.put(name, value);
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (!entry.getValue().toLowerCase(Locale.ROOT).contains("deleted")) {
                out.add(entry.getKey() + "=" + entry.getValue());
            }
        }
        return String.join("; ", out);
    }

    private static void send(ServerCommandSource source, String message, Formatting color) {
        source.sendFeedback(() -> Text.literal(message).formatted(color), false);
    }
}
