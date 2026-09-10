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

## Persistence and audit

Session history and active-session recovery use SQLite. SQL operations run on a dedicated single-thread executor and values are bound with prepared statements. Audit events are stored separately and mirrored to the plugin logger.

## Safety defaults

Automatic punishment commands are empty by default. Quit policy defaults to `NOTIFY_ONLY`, timeout policy defaults to `MARK_AS_TIMEOUT`, and moderator teleport is disabled until explicitly enabled.

## Build

```bash
./gradlew clean build
```

The production JAR is produced by the Shadow task and contains the SQLite JDBC driver. Paper/Adventure server APIs are not bundled.
