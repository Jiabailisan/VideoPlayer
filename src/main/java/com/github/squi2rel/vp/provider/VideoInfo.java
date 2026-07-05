package com.github.squi2rel.vp.provider;

import com.github.squi2rel.vp.network.ByteBufUtils;
import io.netty.buffer.ByteBuf;

public record VideoInfo(String playerName, String name, String path, String rawPath, long expire, boolean seekable, String[] params) {
    private static final int MAX_URL_LENGTH = 16384;
    private static final int MAX_PARAM_LENGTH = 16384;

    public static void write(ByteBuf buf, VideoInfo i) {
        ByteBufUtils.writeString(buf, safe(i.playerName));
        ByteBufUtils.writeString(buf, safe(i.name));
        ByteBufUtils.writeString(buf, safe(i.path));
        ByteBufUtils.writeString(buf, safe(i.rawPath));
        buf.writeLong(i.expire);
        buf.writeBoolean(i.seekable);
        String[] params = i.params == null ? new String[0] : i.params;
        buf.writeByte(params.length);
        for (String param : params) {
            ByteBufUtils.writeString(buf, safe(param));
        }
    }

    public static VideoInfo read(ByteBuf buf) {
        String playerName = ByteBufUtils.readString(buf, 256);
        String name = ByteBufUtils.readString(buf, 256);
        String path = ByteBufUtils.readString(buf, MAX_URL_LENGTH);
        String rawPath = ByteBufUtils.readString(buf, MAX_URL_LENGTH);
        long expire = buf.readLong();
        boolean seekable = buf.readBoolean();
        byte length = buf.readByte();
        String[] params = new String[length];
        for (int i = 0; i < length; i++) {
            params[i] = ByteBufUtils.readString(buf, MAX_PARAM_LENGTH);
        }
        return new VideoInfo(playerName, name, path, rawPath, expire, seekable, params);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
