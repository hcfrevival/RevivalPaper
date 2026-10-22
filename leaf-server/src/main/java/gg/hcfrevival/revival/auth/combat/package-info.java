/**
 * Lag-compensated hits: a bit-exact mirror of every remote player as each viewer's 26.3 client holds it
 * ({@link gg.hcfrevival.revival.auth.combat.RemoteMirror}, {@link gg.hcfrevival.revival.auth.combat.MirrorBook}), the
 * client's attack ray ({@link gg.hcfrevival.revival.auth.combat.PickRay}) and the hit check that combines them
 * ({@link gg.hcfrevival.revival.auth.combat.HitValidator}). Pure logic: hooks feed it packets with their ack ticks and
 * ask it about attacks. Design: docs/revival/auth-combat.md.
 */
@NullMarked
package gg.hcfrevival.revival.auth.combat;

import org.jspecify.annotations.NullMarked;
