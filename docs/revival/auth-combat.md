# Lag compensation and replication (`auth.combat`, `auth.replicate`)

Status: implemented, 2026-10-21, branch `revival/authoritative`. Design: `authoritative.md` §7-8. Research: report 02 §2.2
(the attack ray), report 03 (26.3 stepped interpolation, K2 feed, exact mirror). This file covers the code, what proves
it exact, and what the hooks must do.

Pure logic, no hooks. Everything here compiles unchanged against both the vanilla 26.3 client jar and the RevivalPaper
server jar (`~/Developer/tools/auth-rig/combat/check.sh`), so the same classes are tested against the real client and run
on the server.

## 1. What is where

| Class | Role |
|---|---|
| `auth.combat.RemoteMirror` | One viewer's client copy of one remote player: a line-by-line port of 26.3's `AbstractInterpolationHandler`, `SteppedInterpolationHandler` (+ `InterpolationData`), the `Entity` position/rotation/dimension setters and `ClientPacketListener`'s handlers for that entity. Allocation-free per tick. |
| `auth.combat.MirrorEvent` | One packet's effect on one target: `Spawn`, `Remove`, `Move`, `Sync`, `Teleport`, `SetPose`, `SetScale`, `Riding`, `Spectator`, `Inexact`, plus the book-level `Clear` and `Passengers`. `of(...)` factories read exactly what the client reads from the real packet classes. |
| `auth.combat.MirrorBook` | Per viewer: a mirror per target, events queued by arrival tick, `onViewerTickEnd(T)` (apply, sample the pick-time box, interpolate), a bounded history ring of boxes. |
| `auth.combat.PickWorld` | The attacker's client blocks for the ray: `blockState(x, y, z)` (the same signature as `auth.sim.SimWorld`, so `WorldView` implements both) and an overridable `outlineShape`. |
| `auth.combat.AttackerSnapshot` | The attacker at the start of client tick T: `xo`/`x` (eye = `Mth.lerp(1.0, xo, x)`), eye height, raw rotation floats, own box, interaction ranges, spear and riding flags. |
| `auth.combat.PickRay` | The client's `Minecraft.pick(1.0F)` → `LocalPlayer.pick` for swords, bit for bit, plus the order-free `canPick`. |
| `auth.combat.HitValidator`, `HitCheck` | VALID / INVALID(reason) / UNKNOWN(reason) for one attack. |
| `auth.replicate.PathFeed` | K = 2 replication (report 03 §6, R1-R6): per target a ring of authoritative steps; per viewer a `Viewer` that builds the packets and tracks that client's codec base and interpolation target. |

## 2. APIs

### 2.1 Mirrors

```java
MirrorBook book = new MirrorBook(MirrorBook.playerInterpolationSteps() /* 2 */, historyTicks);
book.onPacket(packet, arrivalTick, stampMillis);            // a packet the viewer applied before its tick arrivalTick
book.onPacket(packet, arrivalTick, stampMillis, exact);      // exact = false: unbundled, arrival is an upper bound
book.onEvent(targetId, MirrorEvent event, arrivalTick);      // the same, already converted
book.onViewerTickEnd(T);                                     // apply arrivals == T, sample(T), interpolate(T); gaps run tick by tick
book.sample(targetId, T, MirrorBook.Sample out);             // box, pickable, exact, stamp; false if never seen or out of history
book.boxAtPick(targetId, T) / exactAtPick(targetId, T);      // convenience (allocates)
book.setRelativeTickSpeed(float);                            // ClientLevel.getRelativeTickSpeed from TickingState (1 unless > 20 TPS)
book.reset();                                                // viewer gone
```

- `onPacket` understands `AddEntity` (players only), `RemoveEntities`, `MoveEntity` Pos/PosRot/Rot (id read through a
  VarHandle, vanilla has no getter), `EntityPositionSync`, `TeleportEntity`, `SetEntityData` (`DATA_POSE`),
  `UpdateAttributes` (`minecraft:scale`, computed as the client does: fresh instance, base + transient modifiers),
  `SetPassengers`, `PlayerInfoUpdate` (`UPDATE_GAME_MODE`, matched by the UUID from `AddEntity`), `Respawn` and `Login`
  (clear), and bundles (recursively). Everything else is ignored.
