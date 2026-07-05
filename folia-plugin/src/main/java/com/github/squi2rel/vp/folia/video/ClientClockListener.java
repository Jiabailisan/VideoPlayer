package com.github.squi2rel.vp.folia.video;

import com.github.squi2rel.vp.folia.provider.VideoInfo;

import java.util.Arrays;
import java.util.function.Consumer;

public class ClientClockListener implements IVideoListener {
    public static final String PARAM = ":videoplayer-client-clock";
    private static final long SEEK_BUFFER_GRACE = 3000;

    private long startTime;
    private boolean playing;
    private long holdProgress = -1;
    private long holdUntil;

    public static boolean accept(VideoInfo info) {
        return info.path() != null && !info.path().isEmpty()
                && info.params() != null
                && Arrays.asList(info.params()).contains(PARAM);
    }

    public static String[] stripInternalParams(String[] params) {
        if (params == null) return new String[0];
        return Arrays.stream(params).filter(param -> !PARAM.equals(param)).toArray(String[]::new);
    }

    @Override
    public long getProgress() {
        if (holdProgress >= 0) {
            if (System.currentTimeMillis() < holdUntil) return holdProgress;
            startTime = System.currentTimeMillis() - holdProgress;
            holdProgress = -1;
        }
        return playing ? System.currentTimeMillis() - startTime : -1;
    }

    @Override
    public void setProgress(long progress) {
        holdProgress = Math.max(0, progress);
        holdUntil = System.currentTimeMillis() + SEEK_BUFFER_GRACE;
        startTime = System.currentTimeMillis() - holdProgress;
    }

    @Override
    public boolean isPlaying() {
        return playing;
    }

    @Override
    public void playing(Consumer<Boolean> playing) {
        playing.accept(true);
    }

    @Override
    public void stopped(Runnable stopped) {
    }

    @Override
    public void errored(Runnable errored) {
    }

    @Override
    public void timeout(Runnable timeout) {
    }

    @Override
    public void listen() {
        startTime = System.currentTimeMillis();
        playing = true;
    }

    @Override
    public void cancel() {
        playing = false;
    }
}
