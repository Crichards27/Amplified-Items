package club.amplifiedsmp.items;

import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import java.util.UUID;

/** ISOLATED TEST SERVER ONLY; independent combat/passive audit regressions. */
public final class AuditRuntimeChecks implements ModInitializer {
    private static int checks;
    private static final java.util.List<String> MESSAGES = new java.util.ArrayList<>();

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("AUDIT_RUNTIME_CHECK " + (++checks) + " " + message);
    }

    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                run(server);
                System.out.println("AUDIT_RUNTIME_OK " + checks);
            } catch (Throwable failure) {
                System.out.println("AUDIT_RUNTIME_FAILED");
                failure.printStackTrace();
            } finally { server.halt(false); }
        });
    }

    public static void run(MinecraftServer server) throws Exception {
        checksCommandHelp(server);
        preservesExternalReaperSpeed(server);
        checksCombatAndRiptide(server);
        checksBloodFangMigration(server);
        checksAltarFailureAtomicity(server);
    }

    private static void checksAltarFailureAtomicity(MinecraftServer server) throws Exception {
        var level = server.overworld();
        level.setChunkForced(0, 0, true);
        level.getChunkSource().tick(() -> true, false);
        level.waitForEntities(new net.minecraft.world.level.ChunkPos(0, 0), 0);
        var pos = new net.minecraft.core.BlockPos(10, 110, 10);
        level.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        var owner = player(server, "AuditAltar");
        owner.setPos(10.5, 110, 8.5);
        var weapon = new WeaponDefinition("audit_wand", "Audit Wand", "minecraft:stick", "amplified_items:windwalker",
            "none", "none", "none", 0, 0, java.util.List.of());
        var ledger = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/amplified-items/altars.json");
        byte[] original = java.nio.file.Files.exists(ledger) ? java.nio.file.Files.readAllBytes(ledger) : null;
        var originalWeapons = AmplifiedItems.CONFIG.weapons;
        AmplifiedItems.CONFIG.weapons = new java.util.ArrayList<>(originalWeapons);
        AmplifiedItems.CONFIG.weapons.add(weapon);
        var altars = new Altars(server);
        try {
            var dispatcher = new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
            dispatcher.register(net.minecraft.commands.Commands.literal("audit_only").then(Altars.commands(() -> altars)));
            var admin = owner.createCommandSourceStack().withPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS);
            check(dispatcher.execute("audit_only altar place audit_wand 10 110 10", admin) == 1,
                "Audit altar is registered through the real operator placement path");
            owner.getInventory().setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK));
            owner.getInventory().setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 2));
            byte[] placed = java.nio.file.Files.readAllBytes(ledger);
            java.nio.file.Files.delete(ledger);
            java.nio.file.Files.createDirectory(ledger);
            try {
                altars.use(owner, InteractionHand.MAIN_HAND, pos);
                check(owner.getInventory().countItem(net.minecraft.world.item.Items.DIAMOND) == 2
                        && owner.getInventory().countItem(net.minecraft.world.item.Items.STICK) == 1
                        && level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.BEDROCK),
                    "Failed durable claim writes preserve every ingredient and the altar");
            } finally {
                java.nio.file.Files.delete(ledger);
                java.nio.file.Files.write(ledger, placed);
            }
            altars.use(owner, InteractionHand.MAIN_HAND, pos);
            check(weapon.equals(WeaponItems.definition(owner.getInventory().getItem(0)))
                    && owner.getInventory().countItem(net.minecraft.world.item.Items.DIAMOND) == 0
                    && level.getBlockState(pos).isAir(),
                "A successful retry consumes the exact ingredients and awards one weapon");
            var restarted = new Altars(server);
            var afterRestart = new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
            afterRestart.register(net.minecraft.commands.Commands.literal("audit_only").then(Altars.commands(() -> restarted)));
            check(afterRestart.execute("audit_only altar place audit_wand 11 110 10", admin) == 0,
                "Reopening the ledger cannot duplicate a successfully claimed weapon");
            restarted.stop();
        } finally {
            altars.stop();
            level.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            if (original == null) java.nio.file.Files.deleteIfExists(ledger);
            else java.nio.file.Files.write(ledger, original);
            AmplifiedItems.CONFIG.weapons = originalWeapons;
        }
    }

    private static void checksCommandHelp(MinecraftServer server) throws Exception {
        var owner = player(server, "AuditHelp");
        MESSAGES.clear();
        server.getCommands().getDispatcher().execute("amplifieditems", owner.createCommandSourceStack());
        check(MESSAGES.stream().anyMatch(text -> text.contains("/ability 1") && text.contains("/ability 2"))
                && MESSAGES.stream().noneMatch(text -> text.contains("press F")),
            "Command help describes working /ability controls rather than the removed F-key menu");
        owner.setItemInHand(InteractionHand.MAIN_HAND, WeaponItems.create(AmplifiedItems.CONFIG.enabledWeapon("kings_decree")));
        MESSAGES.clear();
        server.getCommands().getDispatcher().execute("amplifieditems status", owner.createCommandSourceStack());
        check(MESSAGES.stream().anyMatch(text -> text.contains("Royal guard:"))
                && MESSAGES.stream().noneMatch(text -> text.contains("Stored mob:")),
            "Staff status describes the royal guard, not the removed captured-mob mechanic");
    }

    private static void checksCombatAndRiptide(MinecraftServer server) {
        var level = server.overworld();
        var owner = player(server, "AuditCaster");
        var target = player(server, "AuditEnemy");
        check(!Combat.canAffect(owner, owner), "Combat excludes the caster");
        check(Combat.canAffect(owner, target), "Combat permits an ordinary enemy player");
        target.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
        check(!Combat.canAffect(owner, target), "Combat excludes Creative players");
        target.gameMode.changeGameModeForPlayer(GameType.SPECTATOR);
        check(!Combat.canAffect(owner, target), "Combat excludes Spectators");
        target.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.PVP, false, server);
        check(!Combat.canAffect(owner, target), "Combat respects the server PvP rule");
        level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.PVP, true, server);
        var weapon = WeaponItems.create(AmplifiedItems.CONFIG.enabledWeapon("leviathans_wrath"));
        owner.setItemInHand(InteractionHand.MAIN_HAND, weapon);
        AmplifiedItems.tick = 10000;
        check(AmplifiedItems.castAbility(owner, 2) == 1, "Riptide starts on dry land through the real ability casting path");
        owner.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        AmplifiedItems.tick += 4;
        check(AmplifiedItems.castAbility(owner, 2) == 1 && owner.getDeltaMovement().lengthSqr() == 0,
            "Riptide repeat attempts before one second do not change velocity");
        AmplifiedItems.tick += 16;
        check(AmplifiedItems.castAbility(owner, 2) == 1 && owner.getDeltaMovement().lengthSqr() > 0,
            "Riptide repeat becomes ready at exactly one second");
        owner.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        owner.setItemInHand(InteractionHand.MAIN_HAND, WeaponItems.create(AmplifiedItems.CONFIG.enabledWeapon("leviathans_wrath")));
        AmplifiedItems.tick += 20;
        check(AmplifiedItems.castAbility(owner, 2) == 0 && owner.getDeltaMovement().lengthSqr() == 0,
            "A different physical trident cannot reuse the original Riptide window");
        owner.setItemInHand(InteractionHand.MAIN_HAND, weapon);
        AmplifiedItems.tick = 10200;
        check(AmplifiedItems.castAbility(owner, 2) == 0, "Riptide window closes at its exact expiry");
        AmplifiedItems.tick += 4;
        check(AmplifiedItems.castAbility(owner, 1) == 0
                && AmplifiedItems.COOLDOWNS.remaining(owner.getUUID(), "tide", AmplifiedItems.tick) == 0,
            "Switching to Tide cannot bypass this weapon's active Riptide cooldown");
    }

    private static void checksBloodFangMigration(MinecraftServer server) {
        var player = player(server, "AuditBloodfang");
        var weapon = WeaponItems.create(AmplifiedItems.CONFIG.enabledWeapon("bloodfang"));
        var legacy = weapon.transmuteCopy(net.minecraft.world.item.Items.NETHERITE_SWORD);
        var id = WeaponItems.instance(legacy);
        legacy.setDamageValue(23);
        player.setItemInHand(InteractionHand.MAIN_HAND, legacy);
        WeaponItems.upgradeHeldBloodFang(player, InteractionHand.MAIN_HAND);
        var upgraded = player.getMainHandItem();
        check(upgraded.is(net.minecraft.world.item.Items.NETHERITE_AXE)
                && id.equals(WeaponItems.instance(upgraded)) && upgraded.getDamageValue() == 23,
            "Legacy BloodFang upgrades to an axe while preserving weapon identity and wear");
        var ordinary = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.NETHERITE_SWORD);
        player.setItemInHand(InteractionHand.OFF_HAND, ordinary);
        WeaponItems.upgradeHeldBloodFang(player, InteractionHand.OFF_HAND);
        check(player.getOffhandItem() == ordinary, "Ordinary netherite swords are not migrated");
    }

    private static void preservesExternalReaperSpeed(MinecraftServer server) {
        ServerPlayer player = player(server, "AuditReaper");
        var reaper = WeaponItems.create(AmplifiedItems.CONFIG.enabledWeapon("reapers_edge"));
        player.setItemInHand(InteractionHand.MAIN_HAND, reaper);
        // Earn hearts through the real death hook instead of mutating the attachment internals.
        for (int kill = 0; kill < 5; kill++) {
            ServerPlayer victim = player(server, "AuditVictim" + kill);
            ReaperHearts.death(victim, server.overworld().damageSources().playerAttack(player));
        }
        ReaperHearts.tick(player);
        check(player.hasEffect(MobEffects.SPEED), "Five earned hearts grant held Reaper Speed I");
        player.addEffect(new MobEffectInstance(MobEffects.SPEED, 400, 1));
        player.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
        ReaperHearts.tick(player);
        check(player.hasEffect(MobEffects.SPEED) && player.getEffect(MobEffects.SPEED).getAmplifier() == 1
            && player.getEffect(MobEffects.SPEED).getDuration() == 400,
            "Putting away Reaper preserves a stronger externally applied Speed effect");
    }

    private static ServerPlayer player(MinecraftServer server, String name) {
        var profile = new GameProfile(UUID.randomUUID(), name);
        var player = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault());
        var connection = new Connection(PacketFlow.SERVERBOUND) {
            @Override public void send(Packet<?> packet) {
                if (packet instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket chat) MESSAGES.add(chat.content().getString());
            }
            @Override public void send(Packet<?> packet, io.netty.channel.ChannelFutureListener listener) { send(packet); }
            @Override public void send(Packet<?> packet, io.netty.channel.ChannelFutureListener listener, boolean flush) { send(packet); }
        };
        player.connection = new ServerGamePacketListenerImpl(server, connection, player, CommonListenerCookie.createInitial(profile, false)) {
            @Override public boolean hasClientLoaded() { return true; }
        };
        player.setPos(0.5, 101, 0.5);
        player.setInvulnerableTime(0);
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        server.getPlayerList().getPlayers().add(player);
        server.getPlayerList().getPlayersByUUID().put(player.getUUID(), player);
        server.overworld().addNewPlayer(player);
        return player;
    }
}
