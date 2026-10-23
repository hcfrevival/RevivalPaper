package gg.hcfrevival.revival.auth.view;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Which client a player runs, as far as the server can tell (design §15.8, client-compat §7 N1-N3, §8):
 * <ul>
 *   <li>the brand the client sent ({@code minecraft:brand}), classed vanilla, lunar, fabric, forge or other;</li>
 *   <li>whether it registered the {@code lunar:apollo} channel;</li>
 *   <li>the Apollo combat options the server pushed to that player ({@code CombatModule}: miss penalty, block miss
 *       penalty, dig and use), read from Apollo by reflection as {@code Eligibility} reaches ViaVersion: server truth,
 *       each with the window it was first seen in;</li>
 *   <li>the Lunar version and installed mods from the Apollo handshake (client-claimed).</li>
 * </ul>
 * One input rule reads it: {@code ATTACK_AFTER_CLOSE} doesn't hold while the server has turned the miss penalty off
 * through Apollo (client-compat L1: Lunar may land the first click after a screen, and the server chose to let it).
 * The rest is informational until a recorded Lunar session shows how Lunar behaves (client-compat §9.2). Only "this
 * is Lunar" is client-claimed; faking it gets the rules a Lunar player has, nothing looser. Main thread, one per
 * player.
 */
public final class ClientProfile {

    public enum Brand {
        UNKNOWN, VANILLA, LUNAR, FABRIC, FORGE, OTHER
    }

    private static final String APOLLO_CHANNEL = "lunar:apollo";
    private static final int MAX_MODS = 64;

    private String rawBrand = "";
    private Brand brand = Brand.UNKNOWN;
    private boolean apollo;
    private boolean missPenaltyDisabled;
    private boolean blockMissPenaltyDisabled;
    private boolean digAndUse;
    private int missPenaltySince = -1;
    private int blockMissPenaltySince = -1;
    private int digAndUseSince = -1;
    private String lunarVersion = "";
    private List<String> mods = List.of();
    private int nextRefresh;
    private boolean apolloFailed;

    /** Re-reads the profile every so often: often while the client may still announce itself or Apollo is present. */
    public void maybeRefresh(final ServerPlayer player, final int window, final boolean followApollo) {
        if (window < this.nextRefresh) {
            return;
        }
        this.refresh(player, window, followApollo);
        this.nextRefresh = window + (this.apollo || this.brand == Brand.UNKNOWN ? 20 : 200);
    }

    public void refresh(final ServerPlayer player, final int window, final boolean followApollo) {
        final org.bukkit.entity.Player bukkit = player.getBukkitEntity();
        final String sent = bukkit.getClientBrandName();
        if (sent != null && !sent.equals(this.rawBrand)) {
            this.rawBrand = sent;
            this.brand = classify(sent);
        }
        this.apollo = bukkit.getListeningPluginChannels().contains(APOLLO_CHANNEL);
        if (this.apollo && followApollo) {
            this.readApollo(player.getUUID(), window);
        }
    }

