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

- Optionnelles : `WorldGuard`, `PlaceholderAPI`, `SuperiorSkyblock2`

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
| `shtempfly.admin` | Accès à /shtempfly et aux sous-commandes de /fly. | `op` |

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
