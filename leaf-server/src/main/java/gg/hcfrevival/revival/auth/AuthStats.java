package gg.hcfrevival.revival.auth;

/**
 * Per-player counters for {@code /revival auth inspect}: how often the simulation agreed with the client to the bit,
 * how far it was off when it didn't, why ticks were exempt, and what the transaction layer saw. Main thread only.
 */
public final class AuthStats {

    // Exempt reasons: why a client tick was not simulated
    public static final int EXEMPT_NONE = 0;
    public static final int EXEMPT_NOT_LOADED = 1;
    public static final int EXEMPT_RIDING = 2;
    public static final int EXEMPT_SLEEPING = 3;
    public static final int EXEMPT_DEAD = 4;
    public static final int EXEMPT_SPECTATOR_CAMERA = 5;
    public static final int EXEMPT_SPECTATOR = 6;
    public static final int EXEMPT_UNSEEDED = 7;
    public static final int EXEMPT_REFUSED = 8;
    public static final int EXEMPT_WARMUP = 9;
    public static final int EXEMPT_NO_SIMULATION = 10;
    /** The port threw: the tick falls back to the claim and the next one re-seeds. */
    public static final int EXEMPT_SIMULATION_ERROR = 11;
    /**
     * The player's box is over a chunk the server has loaded but our record of the client's chunks lacks (a chunk sent
     * before authority started that the record missed, one still on its way): bookkeeping, not the client, so the tick
     * goes to vanilla and the truth re-seeds after (no fall through a floor the client stands on).
     */
    public static final int EXEMPT_CHUNK_UNKNOWN = 12;
    /** Reasons from {@link Simulation#unsupported} start here (the port's own codes). */
    public static final int EXEMPT_SIMULATION_BASE = 16;
    public static final int EXEMPT_REASONS = 32;

    private static final String[] NAMES = new String[EXEMPT_REASONS];

    static {
        NAMES[EXEMPT_NONE] = "none";
        NAMES[EXEMPT_NOT_LOADED] = "not-loaded";
        NAMES[EXEMPT_RIDING] = "riding";
        NAMES[EXEMPT_SLEEPING] = "sleeping";
        NAMES[EXEMPT_DEAD] = "dead";
        NAMES[EXEMPT_SPECTATOR_CAMERA] = "spectator-camera";
        NAMES[EXEMPT_SPECTATOR] = "spectator";
        NAMES[EXEMPT_UNSEEDED] = "unseeded";
        NAMES[EXEMPT_REFUSED] = "timer-refused";
        NAMES[EXEMPT_WARMUP] = "warmup";
        NAMES[EXEMPT_NO_SIMULATION] = "no-simulation";
        NAMES[EXEMPT_SIMULATION_ERROR] = "simulation-error";
        NAMES[EXEMPT_CHUNK_UNKNOWN] = "chunk-unknown";
        for (int i = EXEMPT_SIMULATION_BASE; i < EXEMPT_REASONS; i++) {
            NAMES[i] = "simulation-" + (i - EXEMPT_SIMULATION_BASE);
        }
        NAMES[EXEMPT_SIMULATION_BASE + gg.hcfrevival.revival.auth.sim.ExemptReason.RIPTIDE.ordinal()] = "riptide";
        NAMES[EXEMPT_SIMULATION_BASE + gg.hcfrevival.revival.auth.sim.ExemptReason.EXTERNAL_MOVER.ordinal()] = "external-mover";
    }

    public static String exemptName(final int reason) {
        return reason >= 0 && reason < EXEMPT_REASONS ? NAMES[reason] : "unknown-" + reason;
    }

    long windows;
    long simulated;
    long compared;
    long exact;
    long withinThreshold;
    long overThreshold;
    long flagMismatches;
    long missingPositionViolations;
    double maxDivergence;
    double sumDivergence;
    final long[] exempt = new long[EXEMPT_REASONS];
    long corrections;
    long correctionsAccepted;
    long attacksQueued;
    long attacksAccepted;
    long attacksRejected;
    long attacksOverCap;
    long serverTeleports;
    long teleportAcceptMismatches;
    long reverts;
    long lostSync;
    long forcedEntries;
    long violations;
    long missedReminders;
    long externalMovers;
    long simulationErrors;
    long hotbarBranches;
    long attackStrengthBranches;
    long rebasesRefused;
    long mountsRefused;
    long grammarDropped;
    long bypasses;
    long bypassMovers;
    long predictionsRefused;
    long absoluteCorrections;
    long staleAccepts;
    long speedResyncs;
    long inexactSeeds;
    long hiddenChecks;
    long culledEffects;
    long freezes;
    long pushesAdopted;
    long mirrorRepairs;
    long attacksOverCps;
    long pongsBehindKeepAlive;
    long teleportsHeld;
    long teleportChunksSent;
    long moverClaimsRefused;
    long violationLines;

    void countExempt(final int reason) {
        this.exempt[Math.clamp(reason, 0, EXEMPT_REASONS - 1)]++;
    }

