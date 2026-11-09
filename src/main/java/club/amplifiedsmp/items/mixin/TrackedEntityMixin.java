package club.amplifiedsmp.items.mixin;

import club.amplifiedsmp.items.PlayerAbilities;
import club.amplifiedsmp.items.TrackedPlayerRefresh;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin implements TrackedPlayerRefresh {
    @Shadow @Final private Entity entity;
    @Shadow public abstract void removePlayer(ServerPlayer viewer);
    @Shadow public abstract void updatePlayers(List<ServerPlayer> viewers);

    @Override
    public void amplifiedItems$refresh(List<ServerPlayer> viewers) {
        updatePlayers(viewers);
    }

    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
    private void amplifiedItems$hideShadowedPlayer(ServerPlayer viewer, CallbackInfo ci) {
        if (entity instanceof ServerPlayer player && PlayerAbilities.isHidden(player)) {
            removePlayer(viewer);
            ci.cancel();
        }
    }
}