    /** The brand class of a {@code minecraft:brand} string. */
    public static Brand classify(final String brand) {
        final String lower = brand.toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) {
            return Brand.UNKNOWN;
        }
        if (lower.contains("lunar")) {
            return Brand.LUNAR;
        }
        if (lower.equals("vanilla")) {
            return Brand.VANILLA;
        }
        if (lower.contains("fabric") || lower.contains("quilt")) {
            return Brand.FABRIC;
        }
        if (lower.contains("forge")) {
            return Brand.FORGE;
        }
        return Brand.OTHER;
    }

    public Brand brand() {
        return this.brand;
    }

    public String rawBrand() {
        return this.rawBrand;
    }

    public boolean apollo() {
        return this.apollo;
    }

    /** Apollo {@code disable-miss-penalty} as the server pushed it to this player. */
    public boolean missPenaltyDisabled() {
        return this.missPenaltyDisabled;
    }

    public boolean blockMissPenaltyDisabled() {
        return this.blockMissPenaltyDisabled;
    }

    public boolean digAndUse() {
        return this.digAndUse;
    }

    public List<String> mods() {
        return this.mods;
    }

    /** One line for {@code /revival auth inspect}. */
    public String describe() {
        final StringBuilder line = new StringBuilder("client ").append(this.brand.name().toLowerCase(Locale.ROOT));
        if (!this.rawBrand.isEmpty()) {
            line.append(" ('").append(this.rawBrand).append("')");
        }
        if (this.apollo) {
            line.append("; apollo");
            if (!this.lunarVersion.isEmpty()) {
                line.append(' ').append(this.lunarVersion);
            }
            line.append(": miss-penalty ").append(this.missPenaltyDisabled ? "off (since window " + this.missPenaltySince + ")" : "on")
                .append(", block miss-penalty ").append(this.blockMissPenaltyDisabled ? "off (since window " + this.blockMissPenaltySince + ")" : "on")
                .append(", dig-and-use ").append(this.digAndUse ? "on (since window " + this.digAndUseSince + ")" : "off");
            if (!this.mods.isEmpty()) {
                line.append("; mods ").append(String.join(", ", this.mods));
            }
        }
        return line.append("; trig vanilla").toString();
    }

    // Apollo by reflection ----------------------------------------------------------------------------------------------

    private void readApollo(final UUID uuid, final int window) {
        final Apollo api = this.apolloFailed ? null : Apollo.get();
        if (api == null) {
            return;
        }
        try {
            final Object player = api.player(uuid);
            if (player == null) {
                return;
            }
            final boolean miss = api.option(player, api.missPenalty);
            if (miss != this.missPenaltyDisabled) {
                this.missPenaltyDisabled = miss;
                this.missPenaltySince = window;
            }
            final boolean blockMiss = api.option(player, api.blockMissPenalty);
            if (blockMiss != this.blockMissPenaltyDisabled) {
                this.blockMissPenaltyDisabled = blockMiss;
                this.blockMissPenaltySince = window;
            }
            final boolean dig = api.option(player, api.digAndUse);
            if (dig != this.digAndUse) {
                this.digAndUse = dig;
                this.digAndUseSince = window;
            }
            this.lunarVersion = api.version(player);
            this.mods = api.mods(player);
        } catch (final ReflectiveOperationException | LinkageError | RuntimeException e) {
            // This player's read failed, not Apollo's API (Apollo.get checked that): this player goes without its options
            // and everyone else keeps theirs (the error turned Apollo reading off for every player until a reload)
            this.apolloFailed = true;
        }
    }

    /** The Apollo API reached through its plugin's class loader; null while Apollo isn't there or doesn't fit. */
    private static final class Apollo {

        private static @Nullable Apollo instance;
        private static @Nullable Plugin plugin;
        private static boolean broken;

        final Object playerManager;
        final Object combatOptions;
        final Method getPlayer;
        final Method optionGet;
        final Object missPenalty;
        final Object blockMissPenalty;
        final Object digAndUse;
        final Method lunarVersion;
        final Method semVer;
        final Method installedMods;
        final Method modId;

        private Apollo(final ClassLoader loader) throws ReflectiveOperationException {
            final Class<?> apollo = Class.forName("com.lunarclient.apollo.Apollo", true, loader);
            this.playerManager = apollo.getMethod("getPlayerManager").invoke(null);
            this.getPlayer = Class.forName("com.lunarclient.apollo.player.ApolloPlayerManager", true, loader).getMethod("getPlayer", UUID.class);
            final Class<?> combat = Class.forName("com.lunarclient.apollo.module.combat.CombatModule", true, loader);
            final Object modules = apollo.getMethod("getModuleManager").invoke(null);
            final Object module = Class.forName("com.lunarclient.apollo.module.ApolloModuleManager", true, loader)
                .getMethod("getModule", Class.class).invoke(modules, combat);
            this.combatOptions = Class.forName("com.lunarclient.apollo.module.ApolloModule", true, loader).getMethod("getOptions").invoke(module);
            final Class<?> options = Class.forName("com.lunarclient.apollo.option.Options", true, loader);
            final Class<?> apolloPlayer = Class.forName("com.lunarclient.apollo.player.ApolloPlayer", true, loader);
            this.optionGet = options.getMethod("get", apolloPlayer, Class.forName("com.lunarclient.apollo.option.Option", true, loader));
            this.missPenalty = field(combat, "DISABLE_MISS_PENALTY");
            this.blockMissPenalty = field(combat, "DISABLE_BLOCK_MISS_PENALTY");
            this.digAndUse = field(combat, "ALLOW_DIG_AND_USE");
            this.lunarVersion = apolloPlayer.getMethod("getLunarClientVersion");
            this.semVer = Class.forName("com.lunarclient.apollo.client.version.LunarClientVersion", true, loader).getMethod("getSemVer");
            this.installedMods = apolloPlayer.getMethod("getInstalledMods");
            this.modId = Class.forName("com.lunarclient.apollo.client.mod.LunarClientMod", true, loader).getMethod("getId");
        }

        private static Object field(final Class<?> owner, final String name) throws ReflectiveOperationException {
            final Field field = owner.getField(name);
            return field.get(null);
        }

        static @Nullable Apollo get() {
            final Plugin found = Bukkit.getPluginManager().getPlugin("Apollo-Bukkit");
            if (found == null || !found.isEnabled()) {
                instance = null;
                plugin = null;
                return null;
            }
            if (found != plugin) {
                plugin = found; // a reload brings a new class loader
                broken = false;
                instance = null;
            }
            if (instance == null && !broken) {
                try {
                    instance = new Apollo(found.getClass().getClassLoader());
                } catch (final ReflectiveOperationException | LinkageError | RuntimeException e) {
                    broken = true; // another Apollo API: the profile goes without its options
                }
            }
            return instance;
        }

        @Nullable Object player(final UUID uuid) throws ReflectiveOperationException {
            final Optional<?> found = (Optional<?>) this.getPlayer.invoke(this.playerManager, uuid);
            return found.orElse(null);
        }

        boolean option(final Object player, final Object option) throws ReflectiveOperationException {
            return Boolean.TRUE.equals(this.optionGet.invoke(this.combatOptions, player, option));
        }

        String version(final Object player) throws ReflectiveOperationException {
            final Object version = this.lunarVersion.invoke(player);
            return version == null ? "" : String.valueOf(this.semVer.invoke(version));
        }

        List<String> mods(final Object player) throws ReflectiveOperationException {
            final Object listed = this.installedMods.invoke(player);
            if (!(listed instanceof final List<?> list) || list.isEmpty()) {
                return List.of();
            }
            final List<String> ids = new ArrayList<>(Math.min(list.size(), MAX_MODS));
            for (final Object mod : list) {
                if (ids.size() == MAX_MODS) {
                    break;
                }
                ids.add(String.valueOf(this.modId.invoke(mod)));
            }
            return List.copyOf(ids);
        }
    }
}
