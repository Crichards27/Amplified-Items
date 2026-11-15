package club.amplifiedsmp.items.verification;

import club.amplifiedsmp.items.RoyalKnightVisuals;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.level.storage.TagValueOutput;
import java.util.List;

/** Dedicated isolated-server visual tests. Can also call run(server) from a verifier. */
public final class RoyalKnightVisualChecks implements ModInitializer {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        System.out.println("KNIGHT_VISUAL_CHECK " + (++checks) + " " + message);
    }
    @Override public void onInitialize() {
        RoyalKnightVisuals.init();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try { run(server); System.out.println("KNIGHT_VISUAL_OK " + checks); }
            catch (Throwable failure) { System.out.println("KNIGHT_VISUAL_FAILED"); failure.printStackTrace(); }
            finally { server.halt(false); }
        });
    }
    private static IronGolem golem(ServerLevel level, double x) {
        IronGolem golem = EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
        golem.setPos(x, 100, 0);
        golem.setNoAi(true);
        check(level.addFreshEntity(golem), "test golem added to actual ServerLevel");
        return golem;
    }
    private static Display.ItemDisplay overlay(IronGolem golem) {
        return golem.getPassengers().stream().filter(Display.ItemDisplay.class::isInstance)
            .map(Display.ItemDisplay.class::cast).filter(RoyalKnightVisuals::isVisual).findFirst().orElseThrow();
    }
    private static CompoundTag save(Entity entity) {
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
        entity.saveWithoutId(output);
        return output.buildResult();
    }
    public static void run(MinecraftServer server) {
        ServerLevel level = server.overworld();
        IronGolem ordinary = golem(level, 0);
        RoyalKnightVisuals.loaded(ordinary);
        RoyalKnightVisuals.tick();
        RoyalKnightVisuals.cleanup(ordinary);
        check(!ordinary.isInvisible() && ordinary.getPassengers().isEmpty(), "normal vanilla golem untouched");
        ordinary.setInvisible(true);
        RoyalKnightVisuals.cleanup(ordinary);
        check(ordinary.isInvisible(), "cleanup never changes an untagged vanilla golem");
        ordinary.discard();

        IronGolem knight = golem(level, 3);
        knight.yBodyRot = 71;
        check(RoyalKnightVisuals.attach(knight), "knight display attached successfully");
        Display.ItemDisplay display = overlay(knight);
        check(knight.isInvisible() && display.getVehicle() == knight && !display.isInvisible(), "invisible golem carries visible vanilla item_display");
        check(Identifier.parse("amplified_items:royal_knight").equals(display.getSlot(0).get().get(DataComponents.ITEM_MODEL)), "display uses dedicated item-model component");
        check(RoyalKnightVisuals.attach(knight) && knight.getPassengers().size() == 1, "attach is idempotent without duplicate passengers");
        CompoundTag tag = save(display);
        check(tag.getStringOr("item_display", "").equals("none") && tag.getStringOr("billboard", "").equals("fixed"), "model has no hand transform or camera billboard");
        check(!display.affectedByCulling(), "offset model below mount point is not incorrectly frustum-culled");
        var transform = com.mojang.math.Transformation.EXTENDED_CODEC
            .parse(net.minecraft.nbt.NbtOps.INSTANCE, tag.get("transformation")).getOrThrow();
        check(Math.abs(transform.scale().y() - 1.35) < .0001, "model scaled to 2.7-block golem height");
        check(Math.abs(display.getY() + transform.translation().y() - 1.35 - knight.getY()) < .001,
            "model boots sit exactly on golem feet instead of floating at passenger height");
        knight.yBodyRot = -123;
        knight.setInvisible(false);
        RoyalKnightVisuals.tick();
        check(display.getYRot() == -123 && display.getXRot() == 0 && knight.isInvisible(), "display follows body yaw, never head pitch, and maintains invisibility");
        double offset = knight.getPassengerRidingPosition(display).y - display.getVehicleAttachmentPoint(knight).y - knight.getY();
        check(Math.abs(display.getY() - knight.getY() - offset) < .001, "passenger positioned at actual 26.3 mount attachment");
        check(!RoyalKnightVisuals.isVisual(knight), "host is not mistaken for a visual entity");
        RoyalKnightVisuals.cleanup(knight);
        RoyalKnightVisuals.cleanup(knight);
        check(!knight.isInvisible() && display.isRemoved() && knight.getPassengers().isEmpty(), "cleanup removes overlay and restores visibility idempotently");
        knight.setInvisible(true);
        check(RoyalKnightVisuals.attach(knight), "pre-invisible golem can attach");
        RoyalKnightVisuals.cleanup(knight);
        check(knight.isInvisible(), "cleanup preserves preexisting invisibility");
        knight.setInvisible(false);
        check(RoyalKnightVisuals.attach(knight), "attach again after cleanup");
        overlay(knight).discard();
        RoyalKnightVisuals.tick();
        check(!knight.isInvisible() && knight.getPassengers().isEmpty(), "missing overlay fails open to visible golem, not invisible attacker");
        knight.discard();

        IronGolem blocked = golem(level, 6);
        for (int i = 0; i < Entity.MAX_ENTITY_TAG_COUNT; i++) blocked.addTag("visual_test_" + i);
        check(!RoyalKnightVisuals.attach(blocked) && !blocked.isInvisible() && blocked.getPassengers().isEmpty(), "tag-capacity failure rolls back display and invisibility");
        blocked.discard();

        var orphan = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        orphan.addTag(RoyalKnightVisuals.VISUAL_TAG);
        orphan.setPos(9, 100, 0);
        check(level.addFreshEntity(orphan), "orphan display added");
        RoyalKnightVisuals.loaded(orphan);
        RoyalKnightVisuals.tick();
        check(orphan.isRemoved(), "tagged orphan display removed on load/restart");
        IronGolem stale = golem(level, 12);
        stale.addTag(RoyalKnightVisuals.HOST_TAG);
        stale.setInvisible(true);
        RoyalKnightVisuals.loaded(stale);
        RoyalKnightVisuals.tick();
        check(!stale.isInvisible() && !stale.entityTags().contains(RoyalKnightVisuals.HOST_TAG), "loaded stale host restored rather than remaining invisible");
        stale.discard();
        IronGolem dead = golem(level, 15);
        dead.discard();
        check(!RoyalKnightVisuals.attach(dead), "removed host rejected");
    }
}
