package club.amplifiedsmp.items;

import club.amplifiedsmp.items.mixin.ChunkMapAccess;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PlayerAbilities {
    private static final Identifier REAPER_HEALTH = Identifier.parse("amplified_items:reaper_health");
    private static final Map<UUID, Long> SHADOWS = new HashMap<>();
    private static final Map<UUID, RiptideWindow> RIPTIDES = new HashMap<>();
    private static final Map<UUID, Flight> PHANTOMS = new HashMap<>();
    private static final Map<UUID, MobEffectInstance> NIGHT_VISION = new HashMap<>();
    private static final Set<UUID> SLOW_FALL = new HashSet<>();
    private record RiptideWindow(UUID weapon, long expires, long nextDash) {}
    private record Flight(long expires, boolean mayFly, boolean flying, GameType mode) {}

    private PlayerAbilities() {}

    static boolean soulStealer(ServerPlayer player, ItemStack item, LivingEntity clicked) {
        var config = AmplifiedItems.CONFIG;
        LivingEntity target = Combat.aimed(player, config.soulStealerRange);
        if (target == null || !Combat.canAffect(player, target)) {
            Abilities.message(player, "Aim at a nearby enemy.");
            return false;
        }
        float health = target.getHealth();
        if (!target.hurtServer(player.level(), player.level().damageSources().playerAttack(player), (float) config.soulStealerDamage)) {
            return false;
        }
        player.heal(Math.min((float) config.soulStealerDamage, Math.max(0, health - target.getHealth())));
        return true;
    }

    static boolean shadowStep(ServerPlayer player, ItemStack item, LivingEntity clicked) {
        hide(player, AmplifiedItems.CONFIG.shadowStepSeconds);
        Abilities.message(player, "Shadow Step active");
        return true;
    }

    private static void hide(ServerPlayer player, int seconds) {
        boolean alreadyHidden = isHidden(player);
        SHADOWS.merge(player.getUUID(), AmplifiedItems.tick + seconds * 20L, Math::max);
        if (!alreadyHidden) refreshTracking(player);
        buff(player, MobEffects.INVISIBILITY, seconds, 0);
    }

    static boolean phantomPhase(ServerPlayer player, ItemStack item, LivingEntity clicked) {
        if (player.isPassenger() || isPhantom(player)) return false;
        var abilities = player.getAbilities();
        int seconds = AmplifiedItems.CONFIG.phantomPhaseSeconds;
        PHANTOMS.put(player.getUUID(), new Flight(AmplifiedItems.tick + seconds * 20L,
            abilities.mayfly, abilities.flying, player.gameMode.getGameModeForPlayer()));
        abilities.mayfly = true;
        abilities.flying = true;
        player.fallDistance = 0;
        player.onUpdateAbilities();
        hide(player, seconds);
        return true;
    }

    public static boolean isPhantom(ServerPlayer player) {
        Flight flight = PHANTOMS.get(player.getUUID());
        return flight != null && flight.expires() > AmplifiedItems.tick;
    }

    public static net.minecraft.world.entity.player.Abilities.Packed abilitiesForSave(ServerPlayer player) {
        Flight flight = PHANTOMS.get(player.getUUID());
        if (flight == null || flight.mode() != player.gameMode.getGameModeForPlayer()) return null;
        var current = player.getAbilities().pack();
        return new net.minecraft.world.entity.player.Abilities.Packed(current.invulnerable(), flight.flying(), flight.mayFly(),
            current.instabuild(), current.mayBuild(), current.flyingSpeed(), current.walkingSpeed());
    }

    private static void endFlight(ServerPlayer player) {
        Flight flight = PHANTOMS.remove(player.getUUID());
        if (flight == null || flight.mode() != player.gameMode.getGameModeForPlayer()) return;
        var abilities = player.getAbilities();
        abilities.mayfly = flight.mayFly();
        abilities.flying = flight.flying();
        player.fallDistance = 0;
        player.onUpdateAbilities();
    }

    static boolean hollowMoon(ServerPlayer player, ItemStack item, LivingEntity clicked) {
        int seconds = AmplifiedItems.CONFIG.hollowMoonSeconds;
        buff(player, MobEffects.SPEED, seconds, 2);
        buff(player, MobEffects.STRENGTH, seconds, 1);
        return true;
    }

    static boolean updraft(ServerPlayer player, ItemStack item, LivingEntity clicked) {
        int seconds = AmplifiedItems.CONFIG.updraftSeconds;
        buff(player, MobEffects.SPEED, seconds, 1);
        buff(player, MobEffects.JUMP_BOOST, seconds, 1);
        return true;
    }

    static boolean bloodRush(ServerPlayer player, ItemStack item, LivingEntity clicked) {
        int seconds = AmplifiedItems.CONFIG.bloodRushSeconds;
        buff(player, MobEffects.SPEED, seconds, 1);
        buff(player, MobEffects.STRENGTH, seconds, 0);
        return true;
    }

    private static void buff(ServerPlayer player, Holder<MobEffect> effect, int seconds, int amplifier) {
        player.addEffect(new MobEffectInstance(effect, seconds * 20, amplifier, false, false, true));
    }

    public static void toggleSlowFall(ServerPlayer player) {
        boolean enabled = SLOW_FALL.add(player.getUUID());
        if (!enabled) SLOW_FALL.remove(player.getUUID());
        if (enabled) refreshEffect(player, MobEffects.SLOW_FALLING, 0);
        Abilities.message(player, enabled ? "Slow Falling on" : "Slow Falling off");
    }

    public static boolean isHidden(ServerPlayer player) {
        return SHADOWS.getOrDefault(player.getUUID(), 0L) > AmplifiedItems.tick;
    }

    private static void refreshTracking(ServerPlayer player) {
        var chunkMap = (ChunkMapAccess) player.level().getChunkSource().chunkMap;
        Object entry = chunkMap.amplifiedItems$entities().get(player.getId());
        if (entry instanceof TrackedPlayerRefresh tracker) tracker.amplifiedItems$refresh(player.level().players());
    }

    static boolean riptide(ServerPlayer player, ItemStack item, LivingEntity clicked) {
        if (player.isPassenger()) return false;
        long expires = AmplifiedItems.tick + AmplifiedItems.CONFIG.riptideSeconds * 20L;
        dash(player, item, expires);
        Abilities.message(player, "Riptide active. Use this ability again to dash.");
        return true;
    }

    static boolean repeatRiptide(ServerPlayer player, ItemStack item) {
        var window = RIPTIDES.get(player.getUUID());
        if (window == null || window.expires() <= AmplifiedItems.tick
                || !java.util.Objects.equals(window.weapon(), WeaponItems.instance(item))) return false;
        if (!player.isPassenger() && AmplifiedItems.tick >= window.nextDash()) dash(player, item, window.expires());
        else Abilities.message(player, "Next Riptide dash in " + Math.max(1, (window.nextDash() - AmplifiedItems.tick + 19) / 20) + "s");
        return true;
    }

    private static void dash(ServerPlayer player, ItemStack item, long expires) {
        RIPTIDES.put(player.getUUID(), new RiptideWindow(WeaponItems.instance(item), expires, AmplifiedItems.tick + 20));
        Vec3 velocity = player.getLookAngle().scale(AmplifiedItems.CONFIG.riptideVelocity);
        if (player.onGround() && velocity.y < 0.6) velocity = new Vec3(velocity.x, 0.6, velocity.z);
        player.startAutoSpinAttack(20, 0, item);
        Abilities.launch(player, velocity);
        player.level().playSound(null, player, SoundEvents.TRIDENT_RIPTIDE_1, SoundSource.PLAYERS, 1, 1);
    }

    static void tick(ServerPlayer player) {
        UUID id = player.getUUID();
        Flight flight = PHANTOMS.get(id);
        if (flight != null) {
            if (flight.expires() <= AmplifiedItems.tick || flight.mode() != player.gameMode.getGameModeForPlayer()) endFlight(player);
            else player.fallDistance = 0;
        }
        Long end = SHADOWS.get(id);
        if (end != null && end <= AmplifiedItems.tick) {
            SHADOWS.remove(id);
            refreshTracking(player);
        }
        var window = RIPTIDES.get(id);
        if (window != null && window.expires() <= AmplifiedItems.tick) RIPTIDES.remove(id);
        WeaponDefinition held = WeaponItems.definition(player.getMainHandItem());
        if (held == null) held = WeaponItems.definition(player.getOffhandItem());
        String passive = held != null && player.isAlive() && !player.isSpectator() ? held.passive() : "none";
        WebWalk.tick(player, passive.equals("web_phase"));
        ReaperHearts.tick(player);
        if (AmplifiedItems.tick % 10 != 0) return;
        cycleEffects(player, passive);
        if (passive.equals("windwalker") && SLOW_FALL.contains(id)) refreshEffect(player, MobEffects.SLOW_FALLING, 0);
        if (passive.equals("ocean_blessing") && player.isInWater()) {
            player.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, 15, 0, false, false, true));
            player.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, 15, 0, false, false, true));
        }
    }

    private static void cycleEffects(ServerPlayer player, String passive) {
        boolean nightBlade = passive.equals("night_cycle");
        boolean solarBlade = passive.equals("solar_cycle");
        if (nightBlade) refreshNightVision(player);
        else removeNightVision(player);
        if (!nightBlade && !solarBlade) return;

        long time = Math.floorMod(player.level().getOverworldClockTime(), 24000);
        boolean night = time >= 13000 && time < 23000;
        if (nightBlade == night) {
            refreshEffect(player, MobEffects.STRENGTH, 0);
            refreshEffect(player, MobEffects.SPEED, nightBlade ? 1 : 0);
        } else {
            refreshEffect(player, MobEffects.WEAKNESS, 0);
            refreshEffect(player, MobEffects.SLOWNESS, 0);
            if (solarBlade) refreshEffect(player, MobEffects.RESISTANCE, 0);
        }
    }

    private static void refreshEffect(ServerPlayer player, Holder<MobEffect> effect, int amplifier) {
        player.addEffect(new MobEffectInstance(effect, 15, amplifier, false, false, true));
    }

    private static void refreshNightVision(ServerPlayer player) {
        MobEffectInstance current = player.getEffect(MobEffects.NIGHT_VISION);
        MobEffectInstance owned = NIGHT_VISION.get(player.getUUID());
        if (current == null || (current == owned && current.getDuration() >= 0 && current.getDuration() < 240)) {
            player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 260, 0, false, false, true));
            NIGHT_VISION.put(player.getUUID(), player.getEffect(MobEffects.NIGHT_VISION));
        }
    }

    private static void removeNightVision(ServerPlayer player) {
        MobEffectInstance owned = NIGHT_VISION.remove(player.getUUID());
        if (owned != null && owned == player.getEffect(MobEffects.NIGHT_VISION)
                && owned.getDuration() >= 0 && owned.getDuration() <= 260) player.removeEffect(MobEffects.NIGHT_VISION);
    }

    private static void removeHealthBonus(ServerPlayer player) {
        if (player.getAttribute(Attributes.MAX_HEALTH).removeModifier(REAPER_HEALTH)
                && player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
    }

    static void clear(ServerPlayer player) {
        endFlight(player);
        ReaperHearts.clearSpeed(player);
        if (SHADOWS.remove(player.getUUID()) != null) refreshTracking(player);
        RIPTIDES.remove(player.getUUID());
        SLOW_FALL.remove(player.getUUID());
        removeNightVision(player);
        WebWalk.clear(player);
        removeHealthBonus(player);
    }

    static void reset() {
        SHADOWS.clear();
        RIPTIDES.clear();
        PHANTOMS.clear();
        NIGHT_VISION.clear();
        SLOW_FALL.clear();
        WebWalk.reset();
        ReaperHearts.reset();
    }
}
