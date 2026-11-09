package club.amplifiedsmp.items;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class WebWalk {
    private record Mask(ServerLevel level, Set<BlockPos> positions) {}
    private static final Map<UUID, Mask> MASKS = new HashMap<>();

    private WebWalk() {}

    static void tick(ServerPlayer player, boolean enabled) {
        if (!enabled) {
            clear(player);
            return;
        }
        ServerLevel level = player.level();
        Mask previous = MASKS.get(player.getUUID());
        Set<BlockPos> oldPositions = previous != null && previous.level() == level ? previous.positions() : Set.of();
        Set<BlockPos> nearby = new HashSet<>();
        var box = player.getBoundingBox().inflate(0.75);
        var min = BlockPos.containing(box.minX, box.minY, box.minZ);
        var max = BlockPos.containing(box.maxX, box.maxY, box.maxZ);
        for (BlockPos position : BlockPos.betweenClosed(min, max)) {
            if (!level.hasChunkAt(position) || !level.getBlockState(position).is(Blocks.COBWEB) || !tracked(player, position)) continue;
            BlockPos web = position.immutable();
            nearby.add(web);
            // The client predicts web slowdown, so show air to this player while the blade is held.
            if (!oldPositions.contains(web) || AmplifiedItems.tick % 5 == 0) {
                player.connection.send(new ClientboundBlockUpdatePacket(web, Blocks.AIR.defaultBlockState()));
            }
        }
        for (BlockPos position : oldPositions) {
            if (!nearby.contains(position)) restore(player, position);
        }
        if (nearby.isEmpty()) MASKS.remove(player.getUUID());
        else MASKS.put(player.getUUID(), new Mask(level, nearby));
    }

    private static boolean tracked(ServerPlayer player, BlockPos position) {
        return player.level().getChunkSource().chunkMap.isChunkTracked(player, position.getX() >> 4, position.getZ() >> 4);
    }

    private static void restore(ServerPlayer player, BlockPos position) {
        if (player.level().hasChunkAt(position) && tracked(player, position)) {
            player.connection.send(new ClientboundBlockUpdatePacket(player.level(), position));
        }
    }

    static void clear(ServerPlayer player) {
        Mask mask = MASKS.remove(player.getUUID());
        if (mask != null && mask.level() == player.level()) {
            for (BlockPos position : mask.positions()) restore(player, position);
        }
    }

    static void reset() {
        MASKS.clear();
    }
}
