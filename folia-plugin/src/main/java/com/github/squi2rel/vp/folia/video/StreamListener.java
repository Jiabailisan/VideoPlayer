package com.github.squi2rel.vp.folia.video;

import com.github.squi2rel.vp.folia.provider.VideoInfo;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class StreamListener implements IVideoListener {
    public static final ExecutorService releaseExecutor = Executors.newCachedThreadPool();

    private long startTime;
    private boolean playing;
    private long holdProgress = -1;
    private long holdUntil;
    private Runnable stopped = () -> {};

    public StreamListener(VideoInfo info) {
    }

    public static boolean accept(VideoInfo info) {
        return info.path() != null && !info.path().isEmpty();
    }

    public static void load() {
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
        holdUntil = System.currentTimeMillis() + 3000;
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
        this.stopped = stopped;
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
        stopped.run();
    }
}
