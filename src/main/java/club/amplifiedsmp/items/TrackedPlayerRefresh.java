package club.amplifiedsmp.items;

import net.minecraft.server.level.ServerPlayer;
import java.util.List;

public interface TrackedPlayerRefresh {
    void amplifiedItems$refresh(List<ServerPlayer> viewers);
}
