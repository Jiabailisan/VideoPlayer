package com.github.squi2rel.vp.video;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

public class VideoQuad {
    public static final Identifier MISSING_IDENTIFIER = TextureManager.MISSING_IDENTIFIER;
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    private final Identifier textureIdentifier = Identifier.of("videoplayer", "frame/" + NEXT_ID.incrementAndGet());
    private NativeImageBackedTexture texture;
    private int width;
    private int height;
    private boolean textureInitialized = false;

    public VideoQuad(int width, int height) {
        this.width = width;
        this.height = height;
        initializeTexture();
    }

    public synchronized void resize(int width, int height) {
        this.width = width;
        this.height = height;
        regenTexture();
    }

    private void initializeTexture() {
        regenTexture();
    }

    public synchronized void stop() {
    }

    private void regenTexture() {
        texture = new NativeImageBackedTexture("videoplayer-frame", width, height, false);
        MinecraftClient.getInstance().getTextureManager().registerTexture(textureIdentifier, texture);
        textureInitialized = true;
    }

    public synchronized void updateTexture(ByteBuffer frameData) {
        if (!textureInitialized || texture == null || frameData == null) return;
        NativeImage image = texture.getImage();
        if (image == null) return;
        int bytes = width * height * 4;
        if (frameData.capacity() < bytes) return;
        ByteBuffer src = frameData.duplicate().position(0).limit(bytes);
        forceOpaque(src, bytes);
        src.position(0).limit(bytes);
        MemoryUtil.memCopy(MemoryUtil.memAddress(src), image.imageId(), bytes);
        texture.upload();
    }

    private static void forceOpaque(ByteBuffer src, int bytes) {
        for (int i = 3; i < bytes; i += 4) {
            src.put(i, (byte) 0xFF);
        }
    }

    public void cleanup() {
        if (textureInitialized) {
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().getTextureManager().destroyTexture(textureIdentifier));
            textureInitialized = false;
            texture = null;
        }
    }

    public Identifier getTextureIdentifier() {
        return textureIdentifier;
    }
}
