package gg.hcfrevival.revival.auth;

import gg.hcfrevival.revival.config.AuthoritativeSettings;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Playtest instruments ({@code authoritative.debug.*}): an action-bar line for every player under authority, and one
 * console line per correction and per rejected hit, so a playtest over a real network can be read back from the logs.
 * <p>
 * HUD: {@code E|O  rtt <ack ms>  exact <% last 10 s>  corr <total> (<last 10 s>) last <|Δpos| blocks>  bank <ticks>
 * hit <VALID|INVALID reason|UNKNOWN reason> <rewind ms>}.
 */
public final class DebugHud {

    private static final Logger LOGGER = LogManager.getLogger("RevivalPaper");

    private DebugHud() {
    }

    /** The HUD line for one player now. */
    public static Component line(final AuthPlayer auth, final long nowMillis) {
        final AuthStats stats = auth.stats();
        final RollingStats recent = auth.recent();
        final double share = recent.exactShare(nowMillis);
        final String exact = Double.isNaN(share) ? "--" : String.format(Locale.ROOT, "%.1f%%", share * 100.0);
        final double bank = auth.clock().banked(System.nanoTime(), auth.view().tickPeriodNanos());
        final String hit = auth.lastHitVerdict() == null ? "-" : auth.lastHitVerdict()
            + (auth.lastHitRewindMillis() >= 0 ? " " + auth.lastHitRewindMillis() + "ms" : "");
        final NamedTextColor modeColor = auth.mode() == AuthMode.ENFORCE ? NamedTextColor.GREEN : NamedTextColor.YELLOW;
        return Component.text()
            .append(Component.text(auth.mode() == AuthMode.ENFORCE ? "E" : "O", modeColor))
            .append(Component.text(String.format(Locale.ROOT, "  rtt %dms  exact %s  corr %d (%d) last %.3f  bank %.1f  hit ",
                auth.transactions().lastRttNanos() / 1_000_000L, exact, stats.corrections(), recent.corrections(nowMillis),
                auth.lastCorrectionSize(), bank), NamedTextColor.GRAY))
            .append(Component.text(hit, auth.lastHitVerdict() != null && auth.lastHitVerdict().startsWith("INVALID") ? NamedTextColor.RED : NamedTextColor.WHITE))
            .build();
    }

    /** Refreshes the HUD when it is due (from {@code onServerTick}). */
    static void tick(final AuthPlayer auth, final AuthoritativeSettings settings) {
        if (!settings.debugHud() || auth.mode() == AuthMode.OFF || MinecraftServer.currentTick % settings.debugHudIntervalTicks() != 0) {
            return;
        }
        auth.player().getBukkitEntity().sendActionBar(line(auth, System.currentTimeMillis()));
    }

    static void correction(final AuthPlayer auth, final int tick, final double dx, final double dy, final double dz,
                           final double vx, final double vy, final double vz, final boolean relativeVelocity, final int ahead) {
        LOGGER.info(String.format(Locale.ROOT, "[auth] correction %s tick %d dpos (%.6f, %.6f, %.6f) |%.6f| %s (%.6f, %.6f, %.6f) rtt %dms ahead %d (%s)",
            auth.player().getScoreboardName(), tick, dx, dy, dz, Math.sqrt(dx * dx + dy * dy + dz * dz), relativeVelocity ? "dvel" : "vel",
            vx, vy, vz, auth.transactions().lastRttNanos() / 1_000_000L, ahead, auth.forecastParts()));
    }

    static void diverged(final AuthPlayer auth, final int tick, final double x, final double y, final double z, final Simulation sim, final boolean claimOnGround) {
        LOGGER.info(String.format(Locale.ROOT, "[auth] diverged %s tick %d [%s] claim (%.6f, %.6f, %.6f)%s sim (%.6f, %.6f, %.6f)%s v (%.6f, %.6f, %.6f)",
            auth.player().getScoreboardName(), tick, auth.mode().configName(), x, y, z, claimOnGround ? " ground" : "", sim.x(), sim.y(), sim.z(),
            sim.onGround() ? " ground" : "", sim.vx(), sim.vy(), sim.vz()));
    }

    static void absoluteCorrection(final AuthPlayer auth, final int tick, final double x, final double y, final double z, final double vx, final double vy,
                                   final double vz, final int ahead) {
        LOGGER.info(String.format(Locale.ROOT, "[auth] absolute correction %s tick %d pos (%.6f, %.6f, %.6f) vel (%.6f, %.6f, %.6f) rtt %dms ahead %d (%s)",
            auth.player().getScoreboardName(), tick, x, y, z, vx, vy, vz, auth.transactions().rttBaseNanos() / 1_000_000L, ahead, auth.forecastParts()));
    }

    /** A correction's accept: how far the client landed from the truth, and in which window against the forecast. */
    static void accepted(final AuthPlayer auth, final int tick, final double residual, final int landedAfter, final int forecast) {
        LOGGER.info(String.format(Locale.ROOT, "[auth] accept %s tick %d residual %.9f landed after %d (forecast %d)", auth.player().getScoreboardName(),
            tick, residual, landedAfter, forecast));
    }

    /** Entries force-applied at their deadline in this window, by kind ({@code OutboundClassifier}: count). */
    static void forced(final AuthPlayer auth, final int tick, final gg.hcfrevival.revival.auth.net.Transactions.Acked forced) {
        final int[] counts = new int[gg.hcfrevival.revival.auth.net.OutboundClassifier.KINDS];
        for (int i = 0; i < forced.size(); i++) {
            final int kind = forced.kind(i);
            if (kind >= 0 && kind < counts.length) {
                counts[kind]++;
            }
        }
        final StringBuilder kinds = new StringBuilder();
        for (int kind = 0; kind < counts.length; kind++) {
            if (counts[kind] > 0) {
                kinds.append(' ').append(kind).append(':').append(counts[kind]);
            }
        }
        LOGGER.info(String.format(Locale.ROOT, "[auth] forced %s tick %d entries %d kinds%s", auth.player().getScoreboardName(), tick, forced.size(), kinds));
    }

    static void grammar(final AuthPlayer auth, final int tick, final String rule, final net.minecraft.network.protocol.Packet<?> packet, final boolean dropped) {
        LOGGER.info(String.format(Locale.ROOT, "[auth] input grammar %s tick %d %s: %s%s", auth.player().getScoreboardName(), tick, rule,
            packet.getClass().getSimpleName(), dropped ? " (dropped)" : ""));
    }

    static void pathStep(final AuthPlayer auth, final int from, final int to, final long jitterMs) {
        LOGGER.info(String.format(Locale.ROOT, "[auth] path step %s K%d -> K%d (own jitter %d ms)", auth.player().getScoreboardName(), from, to, jitterMs));
    }

    static void rejectedHit(final AuthPlayer auth, final int tick, final String target, final String reason, final long rewindMillis, final double distance) {
        LOGGER.info(String.format(Locale.ROOT, "[auth] hit rejected %s -> %s tick %d %s rewind %dms distance %.4f",
            auth.player().getScoreboardName(), target, tick, reason, rewindMillis, distance));
    }
}
