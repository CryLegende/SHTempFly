package org.Cry_Legende.sHTempFly;

import org.Cry_Legende.sHTempFly.command.AdminCommand;
import org.Cry_Legende.sHTempFly.command.FlyCommand;
import org.Cry_Legende.sHTempFly.config.Messages;
import org.Cry_Legende.sHTempFly.config.PluginConfig;
import org.Cry_Legende.sHTempFly.data.FlyStorage;
import org.Cry_Legende.sHTempFly.data.LegacyImporter;
import org.Cry_Legende.sHTempFly.display.DisplayService;
import org.Cry_Legende.sHTempFly.fly.FlyListener;
import org.Cry_Legende.sHTempFly.fly.FlyManager;
import org.Cry_Legende.sHTempFly.hook.PlaceholderHook;
import org.Cry_Legende.sHTempFly.hook.SkyblockHook;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

public final class SHTempFly extends JavaPlugin {

    private PluginConfig config;
    private Messages messages;
    private FlyStorage storage;
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

        storage = new FlyStorage(this);
        try {
            storage.init();
        } catch (SQLException ex) {
            getLogger().log(Level.SEVERE, "Impossible d'initialiser la base de donnees, arret du plugin.", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
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

    public FlyStorage storage() {
        return storage;
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
