package club.amplifiedsmp.items;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record RitualRecipe(int minutes, Map<String, Integer> ingredients) {
    static final String KINGS_CROWN = "amplified_items:kings_crown";
    static final String SUNLIGHT = "amplified_items:sunlight";
    static final String POISON_ARROW = "amplified_items:poison_arrow";

    public RitualRecipe {
        if (ingredients != null) ingredients = Collections.unmodifiableMap(new LinkedHashMap<>(ingredients));
    }

    static RitualRecipe defaults(String id, String baseItem) {
        if (id == null || baseItem == null) return null;
        return switch (id) {
            case "kings_decree" -> recipe(15,
                ingredient(KINGS_CROWN, 1), ingredient("netherite_ingot", 8), ingredient("diamond_block", 16), ingredient("nether_star", 1));
            case "venombolt" -> recipe(15,
                ingredient("iron_block", 32), ingredient(POISON_ARROW, 16), ingredient("skeleton_skull", 3), ingredient("crossbow", 8));
            case "reapers_edge" -> recipe(30,
                ingredient("wither_skeleton_skull", 3), ingredient("nether_star", 1), ingredient("echo_shard", 16),
                ingredient("gold_block", 64), ingredient("netherite_ingot", 4), ingredient("netherite_hoe", 2));
            case "sunpiercer" -> recipe(15,
                ingredient("crossbow", 3), ingredient("gold_block", 64), ingredient("diamond_block", 32),
                ingredient("netherite_ingot", 4), ingredient("blaze_rod", 64), ingredient("magma_cream", 32));
            case "leviathans_wrath" -> recipe(30,
                ingredient("trident", 1), ingredient("prismarine", 64), ingredient("dark_prismarine", 64),
                ingredient("nautilus_shell", 16), ingredient("heart_of_the_sea", 1), ingredient("diamond_block", 32));
            case "nightblade" -> recipe(30,
                ingredient("netherite_ingot", 4), ingredient("diamond_block", 16), ingredient("skeleton_skull", 3),
                ingredient("netherite_sword", 2), ingredient("sculk_shrieker", 16), ingredient("sculk_catalyst", 32));
            case "solarblade" -> recipe(30,
                ingredient("magma_cream", 64), ingredient("blaze_rod", 64), ingredient("diamond_block", 32),
                ingredient("netherite_ingot", 8), ingredient("skeleton_skull", 6), ingredient(SUNLIGHT, 1));
            case "windwalker" -> recipe(15,
                ingredient("wind_charge", 128), ingredient("breeze_rod", 64), ingredient("guster_banner_pattern", 16),
                ingredient("flow_armor_trim_smithing_template", 32), ingredient("netherite_ingot", 4), ingredient("iron_block", 64));
            case "spiders_bane" -> recipe(15,
                ingredient("cobweb", 256), ingredient("iron_block", 48), ingredient("netherite_ingot", 6),
                ingredient("trident", 2), ingredient("diamond_block", 32));
            case "bloodfang" -> recipe(30,
                ingredient("redstone_block", 128), ingredient("netherite_ingot", 6), ingredient("netherite_axe", 2),
                ingredient("powered_rail", 64), ingredient("nether_star", 1));
            default -> new RitualRecipe(15, baseItem.equals("minecraft:diamond")
                ? Map.of(baseItem, 3) : Map.of(baseItem, 1, "minecraft:diamond", 2));
        };
    }

    private static Map.Entry<String, Integer> ingredient(String item, int count) {
        return Map.entry(item.contains(":") ? item : "minecraft:" + item, count);
    }

    @SafeVarargs
    private static RitualRecipe recipe(int minutes, Map.Entry<String, Integer>... items) {
        var ingredients = new LinkedHashMap<String, Integer>();
        for (var item : items) ingredients.put(item.getKey(), item.getValue());
        return new RitualRecipe(minutes, ingredients);
    }

    void validate(String weapon) {
        if (minutes != 15 && minutes != 30) throw new IllegalArgumentException("Ritual minutes must be 15 or 30 on " + weapon);
        if (ingredients == null || ingredients.isEmpty()) throw new IllegalArgumentException("Missing ritual ingredients on " + weapon);
        for (var ingredient : ingredients.entrySet()) {
            if (!ingredient.getKey().matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || ingredient.getValue() == null || ingredient.getValue() < 1)
                throw new IllegalArgumentException("Invalid ritual ingredient on " + weapon + ": " + ingredient.getKey());
        }
    }
}
