package org.Cry_Legende.sHTempFly.data;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Ce dont le plugin a besoin pour garder le temps de vol des joueurs. {@link FlyStorage} (SQLite, un seul serveur)
 * est le stockage par défaut ; avec SHNetwork, le stockage partagé du réseau prend sa place.
 */
public interface FlyStore {

    /**
     * Lit un joueur à son arrivée ; un joueur inconnu est rendu avec 0 seconde et le fly activé.
     */
    CompletableFuture<FlyUser> load(UUID uuid, String name);

    /**
     * Met à jour la dernière connexion sans toucher au temps.
     */
    void touchLastSeen(UUID uuid, String name, long lastSeen);

    /**
     * Sauvegarde un joueur ; le futur se termine une fois l'écriture faite.
     */
    CompletableFuture<Void> saveAsync(FlyUser user);

    /**
     * Sauvegarde groupée en arrière-plan.
     */
    void saveAll(Collection<FlyUser> users);

    /**
     * Sauvegarde bloquante, utilisée uniquement à l'arrêt du serveur.
     */
    void saveAllNow(Collection<FlyUser> users);

    /**
     * Ajoute (ou définit) du temps pour un joueur hors ligne.
     *
     * @return false si la modification a été ignorée.
     */
    CompletableFuture<Boolean> addTimeOffline(UUID uuid, String name, double amount, boolean absolute);

    /**
     * Temps d'un joueur hors ligne, ou null si la lecture a échoué (mode réseau : base injoignable).
     */
    CompletableFuture<Double> readTime(UUID uuid);

    /**
     * Import de masse : n'écrase jamais un joueur déjà présent.
     */
    CompletableFuture<Integer> importLegacy(Map<UUID, Double> times);

    CompletableFuture<Boolean> metaFlag(String key);

    void setMetaFlag(String key, String value);

    void close();
}
