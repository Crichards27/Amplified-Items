package club.amplifiedsmp.items.mixin;

import club.amplifiedsmp.items.PlayerAbilities;
import club.amplifiedsmp.items.WeaponItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BowItem.class)
public abstract class BowMixin {
    @Inject(method = "releaseUsing", at = @At("HEAD"), cancellable = true)
    private void amplifiedItems$tapForSlowFall(ItemStack bow, Level level, LivingEntity user, int remaining,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (user instanceof ServerPlayer player && WeaponItems.hasPassive(bow, "windwalker")
                && bow.getUseDuration(player) - remaining < 5) {
            PlayerAbilities.toggleSlowFall(player);
            cir.setReturnValue(true);
        }
    }
}
