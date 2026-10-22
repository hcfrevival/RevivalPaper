package gg.hcfrevival.revival.livetest;

import gg.hcfrevival.revival.Revival;
import gg.hcfrevival.revival.config.CombatRules;
import java.io.File;
import java.io.IOException;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The rule set the cheat suites prove the netcode on: {@code -Drevival.livetest.rules} (the live runner's
 * {@code REVIVAL_LIVE_RULES}), classic when unset. {@code classic-capped} is the NAS world's: classic plus the netcode's
 * click cap (12 a second) and a 0.15 s click buffer. Its two keys are written into revival.yml here, so a run doesn't
 * depend on the built-in preset (with one, they are the preset's own values). Plain classic is the control run.
 */
final class LiveRules {

    static final String CLASSIC_CAPPED = "classic-capped";

    static final String MAIN = name(System.getProperty("revival.livetest.rules", ""));

    private LiveRules() {
    }

    private static String name(final String property) {
        return property.isBlank() ? CombatRules.DEFAULT : property.trim();
    }

    /** Whether the rule set caps and buffers clicks, so the click-cap and click-buffer rows apply to it. */
    static boolean capsAndBuffers(final String name) {
        return name.equals(CLASSIC_CAPPED) || name.equals("neue");
    }

    /** Switches the default world to {@code name} by editing revival.yml and reloading it, as an owner would. */
    static CombatRules apply(final ServerLevel level, final String name) {
        try {
            final File file = new File("revival.yml");
            final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            if (name.equals(CLASSIC_CAPPED)) {
                yaml.set("combat-rules." + CLASSIC_CAPPED + ".max-cps", 12.0);
                yaml.set("combat-rules." + CLASSIC_CAPPED + ".click-buffer-seconds", 0.15);
            }
            yaml.set("world-settings.default.legacy-combat.rules", name);
            yaml.save(file);
        } catch (final IOException failure) {
            throw new IllegalStateException(failure);
        }
        gg.hcfrevival.revival.config.RevivalConfig.reload();
        return Revival.settings().legacyCombat(level.getWorld().getName()).rules();
    }

    /** Whether {@code rules} is what {@code name} should have read as. */
    static boolean is(final CombatRules rules, final String name) {
        return switch (name) {
            case "classic" -> rules.equals(CombatRules.CLASSIC);
            case "neue" -> rules.equals(CombatRules.NEUE);
            case CLASSIC_CAPPED -> rules.maxCps() == 12.0 && rules.clickBufferTicks() == 3 && !rules.parry() && !rules.shapesKnock();
            default -> true; // a set the run's revival.yml defines
        };
    }
}
