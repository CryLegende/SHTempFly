package org.Cry_Legende.sHTempFly.zone;

import org.Cry_Legende.sHTempFly.SHTempFly;
import org.Cry_Legende.sHTempFly.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.List;
import java.util.Map;

/**
 * Resout le mode de vol applicable a une position (monde puis regions WorldGuard).
 */
public final class ZoneService {

    private final SHTempFly plugin;

    private WorldGuardBridge worldGuard;

    public ZoneService(SHTempFly plugin) {
        this.plugin = plugin;
    }

    public void setup() {
        worldGuard = null;
        if (!plugin.config().worldGuardEnabled()) {
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) {
            plugin.getLogger().info("WorldGuard absent : seules les regles par monde seront appliquees.");
            return;
        }
        try {
            worldGuard = new WorldGuardBridge();
            plugin.getLogger().info("WorldGuard detecte, gestion des regions active.");
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Impossible d'initialiser WorldGuard : " + throwable.getMessage());
        }
    }

    public boolean hasWorldGuard() {
        return worldGuard != null;
    }

    public ZoneResult resolve(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return ZoneResult.UNMANAGED;
        }
        PluginConfig config = plugin.config();
        PluginConfig.WorldZone worldZone = config.world(world.getName());
        ZoneMode mode = worldZone != null ? worldZone.mode() : config.defaultMode();
        if (mode == ZoneMode.UNMANAGED) {
            return ZoneResult.UNMANAGED;
        }

        double multiplier = config.worldMultiplier(world.getName());

        Map<String, ZoneMode> worldRegions = worldZone != null ? worldZone.regions() : Map.of();
        boolean needRegions = worldGuard != null
            && (!worldRegions.isEmpty() || !config.globalRegions().isEmpty() || config.hasRegionMultipliers());

        if (needRegions) {
            List<String> regions = worldGuard.regionsAt(location);
            boolean modeFound = false;
            boolean multiplierFound = false;
            for (String id : regions) {
                if (!modeFound) {
                    ZoneMode regionMode = worldRegions.get(id);
                    if (regionMode == null) {
                        regionMode = config.globalRegions().get(id);
                    }
                    if (regionMode != null) {
                        mode = regionMode;
                        modeFound = true;
                    }
                }
                if (!multiplierFound && config.hasRegionMultipliers()) {
                    double regionMultiplier = config.regionMultiplier(id);
                    if (regionMultiplier != 1D) {
                        multiplier *= regionMultiplier;
                        multiplierFound = true;
                    }
                }
                if (modeFound && (multiplierFound || !config.hasRegionMultipliers())) {
                    break;
                }
            }
            if (mode == ZoneMode.UNMANAGED) {
                return ZoneResult.UNMANAGED;
            }
        }

        return new ZoneResult(mode, Math.max(0D, multiplier));
    }
}
