package com.github.squi2rel.vp.folia.provider;

import com.github.squi2rel.vp.folia.VideoPlayerFoliaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

public class EntityViewProvider implements IVideoProvider {
    public static final Pattern REGEX = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");
    private final VideoPlayerFoliaPlugin plugin;

    public EntityViewProvider(VideoPlayerFoliaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @Nullable CompletableFuture<VideoInfo> from(String str, IProviderSource source) {
        if (REGEX.matcher(str).matches()) {
            return CompletableFuture.completedFuture(new VideoInfo(source.name(), "ENTITY VIEW", "", str, -1, false, NO_PARAMS));
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().equals(str)) {
                UUID uuid = player.getUniqueId();
                return CompletableFuture.completedFuture(new VideoInfo(source.name(), "ENTITY VIEW", "", uuid.toString(), -1, false, NO_PARAMS));
            }
        }
        return null;
    }
}
