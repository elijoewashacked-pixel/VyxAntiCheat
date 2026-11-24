# VyxAC - low-false-positive anticheat for Spigot / Paper / Purpur (1.20.x - 1.21.x)

## Build and install
1. Needs JDK 17+ and Maven. In this folder run: `mvn package`
2. Copy `target/VyxAC-1.2.0.jar` into your server's `plugins/` folder, start the server once.
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

## 1.1.1: reports, staff mode, self-test
* `/vac report <player> <reason>` - players report; staff get a clickable ticket with a VyxAC evidence snapshot.
* `/vyxac reports` - ticket GUI: claim, reply (typed in chat), dismiss, close as "action taken", reopen. Answers reach the reporter even if they are offline.
* `/vyxac staff` - vanish + flight + invulnerability + hotbar tools (reports, players, inspect, freeze, inventory viewer, random teleport, staff chat, vanish toggle, leave). The real inventory is saved on disk and restored on exit, quit, plugin disable and after a crash.
* `/vyxac staff freeze|inspect|chat|note|notes|rtp|players|list` - more staff tools.
* `/vac test` - self-test: alerts, webhook, punishment ladder dry run. Nobody is punished.
* Permissions: `vyxac.staff` (op), `vyxac.report` (everyone), `vyxac.admin`.

## Combat accuracy (1.1.1)
* **Reach**: default tolerance 0.3 blocks, flags on the first clearly out-of-range hit, plus an *average reach* statistic (catches constant 3.2-3.5 block reach). Lag explains small excesses only.
* **Hitbox**: accepts every camera angle swept since the previous movement packet (so fast flicks are legal, silent aim is not), hitbox expansion 0.15, and flags hits **through cobwebs and solid walls** (`checks.Hitbox.params.web` / `wall`).
* `/vyxac preset strict|normal|relaxed` changes Reach/Hitbox/Backtrack strictness in one command.
* Every alert has **[Punish] [Kick] [Teleport]** buttons (`alerts.buttons`).

## 1.2.0: stronger AI and CheckHacks (client mod detection)
### AI
Ten behavioural features (click rhythm, aim precision, reaction constancy, population outlier, click entropy, mouse-step quantisation,
hit accuracy, snap-to-target, average reach, change against the player's own history). The model LEARNS from staff decisions
(Punish = cheater, Dismiss on an AI-flagged player = clean) and from hack clients confirmed by CheckHacks; it is saved in
`ai-model.yml`. `/vyxac ai explain <player>` shows how the score was made. CPU budget `ai.max-micros-per-second` (default 8000).

### CheckHacks (sign translation check, MC-265322)
A virtual sign with translatable / keybind text is shown above or below the player, its editor is opened and closed in the same
tick, and the translated text the client sends back shows which mods are installed. Players download nothing.
* **Needs Paper (or a fork) and the ProtocolLib plugin.** `/checkhacks status` says what is missing.
* Modes TRANSLATE, KEYBIND, METEOR; a random control line on every sign; a client that rewrites sign text or never answers is **PROTECTED**, never "clean".
* DoubleCheck confirms every DETECTED / PROTECTED with a second scan. Bedrock / Geyser / Floodgate skipped.
* Auto check on join, language check on join, per-player cooldown, separate hack lists (normal / join / anticheat),
  hooks for Vulcan / Spartan / Matrix flags (Grim: add `checkhacks %player% -ac` to its punishment commands) and VyxAC's own flags.
* Commands on DETECTED / PROTECTED / clean and per-mod `command-rules`; placeholders `%player%` `%hack%` `&hack&` `{hack}`.
* Results to everyone with `checkhacks.alerts` (`/chalerts` toggles), always privately to whoever started the check.
* SQLite storage (every scan), web editor (`/cheditor`, one-time token, off by default, binds 127.0.0.1), two Discord webhooks with Components V2,
  updater for YOUR GitHub releases (`/checkhacksupdate`, installs through plugins/update on restart), 9 languages, MiniMessage colours, PlaceholderAPI.
* Commands: `/checkhacks <player> [mods|category|all|-join|-ac]`, `list`, `status`, `history <player>`, `probe <player> <KEYBIND|TRANSLATE> <key>`, `reload`;
  `/chalerts`, `/cheditor`, `/checkhacksupdate`, `/checklang <player>`.

**Important:** the mod table in `checkhacks.yml` ships with three entries (Meteor, Wurst, Xaero's Minimap) whose keys are NOT verified yet,
and empty templates for the others. Verify or add a key with `/checkhacks probe` on a test account that runs the mod
(keys come from the mod's `assets/<modid>/lang/en_us.json` or its keybinding ids). Wrong keys can only miss a mod, never accuse a clean player.
