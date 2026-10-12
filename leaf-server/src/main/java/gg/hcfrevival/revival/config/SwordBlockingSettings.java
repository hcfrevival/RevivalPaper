package gg.hcfrevival.revival.config;

/**
 * The global {@code sword-blocking} section. Global only: the item encoder that decorates swords for clients has no
 * per-player or per-world context.
 *
 * @param enabled legacy weapons block on right click, as in 1.7.10
 * @param clientDecoration send clients a blocking component on legacy weapons so they show and predict the block
 * @param damageOffset a blocked hit takes {@code (damage + offset) × factor}
 * @param damageFactor see {@code damageOffset}; 1.7.10 used {@code (1 + damage) × 0.5}
 * @param ignoreArmorBypassingDamage fall, fire ticks, magic and other armor-bypassing damage isn't reduced, as in 1.7.10
 * @param useSpeedMultiplier movement speed while blocking, sent to clients with the decoration
 * @param rearmSprintOnBlock starting a block re-arms the sprint knock, like the 1.7 block-hit
 */
public record SwordBlockingSettings(
    boolean enabled,
    boolean clientDecoration,
    double damageOffset,
    double damageFactor,
    boolean ignoreArmorBypassingDamage,
    double useSpeedMultiplier,
    boolean rearmSprintOnBlock
) {

    static final String PATH = "sword-blocking.";

    static SwordBlockingSettings read(final ConfigReader reader) {
        return new SwordBlockingSettings(
            reader.getBoolean(PATH + "enabled", true),
            reader.getBoolean(PATH + "client-decoration", true),
            reader.getDouble(PATH + "damage-offset", 1.0),
            reader.getDouble(PATH + "damage-factor", 0.5),
            reader.getBoolean(PATH + "ignore-armor-bypassing-damage", true),
            Math.clamp(reader.getDouble(PATH + "use-speed-multiplier", 0.2), 0.0, 1.0),
            reader.getBoolean(PATH + "rearm-sprint-on-block", true)
        );
    }
}
