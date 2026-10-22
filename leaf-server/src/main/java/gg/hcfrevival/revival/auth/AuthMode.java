package gg.hcfrevival.revival.auth;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** {@code authoritative.mode}: who owns a player's movement and hits. */
public enum AuthMode {
    /** Vanilla/Paper movement handling; no hook acts. */
    OFF,
    /** Everything runs (simulation, transactions, statistics) but the client's claim stays the truth. */
    OBSERVE,
    /** The simulation is the truth: corrections and hit rejection are on. */
    ENFORCE;

    public String configName() {
        return this.name().toLowerCase(Locale.ROOT);
    }

    public static @Nullable AuthMode parse(final String name) {
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "off", "false" -> OFF;
            case "observe" -> OBSERVE;
            case "enforce", "on", "true" -> ENFORCE;
            default -> null;
        };
    }
}
