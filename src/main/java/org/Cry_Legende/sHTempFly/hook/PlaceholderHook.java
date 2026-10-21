package org.Cry_Legende.sHTempFly.hook;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.Cry_Legende.sHTempFly.SHTempFly;
import org.Cry_Legende.sHTempFly.data.FlyUser;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Placeholders : %shtempfly_time%, %shtempfly_time_raw%, %shtempfly_unlimited%,
 * %shtempfly_flying%, %shtempfly_can_fly%, %shtempfly_zone%.
 */
public final class PlaceholderHook extends PlaceholderExpansion {

    private final SHTempFly plugin;

    public PlaceholderHook(SHTempFly plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "shtempfly";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Cry_Legende";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) {
            return "";
        }
        FlyUser user = plugin.flyManager().user(player.getUniqueId());
        if (user == null) {
            return "";
        }
        return switch (params.toLowerCase()) {
            case "time" -> user.unlimited()
                ? plugin.messages().infinite()
                : plugin.messages().formatTime(user.timeSeconds());
            case "time_raw" -> user.unlimited() ? "-1" : String.valueOf(user.timeSeconds());
            case "unlimited" -> String.valueOf(user.unlimited());
            case "flying" -> String.valueOf(player.isFlying());
            case "can_fly" -> String.valueOf(player.getAllowFlight());
            case "enabled" -> String.valueOf(user.flyEnabled());
            case "zone" -> plugin.zones().resolve(player.getLocation()).mode().name();
            default -> null;
        };
    }
}
