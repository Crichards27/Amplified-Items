package club.amplifiedsmp.items.mixin;

import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class AltarEntityMixin {
    @Inject(method = "shouldBeSaved", at = @At("HEAD"), cancellable = true)
    private void amplifiedItems$transientDisplay(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof Display display && display.entityTags().contains("amplified_altar_display")) {
            cir.setReturnValue(false);
        }
    }
}
