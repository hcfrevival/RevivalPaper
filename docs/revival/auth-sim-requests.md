# Requests from the netcode to the movement port (`auth.sim`)

The netcode (`auth`, `auth.net`, `auth.view`) owns `PortSimulation`, the adapter between `AuthPlayer` and
`MovementSim`/`SimPlayer`. These are things it needs from `auth.sim` and can't write itself. Newest first.

## 2. Colliders for solid entities (2026-10-22) — sim side done 2026-10-22

**Done:** the port collides with boats, shulkers and happy ghasts exactly as a 26.3 client does, from what the netcode
hands it through `SimWorld.collectEntityColliders(SimBox area, SimBoxList out)` (the method `WorldView` already has; its
body is the netcode's part). The port does the rest per query, as `Level.getEntities` and `EntityGetter.getEntityCollisions`
do on the client: the entity sections a query searches, box intersection, `canCollideWith` (the happy ghast's rule needs
the player's position at that query), `Shapes.create(box)`, the order entities, border, blocks; the same boxes feed
`Entity.collide` (one gather over the move plus the 26.3 step-height expansion, shared by the sweep and the step-up),
`noCollision` (crouch and stand checks `canPlayerFitWithinBlocksAndEntitiesWhen`, the sneak back-off at edges,
`jumpOutOfFluid`'s `isFree`) and auto-jump's obstacle scan. Proof (auth-sim.md §4): real vanilla boats, shulkers and
happy ghasts in the oracle's client-side level, stationary, moving with interpolation, teleported, peeking, dying, placed
under a falling player, and `RESULTS_PLACEHOLDER`.

What the netcode must supply, per call, for this player's client:

1. **Which entities.** Every entity of these types the client holds (`AddEntity` applied, `RemoveEntities` not yet), as
   `out.add(minX, minY, minZ, maxX, maxY, maxZ, x, y, z, rule)`:

   | Entity | Rule | Never |
   |---|---|---|
   | any `AbstractBoat` (boats, chest boats, rafts) | `SimWorld.ENTITY_SOLID` | |
   | `Shulker` | `ENTITY_SOLID` | dead on the client (health ≤ 0 as last sent; it stays in the level for its 20 death ticks) |
   | `HappyGhast`, adult | `ENTITY_SOLID` while `STAYS_STILL` is set (its still timeout), else `ENTITY_SOLID_FROM_ABOVE` | a baby, a dead one |

   Never an entity riding this player, directly or not (`isPassengerOfSameVehicle`: its root vehicle is the player). A
   player who rides is exempt anyway (`PASSENGER`). No other type collides (only these override `canBeCollidedWith`):
   not players, minecarts, armor stands or other mobs. `ENTITY_SOLID_FROM_ABOVE` is the client's
   `HappyGhast.canBeCollidedWith`: solid only for a player whose `y >= box.maxY` at that query; the port tests it.
2. **Which area.** At least every such entity whose box intersects `area`; more is fine (the port filters, so a cheap
   superset, e.g. a few blocks around, is right). The port asks once per `tick`/`velocityAfterMove`/`collide` for the
   area of its first query plus 2 blocks, and again only if a later query leaves that, so one answer usually serves a
   tick. Do not filter by the client's entity sections: the port does that lookup itself (it misses a box that reaches
   in from a section the query does not search, as the client does: a big shulker's lid, one hanging over a section
   line).
3. **Which box, and when.** The entity's bounding box and position (`Entity.position()`, which picks its section) as the
   client holds them while its local player ticks, i.e. after the packets that client applied before that tick and after
   the entity's own client ticks so far. The client ticks entities in the order they joined its ticking list (an
   `Int2ObjectLinkedOpenHashMap`): the local player joins its level first on login and on a dimension change, so every
   other entity ticks **after** the player, and in client tick N the player sees each one as its tick N-1 left it (plus
   any packet since that snaps). After a respawn in the same dimension the new player joins last, so the entities already
   there tick before it (their tick N is done when it moves). Per type:
   - **Boats** (not driven by this client): `LinearInterpolationHandler` with 3 steps. A move packet (`MoveEntity` with
     a position, decoded with the client's `VecDeltaCodec`; `EntityPositionSync`; `TeleportEntity`, absolute or
     relative) sets the target and 3 remaining steps (`interpolateTo`: nothing new if the target is unchanged); each boat
     tick (`commonTick` → `interpolate`) moves `pos = pos.lerp(target, 1.0 / remaining)` (`Vec3.lerp`: `x + a * (tx - x)`)
     and counts down, reaching the target on the third tick. A jump of more than 64 blocks, or a sync for an entity not
     ticking, snaps (`snapTo`). The client never moves a boat it does not drive any other way (its tick zeroes its
     velocity; a shulker lid could `move` it). Box: `EntityDimensions.makeBoundingBox`, `x ± (double) 0.6875F`, `y .. y + (double) 0.5625F`, `z ±
     0.6875F`.
   - **Shulkers**: no interpolation (`InterpolationHandler.NO_OP`): a move packet sets the position at once. Box:
     `Shulker.getProgressAabb(scale, attachFace.getOpposite(), 0.5F - Mth.sin((0.5F + peek) * 3.1415927F) * 0.5F, pos)`
     with `scale` the `SCALE` attribute capped at 3 (`sanitizeScale`), the attach face from entity data (the box is
     rebuilt the moment it arrives), and `peek` the client's `currentPeekAmount`, which each shulker tick moves 0.05F
     towards `rawPeek * 0.01F` (`updatePeekAmount`, clamped to the target), `rawPeek` being the `DATA_PEEK_ID` byte.
   - **Happy ghasts**: a `LivingEntity`, so `SteppedInterpolationHandler` (steps of the type's update interval, a queue
     of targets and a catch-up speed, see the class). Box: `4.0F x 4.0F` scaled by `getScale()` (the `SCALE` attribute
     capped at 1), `makeBoundingBox` at the position. Babies (0.2375) never collide.
4. **Which order.** The client's, `EntitySectionStorage.getEntities`: by entity section (section x ascending; within an
   x, the section key's z then y bits as unsigned numbers, ascending), and within a section by when the entity entered
   it on the client (its `AddEntity`, or the client moving it there). The port keeps the order it is given. Only ties at
   `Shapes.collide`'s 1e-7 cut and auto-jump's first obstacle can tell; the server's own order is a fine approximation.

**Mirroring:** exactness needs the client's interpolated positions above (the viewer mirror the combat side is building
for non-players). Until then the server's boxes are exact for anything standing still (a parked boat, a closed shulker, a
ghast on its still timeout) and lag a moving one by its interpolation (up to 3 ticks of a boat's travel).

**Still external movers** (keep flagging these ticks `externalMover`, auth-sim.md §6):

- a shulker opening its lid over the player: `Shulker.onPeekAmountChange` moves every entity in
  `getProgressDeltaAabb(scale, face.getOpposite(), oldPhysicalPeek, newPhysicalPeek, pos)` with `Entity.move(SHULKER)`
  in the shulker's tick, teams or not;
- entity pushes (`AbstractBoat.tick` pushes the local player away by `Entity.push`, mobs through `pushEntities`) only
  while the client's own team collision rule is not `NEVER`; the enforce precondition sets it, so none happen.

**Where it's used:** `ExternalMovers.near` can stop returning `SOLID` for boats, shulkers and happy ghasts once
`WorldView.collectEntityColliders` hands them over (keep the geyser and the lid push).

**Asked for:** the port to collide with the solid entities a 26.3 client collides with: boats (`AbstractBoat.canBeCollidedWith`),
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