- Events must arrive in wire order, and every event for tick T before `onViewerTickEnd(T)`; true when pongs, attacks
  and tick ends are handled in client-stream order (report 02 §5-6). Calling `onViewerTickEnd(T)` early (at the first
  attack of `W_T`) and again at `TickEnd(T)` is fine: the second call does nothing. An event whose tick already ran is
  applied at once, counted (`lateEvents()`) and marks its target inexact.
- `stamp` is the server time (ms) of the position the event carries; `HitValidator` measures rewind from the stamp of
  the step the displayed box comes from (`RemoteMirror.displayStamp`). The send time is a fine proxy (it is what
  `CombatWiring` passes); a `PathFeed` step's own stamp is up to one tick older.
- Exactness (`Sample.exact`) turns off for: a snap (> 64 blocks) while steps are queued (the client then shifts its
  queue only if *its* world has room, `adjustInterpolationPosition`; the mirror assumes free space), riding, the
  `SLEEPING` pose (`setPosToBed`), a late or unbundled packet, an empty `Stepped` sync. It comes back at the next
  `Spawn`. Not detectable from packets, assumed absent: pistons or shulkers pushing the target on the viewer's client,
  and a target in a chunk the viewer doesn't tick.

### 2.2 The ray and the verdict

```java
PickRay ray = new PickRay();                 // reusable, one per thread
ray.clear(); int i = ray.add(id, box, pickRadius /* 0 for players */, pickableFromInside /* true */, exact);
ray.cast(attackerSnapshot, pickWorld);
ray.resolveInOrder();                        // vanilla's exact result for this candidate order: kind(), hoveredIndex(), hitX()...
ray.canPick(i, withInexact);                 // is there an order in which the client returns candidate i as an ENTITY hit

HitCheck check = new HitValidator().validate(attacker, targetId, book, T, List<Candidate> nonPlayers, world, nowMillis, rewindCapMillis);
// check.verdict(): VALID | INVALID | UNKNOWN; check.reason(); check.distance(); check.rewindMillis(); check.capped()
```

- Order of checks: spear (`ATTACK_RANGE`) or riding attacker → UNKNOWN `UNSUPPORTED`; target never seen → INVALID
  `NOT_PRESENT`; no sample at T → UNKNOWN `NO_SAMPLE`; not pickable at T (removed, spectator) → INVALID `NOT_PRESENT`;
  `now - stamp > cap` (the cap is inclusive, `CombatWiring.sharedRewindCap`) → the capped age, below; target sample
  inexact → UNKNOWN `INEXACT_TARGET`; the outcome differs with and without inexact candidates → UNKNOWN
  `INEXACT_BLOCKER`; else VALID `PICKED` or INVALID `MISSED` / `OUT_OF_REACH` / `BLOCKED` / `OCCLUDED`.
- Past the cap, Overwatch's rule (2026-10-22): the ray is tested against the target's truth at the capped age instead
  of the box the client drew. `TruthHistory` (live: `combat.TruthPaths`, the target's `PathFeed` ring, one step per
  simulated client tick stamped when it was simulated) gives the oldest step stamped at or after `now - cap`, never an
  older one; the box keeps the drawn sample's size and pose. That truth is the server's own, so it counts as exact, and
  `capped()` is true. The client never drew it there: a high-ping attacker has to lead (in melee, aim at the leading part
  of the box it shows), and a backtrack or a held view gains nothing past the cap. A path that stopped (no `TickEnd`,
  refused or frozen windows) answers with its newest step while the target's `ServerPlayer` is still there and no server
  teleport is pending: a lag switch or a freeze no longer makes a player unhittable (review X2-01, pvp-issues NW-2: "stays
  hittable where it froze"). Only a target with no path (outside authority), or one that moved without a step (a teleport
  pending or forced), is INVALID `REWIND`. Other players stay where the
  client drew them. `rewindMillis()` stays the age of the view. Neue's held-click landing (`NeueReach.authority`)
  measures its reach the same way past the cap.
