package com.github.squi2rel.vp.folia.provider.bilibili;

import com.github.squi2rel.vp.folia.VideoPlayerFoliaPlugin;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

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
    private static final String GENERATE_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate";
    private static final String POLL_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=%s";
    private static final Set<String> STORED_COOKIE_NAMES = Set.of(
            "SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid", "buvid3", "buvid4", "b_nut"
    );

    private final VideoPlayerFoliaPlugin plugin;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private ScheduledFuture<?> pollingTask;
    private String qrcodeKey;

    public BiliBiliAuthManager(VideoPlayerFoliaPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void startQrLogin(CommandSender sender) {
        if (pollingTask != null && !pollingTask.isDone()) {
            sender.sendMessage(Component.text("已有 Bilibili 扫码登录正在进行，请先 /vpfolia auth bilibili cancel", NamedTextColor.YELLOW));
            return;
        }
        plugin.getCoreExecutor().execute(() -> {
            try {
                HttpResponse<String> response = httpClient.send(baseRequest(GENERATE_URL).GET().build(), HttpResponse.BodyHandlers.ofString());
                JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                if (!isOk(root)) {
                    throw new IllegalStateException(root.toString());
                }
                JsonObject data = root.getAsJsonObject("data");
                String url = data.get("url").getAsString();
                synchronized (this) {
                    qrcodeKey = data.get("qrcode_key").getAsString();
                }
                Path qrPath = writeQrImage(qrcodeKey, url);
                sender.sendMessage(Component.text("Bilibili 扫码登录已创建，有效期约 180 秒。", NamedTextColor.GREEN));
                sender.sendMessage(Component.text("二维码图片: " + qrPath.toAbsolutePath(), NamedTextColor.AQUA));
                sender.sendMessage(Component.text("二维码内容 URL: " + url, NamedTextColor.GRAY));
                schedulePolling(sender);
            } catch (Exception e) {
                sender.sendMessage(Component.text("创建 Bilibili 二维码失败: " + e, NamedTextColor.RED));
            }
        });
    }

    public synchronized void cancel(CommandSender sender) {
        if (pollingTask != null) {
            pollingTask.cancel(false);
            pollingTask = null;
            qrcodeKey = null;
        }
        sender.sendMessage(Component.text("Bilibili 扫码登录已取消", NamedTextColor.GREEN));
    }

    public synchronized boolean isPolling() {
        return pollingTask != null && !pollingTask.isDone();
    }

    public synchronized void shutdown() {
        if (pollingTask != null) {
            pollingTask.cancel(false);
        }
        pollingTask = null;
        qrcodeKey = null;
    }

    private synchronized void schedulePolling(CommandSender sender) {
        pollingTask = plugin.getSchedulerExecutor().scheduleAtFixedRate(() -> poll(sender), 2, 2, TimeUnit.SECONDS);
    }

    private void poll(CommandSender sender) {
        String key;
        synchronized (this) {
            key = qrcodeKey;
        }
        if (key == null) return;
        try {
            HttpRequest request = baseRequest(String.format(POLL_URL, key)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (root.has("data") && root.get("data").isJsonObject() && root.getAsJsonObject("data").has("code") && root.getAsJsonObject("data").get("code").getAsInt() == 0) {
                completeLogin(sender, response);
                return;
            }
            JsonObject data = root.has("data") && root.get("data").isJsonObject() ? root.getAsJsonObject("data") : null;
            int code = data != null && data.has("code") ? data.get("code").getAsInt() : Integer.MIN_VALUE;
            String message = root.has("message") ? root.get("message").getAsString() : root.toString();
            if (code == 86038 || message.contains("expire") || message.contains("timeout")) {
                expire(sender);
            } else if (code == 86090) {
                sender.sendMessage(Component.text("Bilibili 二维码已扫描，等待手机确认...", NamedTextColor.YELLOW));
            } else if (code == 86101) {
            } else {
                sender.sendMessage(Component.text("Bilibili 登录状态: " + message, NamedTextColor.GRAY));
            }
        } catch (Exception e) {
            sender.sendMessage(Component.text("轮询 Bilibili 登录失败: " + e, NamedTextColor.RED));
        }
    }

    private void completeLogin(CommandSender sender, HttpResponse<String> response) {
        String cookie = extractCookie(response.headers().allValues("set-cookie"));
        if (cookie.isBlank()) {
            sender.sendMessage(Component.text("扫码成功，但未收到可保存的 Cookie。", NamedTextColor.RED));
            stopPolling();
            return;
        }
        plugin.getDataHolder().config.providerAuth.bilibiliCookie = cookie;
        BiliBiliProvider.configureAuthCookie(cookie);
        plugin.getDataHolder().save();
        sender.sendMessage(Component.text("Bilibili 扫码登录成功，Cookie 已保存到服务端配置。", NamedTextColor.GREEN));
        stopPolling();
    }

    private void expire(CommandSender sender) {
        sender.sendMessage(Component.text("Bilibili 二维码已过期，请重新执行 /vpfolia auth bilibili qr", NamedTextColor.RED));
        stopPolling();
    }

    private synchronized void stopPolling() {
        if (pollingTask != null) pollingTask.cancel(false);
        pollingTask = null;
        qrcodeKey = null;
    }

    private Path writeQrImage(String key, String url) throws Exception {
        Files.createDirectories(plugin.getDataFolder().toPath());
        Path output = plugin.getDataFolder().toPath().resolve("bilibili-login-qr-" + key + ".png");
        BitMatrix matrix = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 360, 360);
        MatrixToImageWriter.writeToPath(matrix, "PNG", output);
        MatrixToImageWriter.writeToPath(matrix, "PNG", plugin.getDataFolder().toPath().resolve("bilibili-login-qr.png"));
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
            if (STORED_COOKIE_NAMES.contains(name)) {
                cookies.put(name, value);
            }
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (!entry.getValue().toLowerCase(Locale.ROOT).contains("deleted")) {
                out.add(entry.getKey() + "=" + entry.getValue());
            }
        }
        return String.join("; ", out);
    }
}
