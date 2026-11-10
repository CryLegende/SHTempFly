package org.Cry_Legende.sHTempFly.command;

import org.Cry_Legende.sHTempFly.SHTempFly;
import org.Cry_Legende.sHTempFly.config.Messages;
import org.Cry_Legende.sHTempFly.data.FlyUser;
import org.Cry_Legende.sHTempFly.network.NetworkFlyStorage;
import org.Cry_Legende.sHTempFly.network.NetworkLink;
import org.Cry_Legende.sHTempFly.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * /shtempfly give|remove|set|giveall|check|reload|migrate (et netimport avec SHNetwork)
 */
public final class AdminCommand implements CommandExecutor, TabCompleter {

    public static final List<String> SUB_COMMANDS =
        List.of("give", "remove", "set", "giveall", "check", "reload", "migrate");

    /**
     * Mode réseau : netimport copie le SQLite local dans la base partagée.
     */
    private static final List<String> NETWORK_SUB_COMMANDS =
        List.of("give", "remove", "set", "giveall", "check", "reload", "migrate", "netimport");

    /**
     * Tous les mots reconnus, alias de l'ancien TempFly compris.
     */
    private static final Set<String> ALL_SUB_COMMANDS = Set.of(
        "give", "add", "remove", "take", "set", "giveall", "addall",
        "check", "look", "reload", "migrate", "import");

    /**
     * True si le premier argument est une sous-commande d'administration.
     */
    public static boolean isAdminSubCommand(String argument) {
        return argument != null && ALL_SUB_COMMANDS.contains(argument.toLowerCase(Locale.ROOT));
    }

    private static final String ADMIN_PERMISSION = "shtempfly.admin";
    /** Donne uniquement "check" : pour les modérateurs, sans le reste de l'administration. */
    private static final String CHECK_PERMISSION = "shtempfly.admin.check";

    private static boolean isCheck(String subCommand) {
        String sub = subCommand.toLowerCase(Locale.ROOT);
        return sub.equals("check") || sub.equals("look");
    }

