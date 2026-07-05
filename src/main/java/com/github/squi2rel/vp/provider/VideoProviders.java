package com.github.squi2rel.vp.provider;

import com.github.squi2rel.vp.VideoPlayerMain;
import com.github.squi2rel.vp.provider.bilibili.BiliBiliLiveProvider;
import com.github.squi2rel.vp.provider.bilibili.BiliBiliVideoProvider;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class VideoProviders {
    public static ArrayList<IVideoProvider> providers = new ArrayList<>();

    public static void register() {
        providers.clear();
        providers.add(new BiliBiliVideoProvider());
        providers.add(new BiliBiliLiveProvider());
        providers.add(new YouTubeProvider());
        providers.add(new EntityViewProvider());
        providers.add(new NetworkProvider());
    }

    public static @Nullable CompletableFuture<VideoInfo> from(String str, IProviderSource source) {
        return from(str, source, "");
    }

    public static @Nullable CompletableFuture<VideoInfo> from(String str, IProviderSource source, String optionId) {
        VideoPlayerMain.LOGGER.info("Player {} requested {}", source.name(), str);
        try {
            for (IVideoProvider provider : providers) {
                CompletableFuture<VideoInfo> info = optionId == null || optionId.isBlank()
                        ? provider.from(str, source)
                        : provider.from(str, source, optionId);
                if (info != null) {
                    VideoPlayerMain.LOGGER.info("Using {}", provider.getClass().getSimpleName());
                    return info;
                }
            }
            VideoPlayerMain.LOGGER.info("No suitable provider");
        } catch (Exception e) {
            VideoPlayerMain.LOGGER.error(e.toString());
            source.reply(e.toString());
        }
        return null;
    }

    public static @Nullable CompletableFuture<List<MediaOption>> options(String str, IProviderSource source) {
        VideoPlayerMain.LOGGER.info("Player {} requested media options for {}", source.name(), str);
        try {
            for (IVideoProvider provider : providers) {
                CompletableFuture<List<MediaOption>> options = provider.options(str, source);
                if (options != null) {
                    VideoPlayerMain.LOGGER.info("Options from {}", provider.getClass().getSimpleName());
                    return options;
                }
            }
        } catch (Exception e) {
            VideoPlayerMain.LOGGER.error(e.toString());
            source.reply(e.toString());
        }
        return null;
    }
}
