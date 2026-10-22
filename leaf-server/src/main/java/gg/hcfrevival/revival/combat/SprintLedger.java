package gg.hcfrevival.revival.combat;

import gg.hcfrevival.revival.config.KnockbackProfile;

/**
 * One sprint knock per sprint start, as 1.7.10 played. A 1.7 sprint knock cleared the server's sprint flag and the
 * client re-sprinted without telling the server, so the next sprint knock needed a fresh START_SPRINTING (a w-tap).
 * 1.21.2+ clients copy a server-side {@code setSprinting(false)} back and can get stuck not sprinting, so legacy hits
 * leave the flag alone and this ledger tracks the arm instead: START_SPRINTING arms it, a landed legacy hit consumes
 * it, and only STOP then START re-arms it.
 * <p>
 * Under authority (enforce) the simulation owns the ledger ({@link #simulated}): it starts and stops it from the client's
 * real sprint, and sprint packets move nothing. A spoofed STOP_SPRINTING after each hit would otherwise clear the latch
 * the held sprint key re-arms through, and every hit would get the armed knock without a w-tap (classic audit #2).
 * <p>
 * Owned by the player's tick thread: sprint packets and hits are both handled there.
 */
public final class SprintLedger {

    private boolean sprinting;
    private boolean armed;
    private boolean armedOnGround;
    private boolean simulated;

    /** The authoritative simulation owns the ledger (enforce) or the client's sprint packets do. */
    public void simulated(final boolean simulated) {
        this.simulated = simulated;
    }

    public boolean simulated() {
        return this.simulated;
    }

    /** START_SPRINTING from the client, or the simulation's sprint start. A repeat without a stop in between doesn't
     * re-arm. Returns whether it armed. */
    public boolean start(final boolean onGround) {
        if (this.sprinting) {
            return false;
        }
        this.sprinting = true;
        this.armed = true;
        this.armedOnGround = onGround;
        return true;
    }

    /** STOP_SPRINTING from the client: ignored while the simulation owns the ledger. */
    public void stop() {
        if (!this.simulated) {
            this.stopped();
        }
    }

    /** The sprint stopped: the client's packet, or the simulation's own stop. */
    public void stopped() {
        this.sprinting = false;
        this.armed = false;
    }

    /** Re-arms a held sprint, for a sword block (the 1.7 block-hit); does nothing when not sprinting. */
    public void rearm(final boolean onGround) {
        if (this.sprinting) {
            this.armed = true;
            this.armedOnGround = onGround;
        }
    }

    /** A legacy hit landed with a sprint knock. Returns whether that used up an armed sprint knock. */
    public boolean consume() {
        final boolean wasArmed = this.armed;
        this.armed = false;
        return wasArmed;
    }

    public boolean armed(final boolean requiresGround) {
        return this.armed && (!requiresGround || this.armedOnGround);
    }

    /** The sprint part of the extra-knockback level: the armed level after a fresh sprint start, the held level otherwise. */
    public double level(final boolean sprinting, final KnockbackProfile profile) {
        if (!sprinting) {
            return 0.0;
        }
        return this.armed(profile.sprintArmRequiresGround()) ? profile.sprintArmedLevel() : profile.sprintHeldLevel();
    }
}
