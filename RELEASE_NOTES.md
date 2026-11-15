# Amplified Items 1.8.0

For Minecraft Java 26.3, Java 25, Fabric Loader 0.20.1+ and Fabric API 0.165.0+26.3.

This is a release candidate. Full multiplayer, restart and vanilla-client checks
are still pending.

## King's Decree

Ruler's Command now summons four iron-golem knights to attack the enemy player
you aim at. It no longer captures mobs. The knights wear steel armor with a
purple-and-gold tabard, helmet, sword and shield. Their models follow the golems'
position and facing, but the limbs don't animate yet. Normal iron golems keep
their usual appearance.

Defaults:

- Target range: 24 blocks.
- Lifetime: 60 seconds.
- Cooldown: 45 seconds, shared with Execution.
- One active squad per player and staff.

The cast needs room for all four knights on solid ground. Failed casts don't
start the cooldown. Knights respect PvP and team protection, drop no loot or XP,
and disappear when their owner or target becomes invalid or their time runs out.

## Fixes and cleanup

- Removed the old barrel rituals, ability GUI, F-key interceptor and unused
  living-flags accessor, along with 20 unreferenced textures.
- Removed the cheap crown crafting recipe that bypassed the one-time altar.
- Fixed bundled resource-pack loading after version updates and cleared stale
  pack hashes after shutdown or failed startup.
- Fixed Reaper's Edge removing a stronger Speed effect when put away.
- Updated command help and staff status text for the new controls.
- Kept the example config in sync with the mod's defaults and made ZIP builds
  reproducible.

## Upgrading

Back up the world and `data/amplified-items/` before replacing the JAR. Update any
externally hosted resource pack too.

Config version 10 updates the old default staff cooldown and description while
keeping custom settings. Keep `summons.json`: the migration returns eligible
land mobs from old staffs. Fish, tadpoles, axolotls, dolphins, squid and nautiluses
stay stored for admin recovery rather than being released onto dry land.

## Testing

- Gradle and the direct Python build pass.
- 87 standalone Java assertions and 22 Python tests pass.
- Separate server checks passed 28 visual assertions and 21 combat/passive/altar
  assertions. These don't cover the full suite against the final build.
- The full combat/recovery suite, restart checks and in-game client rendering
  still need to be run. The new aquatic recovery checks compile but haven't
  been run on a server.

See `RELEASING.md` for the test and packaging commands. The server runner requires
explicit EULA acceptance. Never install `ISOLATED-TESTS-ONLY.jar` on a real server.

## Downloads

- `amplified-items-1.8.0.jar`: Fabric mod, including its resource pack.
- `amplified-items-resources-1.8.0.zip`: textures and models for vanilla clients.
- `amplified-items-datapack-1.8.0.zip`: optional Sunlight recipe and advancement.
  The mod already includes this data; the datapack doesn't replace the mod.
- `SHA256SUMS.txt`: artifact checksums.
- `INSTALL.md`: setup and upgrade instructions.
