package org.Cry_Legende.sHTempFly.zone;

/**
 * Resultat de la resolution d'une position : mode applique et vitesse de consommation.
 */
public record ZoneResult(ZoneMode mode, double drainMultiplier) {

    public static final ZoneResult UNMANAGED = new ZoneResult(ZoneMode.UNMANAGED, 0.0D);
}
