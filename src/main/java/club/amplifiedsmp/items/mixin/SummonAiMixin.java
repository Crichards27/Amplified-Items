package club.amplifiedsmp.items.mixin;

import club.amplifiedsmp.items.AmplifiedItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class SummonAiMixin {
    @Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
    private void amplifiedItems$controlledMovement(CallbackInfo ci) {
        var mob = (Mob) (Object) this;
        if (mob.level() instanceof ServerLevel && AmplifiedItems.SUMMONS != null && AmplifiedItems.SUMMONS.controlled(mob)) {
            AmplifiedItems.SUMMONS.aiStep(mob);
            ci.cancel();
        }
    }
}
