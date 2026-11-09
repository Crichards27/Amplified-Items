package club.amplifiedsmp.items;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class AbilityMenu extends ChestMenu {
    static final String NORMAL_USE = "none";
    private static final String CRIT_COMBO = "crit_combo";
    private static final int FIRST = 2;
    private static final int SECOND = 4;
    private static final int NORMAL = 6;
    private static AttachmentType<Map<String, String>> selections;

    private final ServerPlayer owner;
    private final WeaponDefinition weapon;
    private final UUID instance;

    private AbilityMenu(int id, ServerPlayer player, WeaponDefinition weapon, UUID instance) {
        super(MenuType.GENERIC_9x1, id, player.getInventory(), new SimpleContainer(9), 1);
        this.owner = player;
        this.weapon = weapon;
        this.instance = instance;
        String selected = selection(player, weapon);

        if (castable(weapon.primary())) getSlot(FIRST).set(icon(Items.NETHER_STAR, weapon.primary(), selected));
        if (castable(weapon.secondary())) getSlot(SECOND).set(icon(Items.BLAZE_POWDER, weapon.secondary(), selected));
        getSlot(NORMAL).set(icon(Items.PAPER, NORMAL_USE, selected));
        if (weapon.primary().equals(CRIT_COMBO) || weapon.secondary().equals(CRIT_COMBO)) {
            ItemStack info = new ItemStack(Items.BOOK);
            info.set(DataComponents.ITEM_NAME, Component.literal("Critical Combo (automatic)").withStyle(ChatFormatting.GOLD));
            info.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(
                "Land " + AmplifiedItems.CONFIG.spiderComboHits + " critical hits on the same target.").withStyle(ChatFormatting.GRAY))));
            getSlot(8).set(info);
        }
    }

    static void register() {
        selections = AttachmentRegistry.create(Identifier.parse("amplified_items:ability_selections"),
            builder -> builder.persistent(Codec.unboundedMap(Codec.STRING, Codec.STRING)).copyOnDeath());
    }

    static void open(ServerPlayer player, WeaponDefinition weapon) {
        player.stopUsingItem();
        UUID instance = WeaponItems.instance(player.getMainHandItem());
        player.openMenu(new SimpleMenuProvider((id, inventory, viewer) -> new AbilityMenu(id, player, weapon, instance),
            Component.literal(weapon.name() + " Abilities")));
    }

    static String selection(ServerPlayer player, WeaponDefinition weapon) {
        String selected = ((AttachmentTarget) player).getAttachedOrElse(selections, Map.of()).get(weapon.id());
        if (NORMAL_USE.equals(selected) || (selected != null && castable(selected)
                && (selected.equals(weapon.primary()) || selected.equals(weapon.secondary())))) return selected;
        if (castable(weapon.primary())) return weapon.primary();
        if (castable(weapon.secondary())) return weapon.secondary();
        return NORMAL_USE;
    }

    private static boolean castable(String ability) {
        return !ability.equals(NORMAL_USE) && !ability.equals(CRIT_COMBO);
    }

    static String label(String ability) {
        if (ability.equals(NORMAL_USE)) return "Normal item use";
        if (ability.equals("rulers_command")) return "Ruler's Command";
        StringBuilder name = new StringBuilder();
        for (String word : ability.split("_")) {
            if (word.isEmpty()) continue;
            if (!name.isEmpty()) name.append(' ');
            name.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return name.toString();
    }

    private static ItemStack icon(Item item, String ability, String selected) {
        ItemStack stack = new ItemStack(item);
        boolean active = ability.equals(selected);
        stack.set(DataComponents.ITEM_NAME, Component.literal(label(ability)).withStyle(active ? ChatFormatting.GREEN : ChatFormatting.GOLD));
        stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(active ? "Selected" : "Click to select").withStyle(ChatFormatting.GRAY))));
        return stack;
    }

    @Override public boolean stillValid(Player player) {
        ItemStack item = player.getMainHandItem();
        return player == owner && player.isAlive() && !player.isSpectator()
            && weapon.equals(WeaponItems.definition(item)) && Objects.equals(instance, WeaponItems.instance(item));
    }

    @Override public void clicked(int slot, int button, ContainerInput input, Player player) {
        if (player.containerMenu != this || !stillValid(player)) return;
        String ability = switch (slot) {
            case FIRST -> weapon.primary();
            case SECOND -> weapon.secondary();
            case NORMAL -> NORMAL_USE;
            default -> null;
        };
        if (input != ContainerInput.PICKUP || (button != 0 && button != 1) || ability == null
                || (slot != NORMAL && !castable(ability))) {
            sendAllDataToRemote();
            return;
        }
        Map<String, String> choices = new HashMap<>(((AttachmentTarget) owner).getAttachedOrElse(selections, Map.of()));
        choices.put(weapon.id(), ability);
        ((AttachmentTarget) owner).setAttached(selections, Map.copyOf(choices));
        owner.closeContainer();
        Abilities.message(owner, label(ability) + " selected");
    }

    @Override public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

}
