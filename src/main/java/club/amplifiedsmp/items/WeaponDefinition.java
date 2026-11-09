package club.amplifiedsmp.items;

import java.util.List;

/** JSON data, deliberately independent of Minecraft registries. */
public record WeaponDefinition(String id, String name, String baseItem, String model,
                               String primary, String secondary, String passive,
                               double primaryCooldownSeconds, double secondaryCooldownSeconds,
                               List<String> lore, Boolean enabled, RitualRecipe ritual) {
    public WeaponDefinition {
        if (enabled == null) enabled = true;
        if (ritual == null) ritual = RitualRecipe.defaults(id, baseItem);
    }

    public WeaponDefinition(String id, String name, String baseItem, String model,
                            String primary, String secondary, String passive,
                            double primaryCooldownSeconds, double secondaryCooldownSeconds, List<String> lore) {
        this(id, name, baseItem, model, primary, secondary, passive, primaryCooldownSeconds, secondaryCooldownSeconds,
            lore, true, null);
    }

    public void validate() {
        if (id == null || !id.matches("[a-z0-9_]+")) throw new IllegalArgumentException("Invalid weapon id: " + id);
        if (name == null || name.isBlank() || baseItem == null || model == null || lore == null)
            throw new IllegalArgumentException("Missing fields on weapon " + id);
        if (!Double.isFinite(primaryCooldownSeconds) || primaryCooldownSeconds < 0 || primaryCooldownSeconds > 86400
                || !Double.isFinite(secondaryCooldownSeconds) || secondaryCooldownSeconds < 0 || secondaryCooldownSeconds > 86400)
            throw new IllegalArgumentException("Cooldown must be 0..86400 seconds: " + id);
        ritual.validate(id);
    }
}
