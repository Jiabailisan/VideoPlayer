package com.github.squi2rel.vp.folia.provider;

import com.github.squi2rel.vp.folia.VideoPlayerFoliaPlugin;
import com.github.squi2rel.vp.folia.provider.bilibili.BiliBiliLiveProvider;
import com.github.squi2rel.vp.folia.provider.bilibili.BiliBiliVideoProvider;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class VideoProviders {
    public static final ArrayList<IVideoProvider> providers = new ArrayList<>();
    private static VideoPlayerFoliaPlugin plugin;

    private VideoProviders() {
    }

    public static void register(VideoPlayerFoliaPlugin owner) {
        plugin = owner;
        providers.clear();
        providers.add(new BiliBiliVideoProvider());
        providers.add(new BiliBiliLiveProvider());
        providers.add(new YouTubeProvider(owner));
        providers.add(new EntityViewProvider(owner));
        providers.add(new NetworkProvider());
    }

    public static @Nullable CompletableFuture<VideoInfo> from(String str, IProviderSource source) {
        return from(str, source, "");
    }

    public static @Nullable CompletableFuture<VideoInfo> from(String str, IProviderSource source, String optionId) {
        plugin.getLogger().info("Player %s requested %s".formatted(source.name(), str));
        try {
            for (IVideoProvider provider : providers) {
                CompletableFuture<VideoInfo> info = optionId == null || optionId.isBlank()
                        ? provider.from(str, source)
                        : provider.from(str, source, optionId);
                if (info != null) {
                    plugin.getLogger().info("Using " + provider.getClass().getSimpleName());
                    return info;
                }
            }
            plugin.getLogger().info("No suitable provider");
        } catch (Exception e) {
            plugin.getLogger().warning(e.toString());
            source.reply(e.toString());
        }
        return null;
    }

    public static @Nullable CompletableFuture<List<MediaOption>> options(String str, IProviderSource source) {
        plugin.getLogger().info("Player %s requested media options for %s".formatted(source.name(), str));
        try {
            for (IVideoProvider provider : providers) {
                CompletableFuture<List<MediaOption>> options = provider.options(str, source);
                if (options != null) {
                    plugin.getLogger().info("Options from " + provider.getClass().getSimpleName());
                    return options;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning(e.toString());
            source.reply(e.toString());
        }
        return null;
    }
}