- Why `canPick` and not one answer: `ProjectileUtil.getEntityHitResult` depends on the client's entity iteration order
  (section storage order, unknowable) when the eye is inside a candidate's box or two candidates tie, because a clipped
  entity coming *after* an inside one replaces it (`dd < nearest || nearest == 0.0`). `canPick` unrolls the loop:
  inside → reachable (put it last); clipped → reachable after any "resetter" (an inside candidate, or a clip at
  distance exactly 0), else iff strictly nearer than every other clipped candidate and than `maxDistanceSq`. Then the
  hit must be nearer than the block hit and within entity range (`filterHitResult`). VALID means "the client could have
  picked it"; it never accepts what no order allows.
- `PickWorld`'s default `outlineShape` is `state.getShape(EmptyBlockGetter, pos, CollisionContext.empty())`: exact for
  every block except light and scaffolding when the attacker holds that item (the only outline shapes that read the
  context: `LightBlock`, `ScaffoldingBlock`) and block-entity shapes (an open shulker box reads as closed). The fluid
  shape is never read (`Fluid.NONE`); `clipWithInteractionOverride` only changes the face, never the location.

### 2.3 Replication

```java
PathFeed feed = new PathFeed(entityId, capacity /* 64 */, new PathFeed.Settings(stepTicks /* 2 */, keepAlive, preRoll, respawnDistance /* 48 */));
feed.append(x, y, z, yRot, xRot, onGround, stampMillis);    // once per simulated client tick of the target (R1)
PathFeed.Viewer v = feed.viewer();                           // per (viewer, target)
v.spawned(addX, addY, addZ, yRot, xRot, onGround, stamp);    // right after AddEntity went to the viewer (its doubles: the codec base)
PathFeed.Result r = v.drain(sink, displayX, displayY, displayZ); // every server tick; display = the viewer's mirror x/y/z, or NaN
v.despawned();
```

