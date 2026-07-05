package com.github.squi2rel.vp;

import com.github.squi2rel.vp.video.VideoArea;

import java.util.ArrayList;

public class ServerConfig {
    public ArrayList<VideoArea> areas = new ArrayList<>();
    public String remoteControlName = "minecraft:iron_ingot";
    public float remoteControlId = -1;
    public float remoteControlRange = 64;
    public float noControlRange = 16;
    public boolean debug = false;
    public ProviderAuth providerAuth = new ProviderAuth();

    public static class ProviderAuth {
        public String bilibiliCookie = "";
        public String bilibiliCookieFile = "bilibili-cookies.txt";
        public String youtubeCookie = "";
        public String youtubeCookieFile = "youtube-cookies.txt";
        public String ytDlpPath = "";
    }
}
