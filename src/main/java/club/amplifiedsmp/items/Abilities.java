package club.amplifiedsmp.items;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.*;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import java.util.*;

public final class Abilities {
    @FunctionalInterface public interface Ability { boolean use(ServerPlayer player, ItemStack item, LivingEntity clicked); }
    public static final Map<String, Ability> REGISTRY = new HashMap<>();
    private record Mark(UUID owner, long expires) {}
    private enum ShotType { VENOM, GRAPPLE, WEB_GRAPPLE, FLAMING_BURST, SOLAR_FLARE }
    private record Shot(UUID owner, ShotType type, Vec3 origin, double range, long expires) {
        boolean isGrapple() {
            return type == ShotType.GRAPPLE || type == ShotType.WEB_GRAPPLE;
        }
    }
    private static final class TideWave {
        final ServerPlayer owner;
        final ServerLevel level;
        final Vec3 direction;
        final Set<UUID> hit = new HashSet<>();
        Vec3 position;
        double remaining;

        TideWave(ServerPlayer owner) {
            this.owner = owner;
            this.level = owner.level();
            this.direction = owner.getLookAngle();
            this.position = owner.getEyePosition();
            this.remaining = AmplifiedItems.CONFIG.tideRange;
        }

        boolean advance() {
            if (!owner.isAlive() || owner.level() != level || remaining <= 0
                    || level.getServer().getPlayerList().getPlayer(owner.getUUID()) != owner) return false;
            var config = AmplifiedItems.CONFIG;
            double step = Math.min(1.5, remaining);
            Vec3 end = position.add(direction.scale(step));
            var collision = level.clip(new ClipContext(position, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
            Vec3 center = impactCenter(collision);
            double radius = config.tideRadius;
            level.sendParticles(ParticleTypes.SPLASH, center.x, center.y, center.z, 22, radius * .55, .6, radius * .55, .15);
            for (LivingEntity target : splashTargets(owner, owner, center, radius, null)) {
                if (hit.add(target.getUUID())) {
                    var damage = new DamageSource(level.damageSources().drown().typeHolder(), owner);
                    target.hurtServer(level, damage, (float) config.tideDamage);
                }
            }
            position = end;
            remaining -= step;
            return collision.getType() == HitResult.Type.MISS && remaining > 0;
        }
    }
    private static final Map<UUID, Mark> MARKS = new HashMap<>();
    private static final Map<UUID, Shot> SHOTS = new HashMap<>();
    private static final List<TideWave> TIDES = new ArrayList<>();
    private static final Map<UUID, Long> GRAPPLE_FALL = new HashMap<>();
    private static final Map<UUID, Boolean> AUTHORITY = new HashMap<>();

    public static void init() {
        REGISTRY.put("rulers_command", (p, s, target) -> AmplifiedItems.SUMMONS.use(p, s, target));
        REGISTRY.put("execution", Abilities::execution);
        REGISTRY.put("venom_shot", (p, s, t) -> shoot(p, s, ShotType.VENOM));
        REGISTRY.put("grapple_hook", (p, s, t) -> shoot(p, s, ShotType.GRAPPLE));
        REGISTRY.put("soul_stealer", PlayerAbilities::soulStealer);
        REGISTRY.put("shadow_step", PlayerAbilities::shadowStep);
        REGISTRY.put("flaming_burst", (p, s, t) -> shoot(p, s, ShotType.FLAMING_BURST));
        REGISTRY.put("solar_flare", (p, s, t) -> shoot(p, s, ShotType.SOLAR_FLARE));
        REGISTRY.put("tide", (p, s, t) -> TIDES.add(new TideWave(p)));
        REGISTRY.put("riptide", PlayerAbilities::riptide);
        REGISTRY.put("phantom_phase", PlayerAbilities::phantomPhase);
        REGISTRY.put("hollow_moon", PlayerAbilities::hollowMoon);
        REGISTRY.put("heatwave", MeleeAbilities::heatwave);
        REGISTRY.put("sunflare", MeleeAbilities::sunflare);
        REGISTRY.put("windburst", MeleeAbilities::windburst);
        REGISTRY.put("updraft", PlayerAbilities::updraft);
        REGISTRY.put("crit_combo", (p, item, target) -> {
            message(p, "Land " + AmplifiedItems.CONFIG.spiderComboHits + " critical hits on one target.");
            return false;
        });
        REGISTRY.put("web_grapple", (p, item, target) -> shoot(p, item, ShotType.WEB_GRAPPLE));
        REGISTRY.put("blood_rush", PlayerAbilities::bloodRush);
        REGISTRY.put("crimson_cleave", MeleeAbilities::crimsonCleave);
        REGISTRY.put("none", (p,s,t) -> false);
    }
    public static void reset() {
        MARKS.clear(); SHOTS.clear(); TIDES.clear(); GRAPPLE_FALL.clear(); AUTHORITY.clear();
        PlayerAbilities.reset();
        MeleeAbilities.reset();
    }
    public static void message(ServerPlayer p, String text) { p.sendOverlayMessage(Component.literal(text).withStyle(ChatFormatting.GOLD)); }

    private static boolean execution(ServerPlayer p, ItemStack s, LivingEntity clicked) {
        var target = Combat.aimed(p, AmplifiedItems.CONFIG.executionRange);
        if (!(target instanceof ServerPlayer victim) || !Combat.canAffect(p, target)) {
            message(p, "Aim at an enemy player to mark them."); return false;
        }
        long expires = AmplifiedItems.tick + AmplifiedItems.CONFIG.executionSeconds * 20L;
        MARKS.put(victim.getUUID(), new Mark(p.getUUID(), expires));
        message(p, "Execution: " + victim.getName().getString() + " marked for " + AmplifiedItems.CONFIG.executionSeconds + "s");
        victim.sendSystemMessage(Component.literal("You are marked for Execution! Incoming enemy damage is increased for "
            + AmplifiedItems.CONFIG.executionSeconds + " seconds.").withStyle(ChatFormatting.RED));
        return true;
    }

    private static boolean shoot(ServerPlayer player, ItemStack stack, ShotType type) {
        if (type == ShotType.WEB_GRAPPLE) return fire(player, new Snowball(player.level(), player, new ItemStack(Items.COBWEB)), type, 2.4f);
        Arrow arrow = new Arrow(player.level(), player, new ItemStack(Items.ARROW), stack);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        arrow.setBaseDamage(0);
        if (type == ShotType.FLAMING_BURST || type == ShotType.SOLAR_FLARE) arrow.igniteForSeconds(5);
        return fire(player, arrow, type, type == ShotType.GRAPPLE ? 2.4f : 2f);
    }

    private static boolean fire(ServerPlayer player, Projectile projectile, ShotType type, float speed) {
        projectile.shootFromRotation(player, player.getXRot(), player.getYRot(), 0, speed, 0);
        if (!player.level().addFreshEntity(projectile)) return false;
        boolean grapple = type == ShotType.GRAPPLE || type == ShotType.WEB_GRAPPLE;
        SHOTS.put(projectile.getUUID(), new Shot(player.getUUID(), type, projectile.position(),
            grapple ? Math.min(AmplifiedItems.CONFIG.grappleRange, WeaponConfig.MAX_GRAPPLE_RANGE) : 64, AmplifiedItems.tick + 100));
        projectile.addTag("cw_projectile");
        return true;
    }

    /** Called at the vanilla projectile collision, before any vanilla arrow damage. */
    public static boolean onProjectileHit(Projectile projectile, HitResult hit) {
        Shot shot = SHOTS.remove(projectile.getUUID());
        if (shot == null) return false;
        var level = (net.minecraft.server.level.ServerLevel) projectile.level();
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(shot.owner());
        double rangeSquared = shot.range() * shot.range();
        if (owner != null && owner.isAlive() && owner.level() == level
                && hit.getLocation().distanceToSqr(shot.origin()) <= rangeSquared
                && (!shot.isGrapple() || hit.getLocation().distanceToSqr(owner.getEyePosition()) <= rangeSquared)) {
            switch (shot.type()) {
                case GRAPPLE, WEB_GRAPPLE -> grapple(owner, hit);
                case VENOM -> venom(owner, (AbstractArrow) projectile, hit);
                case FLAMING_BURST -> flamingBurst(owner, (AbstractArrow) projectile, hit);
                case SOLAR_FLARE -> solarFlare(owner, (AbstractArrow) projectile, hit);
            }
        }
        projectile.discard();
        return true;
    }

    private static LivingEntity directHit(HitResult hit) {
        return hit instanceof EntityHitResult entityHit && entityHit.getEntity() instanceof LivingEntity living ? living : null;
    }

    private static Vec3 impactCenter(HitResult hit) {
        Vec3 center = hit.getLocation();
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult block) {
            return center.add(Vec3.atLowerCornerOf(block.getDirection().getUnitVec3i()).scale(0.05));
        }
        return center;
    }