- Each new step goes out as its own `MoveEntity.PosRot` with a one-step `VecDelta.Stepped` of `stepTicks` ticks and the
  step's rotation bytes (R2, R3). A delta past ±32767 units goes as `EntityPositionSync` with a one-step `Stepped` path
  (never an empty one: the client's codec throws).
- R4: a step the client would ignore (`target.is(end, yRot, xRot)`: zero delta and the same rotation bytes as the held
  target) is nudged +1 unit (1/4096 block) on x; the next still step decodes back. A still player then keeps the
  queue fed and never restarts it. Without keep-alive the client does not freeze but restarts its queue every second
  tick (`currentStepTicks = 1`, report 03 M2), so a walk that starts from standing begins half a step in.
- Pre-roll (report 03 §6.3 M3): after a spawn the first packet is a hold step at the spawn point, so the first rendered
  tick shows the spawn point instead of half the first step.
- `RESPAWN` (R6): the next step is a sync whose end is more than `respawnDistance` from the mirror's display or from the
  last end sent (the client snaps past 64 blocks from its display and then shifts its queue by a collision test the
  server can't see), or the viewer fell more than `capacity` steps behind. Nothing is sent; re-pair and call `spawned`.
- The `Viewer` tracks the viewer's `VecDeltaCodec` base exactly (the decoded end of every move, the exact end of every
  sync) and the target its client holds (decoded end, unpacked rotation).

## 3. Proof

### 3.1 The mirror against the real client (`auth-rig/combat`, `MirrorVerify`)

The rig compiles `auth.combat` and `auth.replicate` from this checkout, unchanged, against `client-26.3.jar`, and drives
the real client code: a real `Mannequin` (Avatar → LivingEntity → Entity; Player, AbstractClientPlayer and RemotePlayer
override none of the methods involved) built by its real constructor on a client-side level (a Mockito stub answering
`isClientSide`, `getRelativeTickSpeed`, `noCollision`), so its interpolation handler is the client's
`SteppedInterpolationHandler`; packets pass through the bodies of `ClientPacketListener.handleMoveEntity`,
`handleEntityPositionSync`, `handleTeleportEntity` (+ `setValuesFromPositionPacket`), `handleSetEntityData`,
`handleUpdateAttributes`, copied verbatim, and the real `LivingEntity.recreateFromPacket`; each tick runs the real
`interpolate()` and `refreshDirtyAttributes()`. Movement packets go through their real stream codecs first.

Streams (`MirrorStreams`, in the test sources, shared by the rig and `MirrorFingerprintTest`):

- adversarial: spawns near the world limit and off-grid, 0-3 packets per tick, `Pos`/`PosRot`/`Rot`, `Linear` and
  `Stepped` (1-3 steps, offsets 0-20), repeated targets, still and huge deltas, syncs near and past 64 blocks with float
  rotations including NaN, -0.0, ±720, teleports with random relative flags, every pose, scale updates with modifiers,
  despawns and re-adds, relative tick speeds 1 / 1.25 / 1.5 / 2 / 3.7;
- fed: a player walking, sprint-jumping, turning, standing still, hopping 9-40 blocks and teleporting 60-260 blocks,
  0-2 authoritative ticks per server tick, through `PathFeed` with re-pairs on `RESPAWN`, delivered with 0-3 ticks of
  jitter.

Before and after every client tick it compares, as raw bits: the box, position, rotation, codec base, target position
and rotation, `currentStepTicks`, `remainingTicks`, `interpolationSpeed`, queue length and the dimensions' width.

```
~/Developer/tools/auth-rig/combat/build.sh MirrorVerify 5000 2500 200 20261021
bootstrap 1860 ms; PLAYER updateInterval 2, MANNEQUIN 2
OK: 2,000,000 ticks (1,000,000 adversarial, 1,000,000 fed), 4,000,000 state comparisons, 11,392 far snaps, 0 mismatches, 170.3 s
```

Mutation check (one deliberate bug each, 100,000 ticks): `currentStepTicks = 1.0F + 1e-6F` caught at tick 0; dropping
the rotation delta caught; skipping the snap shift caught; scaling fixed dimensions caught. One survivor,
`clamp(x) % 360` vs `x % 360` on spawn pitch, is equivalent for every byte-derived angle.

`MirrorFingerprintTest` pins the mirror's state over fixed streams to the rig's fingerprints
(`0xEB1BDE5FF2784AC9L` adversarial seed 7, `0x7C4B2C64ECE587A2L` fed seed 11). It passes in `runRevivalTests` against
the RevivalPaper classes: the port computes the same bits on the server as on the vanilla client.

### 3.2 The ray against the real client (`PickVerify`)

The reference is the client's own private `LocalPlayer.pick` (it loads in the rig and is called by reflection), with a
real camera entity, the real `Entity.pick` → `BlockGetter.clip` on a sparse world (all 35,575 block states with an
outline) and the real `ProjectileUtil.getEntityHitResult`. Scenes: eyes on and 1e-7 off block and box boundaries,
moving attackers (`xo != x`), three eye heights, exact cardinal and sensitivity-grid rotations, default and random
ranges, 0-7 blocks along the ray, 0-4 player-sized boxes on, near and grazing the ray, around the eye, with a face
exactly at entity range, exact duplicates (ties), and one scene in eight with a box sharing a full block's face.
It compares kind, entity, block position and the location's bits; for every scene with candidates it also runs vanilla
in every candidate order and checks `canPick` against "some order returns it".

```
~/Developer/tools/auth-rig/combat/build.sh PickVerify 1000000 20261021
OK: 1,000,000 scenes (392,098 entity, 407,565 block, 200,337 miss), 824,560 with every candidate order checked
    (89,889 with order-dependent picks, 297,092 eye-inside candidates), 0 mismatches, 36.8 s
```

Mutation check: half-open `contains` made closed caught; the `nearest == 0.0` quirk removed caught; the eye taken as
`x` instead of `xo + (x - xo)` caught; `<` vs `<=` against the block distance caught by the coplanar scenes. Two
survivors are float-edge guards kept for exactness (the traversal's -1e-7 end pull and `dd < maxDistanceSq`).

`PickRayTest.blockClipMatchesTheServersVoxelShapeClip` checks the block pass against the server's Moonrise-patched
`VoxelShape.clip` and `toAabbs` for every fifth block state (the port reads only `toAabbs()`, which Moonrise caches but
does not change).

### 3.3 In-repo tests (`./gradlew :leaf-server:runRevivalTests`, 60 tests)

| Class | What |
|---|---|
| `RemoteMirrorTest` (15) | report 03 §5.1 vanilla trace (half step, then freeze when late), K2 equilibrium (speed 2, 1.5 steps behind), ignored identical target, rotation-only step, lerped rotations across a stepped path, same-end collapse, pose at packet time, scale after the tick, far sync snap (inexact) vs idle snap (exact), teleport from the target and codec base untouched, world-limit clamp, codec decode, display stamps |
| `MirrorBookTest` (8) | arrival ticks and the pick-time sample, late events, unbundled packets, bounded history and gaps, removal and pose via packets, unknown ids and non-players ignored, riding, clear |
| `MirrorFingerprintTest` (3) | player update interval is 2; both fingerprints |
| `PickRayTest` (11) | view vector equals `Entity.calculateViewVector` (10,000 rotations), eye lerp, reach strictly below the range (9.0 exactly misses), walls, a box flush with a block face, slabs, nearer of two players, eye inside (and the order quirk), the 1e-7 clip tolerance, block clip vs the server's `VoxelShape.clip` |
| `HitValidatorTest` (20) | valid, out of reach, missed, blocked, occluded, eye inside the target, eye inside another player, not present / removed, inclusive rewind cap, rewind from the displayed step, inexact target, no sample, spear, exact vs inexact non-player blockers; past the cap (a real `PathFeed` as the truth): a lead lands, the stale box misses, the first step at the capped age and never an older one, REWIND only with no truth that new, the truth never asked inside the cap, an inexact mirror judged on the truth |
| `PathFeedTest` (11) | `positionSince` (the oldest held step at or after a stamp, inclusive; none past the newest; the ring's oldest when it no longer reaches back), PosRot with a 2-tick stepped delta through the real codec, viewer base equals the real `VecDeltaCodec` over 2,000 random ticks (syncs and keep-alives included), grid and exact sync positions, respawn decisions, ring overrun, no freeze for 1 or 2 silent ticks and one for 3, steady state 1.5 steps behind at speed 2, still player kept alive within 1/4096 block (and restarts without keep-alive), pre-roll |

### 3.4 Cost (this Mac, JDK 25, after warm-up)

`auth-rig/combat/build.sh Bench`: `PathFeed` drain 46 ns per viewer per target per server tick (packet built);
`MirrorBook` 66 ns per target per viewer tick, events included (1.3 µs for 20 targets); `HitValidator` 0.6 µs per
attack among 20 mirrored players.

### 3.5 Solid entities against the real client (`SolidVerify`)

`auth.combat.SolidMirrors` is each viewer's copy of the boats (every `AbstractBoat`: boats, chest boats, rafts),
shulkers and happy ghasts its client holds, the colliders of that client's own player (auth-sim-requests §2,
`WorldView.collectEntityColliders`). It is fed by the same transaction-acked outbound log as the player mirrors
(`OutboundClassifier.SOLID_ENTITY`, `Transactions.trackedSolids`) and ticked where the client ticks its entities:
after its player, except entities still there from before a same-level respawn, which tick before it.

- Boats: a port of `LinearInterpolationHandler` (3 steps) and `AbstractInterpolationHandler` (restart unless the same
  target is active, the target shifted by a snap during an interpolation, rotation deltas), `Entity.snapTo`/`setPos`/
  `setRot`, the type's `EntityDimensions.makeBoundingBox`. A vehicle this client drives ignores the server's moves.
- Shulkers: no interpolation, `Shulker.setPos` snapping (and zeroing the peek on a block change), the box from
  `Shulker.getProgressAabb` with the live scale (at most 3), the attach face (rebuilt when it arrives) and the client's
  own peek stepped 0.05 a tick; a lid opening into the player's box is reported as an external mover.
- Happy ghasts: `RemoteMirror` with the type's update interval (3), dimensions refreshed as `LivingEntity` does (scale
  at most 1 after `refreshDirtyAttributes`, at once for a baby or pose change, `BABY_DIMENSIONS`, sleeping); rule
  `ENTITY_SOLID` while `STAYS_STILL`, else `ENTITY_SOLID_FROM_ABOVE`; never a baby or a dead one.
- Order: section x, then the section key's z and y bits unsigned, then when each entered its section (an entity moved
  into another section goes last there, a raw `snapTo` position counting).
