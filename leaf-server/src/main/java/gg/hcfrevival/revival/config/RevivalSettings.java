package gg.hcfrevival.revival.config;

/**
 * An immutable snapshot of revival.yml. A reload builds a new snapshot and swaps it in whole, so code
 * reading settings during a tick never sees half of an old file and half of a new one.
 *
 * @param configVersion the {@code config-version} the file was written with
 */
public record RevivalSettings(int configVersion) {

    public static final RevivalSettings DEFAULTS = new RevivalSettings(RevivalConfig.CURRENT_VERSION);
}
