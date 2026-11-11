package club.amplifiedsmp.items;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import java.util.*;

public final class WeaponItems {
    private static final String CONTROLS = "Use /ability 1 or /ability 2. Run /ability for descriptions.";

    private WeaponItems() {}
    public static void validate(WeaponConfig config) {
        for (var w : config.weapons) {
            Identifier item = Identifier.parse(w.baseItem());
            if (!BuiltInRegistries.ITEM.containsKey(item) || item.equals(Identifier.parse("minecraft:air")))
                throw new IllegalArgumentException("Unknown base item: " + w.baseItem());
            Identifier.parse(w.model());
            for (String ingredient : w.ritual().ingredients().keySet()) {
                if (!RitualIngredients.known(ingredient))
                    throw new IllegalArgumentException("Unknown ritual ingredient on " + w.id() + ": " + ingredient);
            }
            if (!Abilities.REGISTRY.containsKey(w.primary()) || !Abilities.REGISTRY.containsKey(w.secondary()))
                throw new IllegalArgumentException("Unknown active ability on " + w.id());
        }
    }
    public static ItemStack create(WeaponDefinition w) {
        var stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(w.baseItem())));
        stack.set(DataComponents.MAX_STACK_SIZE, 1);
        if (!w.id().equals("kings_crown")) stack.set(DataComponents.UNBREAKABLE, net.minecraft.util.Unit.INSTANCE);
        stack.set(DataComponents.ITEM_NAME, Component.literal(w.name()).withStyle(ChatFormatting.GOLD));
        stack.set(DataComponents.ITEM_MODEL, Identifier.parse(w.model()));
        var lore = new ArrayList<Component>();
        lore.add(Component.literal(CONTROLS).withStyle(ChatFormatting.YELLOW));
        for (String line : w.lore()) lore.add(Component.literal(line).withStyle(ChatFormatting.GRAY));
        stack.set(DataComponents.LORE, new ItemLore(lore));
        var data = new CompoundTag();
        data.putString("cw_weapon", w.id());
        data.putString("cw_instance", UUID.randomUUID().toString());
        if (w.id().equals("kings_crown")) {
            data.putString("amplified_ingredient", "kings_crown");
            crownEquipment(stack);
        }
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        return stack;
    }
    public static boolean hasPassive(ItemStack stack, String passive) {
        WeaponDefinition definition = definition(stack);
        return definition != null && definition.passive().equals(passive);
    }
    public static WeaponDefinition definition(ItemStack stack) {
        String id = weaponId(stack);
        if (id.isEmpty()) return null;
        WeaponDefinition weapon = AmplifiedItems.CONFIG.enabledWeapon(id);
        return weapon != null && stack.is(BuiltInRegistries.ITEM.getValue(Identifier.parse(weapon.baseItem()))) ? weapon : null;
    }
    static String weaponId(ItemStack stack) {
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        return custom == null ? "" : custom.copyTag().getStringOr("cw_weapon", "");
    }

    static void upgradeHeldBloodFang(ServerPlayer player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.is(Items.NETHERITE_SWORD) || !weaponId(stack).equals("bloodfang")) return;
        WeaponDefinition weapon = AmplifiedItems.CONFIG.enabledWeapon("bloodfang");
        if (weapon != null && weapon.baseItem().equals("minecraft:netherite_axe")) {
            player.setItemInHand(hand, stack.transmuteCopy(Items.NETHERITE_AXE));
        }
    }

    static void updateControls(ItemStack stack) {
        if (definition(stack) == null) return;
        ItemLore lore = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        if (!lore.lines().isEmpty() && lore.lines().getFirst().getString().equals(CONTROLS)) return;
        var lines = new ArrayList<Component>();
        lines.add(Component.literal(CONTROLS).withStyle(ChatFormatting.YELLOW));
        for (Component line : lore.lines()) {
            String text = line.getString();
            if (text.contains("Press F") || text.contains("choose an ability") || text.contains("Right-click to use it")) continue;
            String updated = WeaponConfig.abilityLore(text);
            lines.add(updated.equals(text) ? line : Component.literal(updated).withStyle(line.getStyle()));
        }
        stack.set(DataComponents.LORE, new ItemLore(lines));
    }

    static void upgradeIngredients(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            String ingredient = data(stack).getStringOr("amplified_ingredient", "");
            if (ingredient.equals("sunlight") && stack.is(Items.GLOWSTONE_DUST)) {
                stack.set(DataComponents.ITEM_MODEL, Identifier.parse("amplified_items:sunlight"));
            }
            if (ingredient.equals("kings_crown") && (stack.is(Items.GOLDEN_HELMET) || stack.is(Items.NETHERITE_HELMET))) {
                if (stack.is(Items.GOLDEN_HELMET)) {
                    stack = stack.transmuteCopy(Items.NETHERITE_HELMET);
                    inventory.setItem(slot, stack);
                }
                stack.set(DataComponents.ITEM_MODEL, Identifier.parse("amplified_items:kings_crown"));
                crownEquipment(stack);
            }
            updateControls(stack);
        }
    }

    private static void crownEquipment(ItemStack stack) {
        stack.set(DataComponents.EQUIPPABLE, net.minecraft.world.item.equipment.Equippable.builder(net.minecraft.world.entity.EquipmentSlot.HEAD)
            .setEquipSound(net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_NETHERITE)
            .setAsset(net.minecraft.resources.ResourceKey.create(net.minecraft.world.item.equipment.EquipmentAssets.ROOT_ID,
                Identifier.parse("amplified_items:kings_crown"))).build());
    }

    public static UUID instance(ItemStack stack) {
        try { return UUID.fromString(data(stack).getStringOr("cw_instance", "")); }
        catch (IllegalArgumentException e) { return null; }
    }
    private static CompoundTag data(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }
}
