package club.amplifiedsmp.items.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

public final class AltarDisplayAccess {
    private AltarDisplayAccess() {}

    @Mixin(Display.class)
    public interface Base {
        @Invoker("setBillboardConstraints")
        void amplifiedItems$setBillboard(Display.BillboardConstraints billboard);
    }

    @Mixin(Display.ItemDisplay.class)
    public interface Item {
        @Invoker("setItemStack")
        void amplifiedItems$setItem(ItemStack stack);

        @Invoker("setItemTransform")
        void amplifiedItems$setTransform(ItemDisplayContext context);
    }

    @Mixin(Display.TextDisplay.class)
    public interface Text {
        @Invoker("setText")
        void amplifiedItems$setText(Component text);
    }
}
