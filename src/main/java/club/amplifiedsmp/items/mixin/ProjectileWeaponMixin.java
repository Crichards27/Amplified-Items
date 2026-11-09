package club.amplifiedsmp.items.mixin;

import club.amplifiedsmp.items.WeaponItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ProjectileWeaponItem.class)
public abstract class ProjectileWeaponMixin {
    @Inject(method = "createProjectile", at = @At("RETURN"))
    private void amplifiedItems$disableWindArrowPickup(Level level, LivingEntity shooter, ItemStack weapon,
                                                      ItemStack ammo, boolean critical, CallbackInfoReturnable<Projectile> cir) {
        if (shooter instanceof ServerPlayer && weapon.getItem() instanceof BowItem
                && WeaponItems.hasPassive(weapon, "windwalker") && cir.getReturnValue() instanceof AbstractArrow arrow) {
            arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        }
    }
}
