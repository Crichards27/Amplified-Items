package club.amplifiedsmp.items;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record RitualRecipe(int minutes, Map<String, Integer> ingredients) {
    public RitualRecipe {
        if (ingredients != null) ingredients = Collections.unmodifiableMap(new LinkedHashMap<>(ingredients));
    }

    static RitualRecipe defaults(String id, String baseItem) {
        if (id == null || baseItem == null) return null;
        return switch (id) {
            case "kings_decree" -> recipe(15, baseItem, "gold_block", 4, "diamond", 1);
            case "venombolt" -> recipe(15, baseItem, "fermented_spider_eye", 8, "emerald", 4);
            case "reapers_edge" -> recipe(30, baseItem, "nether_star", 1, "ghast_tear", 4);
            case "sunpiercer" -> recipe(15, baseItem, "blaze_rod", 8, "gold_block", 4);
            case "leviathans_wrath" -> recipe(30, baseItem, "heart_of_the_sea", 1, "prismarine_crystals", 8);
            case "nightblade" -> recipe(30, baseItem, "phantom_membrane", 8, "echo_shard", 4);
            case "solarblade" -> recipe(30, baseItem, "blaze_rod", 8, "nether_star", 1);
            case "windwalker" -> recipe(15, baseItem, "breeze_rod", 8, "feather", 4);
            case "spiders_bane" -> recipe(15, baseItem, "cobweb", 16, "fermented_spider_eye", 8);
            case "bloodfang" -> recipe(30, baseItem, "ghast_tear", 2, "redstone", 16);
            default -> new RitualRecipe(15, baseItem.equals("minecraft:diamond")
                ? Map.of(baseItem, 3) : Map.of(baseItem, 1, "minecraft:diamond", 2));
        };
    }

    private static RitualRecipe recipe(int minutes, String baseItem, String first, int firstCount, String second, int secondCount) {
        var ingredients = new LinkedHashMap<String, Integer>();
        ingredients.put(baseItem, 1);
        ingredients.merge("minecraft:" + first, firstCount, Integer::sum);
        ingredients.merge("minecraft:" + second, secondCount, Integer::sum);
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
