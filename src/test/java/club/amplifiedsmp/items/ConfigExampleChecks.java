package club.amplifiedsmp.items;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;

/** Keep the distributed configuration example synchronized with Java defaults. */
public final class ConfigExampleChecks {
    public static void main(String[] args) throws Exception {
        Path target = Path.of("config.example.json");
        var defaults = WeaponConfig.JSON.toJsonTree(new WeaponConfig());
        if (args.length == 1 && args[0].equals("--write-config")) {
            Files.writeString(target, WeaponConfig.JSON.toJson(defaults) + "\n");
            System.out.println("Exported " + target);
            return;
        }
        if (!JsonParser.parseString(Files.readString(target)).equals(defaults)) {
            throw new AssertionError("config.example.json differs from actual WeaponConfig defaults; run exportExampleConfig");
        }
        System.out.println("AMPLIFIED_CONFIG_EXAMPLE_OK 1");
    }
}
