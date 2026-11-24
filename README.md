# ImmortalBossAltar

Separate Paper plugin for the End altar that summons the existing MythicMobs `AncientDoomDragon` and awards the existing `EndDoomScoreSword` (`종언의 심판`) to the credited final hitter.

## Current status: 0.1.0 development scaffold

This is an initial source package, **not a production-ready release**. It has not been compiled against the user's exact Paper 26.3 server or tested with MythicMobs 5.14.0. The build currently targets Paper API 1.21.11 / Java 21 for broad API compatibility. Verify and align the API version with the actual server before deployment.

## Setup

1. Install MythicMobs and load the provided custom mob/item files.
2. Install this plugin in a test server only.
3. Stand at the intended altar center block in the End and run `/bossaltar setcenter` as an OP.
4. The four pedestals are expected at cardinal offsets of 2 blocks on the same Y level, using `END_STONE_BRICKS` by default.
5. Place the dragon egg by right-clicking the center block while holding it; then offer one Eye of Ender at each pedestal; finally right-click the egg block to begin the ritual.

## Important limitations before live use

- This initial scaffold keeps state in memory only. Restart recovery, durable contribution logs, and safe escrow/refund are not implemented yet.
- Portal handling currently snapshots/removes `END_PORTAL` blocks in a small cube and restores portal material, but does not preserve full block-state metadata or handle chunk unload/crash recovery. Do not use on a live world yet.
- The exact MythicMobs command syntax and item-give syntax must be verified against the installed 5.14.0 build. The plugin uses console commands and checks the newly spawned dragon by UUID difference; ambiguous detection intentionally disables reward tracking.
- Vanilla dragon defeat state is in-memory and resets on restart. Persistent per-world dragon progression is a required next step.
- If a boss dies while the final hitter is offline, no automatic offline inventory delivery is implemented yet.
- Existing `Drops: - EndDoomScoreSword 1 1` must be removed from `AncientDoomDragon.yml` before use to avoid a duplicate ground drop.
- The altar center and pedestal layout must be validated against the actual bedrock fountain coordinates before testing.

## Build

Use Java 21 and Gradle:

```bash
gradle clean build
```

The GitHub Actions workflow builds the jar and uploads it as an artifact.
