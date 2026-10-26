package org.Cry_Legende.sHTempFly.command;

import org.Cry_Legende.sHTempFly.SHTempFly;
import org.Cry_Legende.sHTempFly.config.Messages;
import org.Cry_Legende.sHTempFly.data.FlyUser;
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
import java.util.Set;

/**
 * /shtempfly give|remove|set|giveall|check|reload|migrate
 */
public final class AdminCommand implements CommandExecutor, TabCompleter {

    public static final List<String> SUB_COMMANDS =
        List.of("give", "remove", "set", "giveall", "check", "reload", "migrate");

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

    private final SHTempFly plugin;

    public AdminCommand(SHTempFly plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("shtempfly.admin")) {
            plugin.messages().send(sender, "error.no-permission");
            return true;
        }
        if (args.length == 0) {
            plugin.messages().sendList(sender, "admin.help");
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
            default -> plugin.messages().sendList(sender, "admin.help");
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
            return;
        }

        OfflinePlayer offline = resolveOffline(args[1]);
        if (offline == null) {
            plugin.messages().send(sender, "error.player-not-found", "{ARG}", args[1]);
            return;
        }
        plugin.storage().addTimeOffline(offline.getUniqueId(), offline.getName(),
                operation == Operation.SET ? seconds : amount, operation == Operation.SET)
            .thenAccept(applied -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (applied) {
                    feedback(sender, operation, args[1], seconds);
                } else {
                    plugin.messages().send(sender, "grant.target-unlimited", "{PLAYER}", args[1]);
                }
            }));
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
        plugin.messages().send(sender, "grant.given-all", plugin.messages().timePlaceholders(seconds));
    }

    private void check(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().sendList(sender, "admin.help");
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

        OfflinePlayer offline = resolveOffline(args[1]);
        if (offline == null) {
            messages.send(sender, "error.player-not-found", "{ARG}", args[1]);
            return;
        }
        plugin.storage().readTime(offline.getUniqueId()).thenAccept(time ->
            Bukkit.getScheduler().runTask(plugin, () -> messages.sendList(sender, "balance.other",
                Messages.merge(messages.timePlaceholders((long) Math.floor(time)),
                    "{PLAYER}", args[1]))));
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
        if (!sender.hasPermission("shtempfly.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUB_COMMANDS.stream().filter(value -> value.startsWith(prefix)).toList();
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
