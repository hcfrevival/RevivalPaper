package gg.hcfrevival.revival.api;

import java.util.Set;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * RevivalPaper's 1.7.10 combat layer. Plugins can check for the fork with
 * {@code ServerBuildInfo.buildInfo().brandId()}, which is {@code hcfrevival:revivalpaper}, before calling this.
 */
@NullMarked
public final class RevivalCombat {

    private static @Nullable Provider provider;

    private RevivalCombat() {
    }

    /** Whether hits with a legacy weapon use 1.7.10 combat in this world (revival.yml can turn it off per world). */
    public static boolean legacyCombat(final World world) {
        return provider().legacyCombat(world);
    }

    /** Every knockback profile name: the built-in presets and those in revival.yml or made with {@code /revival kb}. */
    public static Set<String> knockbackProfiles() {
        return provider().knockbackProfiles();
    }

    /** The knockback profile the player's legacy hits use: their own, if set, otherwise their world's. */
    public static String getKnockbackProfile(final Player player) {
        return provider().getKnockbackProfile(player);
    }

    /**
     * Gives the player's legacy hits their own knockback profile, for example per arena ladder, until it is cleared
     * with null or the player leaves. Not saved.
     *
     * @throws IllegalArgumentException if no profile has that name
     */
    public static void setKnockbackProfile(final Player player, final @Nullable String profile) {
        provider().setKnockbackProfile(player, profile);
    }

    private static Provider provider() {
        final Provider current = provider;
        if (current == null) {
            throw new IllegalStateException("RevivalPaper's combat layer has not started");
        }
        return current;
    }

    @ApiStatus.Internal
    public static void setProvider(final Provider provider) {
        RevivalCombat.provider = provider;
    }

    @ApiStatus.Internal
    public interface Provider {

        boolean legacyCombat(World world);

        Set<String> knockbackProfiles();

        String getKnockbackProfile(Player player);

        void setKnockbackProfile(Player player, @Nullable String profile);
    }
}
