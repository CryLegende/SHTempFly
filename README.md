<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHTempFly

*Gestion du fly temporaire et illimité (zones, îles, minijeux).*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Gestion du fly temporaire et illimité (zones, îles, minijeux).

Utilisé sur : **Opale**.

## Dépendances

- Optionnelles : `WorldGuard`, `PlaceholderAPI`, `SuperiorSkyblock2`, `SHNetwork` (mode réseau)

## Mode réseau

Avec **SHNetwork** installé (et `reseau.actif: true`, valeur par défaut), plusieurs serveurs de jeu partagent le fly des joueurs :

- le temps de vol, le fly activé et le statut illimité sont en MariaDB (table `shtempfly`) au lieu de `data.db` ;
- à la connexion, une fois que l'autre serveur a fini de sauvegarder le joueur, ses données sont lues en base (sans bloquer la pré-connexion) ; tant qu'elles ne sont pas là, les temps qu'on lui donne attendent leur arrivée, comme pour un joueur en cours de chargement ;
- tant que le joueur est sur un serveur, la mémoire de ce serveur fait foi (le temps se consomme en mémoire) ; la base reçoit la **différence** de temps (`time = time + ?`) à chaque sauvegarde automatique, après chaque commande d'administration et à son départ, avant que l'autre serveur ne le lise : le temps consommé en vol n'est ni perdu ni compté deux fois au changement de serveur, et un temps ajouté entre-temps n'est jamais écrasé ;
- `give`, `remove` et `set` visant un joueur d'un autre serveur lui sont transmis et exécutés là où se trouve son temps en mémoire (une seule fois) ; pour un joueur connecté nulle part, l'ajout est atomique en base (un joueur illimité garde son temps gelé, comme sur un seul serveur) ; `giveall` touche tous les joueurs du réseau ;
- `check` et `/fly time <joueur>` sur un joueur d'un autre serveur lisent la base (la valeur peut retarder de la durée de la sauvegarde automatique) ; `/fly on|off <joueur>` est transmis au serveur du joueur ;
- un joueur jamais venu sur ce serveur se retrouve par son pseudo dans la base ;
- les joueurs de `data.db` se copient une fois avec `/shtempfly netimport` (rien n'est écrasé, la commande peut être relancée).

Sans SHNetwork, ou sans les clés `reseau` dans la configuration, le plugin se comporte comme avant : `data.db`. Si la base est injoignable au démarrage, il reste en mode local. La table se crée toute seule, ou à la main avec `sql/schema.sql`.

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/fly` | `/tf`, `/tfly`, `/tempfly` | Active / désactive le fly, ou affiche le temps restant. | — |
| `/shtempfly` | `/shtf`, `/flyadmin` | Commandes d'administration du fly. | — |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shtempfly.fly.unlimited` | Fly illimité, sans timer, avec activation / désactivation. | `false` |
| `shtempfly.fly.others` | Active / désactive le fly d'un autre joueur. | `op` |
| `shtempfly.bypass` | Ignore toutes les restrictions de zone. | `op` |
| `shtempfly.admin` | Accès à /shtempfly et aux sous-commandes de /fly (inclut `shtempfly.admin.check`). | `op` |
| `shtempfly.admin.check` | Seulement `check` (`/tfly check <joueur>`), pour les modérateurs. | `op` |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHTempFly/
├── <a href="src/main/resources/config.yml">config.yml</a>
└── <a href="src/main/resources/messages.yml">messages.yml</a>
</pre>

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
