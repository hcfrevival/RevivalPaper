package gg.hcfrevival.revival.config;

import gg.hcfrevival.revival.auth.AuthMode;

/**
 * The global {@code authoritative} section: the server, not the client, decides where players are and whether hits
 * landed (docs/revival/authoritative.md). Global only: a player's movement authority can't change when they cross into
 * another world mid-tick.
 *
 * @param mode {@code off} (vanilla/Paper movement), {@code observe} (everything runs and is measured, nothing is
 *             corrected) or {@code enforce} (the simulation is the truth)
 * @param rebaseEpsilon a claim within this distance of the simulated position is adopted; 0 (default) because the port is
 *                      bit-exact, at most 1e-10 when enabled (§15.5)
 * @param rebaseDriftCap with a rebase epsilon: the most adopted drift per axis within one drift window, so claims can't
 *                       creep (§15.5)
 * @param rebaseDriftWindowTicks with a rebase epsilon: client ticks per drift window
 * @param correctionThreshold divergence up to this goes into the correction budget instead of correcting at once
 * @param correctionBudget summed small divergences past this send a correction
 * @param correctionBudgetDecay the budget is multiplied by this every client tick, so isolated float noise never adds up
 * @param maxCorrectionsPerSecond corrections sent to one player per second at most
 * @param maxRewindMs lag compensation never rewinds further than this (anti-backtrack, ping spoof)
 * @param timerDriftMs slack on the tick clock: the client's clock may run this far behind the acked ping clock
 * @param maxBankedTicks the most client ticks a player can run in a burst after a stall (the client's own cap is 10)
 * @param timerQueueCapMs a client tick over the time budget waits this long for budget before it is refused
 * @param ackJitterTicks a logged packet still unacked {@code ceil(rttBase / 50) + this} client ticks after it was sent
 *                       is applied to the truth anyway (§15.1)
 * @param maxUnackedMs a player whose oldest ping is unanswered this long is frozen; three times this, kicked in enforce
 * @param vehicles {@code deny} (mounting is refused in enforce) or {@code vanilla} (riding is exempt and client-trusted)
 * @param honorDeniedPredictions whether the truth keeps blocks the client predicted but the server denied (false stops
 *                               block glitching)
 * @param maxAttacksPerClientTick attack packets past this many in one client tick are dropped
 * @param heartbeatPing send a ping every server tick that sent the player nothing else, so the tick clock keeps an ack
 * @param pathStepTicks replication: how long each authoritative path step lasts on viewers (report 03 §6, K)
 * @param keepAlive replication: nudge still players by 1/4096 block on alternate ticks so viewers never starve
 * @param exactRadius replication: exact paths, keep-alives and mirrors only within this many blocks of a viewer; beyond
 *                    it (plus 4 blocks of hysteresis) the vanilla tracker cadence, still logged
 * @param preRoll replication: after a spawn, a hold step at the spawn point first (report 03 §6.3, M3)
 * @param respawnDistance replication: a jump farther than this from the viewer's display re-pairs instead of syncing
 * @param pathBuffer replication: authoritative steps kept for viewers that drain late; one further behind is re-paired
 * @param mirrorHistoryTicks lag compensation: viewer ticks of target boxes each viewer's mirrors keep
 * @param visibilityEnabled anti-ESP: players with no line of sight are not sent
 * @param alwaysVisibleDistance anti-ESP: players closer than this are always sent (footsteps range)
 * @param anomalyAlerts fire {@code AuthInputAnomalyEvent} when click or aim statistics pass their thresholds
 * @param traceDirectory where {@code /revival auth trace} writes RVTR files, relative to the server folder
 * @param debugHud playtest HUD: every player under authority sees an action-bar line with mode, RTT, exact ticks,
 *                 corrections, timer bank and the last attack's verdict
 * @param debugHudIntervalTicks server ticks between HUD refreshes
 * @param debugLog one console line per correction and per rejected hit
 */
