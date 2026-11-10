package org.Cry_Legende.sHTempFly.data;

import org.Cry_Legende.sHTempFly.fly.FlyResult;

import java.util.UUID;

/**
 * Etat d'un joueur : donnees persistees + etat runtime de la session.
 */
public final class FlyUser {

    private final UUID uuid;
    private String name;

    private double time;
    private boolean flyEnabled;
    private long lastSeen;

    private boolean dirty;
    // Mode réseau : part du temps déjà reçue par la base partagée (voir takeUnsyncedTime)
    private double syncedTime;

    private boolean unlimited;
    private FlyResult lastResult;
    private FlyResult lastNotified;
    private long lastNotifiedAt;
    private long fallGraceUntil;
    private boolean granted;

    public FlyUser(UUID uuid, String name, double time, boolean flyEnabled, long lastSeen) {
        this.uuid = uuid;
        this.name = name;
        this.time = time;
        this.flyEnabled = flyEnabled;
        this.lastSeen = lastSeen;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        if (!name.equals(this.name)) {
            this.name = name;
            this.dirty = true;
        }
    }

    public double time() {
        return time;
    }

    public long timeSeconds() {
        return (long) Math.floor(time);
    }

    public void time(double time) {
        this.time = Math.max(0D, time);
        this.dirty = true;
    }

    /**
     * Ajoute (ou retire si negatif) du temps. Retourne la variation reellement appliquee.
     */
    public double addTime(double amount) {
        double before = time;
        time = Math.max(0D, time + amount);
        dirty = true;
        return time - before;
    }

    /**
     * Mode réseau : fixe le temps que la base connaît pour ce joueur (valeur lue à son arrivée).
     */
    public synchronized void syncedTime(double syncedTime) {
        this.syncedTime = syncedTime;
    }

    /**
     * Mode réseau : temps gagné ou consommé ici depuis la dernière écriture en base. La base reçoit cette différence
     * ({@code time = time + ?}) et non la valeur entière : un temps ajouté entre-temps par un autre serveur n'est
     * jamais écrasé. À appeler une seule fois par écriture, sur le fil principal.
     */
    public synchronized double takeUnsyncedTime() {
        double delta = time - syncedTime;
        syncedTime = time;
        return delta;
    }

    /**
     * Mode réseau : l'écriture de {@link #takeUnsyncedTime()} a échoué, la différence sera renvoyée à la suivante.
     */
    public synchronized void restoreUnsyncedTime(double delta) {
        syncedTime -= delta;
    }

    public boolean flyEnabled() {
        return flyEnabled;
    }

    public void flyEnabled(boolean flyEnabled) {
        if (this.flyEnabled != flyEnabled) {
            this.flyEnabled = flyEnabled;
            this.dirty = true;
        }
    }

    public long lastSeen() {
        return lastSeen;
    }

    public void lastSeen(long lastSeen) {
        this.lastSeen = lastSeen;
        this.dirty = true;
    }

    public boolean dirty() {
        return dirty;
    }

    public void clean() {
        this.dirty = false;
    }

    public boolean unlimited() {
        return unlimited;
    }

    public void unlimited(boolean unlimited) {
        if (this.unlimited != unlimited) {
            this.unlimited = unlimited;
            this.dirty = true;
        }
    }

    public FlyResult lastResult() {
        return lastResult;
    }

    public void lastResult(FlyResult lastResult) {
        this.lastResult = lastResult;
    }

    /**
     * Reserve l'envoi du message correspondant a ce verdict, au maximum une fois
     * par periode de {@code cooldownMs}. Evite qu'un verdict qui oscille (bord de zone,
     * plafond de vol...) ne repete le meme message a chaque deplacement.
     *
     * @return true si le message doit etre envoye.
     */
    public boolean shouldNotify(FlyResult result, long cooldownMs) {
        long now = System.currentTimeMillis();
        if (result == lastNotified && now - lastNotifiedAt < cooldownMs) {
            return false;
        }
        lastNotified = result;
        lastNotifiedAt = now;
        return true;
    }

    /**
     * True si c'est SHTempFly qui a accorde le vol au joueur (et pas un autre plugin).
     */
    public boolean granted() {
        return granted;
    }

    public void granted(boolean granted) {
        this.granted = granted;
    }

    public long fallGraceUntil() {
        return fallGraceUntil;
    }

    public void fallGraceUntil(long fallGraceUntil) {
        this.fallGraceUntil = fallGraceUntil;
    }
}
