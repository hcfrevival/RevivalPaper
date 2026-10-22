# Requests from the netcode to the movement port (`auth.sim`)

The netcode (`auth`, `auth.net`, `auth.view`) owns `PortSimulation`, the adapter between `AuthPlayer` and
`MovementSim`/`SimPlayer`. These are things it needs from `auth.sim` and can't write itself. Newest first.

## 1. Seed velocity from a claimed move (2026-10-21)

**Need:** `MovementSim.velocityAfterMove(SimPlayer p, double fromX, fromY, fromZ, double toX, toY, toZ, SimWorld world, double[] out)`:
the `deltaMovement` the client has at the end of a tick whose `move` went from `from` to `to`, run through the port's
post-move rules (collision zeroing, `updateEntityMovementAfterFallOn`, block speed factor, then `travel`'s
gravity/drag/friction for the state `p` is in). Design §6.2 calls this the belief velocity.

**Why:** the simulation is seeded from server-owned state (§15.3) when authority starts, after warmup and when an
exempt state ends. The position is exact, but the only server-side velocity is the client-reported known movement,
which is the move, not the velocity. A player seeded mid-air then stays off by about one tick of gravity until it lands
(seen live: a fake client that joins in the air at y=0 and falls to y=-60 diverges by about 0.07 every tick until it
lands). With this call the seed becomes exact after one claimed move. It is also the fallback belief for corrections
when the belief state isn't valid.

**Where it's used:** `PortSimulation.seed(...)` (new overload taking the previous claim), `AuthPlayer.seedFromServer`.
