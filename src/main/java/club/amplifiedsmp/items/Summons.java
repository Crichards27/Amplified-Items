package club.amplifiedsmp.items;

import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import java.io.IOException;
import java.util.*;

/** Temporary, player-commanded knights. The ledger is read only for legacy pet recovery. */
public final class Summons {
    public static final String KNIGHT_TAG = "amplified_royal_knight";
    public static final int KNIGHT_COUNT = 4;
    private static final class Active {
        final UUID staff, owner;
        final IronGolem mob;
        final ServerPlayer target;
        final long expires;
        long nextAttack;
        Active(UUID staff, ServerPlayer owner, IronGolem mob, ServerPlayer target) {
            this.staff = staff; this.owner = owner.getUUID(); this.mob = mob; this.target = target;
            expires = AmplifiedItems.tick + AmplifiedItems.CONFIG.summonMaxSeconds * 20L;
            nextAttack = AmplifiedItems.tick;
        }
    }
    private final MinecraftServer server;
    private final SummonLedger ledger;
    private final Map<UUID, Active> active = new HashMap<>();
    private final Set<Entity> pendingLoads = new LinkedHashSet<>();
    private final Set<UUID> recoveryErrors = new HashSet<>();

    public Summons(MinecraftServer server) throws IOException {
        this.server = server;
        RoyalKnightVisuals.init();
        ledger = new SummonLedger(server.getWorldPath(LevelResource.ROOT).resolve("data/amplified-items/summons.json"));
    }
    public boolean ownedBy(Entity entity, UUID owner) {
        Active knight = active.get(entity.getUUID());
        return knight != null && knight.mob == entity && knight.owner.equals(owner);
    }
    public boolean controlled(Entity entity) {
        Active knight = active.get(entity.getUUID());
        return knight != null && knight.mob == entity;
    }
    public String status(ItemStack item) {
        UUID staff = WeaponItems.instance(item);
        return active.values().stream().anyMatch(a -> a.staff.equals(staff)) ? "Knights active" : "Ready";
    }
    public boolean use(ServerPlayer player, ItemStack item, LivingEntity clicked) {
        UUID staff = WeaponItems.instance(item);
        if (staff == null || !player.isAlive() || player.isSpectator() || player.isCreative()) return false;
        // Cloned items and different staffs cannot multiply one player's army.
        if (active.values().stream().anyMatch(a -> a.owner.equals(player.getUUID()) || a.staff.equals(staff))) {
            Abilities.message(player, "Your royal knights are already deployed.");
            return false;
        }
        LivingEntity aimed = Combat.aimed(player, AmplifiedItems.CONFIG.summonTargetRange);
        if (!(aimed instanceof ServerPlayer target) || !validTarget(player, target)
                || !player.hasLineOfSight(target)) {
            Abilities.message(player, "Aim at an enemy player within " + (int) AmplifiedItems.CONFIG.summonTargetRange + " blocks.");
            return false;
        }
        var planned = new ArrayList<IronGolem>();
        for (int radius = 0; radius < 3 && planned.size() < KNIGHT_COUNT; radius++) {
            for (int direction = 0; direction < 8 && planned.size() < KNIGHT_COUNT; direction++) {
                double angle = direction * Math.PI / 4;
                double distance = 2.5 + radius * 1.5;
                IronGolem golem = createKnight(player.level());
                for (int dy : new int[] {0, 1, -1}) {
                    golem.setPos(player.getX() + Math.cos(angle) * distance,
                        player.getY() + dy, player.getZ() + Math.sin(angle) * distance);
                    if (safeSpot(golem) && planned.stream().noneMatch(other -> other.getBoundingBox().inflate(.1).intersects(golem.getBoundingBox()))) {
                        planned.add(golem);
                        break;
                    }
                }
            }
        }
        if (planned.size() != KNIGHT_COUNT) {
            planned.forEach(Entity::discard);
            Abilities.message(player, "Four knights need clear, solid ground nearby.");
            return false;
        }
        try {
            for (IronGolem golem : planned) {
                golem.setTarget(target);
                if (!player.level().addFreshEntity(golem) || !RoyalKnightVisuals.attach(golem))
                    throw new IllegalStateException("Knight or visual spawn blocked");
                // All insertion callbacks are queued; no AI tick runs during this atomic operation.
                active.put(golem.getUUID(), new Active(staff, player, golem, target));
            }
        } catch (RuntimeException failure) {
            for (IronGolem golem : planned) {
                active.remove(golem.getUUID());
                RoyalKnightVisuals.cleanup(golem);
                golem.discard();
            }
            AmplifiedItems.LOG.warn("Royal knight deployment rolled back", failure);
            Abilities.message(player, "The server could not deploy all four knights.");
            return false;
        }
        Abilities.message(player, "Four royal knights pursue " + target.getName().getString() + ".");
        return true;
    }
    private IronGolem createKnight(ServerLevel level) {
        IronGolem golem = new IronGolem(EntityTypes.IRON_GOLEM, level) {
            @Override protected void dropFromLootTable(ServerLevel world, DamageSource source, boolean playerKill) {}
            @Override protected void dropCustomDeathLoot(ServerLevel world, DamageSource source, boolean playerKill) {}
            @Override protected int getBaseExperienceReward(ServerLevel world) { return 0; }
            @Override public boolean shouldBeSaved() { return false; }
        };
        golem.addTag(KNIGHT_TAG);
        golem.setPlayerCreated(true);
        golem.setPersistenceRequired();
        golem.setCanPickUpLoot(false);
        return golem;
    }
    private boolean safeSpot(Mob mob) {
        ServerLevel level = (ServerLevel) mob.level();
        var box = mob.getBoundingBox();
        for (int x = (int) Math.floor(box.minX / 16); x <= (int) Math.floor(box.maxX / 16); x++)
            for (int z = (int) Math.floor(box.minZ / 16); z <= (int) Math.floor(box.maxZ / 16); z++)
                if (!level.hasChunk(x, z)) return false;
        BlockPos below = mob.blockPosition().below();
        return level.getWorldBorder().isWithinBounds(box) && level.noCollision(mob)
            && !level.containsAnyLiquid(box) && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }
    private boolean validTarget(ServerPlayer owner, ServerPlayer target) {
        double range = AmplifiedItems.CONFIG.summonTargetRange;
        return owner.isAlive() && !owner.isRemoved() && !owner.isSpectator() && !owner.isCreative()
            && server.getPlayerList().getPlayer(owner.getUUID()) == owner
            && server.getPlayerList().getPlayer(target.getUUID()) == target
            && !target.isRemoved() && target.level() == owner.level()
            && owner.distanceToSqr(target) <= range * range && Combat.canAffect(owner, target);
    }
    private void dismiss(Active knight) {
        if (knight == null) return;
        active.remove(knight.mob.getUUID(), knight);
        RoyalKnightVisuals.cleanup(knight.mob);
        knight.mob.getNavigation().stop();
        knight.mob.setTarget(null);
        if (!knight.mob.isRemoved()) knight.mob.discard();
    }
    public void died(LivingEntity entity) { dismiss(active.get(entity.getUUID())); }
    public void loaded(Entity entity) {
        if (entity.entityTags().contains("cw_projectile")) entity.discard();
        else if (entity.entityTags().contains(KNIGHT_TAG) || entity.entityTags().contains("cw_summon") || ledger.retired(entity.getUUID()))
            pendingLoads.add(entity);
    }
    private void reconcileLoads() {
        var loaded = List.copyOf(pendingLoads);
        pendingLoads.clear();
        for (Entity entity : loaded) {
            if (entity.isRemoved() || controlled(entity)) continue;
            if (entity.entityTags().contains(KNIGHT_TAG) || ledger.retired(entity.getUUID())) {
                if (entity instanceof IronGolem golem) RoyalKnightVisuals.cleanup(golem);
                entity.discard();
            }
            else if (entity instanceof Mob mob && entity.entityTags().contains("cw_summon")) {
                var entry = ledger.entries().stream().filter(e -> e.entity.equals(mob.getUUID())
                    && e.state == SummonLedger.State.ACTIVE).findFirst().orElse(null);
                try {
                    if (entry != null) {
                        CompoundTag original = TagParser.parseCompoundFully(entry.snapshot);
                        mob.setNoAi(original.getBooleanOr("NoAI", false));
                        // Native tags, ownership, current health and equipment remain untouched.
                        mob.setTarget(null);
                    }
                    mob.removeTag("cw_summon");
                    if (entry != null) {
                        ((ServerLevel) mob.level()).save(null, true, false);
                        ledger.transaction(() -> entry.state = SummonLedger.State.RESTORED);
                    }
                } catch (Exception error) {
                    AmplifiedItems.LOG.error("Cannot finish returning legacy live pet; snapshot retained", error);
                    pendingLoads.add(mob);
                }
            }
        }
    }
    private Mob legacyMob(SummonLedger.Entry entry, ServerLevel level) throws Exception {
        CompoundTag tag = TagParser.parseCompoundFully(entry.snapshot);
        tag.remove("UUID"); tag.remove("Leash"); tag.remove("leash"); tag.remove("Passengers");
        Entity entity = EntityType.loadEntityRecursive(tag, level,
            new EntitySpawnRequest(EntitySpawnReason.COMMAND, true), EntityProcessor.NOP);
        if (!(entity instanceof Mob mob)) throw new IOException("Legacy snapshot is no longer a mob: " + entry.staff);
        mob.removeTag("cw_summon");
        return mob;
    }
    private void recoverStoredPets() {
        for (var entry : List.copyOf(ledger.entries())) {
            if (entry.state != SummonLedger.State.STORED && entry.state != SummonLedger.State.RETURNING) continue;
            if (recoveryErrors.contains(entry.staff)) continue;
            ServerPlayer owner = server.getPlayerList().getPlayer(entry.owner);
            if (owner == null || !owner.isAlive() || owner.isSpectator()) continue;
            try {
                Mob probe = legacyMob(entry, owner.level());
                // Knight placement proves dry ground, not a legacy pet's habitat. Keep aquatic
                // snapshots AND committed RETURNING plans for admin recovery, even if their
                // recovery UUID already exists: existence alone cannot prove a safe return.
                if (!LegacyRecoveryPolicy.allowsAutomaticDryLandReturn(probe.getClass())) continue;
                if (entry.state == SummonLedger.State.STORED) {
                    boolean found = false;
                    for (int radius = 2; radius <= 6 && !found; radius += 2) {
                        for (int direction = 0; direction < 8 && !found; direction++) {
                            double angle = direction * Math.PI / 4;
                            probe.setPos(owner.getX() + Math.cos(angle) * radius, owner.getY(), owner.getZ() + Math.sin(angle) * radius);
                            found = safeSpot(probe);
                        }
                    }
                    if (!found) continue; // Keep the snapshot; never delete a pet when space is blocked.
                    ledger.transaction(() -> {
                        entry.recoveryEntity = UUID.randomUUID();
                        entry.recoveryDimension = owner.level().dimension().identifier().toString();
                        entry.recoveryX = probe.getX(); entry.recoveryY = probe.getY(); entry.recoveryZ = probe.getZ();
                        entry.state = SummonLedger.State.RETURNING;
                    });
                }
                ServerLevel level = java.util.stream.StreamSupport.stream(server.getAllLevels().spliterator(), false)
                    .filter(l -> l.dimension().identifier().toString().equals(entry.recoveryDimension)).findFirst().orElse(null);
                if (level == null || level.noSave()) continue;
                BlockPos position = BlockPos.containing(entry.recoveryX, entry.recoveryY, entry.recoveryZ);
                // Load the committed recovery chunk before checking UUIDs: an unloaded saved pet is not absent.
                level.getChunk(position);
                level.waitForEntities(new ChunkPos(position.getX() >> 4, position.getZ() >> 4), 0);
                Entity restored = level.getEntityInAnyDimension(entry.recoveryEntity);
                if (restored == null) {
                    Mob pet = legacyMob(entry, level);
                    pet.setUUID(entry.recoveryEntity);
                    pet.setPos(entry.recoveryX, entry.recoveryY, entry.recoveryZ);
                    if (!safeSpot(pet) || !level.addFreshEntity(pet)) continue;
                }
                // Flush native entity storage BEFORE acknowledging return. A crash can retry the same UUID, not clone a pet.
                level.save(null, true, false);
                ledger.transaction(() -> entry.state = SummonLedger.State.RESTORED);
                Abilities.message(owner, "Your previously captured mob has been returned safely. King's Decree now summons royal knights.");
            } catch (Exception error) {
                recoveryErrors.add(entry.staff);
                AmplifiedItems.LOG.error("Legacy pet recovery deferred; original snapshot retained for admin recovery: " + entry.staff, error);
            }
        }
    }
    public void recallOwner(UUID owner) {
        for (Active knight : List.copyOf(active.values())) if (knight.owner.equals(owner)) dismiss(knight);
    }
    public void stop() {
        reconcileLoads();
        for (Active knight : List.copyOf(active.values())) dismiss(knight);
    }
    public void tick() {
        reconcileLoads();
        if (AmplifiedItems.tick % 20 == 0) recoverStoredPets();
        for (Active knight : List.copyOf(active.values())) {
            IronGolem golem = knight.mob;
            ServerPlayer owner = server.getPlayerList().getPlayer(knight.owner);
            if (golem.isRemoved() || !golem.isAlive() || owner == null || owner.level() != golem.level()
                    || !validTarget(owner, knight.target) || AmplifiedItems.tick >= knight.expires
                    || owner.distanceToSqr(golem) > 80 * 80) { dismiss(knight); continue; }
            golem.setTarget(knight.target);
            if (golem.isWithinMeleeAttackRange(knight.target) && golem.hasLineOfSight(knight.target)) {
                golem.getNavigation().stop();
                if (AmplifiedItems.tick >= knight.nextAttack) {
                    knight.nextAttack = AmplifiedItems.tick + 20;
                    golem.level().broadcastEntityEvent(golem, (byte) 4);
                    knight.target.hurtServer(owner.level(), owner.level().damageSources().playerAttack(owner),
                        (float) AmplifiedItems.CONFIG.summonMeleeDamage);
                }
            } else if (AmplifiedItems.tick % 10 == 0 || golem.getNavigation().isDone()) {
                golem.getNavigation().moveTo(knight.target, 1);
            }
        }
    }
    /** Keep native pathfinding, physics and controls, but never run autonomous target/attack goals. */
    public void aiStep(Mob mob) {
        Active knight = active.get(mob.getUUID());
        if (knight == null) return;
        mob.setTarget(knight.target);
        mob.getLookControl().setLookAt(knight.target, 30, 30);
        mob.getNavigation().tick();
        mob.getMoveControl().tick();
        mob.getLookControl().tick();
        mob.getJumpControl().tick();
    }
}
