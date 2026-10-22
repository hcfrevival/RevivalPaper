package gg.hcfrevival.revival.auth;

import net.minecraft.world.entity.Entity;

/**
 * Lag-compensated hit validation (design §7.1). The combat package ({@code auth.combat.HitValidator}) implements it and
 * installs itself with {@link Authority#setAttackJudge}; until then every attack is accepted.
 * <p>
 * Called at {@code TickEnd(T)} for each {@code Attack} in window {@code W_T}, in client order, after the tick's acked
 * packets were applied and before the tick's movement is simulated. What the attacker's client saw at that moment:
 * <ul>
 *   <li>eye: {@code attacker.simulation().xo() + 1.0 * (x() - xo())} (end of tick T-1) plus {@code eyeHeight()};</li>
 *   <li>rotation: {@link AuthPlayer#tickYRot()}/{@link AuthPlayer#tickXRot()} (tick T's claim, or the last one);</li>
 *   <li>targets: the attacker's mirrors (fed through {@link ViewerMirrors}), blocks: {@link AuthPlayer#world()};</li>
 *   <li>reach: the client's interaction-range attributes, {@link AuthPlayer#view()}.</li>
 * </ul>
 */
@FunctionalInterface
public interface AttackJudge {

    int VALID = 1;
    int INVALID = 0;
    /** The judge has no exact data for this target (non-player, missing ack): vanilla/Paper validation decides. */
    int UNKNOWN = -1;

    AttackJudge ACCEPT_ALL = (attacker, target, tick) -> VALID;

    int judge(AuthPlayer attacker, Entity target, int tick);
}
