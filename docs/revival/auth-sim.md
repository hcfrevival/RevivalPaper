# The movement port (`auth.sim`)

Status: stage 2 done, 2026-10-21, branch `revival/authoritative`; `velocityAfterMove` and the missing-chunk fix
2026-10-22; solid entities (boats, shulkers, happy ghasts) 2026-10-22. Design: `authoritative.md` §4-5. Research:
report 01 (the client movement pipeline and the proposed port), report 02 (what the client sends and applies), report
07 (the rigs). This file covers what is ported, what proves it exact, what it costs and what stays out of scope.

`gg.hcfrevival.revival.auth.sim` is a line-by-line port of the vanilla 26.3 **client's** local-player movement tick. It
runs on the server what each player's client runs, bit for bit: same float/double types, same operation order, the
`Mth` sine table, raw unwrapped yaw floats, vanilla's own collision algorithm, and the client-only `LocalPlayer` paths
that `ServerPlayer` never runs (input, sprint and flight toggles, auto-jump, the suffocation push, the minor-collision
angle test). Pure logic, no hooks. It compiles unchanged against the vanilla 26.3 server jar and the RevivalPaper
server jar, so the same sources are proven against vanilla offline and run on the server.

## 1. What is where

| Class | Role |
|---|---|
| `MovementSim` | The tick (`tick`), the client's own actions (attack, item use) and every server→client change the netcode applies between ticks, plus `fireworkBoost` for attached rockets, and `velocityAfterMove` (§3.4). Holds scratch state; one per thread. |
| `MoveVelocity` | `velocityAfterMove`'s work: the tick's start and its aiStep up to the move for each key combination, then the tick's after-move half for every reading of the move the world allows (§3.4). Created on first use. |
| `SimPlayer` | The whole client-side state of one player (report 01 §11.2): kinematics, pose and dimensions, contact flags, supporting block, fall distance, stuck multiplier, fluid trackers, input, client timers (auto-jump included), shared flags, abilities, game modes, food, the client's copies of items, attributes and effects, exempt states, `movementThisTick`. Public fields; `copyFrom` forks it without allocating. |
| `SimAttributes` | The attributes movement reads, with client `AttributeInstance` semantics: base + modifiers keyed by `Identifier`, fastutil `Object2ObjectOpenHashMap`/`Object2ObjectArrayMap` fed the client's history (so several `ADD_MULTIPLIED_TOTAL` modifiers multiply in the client's order), vanilla ranges, `applyUpdate` = `handleUpdateAttributes`. The maps are subclassed only to copy their tables and walk their values in fastutil's iterator order without allocating. |
| `SimWorld` | The narrow world interface the port reads (report 01 §11.3): block and fluid states, chunk loaded, section has fluid, height range, border bounds, the solid entities the client holds (box, position, rule; auth-sim-requests.md §2), fast lava. `auth.view.WorldView` implements it live; the rigs over a map and, in the oracle fuzz, over real vanilla entities. |
| `SimTickInput` | One tick's keys (`Input`, as the client sent them) and raw rotation. |
| `ExemptReason` | Why a tick was not simulated: `PASSENGER`, `SLEEPING`, `DEAD`, `SPECTATOR_CAMERA`, `RIPTIDE`, `EXTERNAL_MOVER` (`NONE` when simulated). |
| `SimCollision` | Vanilla collision: `BlockCollisions` (cursor order, face/edge rules, `joinIsNotEmpty` with the entity box), entity colliders (per query, from the world's candidates: the client's entity section lookup, box intersection, `canCollideWith` with the happy ghast's from-above rule, `Shapes.create`), the world border, `Shapes.collide` with the 1e-7 early exit, `collideWithShapes` axis order, `Entity.collide`'s step-up (26.3 `maxUpStep` entity-box expansion, candidate heights), `noCollision`, suffocation, `findSupportingBlock`, `containsAnyLiquid`, and auto-jump's `getCollisions` scan. |
| `SimShape`, `ShapeCache` | Voxel shapes as data (coordinate lists, full cells, first/last full), read once per shape by identity; `VoxelShape.collideX`, `findIndex` and `toAabbs` ported, with moved (`OffsetDoubleList`, binary search) and unmoved (`CubeVoxelShape` floor) semantics; `Shapes.create` for boxes; the border ring. |
| `SimCollisionContext` | The client's `CollisionContext.of(player)`: `EntityCollisionContext` semantics as a snapshot (entity bottom, descending, held item). |
| `SimFluids` | `EntityFluidInteraction` (26.3): trackers per fluid holder, currents per tag, eye-in-fluid with `getHeightForCamera`, `hasFluidAndLoaded`, `applyCurrentTo` (players average), plus `FlowingFluid.getHeight/getFlow`. |
| `SimBlocks` | `getOnPos`, jump and speed factors, `onClimbable` (trapdoor over ladder), `applyEffectsFromBlocks`: slime `stepOn`, the `checkInsideBlocks` walk (`forEachBlockIntersectedBetween`, `collidedAlongVector`) and the movement `entityInside` effects (cobweb with weaving, sweet berry bush, powder snow, honey slide, bubble columns). |
| `SimClip` | `AABB.clip` and the long-fall `FALLDAMAGE_RESETTING`/water clip of `Entity.move`. |
| `SimBox`, `SimBoxList`, `SimLevel` | A mutable `AABB` with vanilla arithmetic; a flat list of entity colliders (box, position, rule); a `BlockGetter` over `SimWorld` for vanilla's per-state data methods. |

## 2. API

```java
MovementSim sim = new MovementSim();          // one per thread
SimPlayer p = new SimPlayer();                 // seed: p.setPos(...), p.vx.., p.yRot.., p.onGround, items, attributes...
SimTickInput in = new SimTickInput();

// between ticks, in client-stream order (each at the tick its ack says the client applied it, report 02)
sim.setVelocity(p, x, y, z);                   // SetEntityMotion (the LpVec3-decoded value)
sim.addVelocity(p, x, y, z);                   // explosion knockback (push)
sim.teleport(p, change, relatives);            // PlayerPosition: calculateAbsolute + setOldPosAndRot
sim.rotation(p, yRot, relY, xRot, relX);       // PlayerRotation
sim.snapTo(p, x, y, z);                        // block-prediction revert (absSnapTo)
sim.applyAttributeUpdate(p, attribute, base, modifiers);   // UpdateAttributes for self (SCALE refreshes at tick end)
sim.addEffect(p, effect, amplifier); sim.removeEffect(p, effect);
sim.setAbilities(p, flying, mayfly, instabuild, flyingSpeed);
sim.setLocalGameMode(p, gameType);             // GameEvent CHANGE_GAME_MODE (updatePlayerAbilities)
sim.setTabListGameMode(p, gameType);           // PlayerInfoUpdate for self (isSpectator, onGameModeChanged)
sim.applySharedFlags(p, flagsByte);            // self SetEntityData: sprint/swim/glide bits, no modifier change
sim.applyPose(p, pose);                        // self SetEntityData: pose, dimensions at once
p.noGravity = ...;                             // self SetEntityData: DATA_NO_GRAVITY
sim.echoUsingItem(p, using, hand, duration); sim.completeUsingItem(p);
// the tick's own keybind actions, before the tick
sim.clientAttack(p, targetIsPlayer);           // x0.6 + sprint off on a sprinting full-strength hit on a player
sim.startUsingItem(p, hand, useDuration); sim.releaseUsingItem(p); sim.stopUsingItem(p);
// TickEnd
ExemptReason r = sim.tick(p, in.set(sentKeys, yRot, xRot), world);
// after the tick, once per firework rocket attached to the player that the client holds, in the order they were added
sim.fireworkBoost(p);

// the client's velocity at the end of a tick, from the state before it and the tick's two positions (§3.4): 0 when
// out is exact, else VELOCITY_X/Y/Z for the axes that are not and VELOCITY_* reasons
int r = sim.velocityAfterMove(before, fromX, fromY, fromZ, toX, toY, toZ, world, out);
int r = sim.velocityAfterMove(before, sentKeys, fromX, fromY, fromZ, toX, toY, toZ, world, out); // knowing the keys
```

- `p.mainHand`, `offHand`, `feet`, `gliderAvailable`, `foodLevel`, `flying`… are the client's copies; the caller keeps
  them in step with what the client was sent (`auth.PortSimulation.sync` does it from `ClientView`).
- The keys are what the client **sent** for the tick (`ServerboundPlayerInputPacket`, sticky). The client sends them
  after its tick (`Minecraft.tick` → `sendChanges`), so they already carry an auto-jump's `makeJump`.
- `p.sprintWindow` is the client's hidden `sprintWindow` option (default 7): only the double-tap sprint path reads it.
- `p.autoJumpOption` is the client's hidden `autoJump` option as the server believes it, default on; see §3.1.
  `p.autoJumpEnabled` is `LocalPlayer.autoJumpEnabled`: true in a new `SimPlayer`, like a new `LocalPlayer` (join,
  respawn, dimension change), then the option from the end of the first tick. Carry `autoJumpOption` (and
  `sprintWindow`) over when re-creating a player on respawn.
- `p.externalMover` is set by the caller for a tick in which something outside the player's own tick may move it in the
  client's level (§6); the tick then returns `EXTERNAL_MOVER` and leaves the state untouched.
- `MovementSim.setSprinting(p, on)` is the client's own `LivingEntity.setSprinting` (flag + `sprinting` modifier).
- `MovementSim.collide(p, world, dx, dy, dz, out)` is `Entity.collide` alone, for tools and tests.
- `MovementSim.lastMove(out)` is the last tick's exact move (`Entity.collide`'s result) and
  `velocityAfterExactMove(before, from, move, world, out)` takes it instead of `to`: for tools and tests (§3.4).
