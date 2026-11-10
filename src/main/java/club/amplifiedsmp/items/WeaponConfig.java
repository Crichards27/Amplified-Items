package club.amplifiedsmp.items;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class WeaponConfig {
    public static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int CONFIG_VERSION = 7;
    public int configVersion = CONFIG_VERSION;
    public double executionMultiplier = 1.30;
    public int executionSeconds = 30;
    public double executionRange = 24;
    public int authorityOtherPlayers = 3;
    public double authorityRadius = 12;
    public double authorityReduction = 0.10;
    public double venomRadius = 4;
    public int venomPoisonSeconds = 10;
    public double venomSplashDamage = 2;
    public double venomDirectDamage = 6;
    public static final double MAX_GRAPPLE_RANGE = 16;
    public double grappleRange = MAX_GRAPPLE_RANGE;
    public double grappleVelocity = 1.5;
    public double soulStealerDamage = 4;
    public double soulStealerRange = 6;
    public int shadowStepSeconds = 6;
    public double reaperBonusHealth = 10;
    public double flamingBurstDamage = 4;
    public int flamingBurstFireSeconds = 3;
    public double solarFlareDamage = 4;
    public double solarFlareRadius = 3;
    public int solarFlareBlindnessSeconds = 5;
    public double tideDamage = 4;
    public double tideRange = 24;
    public double tideRadius = 2.5;
    public int riptideSeconds = 10;
    public double riptideVelocity = 1.8;
    public int phantomPhaseSeconds = 5;
    public int hollowMoonSeconds = 15;
    public double heatwaveDamage = 3;
    public double heatwaveRange = 4.5;
    public int heatwaveFireSeconds = 3;
    public double sunflareDamage = 4;
    public double sunflareRadius = 5;
    public int sunflareFireSeconds = 4;
    public double windburstRadius = 5;
    public double windburstKnockback = 1.45;
    public int updraftSeconds = 8;
    public int spiderComboHits = 5;
    public int spiderComboGapSeconds = 8;
    public int spiderPoisonSeconds = 60;
    public int bloodRushSeconds = 8;
    public double crimsonCleaveDamage = 6;
    public double crimsonCleaveRange = 4.5;
    public int bloodlustHits = 5;
    public int bloodlustSeconds = 6;
    public int bloodlustBuildSeconds = 10;
    public double bloodlustBonus = 0.20;
    public int summonMaxSeconds = 60;
    public int summonIdleSeconds = 10;
    public double summonMeleeDamage = 4;
    public double summonTargetRange = 24;
    public List<String> captureDenylist = new ArrayList<>();
    public boolean allowCapturingOtherPlayersPets = false;
    public boolean pvpHeadDrops = true;
    public Map<String, String> altarEventRequirements = new LinkedHashMap<>();
    public boolean resourcePackHostEnabled = false;
    public String resourcePackBindAddress = "0.0.0.0";
    public int resourcePackPort = 8190;
    public String resourcePackPublicUrl = "";
    public boolean resourcePackRequired = true;
    public List<WeaponDefinition> weapons = new ArrayList<>(List.of(
        new WeaponDefinition("kings_decree", "King's Decree", "minecraft:iron_sword", "amplified_items:kings_decree",
            "rulers_command", "execution", "rulers_authority", 3, 45,
            List.of("Ruler's Command: capture, summon, or recall a mob", "Execution", "Authority: nearby players reduce damage")),
        new WeaponDefinition("venombolt", "VenomBolt", "minecraft:crossbow", "amplified_items:venombolt",
            "venom_shot", "grapple_hook", "none", 8, 5,
            List.of("Venom Shot", "Grapple Hook", "Poison II splash for 10 seconds")),
        new WeaponDefinition("reapers_edge", "Reaper's Edge", "minecraft:netherite_sword", "amplified_items:reapers_edge",
            "soul_stealer", "shadow_step", "reaper_vitality", 15, 40,
            List.of("Soul Stealer", "Shadow Step", "+5 max hearts. Speed I at 15 hearts.")),
        new WeaponDefinition("sunpiercer", "SunPiercer", "minecraft:crossbow", "amplified_items:sunpiercer",
            "flaming_burst", "solar_flare", "flame_crossbow", 8, 18,
            List.of("Flaming Burst", "Solar Flare", "Normal arrows ignite.")),
        new WeaponDefinition("leviathans_wrath", "Leviathan's Wrath", "minecraft:trident", "amplified_items:leviathans_wrath",
            "tide", "riptide", "ocean_blessing", 10, 35,
            List.of("Tide", "Riptide", "Water Breathing and Dolphin's Grace in water.")),
        new WeaponDefinition("nightblade", "NightBlade", "minecraft:netherite_sword", "amplified_items:nightblade",
            "phantom_phase", "hollow_moon", "night_cycle", 45, 40,
            List.of("Phantom Phase", "HollowMoon", "Stronger at night. Night Vision while held.")),
        new WeaponDefinition("solarblade", "SolarBlade", "minecraft:netherite_sword", "amplified_items:solarblade",
            "heatwave", "sunflare", "solar_cycle", 8, 20,
            List.of("Heatwave", "Sunflare", "Melee hits sweep and burn. Stronger by day.")),
        new WeaponDefinition("windwalker", "WindWalker", "minecraft:bow", "amplified_items:windwalker",
            "windburst", "updraft", "windwalker", 10, 20,
            List.of("Windburst", "Updraft", "Tap right-click: Slow Falling", "Hold and release: Shoot without ammo")),
        new WeaponDefinition("spiders_bane", "Spider's Bane", "minecraft:diamond_sword", "amplified_items:spiders_bane",
            "crit_combo", "web_grapple", "web_phase", 30, 5,
            List.of("Land 5 crits on one target: Poison II", "Web Grapple", "Walk through cobwebs at normal speed.")),
        new WeaponDefinition("bloodfang", "BloodFang", "minecraft:netherite_sword", "amplified_items:bloodfang",
            "blood_rush", "crimson_cleave", "bloodlust", 20, 12,
            List.of("Blood Rush", "Crimson Cleave", "Land 5 hits for a temporary damage boost."))
    ));

    public static WeaponConfig load(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        if (!Files.exists(path)) Files.writeString(path, JSON.toJson(new WeaponConfig()));
        var document = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        WeaponConfig config = JSON.fromJson(document, WeaponConfig.class);
        if (config == null) throw new IllegalArgumentException("Config is empty");
        int previousVersion = document.has("configVersion") ? config.configVersion : 1;
        if (previousVersion < CONFIG_VERSION) {
            WeaponConfig defaults = new WeaponConfig();
            if (config.weapons == null) throw new IllegalArgumentException("weapons cannot be null");
            int firstNewWeapon = previousVersion < 2 ? 2 : previousVersion < 3 ? 5 : defaults.weapons.size();
            for (var weapon : defaults.weapons.subList(firstNewWeapon, defaults.weapons.size())) {
                if (config.weapons.stream().noneMatch(w -> w.id().equals(weapon.id()))) config.weapons.add(weapon);
            }
            config.grappleRange = Math.min(config.grappleRange, MAX_GRAPPLE_RANGE);
            config.weapons.replaceAll(w -> {
                var ingredients = w.ritual().ingredients();
                if (previousVersion < 6) {
                    ingredients = defaults.weapons.stream().filter(d -> d.id().equals(w.id()))
                        .findFirst().map(d -> d.ritual().ingredients()).orElse(ingredients);
                }
                var lore = w.lore();
                if (previousVersion < 5 && lore != null) lore = lore.stream().map(WeaponConfig::abilityLore).toList();
                return new WeaponDefinition(w.id(), w.name(), w.baseItem(), w.model(), w.primary(), w.secondary(),
                    w.passive(), w.primaryCooldownSeconds(), w.secondaryCooldownSeconds(), lore, w.enabled(),
                    new RitualRecipe(RitualRecipe.withHeadCost(w.id(), ingredients)));
            });
            config.configVersion = CONFIG_VERSION;
            config.validate();
            var merged = JSON.toJsonTree(config).getAsJsonObject();
            merged.entrySet().forEach(entry -> document.add(entry.getKey(), entry.getValue()));
            Path temporary = Files.createTempFile(path.getParent(), "items-config-", ".json");
            try {
                Files.writeString(temporary, JSON.toJson(document));
                try {
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        config.validate();
        return config;
    }

    public WeaponDefinition enabledWeapon(String id) {
        return weapons.stream().filter(w -> w.enabled() && w.id().equals(id)).findFirst().orElse(null);
    }

    public void validate() {
        bounded(executionMultiplier, 1, 10, "executionMultiplier");
        bounded(executionSeconds, 1, 3600, "executionSeconds");
        bounded(executionRange, 1, 128, "executionRange");
        bounded(authorityOtherPlayers, 1, 100, "authorityOtherPlayers");
        bounded(authorityRadius, 1, 128, "authorityRadius");
        bounded(authorityReduction, 0, 0.9, "authorityReduction");
        bounded(venomRadius, 0.5, 32, "venomRadius");
        bounded(venomPoisonSeconds, 1, 300, "venomPoisonSeconds");
        bounded(venomSplashDamage, 0, 100, "venomSplashDamage");
        bounded(venomDirectDamage, 0, 100, "venomDirectDamage");
        bounded(grappleRange, 1, MAX_GRAPPLE_RANGE, "grappleRange");
        bounded(grappleVelocity, 0.1, 3, "grappleVelocity");
        bounded(soulStealerDamage, 0.1, 100, "soulStealerDamage");
        bounded(soulStealerRange, 1, 32, "soulStealerRange");
        bounded(shadowStepSeconds, 1, 60, "shadowStepSeconds");
        bounded(reaperBonusHealth, 0, 40, "reaperBonusHealth");
        bounded(flamingBurstDamage, 0.1, 100, "flamingBurstDamage");
        bounded(flamingBurstFireSeconds, 1, 20, "flamingBurstFireSeconds");
        bounded(solarFlareDamage, 0.1, 100, "solarFlareDamage");
        bounded(solarFlareRadius, 0.5, 12, "solarFlareRadius");
        bounded(solarFlareBlindnessSeconds, 1, 30, "solarFlareBlindnessSeconds");
        bounded(tideDamage, 0.1, 100, "tideDamage");
        bounded(tideRange, 1, 48, "tideRange");
        bounded(tideRadius, 0.5, 8, "tideRadius");
        bounded(riptideSeconds, 1, 30, "riptideSeconds");
        bounded(riptideVelocity, 0.1, 3, "riptideVelocity");
        bounded(phantomPhaseSeconds, 1, 30, "phantomPhaseSeconds");
        bounded(hollowMoonSeconds, 1, 60, "hollowMoonSeconds");
        bounded(heatwaveDamage, 0.1, 100, "heatwaveDamage");
        bounded(heatwaveRange, 1, 12, "heatwaveRange");
        bounded(heatwaveFireSeconds, 1, 20, "heatwaveFireSeconds");
        bounded(sunflareDamage, 0.1, 100, "sunflareDamage");
        bounded(sunflareRadius, 1, 16, "sunflareRadius");
        bounded(sunflareFireSeconds, 1, 20, "sunflareFireSeconds");
        bounded(windburstRadius, 1, 16, "windburstRadius");
        bounded(windburstKnockback, 0.1, 3, "windburstKnockback");
        bounded(updraftSeconds, 1, 60, "updraftSeconds");
        bounded(spiderComboHits, 1, 20, "spiderComboHits");
        bounded(spiderComboGapSeconds, 1, 60, "spiderComboGapSeconds");
        bounded(spiderPoisonSeconds, 1, 300, "spiderPoisonSeconds");
        bounded(bloodRushSeconds, 1, 60, "bloodRushSeconds");
        bounded(crimsonCleaveDamage, 0.1, 100, "crimsonCleaveDamage");
        bounded(crimsonCleaveRange, 1, 12, "crimsonCleaveRange");
        bounded(bloodlustHits, 1, 20, "bloodlustHits");
        bounded(bloodlustSeconds, 1, 60, "bloodlustSeconds");
        bounded(bloodlustBuildSeconds, 1, 60, "bloodlustBuildSeconds");
        bounded(bloodlustBonus, 0, 1, "bloodlustBonus");
        bounded(summonMaxSeconds, 1, 3600, "summonMaxSeconds");
        bounded(summonIdleSeconds, 1, 300, "summonIdleSeconds");
        bounded(summonMeleeDamage, 0.1, 100, "summonMeleeDamage");
        bounded(summonTargetRange, 1, 64, "summonTargetRange");
        bounded(resourcePackPort, 1024, 65535, "resourcePackPort");
        if (captureDenylist == null || weapons == null || resourcePackPublicUrl == null || resourcePackBindAddress == null)
            throw new IllegalArgumentException("Lists and resource-pack settings cannot be null");
        Set<String> ids = new HashSet<>();
        for (WeaponDefinition w : weapons) {
            w.validate();
            if (!ids.add(w.id())) throw new IllegalArgumentException("Duplicate weapon id: " + w.id());
            if (w.primary() == null || w.secondary() == null || !w.primary().matches("[a-z0-9_]+") || !w.secondary().matches("[a-z0-9_]+"))
                throw new IllegalArgumentException("Invalid ability id on " + w.id());
            if (!Set.of("rulers_authority", "reaper_vitality", "flame_crossbow", "ocean_blessing", "night_cycle", "solar_cycle",
                    "windwalker", "web_phase", "bloodlust", "none").contains(w.passive()))
                throw new IllegalArgumentException("Unknown passive on " + w.id());
        }
        if (altarEventRequirements == null) throw new IllegalArgumentException("altarEventRequirements cannot be null");
        for (var event : altarEventRequirements.entrySet()) {
            if (!ids.contains(event.getKey()) || event.getValue() == null || !event.getValue().matches("[a-z0-9_]+"))
                throw new IllegalArgumentException("Invalid altar event requirement for " + event.getKey());
        }
        if (!resourcePackPublicUrl.isBlank()) {
            var u = java.net.URI.create(resourcePackPublicUrl);
            if (!("http".equals(u.getScheme()) || "https".equals(u.getScheme())) || u.getHost() == null)
                throw new IllegalArgumentException("resourcePackPublicUrl must be a public http(s) ZIP URL");
        }
    }

    static String abilityLore(String line) {
        return switch (line) {
            case "Right-click a mob: capture", "Aim at a mob and press F to capture" -> "Aim at a nearby mob to capture it";
            case "Normal arrows ignite while Flaming Burst recharges." -> "Normal arrows ignite.";
            case "Left-click: Windburst" -> "Windburst";
            case "Sneak + left-click: Updraft" -> "Updraft";
            default -> {
                for (String prefix : List.of("Sneak + right-click: ", "Right-click: ", "Sneak + F: ", "F: ")) {
                    if (line.startsWith(prefix)) yield line.substring(prefix.length());
                }
                yield line;
            }
        };
    }

    private static void bounded(double n, double min, double max, String name) {
        if (!Double.isFinite(n) || n < min || n > max) throw new IllegalArgumentException(name + " must be " + min + ".." + max);
    }
}
