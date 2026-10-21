package org.Cry_Legende.sHTempFly.command;

import org.Cry_Legende.sHTempFly.SHTempFly;
import org.Cry_Legende.sHTempFly.config.Messages;
import org.Cry_Legende.sHTempFly.data.FlyUser;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /fly [on|off|time] [joueur]
 */
public final class FlyCommand implements CommandExecutor, TabCompleter {

    private static final String OTHERS_PERMISSION = "shtempfly.fly.others";

    private enum Action {
        TOGGLE, ENABLE, DISABLE, BALANCE
    }

    private final SHTempFly plugin;
    private final AdminCommand admin;

    public FlyCommand(SHTempFly plugin, AdminCommand admin) {
        this.plugin = plugin;
        this.admin = admin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        // Compatibilite avec l'ancien plugin : "/tf give <joueur> <temps>" et les autres
        // sous-commandes d'administration sont acceptees directement sur /fly et ses alias,
        // pour ne rien avoir a changer dans les plugins de votes / boxes / quetes.
        if (args.length > 0 && AdminCommand.isAdminSubCommand(args[0])
            && sender.hasPermission("shtempfly.admin")) {
            return admin.onCommand(sender, command, label, args);
        }
        if (args.length > 2) {
            plugin.messages().send(sender, "error.unknown-command");
            return true;
        }

        Action action = args.length > 0 ? parseAction(args[0]) : Action.TOGGLE;
        String targetName = null;
        if (args.length == 2) {
            if (action == null) {
                plugin.messages().send(sender, "error.unknown-command");
                return true;
            }
            targetName = args[1];
        } else if (args.length == 1 && action == null) {
            // "/fly <joueur>" : bascule le fly de quelqu'un d'autre.
            action = Action.TOGGLE;
            targetName = args[0];
        }

        if (targetName != null) {
            return other(sender, action, targetName);
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "error.player-only");
            return true;
        }
        return self(player, action);
    }

    private Action parseAction(String argument) {
        return switch (argument.toLowerCase(Locale.ROOT)) {
            case "on", "enable", "active" -> Action.ENABLE;
            case "off", "disable", "desactive" -> Action.DISABLE;
            case "time", "temps", "bal", "balance" -> Action.BALANCE;
            default -> null;
        };
    }

    private boolean self(Player player, Action action) {
        FlyUser user = plugin.flyManager().user(player.getUniqueId());
        if (user == null) {
            plugin.messages().send(player, "error.data-not-loaded");
            return true;
        }
        if (action == Action.BALANCE) {
            showBalance(player, user);
            return true;
        }
        if (!user.unlimited() && !plugin.config().temporaryCanToggle()) {
            plugin.messages().send(player, "fly.toggle-denied");
            showBalance(player, user);
            return true;
        }
        plugin.flyManager().toggle(player, requested(action));
        return true;
    }

    /**
     * "/fly <joueur>" et "/fly on|off|time <joueur>".
     */
    private boolean other(CommandSender sender, Action action, String targetName) {
        Messages messages = plugin.messages();
        if (!sender.hasPermission(OTHERS_PERMISSION) && !sender.hasPermission("shtempfly.admin")) {
            messages.send(sender, "error.no-permission");
            return true;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            messages.send(sender, "error.player-not-found", "{ARG}", targetName);
            return true;
        }
        FlyUser user = plugin.flyManager().user(target.getUniqueId());
        if (user == null) {
            messages.send(sender, "error.data-not-loaded");
            return true;
        }

        if (action == Action.BALANCE) {
            messages.sendList(sender, user.unlimited() ? "balance.other-unlimited" : "balance.other",
                Messages.merge(messages.timePlaceholders(user.timeSeconds()), "{PLAYER}", target.getName()));
            return true;
        }

        // Un admin peut forcer le fly d'un joueur temporaire, meme si celui-ci
        // n'a pas le droit de le faire lui-meme.
        Boolean state = plugin.flyManager().toggle(target, requested(action));
        if (state == null) {
            messages.send(sender, "error.data-not-loaded");
            return true;
        }
        if (sender != target) {
            messages.send(sender, state ? "fly.enabled-other" : "fly.disabled-other",
                "{PLAYER}", target.getName());
        }
        return true;
    }

    private Boolean requested(Action action) {
        return switch (action) {
            case ENABLE -> Boolean.TRUE;
            case DISABLE -> Boolean.FALSE;
            default -> null;
        };
    }

    private void showBalance(Player player, FlyUser user) {
        Messages messages = plugin.messages();
        String path = user.unlimited() ? "balance.self-unlimited" : "balance.self";
        messages.sendList(player, path,
            Messages.merge(messages.timePlaceholders(user.timeSeconds()), "{PLAYER}", player.getName()));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        boolean isAdmin = sender.hasPermission("shtempfly.admin");
        boolean canOthers = isAdmin || sender.hasPermission(OTHERS_PERMISSION);

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> options = new ArrayList<>(List.of("on", "off", "time"));
            if (isAdmin) {
                options.addAll(AdminCommand.SUB_COMMANDS);
            }
            if (canOthers) {
                options.addAll(playerNames());
            }
            return options.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        if (args.length == 2) {
            if (isAdmin && AdminCommand.isAdminSubCommand(args[0])) {
                return admin.onTabComplete(sender, command, label, args);
            }
            if (canOthers && parseAction(args[0]) != null) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return playerNames().stream()
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
            }
            return List.of();
        }
        return isAdmin && args.length > 2 && AdminCommand.isAdminSubCommand(args[0])
            ? admin.onTabComplete(sender, command, label, args)
            : List.of();
    }

    private List<String> playerNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }
}
