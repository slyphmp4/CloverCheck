# CloverCheck

CloverCheck is a manual cheat-check workflow plugin for Minecraft/Paper 26.2. It does not detect KillAura, Reach, Fly, Speed, or other cheats automatically. It gives moderators a controlled, auditable workflow for checking a player manually.

## Requirements

- Minecraft/Paper 26.2
- Cardboard 26.2.19 or newer when running on Cardboard
- Java 25
- Gradle 9.7.1 via the included wrapper

Gameplay protection uses public Bukkit/Paper event APIs. Checked players are held at a fixed XYZ position while still being able to rotate their camera, and protected actions are blocked through cancellable Bukkit events.

Cardboard 26.2.19 includes the event-cancellation and API-parity fixes CloverCheck relies on. Two small public-API compatibility paths are intentionally retained for the current Cardboard runtime: client-side Blindness is refreshed through `Player#sendPotionEffectChange(...)`, and the Adventure BossBar is recreated on each one-second UI refresh because live mutation of the existing Adventure BossBar is not yet reliably propagated by Cardboard. These paths do not use NMS, CraftBukkit internals, command fallbacks, or per-tick isolation.

The isolated check-chat path is the other intentional compatibility exception: on Fabric/Cardboard, CloverCheck uses a small reflective bridge for the legacy Bukkit chat event because that runtime does not currently expose the same `AsyncChatEvent` path used on Paper.

The current protection, isolated chat, fixed-position freeze, Blindness, Title/Subtitle, ActionBar, inventory restrictions, interaction restrictions, and one-second BossBar countdown have been smoke-tested on a live Cardboard 26.2 server runtime.

## Commands

- `/check <player> [reason...]` - start a check
- `/check clean <player>` - finish as clean
- `/check cheats <player> [comment...]` - finish with cheats found
- `/check refuse <player> [comment...]` - finish as refused
- `/check cancel <player> [reason...]` - cancel
- `/check status [player]` - show current status and optional clickable staff controls
- `/check list` - list active checks
- `/check history <player>` - load persistent history
- `/check info <id>` - inspect a persisted session
- `/check confess` then `/check confess confirm` - checked-player confession flow
- `/check reload` - reload safe runtime configuration and messages

## Automatic punishment actions

CloverCheck executes configurable console commands for result-specific actions. By default, leaving an active check completes the session as `LEFT` and executes:

```yaml
actions:
  left:
    - "tempban %player% 7d Уход с проверки"
```

A confirmed confession completes the session as `CONFESSED` and executes:

```yaml
actions:
  confessed:
    - "tempban %player% 3d Читы, признался"
```

The default commands assume that the server provides a `tempban` command. If the installed punishment plugin uses another syntax, replace these lines with the required commands. Available safe placeholders are `%player%`, `%player_uuid%`, `%moderator%`, and `%session_id%`.

Leaving a check uses `quit.policy: EXECUTE_COMMANDS`. Config version 4 is automatically migrated to version 5 only when the previous quit/action values still match the untouched CloverCheck defaults; customized punishment settings are preserved.

Confession remains a two-step flow to prevent accidental punishment: `/check confess` requests confirmation and `/check confess confirm` applies the `CONFESSED` result and its configured actions.

## Console start checks

Starting a new check from the server console or RCON is disabled by default in production. It can be enabled explicitly in `config.yml`:

```yaml
console:
  allow-start-checks: true
```

When disabled, console/RCON cannot start a check and start targets are not exposed through tab completion. Other console-safe administrative commands are unaffected. Existing configs that do not contain this option are treated as `false`.

## Persistence and audit

Session history and active-session recovery use SQLite. SQL operations run on a dedicated single-thread executor and values are bound with prepared statements. Audit events are stored separately and mirrored to the plugin logger.

## Safety defaults

Leaving an active check executes the configured `LEFT` actions, confirmed confession executes the configured `CONFESSED` actions, timeout policy defaults to `MARK_AS_TIMEOUT`, moderator teleport is disabled until explicitly enabled, and starting checks from console/RCON is disabled until explicitly enabled. Action command failures are logged and audited instead of being silently ignored.

## Build

```bash
./gradlew clean build
```

The production JAR is produced by the Shadow task and contains the SQLite JDBC driver. Paper/Adventure server APIs are not bundled.

## Maintenance release 1.1.1

- Configuration reload validates all three YAML files before applying runtime changes. Invalid version-4 configs are not rewritten by migration.
- Disabled confession cannot be confirmed using an earlier confirmation request.
- Action command exceptions are logged and audited without skipping subsequent configured actions.
- Freeze anchors follow the current session and allowed teleports. Restored `STARTING` sessions activate when their player joins.
- Player quit clears check UI/effect state; reload and shutdown clear Titles/ActionBars only for players whose UI was shown by CloverCheck.
- The SQLite driver is updated to 3.53.4.0. History uses an indexed case-insensitive lookup, and connection closure proceeds even when final writes fail or the shutdown wait expires.

The detailed audit and verification limits are recorded in [MODERNIZATION_AUDIT.md](MODERNIZATION_AUDIT.md). The maintenance changes require a live smoke test on the deployment's Paper/Cardboard runtime; the earlier Cardboard smoke-test statement above applies to the original implementation.
