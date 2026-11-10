package club.amplifiedsmp.items;

import java.nio.file.*;
import java.util.*;

/** Standalone assertions: run with java -ea (also wired into Gradle check). */
public final class LogicChecks {
    private static int checks;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static void reject(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException e) { checks++; return; }
        throw new AssertionError("Expected invalid configuration to be rejected");
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("amplified-items-tests-");
        try {
            var config = WeaponConfig.load(root.resolve("config/config.json"));
            check(config.weapons.size()==10,"Ten default weapons");
            check(config.executionSeconds==30 && config.venomPoisonSeconds==10,"Requested effect durations");
            check(WeaponConfig.load(root.resolve("config/config.json")).weapons.equals(config.weapons),"Config round-trip");
            config.executionMultiplier=Double.NaN; reject(config::validate); config.executionMultiplier=1.3;
            config.authorityReduction=1; reject(config::validate); config.authorityReduction=.1;
            config.weapons.add(config.weapons.getFirst()); reject(config::validate); config.weapons.removeLast();
            var custom=new WeaponDefinition("test_staff","Test Staff","minecraft:stick","amplified_items:kings_decree","rulers_command","grapple_hook","none",2,4,List.of("Test"));
            config.weapons.add(custom); config.validate(); check(config.weapons.size()==11,"Additional JSON weapon definition");
            config.resourcePackPublicUrl="file:///private"; reject(config::validate); config.resourcePackPublicUrl="https://example.org/weapons.zip"; config.validate();
            var previous = WeaponConfig.JSON.toJsonTree(config).getAsJsonObject();
            previous.remove("configVersion");
            previous.add("weapons", WeaponConfig.JSON.toJsonTree(List.of(config.weapons.get(0), config.weapons.get(1), custom)));
            previous.addProperty("venomRadius", 7);
            Path oldConfig = root.resolve("config/previous.json");
            Files.writeString(oldConfig, WeaponConfig.JSON.toJson(previous));
            var upgraded = WeaponConfig.load(oldConfig);
            check(upgraded.weapons.size() == 11 && upgraded.venomRadius == 7, "Upgrade adds new weapons and preserves settings");
            check(WeaponConfig.load(oldConfig).weapons.size() == 11, "Upgrade runs once");
            var oldV3 = WeaponConfig.JSON.toJsonTree(new WeaponConfig()).getAsJsonObject();
            oldV3.addProperty("configVersion", 3);
            oldV3.addProperty("grappleRange", 40);
            var king = oldV3.getAsJsonArray("weapons").get(0).getAsJsonObject();
            king.remove("enabled");
            king.remove("ritual");
            king.add("lore", WeaponConfig.JSON.toJsonTree(List.of("Right-click: summon / recall", "Custom note")));
            oldV3.getAsJsonArray("weapons").get(1).getAsJsonObject().addProperty("enabled", false);
            Path v3Path = root.resolve("config/v3.json");
            Files.writeString(v3Path, WeaponConfig.JSON.toJson(oldV3));
            var v7 = WeaponConfig.load(v3Path);
            check(v7.configVersion == 7 && v7.grappleRange == 16, "Old grapple ranges are clamped during migration");
            check(v7.enabledWeapon("kings_decree").ritual().ingredients().get(RitualRecipe.PVP_HEAD) == 2 && v7.enabledWeapon("venombolt") == null,
                "Legacy weapons get rituals and enabled defaults while explicit disables survive");
            check(v7.enabledWeapon("kings_decree").lore().equals(List.of("summon / recall", "Custom note")), "Control lore migrates");
            check(WeaponConfig.abilityLore("Sneak + F: Execution").equals("Execution")
                && WeaponConfig.abilityLore("Tap right-click: Slow Falling").equals("Tap right-click: Slow Falling"),
                "Legacy menu lore changes while vanilla use instructions remain");
            v7.grappleRange = 17; reject(v7::validate);
            reject(() -> new RitualRecipe(Map.of("minecraft:diamond", 0)).validate("test"));
            var oldV5 = WeaponConfig.JSON.toJsonTree(config).getAsJsonObject();
            oldV5.addProperty("configVersion", 5);
            var oldKing = oldV5.getAsJsonArray("weapons").get(0).getAsJsonObject();
            oldKing.add("ritual", WeaponConfig.JSON.toJsonTree(new RitualRecipe(Map.of("minecraft:iron_sword", 1))));
            oldKing.addProperty("primaryCooldownSeconds", 25);
            Path v5Path = root.resolve("config/v5.json");
            Files.writeString(v5Path, WeaponConfig.JSON.toJson(oldV5));
            var migrated = WeaponConfig.load(v5Path);
            var migratedKing = migrated.enabledWeapon("kings_decree");
            check(migratedKing.ritual().ingredients().equals(Map.of(
                "amplified_items:kings_crown", 1, "minecraft:netherite_ingot", 8, "minecraft:diamond_block", 16, "minecraft:nether_star", 1, RitualRecipe.PVP_HEAD, 2)),
                "The update replaces old built-in recipe ingredients");
            check(migratedKing.primaryCooldownSeconds() == 25
                && migrated.enabledWeapon("test_staff").ritual().equals(custom.ritual()),
                "The update preserves weapon tuning and additional weapons");
            oldV5.addProperty("configVersion", 6);
            Files.writeString(v5Path, WeaponConfig.JSON.toJson(oldV5));
            var altarUpgrade = WeaponConfig.load(v5Path);
            check(altarUpgrade.enabledWeapon("kings_decree").ritual().ingredients()
                .equals(Map.of("minecraft:iron_sword", 1, RitualRecipe.PVP_HEAD, 2)),
                "An existing custom ingredient list gains only the new head cost");
            check(altarUpgrade.weapons.stream().filter(w -> w.ritual().ingredients().containsKey(RitualRecipe.PVP_HEAD)).count() == 6,
                "Six built-in recipes require PvP heads");
            var timers=new Timers(); UUID p=UUID.randomUUID(), other=UUID.randomUUID();
            timers.start(p,"execution",100,45);
            check(timers.remaining(p,"execution",100)==900,"45 second cooldown in ticks");
            check(timers.remaining(other,"execution",100)==0,"Cooldowns are per player");
            check(timers.remaining(p,"venom_shot",100)==0,"Cooldowns are per ability");
            check(timers.remaining(p,"execution",1000)==0,"Cooldown expires exactly at boundary");
            timers.prune(1001); check(timers.remaining(p,"execution",1001)==0,"Expired cooldown pruning");
            var ledger=new SummonLedger(root.resolve("world/summons.json"));
            UUID staff=UUID.randomUUID(), mob=UUID.randomUUID();
            ledger.transaction(() -> { ledger.put(new SummonLedger.Entry(staff,p,mob,"{id:\"minecraft:cow\",Health:7f}",SummonLedger.State.STORED)); ledger.retire(mob); });
            var reopened=new SummonLedger(root.resolve("world/summons.json"));
            check(reopened.get(staff).state==SummonLedger.State.STORED && reopened.retired(mob),"Capture and retired ID persist together");
            UUID summoned=UUID.randomUUID();
            reopened.transaction(() -> { var e=reopened.get(staff); e.state=SummonLedger.State.ACTIVE; e.entity=summoned; });
            var again=new SummonLedger(root.resolve("world/summons.json"));
            check(again.get(staff).state==SummonLedger.State.ACTIVE && again.get(staff).entity.equals(summoned),"Active state persists");
            again.transaction(() -> {again.get(staff).state=SummonLedger.State.DEAD;again.retire(summoned);});
            check(new SummonLedger(root.resolve("world/summons.json")).get(staff).state==SummonLedger.State.DEAD,"Dead summon never falls back to stored");
            check(Files.exists(root.resolve("world/summons.json.bak")),"Ledger keeps a previous successful backup");
            try {again.transaction(() -> {again.get(staff).state=SummonLedger.State.STORED;throw new IllegalArgumentException("deliberate failure");});}
            catch (IllegalArgumentException expected) {}
            check(again.get(staff).state==SummonLedger.State.DEAD,"Failed transaction rolls back in-memory state");
            String broken="not valid json";Files.writeString(root.resolve("world/broken.json"),broken);
            try {new SummonLedger(root.resolve("world/broken.json"));throw new AssertionError("Corruption must fail closed");}
            catch (com.google.gson.JsonParseException | java.io.IOException expected) {checks++;}
            check(Files.readString(root.resolve("world/broken.json")).equals(broken),"Corrupt ledger is never overwritten");
            System.out.println("AMPLIFIED_LOGIC_OK " + checks);
        } finally {
            try(var paths=Files.walk(root)) {for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);}
        }
    }
}
