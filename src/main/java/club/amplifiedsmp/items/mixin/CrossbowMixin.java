package club.amplifiedsmp.items.mixin;

import club.amplifiedsmp.items.WeaponItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CrossbowItem.class)
public abstract class CrossbowMixin {
    @Inject(method = "createProjectile", at = @At("RETURN"))
    private void amplifiedItems$flameArrow(Level level, LivingEntity shooter, ItemStack crossbow,
                                          ItemStack ammunition, boolean critical, CallbackInfoReturnable<Projectile> cir) {
        if (level instanceof ServerLevel && cir.getReturnValue() instanceof AbstractArrow arrow
                && WeaponItems.hasPassive(crossbow, "flame_crossbow")) arrow.igniteForSeconds(100);
    }
}