- A copy is exact until the client does something the log can't see (a target shifted only where its world has room,
  a lid shoving a boat, a vehicle it drove, a passenger); `takeRepairs` hands those to `AuthPlayer`, which re-pairs the
  entity through its tracker so a logged spawn replaces the copy. Until then (and for placeholders after activation or a
  lost log) a tick touching it is the port's external-mover exemption.

The rig (`ClientSolids`) builds real entities with `EntityType.create` on the client-side Mockito level, applies the
`ClientPacketListener` handler bodies (copied verbatim), ticks them with the real `commonTick`,
`refreshDirtyAttributes`, `Shulker.updatePeekAmount`/`onPeekAmountChange`, and keeps them in a real
`EntitySectionStorage` through a copy of `TransientEntitySectionManager.Callback`. Streams (`SolidStreams`, test sources):
1-4 entities of mixed types, some packed at section corners (one where section z and y change sign), moves (linear and
stepped deltas, rotation only), syncs and teleports near and past 64 blocks, attach faces, peeks, health, still timeout,
baby, pose, scale, despawns and respawns, relative tick speeds 1 / 1.25 / 2 / 3.7. Before and after every client tick
it compares each exact copy's box, position, rotation, a boat's target and steps, a shulker's peek, as raw bits, and
checks that the order the mirror hands the port contains `EntitySectionStorage.getEntities`' collidable entities over a
box around one of them in the same order.

