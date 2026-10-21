package org.Cry_Legende.sHTempFly.zone;

public enum ZoneMode {

    /**
     * Fly autorise, consomme du temps si le joueur n'est pas illimite.
     */
    ALLOW,
    /**
     * Fly interdit.
     */
    DENY,
    /**
     * Fly gratuit pour tout le monde, aucun temps requis ni consomme.
     */
    FREE,
    /**
     * Le plugin n'intervient jamais dans cette zone.
     */
    UNMANAGED;

    public static ZoneMode parse(String raw, ZoneMode fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
