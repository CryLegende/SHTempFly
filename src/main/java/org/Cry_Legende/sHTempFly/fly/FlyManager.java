package org.Cry_Legende.sHTempFly.fly;

import org.Cry_Legende.sHTempFly.SHTempFly;
import org.Cry_Legende.sHTempFly.config.PluginConfig;
import org.Cry_Legende.sHTempFly.data.FlyUser;
import org.Cry_Legende.sHTempFly.zone.ZoneMode;
import org.Cry_Legende.sHTempFly.zone.ZoneResult;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Moteur du vol : decide chaque seconde si un joueur peut voler, consomme son temps
 * et applique l'etat au joueur.
 */
public final class FlyManager {

    private static final long TICK_PERIOD = 20L;
    private static final double TICK_SECONDS = 1D;
    /**
     * Delai minimal avant de renvoyer au joueur le meme message d'etat de vol.
     */
    private static final long NOTIFY_COOLDOWN_MS = 3000L;

    /**
     * Metadata posee sur le joueur tant que SHTempFly lui accorde le vol.
     */
    public static final String FLIGHT_METADATA = "shtempfly.granted";

    private final SHTempFly plugin;
    private final Map<UUID, FlyUser> users = new ConcurrentHashMap<>();
    /**
     * Immunite de chute accordee a la connexion, avant meme que les donnees soient chargees.
     */
    private final Map<UUID, Long> joinGrace = new ConcurrentHashMap<>();

    private BukkitTask tickTask;
    private BukkitTask saveTask;

    public FlyManager(SHTempFly plugin) {
        this.plugin = plugin;
    }

    public void start() {
        reschedule();
        for (Player player : Bukkit.getOnlinePlayers()) {
            handleJoin(player);
        }
    }

