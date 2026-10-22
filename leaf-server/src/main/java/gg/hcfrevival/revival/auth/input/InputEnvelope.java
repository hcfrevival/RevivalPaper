package gg.hcfrevival.revival.auth.input;

/**
 * Bounds what can't be made authoritative (design §10): {@code max-attacks-per-client-tick}. The client applies its own
 * attack prediction for every attack it sends, and so does the simulation; attacks past the cap are just not delivered.
 */
public final class InputEnvelope {

    private int attacksThisTick;
    private long dropped;

    /** An attack in the current window: whether it is within the cap and may be delivered. */
    public boolean admitAttack(final int maxPerTick) {
        this.attacksThisTick++;
        if (this.attacksThisTick > maxPerTick) {
            this.dropped++;
            return false;
        }
        return true;
    }

    public int attacksThisTick() {
        return this.attacksThisTick;
    }

    public void endTick() {
        this.attacksThisTick = 0;
    }

    public long dropped() {
        return this.dropped;
    }
}
