package org.Cry_Legende.sHTempFly.zone;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Acces isole a WorldGuard : cette classe n'est chargee que si le plugin est present.
 */
public final class WorldGuardBridge {

    private final RegionContainer container;

    public WorldGuardBridge() {
        this.container = WorldGuard.getInstance().getPlatform().getRegionContainer();
    }

    /**
     * Identifiants des regions couvrant la position, de la priorite la plus haute a la plus basse.
     */
    public List<String> regionsAt(Location location) {
        RegionQuery query = container.createQuery();
        ApplicableRegionSet set = query.getApplicableRegions(BukkitAdapter.adapt(location));
        if (set.size() == 0) {
            return List.of();
        }
        List<ProtectedRegion> regions = new ArrayList<>(set.getRegions());
        regions.sort(Comparator.comparingInt(ProtectedRegion::getPriority).reversed());
        List<String> ids = new ArrayList<>(regions.size());
        for (ProtectedRegion region : regions) {
            ids.add(region.getId().toLowerCase(Locale.ROOT));
        }
        return ids;
    }
}
