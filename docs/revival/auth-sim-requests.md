# Requests from the netcode to the movement port (`auth.sim`)

The netcode (`auth`, `auth.net`, `auth.view`) owns `PortSimulation`, the adapter between `AuthPlayer` and
`MovementSim`/`SimPlayer`. These are things it needs from `auth.sim` and can't write itself. Newest first.

## 2. Colliders for solid entities (2026-10-22)

**Need:** the port to collide with the solid entities a 26.3 client collides with: boats (`AbstractBoat.canBeCollidedWith`),
shulkers, and adult happy ghasts (standing on them, and the ghast carrying its riders' platform), read from a list the
netcode passes in (their boxes as this client shows them: the viewer's mirror once the combat side mirrors non-players,
the server's boxes until then). `SimWorld` could grow `entityColliders(AABB sweep, out)`.

**Why:** review E2. Today a tick touching one of them is the port's `EXTERNAL_MOVER` exemption: vanilla takes the claim
(bounded by `external-movers.max-move`, 1 block per axis since 2026-10-22, and contact only). With colliders the
exemption goes and the truth stays authoritative next to boats (HCF boat clutches, shulker bases).

**Where it's used:** `ExternalMovers.near` would stop returning `SOLID` for entities the port collides with.

## 1. Seed velocity from a claimed move (2026-10-21) — done 2026-10-21

**Done:** `MovementSim.velocityAfterMove(p, fromX, fromY, fromZ, toX, toY, toZ, world, out)`, plus an overload taking the
tick's sent `Input` after `p` (use it: the keys remove `VELOCITY_KEYS`, 16% of the oracle's ticks). See auth-sim.md §3.4.

- `p` is the client's state at the start of the claimed tick (what `tick` would get: the previous tick's flags, ground,
  support, stuck multiplier, effects, attributes, keys); its position, velocity and rotation are never read, and it is
  not modified. `from`/`to` are the two claims of one tick: skip a pair around a tick with no position claim (the 2e-4
  rule), its `from` is not exact.
- It returns 0 when `out` is the client's velocity bit for bit, else `VELOCITY_X/Y/Z` for the axes that are not plus
  reason bits; `out` always holds the most likely value (NaN only with `VELOCITY_NO_TICK`). `VELOCITY_ROUNDED` (the
  claim's position rounding, under an ulp of the position) and `VELOCITY_UNMOVED` (under 3.2e-4 on an axis that did not
  move) are bounded; `COLLIDED`, `EDGE`, `KEYS`, `PRE_MOVE` are not.
- From two claims the result is almost never bit for bit on a moving axis: `from + move` rounds to the position's ulp,
  so the move's last bits are gone (`VELOCITY_ROUNDED`). For the seed that is still the fix for the case below: a
  client seeded mid-air falls with a velocity within an ulp of the position instead of a tick of gravity off, and a
  fall from rest at y = 0 is exact.
- The result is before attached rockets: call `fireworkBoost` per rocket after it, as after `tick`.
- Cost: about 30-120 µs per call with the keys, 200-600 µs without; allocation-free after the first call. For seeds and
  corrections, not every tick.
- Proof (auth-sim.md §4 item 5): 0 wrong determined axes on 4.8M oracle ticks, the 80 real-client traces and the 60k
  fuzz-script ticks, with the state's position, velocity and rotation poisoned.

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

**Where it's used:** `PortSimulation.seedFromMove(...)`, `AuthPlayer.seedFromServer`: when the last two claims were
consecutive ticks ending where the server holds the player (an exempt stretch the vanilla handler moved through), the
seed takes the velocity the port works out; after a respawn or login the seed stands still (a new `LocalPlayer`); else
the server's known movement, counted as an inexact seed. Wired 2026-10-21.
