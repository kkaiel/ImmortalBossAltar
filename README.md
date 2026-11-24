# ImmortalBossAltar 0.2.0

Separate Paper plugin for the End altar and MythicMobs `AncientDoomDragon` encounter.

## Build

- Java 25
- Gradle 9.2.1 (GitHub Actions uses the Gradle setup action)
- Paper API 1.21.11 compile baseline; intended to run on the server's Paper 26.3 runtime using only stable Bukkit/Paper APIs.
- MythicMobs is a soft dependency and is invoked through its console command interface.

## Install

1. Build with `gradle clean build` or download the Actions artifact.
2. Put `build/libs/ImmortalBossAltar-0.2.0.jar` into `plugins/`.
3. Ensure MythicMobs loads the existing `AncientDoomDragon` mob and `EndDoomScoreSword` item.
4. Restart the server.

## Configure the altar safely

1. Go to the End exit fountain.
2. Aim at the TOP BEDROCK SUPPORT BLOCK of the central pillar and run `/bossaltar setcenter`.
3. The plugin stores the air block directly above that support block as the dragon-egg position.
4. The four eye pedestals are the BEDROCK blocks at cardinal offsets configured by `altar.pedestal-offset` (default 2), at the same Y as the egg position.
5. The plugin never replaces the pedestal or support bedrock. It only places/removes the dragon egg in the configured air block.

If `/bossaltar setcenter` reports an invalid structure, do not force the ritual. Adjust `altar.pedestal-offset` or `altar.pedestal-material` only after visually checking the exact fountain coordinates.

## Commands

- `/bossaltar setcenter` (admin, look at the central pillar's top support bedrock)
- `/bossaltar status` (shows vanilla dragon defeat, encounter state, permanent final-boss completion, killer and time)
- `/bossaltar reset` (admin; only before final completion and when no custom boss is active; does not erase permanent final-boss record)
- `/bossaltar reload`

## Persistent data

`plugins/ImmortalBossAltar/data.yml` stores vanilla dragon defeat, custom boss UUID, portal block coordinates, and permanent final-boss completion record. The permanent final-boss record is intentionally not cleared by `/bossaltar reset`.

## Important testing notes

This source revision has not been compiled against the user's exact Paper 26.3 server or tested with their MythicMobs installation. Treat it as a development build. First test on a backup/test server. Verify the actual fountain geometry and portal restoration before any live use. Ritual deposits are not yet an escrow/refund transaction: do not begin a summon test until this is explicitly completed and reviewed.
