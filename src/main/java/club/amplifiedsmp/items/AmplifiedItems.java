package club.amplifiedsmp.items;

import club.amplifiedsmp.items.mixin.LivingFlagsAccess;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.*;
import net.minecraft.world.entity.LivingEntity;
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
        AbilityMenu.register();
        UseItemCallback.EVENT.register((player, level, hand) -> useSelectedAbility(player, hand, null));
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (player instanceof ServerPlayer serverPlayer && altars != null) {
                var result = altars.use(serverPlayer, hand, hit.getBlockPos());
                if (result != InteractionResult.PASS) {
                    LAST_USE.put(serverPlayer.getUUID(), tick);
                    return result;
                }
            }
            return useSelectedAbility(player, hand, null);
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
            useSelectedAbility(player, hand, entity instanceof LivingEntity living ? living : null));
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

    public static boolean useAbilityKey(ServerPlayer player) {
        if (player.isSpectator() || !player.isAlive()) return false;
        WeaponDefinition weapon = WeaponItems.definition(player.getMainHandItem());
        if (weapon == null) return false;
        if (player.containerMenu == player.inventoryMenu) AbilityMenu.open(player, weapon);
        return true;
    }

    private static InteractionResult useSelectedAbility(Player user, InteractionHand hand, LivingEntity target) {
        if (!(user instanceof ServerPlayer player) || !player.isAlive() || player.isSpectator()
                || player.isShiftKeyDown() || player.containerMenu != player.inventoryMenu) return InteractionResult.PASS;
        ItemStack item = player.getMainHandItem();
        WeaponDefinition weapon = WeaponItems.definition(item);
        if (weapon == null) return InteractionResult.PASS;
        String ability = AbilityMenu.selection(player, weapon);
        if (ability.equals(AbilityMenu.NORMAL_USE)) return InteractionResult.PASS;

        // A vanilla client can predict drawing a weapon or try its offhand after the same click.
        if (player.getItemInHand(hand).getUseDuration(player) > 0) {
            var flag = LivingFlagsAccess.amplifiedItems$livingFlags();
            player.connection.send(new ClientboundSetEntityDataPacket(player.getId(),
                List.of(SynchedEntityData.DataValue.create(flag, player.getEntityData().get(flag)))));
        }
        if (hand != InteractionHand.MAIN_HAND || LAST_USE.getOrDefault(player.getUUID(), -100L) + 3 > tick)
            return InteractionResult.CONSUME;
        LAST_USE.put(player.getUUID(), tick);

        if (ability.equals("riptide") && PlayerAbilities.repeatRiptide(player, item)) return InteractionResult.CONSUME;
        long remaining = COOLDOWNS.remaining(player.getUUID(), ability, tick);
        if (remaining > 0) {
            Abilities.message(player, "Ability ready in " + (remaining + 19) / 20 + "s");
        } else if (Abilities.REGISTRY.get(ability).use(player, item, target)) {
            double seconds = ability.equals(weapon.primary()) ? weapon.primaryCooldownSeconds() : weapon.secondaryCooldownSeconds();
            COOLDOWNS.start(player.getUUID(), ability, tick, seconds);
            BARS.update(player);
            player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
        }
        return InteractionResult.CONSUME;
    }

    private static void commands() {
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment) -> {
            dispatcher.register(Commands.literal("amplifieditems")
            .executes(ctx -> {
                ctx.getSource().sendSuccess(() -> Component.literal("Hold a weapon and press F to choose an ability. Right-click to use it. See /amplifieditems altar to claim one."), false); return 1;
            })
            .then(Commands.literal("status").executes(ctx -> {
                var p = ctx.getSource().getPlayerOrException();
                var w = WeaponItems.definition(p.getMainHandItem());
                ctx.getSource().sendSuccess(() -> Component.literal(w == null ? "Hold an Amplified Item in your main hand."
                    : w.name() + " | Selected: " + AbilityMenu.label(AbilityMenu.selection(p, w))
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
