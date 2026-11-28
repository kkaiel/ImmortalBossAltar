# ImmortalBossAltar 0.4.3

## Manual altar coordinate setup

The altar no longer guesses cardinal pedestal coordinates. This avoids incorrect coordinates after End world regeneration or custom fountain layouts.

1. In the End, look at the central fountain's top support BEDROCK and run `/bossaltar setcenter`.
2. Look directly at the first intended pedestal BEDROCK and run `/bossaltar setpedestal 1`.
3. Repeat for the other three pedestals using `2`, `3`, and `4` in any order.
4. Run `/bossaltar status` and verify the four numbered coordinates before installing frames.

The number is a user-defined slot, not a compass direction. It is assigned to the exact BEDROCK block targeted by the player's crosshair.

## Offerings

- Place one Dragon Egg by right-clicking the central support BEDROCK while holding the egg.
- Place exactly one Glow Item Frame on each of the four manually configured pedestal blocks.
- Insert one Eye of Ender into each Glow Item Frame.
- Ordinary Item Frames do not count.
- The player who inserts the fourth and final Eye receives the clickable summon prompt.
- Only that same player can execute `/bossaltar summon`; other players are denied.
- The summon prompt is not broadcast to other players.

## Commands

- `/bossaltar setcenter`
- `/bossaltar setpedestal 1`
- `/bossaltar setpedestal 2`
- `/bossaltar setpedestal 3`
- `/bossaltar setpedestal 4`
- `/bossaltar status`
- `/bossaltar summon`
- `/bossaltar reset`
- `/bossaltar reload`

Run all setup commands while looking directly at the intended BEDROCK block. Use `/bossaltar status` to confirm saved coordinates.
