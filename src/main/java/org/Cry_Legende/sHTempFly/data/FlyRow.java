package org.Cry_Legende.sHTempFly.data;

import java.util.UUID;

/**
 * Une ligne de la table des joueurs, telle qu'elle est stockée (SQLite local ou base partagée).
 */
public record FlyRow(UUID uuid, String name, double time, boolean flyEnabled, long lastSeen, boolean unlimited) {
}
