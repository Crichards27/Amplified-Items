# Amplified Items

For AmplifiedSMP on Minecraft Java 26.3. The server needs Java 25, Fabric Loader 0.20.1 or later, and Fabric API for 26.3. Players can join with vanilla Minecraft.

1. Replace the old Amplified Items JAR in `mods` with `amplified-items-1.4.0.jar`.
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

## Craft a weapon

1. Build a flat 3×3 square with a barrel in the center and eight **chiseled stone bricks** around it. Leave open ground 6–12 blocks away for mobs.
2. Use `/amplifieditems list` to find a weapon ID. Run `/amplifieditems ritual recipe venombolt` to see its ingredients. Put them in the barrel.
3. Aim at the top of the barrel and run `/amplifieditems ritual start venombolt`. The server announces the altar coordinates.
4. Stay alive within 24 blocks of the altar. Husks spawn each minute. Keep the altar and ingredients intact, and clear the mobs before time runs out. Friends can help defend.
5. Collect the weapon from the barrel. The recipe ingredients are used when the weapon is forged.

| Time | Weapons |
|---|---|
| 15 minutes | King’s Decree, VenomBolt, SunPiercer, WindWalker, Spider’s Bane |
| 30 minutes | Reaper’s Edge, Leviathan’s Wrath, NightBlade, SolarBlade, BloodFang |

The timer runs on server ticks. Use `/amplifieditems ritual status` to check it. Rituals require a difficulty above Peaceful. Logging out or restarting the server ends a ritual. Ingredients still in the barrel can be used again.

## Server settings

In `config/amplified-items/config.json`, find a weapon under `weapons` and set `"enabled": false` to disable its abilities, passive, recipe, and admin give command. Set it to `true` to turn it back on.

Change `"ritual": { ... }` on each weapon to edit its ingredients or switch its timer between 15 and 30 minutes. Run `/amplifieditems reload` after editing. Operators can use `/amplifieditems give @s venombolt` to test a weapon.

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

Restart after editing. For external hosting, upload `amplified-items-resources-1.4.0.zip`, set `resourcePackPublicUrl` to its direct ZIP link, and leave `resourcePackHostEnabled` false. If you send your pack through `server.properties`, set its URL and SHA-1 there and leave the mod's `resourcePackPublicUrl` empty. Players can use `/amplifieditems pack` to request the pack again.
