package org.Cry_Legende.sHTempFly.data;

import org.Cry_Legende.sHTempFly.SHTempFly;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

/**
 * Persistance SQLite. Toutes les I/O passent par un thread dedie ;
 * le thread principal n'attend jamais, sauf a l'arret du serveur.
 */
public final class FlyStorage {

    private final SHTempFly plugin;
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SHTempFly-IO");
        thread.setDaemon(true);
        return thread;
    });

    private Connection connection;

    public FlyStorage(SHTempFly plugin) {
        this.plugin = plugin;
    }

    public void init() throws SQLException {
        File folder = plugin.getDataFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            throw new SQLException("Impossible de creer le dossier " + folder);
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ex) {
            throw new SQLException("Driver SQLite introuvable", ex);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + new File(folder, "data.db"));
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS fly_users (
                    uuid TEXT PRIMARY KEY NOT NULL,
                    name TEXT,
                    time REAL NOT NULL DEFAULT 0,
                    fly_enabled INTEGER NOT NULL DEFAULT 1,
                    last_seen INTEGER NOT NULL DEFAULT 0,
                    unlimited INTEGER NOT NULL DEFAULT 0
                )""");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY NOT NULL, v TEXT)");
            statement.executeUpdate("PRAGMA journal_mode=WAL");
        }
    }

    public CompletableFuture<FlyUser> load(UUID uuid, String name) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name, time, fly_enabled, last_seen, unlimited FROM fly_users WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        FlyUser user = new FlyUser(uuid, name,
                            result.getDouble("time"),
                            result.getInt("fly_enabled") != 0,
                            result.getLong("last_seen"));
                        user.unlimited(result.getInt("unlimited") != 0);
                        return user;
                    }
                }
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE, "Echec du chargement de " + uuid, ex);
            }
            return new FlyUser(uuid, name, 0D, true, 0L);
        }, io);
    }

    public void saveAsync(FlyUser user) {
        FlyUser snapshot = copy(user);
        user.clean();
        io.execute(() -> writeSingle(snapshot));
    }

    /**
     * Sauvegarde groupee en arriere plan.
     */
    public void saveAll(Collection<FlyUser> users) {
        List<FlyUser> snapshots = snapshot(users);
        if (!snapshots.isEmpty()) {
            io.execute(() -> writeBatch(snapshots));
        }
    }

    /**
     * Sauvegarde bloquante, utilisee uniquement a l'arret du serveur.
     */
    public void saveAllNow(Collection<FlyUser> users) {
        List<FlyUser> snapshots = snapshot(users);
        if (snapshots.isEmpty()) {
            return;
        }
        try {
            io.submit(() -> writeBatch(snapshots)).get(15, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ex) {
            plugin.getLogger().log(Level.SEVERE, "Echec de la sauvegarde finale", ex);
        }
    }

    private List<FlyUser> snapshot(Collection<FlyUser> users) {
        List<FlyUser> snapshots = new ArrayList<>(users.size());
        for (FlyUser user : users) {
            snapshots.add(copy(user));
            user.clean();
        }
        return snapshots;
    }

    private FlyUser copy(FlyUser user) {
        FlyUser snapshot = new FlyUser(user.uuid(), user.name(), user.time(), user.flyEnabled(), user.lastSeen());
        snapshot.unlimited(user.unlimited());
        return snapshot;
    }

    private void writeBatch(List<FlyUser> users) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(upsertSql())) {
                for (FlyUser user : users) {
                    bind(statement, user);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            connection.commit();
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Echec de la sauvegarde groupee", ex);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // la connexion est fermee juste apres
            }
        }
    }

    /**
     * Ajoute (ou definit) du temps pour un joueur hors ligne.
     * Le dernier statut connu est utilise : un joueur marque illimite garde son temps gele.
     *
     * @return false si la modification a ete ignoree.
     */
    public CompletableFuture<Boolean> addTimeOffline(UUID uuid, String name, double amount, boolean absolute) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                try (PreparedStatement check = connection.prepareStatement(
                    "SELECT unlimited FROM fly_users WHERE uuid = ?")) {
                    check.setString(1, uuid.toString());
                    try (ResultSet result = check.executeQuery()) {
                        if (result.next() && result.getInt(1) != 0) {
                            return false;
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(absolute
                    ? "INSERT INTO fly_users (uuid, name, time) VALUES (?, ?, ?) "
                    + "ON CONFLICT(uuid) DO UPDATE SET time = ?, name = ?"
                    : "INSERT INTO fly_users (uuid, name, time) VALUES (?, ?, MAX(0, ?)) "
                    + "ON CONFLICT(uuid) DO UPDATE SET time = MAX(0, time + ?), name = ?")) {
                    statement.setString(1, uuid.toString());
                    statement.setString(2, name);
                    statement.setDouble(3, Math.max(0D, amount));
                    statement.setDouble(4, amount);
                    statement.setString(5, name);
                    statement.executeUpdate();
                }
                return true;
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE, "Echec de la mise a jour hors ligne de " + uuid, ex);
                return false;
            }
        }, io);
    }

    public CompletableFuture<Double> readTime(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT time FROM fly_users WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getDouble(1) : 0D;
                }
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE, "Echec de la lecture de " + uuid, ex);
                return 0D;
            }
        }, io);
    }

    /**
     * Import de masse : n'ecrase jamais un joueur deja present.
     */
    public CompletableFuture<Integer> importLegacy(Map<UUID, Double> times) {
        return CompletableFuture.supplyAsync(() -> {
            int imported = 0;
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT OR IGNORE INTO fly_users (uuid, name, time, fly_enabled, last_seen) "
                        + "VALUES (?, NULL, ?, 1, ?)")) {
                    long now = System.currentTimeMillis();
                    for (Map.Entry<UUID, Double> entry : times.entrySet()) {
                        statement.setString(1, entry.getKey().toString());
                        statement.setDouble(2, Math.max(0D, entry.getValue()));
                        statement.setLong(3, now);
                        statement.addBatch();
                        imported++;
                    }
                    statement.executeBatch();
                }
                connection.commit();
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE, "Echec de l'import", ex);
                imported = 0;
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // ignore
                }
            }
            return imported;
        }, io);
    }

    public CompletableFuture<Boolean> metaFlag(String key) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT v FROM meta WHERE k = ?")) {
                statement.setString(1, key);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next();
                }
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE, "Echec de la lecture de meta." + key, ex);
                return true;
            }
        }, io);
    }

    public void setMetaFlag(String key, String value) {
        io.execute(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = ?")) {
                statement.setString(1, key);
                statement.setString(2, value);
                statement.setString(3, value);
                statement.executeUpdate();
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE, "Echec de l'ecriture de meta." + key, ex);
            }
        });
    }

    public void close() {
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Les ecritures en base n'ont pas pu se terminer a temps.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Echec de la fermeture de la base", ex);
        }
    }

    private void writeSingle(FlyUser user) {
        try (PreparedStatement statement = connection.prepareStatement(upsertSql())) {
            bind(statement, user);
            statement.executeUpdate();
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Echec de la sauvegarde de " + user.uuid(), ex);
        }
    }

    private String upsertSql() {
        return "INSERT INTO fly_users (uuid, name, time, fly_enabled, last_seen, unlimited) VALUES (?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, time = excluded.time, "
            + "fly_enabled = excluded.fly_enabled, last_seen = excluded.last_seen, "
            + "unlimited = excluded.unlimited";
    }

    private void bind(PreparedStatement statement, FlyUser user) throws SQLException {
        statement.setString(1, user.uuid().toString());
        statement.setString(2, user.name());
        statement.setDouble(3, user.time());
        statement.setInt(4, user.flyEnabled() ? 1 : 0);
        statement.setLong(5, user.lastSeen());
        statement.setInt(6, user.unlimited() ? 1 : 0);
    }
}
