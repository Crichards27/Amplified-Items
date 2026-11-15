package club.amplifiedsmp.items;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Knight models attached to summoned golems as vanilla item displays.
 * init() registers the load, tick and shutdown hooks once. Call attach() after
 * spawning a knight and cleanup() before removing it. Use on the server thread.
 */
public final class RoyalKnightVisuals {
    public static final String VISUAL_TAG = "amplified_royal_knight_visual";
    public static final String HOST_TAG = "amplified_royal_knight_host";
    private static final String WAS_INVISIBLE_TAG = "amplified_royal_knight_was_invisible";
    private static final Identifier MODEL = Identifier.parse("amplified_items:royal_knight");
    private static final Logger LOG = LoggerFactory.getLogger("amplified-items/knight-visuals");
    private static final Map<UUID, Attached> ATTACHED = new HashMap<>();
    private static final Set<Entity> PENDING_LOADS = new LinkedHashSet<>();
    private static boolean initialized;

    private record Attached(IronGolem host, Display.ItemDisplay display, boolean wasInvisible) {}

    private RoyalKnightVisuals() {}

    /** Register once before worlds load; safe to call for each new server instance. */
    public static void init() {
        if (initialized) return;
        initialized = true;
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> loaded(entity));
        ServerTickEvents.END_SERVER_TICK.register(server -> tick());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Attached entry : List.copyOf(ATTACHED.values())) cleanup(entry.host());
            PENDING_LOADS.clear();
        });
    }

    public static boolean isVisual(Entity entity) {
        return entity instanceof Display.ItemDisplay && entity.entityTags().contains(VISUAL_TAG);
    }

    /**
     * Attach the model, rolling back on failure. Preserve existing invisibility
     * and leave unrelated passengers alone.
     */
    public static boolean attach(IronGolem golem) {
        init();
        if (!(golem.level() instanceof ServerLevel level) || golem.isRemoved() || !golem.isAlive()) return false;
        Attached existing = ATTACHED.get(golem.getUUID());
        if (existing != null && existing.host() == golem && valid(existing)) return true;
        if (existing != null) cleanup(existing.host());
        if (golem.entityTags().contains(HOST_TAG)) cleanup(golem);
        if (golem.isPassenger() || golem.isVehicle()) return false;

        boolean wasInvisible = golem.isInvisible();
        Display.ItemDisplay display = null;
        try {
            if (!golem.addTag(HOST_TAG)) return false;
            if (wasInvisible && !golem.addTag(WAS_INVISIBLE_TAG)) throw new IllegalStateException("Host tag capacity exhausted");
            KnightDisplay knight = new KnightDisplay(level);
            display = knight;
            if (!display.addTag(VISUAL_TAG)) throw new IllegalStateException("Display tag rejected");
            display.setNoGravity(true);
            display.setPermanentlyInvulnerable(true);
            display.setSilent(true);
            display.setPos(golem.position());
            // Force mounting permits a non-controlling cosmetic passenger on a golem.
            if (!display.startRiding(golem, true, false)) throw new IllegalStateException("Display could not mount golem");
            golem.positionRider(display);
            // Align the model's feet using the passenger attachment point.
            float translationY = (float) (1.35 - (display.getY() - golem.getY()));
            knight.configure(translationY);
            ItemStack stack = new ItemStack(Items.PAPER);
            stack.set(DataComponents.ITEM_MODEL, MODEL);
            if (!display.getSlot(0).set(stack)) throw new IllegalStateException("Display item slot rejected model");
            display.setYRot(golem.yBodyRot);
            display.setXRot(0);
            if (!level.addFreshEntity(display)) throw new IllegalStateException("Server rejected knight display");
            ATTACHED.put(golem.getUUID(), new Attached(golem, display, wasInvisible));
            golem.setInvisible(true); // Hide only after the visible overlay exists.
            return true;
        } catch (Exception failure) {
            if (display != null) {
                display.stopRiding();
                display.discard();
            }
            golem.removeTag(HOST_TAG);
            golem.removeTag(WAS_INVISIBLE_TAG);
            golem.setInvisible(wasInvisible);
            LOG.warn("Could not attach knight visual to {}; restored original visibility", golem.getUUID(), failure);
            return false;
        }
    }

    /** Remove the knight display and restore the golem's previous invisibility. */
    public static void cleanup(IronGolem golem) {
        Attached entry = ATTACHED.get(golem.getUUID());
        if (entry != null && entry.host() != golem) entry = null;
        if (entry != null) {
            ATTACHED.remove(golem.getUUID(), entry);
            entry.display().stopRiding();
            entry.display().discard();
        }
        for (Entity passenger : List.copyOf(golem.getPassengers())) {
            if (isVisual(passenger)) { passenger.stopRiding(); passenger.discard(); }
        }
        if (entry != null || golem.entityTags().contains(HOST_TAG)) {
            golem.setInvisible(entry != null ? entry.wasInvisible() : golem.entityTags().contains(WAS_INVISIBLE_TAG));
            golem.removeTag(HOST_TAG);
            golem.removeTag(WAS_INVISIBLE_TAG);
        }
        PENDING_LOADS.remove(golem);
    }

    /** Defer removal until after Fabric's entity-load callback finishes iterating. */
    public static void loaded(Entity entity) {
        if (isVisual(entity) || entity instanceof IronGolem && entity.entityTags().contains(HOST_TAG)) PENDING_LOADS.add(entity);
    }

    private static boolean valid(Attached entry) {
        return !entry.host().isRemoved() && entry.host().isAlive() && !entry.display().isRemoved()
            && entry.display().getVehicle() == entry.host() && entry.display().level() == entry.host().level();
    }

    /** Called each tick by the hook registered in init(). */
    public static void tick() {
        var loads = List.copyOf(PENDING_LOADS);
        PENDING_LOADS.clear();
        for (Entity entity : loads) {
            if (entity.isRemoved()) continue;
            if (isVisual(entity)) {
                Entity vehicle = entity.getVehicle();
                Attached current = vehicle == null ? null : ATTACHED.get(vehicle.getUUID());
                if (current == null || current.display() != entity || !valid(current)) {
                    entity.stopRiding();
                    entity.discard();
                }
            } else if (entity instanceof IronGolem golem) {
                Attached current = ATTACHED.get(golem.getUUID());
                // Saved overlays don't restore an active squad.
                if (current == null || current.host() != golem) cleanup(golem);
            }
        }
        for (Attached entry : List.copyOf(ATTACHED.values())) {
            if (!valid(entry)) { cleanup(entry.host()); continue; }
            entry.host().setInvisible(true);
            entry.display().setYRot(entry.host().yBodyRot);
            entry.display().setXRot(0);
            entry.host().positionRider(entry.display());
        }
    }

    /** Exposes NBT configuration while retaining the vanilla item_display type. */
    private static final class KnightDisplay extends Display.ItemDisplay {
        KnightDisplay(ServerLevel level) { super(EntityTypes.ITEM_DISPLAY, level); }
        void configure(float y) throws Exception {
            // The display setters are private in 26.3; configure through the NBT reader.
            var tag = TagParser.parseCompoundFully("{billboard:\"fixed\",item_display:\"none\","
                + "teleport_duration:2,interpolation_duration:0,view_range:1.5f,width:0f,height:0f,"
                + "shadow_radius:0f,shadow_strength:0f,transformation:{translation:[0f," + y + "f,0f],"
                + "scale:[1.35f,1.35f,1.35f],left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f]}}");
            // Zero width/height intentionally disables frustum culling: the mesh
            // extends below the passenger origin, and positive display bounds do not.
            super.readAdditionalSaveData(TagValueInput.create(ProblemReporter.DISCARDING, registryAccess(), tag));
        }
    }
}
