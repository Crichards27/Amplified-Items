package club.amplifiedsmp.items;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class CooldownBars {
    private final Map<UUID, ServerBossEvent> bars = new HashMap<>();

    void update(ServerPlayer player) {
        var weapon = WeaponItems.definition(player.getMainHandItem());
        if (weapon == null || !player.isAlive() || player.isSpectator()) {
            remove(player);
            return;
        }
        String ability = AmplifiedItems.coolingAbility(player, weapon);
        long remaining = AbilityControls.castable(ability)
            ? AmplifiedItems.COOLDOWNS.remaining(player.getUUID(), ability, AmplifiedItems.tick) : 0;
        if (remaining == 0) {
            remove(player);
            return;
        }
        double total = 20 * (ability.equals(weapon.primary()) ? weapon.primaryCooldownSeconds() : weapon.secondaryCooldownSeconds());
        var bar = bars.computeIfAbsent(player.getUUID(), id -> new ServerBossEvent(UUID.randomUUID(),
            Component.empty(), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS));
        bar.setName(Component.literal(AbilityControls.label(ability) + " · " + (remaining + 19) / 20 + "s"));
        bar.setProgress((float) Math.min(1, remaining / Math.max(1, total)));
        bar.addPlayer(player);
    }

    void remove(ServerPlayer player) {
        var bar = bars.remove(player.getUUID());
        if (bar != null) bar.removeAllPlayers();
    }
}
