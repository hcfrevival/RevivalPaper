package gg.hcfrevival.revival.command;

import gg.hcfrevival.revival.Revival;
import gg.hcfrevival.revival.auth.AuthMode;
import gg.hcfrevival.revival.auth.AuthPlayer;
import gg.hcfrevival.revival.auth.AuthStats;
import gg.hcfrevival.revival.auth.Authority;
import gg.hcfrevival.revival.auth.input.InputStats;
import gg.hcfrevival.revival.auth.net.Transactions;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

/**
 * {@code /revival auth status | mode <off|observe|enforce|config> | inspect <player> | trace start|stop <player>}:
 * staff tools for the authoritative netcode.
 */
final class AuthCommand {

    static final String LABEL = "auth";
    private static final List<String> SUBCOMMANDS = List.of("status", "mode", "inspect", "trace");

    private AuthCommand() {
    }

    static void execute(final CommandSender sender, final String[] args) {
        final String sub = args.length < 2 ? "status" : args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender);
            case "mode" -> mode(sender, args);
            case "inspect" -> inspect(sender, args);
            case "trace" -> trace(sender, args);
            default -> sender.sendMessage(Component.text("Usage: /revival auth [status | mode <off|observe|enforce|config> | inspect <player> | trace start|stop <player>]", NamedTextColor.RED));
        }
    }

    static List<String> tabComplete(final String[] args) {
        if (args.length == 2) {
            return StringUtil.copyPartialMatches(args[1], SUBCOMMANDS, new ArrayList<>());
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("mode")) {
            return StringUtil.copyPartialMatches(args[2], List.of("off", "observe", "enforce", "config"), new ArrayList<>());
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("trace")) {
            return StringUtil.copyPartialMatches(args[2], List.of("start", "stop"), new ArrayList<>());
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("inspect") || args.length == 4 && args[1].equalsIgnoreCase("trace")) {
            final List<String> names = new ArrayList<>();
            for (final Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            return StringUtil.copyPartialMatches(args[args.length - 1], names, new ArrayList<>());
        }
        return List.of();
    }

    private static void status(final CommandSender sender) {
        final AuthMode configured = Revival.settings().authoritative().mode();
        final AuthMode override = Authority.modeOverride();
        final String refusal = Authority.refusal();
        sender.sendMessage(Component.text("Authority: " + Authority.globalMode().configName()
            + " (revival.yml " + configured.configName() + (override != null ? ", overridden to " + override.configName() + " until restart" : "") + ")", NamedTextColor.GOLD));
        if (refusal != null) {
            sender.sendMessage(Component.text("Refused: " + refusal, NamedTextColor.RED));
        }
        int observe = 0;
        int enforce = 0;
        for (final AuthPlayer auth : Authority.activePlayers()) {
            if (auth.mode() == AuthMode.ENFORCE) {
                enforce++;
            } else {
                observe++;
            }
        }
        sender.sendMessage(Component.text("Players under authority: " + enforce + " enforce, " + observe + " observe", NamedTextColor.GRAY));
        for (final AuthPlayer auth : Authority.activePlayers()) {
            final AuthStats stats = auth.stats();
            sender.sendMessage(Component.text(String.format(Locale.ROOT, " %s [%s] exact %.1f%% of %d, max %.2e, rtt %.0f ms, corrections %d",
                auth.player().getScoreboardName(), auth.mode().configName(), stats.exactShare() * 100.0, stats.compared(), stats.maxDivergence(),
                auth.transactions().rttBaseNanos() / 1.0E6, stats.corrections()), NamedTextColor.GRAY));
        }
    }

    private static void mode(final CommandSender sender, final String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /revival auth mode <off|observe|enforce|config>", NamedTextColor.RED));
            return;
        }
        if (args[2].equalsIgnoreCase("config")) {
            Authority.setModeOverride(null);
            sender.sendMessage(Component.text("Authority follows revival.yml again: " + Authority.globalMode().configName(), NamedTextColor.GREEN));
            return;
        }
        final AuthMode mode = AuthMode.parse(args[2]);
        if (mode == null) {
            sender.sendMessage(Component.text("Unknown mode " + args[2] + "; use off, observe, enforce or config", NamedTextColor.RED));
            return;
        }
        Authority.setModeOverride(mode);
        final String refusal = Authority.refusal();
        sender.sendMessage(Component.text("Authority set to " + mode.configName() + " until restart (players switch at their next client tick)"
            + (refusal != null && mode != AuthMode.OFF ? ", but it stays off: " + refusal : ""), refusal != null && mode != AuthMode.OFF ? NamedTextColor.YELLOW : NamedTextColor.GREEN));
    }

    private static @org.jspecify.annotations.Nullable AuthPlayer target(final CommandSender sender, final String[] args, final int index) {
        if (args.length <= index) {
            sender.sendMessage(Component.text("Name a player", NamedTextColor.RED));
            return null;
        }
        final Player player = Bukkit.getPlayerExact(args[index]);
        if (player == null) {
            sender.sendMessage(Component.text("No player named " + args[index] + " is online", NamedTextColor.RED));
            return null;
        }
        return ((CraftPlayer) player).getHandle().revivalAuth;
    }

    private static void inspect(final CommandSender sender, final String[] args) {
        final AuthPlayer auth = target(sender, args, 2);
        if (auth == null) {
            return;
        }
        final ServerPlayer player = auth.player();
        final AuthStats stats = auth.stats();
        final Transactions tx = auth.transactions();
        final InputStats input = auth.inputStats();
        final List<String> lines = new ArrayList<>();
        lines.add(player.getScoreboardName() + ": " + auth.mode().configName() + (auth.ineligible() != null ? " (not eligible: " + auth.ineligible() + ")" : "")
            + (auth.frozen() ? ", FROZEN (pings unanswered)" : "") + (auth.view().selfCollisionNever() ? "" : ", players can push it (collision rule is not NEVER)"));
        lines.add(auth.profile().describe());
        lines.add(String.format(Locale.ROOT, "windows %d, simulated %d, compared %d: exact %d (%.2f%%), within threshold %d, over %d; max %.3e, mean %.3e",
            stats.windows(), stats.simulated(), stats.compared(), stats.exact(), stats.exactShare() * 100.0, stats.withinThreshold(), stats.overThreshold(),
            stats.maxDivergence(), stats.meanDivergence()));
        final StringBuilder exempt = new StringBuilder("exempt:");
        for (int reason = 1; reason < AuthStats.EXEMPT_REASONS; reason++) {
            if (stats.exempt(reason) > 0) {
                exempt.append(' ').append(AuthStats.exemptName(reason)).append('=').append(stats.exempt(reason));
            }
        }
        lines.add(exempt.toString());
        lines.add(String.format(Locale.ROOT, "flags off %d, positions missing %d, reminders missed %d, timer refused %d (queued now %d), violations %d (stream %d, pongs %d)",
            stats.flagMismatches(), stats.missingPositionViolations(), stats.missedReminders(), auth.clock().refused(), auth.stream().queued(),
            stats.violations(), auth.stream().violations(), tx.violationCount()));
        if (!auth.violationReasons().isEmpty()) {
            final StringBuilder reasons = new StringBuilder("violations by reason:");
            for (final it.unimi.dsi.fastutil.objects.Object2IntMap.Entry<String> entry : auth.violationReasons().object2IntEntrySet()) {
                reasons.append(' ').append(entry.getKey()).append(" x").append(entry.getIntValue()).append(';');
            }
            lines.add(reasons.toString());
        }
        if (auth.simulation() instanceof final gg.hcfrevival.revival.auth.PortSimulation port) {
            lines.add(String.format(Locale.ROOT, "sprint packets off the simulated sprint %d, flight packets off the simulated flight %d; pushes adopted %d, mover claims refused %d; pongs behind keep-alive %d s (keep-alive %d ms)",
                port.sprintMismatches(), port.flightMismatches(), stats.pushesAdopted(), stats.moverClaimsRefused(), stats.pongsBehindKeepAlive(),
                player.connection.latency()));
        }
        lines.add(String.format(Locale.ROOT, "transactions: sent %d, acked %d, outstanding %d (%d entries), forced %d, skipped %d, duplicates %d, foreign %d; rtt base %.1f ms, last %.1f ms (%d ticks)",
            tx.sentCount(), tx.ackedCount(), tx.outstandingPings(), tx.outstandingEntries(), tx.forcedCount(), tx.skippedCount(), tx.duplicateCount(), tx.foreignCount(),
            tx.rttBaseNanos() / 1.0E6, tx.lastRttNanos() / 1.0E6, tx.lastRttTicks()));
        lines.add(String.format(Locale.ROOT, "corrections %d (accepted %d, reconciler violations %d; avoided by hotbar branch %d, attack-strength branch %d; rebases refused %d), server teleports %d (accepts off %d), reverts %d, external movers %d, mounts refused %d",
            stats.corrections(), stats.correctionsAccepted(), auth.reconciler().violations(), stats.hotbarBranches(), stats.attackStrengthBranches(), stats.rebasesRefused(),
            stats.serverTeleports(), stats.teleportAcceptMismatches(), stats.reverts(), stats.externalMovers(), stats.mountsRefused()));
        lines.add(String.format(Locale.ROOT, "attacks %d: accepted %d, rejected %d, over cap %d, over cps %d; one-click streak %d (longest %d); double clicks %.1f%%, yaw gcd %.4f / %.4f (%d turns, %d misses, %d smoothed skipped)",
            stats.attacksQueued(), stats.attacksAccepted(), stats.attacksRejected(), stats.attacksOverCap(), stats.attacksOverCps(),
            auth.cadence().streak(), auth.cadence().longest(), input.doubleClickRatio() * 100.0,
            input.yawGcd(), input.secondGcd(), input.turns(), input.gcdMisses(), input.smoothedTurns()));
        final gg.hcfrevival.revival.auth.input.InputRules rules = auth.inputRules();
        final StringBuilder grammar = new StringBuilder("input grammar: " + rules.total() + " broken, " + stats.grammarDropped() + " dropped");
        for (int rule = 1; rule < gg.hcfrevival.revival.auth.input.InputRules.RULES; rule++) {
            if (rules.count(rule) > 0) {
                grammar.append(", ").append(gg.hcfrevival.revival.auth.input.InputRules.name(rule)).append(' ').append(rules.count(rule));
            }
        }
        final StringBuilder clicks = new StringBuilder("; attacks per tick");
        for (int bucket = 0; bucket <= 5; bucket++) {
            clicks.append(' ').append(bucket == 5 ? "5+" : String.valueOf(bucket)).append(':').append(input.attacksPerTick(bucket));
        }
        lines.add(grammar.append(clicks).toString());
        final gg.hcfrevival.revival.auth.net.BypassAuditor audit = auth.auditor();
        lines.add(audit == null ? "bypass audit: not installed (no game encoder on this channel)"
            : String.format(Locale.ROOT, "bypass audit: %d packets around the netcode (%d moved the player; %d as Packet objects, %d pre-encoded)",
                stats.bypasses(), stats.bypassMovers(), audit.packetBypasses(), audit.rawBypasses()));
        lines.add(String.format(Locale.ROOT, "world view: %d sent, %d pinned, %d predicted, %d chunks%s; trace %s",
            auth.world().sentCount(), auth.world().pinnedCount(), auth.world().predictionCount(), auth.world().chunkCount(),
            auth.world().chunksKnown() ? "" : " (chunk list unknown)", auth.trace() != null ? auth.trace().file() : "off"));
        for (final String line : lines) {
            sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
        }
    }

    private static void trace(final CommandSender sender, final String[] args) {
        if (args.length < 3 || !args[2].equalsIgnoreCase("start") && !args[2].equalsIgnoreCase("stop")) {
            sender.sendMessage(Component.text("Usage: /revival auth trace start|stop <player>", NamedTextColor.RED));
            return;
        }
        final AuthPlayer auth = target(sender, args, 3);
        if (auth == null) {
            return;
        }
        if (args[2].equalsIgnoreCase("start")) {
            final Path directory = Path.of(Revival.settings().authoritative().traceDirectory());
            try {
                auth.startTrace(directory);
                sender.sendMessage(Component.text("Tracing " + auth.player().getScoreboardName() + " to " + auth.trace().file(), NamedTextColor.GREEN));
            } catch (final java.io.IOException e) {
                sender.sendMessage(Component.text("Could not start the trace: " + e.getMessage(), NamedTextColor.RED));
            }
        } else {
            final Path file = auth.stopTrace();
            sender.sendMessage(file != null
                ? Component.text("Trace written to " + file, NamedTextColor.GREEN)
                : Component.text(auth.player().getScoreboardName() + " was not being traced", NamedTextColor.YELLOW));
        }
    }
}
