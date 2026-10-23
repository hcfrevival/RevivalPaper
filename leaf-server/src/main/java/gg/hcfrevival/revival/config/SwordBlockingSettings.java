package gg.hcfrevival.revival.config;

/**
 * The global {@code sword-blocking} section. Global only: the item encoder that decorates swords for clients has no
 * per-player or per-world context.
 *
 * @param enabled legacy weapons block on right click, as in 1.7.10
 * @param clientDecoration send clients legacy weapons that are "edible" with the block animation, so they show and predict the block
 * @param damageOffset a blocked hit takes {@code (damage + offset) × factor}
 * @param damageFactor see {@code damageOffset}; 1.7.10 used {@code (1 + damage) × 0.5}
 * @param ignoreArmorBypassingDamage fall, fire ticks, magic and other armor-bypassing damage isn't reduced, as in 1.7.10
 * @param useSpeedMultiplier movement speed while blocking, sent to clients with the decoration
 * @param rearmSprintOnBlock starting a block re-arms the sprint knock, like the 1.7 block-hit
 * @param stopSprintOnBlock under authority (enforce), a legacy block held {@code stopSprintAfterTicks} stops the player's
 *                          sprint, as 1.7 did (its blocking walk, x0.2, fell under the 0.8 sprint threshold): a 26.3 client
 *                          keeps sprinting through a block (about 30% faster than 1.7's blocking walk, and the sprint-jump
 *                          boost). Off by default (classic audit #6)
 * @param stopSprintAfterTicks the block's tick that stops the sprint. The stop is a server write the client gets a round
 *                             trip later; sent at a block-hit's first tick, it landed after the release at 250-300 ms and
 *                             cancelled the re-sprint in the tick of the next hit, which went out without the sprint knock
 *                             (1 in 4). A block-hit shorter than this keeps 26.3's sprint for its tick or two and gets its
 *                             knock from {@code rearmSprintOnBlock}; a block held longer walks as in 1.7
 */
public record SwordBlockingSettings(
    boolean enabled,
    boolean clientDecoration,
    double damageOffset,
    double damageFactor,
    boolean ignoreArmorBypassingDamage,
    double useSpeedMultiplier,
    boolean rearmSprintOnBlock,
    boolean stopSprintOnBlock,
    int stopSprintAfterTicks
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
            reader.getBoolean(PATH + "rearm-sprint-on-block", true),
            reader.getBoolean(PATH + "stop-sprint-on-block", false),
            Math.max(1, reader.getInt(PATH + "stop-sprint-after-ticks", 4))
        );
    }
}
