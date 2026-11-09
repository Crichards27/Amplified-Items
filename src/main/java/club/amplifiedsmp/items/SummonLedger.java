package club.amplifiedsmp.items;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Authoritative per-world state. Cloned staff items share a record, never clone a mob. */
public final class SummonLedger {
    public enum State { STORED, ACTIVE, DEAD }
    public static final class Entry {
        public UUID staff;
        public UUID owner;
        public UUID entity;
        public String snapshot;
        public State state;
        public Entry(UUID staff, UUID owner, UUID entity, String snapshot, State state) {
            this.staff = staff; this.owner = owner; this.entity = entity; this.snapshot = snapshot; this.state = state;
        }
    }
    public static final class Data {
        public int format = 1;
        public Map<UUID, Entry> entries = new HashMap<>();
        public Set<UUID> retired = new HashSet<>();
    }
    private final Path path;
    private Data data;
    public SummonLedger(Path path) throws IOException {
        this.path = path;
        Files.createDirectories(path.getParent());
        data = Files.exists(path) ? WeaponConfig.JSON.fromJson(Files.readString(path), Data.class) : new Data();
        if (data == null || data.format != 1 || data.entries == null || data.retired == null)
            throw new IOException("Invalid summon ledger: " + path + ". Restore its backup; it will not be overwritten.");
        for (var e : data.entries.entrySet()) {
            var v = e.getValue();
            if (v == null || v.staff == null || !e.getKey().equals(v.staff) || v.state == null || v.entity == null || v.snapshot == null)
                throw new IOException("Malformed summon record in " + path);
        }
    }
    public Entry get(UUID staff) { return data.entries.get(staff); }
    public Collection<Entry> entries() { return data.entries.values(); }
    public boolean retired(UUID entity) { return data.retired.contains(entity); }
    public void put(Entry entry) { data.entries.put(entry.staff, entry); }
    public void retire(UUID entity) { data.retired.add(entity); }
    public void transaction(Runnable mutation) throws IOException {
        Data previous = WeaponConfig.JSON.fromJson(WeaponConfig.JSON.toJson(data), Data.class);
        try { mutation.run(); save(); }
        catch (IOException | RuntimeException e) { data = previous; throw e; }
    }
    public void save() throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, WeaponConfig.JSON.toJson(data), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try (var channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) { channel.force(true); }
        if (Files.exists(path)) Files.copy(path, path.resolveSibling(path.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        try { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING); }
    }
}
