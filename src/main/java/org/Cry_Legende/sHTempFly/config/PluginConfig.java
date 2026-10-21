package org.Cry_Legende.sHTempFly.config;

import org.Cry_Legende.sHTempFly.SHTempFly;
import org.Cry_Legende.sHTempFly.zone.ZoneMode;
import org.bukkit.GameMode;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Vue typee de config.yml. Tout est resolu une seule fois au chargement pour que
 * la boucle de jeu ne fasse jamais de lecture YAML.
 */
public final class PluginConfig {

    public record WorldZone(ZoneMode mode, Map<String, ZoneMode> regions) {
    }

    private final SHTempFly plugin;

    private boolean debug;
    private int autosaveInterval;
    private boolean overrideCommands = true;

    private List<String> unlimitedPermissions = List.of("shtempfly.fly.unlimited");
    private boolean unlimitedResetOnJoin = true;
    private boolean temporaryCanToggle;
    private boolean autoEnableOnZoneExit = true;
    private int maxHeight = -1;
    private double maxHeightMargin = 3D;
    private boolean zoneCheckPerBlock;
    private float flySpeed = -1F;
    private boolean fallProtection = true;
    private int fallProtectionSeconds = 8;
    private boolean safeLogin = true;
    private boolean respectOtherPlugins = true;
    private List<String> blockedMetadata = List.of("shparkour.active");
    private Set<GameMode> ignoredGameModes = EnumSet.of(GameMode.SPECTATOR, GameMode.CREATIVE);

    private boolean drainWhenGrounded = true;
    private boolean drainInDeniedZones = true;
    private Map<String, Double> worldMultipliers = Map.of();
    private Map<String, Double> regionMultipliers = Map.of();
    private boolean offlineDecay = true;
    private double offlineDecayThreshold = 5;
    private double offlineDecayLoss = 1;

    private ZoneMode defaultMode = ZoneMode.ALLOW;
    private Map<String, ZoneMode> globalRegions = Map.of();
    private Map<String, WorldZone> worlds = Map.of();

    private boolean worldGuardEnabled = true;
    private boolean placeholderApiEnabled = true;
    private boolean skyblockEnabled = true;
    private String skyblockPrivilege = "FLY";
    private boolean skyblockDefaultWhenUnknown = true;
    private boolean skyblockApplyToUnlimited = true;

    private boolean warningEnabled = true;
    private List<Long> warningMarkers = List.of(60L, 10L, 3L, 2L, 1L);
    private int warningFadeIn = 5;
    private int warningStay = 30;
    private int warningFadeOut = 5;

    private boolean actionBarEnabled;
    private boolean actionBarShowUnlimited;

    private boolean bossBarEnabled;
    private BarColor bossBarColor = BarColor.YELLOW;
    private BarStyle bossBarStyle = BarStyle.SOLID;
    private double bossBarFullAt = 3600;
    private boolean bossBarShowUnlimited;

    private boolean autoImport = true;
    private String legacyDataFile = "plugins/TempFly/data.yml";

