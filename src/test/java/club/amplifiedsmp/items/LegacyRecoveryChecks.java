package club.amplifiedsmp.items;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/** Checks the recovery policy against 26.3 entity classes without starting a server. */
public final class LegacyRecoveryChecks {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    public static void main(String[] args) throws Exception {
        Method policy;
        try {
            policy = Class.forName("club.amplifiedsmp.items.LegacyRecoveryPolicy")
                .getDeclaredMethod("allowsAutomaticDryLandReturn", Class.class);
        } catch (ClassNotFoundException | NoSuchMethodException missingPolicy) {
            throw new AssertionError("STORED cod must be deferred before committing a dry-land return; production has no legacy habitat policy");
        }
        for (String type : new String[] {
                "fish.Cod", "fish.Salmon", "fish.Pufferfish", "fish.TropicalFish", "frog.Tadpole",
                "axolotl.Axolotl", "dolphin.Dolphin", "squid.Squid", "squid.GlowSquid",
                "nautilus.Nautilus", "nautilus.ZombieNautilus"}) {
            Class<?> mob = Class.forName("net.minecraft.world.entity.animal." + type, false,
                LegacyRecoveryChecks.class.getClassLoader());
            check(!(boolean) policy.invoke(null, mob), type + " must retain its snapshot rather than return onto dry land");
        }
        for (String type : new String[] {"wolf.Wolf", "cow.Cow", "golem.IronGolem"}) {
            Class<?> mob = Class.forName("net.minecraft.world.entity.animal." + type, false,
                LegacyRecoveryChecks.class.getClassLoader());
            check((boolean) policy.invoke(null, mob), type + " must still be eligible for collision-checked land recovery");
        }
        // The guard must run before both STORED planning and RETURNING completion.
        String source = Files.readString(Path.of("src/main/java/club/amplifiedsmp/items/Summons.java"));
        String recovery = source.substring(source.indexOf("private void recoverStoredPets()"), source.indexOf("public void recallOwner"));
        int guard = recovery.indexOf("if (!LegacyRecoveryPolicy.allowsAutomaticDryLandReturn(probe.getClass())) continue;");
        check(guard >= 0 && guard < recovery.indexOf("if (entry.state == SummonLedger.State.STORED)"),
            "Habitat guard must defer both STORED and RETURNING before either ledger transition, including an already-present recovery UUID");
        System.out.println("AMPLIFIED_LEGACY_RECOVERY_OK " + checks);
    }
}
