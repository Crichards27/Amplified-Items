package club.amplifiedsmp.items;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.*;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import java.util.*;

public final class Combat {
    private Combat() {}
    public static boolean canAffect(ServerPlayer owner, LivingEntity target) {
        return target.isAlive() && isEnemy(owner, target);
    }
    static boolean isEnemy(ServerPlayer owner, LivingEntity target) {
        if (target == owner || target.isSpectator() || target.isAlliedTo(owner)) return false;
        if (target instanceof Player p && (p.isCreative() || !owner.canHarmPlayer(p)
                || !owner.level().getGameRules().get(GameRules.PVP))) return false;
        if (target instanceof TamableAnimal pet && pet.getOwnerReference() != null && pet.getOwnerReference().matches(owner)) return false;
        if (AmplifiedItems.SUMMONS != null && AmplifiedItems.SUMMONS.ownedBy(target, owner.getUUID())) return false;
        return true;
    }
    public static LivingEntity aimed(ServerPlayer player, double range) {
        Vec3 start = player.getEyePosition(), end = start.add(player.getLookAngle().scale(range));
        HitResult block = player.level().clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        double max = start.distanceToSqr(block.getLocation());
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, start, end,
            player.getBoundingBox().expandTowards(player.getLookAngle().scale(range)).inflate(1),
            e -> e instanceof LivingEntity && e.isPickable() && !e.isSpectator(), max);
        return hit != null && hit.getEntity() instanceof LivingEntity e ? e : null;
    }
    public static boolean visible(net.minecraft.server.level.ServerLevel level, Vec3 from, LivingEntity to, Entity context) {
        return level.clip(new ClipContext(from, to.getBoundingBox().getCenter(), ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, context)).getType() == HitResult.Type.MISS;
    }
}
