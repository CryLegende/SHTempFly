package org.Cry_Legende.sHTempFly.data;

import org.Cry_Legende.sHTempFly.SHTempFly;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Import du data.yml de l'ancien plugin TempFly.
 */
public final class LegacyImporter {

    public static final String META_KEY = "legacy_import";

    private final SHTempFly plugin;

    public LegacyImporter(SHTempFly plugin) {
        this.plugin = plugin;
    }

    /**
     * Lit le fichier et injecte les temps manquants en base.
     * Le callback est appele sur le thread principal avec le nombre de joueurs importes (-1 si fichier absent).
     */
    public void importFrom(File file, Consumer<Integer> callback) {
        if (!file.isFile()) {
            callback.accept(-1);
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<UUID, Double> times = read(file);
            if (times.isEmpty()) {
                plugin.getServer().getScheduler().runTask(plugin, () -> callback.accept(0));
                return;
            }
            plugin.storage().importLegacy(times).thenAccept(imported ->
                plugin.getServer().getScheduler().runTask(plugin, () -> callback.accept(imported)));
        });
    }

    private Map<UUID, Double> read(File file) {
        Map<UUID, Double> times = new HashMap<>();
        YamlConfiguration legacy = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = legacy.getConfigurationSection("players");
        if (players == null) {
            return times;
        }
        for (String key : players.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            double time = players.getDouble(key + ".time", 0D);
            if (time > 0D) {
                times.put(uuid, time);
            }
        }
        return times;
    }
}