public record AuthoritativeSettings(
    AuthMode mode,
    double rebaseEpsilon,
    double rebaseDriftCap,
    int rebaseDriftWindowTicks,
    double correctionThreshold,
    double correctionBudget,
    double correctionBudgetDecay,
    int maxCorrectionsPerSecond,
    int maxRewindMs,
    int timerDriftMs,
    int maxBankedTicks,
    int timerQueueCapMs,
    int ackJitterTicks,
    int maxUnackedMs,
    String vehicles,
    boolean honorDeniedPredictions,
    int maxAttacksPerClientTick,
    boolean heartbeatPing,
    int pathStepTicks,
    boolean keepAlive,
    double exactRadius,
    boolean preRoll,
    double respawnDistance,
    int pathBuffer,
    int mirrorHistoryTicks,
    boolean visibilityEnabled,
    double alwaysVisibleDistance,
    boolean anomalyAlerts,
    String traceDirectory,
    boolean debugHud,
    int debugHudIntervalTicks,
    boolean debugLog
) {

    static final String PATH = "authoritative.";

    private static String vehicles(final ConfigReader reader) {
        final String value = reader.getString(PATH + "vehicles", "deny").trim().toLowerCase(java.util.Locale.ROOT);
        if (!value.equals("deny") && !value.equals("vanilla")) {
            reader.problem(PATH + "vehicles", value, "deny or vanilla", "deny");
            return "deny";
        }
        return value;
    }

    static AuthoritativeSettings read(final ConfigReader reader) {
        final String modeName = reader.getString(PATH + "mode", AuthMode.OFF.configName());
        AuthMode mode = AuthMode.parse(modeName);
        if (mode == null) {
            reader.problem(PATH + "mode", modeName, "off, observe or enforce", AuthMode.OFF.configName());
            mode = AuthMode.OFF;
        }
        return new AuthoritativeSettings(
            mode,
            Math.clamp(reader.getDouble(PATH + "rebase-epsilon", 0.0), 0.0, 1.0E-10),
            Math.max(0.0, reader.getDouble(PATH + "rebase-drift-cap", 1.0E-9)),
            Math.max(1, reader.getInt(PATH + "rebase-drift-window-ticks", 20)),
            Math.max(0.0, reader.getDouble(PATH + "correction-threshold", 1.0E-3)),
            Math.max(0.0, reader.getDouble(PATH + "correction-budget", 0.01)),
            Math.clamp(reader.getDouble(PATH + "correction-budget-decay", 0.95), 0.0, 1.0),
            Math.max(1, reader.getInt(PATH + "max-corrections-per-second", 5)),
            Math.max(0, reader.getInt(PATH + "max-rewind-ms", 300)),
            Math.max(0, reader.getInt(PATH + "timer-drift-ms", 120)),
            Math.max(1, reader.getInt(PATH + "max-banked-ticks", 10)),
            Math.max(0, reader.getInt(PATH + "timer-queue-cap-ms", 1000)),
            Math.max(0, reader.getInt(PATH + "ack-jitter-ticks", 4)),
            Math.max(100, reader.getInt(PATH + "max-unacked-ms", 1500)),
            vehicles(reader),
            reader.getBoolean(PATH + "honor-denied-predictions", false),
            Math.max(1, reader.getInt(PATH + "max-attacks-per-client-tick", 1)),
            reader.getBoolean(PATH + "heartbeat-ping", true),
            Math.max(1, reader.getInt(PATH + "replication.path-step-ticks", 2)),
            reader.getBoolean(PATH + "replication.keep-alive", true),
            Math.max(0.0, reader.getDouble(PATH + "replication.exact-radius", 24.0)),
            reader.getBoolean(PATH + "replication.pre-roll", true),
            Math.clamp(reader.getDouble(PATH + "replication.respawn-distance", 48.0), 1.0, 63.0),
            Math.max(8, reader.getInt(PATH + "replication.path-buffer", 64)),
            Math.max(8, reader.getInt(PATH + "mirror-history-ticks", 64)),
            reader.getBoolean(PATH + "visibility.enabled", false),
            Math.max(0.0, reader.getDouble(PATH + "visibility.always-visible-distance", 8.0)),
            reader.getBoolean(PATH + "stats.anomaly-alerts", true),
            reader.getString(PATH + "trace.directory", "traces"),
            reader.getBoolean(PATH + "debug.hud", false),
            Math.max(1, reader.getInt(PATH + "debug.hud-interval-ticks", 5)),
            reader.getBoolean(PATH + "debug.log", false)
        );
    }
}
