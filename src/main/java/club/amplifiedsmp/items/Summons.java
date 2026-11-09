package club.amplifiedsmp.items;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.Vec3;
import java.io.IOException;
import java.util.*;

public final class Summons {
    private static final class Active {
        final UUID staff;
        final Mob mob;
        final UUID owner;
        long expires;
        long idleSince;
        LivingEntity target;
        Active(UUID staff, Mob mob, UUID owner) {
            this.staff = staff; this.mob = mob; this.owner = owner;
            expires = AmplifiedItems.tick + AmplifiedItems.CONFIG.summonMaxSeconds * 20L;
            idleSince = AmplifiedItems.tick;
        }
    }
    private final MinecraftServer server;
    private final SummonLedger ledger;
    private final Map<UUID, Active> active = new HashMap<>();
    public Summons(MinecraftServer server) throws IOException {
        this.server = server;
        ledger = new SummonLedger(server.getWorldPath(LevelResource.ROOT).resolve("data/amplified-items/summons.json"));
    }
    public boolean ownedBy(Entity e, UUID owner) {
        Active a = active.get(e.getUUID()); return a != null && a.owner.equals(owner);
    }
    public boolean controlled(Entity e) { return active.containsKey(e.getUUID()); }
    public String status(ItemStack item) {
        var e = ledger.get(WeaponItems.instance(item)); return e == null ? "Empty" : e.state.toString();
    }
    public boolean use(ServerPlayer player, ItemStack item, LivingEntity clicked) {
        UUID staff = WeaponItems.instance(item);
        if (staff == null) { Abilities.message(player, "Invalid staff. Ask an admin to replace it."); return false; }
        var entry = ledger.get(staff);
        if (entry != null && entry.state == SummonLedger.State.ACTIVE) {
            Active a = active.get(entry.entity);
            if (a == null) { Abilities.message(player, "Your summon is in an unloaded chunk. It will recall when loaded."); return false; }
            if (!a.owner.equals(player.getUUID())) { Abilities.message(player, "This summon belongs to another player until recalled."); return false; }
            recall(a); return true;
        }
        if (entry == null || entry.state == SummonLedger.State.DEAD) {
            LivingEntity target = clicked != null ? clicked : Combat.aimed(player, 5);
            if (!(target instanceof Mob mob)) { Abilities.message(player, "Aim at a mob within 5 blocks and right-click with Ruler's Command selected."); return false; }
            return capture(player, staff, mob);
        }
        return summon(player, staff, entry);
    }
    private boolean capture(ServerPlayer player, UUID staff, Mob mob) {
        if (Rituals.isDefender(mob)) {
            Abilities.message(player, "Altar defenders cannot be captured.");
            return false;
        }
        if (!mob.isAlive() || mob.isRemoved() || mob.distanceToSqr(player) > 25 || !player.hasLineOfSight(mob)
                || mob.isPassenger() || mob.isVehicle() || controlled(mob)) {
            Abilities.message(player, "That mob cannot be captured here. Dismount it and remove passengers first."); return false;
        }
        if (AmplifiedItems.CONFIG.captureDenylist.contains(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString())) {
            Abilities.message(player, "This mob is blocked by the server's capture list."); return false;
        }
        if (mob instanceof TamableAnimal pet && pet.getOwnerReference() != null
                && !pet.getOwnerReference().matches(player) && !AmplifiedItems.CONFIG.allowCapturingOtherPlayersPets) {
            Abilities.message(player, "You cannot capture another player's pet."); return false;
        }
        try {
            String snapshot = snapshot(mob).toString();
            ledger.transaction(() -> {
                ledger.put(new SummonLedger.Entry(staff, player.getUUID(), mob.getUUID(), snapshot, SummonLedger.State.STORED));
                ledger.retire(mob.getUUID());
            }); // Commit first; retired IDs suppress stale entities after an interrupted world save.
            mob.discard();
            Abilities.message(player, "Captured " + mob.getName().getString() + ". Right-click to summon.");
            return true;
        } catch (Exception e) { return fail(player, e); }
    }
    private boolean summon(ServerPlayer player, UUID staff, SummonLedger.Entry entry) {
        try {
            CompoundTag tag = TagParser.parseCompoundFully(entry.snapshot);
            tag.remove("UUID"); tag.remove("Leash"); tag.remove("leash");
            Entity entity = EntityType.loadEntityRecursive(tag, player.level(),
                new EntitySpawnRequest(EntitySpawnReason.COMMAND, true), EntityProcessor.NOP);
            if (!(entity instanceof Mob mob)) throw new IOException("Stored entity is no longer a mob");
            Vec3 spot = player.position().add(player.getLookAngle().multiply(2.5, 0, 2.5));
            mob.setPos(spot.x, spot.y + 0.2, spot.z);
            if (!player.level().hasChunkAt(mob.blockPosition()) || !player.level().noCollision(mob)
                    || !player.level().getWorldBorder().isWithinBounds(mob.getBoundingBox())) {
                Abilities.message(player, "Not enough clear space to summon this mob."); return false;
            }
            mob.setNoAi(true); // All mob types use the same controlled melee AI; no explosions or owner targeting.
            mob.setPersistenceRequired();
            mob.setTarget(null);
            mob.addTag("cw_summon");
            ledger.transaction(() -> {
                entry.owner = player.getUUID(); entry.entity = mob.getUUID(); entry.state = SummonLedger.State.ACTIVE;
            });
            Active a = new Active(staff, mob, player.getUUID());
            a.target = Combat.aimed(player, AmplifiedItems.CONFIG.summonTargetRange);
            if (a.target != null && !Combat.canAffect(player, a.target)) a.target = null;
            active.put(mob.getUUID(), a);
            if (!player.level().addFreshEntity(mob)) {
                active.remove(mob.getUUID()); entry.state = SummonLedger.State.STORED; ledger.save();
                Abilities.message(player, "Summon was blocked by the server."); return false;
            }
            Abilities.message(player, "Summoned " + mob.getName().getString() + ". Right-click again to recall.");
            return true;
        } catch (Exception e) { return fail(player, e); }
    }
    private CompoundTag snapshot(Mob mob) throws IOException {
        var out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, server.registryAccess());
        if (!mob.save(out)) throw new IOException("Mob refused serialization");
        return out.buildResult();
    }
    private void recall(Active a) {
        if (!a.mob.isAlive()) { died(a.mob); return; }
        try {
            var entry = ledger.get(a.staff);
            CompoundTag saved = snapshot(a.mob);
            CompoundTag original = TagParser.parseCompoundFully(entry.snapshot);
            saved.putBoolean("NoAI", original.getBooleanOr("NoAI", false));
            saved.putBoolean("PersistenceRequired", original.getBooleanOr("PersistenceRequired", false));
            saved.remove("Tags");
            if (original.contains("Tags")) saved.put("Tags", original.get("Tags").copy());
            ledger.transaction(() -> {
                entry.snapshot = saved.toString(); entry.state = SummonLedger.State.STORED;
                ledger.retire(a.mob.getUUID());
            });
            active.remove(a.mob.getUUID());
            a.mob.discard();
            var owner = server.getPlayerList().getPlayer(a.owner);
            if (owner != null) Abilities.message(owner, "Your surviving summon returned to the staff.");
        } catch (Exception e) { AmplifiedItems.LOG.error("Cannot recall summon; leaving it active to preserve it", e); }
    }
    public void died(LivingEntity entity) {
        Active a = active.remove(entity.getUUID());
        if (a == null) return;
        var entry = ledger.get(a.staff);
        entry.state = SummonLedger.State.DEAD;
        ledger.retire(entity.getUUID());
        try { ledger.save(); } catch (IOException e) { AmplifiedItems.LOG.error("Could not save dead summon", e); }
        ServerPlayer owner = server.getPlayerList().getPlayer(a.owner);
        if (owner != null) Abilities.message(owner, "Your summon died. Capture a new mob.");
    }
    public void loaded(Entity entity) {
        if (ledger.retired(entity.getUUID())) { entity.discard(); return; }
        if (entity.entityTags().contains("cw_projectile")) { entity.discard(); return; }
        if (!(entity instanceof Mob mob) || !entity.entityTags().contains("cw_summon") || active.containsKey(entity.getUUID())) return;
        var entry = ledger.entries().stream().filter(e -> e.entity.equals(entity.getUUID()) && e.state == SummonLedger.State.ACTIVE).findFirst().orElse(null);
        if (entry == null) { entity.discard(); return; }
        mob.setNoAi(true);
        Active a = new Active(entry.staff, mob, entry.owner);
        active.put(entity.getUUID(), a);
        // On restart / chunk reload, survivors return before resuming combat.
        recall(a);
    }
    public void recallOwner(UUID owner) {
        for (Active a : List.copyOf(active.values())) if (a.owner.equals(owner)) recall(a);
    }
    public void stop() { for (Active a : List.copyOf(active.values())) recall(a); }
    public void tick() {
        for (Active a : List.copyOf(active.values())) {
            Mob mob = a.mob;
            if (!mob.isAlive()) { died(mob); continue; }
            if (mob.isRemoved()) { active.remove(mob.getUUID()); continue; } // Wait for its load event; never recreate an unloaded mob.
            ServerPlayer owner = server.getPlayerList().getPlayer(a.owner);
            if (owner == null || !owner.isAlive() || owner.level() != mob.level() || AmplifiedItems.tick >= a.expires
                    || owner.distanceToSqr(mob) > 80 * 80) { recall(a); continue; }
            if (a.target == null || !Combat.canAffect(owner, a.target) || a.target.level() != mob.level()
                    || a.target.distanceToSqr(mob) > 64 * 64) a.target = null;
            if (AmplifiedItems.tick % 10 == 0 && a.target == null) {
                LivingEntity hurt = owner.getLastHurtByMob(), attacked = owner.getLastHurtMob();
                if (hurt != null && Combat.canAffect(owner, hurt) && hurt.distanceToSqr(owner) < 24 * 24) a.target = hurt;
                else if (attacked != null && Combat.canAffect(owner, attacked) && attacked.distanceToSqr(owner) < 24 * 24) a.target = attacked;
                else a.target = owner.level().getEntitiesOfClass(Monster.class,
                    mob.getBoundingBox().inflate(AmplifiedItems.CONFIG.summonTargetRange), e -> Combat.canAffect(owner, e)
                        && !controlled(e) && mob.hasLineOfSight(e)).stream().min(Comparator.comparingDouble(mob::distanceToSqr)).orElse(null);
            }
            if (a.target == null) {
                if (AmplifiedItems.tick - a.idleSince >= AmplifiedItems.CONFIG.summonIdleSeconds * 20L) recall(a);
                continue;
            }
            a.idleSince = AmplifiedItems.tick;
            mob.getLookControl().setLookAt(a.target, 30, 30);
            mob.getLookControl().tick();
            double reach = Math.max(1.8, (mob.getBbWidth() + a.target.getBbWidth()) / 2 + 0.8);
            if (mob.distanceToSqr(a.target) <= reach * reach && mob.hasLineOfSight(a.target)) {
                if (AmplifiedItems.tick % 20 == 0) {
                    mob.swingForAttack(InteractionHand.MAIN_HAND);
                    // Player attribution preserves PvP checks, death messages and protection event hooks.
                    a.target.hurtServer(owner.level(), owner.level().damageSources().playerAttack(owner), (float) AmplifiedItems.CONFIG.summonMeleeDamage);
                }
            } else moveToward(a);
        }
    }
    private void moveToward(Active a) {
        Mob mob = a.mob;
        Vec3 destination = a.target.position();
        if (AmplifiedItems.tick % 10 == 0) mob.getNavigation().moveTo(a.target, 1);
        mob.getNavigation().tick();
        var path = mob.getNavigation().getPath();
        if (path != null && !path.isDone()) destination = path.getNextEntityPos(mob);
        Vec3 delta = destination.subtract(mob.position());
        double speed = mob.getAttribute(Attributes.MOVEMENT_SPEED) == null ? 0.22 : Math.max(0.12, Math.min(0.4, mob.getAttributeValue(Attributes.MOVEMENT_SPEED)));
        boolean flying = Set.of("ghast", "happy_ghast", "phantom", "blaze", "wither", "ender_dragon", "allay", "bat", "bee", "vex")
            .contains(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath());
        Vec3 step = flying || mob.isInWater() ? delta.normalize().scale(speed)
            : new Vec3(delta.x, 0, delta.z).normalize().scale(speed);
        if (!flying && mob.onGround() && (mob.horizontalCollision || delta.y > 0.4)) step = step.add(0, 0.42, 0);
        mob.move(MoverType.SELF, step);
    }
    private boolean fail(ServerPlayer player, Exception e) {
        AmplifiedItems.LOG.error("Mob storage operation failed", e);
        Abilities.message(player, "Storage error. Ask an admin to check the log."); return false;
    }
}
