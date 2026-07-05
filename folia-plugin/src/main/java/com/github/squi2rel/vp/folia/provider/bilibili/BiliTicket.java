package com.github.squi2rel.vp.folia.provider.bilibili;

import org.jetbrains.annotations.NotNull;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

public final class BiliTicket {
    private BiliTicket() {
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) sb.append('0');
            sb.append(hex);
        }
        return sb.toString();
    }

    public static String hmacSha256(String key, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        return bytesToHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }

    public static String getBiliTicket(String csrf) throws Exception {
        long ts = System.currentTimeMillis() / 1000;
        String hexSign = hmacSha256("XgwSnGZ1p", "ts" + ts);
        HttpURLConnection conn = getHttpURLConnection(csrf, hexSign, ts);
        InputStream in = conn.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            out.write(b);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static @NotNull HttpURLConnection getHttpURLConnection(String csrf, String hexSign, long ts) throws IOException, URISyntaxException {
        String url = "https://api.bilibili.com/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket" + '?' +
                "key_id=ec02" + '&' +
                "hexsign=" + hexSign + '&' +
                "context[ts]=" + ts + '&' +
                "csrf=" + (csrf == null ? "" : csrf);
        HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.addRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:109.0) Gecko/20100101 Firefox/115.0");
        return conn;
    }
}
