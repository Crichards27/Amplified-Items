package club.amplifiedsmp.items;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

final class Rituals {
    private static final String DEFENDER_TAG = "amplified_ritual_defender";
    private static final int DEFENSE_RADIUS = 24;
    private static final int WAVE_INTERVAL = 60 * 20;
    private static final int MAX_DEFENDERS = 6;

    private static final class Ritual {
        final UUID owner;
        final ServerLevel level;
        final BlockPos core;
        final BarrelBlockEntity barrel;
        final WeaponDefinition weapon;
        final long end;
        final long duration;
        final ServerBossEvent bar;
        long nextWave;
        int alive;

        Ritual(ServerPlayer player, BarrelBlockEntity barrel, WeaponDefinition weapon) {
            this.owner = player.getUUID();
            this.level = player.level();
            this.core = barrel.getBlockPos();
            this.barrel = barrel;
            this.weapon = weapon;
            this.duration = weapon.ritual().minutes() * 60L * 20;
            this.end = AmplifiedItems.tick + duration;
            this.nextWave = AmplifiedItems.tick + WAVE_INTERVAL;
            this.bar = new ServerBossEvent(UUID.randomUUID(), Component.literal(weapon.name()),
                BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS);
        }
    }

    private final MinecraftServer server;
    private final Map<UUID, Ritual> active = new HashMap<>();
    private final Map<UUID, Ritual> defenders = new HashMap<>();
    private final List<Entity> staleDefenders = new ArrayList<>();

    Rituals(MinecraftServer server) {
        this.server = server;
    }

    static LiteralArgumentBuilder<CommandSourceStack> commands(Supplier<Rituals> rituals) {
        return Commands.literal("ritual").executes(ctx -> {
            ctx.getSource().sendSuccess(() -> Component.literal(
                "Build a 3x3 square with a barrel in the middle and eight chiseled stone bricks around it. "
                + "Use /amplifieditems ritual recipe <weapon>, load the barrel, then aim at it and run "
                + "/amplifieditems ritual start <weapon>."), false);
            return 1;
        }).then(Commands.literal("recipe").then(weaponArgument().executes(ctx -> {
            WeaponDefinition weapon = AmplifiedItems.CONFIG.enabledWeapon(StringArgumentType.getString(ctx, "weapon"));
            if (weapon == null) return fail(ctx.getSource(), "Weapon is disabled or unknown.");
            Component recipe = Component.literal(weapon.name() + " (" + weapon.ritual().minutes() + " minutes)");
            ctx.getSource().sendSuccess(() -> recipe, false);
            for (var ingredient : weapon.ritual().ingredients().entrySet()) {
                ctx.getSource().sendSuccess(() -> Component.literal(ingredient.getValue() + " × ")
                    .append(RitualIngredients.name(ingredient.getKey())), false);
            }
            return 1;
        }))).then(Commands.literal("start").then(weaponArgument().executes(ctx ->
            rituals.get().start(ctx.getSource(), ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "weapon"))
        ))).then(Commands.literal("status").executes(ctx -> {
            var ritual = rituals.get().active.get(ctx.getSource().getPlayerOrException().getUUID());
            if (ritual == null) return fail(ctx.getSource(), "You have no active ritual.");
            ctx.getSource().sendSuccess(() -> Component.literal(progressText(ritual)), false);
            return 1;
        }));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> weaponArgument() {
        return Commands.argument("weapon", StringArgumentType.word()).suggests((ctx, builder) ->
            SharedSuggestionProvider.suggest(
                AmplifiedItems.CONFIG.weapons.stream().filter(WeaponDefinition::enabled).map(WeaponDefinition::id), builder));
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }

    private int start(CommandSourceStack source, ServerPlayer player, String id) {
        WeaponDefinition weapon = AmplifiedItems.CONFIG.enabledWeapon(id);
        if (weapon == null) return fail(source, "Weapon is disabled or unknown.");
        if (!canDefend(player)) return fail(source, "Start the ritual in Survival or Adventure mode.");
        if (active.containsKey(player.getUUID())) return fail(source, "You already have an active ritual.");

        ServerLevel level = player.level();
        if (level.getDifficulty() == Difficulty.PEACEFUL) return fail(source, "Rituals need hostile mobs. Switch off Peaceful.");
        Vec3 eye = player.getEyePosition();
        var hit = level.clip(new ClipContext(eye, eye.add(player.getLookAngle().scale(5)), ClipContext.Block.OUTLINE,
            ClipContext.Fluid.NONE, player));
        var barrel = hit.getType() == HitResult.Type.BLOCK ? altarAt(level, hit.getBlockPos()) : null;
        if (barrel == null) return fail(source, "Aim at the barrel in the center of a complete altar within 5 blocks.");
        Vec3 center = Vec3.atCenterOf(barrel.getBlockPos());
        if (active.values().stream().anyMatch(r -> r.level == level && center.distanceToSqr(Vec3.atCenterOf(r.core)) < 48 * 48))
            return fail(source, "Another ritual is already running within 48 blocks.");
        var ingredients = ingredients(barrel, weapon.ritual());
        if (ingredients == null) return fail(source, "The barrel is missing ingredients. Check /amplifieditems ritual recipe " + id);
        if (outputSlot(barrel, ingredients) < 0) return fail(source, "Leave a free slot in the barrel for the weapon.");
        if (spawnSpots(level, barrel.getBlockPos()).size() < waveSize(weapon))
            return fail(source, "Clear flat ground 6 to 12 blocks from the altar for the hostile waves.");

        var ritual = new Ritual(player, barrel, weapon);
        active.put(ritual.owner, ritual);
        updateBar(ritual);
        ritual.bar.addPlayer(player);
        var pos = ritual.core;
        server.getPlayerList().broadcastSystemMessage(Component.literal(player.getName().getString()
            + " started a " + weapon.ritual().minutes() + "-minute " + weapon.name() + " ritual at "
            + pos.toShortString() + " in " + level.dimension().identifier() + "."), false);
        return 1;
    }

