package club.amplifiedsmp.items;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class MeleeAbilities {
    private record Combo(UUID victim, UUID weapon, int hits, long expires) {}
    private record Rage(int hits, long expires, boolean boosted) {}
    private record Scorch(ServerPlayer owner, LivingEntity target, long expires, long nextPulse) {}
    private static final Map<UUID, Combo> COMBOS = new HashMap<>();
    private static final Map<UUID, Rage> RAGE = new HashMap<>();
    private static final Map<UUID, Scorch> SCORCHES = new HashMap<>();

    private MeleeAbilities() {}

    static boolean heatwave(ServerPlayer player, ItemStack item, LivingEntity clicked) {
        fireSweep(player, null);
        return true;
    }

    private static void fireSweep(ServerPlayer player, LivingEntity direct) {
        var config = AmplifiedItems.CONFIG;
        Vec3 center = player.getEyePosition().add(player.getLookAngle().scale(1.3));
        player.level().sendParticles(ParticleTypes.FLAME, center.x, center.y, center.z, 18, .6, .25, .6, .12);
        if (direct != null && direct.isAlive()) scorch(player, direct);
        for (LivingEntity target : Abilities.splashTargets(player, player, player.getEyePosition(), config.heatwaveRange, null)) {
            if (target != direct && inFront(player, target)
                    && target.hurtServer(player.level(), heatDamage(player), (float) config.heatwaveDamage)) scorch(player, target);
        }
    }

    private static DamageSource heatDamage(ServerPlayer player) {
        // Keep the caster attached to the hit. Magic damage is unaffected by Fire Resistance.
        return new DamageSource(player.level().damageSources().magic().typeHolder(), player);
    }

    private static void scorch(ServerPlayer player, LivingEntity target) {
        int seconds = AmplifiedItems.CONFIG.heatwaveFireSeconds;
        target.igniteForSeconds(seconds);
        UUID id = target.getUUID();
        Scorch old = SCORCHES.get(id);
        long next = old == null ? AmplifiedItems.tick + 20 : old.nextPulse();
        SCORCHES.put(id, new Scorch(player, target, AmplifiedItems.tick + seconds * 20L, next));
    }

    static boolean sunflare(ServerPlayer player, ItemStack item, LivingEntity clicked) {
        var config = AmplifiedItems.CONFIG;
        Vec3 center = player.getBoundingBox().getCenter();
        for (int height = 0; height < 9; height++) {
            player.level().sendParticles(ParticleTypes.FLAME, center.x, center.y + height, center.z, 6, .15, .4, .15, .03);
        }
        for (LivingEntity target : Abilities.splashTargets(player, player, center, config.sunflareRadius, null)) {
            if (target.hurtServer(player.level(), player.level().damageSources().playerAttack(player), (float) config.sunflareDamage)) {
                target.igniteForSeconds(config.sunflareFireSeconds);
                knockAway(player, target, 1.2);
            }
        }
        return true;
    }

    static boolean windburst(ServerPlayer player, ItemStack item, LivingEntity clicked) {
        var config = AmplifiedItems.CONFIG;
        Vec3 center = player.getBoundingBox().getCenter();
        player.level().sendParticles(ParticleTypes.CLOUD, center.x, center.y, center.z, 30, 1.5, .4, 1.5, .12);
        for (LivingEntity target : Abilities.splashTargets(player, player, center, config.windburstRadius, null)) {
            knockAway(player, target, config.windburstKnockback);
        }
        return true;
    }

    private static void knockAway(ServerPlayer player, LivingEntity target, double strength) {
        Vec3 delta = target.position().subtract(player.position());
        Vec3 direction = new Vec3(delta.x, 0, delta.z).normalize();
        Abilities.launch(target, direction.scale(strength).add(0, .35, 0));
    }

    static boolean crimsonCleave(ServerPlayer player, ItemStack item, LivingEntity clicked) {
        var config = AmplifiedItems.CONFIG;
        Vec3 center = player.getEyePosition().add(player.getLookAngle().scale(1.4));
        player.level().sendParticles(ParticleTypes.SWEEP_ATTACK, center.x, center.y, center.z, 3, .5, .2, .5, 0);
        boolean landed = false;
        for (LivingEntity target : Abilities.splashTargets(player, player, player.getEyePosition(), config.crimsonCleaveRange, null)) {
            if (inFront(player, target)
                    && target.hurtServer(player.level(), player.level().damageSources().playerAttack(player), (float) config.crimsonCleaveDamage)) landed = true;
        }
        return true;
    }

    private static boolean inFront(ServerPlayer player, LivingEntity target) {
        Vec3 direction = target.getBoundingBox().getCenter().subtract(player.getEyePosition()).normalize();
        return player.getLookAngle().dot(direction) >= .42;
    }

    public static void landedHit(ServerPlayer player, LivingEntity target, boolean critical, boolean charged) {
        if (!Combat.isEnemy(player, target)) return;
        ItemStack item = player.getWeaponItem();
        WeaponDefinition weapon = WeaponItems.definition(item);
        if (weapon == null || !weapon.primary().equals("crit_combo")) COMBOS.remove(player.getUUID());
        if (weapon == null) return;
        if (weapon.primary().equals("crit_combo")) countCritical(player, target, item, weapon, critical);
        if (weapon.passive().equals("solar_cycle")) fireSweep(player, target);
        if (weapon.passive().equals("bloodlust") && (charged || critical)) gainRage(player);
    }

    private static void countCritical(ServerPlayer player, LivingEntity target, ItemStack item, WeaponDefinition weapon, boolean critical) {
        UUID id = player.getUUID();
        long now = AmplifiedItems.tick;
        if (!critical || !target.isAlive() || AmplifiedItems.COOLDOWNS.remaining(id, "crit_combo", now) > 0) {
            COMBOS.remove(id);
            return;
        }
        UUID instance = WeaponItems.instance(item);
        Combo old = COMBOS.get(id);
        int hits = old != null && old.expires() > now && old.victim().equals(target.getUUID())
            && java.util.Objects.equals(old.weapon(), instance) ? old.hits() + 1 : 1;
        var config = AmplifiedItems.CONFIG;
        if (hits >= config.spiderComboHits) {
            target.addEffect(new MobEffectInstance(MobEffects.POISON, config.spiderPoisonSeconds * 20, 1), player);
            AmplifiedItems.COOLDOWNS.start(id, "crit_combo", now, weapon.primaryCooldownSeconds());
            COMBOS.remove(id);
            Abilities.message(player, "Poison combo landed");
        } else {
            COMBOS.put(id, new Combo(target.getUUID(), instance, hits, now + config.spiderComboGapSeconds * 20L));
            Abilities.message(player, "Crits " + hits + "/" + config.spiderComboHits);
        }
    }

    private static void gainRage(ServerPlayer player) {
        UUID id = player.getUUID();
        long now = AmplifiedItems.tick;
        Rage old = RAGE.get(id);
        if (old != null && old.expires() > now && old.boosted()) return;
        var config = AmplifiedItems.CONFIG;
        int hits = old != null && old.expires() > now ? old.hits() + 1 : 1;
        boolean boosted = hits >= config.bloodlustHits;
        int seconds = boosted ? config.bloodlustSeconds : config.bloodlustBuildSeconds;
        RAGE.put(id, new Rage(hits, now + seconds * 20L, boosted));
        Abilities.message(player, boosted ? "Bloodlust active" : "Rage " + hits + "/" + config.bloodlustHits);
    }

    static float bloodlustDamage(DamageSource source, float amount) {
        if (!(source.getEntity() instanceof ServerPlayer player) || !source.is(DamageTypes.PLAYER_ATTACK)) return amount;
        Rage rage = RAGE.get(player.getUUID());
        if (rage != null && rage.boosted() && rage.expires() > AmplifiedItems.tick
                && WeaponItems.hasPassive(player.getWeaponItem(), "bloodlust")) {
            return amount * (1 + (float) AmplifiedItems.CONFIG.bloodlustBonus);
        }
        return amount;
    }

    static void tick() {
        long now = AmplifiedItems.tick;
        COMBOS.values().removeIf(combo -> combo.expires() <= now);
        RAGE.values().removeIf(rage -> rage.expires() <= now);
        var burns = SCORCHES.entrySet().iterator();
        while (burns.hasNext()) {
            var entry = burns.next();
            Scorch scorch = entry.getValue();
            LivingEntity target = scorch.target();
            ServerPlayer player = scorch.owner();
            if (now > scorch.expires() || !player.isAlive() || target.level() != player.level()
                    || player.level().getServer().getPlayerList().getPlayer(player.getUUID()) != player || !Combat.canAffect(player, target)) {
                burns.remove();
            } else if (now >= scorch.nextPulse()) {
                if (target.fireImmune() || target.hasEffect(MobEffects.FIRE_RESISTANCE)) target.hurtServer(player.level(), heatDamage(player), 1);
                entry.setValue(new Scorch(player, target, scorch.expires(), now + 20));
            }
        }
    }

    static void clear(UUID player) {
        COMBOS.remove(player);
        RAGE.remove(player);
    }

    static void reset() {
        COMBOS.clear();
        RAGE.clear();
        SCORCHES.clear();
    }
}
