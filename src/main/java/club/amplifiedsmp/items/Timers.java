package club.amplifiedsmp.items;

import java.util.*;

/** Server tick time, so wall-clock jumps cannot bypass ability cooldowns. */
public final class Timers {
    private record Key(UUID player, String ability) {}
    private final Map<Key, Long> ends = new HashMap<>();
    public long remaining(UUID player, String ability, long now) {
        return Math.max(0, ends.getOrDefault(new Key(player, ability), now) - now);
    }
    public void start(UUID player, String ability, long now, double seconds) {
        ends.put(new Key(player, ability), now + (long) Math.ceil(seconds * 20));
    }
    public void prune(long now) { ends.values().removeIf(end -> end <= now); }
    public void clear() { ends.clear(); }
}
