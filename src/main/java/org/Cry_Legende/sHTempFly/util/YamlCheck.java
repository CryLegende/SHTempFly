package org.Cry_Legende.sHTempFly.util;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

/**
 * Vérifie qu'un fichier YAML se lit avant de le recharger : un fichier invalide donnerait sinon une
 * configuration vide, donc les valeurs par défaut du code à la place des réglages du serveur.
 */
public final class YamlCheck {

    private static final int MAX_LENGTH = 220;

    private YamlCheck() {
    }

    /**
     * @return null si le fichier est lisible (ou absent), sinon la cause de l'erreur sur une ligne
     */
    public static String error(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            new YamlConfiguration().load(file);
            return null;
        } catch (IOException | InvalidConfigurationException e) {
            String text = String.valueOf(e.getMessage()).replaceAll("\\s+", " ").trim();
            return text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) + "..." : text;
        }
    }
}
