package gg.hcfrevival.revival.auth;

import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.SharedConstants;
import org.bukkit.Bukkit;
import org.jspecify.annotations.Nullable;

/**
 * Who can be under authority (§15.3): a real connection speaking this server's protocol. Players translated by
 * ViaVersion from another version, and Bedrock players through Geyser/Floodgate, run a different client and stay off.
 * Both plugins are optional, so they are reached by reflection.
 */
final class Eligibility {

    private Eligibility() {
    }

    /** Why ViaVersion says this player's client is another version, or null. */
    static @Nullable String viaVersionMismatch(final UUID player) {
        final org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin("ViaVersion");
        if (plugin == null) {
            return null;
        }
        try {
            // Through the plugin's own class loader: the server's can't see plugin classes
            final Class<?> via = Class.forName("com.viaversion.viaversion.api.Via", true, plugin.getClass().getClassLoader());
            final Object api = via.getMethod("getAPI").invoke(null);
            final Method version = api.getClass().getMethod("getPlayerVersion", UUID.class);
            final int protocol = (int) version.invoke(api, player);
            if (protocol > 0 && protocol != SharedConstants.getProtocolVersion()) {
                return "client protocol " + protocol + " through ViaVersion (server " + SharedConstants.getProtocolVersion() + ")";
            }
        } catch (final ReflectiveOperationException | ClassCastException | LinkageError e) {
            return null;
        }
        return null;
    }

    /** Whether Floodgate says this is a Bedrock player. */
    static boolean isBedrock(final UUID player) {
        final org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin("floodgate");
        if (plugin == null) {
            return false;
        }
        try {
            final Class<?> floodgate = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true, plugin.getClass().getClassLoader());
            final Object api = floodgate.getMethod("getInstance").invoke(null);
            return (boolean) floodgate.getMethod("isFloodgatePlayer", UUID.class).invoke(api, player);
        } catch (final ReflectiveOperationException | ClassCastException | LinkageError e) {
            return false;
        }
    }
}