    /**
     * True si l'expéditeur peut utiliser cette sous-commande : shtempfly.admin pour toutes,
     * shtempfly.admin.check pour check seulement.
     */
    public static boolean canUse(CommandSender sender, String subCommand) {
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            return true;
        }
        return subCommand != null && isCheck(subCommand) && sender.hasPermission(CHECK_PERMISSION);
    }

    private final SHTempFly plugin;

    public AdminCommand(SHTempFly plugin) {
        this.plugin = plugin;
    }

    private void help(CommandSender sender) {
        plugin.messages().sendList(sender, sender.hasPermission(ADMIN_PERMISSION) ? "admin.help" : "admin.help-check");
        if (plugin.network() != null && sender.hasPermission(ADMIN_PERMISSION)) {
            plugin.messages().sendList(sender, "admin.help-network");
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION) && !sender.hasPermission(CHECK_PERMISSION)) {
            plugin.messages().send(sender, "error.no-permission");
            return true;
        }
        if (args.length == 0) {
            help(sender);
            return true;
        }
        if (!canUse(sender, args[0])) {
            // check seul : les autres sous-commandes restent invisibles, comme pour un joueur sans droit
            plugin.messages().send(sender, "error.no-permission");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give", "add" -> modify(sender, args, Operation.ADD);
            case "remove", "take" -> modify(sender, args, Operation.REMOVE);
            case "set" -> modify(sender, args, Operation.SET);
            case "giveall", "addall" -> giveAll(sender, args);
            case "check", "look" -> check(sender, args);
            case "reload" -> {
                plugin.reloadEverything();
                plugin.messages().send(sender, "admin.reloaded");
            }
            case "migrate", "import" -> plugin.runMigration(sender);
            case "netimport" -> plugin.runNetworkImport(sender);
            default -> help(sender);
        }
        return true;
    }

    private enum Operation {
        ADD, REMOVE, SET
    }

    private void modify(CommandSender sender, String[] args, Operation operation) {
        if (args.length < 3) {
            plugin.messages().sendList(sender, "admin.help");
            return;
        }
        String raw = timeArgument(args, 2);
        long seconds = TimeUtil.parseSeconds(raw);
        if (seconds < 0) {
            plugin.messages().send(sender, "error.invalid-time", "{ARG}", raw);
            return;
        }
        double amount = operation == Operation.REMOVE ? -seconds : seconds;

        Player online = Bukkit.getPlayerExact(args[1]);
        if (online != null) {
            // Donnees encore en chargement (recompense donnee a la connexion) : le changement attend leur arrivee
            boolean applied = operation == Operation.SET
                ? plugin.flyManager().grant(online, seconds, true, false)
                : plugin.flyManager().grant(online, amount, false, true);
            if (!applied) {
                plugin.messages().send(sender, "grant.target-unlimited", "{PLAYER}", online.getName());
                return;
            }
            feedback(sender, operation, online.getName(), seconds);
            plugin.flyManager().writeThrough(online);
            return;
        }

        NetworkFlyStorage network = plugin.network();
        if (network != null && forward(sender, network, operation, args[1], raw)) {
            return;
        }

        OfflinePlayer offline = resolveOffline(args[1]);
        if (offline != null) {
            applyOffline(sender, operation, offline.getUniqueId(), offline.getName(), args[1], seconds, amount);
            return;
        }
        if (network == null) {
            plugin.messages().send(sender, "error.player-not-found", "{ARG}", args[1]);
            return;
        }
        // Un joueur jamais venu sur ce serveur n'est pas dans son cache : la base partagée le connaît par son pseudo
        network.findByName(args[1]).whenComplete((known, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.messages().send(sender, "error.database");
            } else if (known.isEmpty()) {
                plugin.messages().send(sender, "error.player-not-found", "{ARG}", args[1]);
            } else {
                applyOffline(sender, operation, known.get().uuid(), known.get().name(), args[1], seconds, amount);
            }
        }));
    }

    /**
     * Modification d'un joueur connecté nulle part. Mode réseau : ajout atomique en base partagée.
     */
    private void applyOffline(CommandSender sender, Operation operation, UUID uuid, String storedName, String shownName,
                              long seconds, double amount) {
        plugin.storage().addTimeOffline(uuid, storedName,
                operation == Operation.SET ? seconds : amount, operation == Operation.SET)
            .thenAccept(applied -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (applied == null) {
                    plugin.messages().send(sender, "error.database");
                } else if (applied) {
                    feedback(sender, operation, shownName, seconds);
                } else {
                    plugin.messages().send(sender, "grant.target-unlimited", "{PLAYER}", shownName);
                }
            }));
    }

    /**
     * Mode réseau : le joueur est connecté sur un autre serveur, la commande y est exécutée (là où se trouve son temps
     * en mémoire), une seule fois.
     *
     * @return vrai si le joueur est sur un autre serveur (la commande lui est transmise)
     */
    private boolean forward(CommandSender sender, NetworkFlyStorage network, Operation operation, String target, String time) {
        Optional<NetworkLink.Remote> remote = network.remote(target);
        if (remote.isEmpty()) {
            return false;
        }
        String sub = switch (operation) {
            case ADD -> "give";
            case REMOVE -> "remove";
            case SET -> "set";
        };
        NetworkLink.Remote player = remote.get();
        network.link().runFor(player.name(), "shtempfly " + sub + " " + player.name() + " " + time);
        plugin.messages().send(sender, "admin.forwarded", "{PLAYER}", player.name(), "{SERVER}", player.server());
        return true;
    }

    private void giveAll(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().sendList(sender, "admin.help");
            return;
        }
        String raw = timeArgument(args, 1);
        long seconds = TimeUtil.parseSeconds(raw);
        if (seconds < 0) {
            plugin.messages().send(sender, "error.invalid-time", "{ARG}", raw);
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.flyManager().grant(player, seconds, false, true);
        }
        NetworkFlyStorage network = plugin.network();
        if (network != null) {
            // les joueurs des autres serveurs reçoivent le temps là où ils sont
            plugin.flyManager().saveDirty();
            NetworkLink link = network.link();
            for (NetworkLink.Remote remote : link.online()) {
                if (!remote.server().equalsIgnoreCase(link.serverName())) {
                    link.runFor(remote.name(), "shtempfly give " + remote.name() + " " + raw);
                }
            }
        }
        plugin.messages().send(sender, "grant.given-all", plugin.messages().timePlaceholders(seconds));
    }

    private void check(CommandSender sender, String[] args) {
        if (args.length < 2) {
            help(sender);
            return;
        }
        Messages messages = plugin.messages();
        Player online = Bukkit.getPlayerExact(args[1]);
        if (online != null) {
            FlyUser user = plugin.flyManager().user(online.getUniqueId());
            if (user == null) {
                messages.send(sender, "error.data-not-loaded");
                return;
            }
            messages.sendList(sender, user.unlimited() ? "balance.other-unlimited" : "balance.other",
                Messages.merge(messages.timePlaceholders(user.timeSeconds()), "{PLAYER}", online.getName()));
            return;
        }

        NetworkFlyStorage network = plugin.network();
        if (network != null) {
            // joueur d'un autre serveur : la base fait foi (son temps y est écrit à chaque sauvegarde automatique)
            Optional<NetworkLink.Remote> remote = network.remote(args[1]);
            if (remote.isPresent()) {
                showTime(sender, remote.get().uuid(), remote.get().name());
                return;
            }
        }

        OfflinePlayer offline = resolveOffline(args[1]);
        if (offline != null) {
            showTime(sender, offline.getUniqueId(), args[1]);
            return;
        }
        if (network == null) {
            messages.send(sender, "error.player-not-found", "{ARG}", args[1]);
            return;
        }
        network.findByName(args[1]).whenComplete((known, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                messages.send(sender, "error.database");
            } else if (known.isEmpty()) {
                messages.send(sender, "error.player-not-found", "{ARG}", args[1]);
            } else {
                showTime(sender, known.get().uuid(), args[1]);
            }
        }));
    }

    private void showTime(CommandSender sender, UUID uuid, String shownName) {
        Messages messages = plugin.messages();
        plugin.storage().readTime(uuid).thenAccept(time ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (time == null) {
                    messages.send(sender, "error.database");
                    return;
                }
                messages.sendList(sender, "balance.other",
                    Messages.merge(messages.timePlaceholders((long) Math.floor(time)), "{PLAYER}", shownName));
            }));
    }

    private void feedback(CommandSender sender, Operation operation, String target, long seconds) {
        String path = switch (operation) {
            case ADD -> "grant.given-other";
            case REMOVE -> "grant.removed-other";
            case SET -> "grant.set-other";
        };
        plugin.messages().send(sender, path,
            Messages.merge(plugin.messages().timePlaceholders(seconds), "{PLAYER}", target));
    }

    /**
     * L'ancien TempFly separait la quantite de son unite ("/tf give Cry 30 minutes").
     * On accepte donc les deux ecritures : "30m" et "30 m".
     */
    private static String timeArgument(String[] args, int index) {
        if (args.length > index + 1) {
            String joined = args[index] + args[index + 1];
            if (TimeUtil.parseSeconds(joined) >= 0) {
                return joined;
            }
        }
        return args[index];
    }

    private OfflinePlayer resolveOffline(String name) {
        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(name);
        return offline != null && offline.getUniqueId() != null ? offline : null;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION) && !sender.hasPermission(CHECK_PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return (plugin.network() != null ? NETWORK_SUB_COMMANDS : SUB_COMMANDS).stream()
                .filter(value -> value.startsWith(prefix) && canUse(sender, value))
                .toList();
        }
        if (args.length >= 2 && !canUse(sender, args[0])) {
            return List.of();
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("giveall") || sub.equals("addall")) {
                return List.of("30m", "1h", "1j");
            }
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    names.add(player.getName());
                }
            }
            return names;
        }
        if (args.length == 3) {
            return List.of("30m", "1h", "1j");
        }
        return List.of();
    }
}