    static Set<LivingEntity> splashTargets(ServerPlayer owner, Entity context, Vec3 center, double radius, LivingEntity direct) {
        var level = owner.level();
        var targets = new HashSet<>(level.getEntitiesOfClass(LivingEntity.class, new AABB(center, center).inflate(radius),
            target -> Combat.canAffect(owner, target) && target.getBoundingBox().getCenter().distanceToSqr(center) <= radius * radius
                && Combat.visible(level, center, target, context)));
        if (direct != null && Combat.canAffect(owner, direct)) targets.add(direct);
        return targets;
    }

    private static void venom(ServerPlayer owner, AbstractArrow arrow, HitResult hit) {
        var config = AmplifiedItems.CONFIG;
        var level = owner.level();
        Vec3 center = impactCenter(hit);
        LivingEntity direct = directHit(hit);
        level.sendParticles(ParticleTypes.WITCH, center.x, center.y, center.z, 45, 0.8, 0.4, 0.8, 0.03);
        for (LivingEntity target : splashTargets(owner, arrow, center, config.venomRadius, direct)) {
            float damage = (float) (target == direct ? config.venomDirectDamage : config.venomSplashDamage);
            boolean permitted = damage <= 0 || target.hurtServer(level, level.damageSources().arrow(arrow, owner), damage);
            if (permitted) target.addEffect(new MobEffectInstance(MobEffects.POISON, config.venomPoisonSeconds * 20, 1), owner);
        }
    }

