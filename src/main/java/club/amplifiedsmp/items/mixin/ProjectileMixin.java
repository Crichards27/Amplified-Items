package club.amplifiedsmp.items.mixin;

import club.amplifiedsmp.items.Abilities;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Projectile.class)
public abstract class ProjectileMixin {
    @Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
    private void amplifiedItems$hit(HitResult hit, CallbackInfo ci) {
        if (Abilities.onProjectileHit((Projectile) (Object) this, hit)) ci.cancel();
    }
}
