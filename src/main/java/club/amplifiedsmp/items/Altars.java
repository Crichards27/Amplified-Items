package club.amplifiedsmp.items;

import club.amplifiedsmp.items.mixin.AltarDisplayAccess;
import club.amplifiedsmp.items.mixin.PlayerSaveAccess;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

final class Altars {
    private record Site(String dimension, long position) {
        BlockPos pos() { return BlockPos.of(position); }
    }

    private record Ledger(Map<String, Site> sites, Map<String, UUID> claims, Set<String> events) {
        Ledger {
            sites = Map.copyOf(sites);
            claims = Map.copyOf(claims);
            events = Set.copyOf(events);
        }
    }

    private record Preview(Display.ItemDisplay item, Display.TextDisplay text, WeaponDefinition weapon, String gate) {
        void discard() { item.discard(); text.discard(); }
    }

    private final MinecraftServer server;
    private final Path file;
    private Ledger ledger;
    private final List<Entity> retiredDefenders = new ArrayList<>();
    private final Map<String, Preview> previews = new HashMap<>();

    Altars(MinecraftServer server) throws IOException {
        this.server = server;
        file = server.getWorldPath(LevelResource.ROOT).resolve("data/amplified-items/altars.json");
        ledger = Files.exists(file)
            ? WeaponConfig.JSON.fromJson(Files.readString(file), Ledger.class)
            : new Ledger(Map.of(), Map.of(), Set.of());
        if (ledger == null || new HashSet<>(ledger.sites().values()).size() != ledger.sites().size()
                || ledger.sites().values().stream().anyMatch(site -> Identifier.tryParse(site.dimension()) == null)) {
            throw new IOException("Invalid altar ledger");
        }
    }

