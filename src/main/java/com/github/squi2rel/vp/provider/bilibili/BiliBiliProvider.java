package com.github.squi2rel.vp.provider.bilibili;

import com.github.squi2rel.vp.provider.IVideoProvider;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.concurrent.CompletableFuture;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;

public abstract class BiliBiliProvider implements IVideoProvider {
    public static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36 Edg/136.0.0.0";
    public static final String[] VLC_PARAMS = {
            ":http-user-agent=" + UA,
            ":http-referrer=https://www.bilibili.com",
            ":http-reconnect",
            ":network-caching=3000",
            ":file-caching=3000",
            ":live-caching=3000",
            ":drop-late-frames",
            ":skip-frames"
    };

    public static String biliTicket;
    public static long expireTime;
    private static String authCookie = "";

    public static void configureAuthCookie(String cookie) {
        authCookie = cookie == null ? "" : cookie.trim();
    }

    public static boolean hasAuthCookie() {
        return !authCookie.isEmpty();
    }

    protected static HttpRequest makeRequest(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url))
                .header("User-Agent", UA)
                .header("Referer", "https://www.bilibili.com");
        if (!authCookie.isEmpty()) {
            builder.header("Cookie", authCookie);
        } else if (biliTicket != null && System.currentTimeMillis() < expireTime) {
            builder.header("Cookie", "bili_ticket=" + biliTicket);
        } else if (System.currentTimeMillis() > expireTime) {
            CompletableFuture.runAsync(() -> {
                try {
                    biliTicket = JsonParser.parseString(BiliTicket.getBiliTicket("")).getAsJsonObject().getAsJsonObject("data").get("ticket").getAsString();
                    LOGGER.info("bilibili ticket refreshed");
                    expireTime = System.currentTimeMillis() + 259260 * 1000;
                } catch (Exception ignored) {
                    expireTime = System.currentTimeMillis() + 1000 * 60 * 10;
                }
            });
        }
        return builder.build();
    }

    protected record VideoMeta(String title, String cid) {}
}
