# Amplified Items

For AmplifiedSMP on Minecraft Java 26.3. The server needs Java 25, Fabric Loader 0.20.1 or later, and Fabric API for 26.3. Players can join with vanilla Minecraft.

1. Replace the old Amplified Items JAR in `mods` with `amplified-items-1.7.0.jar`.
2. Start the server. It updates `config/amplified-items/config.json`.
3. Accept the server resource pack in Minecraft to see the custom textures.

## Singleplayer

Install `amplified-items-1.7.0.jar` and Fabric API 0.165.0+26.3 or newer for 26.3 in your Minecraft instance’s `mods` folder. The textures load from the mod.

Enable commands in your world, then run `/amplifieditems give @s bloodfang`. Hold the axe and use `/ability 1` or `/ability 2`. To claim an altar, switch to Survival and bring the listed ingredients.

## Controls

Hold your weapon in your main hand and use `/ability 1` or `/ability 2`. Run `/ability` to read descriptions and cooldowns. These commands work without operator permissions. A client command-keybind mod can bind them to any keys.

F swaps hands normally. Right-click performs the item’s vanilla action. WindWalker supplies arrows and toggles Slow Falling on a short right-click.

Both active abilities share the weapon’s cooldown. Riptide repeat dashes are limited to once per second during its active window.

## Place and claim an altar

1. As an operator, run `/amplifieditems altar place venombolt ~ ~ ~2` to put a VenomBolt altar on an empty block in the current dimension.
2. The unbreakable altar shows a floating weapon and ingredient list. Players bring the ingredients in their inventory and right-click the altar block in Survival or Adventure mode.
3. The winner gets the weapon immediately. A visual lightning strike and a server announcement mark the claim. The altar disappears.

Each weapon can be claimed once across the server’s dimensions. Unclaimed altars and claim records survive restarts. PvP kills drop the defeated player’s head. Open `source/README.md` in the release ZIP for altar pictures and recipes, or run `/amplifieditems altar recipe <weapon>` in-game.

Existing altars get the new model when their chunks load. Players see it after accepting the updated resource pack.

## Server settings

Use `/amplifieditems altar list` to see the registered sites and claims, `/amplifieditems altar remove <weapon>` to remove a site, and `/amplifieditems altar reset <weapon>` to clear a claim for a new arc. Resetting a claim leaves previously awarded copies in circulation.

In `config/amplified-items/config.json`, set `pvpHeadDrops` to `false` to disable drops. Edit `ritual.ingredients` to change recipes, or add a weapon-to-event mapping under `altarEventRequirements` and use `/amplifieditems altar event <event> open` or `close`. Run `/amplifieditems reload` after editing.

Upgrading from 1.5.1 adds head costs to six recipes. Operators place the new altars in-world. The mod keeps placement and claim records under the world’s `data/amplified-items/altars.json`.

## Textures

If the server already sends the Amplified Items pack, you can keep using it. To host the pack through the mod, open TCP port 8190 and edit these keys in the config. Replace `YOUR_SERVER_IP` with the server's public address.

```json
{
  "resourcePackHostEnabled": true,
  "resourcePackPort": 8190,
  "resourcePackPublicUrl": "http://YOUR_SERVER_IP:8190/amplified-items.zip",
  "resourcePackRequired": true
}
```

Restart after editing. For external hosting, upload `amplified-items-resources-1.7.0.zip`, set `resourcePackPublicUrl` to its direct ZIP link, and leave `resourcePackHostEnabled` false. If you send your pack through `server.properties`, set its URL and SHA-1 there and leave the mod's `resourcePackPublicUrl` empty. Players can use `/amplifieditems pack` to request the pack again.

King’s Crown is a one-time `kings_crown` altar reward with netherite helmet stats. See `source/README.md` for its recipe and the new Reaper heart progression.
