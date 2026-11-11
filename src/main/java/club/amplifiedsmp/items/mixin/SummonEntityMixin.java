package club.amplifiedsmp.items.mixin;

import club.amplifiedsmp.items.AmplifiedItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class SummonEntityMixin {
    private static boolean controlled(Entity entity) {
        return entity.level() instanceof ServerLevel && AmplifiedItems.SUMMONS != null
            && AmplifiedItems.SUMMONS.controlled(entity);
    }

    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z", at = @At("HEAD"), cancellable = true)
    private void amplifiedItems$noMounting(Entity vehicle, boolean force, boolean sendEvent, CallbackInfoReturnable<Boolean> cir) {
        if (controlled((Entity) (Object) this) || controlled(vehicle)) cir.setReturnValue(false);
    }

    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void amplifiedItems$noShoving(Entity other, CallbackInfo ci) {
        if (controlled((Entity) (Object) this) || controlled(other)) ci.cancel();
    }
}
