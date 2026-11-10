package org.Cry_Legende.sHTempFly;

import org.Cry_Legende.sHTempFly.command.AdminCommand;
import org.Cry_Legende.sHTempFly.command.FlyCommand;
import org.Cry_Legende.sHTempFly.config.Messages;
import org.Cry_Legende.sHTempFly.config.PluginConfig;
import org.Cry_Legende.sHTempFly.data.FlyStorage;
import org.Cry_Legende.sHTempFly.data.FlyStore;
import org.Cry_Legende.sHTempFly.data.LegacyImporter;
import org.Cry_Legende.sHTempFly.display.DisplayService;
import org.Cry_Legende.sHTempFly.fly.FlyListener;
import org.Cry_Legende.sHTempFly.fly.FlyManager;
import org.Cry_Legende.sHTempFly.hook.PlaceholderHook;
import org.Cry_Legende.sHTempFly.hook.SkyblockHook;
import org.Cry_Legende.sHTempFly.network.FlyDatabase;
import org.Cry_Legende.sHTempFly.network.NetworkFlyStorage;
import org.Cry_Legende.sHTempFly.network.NetworkLink;
import org.Cry_Legende.sHTempFly.network.ShNetworkLink;
import org.Cry_Legende.sHTempFly.zone.ZoneService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

public final class SHTempFly extends JavaPlugin {

    private PluginConfig config;
    private Messages messages;
    private FlyStore storage;
    // Non nul seulement avec SHNetwork : le temps de vol de chaque joueur est dans la base partagée
    private NetworkFlyStorage network;
    private ZoneService zones;
    private DisplayService display;
    private FlyManager flyManager;
    private SkyblockHook skyblock;
    private PlaceholderHook placeholders;

