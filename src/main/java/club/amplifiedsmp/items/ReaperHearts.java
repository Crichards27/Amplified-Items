package club.amplifiedsmp.items;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.gamerules.GameRules;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class ReaperHearts {
    private static final Identifier HEALTH = Identifier.parse("amplified_items:reaper_health");
    private static final Map<UUID, MobEffectInstance> SPEED = new HashMap<>();
    private static AttachmentType<Integer> earned;

    private ReaperHearts() {}

    static void register() {
        earned = AttachmentRegistry.create(Identifier.parse("amplified_items:reaper_hearts"),
            builder -> builder.persistent(Codec.intRange(0, 5)).copyOnDeath());
    }

    static int count(ServerPlayer player) {
        return ((AttachmentTarget) player).getAttachedOrElse(earned, 0);
    }

    private static boolean carrying(ServerPlayer player) {
        return player.getInventory().getNonEquipmentItems().stream().anyMatch(item -> WeaponItems.hasPassive(item, "reaper_vitality"))
            || WeaponItems.hasPassive(player.getOffhandItem(), "reaper_vitality");
    }

    static void death(ServerPlayer victim, DamageSource source) {
        ((AttachmentTarget) victim).setAttached(earned, Math.max(0, count(victim) - 1));
        if (!(source.getEntity() instanceof ServerPlayer killer) || killer == victim || killer.isCreative()
                || killer.isSpectator() || victim.isCreative() || victim.isSpectator()
                || !victim.level().getGameRules().get(GameRules.PVP) || !carrying(killer)) return;
        int before = count(killer);
        if (before >= 5) return;
        ((AttachmentTarget) killer).setAttached(earned, before + 1);
        tick(killer);
        killer.heal(2);
        Abilities.message(killer, "Reaper hearts " + (before + 1) + "/5");
    }

    static void tick(ServerPlayer player) {
        int hearts = count(player);
        double bonus = hearts > 0 && carrying(player) ? Math.min(hearts * 2, AmplifiedItems.CONFIG.reaperBonusHealth) : 0;
        var attribute = player.getAttribute(Attributes.MAX_HEALTH);
        var modifier = attribute.getModifier(HEALTH);
        if (bonus > 0) {
            if (modifier == null || modifier.amount() != bonus) {
                attribute.addOrUpdateTransientModifier(new AttributeModifier(HEALTH, bonus, AttributeModifier.Operation.ADD_VALUE));
            }
        } else attribute.removeModifier(HEALTH);
        if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
        boolean held = WeaponItems.hasPassive(player.getMainHandItem(), "reaper_vitality")
            || WeaponItems.hasPassive(player.getOffhandItem(), "reaper_vitality");
        if (hearts == 5 && held && player.isAlive() && !player.isSpectator()) {
            var current = player.getEffect(MobEffects.SPEED);
            if (current == null || current == SPEED.get(player.getUUID())) {
                player.addEffect(new MobEffectInstance(MobEffects.SPEED, 15, 0, false, false, true));
                SPEED.put(player.getUUID(), player.getEffect(MobEffects.SPEED));
            }
        } else clearSpeed(player);
    }

    static void clearSpeed(ServerPlayer player) {
        var owned = SPEED.remove(player.getUUID());
        if (owned != null && owned == player.getEffect(MobEffects.SPEED)) player.removeEffect(MobEffects.SPEED);
    }

    static void reset() { SPEED.clear(); }
}
