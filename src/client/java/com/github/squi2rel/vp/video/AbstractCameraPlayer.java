package com.github.squi2rel.vp.video;

import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

public abstract class AbstractCameraPlayer implements IVideoPlayer, MetaListener {
    protected ClientVideoScreen screen;
    protected float aspect = 16f / 9f;
    protected int targetWidth = 16, targetHeight = 9;

    public AbstractCameraPlayer(ClientVideoScreen screen) {
        this.screen = screen;
    }

    @Override
    public @Nullable ClientVideoScreen screen() {
        return screen;
    }

    @Override
    public @Nullable ClientVideoScreen getTrackingScreen() {
        return screen;
    }

    @Override
    public boolean canPause() {
        return false;
    }

    @Override
    public void init() {
    }

    @Override
    public void cleanup() {
    }

    @Override
    public void swapTexture() {
    }

    @Override
    public void updateTexture() {
    }

    @Override
    public void onMetaChanged() {
        aspect = Float.intBitsToFloat(screen.meta.getOrDefault("aspect", Float.floatToIntBits(16f / 9f)));
    }

    @Override
    public Identifier getTextureIdentifier() {
        return VideoQuad.MISSING_IDENTIFIER;
    }

    @Override
    public void pause(boolean pause) {
    }

    @Override
    public boolean isPaused() {
        return false;
    }

    @Override
    public void setVolume(int volume) {
    }

    @Override
    public boolean canSetProgress() {
        return false;
    }

    @Override
    public void setProgress(long progress) {
    }

    @Override
    public long getProgress() {
        return 0;
    }

    @Override
    public long getTotalProgress() {
        return 0;
    }

    @Override
    public void setTargetTime(long targetTime) {
    }

    @Override
    public int getWidth() {
        return targetWidth;
    }

    @Override
    public int getHeight() {
        return targetHeight;
    }

}
