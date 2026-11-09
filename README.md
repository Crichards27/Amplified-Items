# Amplified Items

A server-side weapon mod for AmplifiedSMP on Fabric 26.3. Press **F** to choose an ability and **right-click** to cast it. See [INSTALL.md](INSTALL.md) for setup, controls, and altar crafting.

## Change the balance

Edit `config/amplified-items/config.json`. Damage and health use Minecraft health points, where two points equal one heart. Cooldowns and effect durations use seconds. Set a weapon’s `enabled` field to `false` to turn it off. The `ritual` section sets its ingredients and a 15- or 30-minute timer. Run `/amplifieditems reload` after editing. Restart for resource pack changes.

## Add a weapon

Copy an entry in the `weapons` array and give it a unique `id`. Set its name, vanilla base item, model, abilities, and cooldowns. `examples/royal-harpoon.json` shows an example. Put its item definition, model, and textures in the resource pack.

Use the ability and passive IDs in `config.example.json`. For a new active ability, add its Java handler to `Abilities.init()`.

## Build

Use JDK 25 and run `./gradlew build`, or `gradlew.bat build` on Windows. The JAR is in `build/libs`, and the resource pack is in `build/distributions`.

You can also build with `python3 tools/build_direct.py`. Add `--verification` to build the test JAR for a disposable Fabric server. The test JAR changes the test world and stops the server after the checks finish.
