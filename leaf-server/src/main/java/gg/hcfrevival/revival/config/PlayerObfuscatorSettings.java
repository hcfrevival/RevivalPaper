package gg.hcfrevival.revival.config;

/**
 * The global {@code player-obfuscator} section: a sneaking or invisible player that a viewer could not see through
 * blocks is not sent to that viewer's client at all, so ESP and x-ray cheats have nothing to draw.
 *
 * @param enabled whether the obfuscator runs
 * @param hideDelayTicks consecutive occluded ticks before a player is withheld; a clear line reveals them at once
 * @param leadTicks ticks of movement both players are extrapolated by, so a player rounding a corner is already spawned
 * @param cameraDistance how far the third-person camera is assumed to reach behind and in front of a viewer; 0 turns it off
 */
public record PlayerObfuscatorSettings(boolean enabled, int hideDelayTicks, int leadTicks, double cameraDistance) {

    static final String PATH = "player-obfuscator.";

    static PlayerObfuscatorSettings read(final ConfigReader reader) {
        return new PlayerObfuscatorSettings(
            reader.getBoolean(PATH + "enabled", false),
            ticks(reader, "hide-delay-ticks", 10),
            ticks(reader, "lead-ticks", 6),
            Math.max(0.0, reader.getDouble(PATH + "camera-distance", 4.0))
        );
    }

    private static int ticks(final ConfigReader reader, final String key, final int def) {
        final int ticks = reader.getInt(PATH + key, def);
        if (ticks < 0) {
            reader.problem(PATH + key, Integer.toString(ticks), "0 or more ticks", Integer.toString(def));
            return def;
        }
        return ticks;
    }
}