```
~/Developer/tools/auth-rig/combat/build.sh SolidVerify 6000 200 1
OK: 1,200,000 ticks, 9,600,000 entity-state comparisons (2,681,947 exact, 379,141 skipped inexact), 900,140 order checks (747,549 ordered pairs), 0 mismatches, 156.5 s
fingerprint seed=3 trials=300 ticks=200: 0x7D4E6C44C7C5A3ADL
```

Mutation check (500 trials each): lerp rounding (`t·a + x·(1 − a)`), `Math.sin` for `Mth.sin` in the peek, no peek
reset on a block change, always restarting the boat interpolation, a ghast scale cap of 3, z and y compared as signed
ints, entry order ignored, re-entry on every move: all caught. Two equivalent mutants survive: a signed compare of the
whole key (same x, so the same order) and dropping the raw position's section in `snapTo` (a snap is over 64 blocks, so
it always lands in another section). `SolidMirrorsTest` pins the fingerprint and covers the three-step lerp, a far
snap and a shifted target, the peek and lid push, the attach face, the ghast rules, the section order, passengers and
driven vehicles, and placeholders.

## 4. Findings that differ from the reports

- **Scale reaches the box one tick late.** `UpdateAttributes` only dirties the attribute; the dimensions follow in the
  target's entity tick (`LivingEntity.tick` → `refreshDirtyAttributes` → `onAttributeUpdated(SCALE)`), after that tick's
  `interpolate()`. A pose change in between reads the new scale at once. (Report 03 §2.5 says packet time.)
- **Rotation deltas are always added** in `adjustInterpolationTargetFromDeltas`, even zero ones, which turns `-0.0F`
  targets into `0.0F`. Irrelevant to boxes, mirrored anyway.
