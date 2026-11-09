package club.amplifiedsmp.items.verification;

import club.amplifiedsmp.items.*;
import club.amplifiedsmp.items.mixin.ChunkMapAccess;
import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.network.HashedStack;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.*;
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
    private static String lastRitualMessage;
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
            try { run(server); System.out.println("AMPLIFIED_RUNTIME_OK " + checks); }
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
                if (packet instanceof ClientboundSystemChatPacket chat) {
                    String message = chat.content().getString();
                    if (message.startsWith("Ritual ") || message.contains("was forged")) lastRitualMessage = message;
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
        for (Entity entity : level.getAllEntities()) if (entity instanceof Mob mob && entity.entityTags().contains("cw_summon") && !entity.isRemoved()) return mob;
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
    static void useItem(ServerPlayer player) {
        player.connection.handleUseItem(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, ++sequence, player.getYRot(), player.getXRot()));
    }
    static void menuClick(ServerPlayer player, int slot, int button, ContainerInput input) {
        var menu = player.containerMenu;
        player.connection.handleContainerClick(new ServerboundContainerClickPacket(menu.containerId, menu.getStateId(),
            (short) slot, (byte) button, input, Int2ObjectMaps.emptyMap(), HashedStack.EMPTY));
    }
    static void chooseAbility(ServerPlayer player, int slot) {
        pressF(player);
        if (!(player.containerMenu instanceof AbilityMenu)) throw new AssertionError("Ability menu did not open");
        menuClick(player, slot, 0, ContainerInput.PICKUP);
        if (player.containerMenu != player.inventoryMenu) throw new AssertionError("Ability menu did not close after choosing");
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
        var menu = owner.containerMenu;
        check(menu instanceof AbilityMenu && lastScreen.getType() == MenuType.GENERIC_9x1
            && owner.getMainHandItem() == bow && owner.getOffhandItem().is(Items.TORCH)
            && AmplifiedItems.COOLDOWNS.remaining(owner.getUUID(), "venom_shot", 2000) == 0,
            "F sends a vanilla menu without swapping items or casting");
        check(menu.getSlot(2).getItem().getHoverName().getString().equals("Venom Shot")
            && menu.getSlot(4).getItem().getHoverName().getString().equals("Grapple Hook"), "Menu shows both abilities");
        menuClick(owner, 4, 0, ContainerInput.QUICK_MOVE);
        menuClick(owner, 4, 40, ContainerInput.SWAP);
        check(menu == owner.containerMenu && menu.getCarried().isEmpty()
            && menu.getSlot(4).hasItem() && owner.getOffhandItem().is(Items.TORCH),
            "Menu icons cannot be shift-clicked or swapped into inventory");
        menuClick(owner, 4, 0, ContainerInput.PICKUP);
        check(owner.containerMenu == owner.inventoryMenu && owner.inventoryMenu.getCarried().isEmpty(), "Choosing an ability closes the menu without transferring an icon");
        var save = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, server.registryAccess());
        owner.saveWithoutId(save);
        check(save.buildResult().getCompoundOrEmpty("fabric:attachments").getCompoundOrEmpty("amplified_items:ability_selections")
            .getStringOr("venombolt", "").equals("grapple_hook"), "Selected ability is saved in player data");
        int correctionsBefore = predictedUseCorrections;
        useItem(owner);
        check(AmplifiedItems.COOLDOWNS.remaining(owner.getUUID(), "grapple_hook", 2000) == 100
            && AmplifiedItems.COOLDOWNS.remaining(owner.getUUID(), "venom_shot", 2000) == 0,
            "A vanilla right-click packet uses the selected Grapple Hook");
        check(predictedUseCorrections > correctionsBefore && !owner.isUsingItem(), "Ability cast corrects vanilla crossbow draw prediction");
        Projectile cast = projectile(level);
        useItem(owner);
        cast.discard();
        check(java.util.stream.StreamSupport.stream(level.getAllEntities().spliterator(), false)
            .noneMatch(e -> e.entityTags().contains("cw_projectile") && !e.isRemoved()), "Repeated use packets cannot double-cast");
        AmplifiedItems.tick += 4;
        chooseAbility(owner, 2);
        var blockPos = new BlockPos(0, 100, 3);
        var blockHit = new BlockHitResult(Vec3.atCenterOf(blockPos).add(0, 0.5, 0), Direction.UP, blockPos, false);
        owner.gameMode.useItemOn(owner, level, bow, InteractionHand.MAIN_HAND, blockHit);
        check(AmplifiedItems.COOLDOWNS.remaining(owner.getUUID(), "venom_shot", AmplifiedItems.tick) == 160,
            "Right-clicking a block casts the selected Venom Shot");
        projectile(level).discard();
        chooseAbility(owner, 6);
        var use = net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.invoker();
        check(use.interact(owner, level, InteractionHand.MAIN_HAND) == net.minecraft.world.InteractionResult.PASS,
            "Normal item use preserves vanilla right-click");
        chooseAbility(owner, 2);
        owner.setShiftKeyDown(true);
        check(use.interact(owner, level, InteractionHand.MAIN_HAND) == net.minecraft.world.InteractionResult.PASS,
            "Sneak right-click bypasses the selected ability");
        owner.setShiftKeyDown(false);

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
            check(server.getCommands().getDispatcher().execute("amplifieditems ritual recipe venombolt", owner.createCommandSourceStack()) == 0,
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
        checkRituals(server, owner);
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
        AmplifiedItems.tick += 4;
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
        for (int hit = 0; hit < 4; hit++) {
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
        check(owner.getMaxHealth() == 30, "Reaper grants fifteen max hearts");
        check(Abilities.REGISTRY.get("soul_stealer").use(owner, reaper, null), "Soul Stealer hits the aimed enemy");
        check(victim.getHealth() == 16 && owner.getHealth() == 16, "Soul Stealer transfers two hearts");
        owner.setHealth(30);
        AmplifiedItems.tick += 10;
        Abilities.tick(server);
        check(owner.hasEffect(MobEffects.SPEED) && owner.getEffect(MobEffects.SPEED).getAmplifier() == 0,
            "Reaper grants Speed I at full health");

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
        owner.setDeltaMovement(Vec3.ZERO);
        AmplifiedItems.tick += 20;
        useItem(owner);
        check(owner.getDeltaMovement().length() > 1, "Riptide allows another dash during its window");
        owner.setDeltaMovement(Vec3.ZERO);
        AmplifiedItems.tick += 180;
        useItem(owner);
        check(owner.getDeltaMovement().lengthSqr() == 0, "Riptide window closes after ten seconds");
    }
    static void checkRituals(MinecraftServer server, ServerPlayer owner) throws Exception {
        ServerLevel level = owner.level();
        BlockPos core = new BlockPos(5, 101, 5);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) level.setChunkForced(x, z, true);
        }
        level.getChunkSource().tick(() -> true, false);
        level.waitForEntities(new ChunkPos(0, 0), 1);
        for (int x = -8; x <= 18; x++) {
            for (int z = -8; z <= 18; z++) {
                BlockPos floor = new BlockPos(x, 100, z);
                level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(floor.above(), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(floor.above(2), Blocks.AIR.defaultBlockState());
            }
        }
        level.setBlockAndUpdate(core, Blocks.BARREL.defaultBlockState());
        var barrel = (BarrelBlockEntity) level.getBlockEntity(core);
        owner.setPos(5.5, 101, 2.5);
        owner.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(core).add(0, 0.45, 0));
        var dispatcher = server.getCommands().getDispatcher();
        var source = owner.createCommandSourceStack();
        String start = "amplifieditems ritual start kings_decree";
        String status = "amplifieditems ritual status";
        check(dispatcher.execute(start, source) == 0, "A bare barrel cannot start a ritual");
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x != 0 || z != 0) level.setBlockAndUpdate(core.offset(x, 0, z), Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
            }
        }
        check(dispatcher.execute(start, source) == 0, "An empty altar cannot start a ritual");
        barrel.setItem(0, WeaponItems.create(AmplifiedItems.CONFIG.enabledWeapon("kings_decree")));
        barrel.setItem(1, new ItemStack(Items.GOLD_BLOCK, 5));
        barrel.setItem(2, new ItemStack(Items.DIAMOND));
        check(dispatcher.execute(start, source) == 0, "An existing Amplified Item cannot serve as the base weapon");
        barrel.setItem(0, new ItemStack(Items.IRON_SWORD));
        long started = AmplifiedItems.tick;
        check(dispatcher.execute(start, source) == 1 && dispatcher.execute(status, source) == 1, "A non-operator can start a complete altar");
        check(dispatcher.execute(start, source) == 0 && barrel.getItem(0).is(Items.IRON_SWORD),
            "Duplicate start is rejected and ingredients stay in the barrel");
        ritualTick(server, started + 1200);
        List<Entity> wave = ritualMobs(level);
        check(wave.size() == 2, "A 15-minute ritual spawns two defenders on the first minute");
        check(!AmplifiedItems.SUMMONS.use(owner, WeaponItems.create(AmplifiedItems.CONFIG.enabledWeapon("kings_decree")),
            (LivingEntity) wave.getFirst()), "King's Decree cannot store altar defenders");
        clearWave(level, owner, wave);
        for (int minute = 2; minute < 15; minute++) {
            ritualTick(server, started + minute * 1200);
            wave = ritualMobs(level);
            if (wave.size() != 2) throw new AssertionError("Minute " + minute + " produced " + wave.size() + " visible hostiles");
            clearWave(level, owner, wave);
        }
        ritualTick(server, started + 15 * 1200 - 20);
        check(WeaponItems.definition(barrel.getItem(0)) == null, "Ritual cannot forge before 15 minutes");
        ritualTick(server, started + 15 * 1200);
        check(WeaponItems.definition(barrel.getItem(0)) != null && WeaponItems.definition(barrel.getItem(0)).id().equals("kings_decree") && barrel.getItem(1).getCount() == 1
            && barrel.getItem(2).isEmpty() && dispatcher.execute(status, source) == 0, "Clearing the waves forges one weapon and consumes only recipe items (" + lastRitualMessage + ")");

        barrel.setItem(0, new ItemStack(Items.IRON_SWORD));
        barrel.setItem(1, new ItemStack(Items.GOLD_BLOCK, 4));
        barrel.setItem(2, new ItemStack(Items.DIAMOND));
        started = AmplifiedItems.tick;
        check(dispatcher.execute(start, source) == 1, "Altar can be reused");
        ritualTick(server, started + 1200);
        level.setBlockAndUpdate(core.offset(1, 0, 0), Blocks.AIR.defaultBlockState());
        ritualTick(server, started + 1220);
        check(ritualMobs(level).isEmpty() && barrel.getItem(0).is(Items.IRON_SWORD) && dispatcher.execute(status, source) == 0,
            "Breaking the ring fails the ritual, cleans up hostiles, and leaves the ingredients");

        level.setBlockAndUpdate(core.offset(1, 0, 0), Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
        barrel.setItem(0, new ItemStack(Items.NETHERITE_SWORD));
        barrel.setItem(1, new ItemStack(Items.PHANTOM_MEMBRANE, 8));
        barrel.setItem(2, new ItemStack(Items.ECHO_SHARD, 4));
        started = AmplifiedItems.tick;
        check(dispatcher.execute("amplifieditems ritual start nightblade", source) == 1, "The 30-minute NightBlade recipe starts");
        ritualTick(server, started + 1200);
        check(ritualMobs(level).size() == 3, "A 30-minute ritual sends three defenders per wave");
        ritualTick(server, started + 15 * 1200);
        check(barrel.getItem(0).is(Items.NETHERITE_SWORD) && dispatcher.execute(status, source) == 1,
            "A 30-minute ritual is still running after 15 minutes");
        ritualTick(server, started + 30 * 1200);
        check(ritualMobs(level).isEmpty() && barrel.getItem(0).is(Items.NETHERITE_SWORD) && dispatcher.execute(status, source) == 0,
            "Live defenders at the deadline prevent forging");
    }

    private static void ritualTick(MinecraftServer server, long tick) {
        AmplifiedItems.tick = Math.ceilDiv(tick, 20) * 20 - 1;
        ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server);
    }

    private static List<Entity> ritualMobs(ServerLevel level) {
        return java.util.stream.StreamSupport.stream(level.getAllEntities().spliterator(), false)
            .filter(e -> e.entityTags().contains("amplified_ritual_defender") && e.isAlive()).toList();
    }

    private static void clearWave(ServerLevel level, ServerPlayer owner, List<Entity> wave) {
        for (Entity mob : wave) {
            mob.hurtServer(level, level.damageSources().playerAttack(owner), 1000);
            if (mob.isAlive()) throw new AssertionError("Wave mob resisted lethal damage: " + mob);
            mob.discard();
        }
    }

}