    static LiteralArgumentBuilder<CommandSourceStack> commands(Supplier<Altars> altars) {
        var root = Commands.literal("altar").executes(ctx -> info(ctx.getSource(),
            "Bring the listed ingredients in your inventory and right-click an altar. Each weapon is claimed once."));
        root.then(Commands.literal("recipe").then(weaponArgument().executes(ctx -> {
            WeaponDefinition weapon = AmplifiedItems.CONFIG.enabledWeapon(StringArgumentType.getString(ctx, "weapon"));
            if (weapon == null) return fail(ctx.getSource(), "Weapon is disabled or unknown.");
            ctx.getSource().sendSuccess(() -> altars.get().description(weapon), false);
            return 1;
        })));
        root.then(admin("list").executes(ctx -> altars.get().list(ctx.getSource())));
        root.then(admin("place").then(weaponArgument().then(Commands.argument("pos", BlockPosArgument.blockPos())
            .executes(ctx -> altars.get().place(ctx.getSource(), StringArgumentType.getString(ctx, "weapon"),
                BlockPosArgument.getLoadedBlockPos(ctx, "pos"))))));
        root.then(admin("remove").then(weaponArgument().executes(ctx ->
            altars.get().remove(ctx.getSource(), StringArgumentType.getString(ctx, "weapon")))));
        root.then(admin("reset").then(weaponArgument().executes(ctx ->
            altars.get().reset(ctx.getSource(), StringArgumentType.getString(ctx, "weapon")))));
        root.then(admin("event").then(Commands.argument("event", StringArgumentType.word())
            .then(Commands.literal("open").executes(ctx ->
                altars.get().event(ctx.getSource(), StringArgumentType.getString(ctx, "event"), true)))
            .then(Commands.literal("close").executes(ctx ->
                altars.get().event(ctx.getSource(), StringArgumentType.getString(ctx, "event"), false)))));
        return root;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> admin(String name) {
        return Commands.literal(name).requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> weaponArgument() {
        return Commands.argument("weapon", StringArgumentType.word()).suggests((ctx, builder) ->
            SharedSuggestionProvider.suggest(AmplifiedItems.CONFIG.weapons.stream().map(WeaponDefinition::id), builder));
    }

    private static int info(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }

    private int list(CommandSourceStack source) {
        for (var entry : ledger.sites().entrySet()) {
            Site site = entry.getValue();
            info(source, entry.getKey() + " at " + site.pos().toShortString() + " in " + site.dimension()
                + (ledger.claims().containsKey(entry.getKey()) ? " (claimed)" : ""));
        }
        var removed = ledger.claims().keySet().stream().filter(id -> !ledger.sites().containsKey(id)).sorted().toList();
        if (!removed.isEmpty()) info(source, "Claimed without a registered altar: " + String.join(", ", removed));
        return info(source, "Open altar events: " + (ledger.events().isEmpty() ? "none" : String.join(", ", ledger.events())));
    }

    private int place(CommandSourceStack source, String weapon, BlockPos pos) {
        if (AmplifiedItems.CONFIG.enabledWeapon(weapon) == null) return fail(source, "Weapon is disabled or unknown.");
        if (ledger.claims().containsKey(weapon)) return fail(source, "That weapon has already been claimed. Reset it first.");
        if (ledger.sites().containsKey(weapon)) return fail(source, "That weapon already has an altar.");
        ServerLevel level = source.getLevel();
        Site site = at(level, pos);
        if (ledger.sites().containsValue(site) || !level.getBlockState(pos).isAir()
                || level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)) return fail(source, "Choose an empty block inside the world border.");
        var sites = new HashMap<>(ledger.sites());
        sites.put(weapon, site);
        if (!commit(new Ledger(sites, ledger.claims(), ledger.events()))) return fail(source, "Could not save the altar.");
        reconcile(weapon, site);
        return info(source, "Placed " + weapon + " altar at " + pos.toShortString() + ".");
    }

    private int remove(CommandSourceStack source, String weapon) {
        Site site = ledger.sites().get(weapon);
        if (site == null) return fail(source, "That weapon has no registered altar.");
        ServerLevel level = loadedLevel(site);
        if (level == null) return fail(source, "Load the altar's chunk before removing it.");
        var sites = new HashMap<>(ledger.sites());
        sites.remove(weapon);
        if (!commit(new Ledger(sites, ledger.claims(), ledger.events()))) return fail(source, "Could not save the altar removal.");
        removeBlock(level, site.pos());
        discardPreview(weapon);
        return info(source, "Removed " + weapon + " altar.");
    }

    private int reset(CommandSourceStack source, String weapon) {
        if (!ledger.claims().containsKey(weapon)) return fail(source, "That weapon has no recorded claim.");
        var claims = new HashMap<>(ledger.claims());
        claims.remove(weapon);
        if (!commit(new Ledger(ledger.sites(), claims, ledger.events()))) return fail(source, "Could not reset the claim.");
        Site site = ledger.sites().get(weapon);
        if (site != null) reconcile(weapon, site);
        return info(source, "Reset " + weapon + ". Previously awarded copies must be collected separately.");
    }

    private int event(CommandSourceStack source, String event, boolean open) {
        if (!event.matches("[a-z0-9_]+")) return fail(source, "Event IDs use lowercase letters, numbers, and underscores.");
        var events = new HashSet<>(ledger.events());
        if (open) events.add(event);
        else events.remove(event);
        if (!commit(new Ledger(ledger.sites(), ledger.claims(), events))) return fail(source, "Could not save the event.");
        tick();
        return info(source, "Altar event " + event + (open ? " opened." : " closed."));
    }

    private boolean commit(Ledger next) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "altars-", ".json");
            try {
                Files.writeString(temp, WeaponConfig.JSON.toJson(next));
                try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
            ledger = next;
            return true;
        } catch (IOException e) {
            AmplifiedItems.LOG.error("Could not save altar ledger", e);
            return false;
        }
    }

    boolean protectedBlock(ServerLevel level, BlockPos pos) {
        String weapon = weaponAt(level, pos);
        return weapon != null && !ledger.claims().containsKey(weapon);
    }

    private String weaponAt(ServerLevel level, BlockPos pos) {
        Site target = at(level, pos);
        for (var entry : ledger.sites().entrySet()) if (entry.getValue().equals(target)) return entry.getKey();
        return null;
    }

    InteractionResult use(ServerPlayer player, InteractionHand hand, BlockPos pos) {
        if (hand != InteractionHand.MAIN_HAND || !player.level().getBlockState(pos).is(Blocks.BEDROCK))
            return InteractionResult.PASS;
        String id = weaponAt(player.level(), pos);
        if (id == null) return InteractionResult.PASS;
        if (!player.isAlive() || player.isSpectator() || player.isCreative()) {
            Abilities.message(player, "Claim altars in Survival or Adventure mode.");
        } else if (ledger.claims().containsKey(id)) {
            Abilities.message(player, "That weapon has already been claimed.");
        } else {
            WeaponDefinition weapon = AmplifiedItems.CONFIG.enabledWeapon(id);
            if (weapon == null) Abilities.message(player, "This altar is disabled.");
            else if (closedEvent(id) != null) Abilities.message(player, "Locked until event " + closedEvent(id) + " opens.");
            else claim(player, weapon, pos);
        }
        return InteractionResult.CONSUME;
    }

    private void claim(ServerPlayer player, WeaponDefinition weapon, BlockPos pos) {
        var inventory = player.getInventory().getNonEquipmentItems();
        var missing = new LinkedHashMap<>(weapon.ritual().ingredients());
        boolean poison = missing.containsKey(RitualRecipe.POISON_ARROW);
        boolean heads = missing.containsKey(RitualRecipe.PVP_HEAD);
        var consume = new LinkedHashMap<Integer, Integer>();
        int output = -1;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.get(slot);
            if (stack.isEmpty()) {
                if (output < 0) output = slot;
                continue;
            }
            if (!WeaponItems.weaponId(stack).isEmpty()) continue;
            String key = RitualIngredients.key(stack, poison, heads);
            int count = Math.min(stack.getCount(), missing.getOrDefault(key, 0));
            if (count == 0) continue;
            consume.put(slot, count);
            if (output < 0 && count == stack.getCount()) output = slot;
            if (missing.get(key) == count) missing.remove(key);
            else missing.put(key, missing.get(key) - count);
        }
        if (!missing.isEmpty()) {
            player.sendSystemMessage(recipeLines(Component.literal("Missing ingredients for " + weapon.name()), missing));
            return;
        }
        if (output < 0) {
            Abilities.message(player, "Make room in your inventory for the weapon.");
            return;
        }

        ItemStack reward = WeaponItems.create(weapon);
        var claims = new HashMap<>(ledger.claims());
        claims.put(weapon.id(), player.getUUID());
        // A failed write leaves the altar and every item in place. The saved claim prevents retries after a restart.
        if (!commit(new Ledger(ledger.sites(), claims, ledger.events()))) {
            Abilities.message(player, "Could not save your claim. Try again or tell an admin.");
            return;
        }
        consume.forEach((slot, count) -> player.getInventory().removeItem(slot, count));
        player.getInventory().setItem(output, reward);
        ((PlayerSaveAccess) server.getPlayerList()).amplifiedItems$save(player);
        player.inventoryMenu.broadcastChanges();
        reconcile(weapon.id(), ledger.sites().get(weapon.id()));

        var bolt = EntityTypes.LIGHTNING_BOLT.create(player.level(), EntitySpawnReason.EVENT);
        if (bolt != null) {
            bolt.setVisualOnly(true);
            bolt.setPos(Vec3.atBottomCenterOf(pos.above()));
            player.level().addFreshEntity(bolt);
        }
        server.getPlayerList().broadcastSystemMessage(Component.literal(player.getGameProfile().name() + " claimed "
            + weapon.name() + "!").withStyle(ChatFormatting.GOLD), false);
    }

    private Component description(WeaponDefinition weapon) {
        String event = closedEvent(weapon.id());
        var title = Component.literal(weapon.name()).withStyle(ChatFormatting.GOLD);
        if (event != null) title.append(Component.literal("\nLocked until event " + event + " opens.").withStyle(ChatFormatting.RED));
        return recipeLines(title, weapon.ritual().ingredients());
    }

    private static Component recipeLines(Component title, Map<String, Integer> ingredients) {
        var text = title.copy();
        for (var entry : ingredients.entrySet()) {
            text.append(Component.literal("\n" + entry.getValue() + " × ").withStyle(ChatFormatting.WHITE)
                .append(RitualIngredients.name(entry.getKey())));
        }
        return text;
    }

    private String closedEvent(String weapon) {
        String event = AmplifiedItems.CONFIG.altarEventRequirements.get(weapon);
        return event != null && !ledger.events().contains(event) ? event : null;
    }

    private static Site at(ServerLevel level, BlockPos pos) {
        return new Site(level.dimension().identifier().toString(), pos.asLong());
    }

    private ServerLevel loadedLevel(Site site) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(site.dimension())));
        return level != null && level.hasChunkAt(site.pos()) ? level : null;
    }

    void retireLegacyDefender(Entity entity) {
        retiredDefenders.add(entity);
    }

    void tick() {
        retiredDefenders.forEach(Entity::discard);
        retiredDefenders.clear();
        ledger.sites().forEach(this::reconcile);
    }

    private void reconcile(String id, Site site) {
        ServerLevel level = loadedLevel(site);
        if (level == null) {
            discardPreview(id);
            return;
        }
        BlockPos pos = site.pos();
        if (ledger.claims().containsKey(id)) {
            discardPreview(id);
            removeBlock(level, pos);
            return;
        }
        if (level.getBlockState(pos).isAir()) level.setBlockAndUpdate(pos, Blocks.BEDROCK.defaultBlockState());
        WeaponDefinition weapon = AmplifiedItems.CONFIG.enabledWeapon(id);
        if (weapon == null || !level.getBlockState(pos).is(Blocks.BEDROCK)) {
            discardPreview(id);
            return;
        }
        String gate = closedEvent(id);
        Preview preview = previews.get(id);
        if (preview != null && !preview.item().isRemoved() && !preview.text().isRemoved()
                && preview.weapon().equals(weapon) && Objects.equals(preview.gate(), gate)) return;
        discardPreview(id);
        var item = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.EVENT);
        var text = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.EVENT);
        if (item == null || text == null) return;
        setupDisplay(item, pos, 1.65);
        setupDisplay(text, pos, 2.20);
        ((AltarDisplayAccess.Item) item).amplifiedItems$setItem(WeaponItems.create(weapon));
        ((AltarDisplayAccess.Item) item).amplifiedItems$setTransform(ItemDisplayContext.GROUND);
        ((AltarDisplayAccess.Text) text).amplifiedItems$setText(description(weapon));
        if (level.addFreshEntity(item) && level.addFreshEntity(text)) previews.put(id, new Preview(item, text, weapon, gate));
        else { item.discard(); text.discard(); }
    }

    private static void setupDisplay(Display display, BlockPos pos, double y) {
        display.setPos(pos.getX() + 0.5, pos.getY() + y, pos.getZ() + 0.5);
        display.addTag("amplified_altar_display");
        ((AltarDisplayAccess.Base) display).amplifiedItems$setBillboard(Display.BillboardConstraints.CENTER);
    }

    private static void removeBlock(ServerLevel level, BlockPos pos) {
        if (level.getBlockState(pos).is(Blocks.BEDROCK)) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
    }

    private void discardPreview(String id) {
        Preview preview = previews.remove(id);
        if (preview != null) preview.discard();
    }

    void stop() {
        retiredDefenders.forEach(Entity::discard);
        retiredDefenders.clear();
        previews.values().forEach(Preview::discard);
        previews.clear();
    }
}
