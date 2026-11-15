package club.amplifiedsmp.items;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.AgeableWaterCreature;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.animal.fish.WaterAnimal;
import net.minecraft.world.entity.animal.nautilus.AbstractNautilus;

/** Legacy recovery currently searches dry ground only; aquatic snapshots need admin recovery. */
final class LegacyRecoveryPolicy {
    private LegacyRecoveryPolicy() {}

    static boolean allowsAutomaticDryLandReturn(Class<? extends Mob> type) {
        // In 26.3 these aquatic families don't all inherit from WaterAnimal.
        // Dry-ground placement isn't safe for any of them.
        return !WaterAnimal.class.isAssignableFrom(type)
            && !AgeableWaterCreature.class.isAssignableFrom(type)
            && !Axolotl.class.isAssignableFrom(type)
            && !AbstractNautilus.class.isAssignableFrom(type);
    }
}
