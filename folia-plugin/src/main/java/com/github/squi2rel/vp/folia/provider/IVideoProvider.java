package com.github.squi2rel.vp.folia.provider;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface IVideoProvider {
    String[] NO_PARAMS = new String[0];

    @Nullable CompletableFuture<VideoInfo> from(String str, IProviderSource source);

    default @Nullable CompletableFuture<VideoInfo> from(String str, IProviderSource source, String optionId) {
        return from(str, source);
    }

    default @Nullable CompletableFuture<List<MediaOption>> options(String str, IProviderSource source) {
        return null;
    }
}
