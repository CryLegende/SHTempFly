package org.Cry_Legende.sHTempFly.display;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.Cry_Legende.sHTempFly.SHTempFly;
import org.Cry_Legende.sHTempFly.config.Messages;
import org.Cry_Legende.sHTempFly.config.PluginConfig;
import org.Cry_Legende.sHTempFly.data.FlyUser;
import org.bukkit.Bukkit;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Barre d'action, barre de boss et titres d'avertissement.
 */
public final class DisplayService {

    private final SHTempFly plugin;
    private final Map<UUID, BossBar> bars = new HashMap<>();
    /**
     * Joueurs a qui on affiche actuellement une barre d'action.
     */
    private final Set<UUID> actionBars = new HashSet<>();

    public DisplayService(SHTempFly plugin) {
        this.plugin = plugin;
    }

    public void update(Player player, FlyUser user) {
        PluginConfig config = plugin.config();
        Messages messages = plugin.messages();
        boolean unlimited = user.unlimited();

        // Plus de temps : on n'affiche pas un compteur a 0, on efface tout.
        if (!unlimited && user.timeSeconds() <= 0L) {
            hide(player);
            return;
        }

        if (config.actionBarEnabled() && (!unlimited || config.actionBarShowUnlimited())) {
            String text = unlimited
                ? messages.get("action-bar-unlimited")
                : messages.get("action-bar", messages.timePlaceholders(user.timeSeconds()));
            if (!text.isEmpty()) {
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(text));
                actionBars.add(player.getUniqueId());
            }
        } else {
            clearActionBar(player);
        }

        if (config.bossBarEnabled() && (!unlimited || config.bossBarShowUnlimited())) {
            String text = unlimited
                ? messages.get("boss-bar-unlimited")
                : messages.get("boss-bar", messages.timePlaceholders(user.timeSeconds()));
            BossBar bar = bars.computeIfAbsent(player.getUniqueId(),
                id -> Bukkit.createBossBar(text, config.bossBarColor(), config.bossBarStyle()));
            bar.setTitle(text);
            bar.setProgress(unlimited ? 1D : Math.min(1D, user.time() / config.bossBarFullAt()));
            if (!bar.getPlayers().contains(player)) {
                bar.addPlayer(player);
            }
        } else {
            hideBossBar(player);
        }
    }

    public void hide(Player player) {
        hideBossBar(player);
        clearActionBar(player);
    }

    public void warn(Player player, long secondsLeft) {
        PluginConfig config = plugin.config();
        if (!config.warningEnabled()) {
            return;
        }
        Messages messages = plugin.messages();
        String[] placeholders = messages.timePlaceholders(secondsLeft);
        String title = messages.get("warning.title", placeholders);
        String subtitle = messages.get("warning.subtitle", placeholders);
        if (title.isEmpty() && subtitle.isEmpty()) {
            return;
        }
        player.sendTitle(title, subtitle, config.warningFadeIn(), config.warningStay(), config.warningFadeOut());
    }

    public void remove(Player player) {
        actionBars.remove(player.getUniqueId());
        BossBar bar = bars.remove(player.getUniqueId());
        if (bar != null) {
            bar.removeAll();
        }
    }

    public void shutdown() {
        bars.values().forEach(BossBar::removeAll);
        bars.clear();
        actionBars.clear();
    }

    /**
     * Efface la barre d'action, mais une seule fois : la reenvoyer en boucle
     * ecraserait celle des autres plugins.
     */
    private void clearActionBar(Player player) {
        if (actionBars.remove(player.getUniqueId())) {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(""));
        }
    }

    private void hideBossBar(Player player) {
        BossBar bar = bars.get(player.getUniqueId());
        if (bar != null && bar.getPlayers().contains(player)) {
            bar.removePlayer(player);
        }
    }
}
