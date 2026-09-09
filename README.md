# CloverCheck

CloverCheck is a manual cheat-check workflow plugin for Minecraft/Paper 26.2. It does not detect KillAura, Reach, Fly, Speed, or other cheats automatically. It gives moderators a controlled, auditable workflow for checking a player manually.

## Requirements

- Minecraft/Paper 26.2
- Cardboard 26.2.18 or newer when running on Cardboard
- Java 25
- Gradle 9.7.1 via the included wrapper

Gameplay protection uses public Bukkit/Paper and Adventure APIs. Cardboard 26.2.18 includes the API-parity and cancellation fixes CloverCheck relies on, so CloverCheck does not carry Cardboard-specific gameplay, BossBar, potion-effect, teleport, or per-tick isolation fallbacks.

The isolated check-chat path is the one intentional compatibility exception: on Fabric/Cardboard, CloverCheck uses a small reflective bridge for the legacy Bukkit chat event because that runtime does not currently expose the same `AsyncChatEvent` path used on Paper. Cardboard compatibility is not claimed as runtime-tested until the plugin is exercised on an actual Cardboard 26.2.18+ server.

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
