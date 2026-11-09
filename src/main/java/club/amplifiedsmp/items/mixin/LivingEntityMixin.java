package club.amplifiedsmp.items.mixin;

import club.amplifiedsmp.items.Abilities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true)
    private float amplifiedItems$damage(float amount, ServerLevel level, DamageSource source, float originalAmount) {
        return Abilities.modifyDamage((LivingEntity) (Object) this, source, amount);
    }
}
