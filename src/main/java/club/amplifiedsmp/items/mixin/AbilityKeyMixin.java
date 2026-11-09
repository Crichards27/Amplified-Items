package club.amplifiedsmp.items.mixin;

import club.amplifiedsmp.items.AbilityMenu;
import club.amplifiedsmp.items.AmplifiedItems;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class AbilityKeyMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handlePlayerAction", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket;getAction()Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket$Action;"),
        cancellable = true)
    private void amplifiedItems$abilityKey(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (packet.getAction() == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND
                && AmplifiedItems.useAbilityKey(player)) {
            ci.cancel();
        }
    }

    @Inject(method = "handleContainerClick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;suppressRemoteUpdates()V"), cancellable = true)
    private void amplifiedItems$selectAbility(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (player.containerMenu instanceof AbilityMenu menu) {
            // Apply the choice without using the client's predicted item transfers.
            menu.clicked(packet.slotNum(), packet.buttonNum(), packet.containerInput(), player);
            ci.cancel();
        }
    }

}
