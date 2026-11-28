# ImmortalBossAltar 0.4.2

Separate Paper plugin for the End altar and MythicMobs `AncientDoomDragon` encounter.

## Build

- Java 25
- Gradle 9.2.1 (GitHub Actions uses the Gradle setup action)
- Paper API 1.21.11 compile baseline; intended to run on the server's Paper 26.3 runtime.
- MythicMobs is a soft dependency and is invoked through its console command interface.

## Install

1. Build with GitHub Actions or `gradle clean build`.
2. Put the generated `ImmortalBossAltar-0.4.2.jar` into `plugins/`.
3. Ensure MythicMobs loads the existing `AncientDoomDragon` mob and `EndDoomScoreSword` item.
4. Restart the server.

## Configure the altar

1. Go to the End exit fountain.
2. Aim at the TOP BEDROCK SUPPORT BLOCK of the central pillar and run `/bossaltar setcenter`.
3. The plugin stores the air block directly above that support block as the dragon-egg position.
4. The plugin scans the four cardinal directions for the surrounding BEDROCK pedestals.
5. Run `/bossaltar status` and compare the four detected pedestal coordinates with the actual fountain before placing any offerings.

## Offering layout (0.4.2)

- Place one Dragon Egg by right-clicking the configured central support BEDROCK while holding the egg. The egg is consumed from the hand and appears in the air block above the center.
- Place exactly one GLOW ITEM FRAME on each of the four detected cardinal BEDROCK pedestals.
- Put exactly one Eye of Ender into each Glow Item Frame. Other items are rejected or removed from a pedestal frame.
- A filled Eye frame cannot be rotated or emptied by ordinary right-clicking.
- The summon prompt appears only when the central Dragon Egg exists and all four pedestals each have exactly one Glow Item Frame containing an Eye of Ender.
- If any pedestal frame or Eye is missing, `/bossaltar summon` is rejected with the missing direction(s). No offering is consumed.
- After the MythicMobs boss is detected successfully, all four Glow Item Frames and their displayed Eyes are removed, and the central Dragon Egg is consumed.
- If the boss spawn fails, the frames and displayed Eyes remain. The reserved Dragon Egg is returned.

## Dragon Egg interaction

- Right-clicking the altar Dragon Egg is blocked; vanilla teleport behavior is denied.
- Left-clicking the altar Dragon Egg retrieves exactly one Dragon Egg directly to the player's inventory (or drops it at the player's feet if the inventory is full).
- Ordinary block breaking is cancelled to avoid duplicate drops.

## Commands

- `/bossaltar setcenter` (admin; look at the central pillar's top support bedrock)
- `/bossaltar status` (shows altar coordinates and state, vanilla dragon defeat, encounter state, and permanent completion record)
- `/bossaltar summon` (player; starts the ritual only after all offerings are validated)
- `/bossaltar reset` (admin; clears the current preparation and returns the reserved Dragon Egg; does not erase the permanent final-boss record)
- `/bossaltar reload`

## Persistent data

`plugins/ImmortalBossAltar/data.yml` stores vanilla dragon defeat, active boss UUID, portal block coordinates, permanent final-boss completion record, and pending item refunds.

## Important testing notes

This source revision has not been compiled against the user's exact Paper 26.3 server or tested with their MythicMobs installation. Treat it as a development build and test on a backup/test server first. Confirm the pedestal coordinates with `/bossaltar status` before placing the Glow Item Frames.
