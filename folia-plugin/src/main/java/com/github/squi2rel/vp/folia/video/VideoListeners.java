package com.github.squi2rel.vp.folia.video;

import com.github.squi2rel.vp.folia.provider.VideoInfo;

public final class VideoListeners {
    private VideoListeners() {
    }

    public static IVideoListener from(VideoInfo info) {
        if (ClientClockListener.accept(info)) {
            return new ClientClockListener();
        }
        if (StreamListener.accept(info)) {
            return new StreamListener(info);
        }
        if (PlayerListener.accept(info)) {
            return new PlayerListener();
        }
        return null;
    }
}
