package org.Cry_Legende.sHTempFly.network;

import org.Cry_Legende.sHTempFly.SHTempFly;
import org.Cry_Legende.sHTempFly.data.FlyRow;
import org.Cry_Legende.sHTempFly.data.FlyStorage;
import org.Cry_Legende.sHTempFly.data.FlyStore;
import org.Cry_Legende.sHTempFly.data.FlyUser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.io.File;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Mode réseau : le temps de vol, le fly activé et le statut illimité de chaque joueur sont dans la base partagée
 * au lieu de SQLite.
 *
 * <ul>
 *   <li>à la connexion : une fois le départ de l'autre serveur confirmé, le joueur est lu en base (en attendant,
 *       il est « pas prêt » : ses données ne sont pas chargées et les temps qu'on lui donne attendent leur arrivée) ;</li>
 *   <li>tant que le joueur est sur un serveur, la mémoire de ce serveur fait foi (le temps s'y consomme chaque seconde) ;
 *       la base reçoit la différence de temps à chaque sauvegarde automatique, après chaque commande d'administration
 *       et au départ du joueur ;</li>
 *   <li>au départ (déconnexion ou changement de serveur), la barrière de SHNetwork attend la fin de cette écriture
 *       avant que l'autre serveur ne lise ;</li>
 *   <li>un joueur connecté nulle part se modifie par un ajout atomique en base.</li>
 * </ul>
 */
public final class NetworkFlyStorage implements FlyStore, Listener {

    private static final int WRITE_ATTEMPTS = 3;
    private static final int LOAD_RETRY_SECONDS = 5;
    private static final int LOAD_RETRY_MAX = 60;
    private static final long PRELOAD_KEEP_MS = 120_000L;

    private record Preloaded(CompletableFuture<FlyUser> user, long at) {
    }

    /** Une écriture en attente : ce qui part en base, et le joueur dont elle vient (pour rendre la différence en cas d'échec). */
    private record Pending(FlyUser user, FlyDatabase.Write write) {
    }

    private final SHTempFly plugin;
    private final FlyDatabase db;
    private final NetworkLink link;
    private final Duration waitMax;
    private final ScheduledThreadPoolExecutor io = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "SHTempFly-reseau");
        thread.setDaemon(true);
        return thread;
    });
    /** Joueurs lus pendant la pré-connexion, repris à l'arrivée. */
    private final Map<UUID, Preloaded> preloaded = new ConcurrentHashMap<>();

    public NetworkFlyStorage(SHTempFly plugin, FlyDatabase db, NetworkLink link, Duration waitMax) {
        this.plugin = plugin;
        this.db = db;
        this.link = link;
        this.waitMax = waitMax;
        io.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        io.setRemoveOnCancelPolicy(true);
    }

    public NetworkLink link() {
        return link;
    }

    /** Joueur connecté sur un autre serveur du réseau (pseudo ou UUID), ou vide s'il est ici ou nulle part. */
    public Optional<NetworkLink.Remote> remote(String nameOrUuid) {
        return link.find(nameOrUuid).filter(r -> !r.server().equalsIgnoreCase(link.serverName()));
    }

    /** Branche le mode réseau : lecture à la connexion et sauvegarde au départ de chaque joueur. */
    public void start(Function<Player, CompletableFuture<Void>> saveTask) {
        link.registerSaveTask(saveTask);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** Termine les écritures en cours puis ferme la base. */
    @Override
    public void close() {
        io.shutdown();
        try {
            if (!io.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Des ecritures de temps de vol n'ont pas pu etre terminees a l'arret.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        db.close();
    }

    // ------------------------------------------------------------------ lecture

    /**
     * Lance la lecture du joueur dès la pré-connexion, sans l'attendre : le serveur quitté ne sauvegarde le joueur
     * qu'une fois cette connexion terminée (le proxy ferme l'ancienne connexion après), attendre ici bloquerait la
     * connexion jusqu'au délai maximal. La lecture se fait sur le fil de la base une fois le départ confirmé.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID uuid = event.getUniqueId();
        String name = event.getName();
        long now = System.currentTimeMillis();
        preloaded.values().removeIf(p -> now - p.at() > PRELOAD_KEEP_MS);
        CompletableFuture<FlyUser> user = new CompletableFuture<>();
        preloaded.put(uuid, new Preloaded(user, now));
        link.awaitSaved(uuid, waitMax).whenComplete((saved, error) -> {
            if (error != null || !Boolean.TRUE.equals(saved)) {
                plugin.getLogger().warning("Depart de " + name + " non confirme par l'autre serveur"
                    + (error != null ? " (" + error + ")" : "") + " : temps de vol relu quand meme.");
            }
            try {
                io.execute(() -> {
                    try {
                        user.complete(read(uuid, name));
                    } catch (SQLException e) {
                        // le joueur entre quand même : il est relu dès que la base répond (voir load)
                        plugin.getLogger().warning("Temps de vol de " + name + " non lu en base : " + e.getMessage());
                        user.completeExceptionally(e);
                    }
                });
            } catch (RejectedExecutionException stopped) {
                user.completeExceptionally(stopped);
            }
        });
    }

    /**
     * Joueur qui arrive : reprend la lecture lancée à sa pré-connexion, sinon (rechargement à chaud, base absente à sa
     * connexion) lit en base. Réessaie tant que la base ne répond pas.
     */
    @Override
    public CompletableFuture<FlyUser> load(UUID uuid, String name) {
        CompletableFuture<FlyUser> result = new CompletableFuture<>();
        Preloaded started = preloaded.remove(uuid);
        if (started == null) {
            submit(() -> readWithRetry(uuid, name, 0, result));
            return result;
        }
        started.user().whenComplete((user, error) -> {
            if (error == null) {
                result.complete(user);
            } else {
                submit(() -> readWithRetry(uuid, name, 0, result));
            }
        });
        return result;
    }

    private void readWithRetry(UUID uuid, String name, int attempt, CompletableFuture<FlyUser> result) {
        try {
            result.complete(read(uuid, name));
        } catch (SQLException e) {
            plugin.getLogger().warning("Temps de vol de " + name + " non lu en base : " + e.getMessage());
            if (attempt + 1 >= LOAD_RETRY_MAX) {
                plugin.getLogger().severe("Temps de vol de " + name + " jamais lu en base, abandon.");
                result.completeExceptionally(e);
                return;
            }
            try {
                io.schedule(() -> readWithRetry(uuid, name, attempt + 1, result), LOAD_RETRY_SECONDS, TimeUnit.SECONDS);
            } catch (RejectedExecutionException stopped) {
                result.completeExceptionally(stopped);
            }
        }
    }

    /** Un joueur sans ligne démarre à 0 seconde avec le fly activé, comme sur un seul serveur. */
    private FlyUser read(UUID uuid, String name) throws SQLException {
        Optional<FlyRow> row = db.load(uuid);
        if (row.isEmpty()) {
            FlyUser user = new FlyUser(uuid, name, 0D, true, 0L);
            user.syncedTime(0D);
            return user;
        }
        FlyRow data = row.get();
        FlyUser user = new FlyUser(uuid, name, data.time(), data.flyEnabled(), data.lastSeen());
        user.unlimited(data.unlimited());
        user.syncedTime(data.time());
        return user;
    }

    @Override
    public CompletableFuture<Double> readTime(UUID uuid) {
        CompletableFuture<Double> done = new CompletableFuture<>();
        submit(() -> {
            try {
                done.complete(db.readTime(uuid));
            } catch (SQLException e) {
                plugin.getLogger().warning("Temps de vol de " + uuid + " non lu en base : " + e.getMessage());
                done.complete(null);
            }
        });
        return done;
    }

    /** Cherche un joueur par son pseudo dans la base partagée (un joueur jamais venu sur ce serveur n'est pas dans son cache). */
    public CompletableFuture<Optional<FlyDatabase.Known>> findByName(String name) {
        CompletableFuture<Optional<FlyDatabase.Known>> done = new CompletableFuture<>();
        submit(() -> {
            try {
                done.complete(db.findByName(name));
            } catch (SQLException e) {
                plugin.getLogger().warning("Joueur " + name + " non cherche en base : " + e.getMessage());
                done.completeExceptionally(e);
            }
        });
        return done;
    }

    // ------------------------------------------------------------------ écriture

    @Override
    public CompletableFuture<Void> saveAsync(FlyUser user) {
        return write(snapshot(List.of(user)));
    }

    @Override
    public void saveAll(Collection<FlyUser> users) {
        write(snapshot(users));
    }

    /** Écriture bloquante, à l'arrêt du serveur. */
    @Override
    public void saveAllNow(Collection<FlyUser> users) {
        CompletableFuture<Void> done = write(snapshot(users));
        try {
            done.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            plugin.getLogger().log(Level.SEVERE, "Echec de la sauvegarde finale", e);
        }
    }

    /** Relève, sur le fil principal, ce qui doit partir en base : la différence de temps de chaque joueur et son état. */
    private List<Pending> snapshot(Collection<FlyUser> users) {
        List<Pending> pending = new ArrayList<>(users.size());
        for (FlyUser user : users) {
            pending.add(new Pending(user, new FlyDatabase.Write(user.uuid(), user.name(), user.takeUnsyncedTime(),
                user.flyEnabled(), user.lastSeen(), user.unlimited())));
            user.clean();
        }
        return pending;
    }

    /** Écrit ces joueurs ; le futur se termine quand la base les a reçus (ou après les essais). */
    private CompletableFuture<Void> write(List<Pending> pending) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        if (pending.isEmpty()) {
            done.complete(null);
            return done;
        }
        try {
            io.execute(() -> {
                try {
                    writeWithRetries(pending);
                } finally {
                    done.complete(null);
                }
            });
        } catch (RejectedExecutionException stopped) {
            giveBack(pending);
            done.complete(null);
        }
        return done;
    }

    private void writeWithRetries(List<Pending> pending) {
        List<FlyDatabase.Write> writes = pending.stream().map(Pending::write).toList();
        for (int attempt = 1; attempt <= WRITE_ATTEMPTS; attempt++) {
            try {
                db.save(writes);
                return;
            } catch (SQLException e) {
                if (attempt == WRITE_ATTEMPTS) {
                    plugin.getLogger().log(Level.SEVERE, "Temps de vol non ecrit en base (" + writes + ") : " + e.getMessage());
                    giveBack(pending);
                    return;
                }
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    giveBack(pending);
                    return;
                }
            }
        }
    }

    /** L'écriture a échoué : la différence repart avec la suivante (joueur encore en jeu). */
    private void giveBack(List<Pending> pending) {
        for (Pending p : pending) {
            p.user().restoreUnsyncedTime(p.write().delta());
        }
    }

    @Override
    public void touchLastSeen(UUID uuid, String name, long lastSeen) {
        submit(() -> {
            try {
                db.touchLastSeen(uuid, name, lastSeen);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Echec de la sauvegarde de " + uuid + " : " + e.getMessage());
            }
        });
    }

    /**
     * Ajout atomique en base pour un joueur connecté nulle part.
     *
     * @return faux si le joueur est illimité (temps gelé), null si la base n'a pas répondu
     */
    @Override
    public CompletableFuture<Boolean> addTimeOffline(UUID uuid, String name, double amount, boolean absolute) {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        try {
            io.execute(() -> {
                for (int attempt = 1; attempt <= WRITE_ATTEMPTS; attempt++) {
                    try {
                        done.complete(db.addTime(uuid, name, amount, absolute));
                        return;
                    } catch (SQLException e) {
                        if (attempt == WRITE_ATTEMPTS) {
                            plugin.getLogger().log(Level.SEVERE, "Echec de la mise a jour hors ligne de " + uuid + " : " + e.getMessage());
                            done.complete(null);
                            return;
                        }
                        try {
                            Thread.sleep(200L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            done.complete(null);
                            return;
                        }
                    }
                }
            });
        } catch (RejectedExecutionException stopped) {
            done.complete(null);
        }
        return done;
    }

    // ------------------------------------------------------------------ importation

    /** L'ancien TempFly se reprend dans la base partagée comme dans SQLite : sans rien écraser. */
    @Override
    public CompletableFuture<Integer> importLegacy(Map<UUID, Double> times) {
        CompletableFuture<Integer> done = new CompletableFuture<>();
        long now = System.currentTimeMillis();
        List<FlyRow> rows = new ArrayList<>(times.size());
        times.forEach((uuid, time) -> rows.add(new FlyRow(uuid, null, Math.max(0D, time), true, now, false)));
        submit(() -> {
            try {
                done.complete(db.importRows(rows)[0]);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Echec de l'import : " + e.getMessage());
                done.complete(0);
            }
        });
        return done;
    }

    /**
     * Copie les joueurs du SQLite local (data.db) dans la base partagée, sans toucher à ceux qui y ont déjà une ligne.
     * {@code done} reçoit {importés, déjà présents} sur le fil principal, ou null si la lecture ou la base a refusé.
     */
    public void importLocal(File sqliteFile, Consumer<int[]> done) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int[] result;
            try {
                result = db.importRows(FlyStorage.readAll(sqliteFile));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Importation du SQLite local impossible : " + e.getMessage());
                result = null;
            }
            int[] finalResult = result;
            toMain(() -> done.accept(finalResult));
        });
    }

    // L'ancien TempFly ne se reprend pas tout seul au démarrage : l'importation du SQLite local (netimport) est un geste manuel.
    @Override
    public CompletableFuture<Boolean> metaFlag(String key) {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public void setMetaFlag(String key, String value) {
        // rien à retenir : voir metaFlag
    }

    // ------------------------------------------------------------------ fils

    private void toMain(Runnable task) {
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (IllegalPluginAccessException stopped) {
            // plugin arrêté : plus personne à prévenir
        }
    }

    private void submit(Runnable task) {
        try {
            io.execute(task);
        } catch (RejectedExecutionException stopped) {
            // plugin arrêté : plus rien à faire
        }
    }
}
