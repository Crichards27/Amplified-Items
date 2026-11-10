# Amplified Items

For AmplifiedSMP on Minecraft Java 26.3. The server needs Java 25, Fabric Loader 0.20.1 or later, and Fabric API for 26.3. Players can join with vanilla Minecraft.

1. Replace the old Amplified Items JAR in `mods` with `amplified-items-1.6.3.jar`.
2. Start the server. It updates `config/amplified-items/config.json`.
3. Accept the server resource pack in Minecraft to see the custom textures.

## Controls

1. Hold the weapon in your main hand and press **F** to open its ability menu.
2. Click the ability you want. The menu closes and remembers your choice for that weapon.
3. **Right-click** to cast the selected ability. The first available ability is selected by default.

**Sneak + right-click** uses the item normally. You can also choose **Normal item use** in the menu. For WindWalker, a short normal-use tap toggles Slow Falling and a full draw shoots without ammo.

- If you rebound **Swap Item With Offhand**, use that key to open the menu.
- With **Ruler's Command** selected, aim at a mob within five blocks and right-click to capture it. Right-click again to summon or recall it.
- Spider's Bane's Critical Combo triggers automatically on the fifth critical hit against the same target. Select Web Grapple to use it with right-click.
- VenomBolt's grapple and Spider's Bane's web grapple reach up to 16 blocks.

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

Restart after editing. For external hosting, upload `amplified-items-resources-1.6.3.zip`, set `resourcePackPublicUrl` to its direct ZIP link, and leave `resourcePackHostEnabled` false. If you send your pack through `server.properties`, set its URL and SHA-1 there and leave the mod's `resourcePackPublicUrl` empty. Players can use `/amplifieditems pack` to request the pack again.