- `setLocalGameModeSpectator` is a compatibility shim for one-call callers; prefer the two game-mode calls above
  (`PortSimulation` still uses the shim, and its `sync` writes `p.spectator` directly, which skips
  `onGameModeChanged`'s `vy = 0` when the tab-list mode turns spectator).

Vanilla's physics constants are not `revival.yml` keys on purpose: they are what the client computes with, so any other
value would be a desync, not a setting.

## 3. What is ported

Every method in the sources names the vanilla class, method and 26.3 line it mirrors (paths under
`~/Developer/tools/mc-src/src-26.3/net/minecraft/`), so a Minecraft update can be re-diffed.

| Area | Vanilla source |
|---|---|
| Tick order | `ClientLevel.tickNonPassenger` → `Entity.commonTick` → `Player.tick` → `LivingEntity.tick/baseTick` → `Entity.baseTick` → `LocalPlayer.aiStep` → `Player.aiStep` → `LivingEntity.aiStep` → `Player.updatePlayerPose`, then `LocalPlayer.sendPosition`'s auto-jump option copy (report 01 §1.3) |
| Input | `KeyboardInput.tick`, `Vec2.normalized`, `LocalPlayer.applyInput/modifyInput/modifyInputSpeedForSquareMovement` (use-effect slowdown, sneak speed, square fix) |
| Crouch, sneak | `LocalPlayer.aiStep` crouching (previous shift key), `isMovingSlowly` (incl. `LivingEntity.isVisuallySwimming`'s FALL_FLYING pose), `Player.maybeBackOffFromEdge/isAboveGround/canFallAtLeast` |
| Sprint | trigger window, `canStartSprinting`, `isSprintingPossible` (blindness, food, shallow water), run/swim stop rules, `LivingEntity.setSprinting` modifier |
| Flight | mayfly double-tap toggle (not on an auto-jump), spectator forced flight, vertical input, fly speed, landing ends flight, `Player.travel` y × 0.6 |
| Elytra | `tryToStartFallFlying`/`canGlide` (26.3: not in any liquid, not levitating, a glider item), `travelFallFlying`/`updateFallFlyingMovement` (the client never stops gliding itself), `FireworkRocketEntity.tick`'s boost of an attached rocket (`fireworkBoost`) |
| Auto-jump | `LocalPlayer.move` → `updateAutoJump` (`canAutoJump`, the look-ahead, the `getCollisions` scan in collider order with `toAabbs`, head room, jump height with Jump Boost), `makeJump` (§3.1) |
| Suffocation push | `LocalPlayer.moveTowardsClosestSpace/suffocatesAt` |
| Jumping | `noJumpDelay`, `jumpFromGround` (jump strength, block jump factor, jump boost, sprint boost), `jumpInLiquid`, fluid jump threshold |
| Velocity floor | players' horizontal `9.0E-6` and vertical `0.003` floor |
| Travel | `LivingEntity.travel`, `travelInAir` (FRICTION_MODIFIER, AIR_DRAG_MODIFIER, levitation, slow falling, no gravity; no unloaded-chunk `-0.1` on a client, §6), `travelInFluid`/`travelInWater`/`travelInLava` (water efficiency = depth strider, dolphins grace, fluid-falling adjustment, `jumpOutOfFluid`), `handleOnClimbable` (scaffolding exception), `getFrictionInfluencedSpeed` (ice variants by block friction), `Player.getFlyingSpeed`, swimming look pull |
| `Entity.move` | stuck multiplier, edge back-off, collide, long-fall clip, `movementThisTick`, collision flags, `LocalPlayer.isHorizontalCollisionMinor`, `checkSupportingBlock`, `checkFallDamage` with the mid-move fluid update, `restituteMovementAfterCollisions` (26.3 bounce rules), block speed factor (`MOVEMENT_EFFICIENCY` lerp: soul sand, soul speed's attribute) |
| Collision | §1 `SimCollision`, `SimShape`; world border; scaffolding standing/descending and powder snow (falling, leather boots) through the context; solid entities: `EntityGetter.getEntityCollisions` (`Level.getEntities` over `EntitySectionStorage`'s section range, `canCollideWith`: `AbstractBoat`, `Shulker`, `HappyGhast.canBeCollidedWith`), in `Entity.collide`, `noCollision` and auto-jump's `getCollisions` (§3.5) |
| Fluids | §1 `SimFluids`; `Entity.updateFluidInteraction`, `Player.updateSwimming`, `LocalPlayer.isUnderWater` (`wasUnderwater`), lava halving fall distance |
| Blocks | slime `stepOn`; cobweb (weaving), sweet berry bush, powder snow (stuck in its own block, freeze flag, `canEntityWalkOnPowderSnow`), honey slide, bubble columns (precise, above/inside); climbable tag and trapdoor ladders. Magma's `stepOn` only hurts (server side), soul speed is an attribute the server syncs |
| Pose | `updatePlayerPose` (crawling, swimming), `Avatar.POSES` dimensions scaled by `SCALE`, refresh on pose change and at tick end for `SCALE` |
| Items, attack | `updatingUsingItem`, start/release/stop, `Player.attack` client slowdown + `resetAttackStrengthTicker`, attack strength ticker and the main-hand item-type reset |
| Server changes | `ClientPacketListener.handleMovePlayer/handleRotatePlayer/handleUpdateAttributes/handlePlayerAbilities/handleUpdateMobEffect`, `lerpMotion`, `push`, `absSnapTo`, `GameType.updatePlayerAbilities`, `onGameModeChanged`, entity-data echoes |

### 3.1 Auto-jump

The client's `autoJump` option is not in any packet. What matters is that the jump it causes is in the sent `Input`
(`makeJump` runs before `sendChanges`), so the physics never depend on the option; only `wasAutoJump` does, which keeps
an auto-jump from opening the creative double-tap flight window. The port therefore predicts every auto-jump exactly
like the client (`updateAutoJump` after each move, `autoJumpTime = 1`), believing the option on (`p.autoJumpOption`,
default true), and on the next tick:

- the sent keys carry the jump: it is the auto-jump (`wasAutoJump`), exactly like the client;
- they do not: the client did not jump, so its option is off. The port turns `autoJumpOption` off for good and forces
  nothing.

So the physics are exact for every client, and `wasAutoJump` is exact once the belief is right. The two remaining
ambiguities only show while the player can fly (`mayfly`): a client with the option off that presses jump on exactly
the tick after a predicted auto-jump keeps the belief on and skips one double-tap window; and a belief turned off by a
desync elsewhere. A new `LocalPlayer` has `autoJumpEnabled = true` until its first `sendPosition`, so even a client with
the option off auto-jumps on its first tick; a new `SimPlayer` does the same.

### 3.2 Firework rockets

A rocket attached to the player is its own entity in the client's level; it ticks after the player in the same client
tick (`ClientLevel`'s `Int2ObjectLinkedOpenHashMap` keeps insertion order and the rocket is added after the player) and,
while the player glides, sets its velocity to `v + look × 0.1 + (look × 1.5 - v) × 0.5` (`fireworkBoost`). The client
keeps a rocket from its `AddEntity` (the attachment comes in the same bundle's entity data) until its
`RemoveEntities`; it never explodes one itself. Several rockets boost one after the other in the order they were added.

### 3.3 Effects

The client holds only what `ClientboundUpdateMobEffectPacket` gives it, built with no hidden effect, and never adds the
effects' attribute modifiers itself (`onEffectAdded/Updated/Removed` are server-only; the modifiers come as attribute
updates). So a hidden-effect downgrade (a longer weaker effect under a shorter stronger one) happens on the server and
reaches the client as an update with the lower amplifier; the client keeps an expired effect until the
`RemoveMobEffect`. The port does the same: `addEffect` replaces the amplifier, `removeEffect` clears it.

### 3.4 Velocity after a move

`velocityAfterMove(p, from, to, world, out)` (auth-sim-requests.md §1) is the `deltaMovement` the client holds at the
end of a tick whose move went from `from` to `to`: the seed's velocity after one claimed move, and the belief velocity of
authoritative.md §6.2. `p` is the client's state at the start of that tick (as `tick` would get it); its position,
velocity and rotation are never read and it is not modified. The result is the velocity at the end of the player's own
tick, before attached rockets boost it (`fireworkBoost` after it, as after `tick`).

It restates nothing: the tick is split at its move (`startTick`; each travel branch records what it worked out before
its move and has its after-move half as its own method, `afterAirMove`, `afterWaterMove`, `afterLavaMove`,
`afterFlyingTravel`; `Entity.move` after collide is `afterCollide`), and `tick` runs exactly these, so the oracle proves
them with the tick. `velocityAfterMove` runs the tick's start from `from`, its aiStep up to the move (`probeMove`, which
stops at `move`), then the after-move half: collision flags with `Mth.equal`, support, fall distance, restitution,
the block speed factor, the branch's climb, gravity or levitation, drag and friction or the fluid slow-downs and
`jumpOutOfFluid`, the flight rule and `applyEffectsFromBlocks`. What the after-move half needs that the state before the
tick does not hold, it tries every way, and an axis counts as determined only when all of them give the same bits:

- **The tick's keys** (forward, jump, shift, sprint): sprint, jumping, the shift that bounces and scaffolding read, a
  double-tap flight, a glide started this tick, and the branch's own values (water's slow-down) depend on them. All 16
  combinations run the port's aiStep up to the move; backward, left and right change none of these.
  `velocityAfterMove(p, keys, ...)` takes the keys the client sent for the tick instead.
- **What collide was given** (the velocity before the move): per axis the move itself, and where a collider sits at the
  face the box ended on (the world border at any distance, since collide gathers it only for a long enough move), cuts
  under `Mth.equal`'s 1e-5 (one only ulps of the position deep: a longer one can swap collide's x/z order), just past
  and far past it, and 39 depths from 1e-5 to 3.6 where a bounce or the flight rule carries the depth into the velocity
  (slime or a bed under the box, the bounciness attribute, creative flight); for y also step-ups, from a fall or from
  rises of several heights. Each combination goes through the port's own `collide` and is kept only if it lands exactly
  on the observed position.
- **What a still or rounded axis hides**: the other signed zero, a velocity under collide's 1e-7 cut, or one under
  `Entity.move`'s 3.2e-4 (a move that short is not applied when another axis lost more); and `to - from` is only one of
  the moves that `from + move` rounds to `to`, so both ends of that range run too.

The result is 0 when `out` is the client's velocity bit for bit. Otherwise `VELOCITY_X`/`Y`/`Z` mark the axes that are
not, `out` holds the most likely value (the keys as before the tick; a move that ended against something was cut, a
still axis stood still, a player on the ground pressed down on it), and the reasons say why:

| Reason | What the move cannot show | Error bound |
|---|---|---|
| `VELOCITY_ROUNDED` | `from + move` rounds to the ulp of `to`, so the move's bits below it are lost (nearly every move on a moving axis) | half an ulp of `to` per axis, times the drag |
| `VELOCITY_UNMOVED` | an axis that did not move: `-0.0`, a velocity under 1e-7 (a near-cardinal yaw's sine), or under 3.2e-4 not applied | under 3.2e-4 |
| `VELOCITY_COLLIDED` | a collision may have cut the move: hit or merely reached a wall (`-0.0` or the move), landed or arrived exactly, a bounce off slime or a bed (impact speed), a cut under 1e-5, a step | none |
| `VELOCITY_EDGE` | sneaking at an edge may have shortened the move (`maybeBackOffFromEdge`), not the velocity | none |
| `VELOCITY_KEYS` | the tick's keys decide (water and lava sprint, a glide or flight started, jumping on a climbable, sneaking on a bounce); never with the keys | none |
| `VELOCITY_PRE_MOVE` | creative flight while gliding or stuck keeps a y from before the move (tried over ±3.3, so every axis it reaches is flagged); or the glide fall clamp read it | none |
| `VELOCITY_NO_TICK` | no tick from `p` makes this move here (an exempt state, a move into a block); `out` is NaN | |

`from` and `to` must be the two ends of one client tick. A tick the client sent no position for (it moved under 2.0E-4
from the last position it sent, without the 20-tick reminder; `LocalPlayer.sendPosition`) has no `to`, and the tick after
it no exact `from`: neither has an answer, the caller skips them. In practice (§4) the exact move determines most ticks,
while from positions `ROUNDED` covers nearly every moving axis: the answer is then within an ulp of the position, not bit
for bit, and a seed carries that difference until a collision or the 0.003 floor resets the axis. It is exact where
`from + move` needs no rounding, as for a fall from rest at y = 0. The caller that knows the tick's keys should pass
them: `KEYS` covered 16% of the oracle's ticks and 27% of the real-client traces.

shortcut: the depths of a cut, the rises, the pre-glide y and the hidden values are samples, not every value. The rules
after a move read what collide was given only through restitution (a bounce: linear, after a gravity threshold), the
flight rule (linear), the fluid falling test (its sign) and the inside-block walk's axis order (which the far cut flips);
without a bounce or flight only the cut's sign counts, and with one the depths are dense, since the bounced velocity
meets more thresholds (slime scales x and z under |vy| 0.1). The oracle fuzz and the traces check it on every tick (§4).
The full runs found eight gaps the readings now cover: a step that ends below the start, a rising step to height 0, the
border's size-dependent reach, a step height of 0 (which hid a rounded axis), a near cut that swapped collide's x/z
order, a cut shallower than the position's rounding (not gathered), a slime bounce whose depth decides x and z, and a
pre-glide y that honey reads into x and z.

## 4. Exactness

Four independent checks, all green on 2026-10-21 with the final code (re-run 2026-10-22 after the tick was split at its
move for `velocityAfterMove` and the unloaded-chunk fall removed), and a fifth for `velocityAfterMove`.

1. **Collision, differential fuzzer** (`~/Developer/tools/auth-rig/diff/run.sh`, report 07 §1.6, the port is mode 2,
   compiled once per class loader against that loader's jar; the RevivalPaper jar refreshed from the 21:12 build):
   vanilla `Entity.collide` vs the port in the **patched RevivalPaper loader** and in the vanilla loader: **0
   differences** on 1,000,000 adversarial single moves and **0 of 20,000** 200-tick walks, in both loaders (seed 11).
   Paper/Moonrise differs in 233,558 of the same moves. The shape data the port reads hashes the same in both loaders
   (`fadae0cab51c08fa`), and its world-border ring equals vanilla's.
2. **Whole tick, vanilla oracle** (`~/Developer/tools/auth-rig/harness/sim.sh SimFuzz runs ticks seed`, or
   `fuzz-seeds.sh` for several seeds at once): vanilla's own `Player`/`LivingEntity`/`Entity` code from the 26.3 server
   jar running the local player on a client-side `Level` (`isClientSide() == true`, data pack and tags loaded), with
   `LocalPlayer`'s overrides copied verbatim (`rig.sim.OraclePlayer`, auto-jump included), against the port, in random
   worlds (the 30,000 shaped states, ladders, vines, scaffolding, water and lava at every level, bubble columns, honey
   walls, powder snow, cobwebs, berry bushes, slime, ice, soul sand, beds, solid entity boxes, a close world border,
   unloaded chunks, fields of one-block steps, 40-block drops onto water, hay, slime, cobwebs, ladders, vines, berry
   bushes, powder snow and scaffolding) with random keys, rotations (a sixtieth of them exact cardinals) and
   between-tick changes (knockback, explosions, effects, attribute packets with random modifiers incl. the sprint id,
   abilities, game modes, item use, attacks, teleports, rotations, prediction snaps, food, flag, pose and no-gravity
   echoes, elytra, attached firework rockets as real `FireworkRocketEntity`s ticked after the player). The port gets
   the keys the client sent (after `makeJump`); half the clients have auto-jump on, and half of the others are
   believed on, so the port has to learn the option. Every field movement touches is compared bit for bit every tick,
   plus `autoJumpTime` and the option belief. **0 differences in 4,800,000 ticks** (seeds 20-23, 3,000 runs of 400
   ticks each; again 2026-10-22 with the oracle's level as the client's: `hasChunk` always true, a missing chunk the
   client cache's empty chunk, which an old-rule mutant fails on its first tick over one). Coverage per seed (1.2 million ticks, seed 20): ground 238k, water 39k, lava 12k, swimming 2.0k,
   climbing 2.9k, stuck 17k, powder snow 1.9k, gliding 163k, rocket boosts 73k, flying 124k, item use 486k, crouching
   58k, sprinting 298k, bounces 5.6k, edge back-off 12k, currents 43k, bubble columns 0.7k, honey 16k, cobwebs 12k,
   moves of a block or more per tick 191k, no gravity 68k, auto-jumps 1.8k, option learned off 207, solid entity boxes
   in 72k, the border within 3 blocks in 56k. Mutations of the new code are caught: the rocket's 1.5 → 1.4 in 37 of
   300 runs, the auto-jump look-ahead 7 → 6 in 21, the facing test -0.15 → 0.15 in 24, the shift test dropped in 3.
3. **Real client** (80 golden traces from an unmodified vanilla 26.3 client in the movement gym,
   `~/Developer/tools/auth-rig/traces`, see `README-traces.md` there): **80/80 traces, 5,085 client ticks, exact**
   (position and velocity bits, flags, pose, fall distance) with `harness/sim-replay.sh traces/`.
   `harness/build-port.sh` now runs the same replay (it used to skip the data pack, so `CLIMBABLE` and `WATER` were
   unbound and 7 traces failed; `README-traces.md` still quotes that 73/80).
4. **RevivalPaper tests** (`./gradlew :leaf-server:runRevivalTests`, on the patched server jar, so the deploy jar's
   block data, tags and shapes are what the port reads): `MovementSimTest` (30 mechanics against hand-worked vanilla
   formulas, auto-jump, rockets, the item-type ticker reset, effect updates and no gravity included),
   `SimCollisionTest` (6), `SimAttributesTest` (6, the allocation-free copy included), `MovementSimGoldenTest` (18
   scripted scenes, `GoldenScenes`, among them an auto-jump over steps, a rocket-boosted glide and no gravity, whose
   per-tick positions, flags and final velocity, `GoldenData`, were recorded on the vanilla oracle; `harness/sim.sh
   GoldenGen <path>` regenerates them) and `MovementSimFuzzReplayTest`: 300 random `FuzzScript` runs of 200 ticks
   (60,000 ticks, with no-gravity echoes, elytra and rockets, auto-jump on or off) whose per-run state hashes
   (`FuzzData`) vanilla's own Player code produced on the oracle (`harness/sim.sh FuzzRecord 300 77 <path>`). Plus a
   missing chunk in `MovementSimTest` (now 31) and `VelocityAfterMoveTest` (9, item 5). All 71 pass, in a full suite of
   299.
5. **`velocityAfterMove`** (§3.4), on every tick of the checks above (`rig.sim.VelocityCheck`): from the state before
   the tick, its position, velocity and rotation poisoned with NaN (so they cannot be read), and the tick's two
   positions; again with the tick's keys; and from the exact move (`lastMove`). Every axis reported determined must be
   the tick's velocity bit for bit. **0 wrong on 4,800,000 oracle ticks** (seeds 20-23), on the 80 real-client traces
   (5,085 ticks) and on the 60,000 fuzz-script ticks, in all three; the RevivalPaper test does the last on the deploy
   jar and checks the state comes back untouched and the runs still hash like vanilla's. Determined on every axis:

   | | oracle fuzz (4.8M ticks) | real-client traces (5,085) |
   |---|---|---|
   | from positions | 1.4% (y alone 24%) | 3.2% (y alone 59%) |
   | from positions and the keys | 1.5% (y alone 24%) | 3.2% (y alone 63%) |
   | from the exact move | 80.9% (each axis about 90%) | 32.3% |

   Reasons, share of oracle ticks (from positions / with the keys / exact move): `ROUNDED` 98.4 / 98.4 / 0, `UNMOVED`
   12.6 / 12.5 / 8.5, `COLLIDED` 13.3 / 13.2 / 13.3, `EDGE` 4.5 / 0.3 / 4.5, `KEYS` 15.9 / 0 / 15.8, `PRE_MOVE` 3.2 /
   2.8 / 3.2. Ticks with only `ROUNDED`/`UNMOVED`: 80% from positions (83% with the keys), largest error 3.0e-4 (the
   `UNMOVED` bound; a `ROUNDED` axis alone is off by about an ulp of its position, 5e-14 in the short runs); in the
   traces largest 3.2e-8. The traces are mostly cardinal moves, whose still axis keeps a near-cardinal sine's
   1e-17-to-1e-8 velocity (`UNMOVED`). The runs before the final code found the eight gaps listed in §3.4, each now
   covered.

The test harnesses feed the port the keys the client would send: `FuzzScript.sent` adds the jump of an auto-jump the
port predicted, which equals the client's when the port's option is the client's (the rigs check both).

## 5. Cost

`harness/sim.sh SimBench`, JIT-warm, one MacBook core shared with other work, 1,000,000 ticks per row, a PvP loadout
(an enchanted, damaged sword whose stack is compared every tick, boots):

| Scene | auto-jump | µs per tick | bytes allocated per tick |
|---|---|---|---|
| open field, sprint-jumping and strafing | off | 7.1 | 0 |
| | on | 7.6 | 0 |
| cluttered arena (500 stairs, slabs, fences, walls, random shapes) | off | 6.6 | 9.7 |
| | on | 6.8 | 9.9 |
| field of one-block steps | off | 6.3 | 0 |
| | on | 6.3 | 0 |
| water pool | off | 5.9 | 0 |
| | on | 5.9 | 0 |
| `SimPlayer.copyFrom` (the reconciler's fork, every tick) | | 0.18 | 0 |

"0" is under 0.1 byte per tick, the measurement's own noise. The design target is 50 µs; auto-jump adds about 0.4 µs
in the open, where every ground tick scans ahead. The tick allocates nothing in steady state (stage 1 measured 30-64
bytes per tick, mostly its benchmark's own player, and its `copyFrom` cloned the attribute maps). What it can still
allocate, all rare: the copy of a new main-hand item type, a modifier table that has to grow, a cache miss on a block
shape the port has not seen, and the shapes of blocks with a random offset (bamboo, pointed dripstone), which
vanilla's own `getCollisionShape` builds per call (the cluttered row's bytes). Stage 2 removed the rest: the per-tick
`ItemStack.matches` (only the item type of the copy is ever read, so the port compares types), attribute copies and
recomputes (the subclassed maps copy tables and walk values in place), entity-collider shapes (a small cache by box,
so a parked boat costs nothing) and `noCollision`'s entity check (a count). Vanilla's `LiquidBlock.getCollisionShape`
allocates a lambda per call, so the port answers liquids itself (empty for players).

`velocityAfterMove`, on the same machine under the fuzz's load: about 30-120 µs per call with the tick's keys, 200-600
µs without them (16 key combinations; ground ticks and bounces try the most readings), allocation-free after its first
call (`VelocityAfterMoveTest`). It is meant for seeds and corrections, not every tick.

## 6. Not simulated, and what the caller owns

- **Chunks the client does not have** (after a long teleport, at the view edge): the player keeps ticking (players
  always tick), keeps normal gravity, and reads such a chunk as the client's `EmptyLevelChunk`: void air, no fluid, no
  colliders. `travelInAir`'s `-0.1` fall needs `!level.hasChunkAt(posBelow)`, and `ClientLevel.hasChunk` is always true
  (ClientLevel.java:519-522); only `BlockCollisions` and `EntityFluidInteraction` ask the cache itself
  (`getChunk(x, z, FULL, false)`, null for a missing chunk). So `SimWorld.isChunkLoaded` must be the client's chunk
  cache (what it was sent and not yet told to forget), with `blockState`/`fluidState` air and empty there. Found by the
  real-client enforce run: after a 218-block teleport the client fell at -0.0784 over void air for the two ticks until
  the chunk arrived, where the port had taken the `-0.1` branch (fixed 2026-10-21; the rig's `OracleLevel` had the same
  mistake, now `hasChunk` true and an empty chunk like `ClientChunkCache`).
- **Exempt states** (`ExemptReason`; the caller keeps vanilla handling of the client's claim and re-seeds when the
  state ends): riding (and vehicles), sleeping, dead, spectating through another entity, riptide
  (`autoSpinAttackTicks`), and `EXTERNAL_MOVER` for ticks the caller flags.
- **Movers outside the player's tick** (report 01 §6.6-6.7), which need the client's block entities or entity tick
  order, so the caller flags them with `p.externalMover`: moving pistons (a `MOVING_PISTON` shape needs its block
  entity; `SimLevel` has none, so it reads empty) and shulker boxes, shulkers' lids, potent sulfur geysers (a block
  entity that adds velocity to nearby entities), pushes from mobs and remote players (Paper's
  `enable-player-collisions: false` removes the player case). Rockets are modelled (§3.2).
- **Client-side block changes** are the world view's job: redstone ore lighting under the player (moves nothing), and
  a rocket's hit ray tilting a big dripleaf (it does change the collision shape; the oracle rig showed it, the port
  reads whatever `SimWorld` says).
- **Hidden client options**: `sprintWindow` must be learned from when `START_SPRINTING` arrives (the field exists);
  auto-jump is learned by the port (§3.1).
- **Netcode inputs the port trusts**: the sent keys per tick, velocities after `LpVec3` decoding, the tick each change
  was applied (transactions), the client's item copies (`LegacySwordBlocking.toClient`), hotbar changes (one-tick
  ambiguity, report 01 §1.2), the block-prediction revert (`snapTo`), chunk loads, the border lerp, solid entity boxes
  at their client-interpolated positions, which entity a remote-player attack hit, and the attached rockets' lifetimes.
- **Water's clip shape** in the long-fall clip: vanilla caches `FlowingFluid.getShape` per fluid state at the height of
  the first position ever asked, which no server can know; the port uses the height at the position (only a fall of
  more than a block per tick ending inside the top ninth of a water surface can tell).
- **Attribute packets with a duplicate modifier id** throw, as the client does (it would drop the connection).
- **Fast lava** (`FAST_LAVA` dimensions) is a `SimWorld` flag, not covered by the oracle (no environment layers there).
- **Real-client traces for the rest**: the 80 golden traces cover walking, sprinting, jumping, stairs, slabs, fences,
  ledges, ceilings, ice, slime, honey, soul sand, cobwebs, berry bushes, powder snow, lava, ladders, vines, scaffolding,
  water, currents, bubble columns, floor bits, doors and crawling. Knockback, item use, effects, attribute changes,
  elytra, rockets, flight, teleports and auto-jump are proven against vanilla's own code only (the oracle).
- **`Math.sin`**: the `Mth` table is built with `Math.sin` on each JVM; values are rounded to float, so a 1-ulp
  intrinsic difference would have to cross a float boundary to show.

## 7. Re-checking after a Minecraft or Leaf update

First re-diff every `Class.method (File.java:lines)` reference in `auth/sim` against the new decompile, then:

```bash
R=~/Developer/tools/auth-rig
T=~/Developer/GitHub/RevivalPaper/leaf-server/src/test/java/gg/hcfrevival/revival/auth/sim
$R/fetch-server.sh                                   # the new vanilla server jar
$R/diff/refresh-leaf.sh && $R/diff/run.sh 1000000 11 # collision, both loaders (after a RevivalPaper build)
$R/harness/fuzz-seeds.sh 3000 400 20 21 22 23        # the whole tick against vanilla's own code (harness/results)
$R/harness/sim.sh GoldenGen $T/GoldenData.java       # re-record the golden scenes on vanilla
$R/harness/sim.sh FuzzRecord 300 77 $T/FuzzData.java # re-record the deploy-jar fuzz hashes on vanilla
$R/harness/sim-replay.sh traces/                     # golden client traces (re-capture them for a new client)
$R/harness/sim.sh SimBench                           # cost per tick
./gradlew :leaf-server:runRevivalTests               # the port on the RevivalPaper jar
```

`sim.sh` compiles the port straight from the checkout (`RP_SIM`, `RP_TESTS` override the paths) together with the rig
(`harness/src-sim`: `RigWorld`, `RigBootstrap`, `OracleLevel`, `OraclePlayer`, `SimFuzz`, `GoldenGen`, `FuzzRecord`,
`SimBench`, `VelocityCheck`) against the vanilla 26.3 server jar; `fuzz-seeds.sh` compiles once into its own directory
and runs two seeds at a time. Point `RP_SIM` at a mutated copy of the sources to check the rigs still catch a change.
`SimFuzz`, `FuzzRecord` and the trace replay also check `velocityAfterMove` on every tick (`rig.sim.VelocityCheck`, its
report at the end; `RIG_JAVA_OPTS=-Dvelocity=false` skips it in the first two, `-Dvelocity.dump=<reason bits>` prints the
ticks whose hard reasons are exactly those).
