package org.Cry_Legende.sHTempFly.network;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.Cry_Legende.sHTempFly.data.FlyRow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Temps de vol en MariaDB, partagé par tous les serveurs. Chargée seulement avec SHNetwork
 * (HikariCP et le pilote MariaDB viennent de {@code libraries:} dans plugin.yml).
 *
 * <p>Le temps s'écrit toujours par différence ({@code time = time + ?}) : un temps ajouté par un autre serveur
 * pendant que le joueur est en jeu n'est jamais écrasé. Le pseudo, l'état du fly, la dernière connexion et le
 * statut illimité sont écrits tels quels : seul le serveur où se trouve le joueur les écrit.</p>
 *
 * <p>Toutes les méthodes bloquent : à appeler hors du fil principal.</p>
 */
public final class FlyDatabase {

    /** Ce que le serveur écrit pour un joueur : la différence de temps depuis sa dernière écriture et son état. */
    public record Write(UUID uuid, String name, double delta, boolean flyEnabled, long lastSeen, boolean unlimited) {
    }

    /** Joueur retrouvé par son pseudo. */
    public record Known(UUID uuid, String name) {
    }

    private static final String TABLE = "shtempfly";

    private final HikariDataSource pool;

    public FlyDatabase(String url, String user, String password, int timeoutMs) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("SHTempFly");
        hikari.setJdbcUrl(url);
        hikari.setUsername(user);
        hikari.setPassword(password);
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.addDataSourceProperty("connectTimeout", String.valueOf(timeoutMs));
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(timeoutMs));
        hikari.setMaximumPoolSize(3);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(timeoutMs);
        // le serveur démarre même si la base est absente : le plugin reste alors en mode local
        hikari.setInitializationFailTimeout(-1);
        hikari.setKeepaliveTime(60_000);
        this.pool = new HikariDataSource(hikari);
    }

    /** Crée la table si elle manque. Une table déjà là ne demande aucun droit CREATE à l'utilisateur. */
    public void createTable() throws SQLException {
        try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT 1 FROM " + TABLE + " LIMIT 1")) {
                return;
            } catch (SQLException missing) {
                // la table n'existe pas encore : création ci-dessous
            }
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "uuid CHAR(36) NOT NULL, "
                + "name VARCHAR(32) NULL, "
                + "time DOUBLE NOT NULL DEFAULT 0, "
                + "fly_enabled TINYINT(1) NOT NULL DEFAULT 1, "
                + "last_seen BIGINT NOT NULL DEFAULT 0, "
                + "unlimited TINYINT(1) NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (uuid), "
                + "KEY idx_shtempfly_name (name)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        }
    }

    /** Ligne du joueur, ou vide s'il n'en a pas encore. */
    public Optional<FlyRow> load(UUID uuid) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT name, time, fly_enabled, last_seen, unlimited FROM " + TABLE + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new FlyRow(uuid, rs.getString(1), Math.max(0D, rs.getDouble(2)),
                    rs.getInt(3) != 0, rs.getLong(4), rs.getInt(5) != 0));
            }
        }
    }

    /**
     * Écrit l'état de plusieurs joueurs d'un coup (tout ou rien). Le temps reçoit la différence : une ligne qui n'existe
     * pas encore démarre à cette différence, une ligne existante l'ajoute (sans passer sous zéro).
     */
    public void save(List<Write> writes) throws SQLException {
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + TABLE
                + " (uuid, name, time, fly_enabled, last_seen, unlimited) VALUES (?, ?, GREATEST(0, ?), ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE name = VALUES(name), time = GREATEST(0, time + ?), "
                + "fly_enabled = VALUES(fly_enabled), last_seen = VALUES(last_seen), unlimited = VALUES(unlimited)")) {
                for (Write w : writes) {
                    ps.setString(1, w.uuid().toString());
                    ps.setString(2, w.name());
                    ps.setDouble(3, w.delta());
                    ps.setInt(4, w.flyEnabled() ? 1 : 0);
                    ps.setLong(5, w.lastSeen());
                    ps.setInt(6, w.unlimited() ? 1 : 0);
                    ps.setDouble(7, w.delta());
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** Met à jour la dernière connexion (et le pseudo) sans toucher au temps. */
    public void touchLastSeen(UUID uuid, String name, long lastSeen) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO " + TABLE + " (uuid, name, last_seen) VALUES (?, ?, ?) "
                 + "ON DUPLICATE KEY UPDATE name = VALUES(name), last_seen = VALUES(last_seen)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, lastSeen);
            ps.executeUpdate();
        }
    }

    /**
     * Ajoute (ou définit) du temps à un joueur qui n'est connecté nulle part. L'ajout est atomique côté base : le temps
     * lu par le serveur n'entre pas dans le calcul. Un joueur marqué illimité garde son temps gelé, comme sur un seul serveur.
     *
     * @return faux si la modification est ignorée parce que le joueur est illimité
     */
    public boolean addTime(UUID uuid, String name, double amount, boolean absolute) throws SQLException {
        try (Connection c = pool.getConnection()) {
            try (PreparedStatement check = c.prepareStatement("SELECT unlimited FROM " + TABLE + " WHERE uuid = ?")) {
                check.setString(1, uuid.toString());
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next() && rs.getInt(1) != 0) {
                        return false;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(absolute
                ? "INSERT INTO " + TABLE + " (uuid, name, time) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE time = IF(unlimited <> 0, time, ?), name = VALUES(name)"
                : "INSERT INTO " + TABLE + " (uuid, name, time) VALUES (?, ?, GREATEST(0, ?)) "
                + "ON DUPLICATE KEY UPDATE time = IF(unlimited <> 0, time, GREATEST(0, time + ?)), name = VALUES(name)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setDouble(3, absolute ? Math.max(0D, amount) : amount);
                ps.setDouble(4, absolute ? Math.max(0D, amount) : amount);
                ps.executeUpdate();
            }
            return true;
        }
    }

    /** Temps du joueur (0 sans ligne). */
    public double readTime(UUID uuid) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT time FROM " + TABLE + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Math.max(0D, rs.getDouble(1)) : 0D;
            }
        }
    }

    /** Joueur connu de la base sous ce pseudo (casse ignorée) ; le plus récemment vu s'il y en a plusieurs. */
    public Optional<Known> findByName(String name) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT uuid, name FROM " + TABLE + " WHERE name = ? ORDER BY last_seen DESC LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Known(UUID.fromString(rs.getString(1)), rs.getString(2))) : Optional.empty();
            }
        }
    }

    /**
     * Reprend des lignes existantes (SQLite local, ancien TempFly). Un joueur qui a déjà une ligne n'est pas touché,
     * donc l'importation peut être relancée sans rien écraser.
     *
     * @return {importés, déjà présents}
     */
    public int[] importRows(List<FlyRow> rows) throws SQLException {
        int imported = 0;
        int present = 0;
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + TABLE
                + " (uuid, name, time, fly_enabled, last_seen, unlimited) VALUES (?, ?, ?, ?, ?, ?)")) {
                for (FlyRow row : rows) {
                    ps.setString(1, row.uuid().toString());
                    ps.setString(2, row.name());
                    ps.setDouble(3, Math.max(0D, row.time()));
                    ps.setInt(4, row.flyEnabled() ? 1 : 0);
                    ps.setLong(5, row.lastSeen());
                    ps.setInt(6, row.unlimited() ? 1 : 0);
                    if (ps.executeUpdate() > 0) {
                        imported++;
                    } else {
                        present++;
                    }
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
        return new int[]{imported, present};
    }

    public void close() {
        pool.close();
    }
}
