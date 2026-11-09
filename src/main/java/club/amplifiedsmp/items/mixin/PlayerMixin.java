package club.amplifiedsmp.items.mixin;

import club.amplifiedsmp.items.MeleeAbilities;
import club.amplifiedsmp.items.PlayerAbilities;
import club.amplifiedsmp.items.WeaponItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerMixin {
    @Inject(method = "attackVisualEffects", at = @At("HEAD"))
    private void amplifiedItems$landedHit(Entity entity, boolean critical, boolean sweep, boolean charged,
                                          boolean stab, float bonusDamage, CallbackInfo ci) {
        if (!stab && (Object) this instanceof ServerPlayer player && entity instanceof LivingEntity target) {
            MeleeAbilities.landedHit(player, target, critical);
        }
    }

    @Inject(method = "getProjectile", at = @At("HEAD"), cancellable = true)
    private void amplifiedItems$windArrows(ItemStack weapon, CallbackInfoReturnable<ItemStack> cir) {
        if ((Object) this instanceof ServerPlayer && weapon.getItem() instanceof BowItem
                && WeaponItems.hasPassive(weapon, "windwalker")) cir.setReturnValue(new ItemStack(Items.ARROW));
    }

    @Inject(method = "makeStuckInBlock", at = @At("HEAD"), cancellable = true)
    private void amplifiedItems$ignoreCobweb(BlockState state, Vec3 multiplier, CallbackInfo ci) {
        if (!state.is(Blocks.COBWEB) || !((Object) this instanceof ServerPlayer player)) return;
        var weapon = WeaponItems.definition(player.getMainHandItem());
        if (weapon == null) weapon = WeaponItems.definition(player.getOffhandItem());
        if (weapon != null && weapon.passive().equals("web_phase")) ci.cancel();
    }

    @Inject(method = "addAdditionalSaveData", at = @At("RETURN"))
    private void amplifiedItems$saveNormalFlight(ValueOutput output, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player) {
            var abilities = PlayerAbilities.abilitiesForSave(player);
            if (abilities != null) output.store("abilities", Abilities.Packed.CODEC, abilities);
        }
    }
}
