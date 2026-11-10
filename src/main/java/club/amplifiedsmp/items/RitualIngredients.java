package club.amplifiedsmp.items;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.CustomData;

final class RitualIngredients {
    private RitualIngredients() {}

    static boolean known(String key) {
        return switch (key) {
            case RitualRecipe.KINGS_CROWN, RitualRecipe.SUNLIGHT, RitualRecipe.POISON_ARROW -> true;
            default -> !key.equals("minecraft:air") && BuiltInRegistries.ITEM.containsKey(Identifier.parse(key));
        };
    }

    static Component name(String key) {
        return switch (key) {
            case RitualRecipe.KINGS_CROWN -> Component.literal("King's Crown");
            case RitualRecipe.SUNLIGHT -> Component.literal("Sunlight");
            case RitualRecipe.POISON_ARROW -> Component.literal("Arrow of Poison");
            default -> new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(key))).getHoverName();
        };
    }

    static String key(ItemStack stack, boolean requiresPoison) {
        if (requiresPoison && stack.is(Items.TIPPED_ARROW)) {
            PotionContents potion = stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
            if (potion.is(Potions.POISON) || potion.is(Potions.LONG_POISON) || potion.is(Potions.STRONG_POISON))
                return RitualRecipe.POISON_ARROW;
        }
        if (stack.is(Items.GOLDEN_HELMET) || stack.is(Items.GLOWSTONE_DUST)) {
            var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            String ingredient = tag.getStringOr("amplified_ingredient", "");
            if (stack.is(Items.GOLDEN_HELMET) && ingredient.equals("kings_crown")) return RitualRecipe.KINGS_CROWN;
            if (stack.is(Items.GLOWSTONE_DUST) && ingredient.equals("sunlight")) return RitualRecipe.SUNLIGHT;
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