    void divergence(final double distance, final double epsilon, final double threshold) {
        this.compared++;
        this.sumDivergence += distance;
        if (distance > this.maxDivergence) {
            this.maxDivergence = distance;
        }
        if (distance <= epsilon) {
            this.exact++;
        } else if (distance <= threshold) {
            this.withinThreshold++;
        } else {
            this.overThreshold++;
        }
    }

    public long windows() {
        return this.windows;
    }

    public long simulated() {
        return this.simulated;
    }

    public long compared() {
        return this.compared;
    }

    public long exact() {
        return this.exact;
    }

    /** Share of compared ticks whose claim was within {@code rebase-epsilon} of the simulation. */
    public double exactShare() {
        return this.compared == 0 ? 0.0 : (double) this.exact / this.compared;
    }

    public long withinThreshold() {
        return this.withinThreshold;
    }

    public long overThreshold() {
        return this.overThreshold;
    }

    public long flagMismatches() {
        return this.flagMismatches;
    }

    public long missingPositionViolations() {
        return this.missingPositionViolations;
    }

    public double maxDivergence() {
        return this.maxDivergence;
    }

    public double meanDivergence() {
        return this.compared == 0 ? 0.0 : this.sumDivergence / this.compared;
    }

    public long exempt(final int reason) {
        return reason >= 0 && reason < EXEMPT_REASONS ? this.exempt[reason] : 0L;
    }

    public long corrections() {
        return this.corrections;
    }

    public long correctionsAccepted() {
        return this.correctionsAccepted;
    }

    public long attacksQueued() {
        return this.attacksQueued;
    }

    public long attacksAccepted() {
        return this.attacksAccepted;
    }

    public long attacksRejected() {
        return this.attacksRejected;
    }

    public long attacksOverCap() {
        return this.attacksOverCap;
    }

    public long serverTeleports() {
        return this.serverTeleports;
    }

    public long teleportAcceptMismatches() {
        return this.teleportAcceptMismatches;
    }

    public long reverts() {
        return this.reverts;
    }

    public long lostSync() {
        return this.lostSync;
    }

    /** Logged packets applied at their ack deadline because the client had not acked them (§15.1). */
    public long forcedEntries() {
        return this.forcedEntries;
    }

    /** Protocol violations: pongs, accepts or predictions a vanilla client can't produce. */
    public long violations() {
        return this.violations;
    }

    /** Ticks where the 20-tick position reminder was due and no position came. */
    public long missedReminders() {
        return this.missedReminders;
    }

    /** Ticks re-run as a number-key hotbar change because that reproduced the claim (report 02 H9). */
    public long hotbarBranches() {
        return this.hotbarBranches;
    }

    public long attackStrengthBranches() {
        return this.attackStrengthBranches;
    }

    public long rebasesRefused() {
        return this.rebasesRefused;
    }

    public long mountsRefused() {
        return this.mountsRefused;
    }

    public long grammarDropped() {
        return this.grammarDropped;
    }

    public long bypasses() {
        return this.bypasses;
    }

    public long bypassMovers() {
        return this.bypassMovers;
    }

    public long predictionsRefused() {
        return this.predictionsRefused;
    }

    public long absoluteCorrections() {
        return this.absoluteCorrections;
    }

    public long staleAccepts() {
        return this.staleAccepts;
    }

    public long speedResyncs() {
        return this.speedResyncs;
    }

    public long inexactSeeds() {
        return this.inexactSeeds;
    }

    public long hiddenChecks() {
        return this.hiddenChecks;
    }

    public long culledEffects() {
        return this.culledEffects;
    }

    public long freezes() {
        return this.freezes;
    }

    public long simulationErrors() {
        return this.simulationErrors;
    }

    /** Pistons, shulker boxes and rod pulls the client applied. */
    public long externalMovers() {
        return this.externalMovers;
    }

    /**
     * Seconds the ack round trip (our pongs, answered on the main thread) ran more than {@code stats.pong-lag-ms} behind the keep-alive
     * round trip (answered on the netty thread): a client delaying its pongs (review E5). Evidence for staff.
     */
    public long pongsBehindKeepAlive() {
        return this.pongsBehindKeepAlive;
    }

    /** Server teleports held until the chunk under the destination was on its way to the client (pvp-issues PR-8). */
    public long teleportsHeld() {
        return this.teleportsHeld;
    }

    /** Destination chunks sent ahead of a held teleport because Moonrise hadn't sent them yet. */
    public long teleportChunksSent() {
        return this.teleportChunksSent;
    }

    /** Attacks the click cap refused (neue-combat §4.3): no token, not judged, not delivered. */
    public long attacksOverCps() {
        return this.attacksOverCps;
    }

    /** Re-pairs of a tracked player whose mirror was missing or inexact outside the path feed's own repair. */
    public long mirrorRepairs() {
        return this.mirrorRepairs;
    }

    /** Claims adopted within the push band in a tick a piston or shulker box may push (review E2). */
    public long pushesAdopted() {
        return this.pushesAdopted;
    }

    /** Claims vanilla never saw in an exempt mover tick: too far from where the server holds the player (review E2). */
    public long moverClaimsRefused() {
        return this.moverClaimsRefused;
    }
}