    @Override
    public void onEnable() {
        config = new PluginConfig(this);
        config.load();

        messages = new Messages(this);
        messages.load();

        network = openNetwork();
        if (network != null) {
            storage = network;
        } else {
            FlyStorage local = new FlyStorage(this);
            try {
                local.init();
            } catch (SQLException ex) {
                getLogger().log(Level.SEVERE, "Impossible d'initialiser la base de donnees, arret du plugin.", ex);
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            storage = local;
        }

        zones = new ZoneService(this);
        zones.setup();

        display = new DisplayService(this);
        setupSkyblock();
        setupPlaceholders();

        flyManager = new FlyManager(this);
        getServer().getPluginManager().registerEvents(new FlyListener(this), this);
        AdminCommand adminCommand = new AdminCommand(this);
        register("fly", new FlyCommand(this, adminCommand));
        register("shtempfly", adminCommand);
        if (network != null) {
            network.start(player -> flyManager.handleQuit(player));
        }
        flyManager.start();

        autoImport();
    }

    @Override
    public void onDisable() {
        if (flyManager != null) {
            flyManager.stop();
        }
        if (display != null) {
            display.shutdown();
        }
        if (placeholders != null) {
            placeholders.unregister();
        }
        if (storage != null) {
            storage.close();
        }
    }

    public void reloadEverything() {
        config.load();
        messages.load();
        zones.setup();
        setupSkyblock();
        flyManager.reschedule();
        for (Player player : Bukkit.getOnlinePlayers()) {
            flyManager.refresh(player);
        }
    }

    public void runMigration(CommandSender sender) {
        File file = new File(config.legacyDataFile());
        new LegacyImporter(this).importFrom(file, imported -> {
            if (imported < 0) {
                messages.send(sender, "admin.migrate-missing", "{ARG}", file.getPath());
                return;
            }
            storage.setMetaFlag(LegacyImporter.META_KEY, String.valueOf(System.currentTimeMillis()));
            messages.send(sender, "admin.migrated", "{AMOUNT}", String.valueOf(imported));
        });
    }

    public PluginConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public FlyStore storage() {
        return storage;
    }

    /**
     * Null sans SHNetwork (ou si 'reseau.actif' vaut false) : le temps de vol reste alors dans le SQLite du serveur.
     */
    public NetworkFlyStorage network() {
        return network;
    }

    public ZoneService zones() {
        return zones;
    }

    public DisplayService display() {
        return display;
    }

    public FlyManager flyManager() {
        return flyManager;
    }

    /**
     * Null si SuperiorSkyblock2 est absent ou si le hook est desactive.
     */
    public SkyblockHook skyblock() {
        return skyblock;
    }

    /**
     * Passe en mode réseau si SHNetwork est installé et 'reseau.actif' est vrai. Les classes qui touchent à SHNetwork
     * ne sont chargées qu'ici : sans lui, le plugin se comporte exactement comme avant.
     */
    private NetworkFlyStorage openNetwork() {
        if (!getConfig().getBoolean("reseau.actif", true)) {
            return null;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("SHNetwork")) {
            return null;
        }
        FlyDatabase db = null;
        try {
            NetworkLink link = ShNetworkLink.open();
            db = new FlyDatabase(
                getConfig().getString("reseau.base-de-donnees.url", ""),
                getConfig().getString("reseau.base-de-donnees.utilisateur", ""),
                getConfig().getString("reseau.base-de-donnees.mot-de-passe", ""),
                Math.max(250, getConfig().getInt("reseau.base-de-donnees.delai-ms", 3000)));
            db.createTable();
            Duration wait = Duration.ofSeconds(Math.max(1, getConfig().getInt("reseau.attente-max-secondes", 10)));
            getLogger().info("SHNetwork detecte : le temps de vol est dans la base partagee.");
            return new NetworkFlyStorage(this, db, link, wait);
        } catch (Exception | LinkageError e) {
            if (db != null) {
                db.close();
            }
            getLogger().log(Level.SEVERE, "SHNetwork present mais inutilisable, temps de vol local a ce serveur : " + e);
            return null;
        }
    }

    /**
     * /shtempfly netimport : copie les joueurs du SQLite local (data.db) dans la base partagée, sans rien écraser.
     */
    public void runNetworkImport(CommandSender sender) {
        if (network == null) {
            messages.send(sender, "admin.network-only");
            return;
        }
        File file = new File(getDataFolder(), "data.db");
        if (!file.isFile()) {
            messages.send(sender, "admin.netimport-missing", "{ARG}", file.getPath());
            return;
        }
        network.importLocal(file, result -> {
            if (result == null) {
                messages.send(sender, "admin.netimport-failed");
                return;
            }
            messages.send(sender, "admin.netimport-done",
                "{AMOUNT}", String.valueOf(result[0]), "{PRESENT}", String.valueOf(result[1]));
        });
    }

    private void autoImport() {
        if (!config.autoImport()) {
            return;
        }
        storage.metaFlag(LegacyImporter.META_KEY).thenAccept(alreadyDone ->
            Bukkit.getScheduler().runTask(this, () -> {
                if (alreadyDone || !isEnabled()) {
                    return;
                }
                File file = new File(config.legacyDataFile());
                if (!file.isFile()) {
                    storage.setMetaFlag(LegacyImporter.META_KEY, "no-legacy-data");
                    return;
                }
                getLogger().info("Import de l'ancien TempFly depuis " + file.getPath() + "...");
                new LegacyImporter(this).importFrom(file, imported -> {
                    storage.setMetaFlag(LegacyImporter.META_KEY, String.valueOf(System.currentTimeMillis()));
                    getLogger().info("Import termine : " + imported + " joueurs.");
                });
            }));
    }

    private void setupSkyblock() {
        skyblock = null;
        if (!config.skyblockEnabled() || getServer().getPluginManager().getPlugin("SuperiorSkyblock2") == null) {
            return;
        }
        try {
            SkyblockHook hook = new SkyblockHook(this);
            if (hook.available()) {
                skyblock = hook;
                getLogger().info("SuperiorSkyblock2 detecte, permission d'ile '"
                    + config.skyblockPrivilege() + "' active.");
            }
        } catch (Throwable throwable) {
            getLogger().warning("Hook SuperiorSkyblock2 indisponible : " + throwable);
        }
    }

    private void setupPlaceholders() {
        if (!config.placeholderApiEnabled()
            || getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        try {
            placeholders = new PlaceholderHook(this);
            placeholders.register();
            getLogger().info("PlaceholderAPI detecte, placeholders %shtempfly_...% disponibles.");
        } catch (Throwable throwable) {
            placeholders = null;
            getLogger().warning("Hook PlaceholderAPI indisponible : " + throwable);
        }
    }

    private void register(String name, Object handler) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("Commande '" + name + "' absente de plugin.yml");
            return;
        }
        command.setExecutor((CommandExecutor) handler);
        command.setTabCompleter((TabCompleter) handler);
        if (config.overrideCommands()) {
            takeOver(command);
        }
    }

    /**
     * Reprend le label et les alias a un plugin qui les avait deja enregistres
     * (Essentials, ancien TempFly...). L'autre plugin reste joignable via son prefixe,
     * par exemple /essentials:fly.
     */
    private void takeOver(PluginCommand command) {
        if (!(Bukkit.getCommandMap() instanceof SimpleCommandMap map)) {
            return;
        }
        Map<String, Command> known = map.getKnownCommands();
        List<String> labels = new ArrayList<>();
        labels.add(command.getName());
        labels.addAll(command.getAliases());
        for (String label : labels) {
            String key = label.toLowerCase(Locale.ROOT);
            Command current = known.get(key);
            if (current == command) {
                continue;
            }
            known.put(key, command);
            if (current != null) {
                getLogger().info("Commande /" + key + " reprise a "
                    + (current instanceof PluginCommand other ? other.getPlugin().getName() : current.getLabel()));
            }
        }
    }
}
