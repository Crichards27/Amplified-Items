package club.amplifiedsmp.items;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.gamerules.GameRules;

final class PlayerHeads {
    private static final String PVP_TAG = "amplified_pvp_head";

    private PlayerHeads() {}

    static void drop(ServerPlayer victim, DamageSource source) {
        if (!AmplifiedItems.CONFIG.pvpHeadDrops || !(source.getEntity() instanceof ServerPlayer killer)
                || killer == victim || killer.isCreative() || killer.isSpectator() || victim.isCreative()
                || victim.isSpectator() || !victim.level().getGameRules().get(GameRules.PVP)) return;
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(victim.getGameProfile()));
        var tag = new CompoundTag();
        tag.putBoolean(PVP_TAG, true);
        head.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        victim.drop(head, false, Prediction.SERVER_ONLY);
    }

    static boolean isPvPHead(ItemStack item) {
        return item.is(Items.PLAYER_HEAD)
            && item.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getBooleanOr(PVP_TAG, false);
    }
}
