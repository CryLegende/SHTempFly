package org.Cry_Legende.sHTempFly.fly;

/**
 * Verdict du moteur de vol pour un joueur a un instant donne.
 */
public enum FlyResult {

    /**
     * Le plugin n'intervient pas (gamemode ignore, zone UNMANAGED).
     */
    UNMANAGED(false),
    /**
     * Vol autorise, consomme du temps.
     */
    ALLOWED(true),
    /**
     * Vol autorise sans consommer de temps (zone FREE, permission illimitee, bypass).
     */
    ALLOWED_FREE(true),
    DENIED_ZONE(false),
    DENIED_HEIGHT(false),
    DENIED_ISLAND(false),
    /**
     * Vol suspendu par un autre plugin (parcours SHParkour...), ne consomme pas de temps.
     */
    DENIED_ACTIVITY(false),
    DENIED_NO_TIME(false);

    private final boolean allowed;

    FlyResult(boolean allowed) {
        this.allowed = allowed;
    }

    public boolean allowed() {
        return allowed;
    }

    public boolean denied() {
        return !allowed && this != UNMANAGED;
    }
}
