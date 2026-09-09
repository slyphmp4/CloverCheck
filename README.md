# CloverCheck

CloverCheck is a lightweight cheat-check workflow plugin for Minecraft/Paper 26.2 with a compatibility-first implementation for Cardboard.

## Features

- Staff-controlled cheat-check sessions with configurable duration.
- Full player freeze: movement, teleporting, interactions, inventory actions, item drop/pickup, damage and hunger.
- Command allowlist while a player is being checked.
- Persistent active sessions in `checks.yml`, including reconnect and server restart recovery.
- Configurable disconnect behavior: keep the check active or fail it immediately.
- Configurable console actions for cheats, refusal, admission, timeout and disconnect outcomes.
- Staff notifications with hover details and clickable status lookup.
- Adventure-based messages with `&` colors and `&FF0000`, `&#FF0000`, `<#FF0000>` HEX support.
- Permission-aware tab completion.
- No NMS, reflection or shaded server libraries.

## Commands

| Command | Permission | Description |
| --- | --- | --- |
| `/check start <player> [duration]` | `clovercheck.command.start` | Start a cheat check. |
| `/check finish <player> <clean\|cheats\|refusal>` | `clovercheck.command.finish` | Finish a check with a result. |
| `/check cancel <player>` | `clovercheck.command.cancel` | Cancel a check without punishment. |
| `/check status [player]` | `clovercheck.command.status` | Show check status. Checked players can view their own status. |
| `/check list` | `clovercheck.command.list` | List active checks. |
| `/check admit` | `clovercheck.command.admit` | Admit cheat usage while being checked. |
| `/check reload` | `clovercheck.command.reload` | Reload configuration and messages. |

## Compatibility

- Minecraft / Paper: `26.2`
- Java: `25`
- Gradle: `9.7.1`
- `api-version`: `26.2`

The plugin intentionally uses stable Bukkit/Paper APIs and avoids NMS and reflection to keep Cardboard compatibility as broad as possible.

## Build

```bash
./gradlew clean build
```

The JAR is produced in `build/libs/`.

## Author

`slyph`