    private static BarrelBlockEntity altarAt(ServerLevel level, BlockPos core) {
        if (!level.hasChunkAt(core) || !(level.getBlockEntity(core) instanceof BarrelBlockEntity barrel)) return null;
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) continue;
                BlockPos pos = core.offset(x, 0, z);
                if (!level.hasChunkAt(pos) || !level.getBlockState(pos).is(Blocks.CHISELED_STONE_BRICKS)) return null;
            }
        }
        return barrel;
    }

    private static Map<Integer, Integer> ingredients(BarrelBlockEntity barrel, RitualRecipe recipe) {
        var missing = new HashMap<>(recipe.ingredients());
        var slots = new LinkedHashMap<Integer, Integer>();
        boolean requiresPoison = missing.containsKey(RitualRecipe.POISON_ARROW);
        for (int slot = 0; slot < barrel.getContainerSize(); slot++) {
            ItemStack stack = barrel.getItem(slot);
            if (stack.isEmpty() || !WeaponItems.weaponId(stack).isEmpty()) continue;
            String id = RitualIngredients.key(stack, requiresPoison);
            int amount = Math.min(stack.getCount(), missing.getOrDefault(id, 0));
            if (amount == 0) continue;
            slots.put(slot, amount);
            int remaining = missing.get(id) - amount;
            if (remaining == 0) missing.remove(id);
            else missing.put(id, remaining);
        }
        return missing.isEmpty() ? slots : null;
    }

    private static int outputSlot(BarrelBlockEntity barrel, Map<Integer, Integer> ingredients) {
        for (int slot = 0; slot < barrel.getContainerSize(); slot++) {
            ItemStack stack = barrel.getItem(slot);
            if (stack.isEmpty() || ingredients.getOrDefault(slot, 0) == stack.getCount()) return slot;
        }
        return -1;
    }

    void tick() {
        for (Entity entity : staleDefenders) {
            if (!defenders.containsKey(entity.getUUID())) entity.discard();
        }
        staleDefenders.clear();
        for (Ritual ritual : List.copyOf(active.values())) {
            ServerPlayer owner = server.getPlayerList().getPlayer(ritual.owner);
            if (owner == null || !canDefend(owner) || owner.level() != ritual.level
                    || owner.distanceToSqr(Vec3.atCenterOf(ritual.core)) > DEFENSE_RADIUS * DEFENSE_RADIUS) {
                end(ritual, "Ritual failed. Stay alive and within 24 blocks of the altar.");
            } else if (altarAt(ritual.level, ritual.core) != ritual.barrel) {
                end(ritual, "Ritual failed. The altar was broken.");
            } else if (ritual.level.getDifficulty() == Difficulty.PEACEFUL) {
                end(ritual, "Ritual stopped because the world switched to Peaceful.");
            } else {
                var ingredients = ingredients(ritual.barrel, ritual.weapon.ritual());
                if (ingredients == null) {
                    end(ritual, "Ritual failed. The barrel lost its ingredients.");
                } else if (AmplifiedItems.tick >= ritual.end) {
                    finish(ritual, ingredients);
                } else {
                    if (AmplifiedItems.tick >= ritual.nextWave) {
                        ritual.nextWave += WAVE_INTERVAL;
                        if (!spawnWave(ritual, owner)) {
                            end(ritual, "Ritual failed. The wave had nowhere to spawn.");
                            continue;
                        }
                    }
                    updateBar(ritual);
                }
            }
        }
    }

    private void finish(Ritual ritual, Map<Integer, Integer> ingredients) {
        if (ritual.alive > 0) {
            end(ritual, "Ritual failed. Clear every hostile mob before time runs out.");
            return;
        }
        int output = outputSlot(ritual.barrel, ingredients);
        if (output < 0) {
            end(ritual, "Ritual failed. The barrel has no room for the weapon.");
            return;
        }
        ItemStack weapon = WeaponItems.create(ritual.weapon);
        ingredients.forEach((slot, amount) -> ritual.barrel.removeItem(slot, amount));
        ritual.barrel.setItem(output, weapon);
        ritual.barrel.setChanged();
        end(ritual, ritual.weapon.name() + " was forged. Collect it from the altar barrel.");
    }

    private static int waveSize(WeaponDefinition weapon) {
        return weapon.ritual().minutes() == 30 ? 3 : 2;
    }

    private static List<BlockPos> spawnSpots(ServerLevel level, BlockPos core) {
        var spots = new ArrayList<BlockPos>();
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                int squared = x * x + z * z;
                if (squared < 36 || squared > 144) continue;
                for (int y = -1; y <= 1; y++) {
                    BlockPos pos = core.offset(x, y, z);
                    if (!level.isPositionEntityTicking(pos) || !level.getWorldBorder().isWithinBounds(pos)) break;
                    if (level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()
                            && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) {
                        spots.add(pos);
                        break;
                    }
                }
            }
        }
        return spots;
    }

    private boolean spawnWave(Ritual ritual, ServerPlayer owner) {
        int count = Math.min(waveSize(ritual.weapon), MAX_DEFENDERS - ritual.alive);
        if (count == 0) return true;
        var spots = spawnSpots(ritual.level, ritual.core);
        int spawned = 0;
        while (spawned < count && !spots.isEmpty()) {
            BlockPos pos = spots.remove(ritual.level.getRandom().nextInt(spots.size()));
            var mob = EntityTypes.HUSK.create(ritual.level, EntitySpawnReason.EVENT);
            if (mob == null) break;
            mob.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            if (!ritual.level.noCollision(mob)) continue;
            mob.setPersistenceRequired();
            mob.setTarget(owner);
            mob.addTag(DEFENDER_TAG);
            defenders.put(mob.getUUID(), ritual);
            if (ritual.level.addFreshEntity(mob)) {
                ritual.alive++;
                spawned++;
            } else {
                defenders.remove(mob.getUUID());
            }
        }
        return spawned > 0;
    }

    private static boolean canDefend(ServerPlayer player) {
        return player.isAlive() && !player.isSpectator() && !player.isCreative();
    }

    private static String progressText(Ritual ritual) {
        long seconds = (Math.max(0, ritual.end - AmplifiedItems.tick) + 19) / 20;
        return "%s | %d:%02d | %d hostiles".formatted(ritual.weapon.name(), seconds / 60, seconds % 60, ritual.alive);
    }

    private static void updateBar(Ritual ritual) {
        ritual.bar.setName(Component.literal(progressText(ritual)));
        ritual.bar.setProgress((float) (ritual.end - AmplifiedItems.tick) / ritual.duration);
    }

    static boolean isDefender(Entity entity) {
        return entity.entityTags().contains(DEFENDER_TAG);
    }

    void entityLoaded(Entity entity) {
        // The load callback can run while Minecraft is iterating the entity section.
        if (isDefender(entity) && !defenders.containsKey(entity.getUUID())) staleDefenders.add(entity);
    }

    void mobConverted(Mob previous, Mob converted) {
        Ritual ritual = defenders.remove(previous.getUUID());
        if (ritual == null) return;
        converted.addTag(DEFENDER_TAG);
        converted.setPersistenceRequired();
        defenders.put(converted.getUUID(), ritual);
    }

    void entityDied(Entity entity) {
        if (entity instanceof ServerPlayer player) {
            var ritual = active.get(player.getUUID());
            if (ritual != null) end(ritual, "Ritual failed. You died.");
        }
        Ritual ritual = defenders.remove(entity.getUUID());
        if (ritual != null) ritual.alive--;
    }

    void ownerLeft(ServerPlayer owner) {
        var ritual = active.get(owner.getUUID());
        if (ritual != null) end(ritual, "Ritual failed. You disconnected.");
    }

    void blockBroken(ServerLevel level, BlockPos pos) {
        for (Ritual ritual : List.copyOf(active.values())) {
            if (ritual.level == level && pos.getY() == ritual.core.getY()
                    && Math.abs(pos.getX() - ritual.core.getX()) <= 1 && Math.abs(pos.getZ() - ritual.core.getZ()) <= 1) {
                end(ritual, "Ritual failed. The altar was broken.");
            }
        }
    }

    void settingsReloaded() {
        for (Ritual ritual : List.copyOf(active.values())) {
            if (!ritual.weapon.equals(AmplifiedItems.CONFIG.enabledWeapon(ritual.weapon.id())))
                end(ritual, "Ritual stopped because the weapon settings changed.");
        }
    }

    void stop() {
        for (Ritual ritual : List.copyOf(active.values())) end(ritual, "Ritual stopped for server shutdown.");
    }

    private void end(Ritual ritual, String message) {
        active.remove(ritual.owner);
        ritual.bar.removeAllPlayers();
        for (var iterator = defenders.entrySet().iterator(); iterator.hasNext();) {
            var defender = iterator.next();
            if (defender.getValue() != ritual) continue;
            Entity mob = ritual.level.getEntity(defender.getKey());
            if (mob != null) mob.discard();
            iterator.remove();
        }
        var owner = server.getPlayerList().getPlayer(ritual.owner);
        if (owner != null) owner.sendSystemMessage(Component.literal(message));
    }
}
