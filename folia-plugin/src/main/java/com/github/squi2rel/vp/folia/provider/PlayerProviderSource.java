package com.github.squi2rel.vp.folia.provider;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class PlayerProviderSource implements IProviderSource {
    private final Plugin plugin;
    private final Player player;

    public PlayerProviderSource(Plugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    @Override
    public String name() {
        return player.getName();
    }

    @Override
    public void reply(String text) {
        player.getScheduler().run(plugin, task -> player.sendMessage(Component.text(text)), null);
    }
}
