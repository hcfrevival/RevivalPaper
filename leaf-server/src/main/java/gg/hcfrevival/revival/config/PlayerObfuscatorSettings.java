package gg.hcfrevival.revival.config;

/**
 * The global {@code player-obfuscator} section: a sneaking or invisible player that a viewer could not see through
 * blocks is not sent to that viewer's client at all, so ESP and x-ray cheats have nothing to draw.
 *
 * @param enabled whether the obfuscator runs
 * @param hideDelayTicks consecutive occluded ticks before a player is withheld; a clear line reveals them at once
 * @param leadTicks ticks of movement both players are extrapolated by, so a player rounding a corner is already spawned
 * @param cameraDistance how far the third-person camera is assumed to reach behind and in front of a viewer; 0 turns it off
 * @param blockChecksPerTick block lookups each sneaking or invisible player may spend per tick; pairs past it keep last tick's
 *     answer until the next tick, so a crowd costs a bounded slice of the tick. 0 is no limit
 */
public record PlayerObfuscatorSettings(boolean enabled, int hideDelayTicks, int leadTicks, double cameraDistance, int blockChecksPerTick) {

    static final String PATH = "player-obfuscator.";

    static PlayerObfuscatorSettings read(final ConfigReader reader) {
        return new PlayerObfuscatorSettings(
            reader.getBoolean(PATH + "enabled", false),
            nonNegative(reader, "hide-delay-ticks", 10),
            nonNegative(reader, "lead-ticks", 6),
            Math.max(0.0, reader.getDouble(PATH + "camera-distance", 4.0)),
            nonNegative(reader, "block-checks-per-tick", 8000)
        );
    }

    private static int nonNegative(final ConfigReader reader, final String key, final int def) {
        final int value = reader.getInt(PATH + key, def);
        if (value < 0) {
            reader.problem(PATH + key, Integer.toString(value), "0 or more", Integer.toString(def));
            return def;
        }
        return value;
    }
}
