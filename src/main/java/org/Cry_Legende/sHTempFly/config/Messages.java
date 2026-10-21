package org.Cry_Legende.sHTempFly.config;

import org.Cry_Legende.sHTempFly.SHTempFly;
import org.Cry_Legende.sHTempFly.util.Text;
import org.Cry_Legende.sHTempFly.util.TimeUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Charge messages.yml et fabrique les textes envoyes aux joueurs.
 */
public final class Messages {

    private final SHTempFly plugin;

    private YamlConfiguration config;

    private String prefixInfo = "";
    private String prefixSuccess = "";
    private String prefixWarning = "";
    private String prefixError = "";

    private String timeFormat = "{QUANTITY}{UNIT}";
    private String timeSeparator = " ";
    private String unitSeconds = "s";
    private String unitMinutes = "m";
    private String unitHours = "h";
    private String unitDays = "j";
    private String timeInfinite = "Infini";
    private String timeZero = "0s";

    public Messages(SHTempFly plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);

        InputStream defaults = plugin.getResource("messages.yml");
        if (defaults != null) {
            config.setDefaults(YamlConfiguration.loadConfiguration(
                new InputStreamReader(defaults, StandardCharsets.UTF_8)));
        }

        prefixInfo = config.getString("prefix.info", "");
        prefixSuccess = config.getString("prefix.success", "");
        prefixWarning = config.getString("prefix.warning", "");
        prefixError = config.getString("prefix.error", "");

        timeFormat = config.getString("time.format", "{QUANTITY}{UNIT}");
        timeSeparator = config.getString("time.separator", " ");
        unitSeconds = config.getString("time.units.seconds", "s");
        unitMinutes = config.getString("time.units.minutes", "m");
        unitHours = config.getString("time.units.hours", "h");
        unitDays = config.getString("time.units.days", "j");
        timeInfinite = config.getString("time.infinite", "Infini");
        timeZero = config.getString("time.zero", "0s");
    }

    /**
     * Recupere un message brut, colore, avec les placeholders remplaces.
     * Les placeholders sont passes par paires cle/valeur : "{PLAYER}", "Steve".
     */
    public String get(String path, String... placeholders) {
        String raw = config.getString(path);
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return Text.color(applyPrefixes(replace(raw, placeholders)));
    }

    public List<String> getList(String path, String... placeholders) {
        List<String> raw = config.getStringList(path);
        List<String> out = new ArrayList<>(raw.size());
        for (String line : raw) {
            out.add(Text.color(applyPrefixes(replace(line, placeholders))));
        }
        return out;
    }

    public void send(CommandSender sender, String path, String... placeholders) {
        String message = get(path, placeholders);
        if (!message.isEmpty()) {
            sender.sendMessage(message);
        }
    }

    public void sendList(CommandSender sender, String path, String... placeholders) {
        for (String line : getList(path, placeholders)) {
            if (!line.isEmpty()) {
                sender.sendMessage(line);
            }
        }
    }

    /**
     * Formate une duree en n'affichant que les unites pertinentes ("1j 3h 20s").
     */
    public String formatTime(long totalSeconds) {
        if (totalSeconds <= 0) {
            return timeZero;
        }
        StringBuilder builder = new StringBuilder();
        appendUnit(builder, TimeUtil.days(totalSeconds), unitDays);
        appendUnit(builder, TimeUtil.hours(totalSeconds), unitHours);
        appendUnit(builder, TimeUtil.minutes(totalSeconds), unitMinutes);
        appendUnit(builder, TimeUtil.seconds(totalSeconds), unitSeconds);
        return builder.toString();
    }

    public String infinite() {
        return timeInfinite;
    }

    /**
     * Placeholders temps prets a l'emploi pour {@link #get(String, String...)}.
     */
    public String[] timePlaceholders(long totalSeconds) {
        return new String[]{
            "{TIME}", formatTime(totalSeconds),
            "{RAW}", String.valueOf(totalSeconds),
            "{DAYS}", String.valueOf(TimeUtil.days(totalSeconds)),
            "{HOURS}", String.valueOf(TimeUtil.hours(totalSeconds)),
            "{MINUTES}", String.valueOf(TimeUtil.minutes(totalSeconds)),
            "{SECONDS}", String.valueOf(TimeUtil.seconds(totalSeconds))
        };
    }

    public static String[] merge(String[] first, String... second) {
        String[] merged = new String[first.length + second.length];
        System.arraycopy(first, 0, merged, 0, first.length);
        System.arraycopy(second, 0, merged, first.length, second.length);
        return merged;
    }

    private void appendUnit(StringBuilder builder, long quantity, String unit) {
        if (quantity <= 0) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(timeSeparator);
        }
        builder.append(timeFormat.replace("{QUANTITY}", String.valueOf(quantity)).replace("{UNIT}", unit));
    }

    private String applyPrefixes(String input) {
        if (input.indexOf('{') < 0) {
            return input;
        }
        return input
            .replace("{PREFIX}", prefixInfo)
            .replace("{PREFIX_SUCCESS}", prefixSuccess)
            .replace("{PREFIX_WARNING}", prefixWarning)
            .replace("{PREFIX_ERROR}", prefixError);
    }

    private String replace(String input, String... placeholders) {
        String result = input;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            result = result.replace(placeholders[i], placeholders[i + 1]);
        }
        return result;
    }
}