    private static void flamingBurst(ServerPlayer owner, AbstractArrow arrow, HitResult hit) {
        var config = AmplifiedItems.CONFIG;
        var level = owner.level();
        Vec3 center = impactCenter(hit);
        level.sendParticles(ParticleTypes.FLAME, center.x, center.y, center.z, 30, .35, .35, .35, .12);
        LivingEntity target = directHit(hit);
        if (target != null && Combat.canAffect(owner, target)
                && target.hurtServer(level, level.damageSources().arrow(arrow, owner), (float) config.flamingBurstDamage)) {
            target.igniteForSeconds(config.flamingBurstFireSeconds);
        }
    }

    private static void solarFlare(ServerPlayer owner, AbstractArrow arrow, HitResult hit) {
        var config = AmplifiedItems.CONFIG;
        var level = owner.level();
        Vec3 center = impactCenter(hit);
        level.sendParticles(ParticleTypes.EXPLOSION, center.x, center.y, center.z, 6, .5, .5, .5, 0);
        level.playSound(null, center.x, center.y, center.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, .8f, 1.3f);
        var damage = level.damageSources().explosion(arrow, owner);
        for (LivingEntity target : splashTargets(owner, arrow, center, config.solarFlareRadius, directHit(hit))) {
            if (target.hurtServer(level, damage, (float) config.solarFlareDamage)) {
                target.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, config.solarFlareBlindnessSeconds * 20, 0), owner);
            }
        }
    }

    private static void grapple(ServerPlayer owner, HitResult hit) {
        if (hit instanceof EntityHitResult entityHit) {
            if (entityHit.getEntity() instanceof LivingEntity victim && Combat.canAffect(owner, victim)) {
                Vec3 toward = owner.position().subtract(victim.position());
                pull(victim, toward);
                if (victim instanceof ServerPlayer p) GRAPPLE_FALL.put(p.getUUID(), AmplifiedItems.tick + 80);
            }
        } else if (hit.getType() == HitResult.Type.BLOCK) {
            pull(owner, hit.getLocation().subtract(owner.position()));
            GRAPPLE_FALL.put(owner.getUUID(), AmplifiedItems.tick + 80);
        }
    }
    private static void pull(LivingEntity who, Vec3 toward) {
        if (toward.lengthSqr() < 1) return;
        Vec3 motion = toward.normalize().scale(AmplifiedItems.CONFIG.grappleVelocity).add(0, 0.25, 0);
        launch(who, motion);
    }

    static void launch(LivingEntity who, Vec3 motion) {
        who.setDeltaMovement(motion);
        who.fallDistance = 0;
        ((net.minecraft.server.level.ServerLevel) who.level()).getChunkSource().sendToTrackingPlayersAndSelf(who, new ClientboundSetEntityMotionPacket(who));
    }

    public static float modifyDamage(LivingEntity victim, DamageSource source, float amount) {
        float result = MeleeAbilities.bloodlustDamage(source, amount);
        if (source.getEntity() instanceof LivingEntity attacker) {
            var mark = MARKS.get(victim.getUUID());
            ServerPlayer owner = ((net.minecraft.server.level.ServerLevel) victim.level()).getServer().getPlayerList().getPlayer(mark == null ? new UUID(0,0) : mark.owner());
            if (mark != null && mark.expires() > AmplifiedItems.tick && owner != null && Combat.canAffect(owner, victim)
                    && attacker != victim && !attacker.isAlliedTo(victim)) result *= (float) AmplifiedItems.CONFIG.executionMultiplier;
        }
        if (Boolean.TRUE.equals(AUTHORITY.get(victim.getUUID()))) result *= (float) (1 - AmplifiedItems.CONFIG.authorityReduction);
        return result;
    }
    public static void tick(net.minecraft.server.MinecraftServer server) {
        MARKS.entrySet().removeIf(e -> e.getValue().expires() <= AmplifiedItems.tick);
        GRAPPLE_FALL.entrySet().removeIf(e -> e.getValue() <= AmplifiedItems.tick);
        TIDES.removeIf(wave -> !wave.advance());
        MeleeAbilities.tick();
        for (var p : server.getPlayerList().getPlayers()) {
            PlayerAbilities.tick(p);
            if (AmplifiedItems.tick % 20 == 0) {
                WeaponItems.updateControls(p.getMainHandItem());
                WeaponItems.updateControls(p.getOffhandItem());
            }
            if (!PlayerAbilities.isHidden(p) && MARKS.containsKey(p.getUUID()) && AmplifiedItems.tick % 20 == 0) p.level().sendParticles(ParticleTypes.ANGRY_VILLAGER,
                p.getX(), p.getY() + p.getBbHeight() + 0.3, p.getZ(), 3, 0.25, 0.1, 0.25, 0);
            if (GRAPPLE_FALL.containsKey(p.getUUID())) p.fallDistance = 0;
            if (AmplifiedItems.tick % 10 == 0) {
                WeaponDefinition w = WeaponItems.definition(p.getMainHandItem());
                if (w == null) w = WeaponItems.definition(p.getOffhandItem());
                boolean active = w != null && w.passive().equals("rulers_authority") && p.isAlive() && !p.isSpectator()
                    && p.level().getPlayers(other -> other != p && !other.isSpectator() && other.isAlive()
                        && other.distanceToSqr(p) <= AmplifiedItems.CONFIG.authorityRadius * AmplifiedItems.CONFIG.authorityRadius).size()
                       >= AmplifiedItems.CONFIG.authorityOtherPlayers;
                AUTHORITY.put(p.getUUID(), active);
                if (active && !PlayerAbilities.isHidden(p) && AmplifiedItems.tick % 40 == 0) p.level().sendParticles(ParticleTypes.ENCHANT,
                    p.getX(), p.getY() + 1, p.getZ(), 8, 0.5, 0.4, 0.5, 0.1);
            }
        }
        for (var it = SHOTS.entrySet().iterator(); it.hasNext();) {
            var e = it.next();
            Entity projectile = find(server, e.getKey());
            if (projectile == null || e.getValue().expires() <= AmplifiedItems.tick
                    || projectile.position().distanceToSqr(e.getValue().origin()) > e.getValue().range() * e.getValue().range()) {
                if (projectile != null) projectile.discard();
                it.remove();
            } else if (e.getValue().type() == ShotType.FLAMING_BURST && AmplifiedItems.tick % 2 == 0) {
                ((ServerLevel) projectile.level()).sendParticles(ParticleTypes.FLAME, projectile.getX(), projectile.getY(), projectile.getZ(),
                    12, .25, .25, .25, .08);
            } else if (e.getValue().type() == ShotType.WEB_GRAPPLE) {
                ((ServerLevel) projectile.level()).sendParticles(ParticleTypes.WHITE_SMOKE, projectile.getX(), projectile.getY(), projectile.getZ(),
                    4, .12, .12, .12, .01);
            }
        }
    }
    static Entity find(net.minecraft.server.MinecraftServer server, UUID id) {
        for (var level : server.getAllLevels()) { Entity entity = level.getEntity(id); if (entity != null) return entity; }
        return null;
    }
    public static void clearPlayer(ServerPlayer player) {
        UUID id = player.getUUID();
        MARKS.remove(id); AUTHORITY.remove(id); GRAPPLE_FALL.remove(id);
        PlayerAbilities.clear(player);
        MeleeAbilities.clear(id);
    }
}
