package com.github.squi2rel.vp.provider;

import com.github.squi2rel.vp.network.ByteBufUtils;
import io.netty.buffer.ByteBuf;

public record MediaOption(String id, String label, String description) {
    public static void write(ByteBuf buf, MediaOption option) {
        ByteBufUtils.writeString(buf, option.id);
        ByteBufUtils.writeString(buf, option.label);
        ByteBufUtils.writeString(buf, option.description);
    }

    public static MediaOption read(ByteBuf buf) {
        return new MediaOption(
                ByteBufUtils.readString(buf, 128),
                ByteBufUtils.readString(buf, 128),
                ByteBufUtils.readString(buf, 256)
        );
    }
}
