package club.amplifiedsmp.items.mixin;

import club.amplifiedsmp.items.AmplifiedItems;
import club.amplifiedsmp.items.PackHost;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ResourcePackMixin {
    @Inject(method = "handleResourcePackResponse", at = @At("TAIL"))
    private void amplifiedItems$requirePack(ServerboundResourcePackPacket packet, CallbackInfo ci) {
        if (!packet.id().equals(PackHost.ID) || !AmplifiedItems.CONFIG.resourcePackRequired) return;
        switch (packet.action()) {
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED ->
                ((ServerCommonPacketListenerImpl) (Object) this).disconnect(Component.literal("This server requires the Amplified Items resource pack. Enable Server Resource Packs and reconnect."));
            default -> { }
        }
    }
}
