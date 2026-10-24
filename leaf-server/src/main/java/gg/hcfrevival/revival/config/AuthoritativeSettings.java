package gg.hcfrevival.revival.config;

import gg.hcfrevival.revival.auth.AuthMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.Identifier;

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
 * @param attackEyeOffsetCap while the client is off the truth (a correction decided, rate-limited or in flight), its
 *                           attacks are cast from its claim when that is within this many blocks of the truth on every
 *                           axis, else from the truth (review E3: a claim chose its own correction's size)
 * @param attackEyeForecastTolerance ...and only for a gap the server caused (entries forced at their deadline, a window the
 *                                   timer refused, a denied break, a revert snap) while every claim since the client was
 *                                   last on the truth landed within this many blocks of the client's own state stepped
 *                                   with its own inputs (the belief), with no block between the truth and the claim; a
 *                                   gap the claim made up casts from the truth (classic audit #1)
 * @param blockClipNonPlayerTargets an attack on a living non-player target (a mob, a villager, an armor stand; not a
 *                                  mannequin) is refused when a block the server has stands between the attacker's eye and
 *                                  every place the target may have been on its screen (design §7.1, 2026-10-24: villagers
 *                                  killed through a glass box on the NAS); reach and aim stay vanilla's
 * @param correctionBudget summed small divergences past this send a correction
 * @param correctionBudgetDecay the budget is multiplied by this every client tick, so isolated float noise never adds up
 * @param maxCorrectionsPerSecond corrections sent to one player per second at most
 * @param correctionRepeatLimit the same correction this many times in a row goes out absolute (position and velocity):
 *                              the client is stuck off the truth, where a relative one can't reach it
 * @param correctionPersistTicks a claim off the truth by less than the threshold this many ticks in a row is corrected
 *                               anyway, so every client converges
 * @param correctionPersistMin a claim off by less than this never counts toward {@code correction-persist-ticks}: a
 *                             sub-µm gap is float noise a teleport can't remove, only add to (counted, never corrected)
 * @param landingProbeMs between server ticks, a window flush that sent nothing gets a lone ping when this kind of flush
 *                       wasn't measured for this long, so corrections know where they land (LandingLag)
 * @param landingSampleMs corrections are forecast from the landings of pings that came back within this long; with
 *                        none, from {@code ceil(rttBase / 50)}
 * @param maxRewindMs a sanity bound on the per-attacker rewind cap, {@code min(rttBase, rtt-ceiling-ms) + the attacker's
 *                    own display delay + jitter + rewind-slack-ms}; the ceiling on ping is {@code rtt-ceiling-ms}
 * @param rewindJitterFactor the jitter allowance in that cap: this times the attacker's ack-RTT spread (p90 − p10)
 * @param rewindSlackMs the tick waits in that cap: a step waits for its flush, the client tick that shows it and the
 *                      attack's window for the server tick (up to 3 ticks)
 * @param timerDriftMs slack on the tick clock: the client's clock may run this far behind the acked ping clock
 * @param timerAckSettleMs a pong binds the tick clock for the ticks of its own frame only once it arrived this long
 *                         before their {@code TickEnd}: a client back from a stall answers its queued pings and then runs
 *                         its catch-up ticks, which the stall is owed
 * @param maxBankedTicks the most client ticks a player can run in a burst after a stall (the client's own cap is 10)
 * @param timerQueueCapMs a client tick over the time budget waits this long for budget before it is refused (400: a
 *                        window that waited longer is a cheat or a broken link, not a backlog; torture F4)
 * @param catchUpGapMs a client tick whose {@code TickEnd} came less than this after the one before, with no pong in its
 *                     drain, is a catch-up tick of a burst after a stall or hitch: ack deadlines don't count it (torture
 *                     F3), at most {@code max-banked-ticks} in a row
 * @param rttCeilingMs the most round trip lag compensation makes good, and that any slack granted to the client is sized
 *                     from: the rewind cap, the ack deadline, the visibility growth and the correction lead all use
 *                     {@code min(rttBase, this)}, so a client that delays its pongs can't stretch them (review E5); past
 *                     it a high-ping shooter has to lead moving players
 * @param ackJitterTicks a logged packet still unacked {@code ceil(rttBase / 50) + this} client ticks after it was sent
 *                       is applied to the truth anyway (§15.1)
 * @param maxUnackedMs a player whose oldest ping is unanswered this long (or whose client tick stays open this long) is
 *                     frozen: it stays where the truth is until its replies come back; vanilla's keep-alive timeout
 *                     decides when the connection is gone (no kick of ours: in HCF a kick is a combat-logger death)
 * @param vehicles {@code deny} (mounting is refused in enforce) or {@code vanilla} (riding is exempt and client-trusted)
 * @param ineligible players who can't be under authority (another protocol through ViaVersion, Bedrock through Geyser):
 *                   {@code allow} (they play under vanilla rules, logged once each) or {@code deny} (refused while
 *                   the mode is enforce: authority then protects every fight, review E4)
 * @param apolloRules {@code follow} (client profiles read the Apollo combat options the server pushed to each Lunar
 *                    player) or {@code ignore}; informational until a rule reads the profile (design §15.8)
 * @param protocolKickRate ordering-only protocol violations (a pong or accept after the tick's keybinds, a second input,
 *                         sprint command or move packet in a tick, a teleport never accepted, a backwards prediction
 *                         sequence) a player may make in 60 s before a kick (design §15.8: in HCF a kick is a combat-logger
 *                         death); 0 kicks at the first, 4096 at most. Forged or unknown replies, overflow and a second
 *                         position claim kick at once
 * @param teleportChunkSendTicks a server teleport into a chunk the client hasn't been sent is held (pvp-issues PR-8): if
 *                               Moonrise hasn't sent that chunk within this many ticks, it is sent now, then the teleport
 * @param teleportHoldMaxTicks a held teleport goes out after this many ticks whatever its chunk (one the server can't load)
 * @param seedMaxMove the most a claimed move or a seeded velocity may be on one axis, in blocks per tick: a seed from
 *                    two claims further apart than this uses the server's known movement (vanilla's moved-too-quickly
 *                    bound is 10 blocks)
 * @param maxAttacksPerClientTick attack packets past this many in one client tick are dropped
 * @param heartbeatPing send a ping every server tick that sent the player nothing else, so the tick clock keeps an ack
 * @param bypassAudit watch each authoritative player's channel for physics packets written around the netcode (§15.1)
 * @param pathStepTicks replication: how long each authoritative path step lasts on viewers (report 03 §6, K)
 * @param lowJitterStepTicks replication: K for a viewer whose own ack jitter (p90 − p10 RTT) is low: one tick (50 ms) of
 *                           view latency back on every remote player (torture K1); set it to {@code path-step-ticks}
 *                           to give every viewer the same K
 * @param lowJitterMs replication: a viewer moves to {@code low-jitter-step-ticks} below this much jitter
 * @param highJitterMs replication: and back to {@code path-step-ticks} above this much (the gap is hysteresis); a switch
 *                     re-pairs the players fed to it
 * @param stepSwitchSeconds replication: a viewer's K changes only after its jitter stayed on the other side this many
 *                          once-a-second checks in a row (a join or teleport delays acks for a moment)
 * @param keepAlive replication: nudge still players by 1/4096 block on alternate ticks so viewers never starve
 * @param exactRadius replication: exact paths, keep-alives and mirrors only within this many blocks of a viewer; beyond
 *                    it (plus 4 blocks of hysteresis) the vanilla tracker cadence, still logged
 * @param rangedRadius replication: a viewer aiming a ranged item gets paths out to this many blocks instead, so the player
 *                     it aims at doesn't stutter (MC-311974, pvp-issues VI-1); the player tracking range still caps it.
 *                     0 (default) is off: design §15.6 keeps exact paths within exact-radius until its owner signs off on 64
 * @param rangedLingerTicks replication: ...for this many server ticks after it last held one, so a hotbar switch between a
 *                          pearl and a sword doesn't drop the far players off the feed and back
 * @param rangedItems replication: the items that count, in either hand
 * @param preRoll replication: after a spawn, a hold step at the spawn point first (report 03 §6.3, M3)
 * @param respawnDistance replication: a jump farther than this from the viewer's display re-pairs instead of syncing
 * @param pathBuffer replication: authoritative steps kept for viewers that drain late; one further behind is re-paired
 * @param repairIntervalTicks replication: a viewer whose mirror of a target lost exactness (a lag spike, a late pong) gets
 *                            the target re-spawned, at most once per this many server ticks
 * @param mirrorHistoryTicks lag compensation: viewer ticks of target boxes each viewer's mirrors keep. Hits read the box
 *                           of the attack's own tick and measure the rewind from its stamp, so the history only has to
 *                           outlast the rewind cap ({@code max-rewind-ms} / 50 = 12 ticks); it costs 61 bytes per tick
 *                           per viewer-target pair (review PT-16)
 * @param visibilityEnabled anti-ESP: players with no line of sight are not sent
 * @param alwaysVisibleDistance anti-ESP: players closer than this are always sent (footsteps range)
 * @param visibilityCheckIntervalTicks anti-ESP: server ticks between line-of-sight checks of one viewer-target pair
 * @param visibilityHideAfterChecks anti-ESP: hidden checks in a row before a player is hidden (one seen check shows it)
 * @param visibilityMaxSpeed anti-ESP: blocks per tick either player may move before the client could show the target;
 *                           both boxes grow by it over the viewer's round trip and the check interval
 * @param visibilityMaxChecksPerTick the most visibility checks (up to 81 rays each) the whole server runs in one tick; past
 *                                   it a pair keeps its last decision until a later tick (review PT-08)
 * @param visibilityEffectReach anti-ESP: sounds and particles this close to a hidden player's box are its own and are not
 *                              sent either (steps, hits, eating, crits)
 * @param anomalyAlerts fire {@code AuthInputAnomalyEvent} when click or aim statistics pass their thresholds
 * @param anomalyDoubleClickRatio double-click alert: share of attack ticks with two or more attacks. 1.0 (default): only a
 *                                client that doubles every attack tick; 0.3 flagged honest butterfly clickers at 16-20 CPS
 *                                (0.4-0.5) and caught no autoclicker (about 0) (classic audit #12)
 * @param anomalyMinAttackTicks double-click alert: attack ticks needed before it is judged
 * @param anomalyAimGcdMissRatio aim alert: share of turns that share no mouse step with the others
 * @param anomalyMinTurns aim alert: turns needed before it is judged
 * @param anomalyClickStreakTicks click-streak alert (neue-combat §12.2 detector A): this many eligible client ticks in a
 *                                row with exactly one click (a 20 CPS lock or a one-per-tick macro)
 * @param anomalyClickLockClicks click-lock alert (neue-combat §12.2 detector B, any fixed rate): this many intervals
 *                               between clicks, each k or k+1 ticks (the alert waits for the lock to hold this many
 *                               clicks more), ...
 * @param anomalyClickLockResidual ...no click further than this many ticks from their least-squares line...
 * @param anomalyClickLockMinCps ...at this many clicks a second or faster
 * @param anomalyTapLockSamples {@code w-tap-lock} and {@code block-hit-lock}: hits in a row followed by the same w-tap or
 *                              block-hit, to the tick (pvp-issues SP-4)
 * @param anomalyRewindStretchHits {@code rewind-stretch}: the valid hits whose median excess rewind is judged (HR-3)
 * @param anomalyRewindStretchMs ...and the median, in ms past the attacker's base round trip and display delay, that
 *                               raises it
 * @param pongLagMs a second whose ack RTT runs this much over the keep-alive RTT is counted (pongs held back, review E5)
 * @param anomalyGrammarViolations input-grammar alert: actions a vanilla client can't send, since the player joined
 * @param anomalyJumpResetShare jump-reset alert (classic audit #8): the share of knocks applied on the ground whose own
 *                              window pressed jump (a rising edge) at or past this, and...
 * @param anomalyJumpResetBaseRatio ...this many times the player's own share of grounded ticks that press jump (a
 *                                  tap-hopper presses on most of them, so a high share alone accuses nobody), ...
 * @param anomalyJumpResetMinKnocks ...over at least this many grounded knocks. A human can't react inside the tick a knock
 *                                  lands; simulated, honest styles from 2% to 95% of grounded ticks and humans hitting the
 *                                  exact tick a fifth of the time by anticipation stay under 1 in 10,000 per 40 knocks,
 *                                  a macro on 95% of knocks is always flagged
 * @param traceDirectory where {@code /revival auth trace} writes RVTR files, relative to the server folder
 * @param externalMoverBlockWindowTicks after a piston or shulker box event, nearby ticks may be pushed for this many
 *                                      client ticks (a piston moves for 2-3 ticks, a shulker box lid for 10)
 * @param externalMoverBlockRange how far along its facing a piston's push lane reaches (13 at most: its head and the 12
 *                                blocks it can push; classic audit #4)
 * @param externalMoverBand in a tick a piston or shulker box may push, a claim this close to the truth on every axis is
 *                          adopted (a push moves a player at most about 0.51 a tick); the velocity stays the truth's
 * @param externalMoverDriftCap the most adopted push per axis within a {@code rebase-drift-window-ticks} window (a piston
 *                              push is 1.01 blocks)
 * @param externalMoverEntityRange a solid entity (boat, shulker, happy ghast) this close to the player's box exempts the
 *                                 tick when its client copy is not exact (a placeholder until a logged spawn, a copy
 *                                 gone inexact, a vehicle the player drove, a passenger); exact copies are the port's
 *                                 colliders. 0 turns the check off. Contact only: a wider range lets a player standing
 *                                 near one move as vanilla allows
 * @param externalMoverMaxMove in an exempt mover tick, vanilla takes a claim only this close to where the server holds
 *                             the player on every axis
 * @param externalMoverGeyserDepth how far below a player in water a potent sulfur geyser is looked for (a launching one:
 *                                 erupting or continuous, unbroken water to the feet, vanilla's 4 water blocks at most);
 *                                 0 turns it off
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
    double attackEyeOffsetCap,
    double attackEyeForecastTolerance,
    boolean blockClipNonPlayerTargets,
    double correctionBudget,
    double correctionBudgetDecay,
    int maxCorrectionsPerSecond,
    int correctionRepeatLimit,
    int correctionPersistTicks,
    double correctionPersistMin,
    int landingProbeMs,
    int landingSampleMs,
    int maxRewindMs,
    double rewindJitterFactor,
    int rewindSlackMs,
    int timerDriftMs,
    int timerAckSettleMs,
    int maxBankedTicks,
    int timerQueueCapMs,
    int catchUpGapMs,
    int rttCeilingMs,
    int ackJitterTicks,
    int maxUnackedMs,
    String vehicles,
    String ineligible,
    String apolloRules,
    int protocolKickRate,
    int teleportChunkSendTicks,
    int teleportHoldMaxTicks,
    double seedMaxMove,
    int maxAttacksPerClientTick,
    boolean heartbeatPing,
    boolean bypassAudit,
    int pathStepTicks,
    int lowJitterStepTicks,
    int lowJitterMs,
    int highJitterMs,
    int stepSwitchSeconds,
    boolean keepAlive,
    double exactRadius,
    double rangedRadius,
    int rangedLingerTicks,
    Set<Identifier> rangedItems,
    boolean preRoll,
    double respawnDistance,
    int pathBuffer,
    int repairIntervalTicks,
    int mirrorHistoryTicks,
    boolean visibilityEnabled,
    double alwaysVisibleDistance,
    int visibilityCheckIntervalTicks,
    int visibilityHideAfterChecks,
    double visibilityMaxSpeed,
    int visibilityMaxChecksPerTick,
    double visibilityEffectReach,
    boolean anomalyAlerts,
    double anomalyDoubleClickRatio,
    int anomalyMinAttackTicks,
    double anomalyAimGcdMissRatio,
    int anomalyMinTurns,
    int anomalyClickStreakTicks,
    int anomalyClickLockClicks,
    double anomalyClickLockResidual,
    double anomalyClickLockMinCps,
    int anomalyTapLockSamples,
    int anomalyRewindStretchHits,
    long anomalyRewindStretchMs,
    int pongLagMs,
    int anomalyGrammarViolations,
    double anomalyJumpResetShare,
    double anomalyJumpResetBaseRatio,
    int anomalyJumpResetMinKnocks,
    String traceDirectory,
    int externalMoverBlockWindowTicks,
    double externalMoverBlockRange,
    double externalMoverBand,
    double externalMoverDriftCap,
    double externalMoverEntityRange,
    double externalMoverMaxMove,
    int externalMoverGeyserDepth,
    boolean debugHud,
    int debugHudIntervalTicks,
    boolean debugLog
) {

    static final String PATH = "authoritative.";
    /** Defaults that changed: {@code RevivalConfig}'s migration moves a file still holding an old default onto these. */
    public static final int MAX_REWIND_MS = 600;
    public static final int RTT_CEILING_MS = 250;
    public static final double EXTERNAL_MOVER_ENTITY_RANGE = 0.1;
    public static final int MIRROR_HISTORY_TICKS = 16;
    public static final int TIMER_QUEUE_CAP_MS = 400;
    public static final double DOUBLE_CLICK_RATIO = 1.0;
    /** The most ordering violations a minute {@code AuthPlayer} keeps times for (review 3 #11: past it the kick never fired). */
    public static final int MAX_PROTOCOL_KICK_RATE = 4096;
    /** What a player aims at range with: launchers, rods and thrown items (potions are thrown at the thrower's feet). */
    static final List<String> RANGED_ITEMS = List.of("minecraft:bow", "minecraft:crossbow", "minecraft:fishing_rod", "minecraft:ender_pearl",
        "minecraft:snowball", "minecraft:egg", "minecraft:blue_egg", "minecraft:brown_egg", "minecraft:trident", "minecraft:wind_charge");

    private static Set<Identifier> items(final ConfigReader reader, final String path, final List<String> def) {
        final Set<Identifier> items = new HashSet<>();
        for (final String entry : reader.getStringList(path, def)) {
            final Identifier id = Identifier.tryParse(entry);
            if (id == null) {
                reader.problem(path, entry, "an item id such as minecraft:bow", "skipping it");
            } else {
                items.add(id);
            }
        }
        return Set.copyOf(items);
    }

    private static String apolloRules(final ConfigReader reader) {
        final String value = reader.getString(PATH + "client-profiles.apollo-rules", "follow").trim().toLowerCase(java.util.Locale.ROOT);
        if (!value.equals("follow") && !value.equals("ignore")) {
            reader.problem(PATH + "client-profiles.apollo-rules", value, "follow or ignore", "follow");
            return "follow";
        }
        return value;
    }

    private static String ineligible(final ConfigReader reader) {
        final String value = reader.getString(PATH + "ineligible", "allow").trim().toLowerCase(java.util.Locale.ROOT);
        if (!value.equals("allow") && !value.equals("deny")) {
            reader.problem(PATH + "ineligible", value, "allow or deny", "allow");
            return "allow";
        }
        return value;
    }

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
            Math.max(0.0, reader.getDouble(PATH + "attack-eye-offset-cap", 0.5)),
            Math.max(0.0, reader.getDouble(PATH + "attack-eye-forecast-tolerance", 0.03)),
            reader.getBoolean(PATH + "block-clip-non-player-targets", true),
            Math.max(0.0, reader.getDouble(PATH + "correction-budget", 0.01)),
            Math.clamp(reader.getDouble(PATH + "correction-budget-decay", 0.95), 0.0, 1.0),
            Math.max(1, reader.getInt(PATH + "max-corrections-per-second", 5)),
            Math.max(1, reader.getInt(PATH + "correction-repeat-limit", 2)),
            Math.max(1, reader.getInt(PATH + "correction-persist-ticks", 20)),
            Math.max(0.0, reader.getDouble(PATH + "correction-persist-min", 1.0E-6)),
            Math.max(50, reader.getInt(PATH + "landing-probe-ms", 500)),
            Math.max(100, reader.getInt(PATH + "landing-sample-ms", 2000)),
            Math.max(0, reader.getInt(PATH + "max-rewind-ms", MAX_REWIND_MS)),
            Math.max(0.0, reader.getDouble(PATH + "rewind-jitter-factor", 1.0)),
            Math.max(0, reader.getInt(PATH + "rewind-slack-ms", 150)),
            Math.max(0, reader.getInt(PATH + "timer-drift-ms", 120)),
            Math.max(0, reader.getInt(PATH + "timer-ack-settle-ms", 100)),
            Math.max(1, reader.getInt(PATH + "max-banked-ticks", 10)),
            Math.max(0, reader.getInt(PATH + "timer-queue-cap-ms", TIMER_QUEUE_CAP_MS)),
            Math.max(0, reader.getInt(PATH + "catch-up-gap-ms", 15)),
            Math.max(50, reader.getInt(PATH + "rtt-ceiling-ms", RTT_CEILING_MS)),
            Math.max(0, reader.getInt(PATH + "ack-jitter-ticks", 4)),
            Math.max(100, reader.getInt(PATH + "max-unacked-ms", 1500)),
            vehicles(reader),
            ineligible(reader),
            apolloRules(reader),
            Math.clamp(reader.getInt(PATH + "client-profiles.protocol-kick-rate", 20), 0, MAX_PROTOCOL_KICK_RATE),
            Math.max(0, reader.getInt(PATH + "teleport-chunk-send-ticks", 2)),
            Math.max(0, reader.getInt(PATH + "teleport-hold-max-ticks", 100)),
            Math.max(0.1, reader.getDouble(PATH + "seed-max-move", 10.0)),
            Math.max(1, reader.getInt(PATH + "max-attacks-per-client-tick", 1)),
            reader.getBoolean(PATH + "heartbeat-ping", true),
            reader.getBoolean(PATH + "bypass-audit", true),
            Math.max(1, reader.getInt(PATH + "replication.path-step-ticks", 2)),
            Math.max(1, reader.getInt(PATH + "replication.low-jitter-step-ticks", 1)),
            Math.max(0, reader.getInt(PATH + "replication.low-jitter-ms", 25)),
            Math.max(0, reader.getInt(PATH + "replication.high-jitter-ms", 35)),
            Math.max(1, reader.getInt(PATH + "replication.step-switch-seconds", 5)),
            reader.getBoolean(PATH + "replication.keep-alive", true),
            Math.max(0.0, reader.getDouble(PATH + "replication.exact-radius", 24.0)),
            Math.max(0.0, reader.getDouble(PATH + "replication.ranged-radius", 0.0)),
            Math.max(0, reader.getInt(PATH + "replication.ranged-linger-ticks", 100)),
            items(reader, PATH + "replication.ranged-items", RANGED_ITEMS),
            reader.getBoolean(PATH + "replication.pre-roll", true),
            Math.clamp(reader.getDouble(PATH + "replication.respawn-distance", 48.0), 1.0, 63.0),
            Math.max(8, reader.getInt(PATH + "replication.path-buffer", 64)),
            Math.max(1, reader.getInt(PATH + "replication.repair-interval-ticks", 20)),
            Math.max(8, reader.getInt(PATH + "mirror-history-ticks", MIRROR_HISTORY_TICKS)),
            reader.getBoolean(PATH + "visibility.enabled", false),
            Math.max(0.0, reader.getDouble(PATH + "visibility.always-visible-distance", 8.0)),
            Math.max(1, reader.getInt(PATH + "visibility.check-interval-ticks", 2)),
            Math.max(1, reader.getInt(PATH + "visibility.hide-after-checks", 2)),
            Math.max(0.0, reader.getDouble(PATH + "visibility.max-speed", 1.0)),
            Math.max(1, reader.getInt(PATH + "visibility.max-checks-per-tick", 512)),
            Math.max(0.0, reader.getDouble(PATH + "visibility.effect-reach", 1.5)),
            reader.getBoolean(PATH + "stats.anomaly-alerts", true),
            Math.clamp(reader.getDouble(PATH + "stats.double-click-ratio", DOUBLE_CLICK_RATIO), 0.0, 1.0),
            Math.max(1, reader.getInt(PATH + "stats.double-click-min-attack-ticks", 40)),
            Math.clamp(reader.getDouble(PATH + "stats.aim-gcd-miss-ratio", 0.5), 0.0, 1.0),
            Math.max(1, reader.getInt(PATH + "stats.aim-min-turns", 200)),
            Math.max(1, reader.getInt(PATH + "stats.click-streak-ticks", 100)),
            Math.clamp(reader.getInt(PATH + "stats.click-lock-clicks", gg.hcfrevival.revival.auth.input.CadenceWatch.LOCK_CLICKS), 10, 1000),
            Math.max(0.0, reader.getDouble(PATH + "stats.click-lock-residual", gg.hcfrevival.revival.auth.input.CadenceWatch.LOCK_RESIDUAL)),
            Math.max(0.1, reader.getDouble(PATH + "stats.click-lock-min-cps", gg.hcfrevival.revival.auth.input.CadenceWatch.LOCK_MIN_CPS)),
            Math.max(2, reader.getInt(PATH + "stats.tap-lock-samples", gg.hcfrevival.revival.auth.input.TapWatch.LOCK_SAMPLES)),
            Math.clamp(reader.getInt(PATH + "stats.rewind-stretch-hits", gg.hcfrevival.revival.auth.input.RewindWatch.HITS), 5, 500),
            Math.max(0L, (long) reader.getInt(PATH + "stats.rewind-stretch-ms", (int) gg.hcfrevival.revival.auth.input.RewindWatch.STRETCH_MS)),
            Math.max(0, reader.getInt(PATH + "stats.pong-lag-ms", 150)),
            Math.max(1, reader.getInt(PATH + "stats.grammar-violations", 3)),
            Math.clamp(reader.getDouble(PATH + "stats.jump-reset-share", 0.65), 0.0, 1.0),
            Math.max(1.0, reader.getDouble(PATH + "stats.jump-reset-base-ratio", 4.0)),
            Math.max(1, reader.getInt(PATH + "stats.jump-reset-min-knocks", 40)),
            reader.getString(PATH + "trace.directory", "traces"),
            Math.max(0, reader.getInt(PATH + "external-movers.block-window-ticks", 12)),
            Math.max(0.0, reader.getDouble(PATH + "external-movers.block-range", 14.0)),
            Math.max(0.0, reader.getDouble(PATH + "external-movers.band", 0.6)),
            Math.max(0.0, reader.getDouble(PATH + "external-movers.drift-cap", 1.6)),
            Math.max(0.0, reader.getDouble(PATH + "external-movers.entity-range", EXTERNAL_MOVER_ENTITY_RANGE)),
            Math.max(0.0, reader.getDouble(PATH + "external-movers.max-move", 1.0)),
            Math.max(0, reader.getInt(PATH + "external-movers.geyser-depth", 24)),
            reader.getBoolean(PATH + "debug.hud", false),
            Math.max(1, reader.getInt(PATH + "debug.hud-interval-ticks", 5)),
            reader.getBoolean(PATH + "debug.log", false)
        );
    }
}