    /**
     * Recree les taches periodiques (utilise apres un /shtempfly reload).
     */
    public void reschedule() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        if (saveTask != null) {
            saveTask.cancel();
        }
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_PERIOD, TICK_PERIOD);
        int autosave = plugin.config().autosaveInterval();
        saveTask = autosave > 0
            ? Bukkit.getScheduler().runTaskTimer(plugin, this::saveDirty, autosave * 20L, autosave * 20L)
            : null;
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        if (saveTask != null) {
            saveTask.cancel();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.removeMetadata(FLIGHT_METADATA, plugin);
        }
        long now = System.currentTimeMillis();
        users.values().forEach(user -> user.lastSeen(now));
        plugin.storage().saveAllNow(new ArrayList<>(users.values()));
        users.clear();
        joinGrace.clear();
    }

    public FlyUser user(UUID uuid) {
        return users.get(uuid);
    }

    public Collection<FlyUser> onlineUsers() {
        return users.values();
    }

    public boolean isUnlimited(Player player) {
        for (String permission : plugin.config().unlimitedPermissions()) {
            if (player.hasPermission(permission)) {
                return true;
            }
        }
        return false;
    }

    public void handleJoin(Player player) {
        UUID uuid = player.getUniqueId();
        PluginConfig config = plugin.config();
        // "Safe login" : les donnees arrivent de facon asynchrone, un joueur reconnecte
        // en plein ciel ne doit pas mourir de chute pendant ce court instant.
        if (config.fallProtection() && config.safeLogin() && isAirborne(player)) {
            joinGrace.put(uuid, System.currentTimeMillis() + config.fallProtectionSeconds() * 1000L);
        }
        plugin.storage().load(uuid, player.getName()).thenAccept(user -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                user.lastSeen(System.currentTimeMillis());
                plugin.storage().saveAsync(user);
                return;
            }
            user.name(player.getName());
            user.unlimited(isUnlimited(player));
            applyOfflineDecay(player, user);
            if (user.unlimited()) {
                if (plugin.config().unlimitedResetOnJoin()) {
                    user.flyEnabled(true);
                }
            } else {
                // Le fly temporaire ne se coupe pas : il est toujours actif tant qu'il reste du temps.
                user.flyEnabled(true);
            }
            user.lastSeen(System.currentTimeMillis());
            users.put(uuid, user);
            evaluate(player, user, false);
            // Le vol n'a pas ete rendu : on transfere l'immunite de connexion au joueur.
            Long grace = joinGrace.remove(uuid);
            if (grace != null && !player.getAllowFlight() && grace > user.fallGraceUntil()) {
                user.fallGraceUntil(grace);
            }
        }));
    }

    public void handleQuit(Player player) {
        joinGrace.remove(player.getUniqueId());
        FlyUser user = users.remove(player.getUniqueId());
        plugin.display().remove(player);
        player.removeMetadata(FLIGHT_METADATA, plugin);
        if (user != null) {
            user.lastSeen(System.currentTimeMillis());
            plugin.storage().saveAsync(user);
        }
    }

    /**
     * Consomme l'immunite de chute du joueur si elle est active.
     *
     * @return true si les degats de chute doivent etre annules.
     */
    public boolean consumeFallGrace(Player player) {
        long now = System.currentTimeMillis();
        FlyUser user = users.get(player.getUniqueId());
        if (user != null && user.fallGraceUntil() > now) {
            user.fallGraceUntil(0L);
            return true;
        }
        Long grace = joinGrace.get(player.getUniqueId());
        if (grace != null && grace > now) {
            joinGrace.remove(player.getUniqueId());
            return true;
        }
        return false;
    }

    private boolean isAirborne(Player player) {
        return isAirborne(player, player.getLocation());
    }

    /**
     * Variante sans allocation quand la position du joueur a deja ete resolue.
     */
    private boolean isAirborne(Player player, Location location) {
        return !player.isOnGround()
            && location.getWorld()
            .getBlockAt(location.getBlockX(), location.getBlockY() - 1, location.getBlockZ())
            .isPassable();
    }

    /**
     * Reevalue immediatement un joueur, sans consommer de temps.
     */
    public void refresh(Player player) {
        FlyUser user = users.get(player.getUniqueId());
        if (user != null) {
            evaluate(player, user, false);
        }
    }

    public void refreshLater(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                refresh(player);
            }
        });
    }

    /**
     * Ajoute du temps a un joueur connecte.
     *
     * @return false si le gain a ete ignore parce que le joueur possede le fly illimite.
     */
    public boolean addTime(Player player, double amount, boolean notify) {
        FlyUser user = users.get(player.getUniqueId());
        if (user == null) {
            return false;
        }
        if (user.unlimited()) {
            return false;
        }
        double applied = user.addTime(amount);
        if (notify && applied != 0D) {
            String path = applied > 0 ? "grant.received" : "grant.lost";
            plugin.messages().send(player, path,
                plugin.messages().timePlaceholders((long) Math.abs(Math.round(applied))));
        }
        evaluate(player, user, false);
        return true;
    }

    public boolean setTime(Player player, double amount) {
        FlyUser user = users.get(player.getUniqueId());
        if (user == null) {
            return false;
        }
        if (user.unlimited()) {
            return false;
        }
        user.time(amount);
        evaluate(player, user, false);
        return true;
    }

    /**
     * Active ou desactive le fly d'un joueur.
     *
     * @return le nouvel etat, ou null si les donnees du joueur ne sont pas chargees.
     */
    public Boolean toggle(Player player, Boolean requested) {
        FlyUser user = users.get(player.getUniqueId());
        if (user == null) {
            return null;
        }
        boolean target = requested != null ? requested : !user.flyEnabled();
        if (target == user.flyEnabled()) {
            plugin.messages().send(player, target ? "fly.already-enabled" : "fly.already-disabled");
            return target;
        }
        user.flyEnabled(target);
        if (target) {
            user.lastResult(null);
        }
        evaluate(player, user, false);
        plugin.messages().send(player, target ? "fly.enabled" : "fly.disabled");
        return target;
    }

    public void saveDirty() {
        List<FlyUser> dirty = new ArrayList<>();
        for (FlyUser user : users.values()) {
            if (user.dirty()) {
                dirty.add(user);
            }
        }
        if (!dirty.isEmpty()) {
            long now = System.currentTimeMillis();
            dirty.forEach(user -> user.lastSeen(now));
            plugin.storage().saveAll(dirty);
        }
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            FlyUser user = users.get(player.getUniqueId());
            if (user != null) {
                evaluate(player, user, true);
            }
        }
    }

    private void evaluate(Player player, FlyUser user, boolean drain) {
        PluginConfig config = plugin.config();

        if (config.ignoredGameModes().contains(player.getGameMode())) {
            user.lastResult(FlyResult.UNMANAGED);
            markGranted(player, user, false);
            plugin.display().hide(player);
            return;
        }

        // Une seule Location pour toute l'evaluation : elle est relue par les zones,
        // le hook d'ile et le plafond, et ce chemin tourne a chaque changement de chunk.
        // Aucun de ces appels ne la conserve ni ne la modifie.
        Location location = player.getLocation();

        ZoneResult zone = plugin.zones().resolve(location);
        if (zone.mode() == ZoneMode.UNMANAGED) {
            user.lastResult(FlyResult.UNMANAGED);
            markGranted(player, user, false);
            plugin.display().hide(player);
            return;
        }

        user.unlimited(isUnlimited(player));

        FlyResult result = decide(player, user, zone, location);

        if (drain && user.time() > 0D && shouldConsume(config, result) && shouldDrain(config, player)) {
            double amount = TICK_SECONDS * zone.drainMultiplier();
            if (amount > 0D) {
                long before = user.timeSeconds();
                user.addTime(-amount);
                long after = user.timeSeconds();
                warn(player, before, after);
                if (user.time() <= 0D) {
                    result = FlyResult.DENIED_NO_TIME;
                }
            }
        }

        apply(player, user, result, location);
        if (drain) {
            // Les affichages ne sont rafraichis qu'une fois par seconde, pas a chaque deplacement.
            plugin.display().update(player, user);
        }
    }

    private boolean shouldDrain(PluginConfig config, Player player) {
        return config.drainWhenGrounded() || player.isFlying();
    }

    /**
     * Les zones interdites consomment aussi du temps si la config le demande, avec leur
     * multiplicateur. Les zones gratuites, le fly illimite et les iles qui refusent le
     * vol aux visiteurs ne consomment jamais.
     */
    private boolean shouldConsume(PluginConfig config, FlyResult result) {
        return switch (result) {
            case ALLOWED -> true;
            case DENIED_ZONE, DENIED_HEIGHT -> config.drainInDeniedZones();
            default -> false;
        };
    }

    private FlyResult decide(Player player, FlyUser user, ZoneResult zone, Location location) {
        // Une activite en cours (parcours SHParkour...) suspend le vol pour tout le monde,
        // bypass compris : sinon le joueur serait ejecte de son parcours des qu'il decolle.
        if (isBlockedByActivity(player)) {
            return FlyResult.DENIED_ACTIVITY;
        }

        boolean bypass = player.hasPermission("shtempfly.bypass");

        if (!bypass) {
            if (zone.mode() == ZoneMode.DENY) {
                return FlyResult.DENIED_ZONE;
            }
            if (plugin.skyblock() != null
                && (!user.unlimited() || plugin.config().skyblockApplyToUnlimited())
                && !plugin.skyblock().canFly(player, location)) {
                return FlyResult.DENIED_ISLAND;
            }
            if (isAboveCeiling(user, location)) {
                return FlyResult.DENIED_HEIGHT;
            }
        }

        if (zone.mode() == ZoneMode.FREE || user.unlimited() || bypass) {
            return FlyResult.ALLOWED_FREE;
        }
        if (user.time() <= 0D) {
            return FlyResult.DENIED_NO_TIME;
        }
        return FlyResult.ALLOWED;
    }

    /**
     * True si le joueur est au-dessus de la hauteur maximale autorisee.
     *
     * <p>Le test porte sur la position seule, jamais sur {@code isFlying()} : sinon le
     * joueur redevenait autorise des la premiere frame de chute, le vol lui etait rendu,
     * il remontait aussitot dans le plafond, et chaque deplacement produisait un couple
     * de messages refus / retour.
     *
     * <p>Une marge d'hysteresis est appliquee tant que le refus est actif : il faut
     * redescendre franchement sous le plafond pour recuperer le vol, ce qui empeche
     * toute oscillation autour de la limite.
     */
    private boolean isAboveCeiling(FlyUser user, Location location) {
        int maxHeight = plugin.config().maxHeight();
        if (maxHeight <= 0) {
            return false;
        }
        double limit = user.lastResult() == FlyResult.DENIED_HEIGHT
            ? maxHeight - plugin.config().maxHeightMargin()
            : maxHeight;
        return location.getY() > limit;
    }

    /**
     * Filtre pose sur PlayerMoveEvent : true si le deplacement peut changer le verdict.
     *
     * <p>Les zones, les regions et les iles ne changent pas a l'interieur d'un meme chunk,
     * il est donc inutile de tout reevaluer a chaque bloc parcouru. Le tick d'une seconde
     * rattrape de toute facon les cas limites. Seule la bande proche du plafond reste
     * suivie au bloc pres, pour couper le vol sans delai.
     */
    public boolean needsRefresh(Location from, Location to) {
        if (from.getBlockX() >> 4 != to.getBlockX() >> 4
            || from.getBlockZ() >> 4 != to.getBlockZ() >> 4) {
            return true;
        }
        if (plugin.config().zoneCheckPerBlock()) {
            return true;
        }
        int maxHeight = plugin.config().maxHeight();
        if (maxHeight <= 0 || from.getBlockY() == to.getBlockY()) {
            return false;
        }
        double band = maxHeight - plugin.config().maxHeightMargin();
        return from.getY() > band || to.getY() > band;
    }

    /**
     * True si un autre plugin a pose une metadata qui suspend le vol
     * (voir fly.blocked-metadata dans config.yml). Aucune dependance de compilation.
     */
    public boolean isBlockedByActivity(Player player) {
        for (String key : plugin.config().blockedMetadata()) {
            if (player.hasMetadata(key)) {
                return true;
            }
        }
        return false;
    }

    private void apply(Player player, FlyUser user, FlyResult result, Location location) {
        PluginConfig config = plugin.config();
        FlyResult previous = user.lastResult();
        boolean wantFly = result.allowed() && user.flyEnabled();

        if (wantFly) {
            if (!player.getAllowFlight()) {
                player.setAllowFlight(true);
                if (config.flySpeed() > 0F) {
                    player.setFlySpeed(Math.min(1F, config.flySpeed()));
                }
                // On ne remet le joueur en vol que s'il est en l'air : sinon il se retrouverait
                // a voler sans l'avoir demande juste apres une teleportation au sol.
                // Apres un refus de hauteur en revanche on ne relance jamais le vol : le joueur
                // est encore sous le plafond, il y remonterait immediatement. Il redecolle
                // lui-meme quand il le souhaite.
                if (config.autoEnableOnZoneExit() && previous != null && previous.denied()
                    && previous != FlyResult.DENIED_HEIGHT && isAirborne(player, location)) {
                    player.setFlying(true);
                }
            }
            markGranted(player, user, true);
        } else {
            // On ne coupe que ce qu'on a accorde nous-memes (ou un vol reellement en cours),
            // sinon on casserait les plugins de double saut qui posent leur propre allowFlight.
            boolean ours = user.granted() || player.isFlying() || !config.respectOtherPlugins();
            if (player.getAllowFlight() && ours) {
                boolean wasFlying = player.isFlying();
                player.setFlying(false);
                player.setAllowFlight(false);
                if (wasFlying && config.fallProtection()) {
                    user.fallGraceUntil(System.currentTimeMillis() + config.fallProtectionSeconds() * 1000L);
                }
            }
            markGranted(player, user, false);
        }

        if (result != previous) {
            notifyChange(player, user, result, previous);
        }
        user.lastResult(result);
    }

    /**
     * Marque (ou demarque) le joueur comme "vol accorde par SHTempFly".
     * La metadata sert aux autres plugins, par exemple SHDoubleJump, pour savoir
     * qui possede le allowFlight du joueur a cet instant.
     */
    private void markGranted(Player player, FlyUser user, boolean granted) {
        if (user.granted() == granted) {
            return;
        }
        user.granted(granted);
        if (granted) {
            player.setMetadata(FLIGHT_METADATA, new FixedMetadataValue(plugin, Boolean.TRUE));
        } else {
            player.removeMetadata(FLIGHT_METADATA, plugin);
        }
    }

    private void notifyChange(Player player, FlyUser user, FlyResult result, FlyResult previous) {
        // Garde-fou general : un verdict qui va-et-vient (bord de zone, limite d'ile...)
        // ne doit jamais repeter le meme message plusieurs fois par seconde.
        if (!user.shouldNotify(result, NOTIFY_COOLDOWN_MS)) {
            return;
        }
        switch (result) {
            case DENIED_ZONE -> plugin.messages().send(player, "denied.zone");
            case DENIED_HEIGHT -> plugin.messages().send(player, "denied.height");
            case DENIED_ISLAND -> plugin.messages().send(player, "denied.island");
            case DENIED_ACTIVITY -> plugin.messages().send(player, "denied.activity");
            case DENIED_NO_TIME -> {
                if (previous != null) {
                    plugin.messages().send(player, "fly.out-of-time");
                }
            }
            case ALLOWED, ALLOWED_FREE -> {
                if (previous != null && previous.denied() && user.flyEnabled()) {
                    plugin.messages().send(player, "fly.restored");
                }
            }
            default -> {
                // UNMANAGED : aucun message
            }
        }
    }

    private void warn(Player player, long before, long after) {
        if (!plugin.config().warningEnabled() || before == after) {
            return;
        }
        for (long marker : plugin.config().warningMarkers()) {
            if (before > marker && after <= marker) {
                plugin.display().warn(player, after);
                return;
            }
        }
    }

    private void applyOfflineDecay(Player player, FlyUser user) {
        PluginConfig config = plugin.config();
        if (!config.offlineDecay() || user.unlimited() || user.lastSeen() <= 0L || user.time() <= 0D) {
            return;
        }
        double offlineSeconds = (System.currentTimeMillis() - user.lastSeen()) / 1000D;
        if (offlineSeconds <= 0D) {
            return;
        }
        double loss = (offlineSeconds / config.offlineDecayThreshold()) * config.offlineDecayLoss();
        if (loss < 1D) {
            return;
        }
        double applied = -user.addTime(-loss);
        long rounded = Math.round(applied);
        if (rounded > 0) {
            plugin.messages().send(player, "grant.decay", plugin.messages().timePlaceholders(rounded));
        }
    }
}
