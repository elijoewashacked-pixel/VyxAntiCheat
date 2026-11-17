# VyxAC - low-false-positive anticheat for Spigot / Paper / Purpur (1.20.x - 1.21.x)

## Build and install
1. Needs JDK 17+ and Maven. In this folder run: `mvn package`
2. Copy `target/VyxAC-1.1.0.jar` into your server's `plugins/` folder, start the server once.
3. Edit `plugins/VyxAC/config.yml` (every check gets its own section on first start) or use `/vyxac`.

Optional pom change: set `spigot-api` to the version you run (1.21.x works too, Java 21 on the server is fine).

## How it avoids false bans
* Default mode is **REVIEW**: nobody is punished automatically, staff get `[Punish] [Dismiss] [TP]` buttons.
* In AUTO mode a punishment needs **several different checks** (default 2) that each reached their own punish-VL,
  at least 6 flags inside 3 minutes, the player's ping <= 400, a stable server (TPS guard), no join/teleport grace,
  and the evidence must still hold after a short delay. The mini AI counts as one extra piece of evidence only.
* Punishment ladder (kick -> kick -> ban) instead of instant bans; a punishment resets the player's VL.
* Checks use accumulated evidence (debt / buffers), never single events, and are suspended during: join, teleport,
  respawn, world change, knockback/explosions, vehicles, flight, elytra/riptide, pistons, creative/spectator,
  server lag (TPS / long ticks) and connection stalls.
* Movement setbacks (pulling a player back) are **off by default** (`features.mitigate-movement`); flags and alerts still work.
* Unknown world data (unloaded chunk) is always treated as "player is legitimately supported".
* Everything is configurable per check: `enabled`, `punishable`, `vl-per-flag`, `alert-vl`, `punish-vl`, `mitigate-vl`, `params.*`.

## Mini AI (low CPU)
A small statistical model, not a neural network: O(1) updates per hit, analysis every 4 s, a hard time budget
(`ai.max-micros-per-second`, default 3 ms/s) with round-robin so it can never lag the server. It scores click rhythm,
aim precision, and reaction constancy, and compares players with the server's own population of *clean* players.
It raises its own alerts and corroborates other checks; it never punishes alone. `/vyxac ai` shows its cost.

## Commands (permission `vyxac.admin`, alerts need `vyxac.alerts`, `vyxac.bypass` exempts a player)
| Command | Purpose |
|---|---|
| `/vyxac help` | list commands |
| `/vyxac reload` | reload config.yml |
| `/vyxac configure [path] [value]` | read/change ANY config value (TAB completes paths) |
| `/vyxac customize` | GUI: enable/disable checks, evidence on/off, mode, AI, mitigation, lag guard, alerts |
| `/vyxac special [feature] [value]` | mode, mitigation, lag-protection, ai, ai-sensitivity, logging, require-swing, bedrock-exempt, console-alerts, webhook, flood-kick |
| `/vyxac check <name> [on/off]` | toggle one check |
| `/vyxac alerts [on/off]` | your alerts |
| `/vyxac status` / `ai` | health, TPS guard, AI cost |
| `/vyxac profile/history <player>` | violations, AI confidence, last flags |
| `/vyxac clear <player/all>`, `exempt <player> [sec]` | reset / temporarily ignore |
| `/vyxac punish/dismiss <player>` | used by the review buttons |

## The 47 checks
The list you gave contains FastBow twice (#10 and #45); it is one check here.
Combat: KillAura, AimAssist, AutoClicker, TriggerBot, Reach, Hitbox, Criticals, Velocity, AntiKnockback, FastBow, AutoPot, AutoTotem, MultiAura, AutoCrystal, Backtrack.
Movement: Speed, Fly, LongJump, BunnyHop, NoFall, Jesus, Spider, Step, NoSlow, Timer, Blink, Phase, FastLadder, ElytraFly.
Player: AutoArmor, ChestStealer, InventoryMove, Freecam, XRay, Scaffold, FastPlace, Nuker, FastBreak, Baritone, AutoFish.
Packet/misc: PingSpoof, BadPackets, PacketFlood, GroundSpoof, RotationSpoof, FastEat, AutoEat.
Plus the pseudo-check `AI`.

## Honest limitations - read before going live
* **Not run on a live server.** It was syntax/flow-checked and its core math was unit-tested and simulated,
  but it could not be compiled against the real Spigot jar in the build sandbox (offline). Compile it yourself;
  if the compiler reports an API mismatch for your version, that is a quick fix.
* **Start in REVIEW or ALERT_ONLY** for a few days, look at `/vyxac profile` and the logs in `plugins/VyxAC/logs/`,
  then tune thresholds. No anticheat can promise zero false positives.
* **Bukkit-only, no packet library.** Timer, Blink, PacketFlood, PingSpoof and BadPackets are approximations built
  from movement events. Blink and PingSpoof are alert-only by default. For exact versions add PacketEvents/ProtocolLib.
* Speed hacks below roughly 1.2x and similar subtle advantages are tolerated on purpose (no false bans first).
* Geometry combat checks (Reach/Hitbox/Backtrack/KillAura angle) run on player-vs-player hits only.
* X-Ray is a heuristic (alert only). Real protection: enable the server's built-in anti-xray (Paper: `anti-xray` in
  `paper-world-defaults.yml`; Spigot: `anti-xray` in `spigot.yml`).
* Plugins that break many blocks or deal damage via API (VeinMiner, skill plugins) can look like Nuker / MultiAura:
  give those players `vyxac.bypass` or disable that check. `general.require-swing` already ignores most API damage.
* Bedrock players (Floodgate prefix, default `.`) are exempt.
