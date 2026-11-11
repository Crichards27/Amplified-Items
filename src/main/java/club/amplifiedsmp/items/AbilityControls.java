package club.amplifiedsmp.items;

import java.util.List;
import java.util.Locale;

final class AbilityControls {
    private static final String NORMAL_USE = "none";
    private static final String CRIT_COMBO = "crit_combo";

    private AbilityControls() {}

    static boolean castable(String ability) {
        return !ability.equals(NORMAL_USE) && !ability.equals(CRIT_COMBO);
    }

    static String label(String ability) {
        if (ability.equals(NORMAL_USE)) return "Normal item use";
        if (ability.equals("rulers_command")) return "Ruler's Command";
        StringBuilder name = new StringBuilder();
        for (String word : ability.split("_")) {
            if (word.isEmpty()) continue;
            if (!name.isEmpty()) name.append(' ');
            name.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return name.toString();
    }

    static List<String> description(String ability) {
        var config = AmplifiedItems.CONFIG;
        return switch (ability) {
            case "crit_combo" -> List.of("Automatic. Land " + config.spiderComboHits + " critical hits on one enemy.",
                "Applies Poison II for " + config.spiderPoisonSeconds + "s.");
            case "rulers_command" -> List.of("Capture a mob within 5 blocks.", "Cast again to summon or recall it.");
            case "execution" -> List.of("Mark an enemy for " + config.executionSeconds + "s.",
                "The marked enemy takes " + Math.round((config.executionMultiplier - 1) * 100) + "% more damage.");
            case "venom_shot" -> List.of("Fire an arrow that splashes nearby enemies.",
                "Applies Poison II for " + config.venomPoisonSeconds + "s.");
            case "grapple_hook", "web_grapple" -> List.of("Pull yourself to a block or pull an enemy to you.",
                "Range up to " + (long) config.grappleRange + " blocks.");
            case "soul_stealer" -> List.of("Drain health from a nearby enemy.", "Heal for the damage dealt.");
            case "shadow_step" -> List.of("Hide yourself, armor and held items",
                "for " + config.shadowStepSeconds + "s.");
            case "flaming_burst" -> List.of("Shoot a blazing arrow.", "Deals impact damage and ignites the target.");
            case "solar_flare" -> List.of("Shoot an arrow that bursts on impact.",
                "Blinds nearby enemies for " + config.solarFlareBlindnessSeconds + "s.");
            case "tide" -> List.of("Send a wave of water forward.", "Enemies hit take drowning damage.");
            case "riptide" -> List.of("Dash without water for " + config.riptideSeconds + "s.",
                "Dash again up to once per second.");
            case "phantom_phase" -> List.of("Become fully invisible and fly",
                "with damage immunity for " + config.phantomPhaseSeconds + "s.");
            case "hollow_moon" -> List.of("Gain Speed III and Strength II",
                "for " + config.hollowMoonSeconds + "s.");
            case "heatwave" -> List.of("Sweep fire through enemies in front of you.",
                "The burn also hurts enemies with Fire Resistance.");
            case "sunflare" -> List.of("Call down sunlight around yourself.",
                "Damage, knock back and ignite nearby enemies.");
            case "windburst" -> List.of("Push nearby enemies away from you.");
            case "updraft" -> List.of("Gain Speed II and Jump Boost",
                "for " + config.updraftSeconds + "s.");
            case "blood_rush" -> List.of("Gain Speed II and Strength I",
                "for " + config.bloodRushSeconds + "s.");
            case "crimson_cleave" -> List.of("Strike enemies in front of you with a heavy swing.");
            case NORMAL_USE -> List.of("Use the item normally, including drawing bows",
                "and loading crossbows. Sneak + right-click also works.");
            default -> List.of("Use this weapon's " + label(ability) + " ability.");
        };
    }

}
