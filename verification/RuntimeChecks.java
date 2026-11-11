package club.amplifiedsmp.items.verification;

import club.amplifiedsmp.items.*;
import club.amplifiedsmp.items.mixin.ChunkMapAccess;
import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.Connection;
import net.minecraft.network.HashedStack;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.*;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.*;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.phys.*;
import java.util.*;

/** ISOLATED TEST SERVER ONLY. Never install the verification jar on a real server. */
public final class RuntimeChecks implements ModInitializer {
    static int checks;
    private static String lastClaim;
    private static int bossPackets;
    private static ClientboundOpenScreenPacket lastScreen;
    private static int predictedUseCorrections;
    private static int sequence;
    private static final List<ClientboundBlockUpdatePacket> BLOCK_UPDATES = new ArrayList<>();
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("AMPLIFIED_CHECK " + (++checks) + " " + message);
    }
    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                if (Boolean.getBoolean("amplified.verifyRestart")) {
                    // Check the parked summon before the altar check drives an end-of-server tick.
                    checkStaffRestart(server);
                    checkAltarRestart(server);
                } else run(server);
                System.out.println("AMPLIFIED_RUNTIME_OK " + checks);
            }
            catch (Throwable e) { System.out.println("AMPLIFIED_RUNTIME_FAILED"); e.printStackTrace(); }
            finally { server.halt(false); }
        });
    }
    static ServerPlayer player(MinecraftServer server, String name, double x, double z) {
        var profile = new GameProfile(UUID.randomUUID(), name);
        var p = new ServerPlayer(server,server.overworld(),profile,ClientInformation.createDefault());
        var c = new Connection(PacketFlow.SERVERBOUND) {
            @Override public void send(Packet<?> packet) {
                if (packet instanceof ClientboundBlockUpdatePacket update) BLOCK_UPDATES.add(update);
                if (packet instanceof ClientboundOpenScreenPacket screen) lastScreen = screen;
                if (packet instanceof ClientboundSetEntityDataPacket) predictedUseCorrections++;
                if (packet instanceof ClientboundBossEventPacket) bossPackets++;
                if (packet instanceof ClientboundSystemChatPacket chat) {
                    String message = chat.content().getString();
                    if (message.contains(" claimed ")) lastClaim = message;
                }
            }
            @Override public void send(Packet<?> packet, io.netty.channel.ChannelFutureListener listener) { send(packet); }
            @Override public void send(Packet<?> packet, io.netty.channel.ChannelFutureListener listener, boolean flush) { send(packet); }
        };
        p.connection = new ServerGamePacketListenerImpl(server,c,p,CommonListenerCookie.createInitial(profile,false)) {
            @Override public boolean hasClientLoaded() { return true; }
        };
        p.setPos(x,101,z); p.setYRot(0); p.setXRot(0); p.setInvulnerableTime(0);
        p.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        server.getPlayerList().getPlayers().add(p); server.getPlayerList().getPlayersByUUID().put(p.getUUID(),p);
        server.overworld().addNewPlayer(p);
        return p;
    }
    static Mob active(ServerLevel level) {
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Mob mob && mob.isAlive() && AmplifiedItems.SUMMONS.controlled(mob)) return mob;
        }
        throw new AssertionError("No active summon");
    }
    static Projectile projectile(ServerLevel level) {
        for (Entity e : level.getAllEntities()) if (e instanceof Projectile p && e.entityTags().contains("cw_projectile") && !e.isRemoved()) return p;
        throw new AssertionError("No ability projectile");
    }
    static void collision(Projectile p, HitResult hit) throws Exception {
        var method = Projectile.class.getDeclaredMethod("onHit",HitResult.class);
        method.setAccessible(true); method.invoke(p,hit);
    }
    static void pressF(ServerPlayer player) {
        player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
    }
    private static final java.util.Map<UUID, Integer> TEST_ABILITY = new java.util.HashMap<>();
    static void useItem(ServerPlayer player) {
        try {
            player.level().getServer().getCommands().getDispatcher().execute(
                "ability " + TEST_ABILITY.getOrDefault(player.getUUID(), 1), player.createCommandSourceStack());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) { throw new AssertionError(e); }
    }
    static void chooseAbility(ServerPlayer player, int slot) {
        TEST_ABILITY.put(player.getUUID(), slot == 4 ? 2 : 1);
    }
    static void run(MinecraftServer server) throws Exception {
        var level = server.overworld();
        check(net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("amplified_items").orElseThrow().getMetadata().getName().equals("Amplified Items"),"Published mod name is Amplified Items");
        var commands=server.getCommands().getDispatcher().getRoot();
        check(commands.getChild("amplifieditems")!=null,"Amplified Items command is registered");

        level.getGameRules().set(GameRules.PVP,true,server);
        server.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
        level.getChunk(0,0);
        for (int x=-6;x<=12;x++) for(int z=-6;z<=14;z++) level.setBlockAndUpdate(new BlockPos(x,100,z),Blocks.STONE.defaultBlockState());
        ServerPlayer owner=player(server,"TestOwner",0,0), victim=player(server,"TestTarget",0,8);
        var staff=WeaponItems.create(AmplifiedItems.CONFIG.weapons.getFirst());
        var bow=WeaponItems.create(AmplifiedItems.CONFIG.weapons.get(1));
        check(staff.get(DataComponents.ITEM_MODEL).toString().equals("amplified_items:kings_decree"),"Vanilla item_model component");
        check(staff.has(DataComponents.UNBREAKABLE),"Staff does not break and strand its mob");
        owner.setItemInHand(InteractionHand.MAIN_HAND,staff);
        var cow=EntityTypes.COW.create(level,EntitySpawnReason.COMMAND); cow.setPos(2,101,0); cow.setHealth(7); cow.setCustomName(Component.literal("Ledger Cow")); level.addFreshEntity(cow);
        check(AmplifiedItems.SUMMONS.use(owner,staff,cow),"Capture passive mob");
        check(cow.isRemoved() && AmplifiedItems.SUMMONS.status(staff).equals("STORED"),"Capture removes original and commits storage");
        check(AmplifiedItems.SUMMONS.use(owner,staff.copy(),null),"Copied staff shares the authoritative record");
        Mob summon=active(level);
        check(summon.getHealth()==7 && summon.getName().getString().equals("Ledger Cow"),"Summon preserves health and name");
        summon.setPos(0,101,6.5); AmplifiedItems.tick=20; AmplifiedItems.SUMMONS.tick();
        check(victim.getHealth()==16,"Captured passive mob attacks commanded enemy");
        victim.setHealth(20); victim.damageCooldownTime=0;
        summon.setHealth(3);
        check(AmplifiedItems.SUMMONS.use(owner,staff,null),"Manual recall");
        check(AmplifiedItems.SUMMONS.use(owner,staff,null),"Resummon survivor");
        summon=active(level);
        check(summon.getHealth()==3,"Recall cannot heal captured mob");
        summon.hurtServer(level,level.damageSources().genericKill(),10000);
        check(AmplifiedItems.SUMMONS.status(staff).equals("DEAD"),"Death consumes the stored mob");
        AmplifiedItems.SUMMONS = new Summons(server);
        check(AmplifiedItems.SUMMONS.status(staff).equals("DEAD"),"Death persists when ledger is reopened");
        var zombie=EntityTypes.ZOMBIE.create(level,EntitySpawnReason.COMMAND); zombie.setPos(2,101,0); level.addFreshEntity(zombie);
        check(AmplifiedItems.SUMMONS.use(owner,staff,zombie),"Dead slot accepts a replacement mob");
        AmplifiedItems.SUMMONS = new Summons(server);
        check(AmplifiedItems.SUMMONS.status(staff).equals("STORED"),"Captured mob persists when ledger is reopened");
        check(Abilities.REGISTRY.get("execution").use(owner,staff,null),"Execution ray finds a player");
        check(Math.abs(Abilities.modifyDamage(victim,level.damageSources().playerAttack(owner),10)-13)<0.01,"Execution increases incoming damage by 30 percent");
        victim.damageCooldownTime=0; victim.setHealth(20);
        victim.hurtServer(level,level.damageSources().playerAttack(owner),2);
        check(Math.abs(victim.getHealth()-17.4)<0.01,"Damage mixin applies Execution exactly once (health="+victim.getHealth()+")");
        AmplifiedItems.tick+=601; Abilities.tick(server);
        check(Abilities.modifyDamage(victim,level.damageSources().playerAttack(owner),10)==10,"Execution expires after 30 seconds");
        victim.damageCooldownTime=0; victim.setHealth(20);
        check(Abilities.REGISTRY.get("venom_shot").use(owner,bow,null),"Venom shot creates a real vanilla arrow");
        collision(projectile(level),new EntityHitResult(victim,victim.getBoundingBox().getCenter()));
        var poison=victim.getEffect(net.minecraft.world.effect.MobEffects.POISON);
        check(poison!=null && poison.getAmplifier()==1 && poison.getDuration()==200,"Collision applies Poison II for 10 seconds");
        check(Math.abs(victim.getHealth()-14)<0.01,"Venom direct hit deals 6 damage once");
        check(Abilities.REGISTRY.get("grapple_hook").use(owner,bow,null),"Grapple creates a bolt");
        collision(projectile(level),new EntityHitResult(victim,victim.getBoundingBox().getCenter()));
        check(victim.getDeltaMovement().z<0,"Grapple pulls target toward shooter");
        level.getGameRules().set(GameRules.PVP,false,server);
        check(!Combat.canAffect(owner,victim),"Abilities respect the PvP gamerule");
        level.getGameRules().set(GameRules.PVP,true,server);
        var team=server.getScoreboard().addPlayerTeam("cw_allies");
        server.getScoreboard().addPlayerToTeam(owner.getScoreboardName(),team); server.getScoreboard().addPlayerToTeam(victim.getScoreboardName(),team);
        check(!Combat.canAffect(owner,victim),"Abilities respect scoreboard alliances");
        server.getScoreboard().removePlayerFromTeam(victim.getScoreboardName(),team);
        player(server,"TestGroupA",4,0); player(server,"TestGroupB",5,0);
        AmplifiedItems.tick=1000; Abilities.tick(server);
        check(Math.abs(Abilities.modifyDamage(owner,level.damageSources().playerAttack(victim),10)-9)<0.01,"Ruler's Authority reduces damage by 10 percent in a group");
        owner.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
        AmplifiedItems.tick=1010; Abilities.tick(server);
        check(Abilities.modifyDamage(owner,level.damageSources().playerAttack(victim),10)==10,"Authority stops when staff is no longer held");
        owner.setItemInHand(InteractionHand.MAIN_HAND,bow); AmplifiedItems.tick=2000;
        owner.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TORCH));
        pressF(owner);
        check(owner.getMainHandItem().is(Items.TORCH) && owner.getOffhandItem() == bow
            && owner.containerMenu == owner.inventoryMenu, "F swaps items and never opens an ability menu");
        pressF(owner);
        chooseAbility(owner, 4);
        int bossPacketsBefore = bossPackets;
        useItem(owner);
        check(AmplifiedItems.COOLDOWNS.remaining(owner.getUUID(), "grapple_hook", 2000) == 100,
            "/ability 2 directly casts Grapple Hook for an ordinary player");
        check(bossPackets > bossPacketsBefore, "Command casts send the weapon cooldown bar");
        Projectile cast = projectile(level);
        useItem(owner);
        cast.discard();
        AmplifiedItems.tick += 4;
        chooseAbility(owner, 2);
        useItem(owner);
        check(AmplifiedItems.COOLDOWNS.remaining(owner.getUUID(), "venom_shot", AmplifiedItems.tick) == 0
            && AmplifiedItems.COOLDOWNS.remaining(owner.getUUID(), "grapple_hook", AmplifiedItems.tick) == 96,
            "Changing the command number cannot bypass the shared cooldown");
        AmplifiedItems.tick += 96;
        useItem(owner);
        check(AmplifiedItems.COOLDOWNS.remaining(owner.getUUID(), "venom_shot", AmplifiedItems.tick) == 160,
            "/ability 1 directly casts Venom Shot after the cooldown");
        projectile(level).discard();
        var use = net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.invoker();
        check(use.interact(owner, level, InteractionHand.MAIN_HAND) == net.minecraft.world.InteractionResult.PASS,
            "Right-click retains normal vanilla item use");
        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
        pressF(owner);
        check(owner.getMainHandItem().is(Items.TORCH) && owner.getOffhandItem().is(Items.STONE), "F still swaps ordinary items");
        owner.setItemInHand(InteractionHand.MAIN_HAND, bow);
        var original = AmplifiedItems.CONFIG.weapons.get(1);
        AmplifiedItems.CONFIG.weapons.set(1, new WeaponDefinition(original.id(), original.name(), original.baseItem(), original.model(),
            original.primary(), original.secondary(), original.passive(), original.primaryCooldownSeconds(), original.secondaryCooldownSeconds(),
            original.lore(), false, original.ritual()));
        try {
            check(WeaponItems.definition(bow) == null, "A disabled existing weapon has no active definition");
            pressF(owner);
            check(owner.getMainHandItem().is(Items.STONE) && owner.getOffhandItem() == bow, "F swaps a disabled weapon normally");
            check(server.getCommands().getDispatcher().execute("amplifieditems altar recipe venombolt", owner.createCommandSourceStack()) == 0,
                "Disabled weapon recipes are unavailable");
        } finally {
            AmplifiedItems.CONFIG.weapons.set(1, original);
            owner.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        }

        for (String ability : List.of("grapple_hook", "web_grapple")) {
            Vec3 eye = owner.getEyePosition();
            owner.setDeltaMovement(Vec3.ZERO);
            check(Abilities.REGISTRY.get(ability).use(owner, bow, null), "Launch " + ability + " for range check");
            collision(projectile(level), new BlockHitResult(eye.add(0, 0, 16.01), Direction.NORTH, BlockPos.containing(eye.add(0, 0, 16.01)), false));
            check(owner.getDeltaMovement().lengthSqr() == 0, ability + " rejects impacts past 16 blocks");
            check(Abilities.REGISTRY.get(ability).use(owner, bow, null), "Launch " + ability + " within range");
            collision(projectile(level), new BlockHitResult(eye.add(0, 0, 15.9), Direction.NORTH, BlockPos.containing(eye.add(0, 0, 15.9)), false));
            check(owner.getDeltaMovement().z > 0, ability + " pulls at 15.9 blocks");
        }
        checkNewWeapons(server, owner, victim);
        checkExpansion(server);
        checkAltars(server, owner);
        checkReaperProgress(server);
        checkStaffControls(server);
        checkStaffUnload(server, owner);
        var dir=java.nio.file.Files.createTempDirectory("cw-pack-check-");
        var settings=AmplifiedItems.CONFIG; boolean oldEnabled=settings.resourcePackHostEnabled; int oldPort=settings.resourcePackPort; String oldBind=settings.resourcePackBindAddress;
        settings.resourcePackHostEnabled=true; settings.resourcePackPort=8199; settings.resourcePackBindAddress="127.0.0.1";
        try(var host=new PackHost()) {
            host.start(dir);
            byte[] downloaded=new java.net.URI("http://127.0.0.1:8199/amplified-items.zip").toURL().openStream().readAllBytes();
            check(downloaded[0]=='P' && downloaded[1]=='K',"Built-in HTTP host serves a ZIP");
            check(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(downloaded)).equals(host.sha1()),"Hosted bytes match the advertised SHA-1");
            var connection=(java.net.HttpURLConnection)new java.net.URI("http://127.0.0.1:8199/amplified-items.zip/private").toURL().openConnection();
            check(connection.getResponseCode()==404,"HTTP host exposes only the exact pack path"); connection.disconnect();
        } finally {
            settings.resourcePackHostEnabled=oldEnabled; settings.resourcePackPort=oldPort; settings.resourcePackBindAddress=oldBind;
            try(var paths=java.nio.file.Files.walk(dir)) { for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(path); }
        }
        for(var p : List.copyOf(server.getPlayerList().getPlayers())) {
            level.removePlayerImmediately(p,Entity.RemovalReason.DISCARDED);
            server.getPlayerList().getPlayers().remove(p); server.getPlayerList().getPlayersByUUID().remove(p.getUUID());
        }
    }

    private static ItemStack weapon(String id) {
        return WeaponItems.create(AmplifiedItems.CONFIG.weapons.stream().filter(w -> w.id().equals(id)).findFirst().orElseThrow());
    }

    private static void checkExpansion(MinecraftServer server) throws Exception {
        var level = server.overworld();
        var owner = player(server, "BladeTester", 8.5, 3.5);
        var victim = player(server, "BladeTarget", 8.5, 6.5);
        var night = weapon("nightblade");
        var solar = weapon("solarblade");
        var wind = weapon("windwalker");
        var spider = weapon("spiders_bane");
        var blood = weapon("bloodfang");
        var oldBlood = blood.transmuteCopy(Items.NETHERITE_SWORD);
        oldBlood.set(DataComponents.CUSTOM_NAME, Component.literal("Named BloodFang"));
        var sword = new ItemStack(Items.NETHERITE_SWORD);
        owner.setItemInHand(InteractionHand.MAIN_HAND, oldBlood);
        owner.setItemInHand(InteractionHand.OFF_HAND, sword);
        Abilities.tick(server);
        var upgraded = owner.getMainHandItem();
        check(blood.is(Items.NETHERITE_AXE) && upgraded.is(Items.NETHERITE_AXE)
            && WeaponItems.definition(blood).equals(WeaponItems.definition(upgraded))
            && WeaponItems.instance(blood).equals(WeaponItems.instance(upgraded))
            && "Named BloodFang".equals(upgraded.getHoverName().getString())
            && owner.getOffhandItem() == sword, "Holding a legacy BloodFang converts it to an axe and preserves its identity and name");
        owner.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        var clock = level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD);
        level.clockManager().setTotalTicks(clock, 14000);
        AmplifiedItems.tick = 3000;
        owner.setItemInHand(InteractionHand.MAIN_HAND, night);
        Abilities.tick(server);
        check(owner.getEffect(MobEffects.SPEED).getAmplifier() == 1 && owner.hasEffect(MobEffects.STRENGTH)
            && owner.getEffect(MobEffects.NIGHT_VISION).getDuration() > 200, "NightBlade grants nighttime buffs and steady Night Vision");
        check(Abilities.REGISTRY.get("phantom_phase").use(owner, night, null) && PlayerAbilities.isHidden(owner)
            && owner.getAbilities().mayfly && owner.getAbilities().flying, "Phantom Phase hides the player and starts flight");
        var playerHit = level.damageSources().playerAttack(victim);
        check(!owner.hurtServer(level, playerHit, 4) && owner.getHealth() == 20, "Phantom Phase blocks damage");
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        owner.saveWithoutId(output);
        var saved = output.buildResult().getCompoundOrEmpty("abilities");
        check(!saved.getBooleanOr("mayfly", false) && !saved.getBooleanOr("flying", false), "Phantom flight does not persist in player saves");
        AmplifiedItems.tick += 100;
        Abilities.tick(server);
        check(!PlayerAbilities.isHidden(owner) && !owner.getAbilities().mayfly && !owner.getAbilities().flying
            && owner.hurtServer(level, playerHit, 4), "Phantom Phase restores visibility, flight, and damage after five seconds");
        check(Abilities.REGISTRY.get("hollow_moon").use(owner, night, null)
            && owner.getEffect(MobEffects.SPEED).getAmplifier() == 2 && owner.getEffect(MobEffects.STRENGTH).getAmplifier() == 1
            && owner.getEffect(MobEffects.SPEED).getDuration() == 300, "HollowMoon gives the requested effects for 15 seconds");

        level.clockManager().setTotalTicks(clock, 6000);
        Abilities.tick(server);
        check(owner.hasEffect(MobEffects.WEAKNESS) && owner.hasEffect(MobEffects.SLOWNESS), "NightBlade applies daytime penalties");
        owner.removeAllEffects();
        owner.setItemInHand(InteractionHand.MAIN_HAND, solar);
        Abilities.tick(server);
        check(owner.getEffect(MobEffects.SPEED).getAmplifier() == 0 && owner.hasEffect(MobEffects.STRENGTH), "SolarBlade grants its daytime buffs");
        level.clockManager().setTotalTicks(clock, 14000);
        Abilities.tick(server);
        check(owner.hasEffect(MobEffects.WEAKNESS) && owner.hasEffect(MobEffects.SLOWNESS)
            && owner.hasEffect(MobEffects.RESISTANCE), "SolarBlade grants its nighttime effects");
        owner.removeAllEffects();
        victim.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 600));
        victim.damageCooldownTime = 0;
        check(Abilities.REGISTRY.get("heatwave").use(owner, solar, null) && victim.getHealth() == 17,
            "Heatwave hits through Fire Resistance");
        AmplifiedItems.tick += 20;
        victim.damageCooldownTime = 0;
        Abilities.tick(server);
        check(victim.getHealth() == 16, "Heatwave burn keeps hurting a fire-resistant target");
        victim.removeAllEffects();
        victim.setHealth(20);
        victim.damageCooldownTime = 0;
        victim.setRemainingFireTicks(0);
        check(Abilities.REGISTRY.get("sunflare").use(owner, solar, null) && victim.getHealth() == 16
            && victim.isOnFire() && victim.getDeltaMovement().z > 0, "Sunflare deals two hearts, burns, and pushes back");

        owner.removeAllEffects();
        owner.setItemInHand(InteractionHand.MAIN_HAND, wind);
        AmplifiedItems.tick += 30;
        useItem(owner);
        check(AmplifiedItems.COOLDOWNS.remaining(owner.getUUID(), "windburst", AmplifiedItems.tick) > 0
            && victim.getDeltaMovement().z >= 1.4, "Windburst defaults to right-click on WindWalker");
        AmplifiedItems.tick += 200;
        chooseAbility(owner, 4);
        useItem(owner);
        check(owner.getEffect(MobEffects.SPEED).getAmplifier() == 1 && owner.hasEffect(MobEffects.JUMP_BOOST), "Right-click casts Updraft after choosing it");
        check(owner.getProjectile(wind).is(Items.ARROW) && owner.getInventory().countItem(Items.ARROW) == 0, "WindWalker supplies virtual arrows");
        var bow = (BowItem) wind.getItem();
        int useDuration = wind.getUseDuration(owner);
        check(bow.releaseUsing(wind, level, owner, useDuration - 2) && owner.hasEffect(MobEffects.SLOW_FALLING), "A right-click tap toggles Slow Falling");
        check(bow.releaseUsing(wind, level, owner, useDuration - 20), "WindWalker fires a drawn shot without inventory arrows");
        boolean foundWindArrow = false;
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof AbstractArrow arrow && arrow.getOwner() == owner
                    && arrow.pickup == AbstractArrow.Pickup.DISALLOWED) foundWindArrow = true;
        }
        check(foundWindArrow && owner.getInventory().countItem(Items.ARROW) == 0, "WindWalker arrows cannot be collected as items");

        owner.removeAllEffects();
        owner.setItemInHand(InteractionHand.MAIN_HAND, spider);
        BlockPos web = new BlockPos(8, 101, 3);
        level.setBlockAndUpdate(web, Blocks.COBWEB.defaultBlockState());
        owner.setChunkTrackingView(ChunkTrackingView.of(owner.chunkPosition(), 2));
        owner.connection.chunkSender.dropChunk(owner, owner.chunkPosition());
        BLOCK_UPDATES.clear();
        Abilities.tick(server);
        check(level.getBlockState(web).is(Blocks.COBWEB) && BLOCK_UPDATES.stream().anyMatch(update -> update.getPos().equals(web)
            && update.getBlockState().isAir()), "Spider's Bane masks nearby webs only for the holder");
        var stuckSpeed = Entity.class.getDeclaredField("stuckSpeedMultiplier");
        stuckSpeed.setAccessible(true);
        stuckSpeed.set(owner, Vec3.ZERO);
        owner.makeStuckInBlock(Blocks.COBWEB.defaultBlockState(), new Vec3(.25, .05, .25));
        check(stuckSpeed.get(owner).equals(Vec3.ZERO), "Spider's Bane prevents server-side web slowdown");

        var attackCharge = LivingEntity.class.getDeclaredField("attackStrengthTicker");
        attackCharge.setAccessible(true);
        for (int hit = 1; hit <= 5; hit++) {
            owner.setOnGround(false);
            owner.fallDistance = 1;
            victim.setHealth(20);
            victim.damageCooldownTime = 0;
            attackCharge.setInt(owner, 40);
            owner.attack(victim);
            check(victim.hasEffect(MobEffects.POISON) == (hit == 5), "Spider crit combo hit " + hit);
            AmplifiedItems.tick += 15;
        }
        var poison = victim.getEffect(MobEffects.POISON);
        check(poison.getAmplifier() == 1 && poison.getDuration() == 1200
            && AmplifiedItems.COOLDOWNS.remaining(owner.getUUID(), "crit_combo", AmplifiedItems.tick) == 585,
            "Spider combo applies one minute of Poison II and a 30-second cooldown");
        check(Abilities.REGISTRY.get("web_grapple").use(owner, spider, null), "Web Grapple launches");
        Snowball webShot = null;
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Snowball shot && shot.getOwner() == owner) webShot = shot;
        }
        check(webShot != null && webShot.getItem().is(Items.COBWEB), "Web Grapple uses a web projectile");
        collision(webShot, new EntityHitResult(victim, victim.getBoundingBox().getCenter()));
        check(victim.getDeltaMovement().z < 0, "Web Grapple pulls a hit enemy");

        owner.setItemInHand(InteractionHand.MAIN_HAND, blood);
        BLOCK_UPDATES.clear();
        Abilities.tick(server);
        check(BLOCK_UPDATES.stream().anyMatch(update -> update.getPos().equals(web) && update.getBlockState().is(Blocks.COBWEB)),
            "Holstering Spider's Bane restores the web on the client");
        level.setBlockAndUpdate(web, Blocks.AIR.defaultBlockState());
        owner.fallDistance = 0;
        owner.setOnGround(true);
        victim.removeAllEffects();
        victim.setHealth(20);
        victim.damageCooldownTime = 0;
        check(Abilities.REGISTRY.get("blood_rush").use(owner, blood, null) && owner.getEffect(MobEffects.SPEED).getAmplifier() == 1
            && owner.hasEffect(MobEffects.STRENGTH), "Blood Rush grants speed and strength");
        check(Abilities.REGISTRY.get("crimson_cleave").use(owner, blood, null) && victim.getHealth() == 14, "Crimson Cleave hits in front for three hearts");
        owner.removeAllEffects();
        for (int spam = 0; spam < 8; spam++) {
            victim.setHealth(20);
            victim.damageCooldownTime = 0;
            attackCharge.setInt(owner, 0);
            owner.attack(victim);
        }
        check(Abilities.modifyDamage(victim, level.damageSources().playerAttack(owner), 10) == 10,
            "Spam attacks do not fill BloodFang rage");
        for (int hit = 0; hit < 5; hit++) {
            victim.setHealth(20);
            victim.damageCooldownTime = 0;
            attackCharge.setInt(owner, 40);
            owner.attack(victim);
        }
        var damage = level.damageSources().playerAttack(owner);
        check(Math.abs(Abilities.modifyDamage(victim, damage, 10) - 12) < .001, "Bloodlust boosts damage by 20 percent after five hits");
        AmplifiedItems.tick += 120;
        check(Abilities.modifyDamage(victim, damage, 10) == 10, "Bloodlust expires after six seconds");
    }

    @SuppressWarnings("unchecked")
    private static void checkNewWeapons(MinecraftServer server, ServerPlayer owner, ServerPlayer victim) throws Exception {
        var level = owner.level();
        var reaper = weapon("reapers_edge");
        var sun = weapon("sunpiercer");
        var leviathan = weapon("leviathans_wrath");
        owner.setItemInHand(InteractionHand.MAIN_HAND, reaper);
        owner.setHealth(12);
        victim.setPos(0, 101, 4);
        victim.setHealth(20);
        victim.damageCooldownTime = 0;
        AmplifiedItems.tick = 2010;
        Abilities.tick(server);
        check(owner.getMaxHealth() == 20, "Reaper starts without unearned bonus hearts");
        check(Abilities.REGISTRY.get("soul_stealer").use(owner, reaper, null), "Soul Stealer hits the aimed enemy");
        check(victim.getHealth() == 16 && owner.getHealth() == 16, "Soul Stealer transfers two hearts");
        for (int kill = 0; kill < 5; kill++) {
            var defeated = player(server, "ReaperProgress" + kill, 4, 4);
            defeated.setHealth(1);
            defeated.hurtServer(level, level.damageSources().playerAttack(owner), 100);
        }
        check(owner.getMaxHealth() == 30, "Five PvP kills earn five Reaper hearts");
        owner.setHealth(30);
        AmplifiedItems.tick += 10;
        Abilities.tick(server);
        check(owner.hasEffect(MobEffects.SPEED) && owner.getEffect(MobEffects.SPEED).getAmplifier() == 0,
            "Reaper grants Speed I at full health");

        owner.getInventory().setItem(10, reaper);
        owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        Abilities.tick(server);
        check(owner.getMaxHealth() == 30 && !owner.hasEffect(MobEffects.SPEED),
            "Reaper hearts remain in inventory while the held Speed effect ends");
        owner.getInventory().setItem(10, ItemStack.EMPTY);
        owner.setItemInHand(InteractionHand.MAIN_HAND, reaper);
        Abilities.tick(server);
        var chunkMap = (ChunkMapAccess) level.getChunkSource().chunkMap;
        Object tracker = chunkMap.amplifiedItems$entities().get(owner.getId());
        var seenByField = tracker.getClass().getDeclaredField("seenBy");
        seenByField.setAccessible(true);
        var viewers = (Set<ServerPlayerConnection>) seenByField.get(tracker);
        viewers.add(victim.connection);
        check(Abilities.REGISTRY.get("shadow_step").use(owner, reaper, null), "Shadow Step activates");
        check(PlayerAbilities.isHidden(owner) && !viewers.contains(victim.connection), "Shadow Step removes the player from client tracking");
        viewers.add(victim.connection);
        ((TrackedPlayerRefresh) tracker).amplifiedItems$refresh(List.of(victim));
        check(!viewers.contains(victim.connection), "An observer cannot start tracking a hidden player");
        victim.setChunkTrackingView(ChunkTrackingView.of(owner.chunkPosition(), 2));
        victim.connection.chunkSender.dropChunk(victim, owner.chunkPosition());
        AmplifiedItems.tick += 130;
        Abilities.tick(server);
        check(!PlayerAbilities.isHidden(owner) && viewers.contains(victim.connection), "Shadow Step expires and restores client tracking");
        owner.removeEffect(MobEffects.INVISIBILITY);
        owner.setItemInHand(InteractionHand.MAIN_HAND, sun);
        AmplifiedItems.tick += 10;
        Abilities.tick(server);
        check(owner.getMaxHealth() == 20 && owner.getHealth() == 20, "Holstering Reaper removes its max health bonus");

        victim.setHealth(20);
        victim.damageCooldownTime = 0;
        check(Abilities.REGISTRY.get("flaming_burst").use(owner, sun, null), "Flaming Burst fires");
        Projectile flame = projectile(level);
        check(flame.isOnFire(), "Flaming Burst arrow burns in flight");
        collision(flame, new EntityHitResult(victim, victim.getBoundingBox().getCenter()));
        check(victim.getHealth() == 16 && victim.getRemainingFireTicks() == 60, "Flaming Burst deals two hearts and ignites for three seconds");
        victim.setRemainingFireTicks(0);
        victim.setHealth(20);
        victim.damageCooldownTime = 0;
        check(Abilities.REGISTRY.get("solar_flare").use(owner, sun, null), "Solar Flare fires");
        collision(projectile(level), new EntityHitResult(victim, victim.getBoundingBox().getCenter()));
        check(victim.getHealth() == 16 && victim.getEffect(MobEffects.BLINDNESS).getDuration() == 100,
            "Solar Flare damages and blinds for five seconds");
        check(level.getBlockState(new BlockPos(0, 100, 4)).is(Blocks.STONE), "Solar Flare leaves terrain intact");

        sun.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(Items.ARROW))));
        var callback = net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.invoker();
        chooseAbility(owner, 6);
        check(callback.interact(owner, level, InteractionHand.MAIN_HAND) == net.minecraft.world.InteractionResult.PASS,
            "SunPiercer in normal-use mode lets vanilla fire a loaded arrow");
        ((CrossbowItem) sun.getItem()).performShooting(level, owner, InteractionHand.MAIN_HAND, sun, 3.15f, 0, null);
        boolean burningBolt = false;
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof AbstractArrow arrow && arrow.getOwner() == owner && arrow.isOnFire()) burningBolt = true;
        }
        check(burningBolt, "SunPiercer ignites ordinary crossbow ammunition");

        owner.setItemInHand(InteractionHand.MAIN_HAND, leviathan);
        victim.setHealth(20);
        victim.damageCooldownTime = 0;
        check(Abilities.REGISTRY.get("tide").use(owner, leviathan, null), "Tide launches a moving water wave");
        for (int tick = 0; tick < 18; tick++) {
            victim.damageCooldownTime = 0;
            AmplifiedItems.tick++;
            Abilities.tick(server);
        }
        check(victim.getHealth() == 16, "Tide deals two hearts of drowning damage once");

        var touchingWater = Entity.class.getDeclaredField("wasTouchingWater");
        touchingWater.setAccessible(true);
        touchingWater.setBoolean(owner, true);
        AmplifiedItems.tick = 2400;
        Abilities.tick(server);
        check(owner.hasEffect(MobEffects.WATER_BREATHING) && owner.hasEffect(MobEffects.DOLPHINS_GRACE),
            "Leviathan grants both effects in water");
        touchingWater.setBoolean(owner, false);
        chooseAbility(owner, 4);
        owner.setDeltaMovement(Vec3.ZERO);
        useItem(owner);
        check(!owner.isInWater() && owner.isAutoSpinAttack() && owner.getDeltaMovement().length() > 1,
            "Riptide launches the player on dry land");
        double launchY = owner.getY();
        owner.setDeltaMovement(Vec3.ZERO);
        AmplifiedItems.tick += 4;
        useItem(owner);
        check(owner.getDeltaMovement().lengthSqr() == 0 && owner.getY() == launchY,
            "Trying Riptide before the next dash is ready preserves position and velocity");
        owner.setDeltaMovement(Vec3.ZERO);
        AmplifiedItems.tick += 16;
        useItem(owner);
        check(owner.getDeltaMovement().length() > 1, "Riptide allows another dash during its window");
        owner.setDeltaMovement(Vec3.ZERO);
        AmplifiedItems.tick += 180;
        useItem(owner);
        check(owner.getDeltaMovement().lengthSqr() == 0, "Riptide window closes after ten seconds");
        chooseAbility(owner, 2);
        useItem(owner);
        check(AmplifiedItems.COOLDOWNS.remaining(owner.getUUID(), "tide", AmplifiedItems.tick) == 0,
            "Switching to Tide does not bypass the Riptide cooldown");
    }

    private static void checkReaperProgress(MinecraftServer server) {
        var level = server.overworld();
        var owner = player(server, "ReaperInventory", 3.5, 10.5);
        var reaper = weapon("reapers_edge");
        owner.getInventory().setItem(10, reaper);
        for (int kill = 0; kill < 7; kill++) {
            var victim = player(server, "ReaperCapVictim" + kill, 4, 10);
            victim.setHealth(1);
            victim.hurtServer(level, level.damageSources().playerAttack(owner), 100);
        }
        Abilities.tick(server);
        check(owner.getMaxHealth() == 30 && !owner.hasEffect(MobEffects.SPEED),
            "Reaper hearts cap at five and apply from inventory without held effects");
        var saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        owner.saveWithoutId(saved);
        check(saved.buildResult().getCompoundOrEmpty("fabric:attachments").getIntOr("amplified_items:reaper_hearts", 0) == 5,
            "Earned Reaper hearts are saved in persistent player data");
        owner.hurtServer(level, level.damageSources().genericKill(), 10000);
        var after = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        owner.saveWithoutId(after);
        check(after.buildResult().getCompoundOrEmpty("fabric:attachments").getIntOr("amplified_items:reaper_hearts", 0) == 4,
            "Dying deducts exactly one earned heart");
    }

    private static void checkStaffControls(MinecraftServer server) {
        var level = server.overworld();
        level.setChunkForced(2, 0, true);
        level.getChunkSource().tick(() -> true, false);
        level.waitForEntities(new ChunkPos(2, 0), 0);
        for (int x = 38; x <= 50; x++) for (int z = 4; z <= 14; z++) {
            level.setBlockAndUpdate(new BlockPos(x, 100, z), Blocks.STONE.defaultBlockState());
        }
        var owner = player(server, "StaffControlCheck", 40.5, 10.5);
        var victim = player(server, "StaffExecutionCheck", 40.5, 13.5);
        var staff = weapon("kings_decree");
        owner.setItemInHand(InteractionHand.MAIN_HAND, staff);
        owner.setYRot(180);
        var cow = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
        cow.setPos(41.5, 101, 10.5);
        level.addFreshEntity(cow);
        check(AmplifiedItems.SUMMONS.use(owner, staff, cow) && AmplifiedItems.SUMMONS.use(owner, staff, null),
            "A staff releases a captured mob without an enemy target");
        Mob summoned = active(level);
        double height = summoned.getY();
        AmplifiedItems.tick += 20;
        AmplifiedItems.SUMMONS.tick();
        summoned.tick();
        AmplifiedItems.tick++;
        AmplifiedItems.SUMMONS.tick();
        summoned.tick();
        check(summoned.getY() < height, "A released idle mob falls normally instead of freezing in the air");
        var mount = EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND);
        mount.setPos(42.5, 101, 10.5);
        level.addFreshEntity(mount);
        check(!summoned.startRiding(mount, true, true) && !mount.startRiding(summoned, true, true),
            "Summons cannot ride other entities or accept riders");
        Vec3 beforePush = owner.getDeltaMovement();
        summoned.push(owner);
        check(owner.getDeltaMovement().equals(beforePush), "A summon cannot shove its owner");
        for (int z = 4; z <= 14; z++) level.setBlockAndUpdate(new BlockPos(44, 101, z), Blocks.STONE.defaultBlockState());
        owner.setPos(48.5, 101, 10.5);
        double distance = summoned.distanceToSqr(owner);
        AmplifiedItems.tick++;
        AmplifiedItems.SUMMONS.tick();
        double highest = summoned.getY();
        for (int movement = 0; movement < 80; movement++) {
            summoned.tick();
            AmplifiedItems.tick++;
            AmplifiedItems.SUMMONS.tick();
            highest = Math.max(highest, summoned.getY());
        }
        check(highest > 101.4 && summoned.getX() > 44, "A released mob navigates up and over a one-block obstacle");
        check(summoned.distanceToSqr(owner) < distance, "A released idle mob follows its owner");
        AmplifiedItems.SUMMONS.recallOwner(owner.getUUID());
        owner.setPos(40.5, 101, 10.5);
        owner.setYRot(0);
        owner.setXRot(0);
        chooseAbility(owner, 4);
        useItem(owner);
        check(AmplifiedItems.COOLDOWNS.remaining(owner.getUUID(), "execution", AmplifiedItems.tick) == 900
            && AmplifiedItems.SUMMONS.status(staff).equals("STORED"),
            "Selecting Execution takes effect on the next click without releasing the stored mob");
        chooseAbility(owner, 2);
        useItem(owner);
        check(AmplifiedItems.SUMMONS.status(staff).equals("STORED"),
            "Switching back to Ruler's Command does not clear Execution's cooldown");
        AmplifiedItems.tick += 900;
        var beeStaff = weapon("kings_decree");
        owner.setItemInHand(InteractionHand.MAIN_HAND, beeStaff);
        owner.setYRot(180);
        var bee = EntityTypes.BEE.create(level, EntitySpawnReason.COMMAND);
        bee.setPos(41.5, 101, 10.5);
        level.addFreshEntity(bee);
        check(AmplifiedItems.SUMMONS.use(owner, beeStaff, bee) && AmplifiedItems.SUMMONS.use(owner, beeStaff, null),
            "A flying mob can be stored and released");
        Mob flying = active(level);
        owner.setPos(48.5, 104, 10.5);
        double flyingDistance = flying.distanceToSqr(owner);
        for (int step = 0; step < 100; step++) {
            AmplifiedItems.tick++;
            AmplifiedItems.SUMMONS.tick();
            flying.tick();
        }
        check(flying.distanceToSqr(owner) < flyingDistance && flying.getY() < 110,
            "Flying summons follow through native movement without launching away");
        AmplifiedItems.SUMMONS.recallOwner(owner.getUUID());
    }
    static void checkAltars(MinecraftServer server, ServerPlayer owner) throws Exception {
        ServerLevel level = owner.level();
        BlockPos first = new BlockPos(2, 101, 5);
        level.setChunkForced(0, 0, true);
        level.getChunkSource().tick(() -> true, false);
        level.waitForEntities(new ChunkPos(0, 0), 0);
        for (int x = 1; x <= 13; x++) {
            level.setBlockAndUpdate(new BlockPos(x, 101, 5), Blocks.AIR.defaultBlockState());
        }
        owner.setPos(2.5, 101, 3.5);
        var dispatcher = server.getCommands().getDispatcher();
        var source = owner.createCommandSourceStack();
        var admin = source.withPermission(PermissionSet.ALL_PERMISSIONS);
        String command = "amplifieditems altar ";
        var place = dispatcher.getRoot().getChild("amplifieditems").getChild("altar").getChild("place");
        check(!place.canUse(source), "Survival players cannot place altars");
        check(dispatcher.execute(command + "place kings_decree 2 101 5", admin) == 1, "An operator places a registered altar");
        check(level.getBlockState(first).is(Blocks.BEDROCK), "The altar core is bedrock");
        check(!PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, owner, first,
            level.getBlockState(first), null), "The altar blocks player destruction");
        List<Entity> visuals = altarDisplays(level);
        check(visuals.size() == 3 && visuals.stream().filter(Display.ItemDisplay.class::isInstance).count() == 2
            && visuals.stream().anyMatch(Display.TextDisplay.class::isInstance) && visuals.stream().noneMatch(Entity::shouldBeSaved),
            "The altar has a core model, floating weapon and recipe text rebuilt from the ledger");
        var coreDisplay = visuals.stream().filter(Display.ItemDisplay.class::isInstance)
            .map(Display.ItemDisplay.class::cast).filter(display -> display.getSlot(0).get().is(Items.BEDROCK))
            .findFirst().orElseThrow();
        check(coreDisplay.position().equals(Vec3.atCenterOf(first))
            && "amplified_items:altar_core".equals(coreDisplay.getSlot(0).get().get(DataComponents.ITEM_MODEL).toString()),
            "The custom altar model is centered on the protected bedrock");
        coreDisplay.discard();
        level.setBlockAndUpdate(first, Blocks.AIR.defaultBlockState());
        altarTick(server);
        check(level.getBlockState(first).is(Blocks.BEDROCK) && altarDisplays(level).size() == 3,
            "The loaded altar restores a missing core without duplicating its displays");
        check(dispatcher.execute(command + "place kings_decree 3 101 5", admin) == 0, "A second altar for the same weapon is refused");

        long headsBefore = java.util.stream.StreamSupport.stream(level.getAllEntities().spliterator(), false)
            .filter(entity -> entity instanceof ItemEntity item && item.getItem().is(Items.PLAYER_HEAD)).count();
        var defeated = player(server, "HeadDropTest", 3, 3.5);
        defeated.setHealth(1);
        defeated.damageCooldownTime = 0;
        defeated.hurtServer(level, level.damageSources().playerAttack(owner), 100);
        var head = java.util.stream.StreamSupport.stream(level.getAllEntities().spliterator(), false)
            .filter(ItemEntity.class::isInstance).map(ItemEntity.class::cast).map(ItemEntity::getItem)
            .filter(stack -> stack.is(Items.PLAYER_HEAD) && stack.has(DataComponents.PROFILE)
                && stack.get(DataComponents.PROFILE).name().orElse("").equals("HeadDropTest")).findFirst().orElseThrow();
        check(head.has(DataComponents.PROFILE) && head.get(DataComponents.PROFILE).name().orElse("").equals("HeadDropTest")
            && head.get(DataComponents.CUSTOM_DATA).copyTag().getBooleanOr("amplified_pvp_head", false),
            "A real player kill drops a recipe head with the victim's profile");
        var environmental = player(server, "FallDeathTest", 3, 4.5);
        environmental.setHealth(1);
        environmental.damageCooldownTime = 0;
        environmental.hurtServer(level, level.damageSources().fall(), 100);
        long headCount = java.util.stream.StreamSupport.stream(level.getAllEntities().spliterator(), false)
            .filter(entity -> entity instanceof ItemEntity item && item.getItem().is(Items.PLAYER_HEAD)).count();
        check(headCount == headsBefore + 1, "Environmental deaths do not produce PvP heads");

        var creativeKiller = player(server, "CreativeTester", 3, 3.5);
        creativeKiller.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
        var creativeVictim = player(server, "CreativeVictim", 3, 4.5);
        creativeVictim.setHealth(1);
        creativeVictim.hurtServer(level, level.damageSources().playerAttack(creativeKiller), 100);
        check(java.util.stream.StreamSupport.stream(level.getAllEntities().spliterator(), false)
            .anyMatch(entity -> entity instanceof ItemEntity drop && drop.getItem().is(Items.PLAYER_HEAD)
                && drop.getItem().has(DataComponents.PROFILE)
                && drop.getItem().get(DataComponents.PROFILE).name().orElse("").equals("CreativeVictim")),
            "A Creative attacker drops the Survival victim's profiled player head");
        ItemStack crown = weapon("kings_crown");
        ItemStack sunlight = craft(level, CraftingInput.of(3, 1, List.of(
            new ItemStack(Items.GLOWSTONE), new ItemStack(Items.BLAZE_POWDER), new ItemStack(Items.GOLD_INGOT))));
        check(crown.is(Items.NETHERITE_HELMET) && crown.getHoverName().getString().equals("King's Crown")
            && sunlight.is(Items.GLOWSTONE_DUST) && sunlight.getHoverName().getString().equals("Sunlight"),
            "The crown has netherite stats and Sunlight crafts with its custom texture");
        ItemStack poison = new ItemStack(Items.TIPPED_ARROW, 16);
        poison.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.POISON));
        var special = Map.of("amplified_items:kings_crown", crown, "amplified_items:sunlight", sunlight,
            "amplified_items:poison_arrow", poison, "amplified_items:pvp_head", head);
        owner.getInventory().clearContent();
        clickAltar(owner, first);
        check(level.getBlockState(first).is(Blocks.BEDROCK), "An empty inventory cannot consume the altar");

        int index = 0;
        for (var weapon : AmplifiedItems.CONFIG.weapons) {
            BlockPos core = first.offset(index, 0, 0);
            if (index > 0) check(dispatcher.execute(command + "place " + weapon.id() + " " + core.getX() + " 101 5", admin) == 1,
                "Placed " + weapon.name() + " altar");
            index++;
            owner.setPos(core.getX() + 0.5, 101, 3.5);
            var slots = fillInventory(owner, weapon.ritual(), special);
            check(slots.size() == weapon.ritual().ingredients().size()
                && dispatcher.execute(command + "recipe " + weapon.id(), source) == 1, weapon.name() + " recipe fits in inventory and appears in chat");

            if (weapon.id().equals("kings_decree")) {
                int slot = slots.get("amplified_items:pvp_head");
                owner.getInventory().setItem(slot, new ItemStack(Items.PLAYER_HEAD, 2));
                clickAltar(owner, core);
                check(level.getBlockState(core).is(Blocks.BEDROCK) && owner.getInventory().getItem(slot).getCount() == 2,
                    "An ordinary player head cannot pay the PvP head cost");
                owner.getInventory().setItem(slot, head.copyWithCount(2));
                int ingotSlot = slots.get("minecraft:netherite_ingot");
                owner.getInventory().setItem(ingotSlot, new ItemStack(Items.NETHERITE_INGOT, 9));
            }
            if (weapon.id().equals("venombolt")) {
                int slot = slots.get("amplified_items:poison_arrow");
                var wrong = new ItemStack(Items.TIPPED_ARROW, 16);
                wrong.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.SLOWNESS));
                owner.getInventory().setItem(slot, wrong);
                clickAltar(owner, core);
                check(level.getBlockState(core).is(Blocks.BEDROCK), "VenomBolt rejects other tipped arrows");
                owner.getInventory().setItem(slot, poison.copy());
            }
            if (weapon.id().equals("solarblade")) {
                AmplifiedItems.CONFIG.altarEventRequirements.put(weapon.id(), "sunrise");
                altarTick(server);
                clickAltar(owner, core);
                check(level.getBlockState(core).is(Blocks.BEDROCK), "A closed event gates an otherwise payable claim");
                check(dispatcher.execute(command + "event sunrise open", admin) == 1, "An admin can open the required event");
            }
            clickAltar(owner, core);
            var rewards = owner.getInventory().getNonEquipmentItems().stream()
                .filter(stack -> weapon.equals(WeaponItems.definition(stack))).toList();
            check(rewards.size() == 1 && level.getBlockState(core).isAir() && lastClaim.contains(weapon.name()),
                "Claiming " + weapon.name() + " awards one weapon, consumes the altar, and announces the winner");
            if (weapon.id().equals("kings_crown")) {
                check(rewards.getFirst().is(Items.NETHERITE_HELMET) && !rewards.getFirst().has(DataComponents.UNBREAKABLE)
                    && dispatcher.execute(command + "place kings_crown 13 101 6", admin) == 0,
                    "The crown altar awards one netherite helmet and rejects a second claim");
            }
            if (weapon.id().equals("bloodfang")) {
                check(rewards.getFirst().is(Items.NETHERITE_AXE), "The BloodFang altar awards a netherite axe");
            }
            if (weapon.id().equals("kings_decree")) {
                check(owner.getInventory().countItem(Items.NETHERITE_INGOT) == 1
                    && owner.getInventory().countItem(Items.PLAYER_HEAD) == 0, "The claim charges the exact recipe and leaves surplus materials");
                check(dispatcher.execute(command + "place kings_decree 13 101 5", admin) == 0,
                    "A second claim remains blocked at a different location");
            }
        }
        check(altarDisplays(level).isEmpty(), "Claimed altars leave no floating displays behind");
        var visualOnly = LightningBolt.class.getDeclaredField("visualOnly");
        visualOnly.setAccessible(true);
        var lightning = new ArrayList<LightningBolt>();
        for (Entity entity : level.getAllEntities()) if (entity instanceof LightningBolt bolt) lightning.add(bolt);
        boolean cosmetic = !lightning.isEmpty();
        for (LightningBolt bolt : lightning) cosmetic &= visualOnly.getBoolean(bolt);
        check(cosmetic, "Altar claims spawn cosmetic lightning");
        check(dispatcher.execute(command + "reset sunpiercer", admin) == 1
            && level.getBlockState(first.offset(3, 0, 0)).is(Blocks.BEDROCK),
            "An explicit reset reopens the prior altar site");
        AmplifiedItems.CONFIG.altarEventRequirements.clear();
    }

    private static final BlockPos STAFF_HOLDER = new BlockPos(14, 101, 1);
    private static final ChunkPos SUMMON_CHUNK = new ChunkPos(8, 8);
    private static final String CHUNK_COW = "Chunk Ledger Cow";

    private static void checkStaffUnload(MinecraftServer server, ServerPlayer owner) throws Exception {
        ServerLevel level = server.overworld();
        loadSummonChunk(level);
        owner.setPos(0.5, 101, 0.5);
        owner.setYRot(0);
        owner.setXRot(0);
        var staff = weapon("kings_decree");
        owner.setItemInHand(InteractionHand.MAIN_HAND, staff);
        var cow = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
        cow.setPos(2, 101, 0.5);
        cow.setCustomName(Component.literal(CHUNK_COW));
        level.addFreshEntity(cow);
        check(AmplifiedItems.SUMMONS.use(owner, staff, cow) && AmplifiedItems.SUMMONS.use(owner, staff, null),
            "Chunk test captures and deploys a fresh cow");
        Mob summon = active(level);
        summon.setHealth(4);
        summon.setPos(128.5, 101, 128.5);
        unloadSummonChunk(level);
        check(summon.getRemovalReason() == Entity.RemovalReason.UNLOADED_TO_CHUNK && summon.getHealth() == 4,
            "Vanilla unload removes a living summon while preserving its health");

        AmplifiedItems.SUMMONS.tick();
        check(AmplifiedItems.SUMMONS.status(staff).equals("STORED"),
            "The unloaded summon is recalled instead of being marked dead");
        check(AmplifiedItems.SUMMONS.use(owner, staff, null), "The staff can summon again while the old chunk copy is parked");
        Mob recalled = active(level);
        check(recalled.getHealth() == 4 && recalled.getName().getString().equals(CHUNK_COW)
            && !recalled.getUUID().equals(summon.getUUID()), "Recall preserves the mob's current health and name under a fresh UUID");
        check(AmplifiedItems.SUMMONS.use(owner, staff, null), "Recall the surviving summon for the restart test");

        level.setBlockAndUpdate(STAFF_HOLDER, Blocks.BARREL.defaultBlockState());
        var holder = (BarrelBlockEntity) level.getBlockEntity(STAFF_HOLDER);
        holder.setItem(0, staff);
    }

    private static void checkStaffRestart(MinecraftServer server) {
        ServerLevel level = server.overworld();
        level.setChunkForced(0, 0, true);
        level.getChunkSource().tick(() -> true, false);
        level.waitForEntities(new ChunkPos(0, 0), 0);
        loadSummonChunk(level);
        var holder = (BarrelBlockEntity) level.getBlockEntity(STAFF_HOLDER);
        ItemStack staff = holder.getItem(0);
        check(AmplifiedItems.SUMMONS.status(staff).equals("STORED"), "The recalled mob remains stored after restarting the server");
        var parked = java.util.stream.StreamSupport.stream(level.getAllEntities().spliterator(), false)
            .filter(e -> e.getName().getString().equals(CHUNK_COW)).findFirst().orElseThrow();
        AmplifiedItems.SUMMONS.tick();
        check(parked.isRemoved(), "The old world copy is discarded safely when its chunk reloads after restart");

        ServerPlayer owner = player(server, "StaffRestartCheck", 0.5, 0.5);
        owner.setItemInHand(InteractionHand.MAIN_HAND, staff);
        check(AmplifiedItems.SUMMONS.use(owner, staff, null), "The staff deploys the saved summon after restart");
        Mob mob = active(level);
        check(mob.getHealth() == 4 && mob.getName().getString().equals(CHUNK_COW),
            "Restart preserves the recalled mob's exact health and name");
        AmplifiedItems.SUMMONS.recallOwner(owner.getUUID());
        level.removePlayerImmediately(owner, Entity.RemovalReason.DISCARDED);
        server.getPlayerList().getPlayers().remove(owner);
        server.getPlayerList().getPlayersByUUID().remove(owner.getUUID());
    }

    private static void loadSummonChunk(ServerLevel level) {
        level.setChunkForced(8, 8, true);
        level.getChunkSource().tick(() -> true, false);
        level.waitForEntities(SUMMON_CHUNK, 0);
    }

    private static void unloadSummonChunk(ServerLevel level) throws ReflectiveOperationException {
        // Drive the vanilla save-and-unload path synchronously in the test server.
        var entityManager = ServerLevel.class.getDeclaredField("entityManager");
        entityManager.setAccessible(true);
        Object manager = entityManager.get(level);
        var unload = manager.getClass().getDeclaredMethod("processChunkUnload", long.class);
        unload.setAccessible(true);
        if (!(boolean) unload.invoke(manager, SUMMON_CHUNK.pack())) throw new AssertionError("Chunk entities were not ready to unload");
    }

    private static void checkAltarRestart(MinecraftServer server) throws Exception {
        ServerLevel level = server.overworld();
        level.setChunkForced(0, 0, true);
        level.getChunkSource().tick(() -> true, false);
        level.waitForEntities(new ChunkPos(0, 0), 0);
        altarTick(server);
        var source = server.createCommandSourceStack().withPermission(PermissionSet.ALL_PERMISSIONS);
        var dispatcher = server.getCommands().getDispatcher();
        check(level.getBlockState(new BlockPos(2, 101, 5)).isAir()
            && dispatcher.execute("amplifieditems altar place kings_decree 13 101 5", source) == 0,
            "A claimed weapon remains unavailable after an actual server restart");
        var core = level.getBlockState(new BlockPos(5, 101, 5));
        int displayCount = altarDisplays(level).size();
        check(core.is(Blocks.BEDROCK) && displayCount == 3,
            "An unclaimed altar restores its displays after restart (" + core + ", " + displayCount + " displays)");
    }

    private static void altarTick(MinecraftServer server) {
        AmplifiedItems.tick = Math.ceilDiv(AmplifiedItems.tick + 1, 20) * 20 - 1;
        ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server);
    }

    private static List<Entity> altarDisplays(ServerLevel level) {
        return java.util.stream.StreamSupport.stream(level.getAllEntities().spliterator(), false)
            .filter(e -> e.entityTags().contains("amplified_altar_display") && !e.isRemoved()).toList();
    }

    private static void clickAltar(ServerPlayer player, BlockPos pos) {
        var hit = new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false);
        player.gameMode.useItemOn(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
    }

    private static ItemStack craft(ServerLevel level, CraftingInput input) {
        return level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level).orElseThrow()
            .value().assemble(input);
    }

    private static Map<String, Integer> fillInventory(ServerPlayer player, RitualRecipe recipe, Map<String, ItemStack> special) {
        var inventory = player.getInventory();
        inventory.clearContent();
        var slots = new HashMap<String, Integer>();
        int slot = 0;
        for (var ingredient : recipe.ingredients().entrySet()) {
            ItemStack stack = special.get(ingredient.getKey());
            if (stack == null) stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(ingredient.getKey())));
            if (stack.isEmpty()) throw new AssertionError("Missing " + ingredient.getKey());
            slots.put(ingredient.getKey(), slot);
            for (int remaining = ingredient.getValue(); remaining > 0;) {
                int count = Math.min(stack.getMaxStackSize(), remaining);
                if (slot >= inventory.getNonEquipmentItems().size()) throw new AssertionError("Recipe does not fit in an inventory");
                inventory.setItem(slot++, stack.copyWithCount(count));
                remaining -= count;
            }
        }
        return slots;
    }
}
