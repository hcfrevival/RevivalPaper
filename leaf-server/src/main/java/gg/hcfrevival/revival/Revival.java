package gg.hcfrevival.revival;

import gg.hcfrevival.revival.config.RevivalConfig;
import gg.hcfrevival.revival.config.RevivalSettings;
import net.kyori.adventure.key.Key;

/**
 * The one place patched Minecraft code and plugins call into RevivalPaper.
 * <p>
 * Patches reach the combat layer only through this class, so a Minecraft update moves hook sites
 * but never the logic behind them. Plugins can detect the fork without compiling against it with
 * {@code ServerBuildInfo.buildInfo().brandId()}, which returns {@link #BRAND_ID}.
 */
public final class Revival {

    public static final Key BRAND_ID = Key.key("hcfrevival", "revivalpaper");
    public static final String BRAND_NAME = "RevivalPaper";

    private Revival() {
    }

    /** The live revival.yml settings. */
    public static RevivalSettings settings() {
        return RevivalConfig.settings();
    }

    /** Whether hits with a legacy weapon use 1.7.10 combat in this world. */
    public static boolean legacyCombat(final org.bukkit.World world) {
        return settings().legacyCombat(world.getName()).enabled();
    }

    /** Connects {@link gg.hcfrevival.revival.api.RevivalCombat} to this server; called once at startup. */
    public static void startApi() {
        gg.hcfrevival.revival.api.RevivalCombat.setProvider(new RevivalApi());
    }
}
