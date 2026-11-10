-- Table du temps de vol de SHTempFly avec SHNetwork. Le plugin la crée seul au démarrage si l'utilisateur a le droit CREATE ;
-- ce fichier sert à la créer à la main et à ne donner à l'utilisateur que les droits de lecture et d'écriture.
--
-- Utilisation (base bo_net pour les essais multi-Opale) :
--   mysql -u root < sql/schema.sql   puis remplacer CHANGE_ME par le mot de passe de config.yml

CREATE DATABASE IF NOT EXISTS bo_net;

CREATE TABLE IF NOT EXISTS bo_net.shtempfly (
    uuid        CHAR(36)    NOT NULL,                 -- uuid « hors ligne » du joueur, le même sur tous les serveurs
    name        VARCHAR(32) NULL,                     -- dernier pseudo connu (sert à retrouver un joueur hors ligne)
    time        DOUBLE      NOT NULL DEFAULT 0,       -- temps de vol en réserve, en secondes
    fly_enabled TINYINT(1)  NOT NULL DEFAULT 1,       -- fly activé par le joueur
    last_seen   BIGINT      NOT NULL DEFAULT 0,       -- dernière connexion, en millisecondes
    unlimited   TINYINT(1)  NOT NULL DEFAULT 0,       -- fly illimité : le temps ne bouge plus
    PRIMARY KEY (uuid),
    KEY idx_shtempfly_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE USER IF NOT EXISTS 'shtempfly'@'127.0.0.1' IDENTIFIED BY 'CHANGE_ME';
GRANT SELECT, INSERT, UPDATE ON bo_net.shtempfly TO 'shtempfly'@'127.0.0.1';
