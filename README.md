# ImmortalBossAltar 0.4.1

Separate Paper plugin for the End altar and MythicMobs `AncientDoomDragon` encounter.

## Build

- Java 25
- Gradle 9.2.1 (GitHub Actions uses the Gradle setup action)
- Paper API 1.21.11 compile baseline; intended to run on the server's Paper 26.3 runtime using only stable Bukkit/Paper APIs.
- MythicMobs is a soft dependency and is invoked through its console command interface.

## Install

1. Build with `gradle clean build` or download the Actions artifact.
2. Put `build/libs/ImmortalBossAltar-0.4.1.jar` into `plugins/`.
3. Ensure MythicMobs loads the existing `AncientDoomDragon` mob and `EndDoomScoreSword` item.
4. Restart the server.

## Configure the altar safely

1. Go to the End exit fountain.
2. Aim at the TOP BEDROCK SUPPORT BLOCK of the central pillar and run `/bossaltar setcenter`.
3. The plugin stores the air block directly above that support block as the dragon-egg position.
4. The plugin scans each cardinal direction for the nearest BEDROCK block within the configured horizontal radius and vertical range (`altar.pedestal-search-radius`, `altar.pedestal-search-min-y`, `altar.pedestal-search-max-y`). This accommodates the End fountain's raised center and lower surrounding bedrock rim.
5. `/bossaltar status` prints the four detected pedestal coordinates and block types. Check these coordinates against the actual fountain before inserting any items.
5. The plugin never replaces the pedestal or support bedrock. It only places/removes the dragon egg in the configured air block.

If `/bossaltar setcenter` cannot find all four pedestals, do not force the ritual. Review the coordinates shown by `/bossaltar status`; the scan is a heuristic and must be visually checked before any materials are inserted.

## Commands

- `/bossaltar setcenter` (admin, look at the central pillar's top support bedrock)
- `/bossaltar status` (shows vanilla dragon defeat, encounter state, permanent final-boss completion, killer and time)
- `/bossaltar reset` (admin; only before final completion and when no custom boss is active; does not erase permanent final-boss record)
- `/bossaltar reload`

## Persistent data

`plugins/ImmortalBossAltar/data.yml` stores vanilla dragon defeat, custom boss UUID, portal block coordinates, and permanent final-boss completion record. The permanent final-boss record is intentionally not cleared by `/bossaltar reset`.

## Important testing notes

This source revision has not been compiled against the user's exact Paper 26.3 server or tested with their MythicMobs installation. Treat it as a development build. First test on a backup/test server. Verify the actual fountain geometry and portal restoration before any live use. Ritual deposits are not yet an escrow/refund transaction: do not begin a summon test until this is explicitly completed and reviewed.


## 0.4.1 material reservation safety

Dragon Egg and Eyes of Ender are reserved in player inventories and are not removed when offered. They are consumed only after exactly one newly spawned MythicMobs Ender Dragon is detected and all reserving players are online with the required items. If the ritual is cancelled, the plugin is disabled/restarted, or spawn detection fails, the visual egg/preparation is cleared and the items remain with their owners.


## 0.4.1 complete altar flow

- Place the dragon egg by right-clicking the configured central support with the egg in hand. The egg remains in the player's inventory until the MythicMobs boss is confirmed spawned.
- Right-click each of the four detected cardinal BEDROCK pedestals while holding an Eye of Ender. Exactly one eye is removed immediately and escrowed by player UUID.
- Once the egg is placed and all four eyes are deposited, the plugin sends a clickable `[소환하기]` chat action. Clicking it starts the configured ritual countdown.
- If the ritual is cancelled, reset, plugin disabled, or spawn detection fails, escrowed eyes are returned to their depositor (or retained for refund on next join).
- The MythicMobs spawn location uses the 5.14.0 required `world,x,y,z,yaw,pitch` format. Spawn success is confirmed by detecting exactly one new EnderDragon entity before consuming the dragon egg and committing the four eyes.


## 0.4.1 altar-to-boss flow
- Place the dragon egg by right-clicking the configured central support with the egg in hand.
- Right-click each detected cardinal BEDROCK pedestal while holding an Eye of Ender. Exactly one eye is removed immediately and escrowed by player UUID.
- After the egg and all four eyes are offered, a clickable [소환하기] chat action appears. Clicking it starts the ritual countdown.
- On cancellation, reset, shutdown, or failed spawn detection, escrowed eyes are returned to their depositor, or kept for refund on next join.
- MythicMobs 5.14.0 requires explicit world,x,y,z,yaw,pitch spawn coordinates; the command now supplies all six location values. The plugin consumes the egg and commits the eyes only after exactly one newly spawned EnderDragon is detected.


The bundled replacement AncientDoomDragon.yml intentionally has no `Drops` entry for EndDoomScoreSword: ImmortalBossAltar awards the configured MythicMobs item directly to the final-hit player, so retaining the old MythicMobs drop would duplicate the reward.
