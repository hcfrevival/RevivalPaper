package gg.hcfrevival.revival;

import gg.hcfrevival.revival.api.RevivalCombat;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** The server side of {@link RevivalCombat}. */
final class RevivalApi implements RevivalCombat.Provider {

    @Override
    public boolean legacyCombat(final World world) {
        return Revival.legacyCombat(world);
    }

    @Override
    public Set<String> knockbackProfiles() {
        return Set.copyOf(Revival.settings().knockbackProfiles().keySet());
    }

    @Override
    public String getKnockbackProfile(final Player player) {
        final ServerPlayer handle = ((CraftPlayer) player).getHandle();
        return gg.hcfrevival.revival.combat.LegacyKnockback.profileName(handle, Revival.settings().legacyCombat(player.getWorld().getName()));
    }

    @Override
    public void setKnockbackProfile(final Player player, final @Nullable String profile) {
        if (profile != null && Revival.settings().knockbackProfile(profile) == null) {
            throw new IllegalArgumentException("no knockback profile named " + profile);
        }
        ((CraftPlayer) player).getHandle().revivalKnockbackProfile = profile;
    }
}