- **`getViewYRot`:** a `LivingEntity` looks along its head yaw; `LocalPlayer` overrides that back to `getYRot`, so the
  attack ray uses the body rotation the move packet reports (report 02 is right; a rig camera must copy it).
- **The entity-pass ray ends exactly at the block hit**, and `AABB.clipPoint` needs `s < 1`: a box flush with the block
  face is never reached.
- **Without keep-alive** a still player costs restarts, not freezes (see 2.3).

## 5. What the hooks must do

Viewer side (lag compensation):

1. Every packet about a remote player that reaches a viewer (list in 2.1) must go out inside a pinged bundle (design
   §3.2), and on its ack: `book.onPacket(packet, T, stamp, exact)` in wire order, `exact = false` for the unbundled
   (upper-bound) ones. `CombatWiring.onApplied` currently drops `exact`; it should pass it.
2. `book.onViewerTickEnd(T)` at `TickEnd(T)`, or before the first attack of `W_T` (both is fine).
3. `ClientboundTickingStatePacket` acked → `book.setRelativeTickSpeed(rate > 20 ? rate / 20 : 1)`.
4. Attack in `W_T`: `AttackerSnapshot` from the simulation (`xo`/`x` at the end of T-1, the pose's eye height, the
   attacker's own box) and tick T's raw rotation; `validate(..., book, T, nonPlayers, worldView, now, cap)`.
   VALID → `player.attack(target)`; INVALID → drop (log in `observe`); UNKNOWN → vanilla/Paper checks. Pass non-player
   boxes as `Candidate(exact = false)` only if they should be able to turn a hit into UNKNOWN.
5. `PickWorld`: `WorldView` already implements it; override `outlineShape` when the attacker holds scaffolding or a
   light block.

Replication (hook 1014, report 03 §8):

1. One `PathFeed` per player under authority; `PathSink.onTick` → `feed.append(..., now)`.
2. Pairing (`ServerEntity.addPairing`): after the `AddEntity` bundle, `viewer.spawned(add.getX(), add.getY(), add.getZ(),
   yRot, xRot, onGround, now)`; the book learns the same `AddEntity` at its ack.
3. Every server tick, for each viewer and each player it tracks: `drain(sink, mirror.x(), mirror.y(), mirror.z())`
   into that viewer's per-tick bundle ending in a ping (R5). `RESPAWN` → `RemoveEntities` + pairing + `spawned` +
   `drain` again.
4. Suppress the vanilla movement output for player targets (R7): no `MoveEntity`, `Rot`, `EntityPositionSync` (forced,
   on-ground flips, every-400, `forceStateResync`) from `ServerEntity.sendChanges` or Leaf's `leaf$sendChanges`. Head
   rotation, equipment, passengers and entity data stay (entity data preferably in the R5 bundle).
5. `viewer.despawned()` on unpair.

Settings: `PathFeed.Settings.stepTicks` and `keepAlive` are `authoritative.replication.path-step-ticks` and
`keep-alive`; `maxRewindMillis` is `authoritative.max-rewind-ms`. Still hard-coded in callers and wanting keys:
`replication.pre-roll` (true), `replication.respawn-distance` (48.0), `replication.path-buffer` (64, the ring),
`mirror-history-ticks` (`CombatWiring.HISTORY_TICKS`, 64).

## 6. Not covered

- Non-player targets and blockers are not mirrored (no exact interpolation for mobs); spears' `AttackRange` pick and
  riding attackers are UNKNOWN.
- Pistons and shulkers pushing a target on the viewer's client, chunks the viewer doesn't tick, and the snap-shift
  collision test are not modelled (each makes the replay inexact, never wrong silently, except the first two, which no
  packet reveals).
- R8 (holding pose changes until the display reaches them) is not implemented; exactness doesn't depend on it.
- The mirror was proven against the client's own classes driven by copied handler bodies, not against a running
  client; the end-to-end check is report 03 §7.5 step 2 (a client that logs boxes at each pick).
