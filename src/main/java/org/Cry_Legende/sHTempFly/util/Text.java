package org.Cry_Legende.sHTempFly.util;

import net.md_5.bungee.api.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Text {

    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private Text() {
    }

    public static String color(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        Matcher matcher = HEX.matcher(input);
        StringBuilder builder = new StringBuilder(input.length());
        while (matcher.find()) {
            matcher.appendReplacement(builder, ChatColor.of("#" + matcher.group(1)).toString());
        }
        matcher.appendTail(builder);
        return ChatColor.translateAlternateColorCodes('&', builder.toString());
    }
}
