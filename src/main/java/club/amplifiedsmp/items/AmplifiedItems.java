package club.amplifiedsmp.items;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.damagesource.DamageTypes;
import org.slf4j.*;
import java.nio.file.Path;
import java.util.*;

public final class AmplifiedItems implements ModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("amplified-items");
    public static WeaponConfig CONFIG;
    public static Summons SUMMONS;
    private static Altars altars;
    private static final CooldownBars BARS = new CooldownBars();
    public static long tick;
    public static final Timers COOLDOWNS = new Timers();
    private static final Map<UUID, Long> LAST_USE = new HashMap<>();
    private static final PackHost PACK = new PackHost();
    private static Path configPath() { return FabricLoader.getInstance().getConfigDir().resolve("amplified-items/config.json"); }

    @Override public void onInitialize() {
        Abilities.init();
        ReaperHearts.register();
        UseBlockCallback.EVENT.register((player, level, hand, hit) ->
            player instanceof ServerPlayer serverPlayer && altars != null
                ? altars.use(serverPlayer, hand, hit.getBlockPos()) : InteractionResult.PASS);
        try { CONFIG = WeaponConfig.load(configPath()); WeaponItems.validate(CONFIG); }
        catch (Exception e) { throw new IllegalStateException("Fix config/amplified-items/config.json before starting", e); }
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            try { altars = new Altars(server); }
            catch (Exception e) { throw new IllegalStateException("Cannot open altar ledger", e); }
            tick = 0; COOLDOWNS.clear(); LAST_USE.clear(); Abilities.reset();
            try { SUMMONS = new Summons(server); }
            catch (Exception e) { throw new IllegalStateException("Cannot open summon ledger; restore its backup", e); }
            try { PACK.start(configPath().getParent()); }
            catch (Exception e) { LOG.error("Resource pack could not be hosted. Use external hosting or fix the configured port.", e); }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                Abilities.clearPlayer(player);
                BARS.remove(player);
            }
            if (altars != null) altars.stop();
            if (SUMMONS != null) SUMMONS.stop();
            PACK.close();
        });
        ServerEntityEvents.ENTITY_LOAD.register((e,l) -> {
            if (altars != null && e.entityTags().contains("amplified_ritual_defender")) {
                altars.retireLegacyDefender(e);
                return;
            }
            if (SUMMONS != null && !e.isRemoved()) SUMMONS.loaded(e);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((e,s) -> {
            if (SUMMONS != null) { SUMMONS.died(e); if (e instanceof ServerPlayer) SUMMONS.recallOwner(e.getUUID()); }
            if (e instanceof ServerPlayer player) {
                ReaperHearts.death(player, s);
                Abilities.clearPlayer(player);
                BARS.remove(player);
                PlayerHeads.drop(player, s);
            }
        });
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
            !(level instanceof net.minecraft.server.level.ServerLevel serverLevel) || altars == null
                || !altars.protectedBlock(serverLevel, pos));
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((victim,source,amount) -> {
            if (victim instanceof ServerPlayer player && PlayerAbilities.isPhantom(player) && !source.is(DamageTypes.GENERIC_KILL)) return false;
            if (SUMMONS != null && source.getEntity() != null && SUMMONS.controlled(source.getEntity())) return false;
            return true; // Controlled mobs only damage through the owner's explicit melee controller.
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tick++;
            Abilities.tick(server);
            if (tick % 20 == 0) altars.tick();
            if (tick % 5 == 0) server.getPlayerList().getPlayers().forEach(BARS::update);
            if (SUMMONS != null) SUMMONS.tick();
            if (tick % 1200 == 0) { COOLDOWNS.prune(tick); LAST_USE.values().removeIf(t -> t + 100 < tick); }
        });
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server) -> PACK.send(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server) -> {
            if (SUMMONS != null) SUMMONS.recallOwner(handler.player.getUUID());
            BARS.remove(handler.player);
            Abilities.clearPlayer(handler.player); LAST_USE.remove(handler.player.getUUID());
        });
        commands();
        LOG.info("Loaded {} server-side weapon definitions for Minecraft 26.3", CONFIG.weapons.size());
    }

    static String coolingAbility(ServerPlayer player, WeaponDefinition weapon) {
        long primary = AbilityControls.castable(weapon.primary())
            ? COOLDOWNS.remaining(player.getUUID(), weapon.primary(), tick) : 0;
        long secondary = AbilityControls.castable(weapon.secondary())
            ? COOLDOWNS.remaining(player.getUUID(), weapon.secondary(), tick) : 0;
        return primary >= secondary ? weapon.primary() : weapon.secondary();
    }

    public static int castAbility(ServerPlayer player, int number) {
        if (!player.isAlive() || player.isSpectator() || player.containerMenu != player.inventoryMenu) return 0;
        ItemStack item = player.getMainHandItem();
        WeaponDefinition weapon = WeaponItems.definition(item);
        if (weapon == null) { Abilities.message(player, "Hold an Amplified weapon."); return 0; }
        String ability = number == 1 ? weapon.primary() : weapon.secondary();
        if (!AbilityControls.castable(ability)) {
            Abilities.message(player, ability.equals("crit_combo") ? "Critical Combo triggers on critical hits." : "This item has no active ability.");
            return 0;
        }
        player.stopUsingItem();
        if (LAST_USE.getOrDefault(player.getUUID(), -100L) + 3 > tick) return 0;
        LAST_USE.put(player.getUUID(), tick);
        if (ability.equals("riptide") && PlayerAbilities.repeatRiptide(player, item)) return 1;
        String cooling = coolingAbility(player, weapon);
        long remaining = AbilityControls.castable(cooling) ? COOLDOWNS.remaining(player.getUUID(), cooling, tick) : 0;
        if (remaining > 0) {
            Abilities.message(player, "Weapon ready in " + (remaining + 19) / 20 + "s");
            return 0;
        }
        if (!Abilities.REGISTRY.get(ability).use(player, item, null)) return 0;
        double seconds = number == 1 ? weapon.primaryCooldownSeconds() : weapon.secondaryCooldownSeconds();
        COOLDOWNS.start(player.getUUID(), ability, tick, seconds);
        BARS.update(player);
        player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
        return 1;
    }

    private static void commands() {
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment) -> {
            dispatcher.register(Commands.literal("ability").executes(ctx -> {
                var player = ctx.getSource().getPlayerOrException();
                var weapon = WeaponItems.definition(player.getMainHandItem());
                if (weapon == null) { Abilities.message(player, "Hold an Amplified weapon."); return 0; }
                for (int number = 1; number <= 2; number++) {
                    String ability = number == 1 ? weapon.primary() : weapon.secondary();
                    double cooldown = number == 1 ? weapon.primaryCooldownSeconds() : weapon.secondaryCooldownSeconds();
                    player.sendSystemMessage(Component.literal("/ability " + number + " — " + AbilityControls.label(ability)
                        + " (" + (long) Math.ceil(cooldown) + "s cooldown)"));
                    for (String line : AbilityControls.description(ability)) player.sendSystemMessage(Component.literal(line));
                }
                return 1;
            }).then(Commands.argument("number", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 2))
                .executes(ctx -> castAbility(ctx.getSource().getPlayerOrException(),
                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "number")))));
            dispatcher.register(Commands.literal("amplifieditems")
            .executes(ctx -> {
                ctx.getSource().sendSuccess(() -> Component.literal("Hold a weapon and press F to choose an ability. Right-click to use it. See /amplifieditems altar to claim one."), false); return 1;
            })
            .then(Commands.literal("status").executes(ctx -> {
                var p = ctx.getSource().getPlayerOrException();
                var w = WeaponItems.definition(p.getMainHandItem());
                ctx.getSource().sendSuccess(() -> Component.literal(w == null ? "Hold an Amplified Item in your main hand."
                    : w.name() + " | /ability 1: " + AbilityControls.label(w.primary()) + " | /ability 2: " + AbilityControls.label(w.secondary())
                        + (w.primary().equals("rulers_command") || w.secondary().equals("rulers_command")
                            ? " | Stored mob: " + SUMMONS.status(p.getMainHandItem()) : "")), false); return 1;
            }))
            .then(Altars.commands(() -> altars))
            .then(Commands.literal("pack").executes(ctx -> { PACK.send(ctx.getSource().getPlayerOrException()); return 1; }))
            .then(Commands.literal("list").executes(ctx -> {
                ctx.getSource().sendSuccess(() -> Component.literal(String.join(", ", CONFIG.weapons.stream().filter(WeaponDefinition::enabled).map(WeaponDefinition::id).toList())), false); return 1;
            }))
            .then(Commands.literal("give").requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.argument("player", EntityArgument.players()).then(Commands.argument("weapon", StringArgumentType.word())
                    .suggests((ctx,builder) -> { CONFIG.weapons.stream().filter(WeaponDefinition::enabled).forEach(w -> builder.suggest(w.id())); return builder.buildFuture(); })
                    .executes(ctx -> {
                        String id = StringArgumentType.getString(ctx,"weapon");
                        var w = CONFIG.enabledWeapon(id);
                        if (w == null) { ctx.getSource().sendFailure(Component.literal("Weapon is disabled or unknown. Use /amplifieditems list")); return 0; }
                        var players = EntityArgument.getPlayers(ctx,"player");
                        for (ServerPlayer p : players) { ItemStack stack = WeaponItems.create(w); if (!p.getInventory().add(stack)) p.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY); }
                        ctx.getSource().sendSuccess(() -> Component.literal("Gave " + w.name() + " to " + players.size() + " player(s)."), true); return players.size();
                    }))))
            .then(Commands.literal("reload").requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)).executes(ctx -> {
                try {
                    var next = WeaponConfig.load(configPath()); WeaponItems.validate(next);
                    CONFIG = next;
                    altars.tick();
                    ctx.getSource().sendSuccess(() -> Component.literal("Weapon definitions and balance reloaded. Pack hosting changes require a server restart. Re-give items after changing their models or lore."), true); return 1;
                } catch (Exception e) { ctx.getSource().sendFailure(Component.literal("Config rejected; previous settings kept: " + e.getMessage())); return 0; }
            })));
        });
    }
}