    public PluginConfig(SHTempFly plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        debug = config.getBoolean("general.debug", false);
        autosaveInterval = config.getInt("general.autosave-interval", 300);
        overrideCommands = config.getBoolean("general.override-commands", true);

        unlimitedPermissions = List.copyOf(config.getStringList("fly.unlimited-permissions"));
        if (unlimitedPermissions.isEmpty()) {
            unlimitedPermissions = List.of("shtempfly.fly.unlimited");
        }
        unlimitedResetOnJoin = config.getBoolean("fly.unlimited-reset-on-join", true);
        temporaryCanToggle = config.getBoolean("fly.temporary-can-toggle", false);
        autoEnableOnZoneExit = config.getBoolean("fly.auto-enable-on-zone-exit", true);
        maxHeight = config.getInt("fly.max-height", -1);
        maxHeightMargin = Math.max(0D, config.getDouble("fly.max-height-margin", 3D));
        zoneCheckPerBlock = config.getBoolean("fly.zone-check-per-block", false);
        flySpeed = (float) config.getDouble("fly.speed", -1D);
        fallProtection = config.getBoolean("fly.fall-damage-protection.enabled", true);
        fallProtectionSeconds = config.getInt("fly.fall-damage-protection.seconds", 8);
        safeLogin = config.getBoolean("fly.fall-damage-protection.safe-login", true);
        respectOtherPlugins = config.getBoolean("fly.respect-other-plugins", true);
        blockedMetadata = config.isSet("fly.blocked-metadata")
            ? List.copyOf(config.getStringList("fly.blocked-metadata"))
            : List.of("shparkour.active");

        if (config.isSet("fly.ignored-gamemodes")) {
            Set<GameMode> modes = EnumSet.noneOf(GameMode.class);
            for (String raw : config.getStringList("fly.ignored-gamemodes")) {
                try {
                    modes.add(GameMode.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Gamemode inconnu dans fly.ignored-gamemodes : " + raw);
                }
            }
            ignoredGameModes = modes;
        } else {
            // Section absente : on garde la protection minigames par defaut.
            ignoredGameModes = EnumSet.of(GameMode.SPECTATOR, GameMode.CREATIVE);
        }

        drainWhenGrounded = config.getBoolean("timer.drain-when-grounded", true);
        drainInDeniedZones = config.getBoolean("timer.drain-in-denied-zones", true);
        worldMultipliers = readDoubles(config.getConfigurationSection("timer.drain-multiplier.worlds"), false);
        regionMultipliers = readDoubles(config.getConfigurationSection("timer.drain-multiplier.regions"), true);
        offlineDecay = config.getBoolean("timer.offline-decay.enabled", true);
        offlineDecayThreshold = Math.max(0.05D, config.getDouble("timer.offline-decay.threshold-seconds", 5));
        offlineDecayLoss = Math.max(0D, config.getDouble("timer.offline-decay.seconds-lost", 1));

        defaultMode = ZoneMode.parse(config.getString("zones.default-mode"), ZoneMode.ALLOW);
        globalRegions = readModes(config.getConfigurationSection("zones.regions"));
        worlds = readWorlds(config.getConfigurationSection("zones.worlds"));

        worldGuardEnabled = config.getBoolean("hooks.worldguard.enabled", true);
        placeholderApiEnabled = config.getBoolean("hooks.placeholderapi.enabled", true);
        skyblockEnabled = config.getBoolean("hooks.superiorskyblock.enabled", true);
        skyblockPrivilege = config.getString("hooks.superiorskyblock.privilege-name", "FLY");
        skyblockDefaultWhenUnknown = config.getBoolean("hooks.superiorskyblock.default-when-unknown", true);
        skyblockApplyToUnlimited = config.getBoolean("hooks.superiorskyblock.apply-to-unlimited", true);

        warningEnabled = config.getBoolean("display.warning.enabled", true);
        List<Long> markers = new ArrayList<>();
        for (int value : config.getIntegerList("display.warning.seconds")) {
            if (value > 0) {
                markers.add((long) value);
            }
        }
        markers.sort(Collections.reverseOrder());
        warningMarkers = List.copyOf(markers);
        warningFadeIn = config.getInt("display.warning.fade-in", 5);
        warningStay = config.getInt("display.warning.stay", 30);
        warningFadeOut = config.getInt("display.warning.fade-out", 5);

        actionBarEnabled = config.getBoolean("display.action-bar.enabled", false);
        actionBarShowUnlimited = config.getBoolean("display.action-bar.show-unlimited", false);

        bossBarEnabled = config.getBoolean("display.boss-bar.enabled", false);
        bossBarColor = parseEnum(BarColor.class, config.getString("display.boss-bar.color"), BarColor.YELLOW);
        bossBarStyle = parseEnum(BarStyle.class, config.getString("display.boss-bar.style"), BarStyle.SOLID);
        bossBarFullAt = Math.max(1D, config.getDouble("display.boss-bar.full-at-seconds", 3600));
        bossBarShowUnlimited = config.getBoolean("display.boss-bar.show-unlimited", false);

        autoImport = config.getBoolean("migration.auto-import", true);
        legacyDataFile = config.getString("migration.legacy-data-file", "plugins/TempFly/data.yml");
    }

    private <T extends Enum<T>> T parseEnum(Class<T> type, String raw, T fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Valeur inconnue '" + raw + "' pour " + type.getSimpleName());
            return fallback;
        }
    }

    private Map<String, Double> readDoubles(ConfigurationSection section, boolean lowercaseKeys) {
        if (section == null) {
            return Map.of();
        }
        Map<String, Double> out = new HashMap<>();
        for (String key : section.getKeys(false)) {
            out.put(lowercaseKeys ? key.toLowerCase(Locale.ROOT) : key, section.getDouble(key, 1D));
        }
        return Map.copyOf(out);
    }

    private Map<String, ZoneMode> readModes(ConfigurationSection section) {
        if (section == null) {
            return Map.of();
        }
        Map<String, ZoneMode> out = new HashMap<>();
        for (String key : section.getKeys(false)) {
            out.put(key.toLowerCase(Locale.ROOT), ZoneMode.parse(section.getString(key), ZoneMode.DENY));
        }
        return Map.copyOf(out);
    }

