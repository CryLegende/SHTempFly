package org.Cry_Legende.sHTempFly.hook;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandPrivilege;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.Cry_Legende.sHTempFly.SHTempFly;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Lecture de la permission d'ile "FLY" de SuperiorSkyblock2.
 * Cette classe n'est chargee que si SuperiorSkyblock2 est present.
 */
public final class SkyblockHook {

    private final SHTempFly plugin;
    private final IslandPrivilege privilege;

    public SkyblockHook(SHTempFly plugin) {
        this.plugin = plugin;
        this.privilege = resolvePrivilege(plugin.config().skyblockPrivilege());
    }

    public boolean available() {
        return privilege != null;
    }

    /**
     * @return true si le joueur a le droit de voler a cet endroit selon les reglages de l'ile.
     */
    public boolean canFly(Player player, Location location) {
        if (privilege == null) {
            return plugin.config().skyblockDefaultWhenUnknown();
        }
        try {
            Island island = SuperiorSkyblockAPI.getIslandAt(location);
            if (island == null) {
                return true;
            }
            SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(player.getUniqueId());
            if (superiorPlayer == null) {
                return true;
            }
            return island.hasPermission(superiorPlayer, privilege);
        } catch (Throwable throwable) {
            if (plugin.config().debug()) {
                plugin.getLogger().warning("Erreur du hook SuperiorSkyblock2 : " + throwable);
            }
            return plugin.config().skyblockDefaultWhenUnknown();
        }
    }

    private IslandPrivilege resolvePrivilege(String name) {
        try {
            return IslandPrivilege.getByName(name);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Permission d'ile '" + name + "' introuvable dans SuperiorSkyblock2.");
            return null;
        }
    }
}
