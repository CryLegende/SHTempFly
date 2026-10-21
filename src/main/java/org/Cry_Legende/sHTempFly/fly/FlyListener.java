package org.Cry_Legende.sHTempFly.fly;

import org.Cry_Legende.sHTempFly.SHTempFly;
import org.Cry_Legende.sHTempFly.data.FlyUser;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;

public final class FlyListener implements Listener {

    private final SHTempFly plugin;

    public FlyListener(SHTempFly plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.flyManager().handleJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.flyManager().handleQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null
            || (from.getBlockX() == to.getBlockX()
            && from.getBlockY() == to.getBlockY()
            && from.getBlockZ() == to.getBlockZ())) {
            return;
        }
        // Tous les deplacements ne peuvent pas changer le verdict : le moteur decide
        // lui-meme lesquels valent une reevaluation (voir FlyManager.needsRefresh).
        if (!plugin.flyManager().needsRefresh(from, to)) {
            return;
        }
        plugin.flyManager().refresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        plugin.flyManager().refreshLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        plugin.flyManager().refreshLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        plugin.flyManager().refreshLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        plugin.flyManager().refreshLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        if (!event.isFlying()) {
            return;
        }
        Player player = event.getPlayer();
        FlyUser user = plugin.flyManager().user(player.getUniqueId());
        if (user == null) {
            return;
        }
        FlyResult result = user.lastResult();
        if (result != null && result.denied()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL
            || !(event.getEntity() instanceof Player player)) {
            return;
        }
        if (plugin.flyManager().consumeFallGrace(player)) {
            event.setCancelled(true);
        }
    }
}