    private Map<String, WorldZone> readWorlds(ConfigurationSection section) {
        if (section == null) {
            return Map.of();
        }
        Map<String, WorldZone> out = new HashMap<>();
        for (String worldName : section.getKeys(false)) {
            ConfigurationSection worldSection = section.getConfigurationSection(worldName);
            if (worldSection == null) {
                continue;
            }
            ZoneMode mode = ZoneMode.parse(worldSection.getString("mode"), defaultMode);
            Map<String, ZoneMode> regions = readModes(worldSection.getConfigurationSection("regions"));
            out.put(worldName, new WorldZone(mode, regions));
        }
        return Map.copyOf(out);
    }

    public boolean debug() {
        return debug;
    }

    public int autosaveInterval() {
        return autosaveInterval;
    }

    public boolean overrideCommands() {
        return overrideCommands;
    }

    public List<String> unlimitedPermissions() {
        return unlimitedPermissions;
    }

    public boolean unlimitedResetOnJoin() {
        return unlimitedResetOnJoin;
    }

    public boolean temporaryCanToggle() {
        return temporaryCanToggle;
    }

    public boolean autoEnableOnZoneExit() {
        return autoEnableOnZoneExit;
    }

    public int maxHeight() {
        return maxHeight;
    }

    /**
     * Nombre de blocs a redescendre sous la hauteur maximale avant de recuperer le vol.
     */
    public double maxHeightMargin() {
        return maxHeightMargin;
    }

    /**
     * True pour reevaluer le vol a chaque bloc parcouru plutot qu'a chaque chunk.
     */
    public boolean zoneCheckPerBlock() {
        return zoneCheckPerBlock;
    }

    public float flySpeed() {
        return flySpeed;
    }

    public boolean fallProtection() {
        return fallProtection;
    }

    public int fallProtectionSeconds() {
        return fallProtectionSeconds;
    }

    public boolean safeLogin() {
        return safeLogin;
    }

    public boolean respectOtherPlugins() {
        return respectOtherPlugins;
    }

    public List<String> blockedMetadata() {
        return blockedMetadata;
    }

    public Set<GameMode> ignoredGameModes() {
        return ignoredGameModes;
    }

    public boolean drainWhenGrounded() {
        return drainWhenGrounded;
    }

    public boolean drainInDeniedZones() {
        return drainInDeniedZones;
    }

    public double worldMultiplier(String world) {
        return worldMultipliers.getOrDefault(world, 1D);
    }

    public double regionMultiplier(String region) {
        return regionMultipliers.getOrDefault(region, 1D);
    }

    public boolean hasRegionMultipliers() {
        return !regionMultipliers.isEmpty();
    }

    public boolean offlineDecay() {
        return offlineDecay;
    }

    public double offlineDecayThreshold() {
        return offlineDecayThreshold;
    }

    public double offlineDecayLoss() {
        return offlineDecayLoss;
    }

    public ZoneMode defaultMode() {
        return defaultMode;
    }

    public Map<String, ZoneMode> globalRegions() {
        return globalRegions;
    }

    public WorldZone world(String name) {
        return worlds.get(name);
    }

    public boolean worldGuardEnabled() {
        return worldGuardEnabled;
    }

    public boolean placeholderApiEnabled() {
        return placeholderApiEnabled;
    }

    public boolean skyblockEnabled() {
        return skyblockEnabled;
    }

    public String skyblockPrivilege() {
        return skyblockPrivilege;
    }

    public boolean skyblockDefaultWhenUnknown() {
        return skyblockDefaultWhenUnknown;
    }

    public boolean skyblockApplyToUnlimited() {
        return skyblockApplyToUnlimited;
    }

    public boolean warningEnabled() {
        return warningEnabled;
    }

    public List<Long> warningMarkers() {
        return warningMarkers;
    }

    public int warningFadeIn() {
        return warningFadeIn;
    }

    public int warningStay() {
        return warningStay;
    }

    public int warningFadeOut() {
        return warningFadeOut;
    }

    public boolean actionBarEnabled() {
        return actionBarEnabled;
    }

    public boolean actionBarShowUnlimited() {
        return actionBarShowUnlimited;
    }

    public boolean bossBarEnabled() {
        return bossBarEnabled;
    }

    public BarColor bossBarColor() {
        return bossBarColor;
    }

    public BarStyle bossBarStyle() {
        return bossBarStyle;
    }

    public double bossBarFullAt() {
        return bossBarFullAt;
    }

    public boolean bossBarShowUnlimited() {
        return bossBarShowUnlimited;
    }

    public boolean autoImport() {
        return autoImport;
    }

    public String legacyDataFile() {
        return legacyDataFile;
    }
}
