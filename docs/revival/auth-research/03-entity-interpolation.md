# 03 · Entity movement replication and interpolation (26.3 vs 26.2)

How a vanilla client moves *other* players on screen, what the server sends to drive it, why 26.3 stutters
(MC-311974 / MC-311976), what a server alone can do about it, and how the server can rebuild, bit for bit,
where viewer V's client had target T's hitbox on any client tick.

Path prefixes used for citations:

| Prefix | Path |
|---|---|
| `c3/` | `~/Developer/tools/mc-src/src-26.3/net/minecraft/` (decompiled 26.3 client, includes common + integrated server) |
| `c2/` | `~/Developer/tools/mc-src/src-26.2/net/minecraft/` |
| `lp/` | `~/Developer/GitHub/RevivalPaper/leaf-server/src/minecraft/java/net/minecraft/` (Minecraft with Paper, Leaf and RevivalPaper patches applied, branch `revival/authoritative`) |
| `leaf/` | `~/Developer/GitHub/RevivalPaper/leaf-server/` |

Method. Everything below was read from the sources. The 26.3 client algorithm was then ported to plain Java
(appendix A) and checked against the real `SteppedInterpolationHandler$InterpolationData` class inside
`client-26.3.jar`, driven by reflection with random packet streams (linear, stepped, rotation-only, rotation
changes, 0-3 packets per tick): **400,000 ticks, bit-identical** position, rotation, `currentStepTicks`,
`remainingTicks`, `interpolationSpeed` and queue length (harness in appendix B). All numbers in sections 5 and 6
come from running that verified port.

---

## 0. Key facts

1. **Where the hitbox moves.** A remote player's position, and with it its `AABB`, changes only inside
   `Entity.commonTick()` → `interpolate()` (`c3/world/entity/Entity.java:527-537`), which runs in
   `ClientLevel.tickNonPassenger` (`c3/client/multiplayer/ClientLevel.java:471-474`) during
   `level.tickEntities()` (`c3/client/Minecraft.java:1833`). The attack ray is cast earlier in the same tick,
   by `pick(1.0F)` (`Minecraft.java:1812`) before `handleKeybinds()` (`:1822`). **So the attack in client tick n
   tests T's box as left by tick n−1's interpolation**, plus any pose/size change or snap applied by packets
   drained before tick n. 26.2 has the same relation (interpolation ran in `RemotePlayer.aiStep`, still after
   the pick; `c2/client/player/RemotePlayer.java:43-46`).
2. **26.2** used a 3-step "chase the latest target" lerp for every living entity
   (`c2/world/entity/InterpolationHandler.java:80-110`, steps = 3 from `LivingEntity.java:254`). With packets every
   2 ticks the display sits a constant **2 ticks** behind the newest received position and never runs dry.
3. **26.3** replaces it, for every `LivingEntity` (players included), with `SteppedInterpolationHandler`: a FIFO
   of path steps, each with its own duration in ticks, played at a speed that adapts to the backlog
   (`c3/world/entity/SteppedInterpolationHandler.java`). Its nominal length `N` is
   `entity.getType().updateInterval()` (`:13-15`), which is **2 for players** (`c3/world/entity/EntityTypes.java:1173-1184`).
4. **Vanilla 26.3 servers** send players every 2 ticks with one step of duration 2. The client's queue is
   then exactly empty when the next packet is due. Any late packet freezes the entity for a tick
   (`interpolate()` → `cancel()`), and every restart begins **one tick into** the new path
   (`setStartingPoint` sets `currentStepTicks = 1.0F`, `:110-113`). That is the stutter.
5. **Wire changes 26.2 → 26.3:** `MoveEntity.Pos/PosRot` gained a VarInt "properties" field
   (`onGround | stepCount << 1`) and an optional list of `(ticks, dx, dy, dz)` steps; `MoveEntity.Rot` moved
   `onGround` *before* the rotation bytes; `EntityPositionSync` now carries a `PositionPath` and float
   rotations and **no velocity** (section 3).
6. **The client's catch-up controller caps the useful buffer at N = 2 ticks.** Its equilibrium, when the server
   feeds K path-ticks per real tick (K ≥ 2), is backlog `R = 2K` and speed `s = K`, so the display trails the
   newest data by `(2K−1)/K` ticks. More buffer than ~2 ticks is impossible with a vanilla client
   (section 6.2).
7. **Proposal (section 6):** send **one step per authoritative T-tick, every server tick, each step
   lasting K = 2 ticks**, keep the client queue from ever emptying (a 1/4096-block keep-alive when T is
   perfectly still), never send 1-tick steps, wrap each viewer's per-tick movement in a bundle that ends in a
   ping. Result: no restarts after spawn, no freezes unless 3 consecutive ticks arrive empty, a fixed
   steady-state delay of **1.5 ticks** after arrival (26.2: 2, vanilla 26.3: 1 but stutters), smooth
   slow-down instead of freezing under jitter. Simulated at 15 ms jitter on both legs: **0 freeze ticks per 1000**
   vs 13 for vanilla 26.3.
8. **Exact reconstruction (section 7)** is possible because the client is deterministic: with every packet
   affecting T on V and the client tick before which V drained it (from the bundled ping), replaying
   appendix A reproduces V's `AABB` of T bit for bit.

---

## 1. Client tick anatomy (what happens when)

### 1.1 26.3

`Minecraft.runTick(advanceGameTime)` (`c3/client/Minecraft.java:1184-1229`):

1. `ticksToDo = deltaTracker.advanceGameTime(now)` (`:1197`).
2. `packetProcessor.processQueuedPackets()` (`:1202`): drains **every** queued clientbound packet, including
   ones that arrive while it drains (`c3/network/PacketProcessor.java:34-40`). A `ClientboundBundlePacket` is one
   queue entry; its sub-packets run back to back (`ClientPacketListener.java:2468-2474`; bundling happens in
   the Netty decoder, `c3/network/PacketBundlePacker.java`).
3. `for i < min(10, ticksToDo): tick()` (`:1216-1222`). Several ticks can run after one drain (low FPS,
   hitches); no packet is applied between them.
4. Render; per-frame `pick(partialTicks)` for the crosshair only (`:1318`).

`Minecraft.tick()` (`:1795-1894`), in order:

| Step | Line | Effect on remote entities |
|---|---|---|
| `gameMode.tick()` | 1808 | none |
| `pick(1.0F)` → `LocalPlayer.raycastHitResult(1.0F, camera)` | 1812, 3009-3019 | **attack/use ray uses the targets' current `getBoundingBox()`** |
| `handleKeybinds()` → `startAttack()` → `gameMode.attack(player, target)` | 1822, `startAttack` | sends the attack using that ray |
| `level.tickEntities()` → `tickNonPassenger(e)` → `e.commonTick()` then `e.tick()` | 1833; `ClientLevel.java:454-480` | `commonTick`: `setOldPosAndRot()`, then `getInterpolation().interpolate()` on the client (`Entity.java:527-537`) → `setPos` → new `AABB` |
| `player.sendChanges()` (movement packet) | 1838 | none |
| `level.tick`, particles | 1859-1880 | none |
| `send(ServerboundClientTickEndPacket)` | 1885 | marks end of tick |

`Entity.setPos(x,y,z)` = `setPosRaw` + `setBoundingBox(makeBoundingBox())` (`Entity.java:488-499`). So the
box always equals `dimensions.makeBoundingBox(position)` after any position change.

Passengers are ticked through `tickPassenger` → `commonTick()` + `rideTick()` (`ClientLevel.java:482-494`);
their position comes from the vehicle (`positionRider`), not from interpolation.

### 1.2 26.2

Same `Minecraft.tick` order (`pick(1.0F)`, `handleKeybinds`, `tickEntities`, `TickEnd`; verified in
`c2/client/Minecraft.java`). Differences:

- No `commonTick`. `ClientLevel.tickNonPassenger` is `setOldPosAndRot(); tickCount++; tick()`
  (`c2/client/multiplayer/ClientLevel.java:468-478`).
- Remote players interpolate inside `RemotePlayer.aiStep()` (`c2/client/player/RemotePlayer.java:43-46`),
  i.e. inside `tick()` after `baseTick`. Other living entities interpolate in `LivingEntity.aiStep`
  (`c2/world/entity/LivingEntity.java:3044-3045`).
- Net effect on hit tests is identical: tick n's attack sees tick n−1's interpolation result.

### 1.3 Which handler each entity type uses (26.3)

| Class | Handler | Source |
|---|---|---|
| `LivingEntity` (players, mobs, armor stands, mannequins) | `SteppedInterpolationHandler`, N = `type.updateInterval()` | `c3/world/entity/LivingEntity.java:3303-3305` |
| `Display` | `LinearInterpolationHandler`, steps from entity data (0 = snap) | `Display.java:188-190`, `:128` |
| `AbstractBoat` | Linear, 3 | `vehicle/boat/AbstractBoat.java:202-204` |
| `AbstractMinecart` (old behaviour) | Linear, 3; new behaviour: own `lerpSteps` | `vehicle/minecart/AbstractMinecart.java:360-362` |
| `ExperienceOrb`, `FishingHook` | Linear, 3 | `ExperienceOrb.java:387`, `projectile/FishingHook.java:113` |
| everything else, `Shulker` | `NO_OP` (positions applied directly) | `Entity.java:2663-2665`, `monster/Shulker.java:428` |

On the server, `SteppedInterpolationHandler.create` returns a `SteppedInterpolationTracker` instead
(`SteppedInterpolationHandler.java:17-19`), which records the path that the next packet will carry (4.2).

`updateInterval` per type (26.3 `EntityTypes.java`; builder default 3, `EntityType.java:494`):
1 = the three displays; **2 = `PLAYER`, `MANNEQUIN`, `ALLAY`, `EVOKER_FANGS`**; 4 = eye of ender; 5 = fishing
bobber; 10 = most thrown projectiles, TNT, fireworks; 20 = arrows, trident, items, XP orbs, falling blocks;
none (`Integer.MAX_VALUE`) = frames, paintings, leash knots, area clouds, lightning, end crystals; 3 = all others
(123 types). `PLAYER` also has `dontTrackDeltas()` (`EntityTypes.java:1183`). Disguising players as
`MANNEQUIN` gains nothing (also 2).

---

## 2. The client algorithms

### 2.1 26.3: `AbstractInterpolationHandler` + `SteppedInterpolationHandler`

State (per entity, client side):

| Name | Type | Meaning | Source |
|---|---|---|---|
| `entity.position`, `yRot`, `xRot` | double×3, float×2 | what is rendered and hit-tested | `Entity` |
| `N` = `interpolationSteps` | int | `type.updateInterval()` (2 for players) | `SteppedInterpolationHandler.java:13-15` |
| `target` | `PositionAndRotation.Mutable` (the `InterpolationData` itself) | end of the last accepted path | `:39`, `:61` |
| `remainingSteps` | `LinkedList<Step(pos, yRot, xRot, int tickOffset)>` | queued path | `:62`, `:202-209` |
| `lastStepPosRot` | pos/rot | start of the current step | `:63` |
| `currentStepTicks` (c) | float | path time into the current step | `:64` |
| `remainingTicks` (R) | float | path ticks not yet consumed | `:65` |
| `interpolationSpeed` (s) | float, starts 1.0 | path ticks consumed per real tick | `:66` |
| `lastPositionAndRotation` | pos/rot | entity pos/rot after the last interpolate | `AbstractInterpolationHandler.java:10` |

`active` ⇔ `!remainingSteps.isEmpty()` (`:52-54`).

**On a packet** — `Entity.moveOrInterpolateTo(path?, yRot, xRot, hasRotation)` (`Entity.java:2632-2658`) calls
`AbstractInterpolationHandler.interpolateTo` (`AbstractInterpolationHandler.java:30-47`):

```
current := active ? target : entity.storePositionAndRotation()
path    := path ?? Linear(current.position)                // rotation-only packets
y, x    := hasRotation ? (yRot, xRot) : (current.yRot, current.xRot)
if N == 0:            entity.snapTo(path.end, y, x); cancel()           // never for players
elif !active || !target.is(path.end, y, x):                            // is(): float ==, Vec3.equals (Double.compare)
    startInterpolating(path, y, x)
    lastPositionAndRotation := entity pos/rot
    entity.onInterpolationStart(this)                                  // no-op for players
// else: identical to the current target while active -> ignored, nothing queued
```

`startInterpolating` (`SteppedInterpolationHandler.java:27-40`):

```
if !active:
    lastStepPosRot := entity pos/rot
    c := 1.0F                                   // <-- the "starts one tick in" rule; R and s are NOT touched
end := path.end
if Vec3.equals(end, target.position):           // end equals the *old* target position
    addStep(end, y, x, N)                       //   whole path collapses to ONE step of N ticks
else switch path:
    Linear(end)   -> addStep(end, y, x, N)
    Stepped(steps):
        if y == target.yRot && x == target.xRot:
            for st in steps: addStep(st.pos, y, x, st.tickOffset)
        else:
            T := Σ st.tickOffset; acc := 0
            for st in steps:
                acc += st.tickOffset; a := (float)acc / T             // float
                addStep(st.pos, Mth.rotLerp(a, target.yRot, y),      // float overloads (javap-verified)
                                Mth.lerp(a, target.xRot, x), st.tickOffset)
target := (end, y, x)
addStep(p, y, x, d): remainingSteps.add(Step(p, y, x, d)); R += d     // :115-118
```

**Every client tick** — `interpolate()` (`AbstractInterpolationHandler.java:56-64`):

```
if !active: cancel()  -> remainingSteps.clear(); R := 0; s := 1   (:57-59, :82-86)   // c is NOT reset
            return                                                // entity keeps its position (freeze)
adjustInterpolationTargetFromDeltas()   // :73-82, see 2.4; a no-op for remote players unless snapped/pushed
P := getNewPositionAndRotation()        // :180-199
entity.setPos(P.position)               // -> new AABB
entity.setRot(P.yRot, P.xRot)           // setYRot(y % 360), setXRot(clamp(x % 360 % 360, -90, 90)) (Entity.java:479-482, 4057-4075)
advance(level.getRelativeTickSpeed(), N)  // 1.0F unless server tick rate > 20 (ClientLevel.java:804-807)
lastPositionAndRotation := entity pos/rot
```

`getNewPositionAndRotation()` (`:180-199`):

```
while remainingSteps not empty:
    st := first
    if c < st.tickOffset:                         // float < int
        a := c / st.tickOffset                    // float
        return ( lastStep.pos.lerp(st.pos, (double)a),          // Vec3.lerp: p0 + a*(p1-p0) per axis (Vec3.java:230-232)
                 Mth.rotLerp(a, lastStep.yRot, st.yRot),        // from + a*wrapDegrees(to-from), float
                 Mth.lerp(a, lastStep.xRot, st.xRot) )          // p0 + a*(p1-p0), float
    c -= st.tickOffset
    lastStep := st; remainingSteps.removeFirst()
return target                                     // queue consumed: show the end exactly
```

`advance(ticks, N)` (`:68-80`, javap-verified float ops):

```
ts := max(R / N, 1.0F)
s  := s + (1.0F/N) * (ts - s)                     // Mth.lerp(1/N, s, ts)
if ticks * s < R:  ticks := ticks * s
else:              ticks := R; s := 1.0F          // last bit: consume exactly what is left, reset speed
c += ticks; R -= ticks
```

Properties that matter later (all hold in exact arithmetic; float checks in 5.4):

- **P1 (offset 1).** Since every restart sets c = 1 and every tick adds the same amount to c and removes it from
  R, the evaluated path time is always *one tick ahead* of the consumed time: `c_abs = 1 + consumed`,
  `R = Σadded − consumed`. The path left *after the displayed point* is `R⁻ − 1` where `R⁻` is R at the start
  of the tick (after packets).
- **P2.** The queue empties during the tick in which `R⁻ ≤ 1`; that same tick's `advance` then clamps
  (s ≥ 1 ≥ R) so R = 0 and s = 1 exactly.
- **P3.** `s ≥ 1` always (ts ≥ 1, s starts at 1, lerp stays in range). The client can only *speed up*,
  except that with K ≥ 2 path-ticks per real tick "s < K" is a slow-down (6.2).
- **P4.** When the server feeds exactly 1 path-tick per real tick and `R⁻ ≤ N`, `ts = 1` and s stays at 1: the
  controller is inactive; backlog is neither corrected up nor down.

### 2.2 26.2: `InterpolationHandler`

`c2/world/entity/InterpolationHandler.java` (state `steps`, `target pos/rot`, `previousTickPosition`,
`previousTickRot`; `interpolationSteps` = 3 for living entities, `LivingEntity.java:254`):

```
interpolateTo(pos, y, x):                                        // :52-70
    if interpolationSteps == 0: snapTo; cancel
    elif steps == 0 || y != targetY || x != targetX || pos != targetPos:   // position()/yRot()/xRot() return target while active
        steps := 3; target := (pos, y, x)
        previousTickPosition := entity.position; previousTickRot := (xRot, yRot)
interpolate():                                                    // :80-110
    if steps == 0: cancel (steps=0, prev=null); return
    alpha := 1.0 / steps                                          // double
    if prev != null:
        d := entity.position - prev
        if level.noCollision(entity, makeBoundingBox(target + d)): target += d
    if prevRot != null: target.rot += entity.rot - prevRot
    p := (Mth.lerp(alpha, e.x, t.x), Mth.lerp(alpha, e.y, t.y), Mth.lerp(alpha, e.z, t.z))
    y := (float)Mth.rotLerp(alpha, (double)e.yRot, (double)t.yRot);  x := (float)Mth.lerp(alpha, (double)e.xRot, (double)t.xRot)
    setPos(p); setRot(y, x); steps--; prev := p; prevRot := (x, y)
```

Rotation-only / position-only packets keep the missing parts from the current target
(`c2/world/entity/Entity.java:2562-2585`). Every new target restarts a fresh 3-step chase from wherever the
entity is. With packets every 2 ticks and constant speed v, the lag at a packet is 3v, after the first tick 2v,
after the second v, then the next packet arrives: per-tick motion is exactly v and the displayed position is
always **the true position 2 ticks before the newest received one** (simulated: delay = latency + 2.000,
section 5.5). Non-linear motion (strafes, jumps) is low-pass filtered, not reproduced.

### 2.3 26.3 `LinearInterpolationHandler` (boats, old minecarts, orbs, bobbers, displays)

`c3/world/entity/LinearInterpolationHandler.java:33-47`: a new target resets `remainingSteps = N`; each tick
`alpha = 1.0/remainingSteps`, `pos = entity.pos.lerp(target, alpha)`, rot via double `rotLerp`/`lerp`,
`remainingSteps--`. Same idea as 26.2 but `Vec3.lerp` instead of per-axis `Mth.lerp` (numerically identical).

### 2.4 Things that move a remote player outside interpolation

- `snapTo` from `EntityPositionSync` when the jump is > 64 blocks (`distanceToSqr > 4096`) or the entity is not
  in the ticking list (`ClientPacketListener.java:659-664`); `setPos` from `TeleportEntity` in the same cases
  (`:809-829`). **Neither cancels the queue.** On the next `interpolate()`,
  `adjustInterpolationTargetFromDeltas` sees `entity.position − lastPositionAndRotation.position` and, if the
  box at `target + delta` is collision-free on V's world (`level.noCollision`), translates the target, every
  queued step and `lastStepPosRot` by that delta (`AbstractInterpolationHandler.java:66-82`,
  `SteppedInterpolationHandler.java:89-97`). Rotation deltas are always added.
- Pistons/shulkers: `move(PISTON|SHULKER…)` → `recordMovement` → `applyPredictedMovement` shifts target and
  `lastPositionAndRotation` together (`Entity.java:838-842`, `AbstractInterpolationHandler.java:50-53`).
- Riding: `positionRider` every tick.
- `RemotePlayer` has `noPhysics = true` and an `aiStep` that only lerps head yaw and delta movement, bobs and
  pushes others (`c3/client/player/RemotePlayer.java:17, 43-65`): **no `travel`, no collision, no gravity**.
  Its `deltaMovement` (from `SetEntityMotion`, lerped over `updateInterval + 1` = 3 ticks, `:68-75`) never moves it.

### 2.5 The hitbox the attack ray uses

- `LocalPlayer.pick` (`c3/client/player/LocalPlayer.java:1292-1322`): `from = camera.getEyePosition(1.0F)`,
  `to = from + view(1.0F) × maxDistance`, search box = own box expanded and inflated by 1;
  `ProjectileUtil.getEntityHitResult` (`c3/world/entity/projectile/ProjectileUtil.java:110-147`) tests each
  candidate's `getBoundingBox().inflate(getPickRadius())` (pick radius 0 for players, `Entity.java:2675-2677`),
  `bb.clip(from, to)`, inside-hit if `bb.contains(from)`; then `filterHitResult` against
  `entityInteractionRange` from the eye. Spectators are not pickable (`player/Player.java:1644-1646`).
- Box = `dimensions.makeBoundingBox(pos)` = `AABB(x − w, y, z − w, x + w, y + h, z + w)` with `w = width/2.0F`,
  `h = height` as floats widened to double (`c3/world/entity/EntityDimensions.java:15-23`).
- Player dimensions (`c3/world/entity/Avatar.java:18-38`): standing 0.6F × 1.8F (so `w = 0.30000001192092896`,
  `h = 1.7999999523162842`), crouching 0.6F × 1.5F, swimming/fall-flying/spin-attack 0.6F × 0.6F,
  dying fixed 0.2F × 0.2F; sleeping uses `SLEEPING_DIMENSIONS` fixed 0.2F × 0.2F (`LivingEntity.java:196, 3762-3764`).
  Non-fixed dimensions are scaled by `getScale()` (the `SCALE` attribute, `LivingEntity.java:572-575`).
- Dimensions change **at packet time**: `SetEntityData` with `DATA_POSE` → `onSyncedDataUpdated` →
  `refreshDimensions()` → `reapplyPosition()` → new box (`Entity.java:3520-3524, 3534-3550`);
  `UpdateAttributes` with `SCALE` → `refreshDimensions()` (`LivingEntity.java:1156-1157`). Remote players never
  compute their own pose (`RemotePlayer.updatePlayerPose()` is empty, `RemotePlayer.java:77-79`).

**Timing rule.** For V's client tick n: the box of T used by the attack ray is
`dims(pose_n, scale_n).makeBoundingBox(pos_{n−1})`, where `pos_{n−1}` is T's position after the
`interpolate()` of tick n−1 (or a snap applied by a packet drained before tick n), and `pose_n`, `scale_n` are
the last values from packets drained before tick n.

---

## 3. Packets: 26.3 wire formats and how each feeds the interpolator

### 3.1 Position codec (both versions)

`c3/network/protocol/game/VecDeltaCodec.java`: `encode(d) = Math.round(d × 4096)`, `decode(v) = v / 4096.0`
(`:14-21`). Server: `xa = encode(p.x) − encode(base.x)` (`:78-88`), too big if outside
`[−32768, 32767]` (`:27-29`, i.e. ±7.9998 blocks). Client `decode(xa, ya, za)` (`:31-40`):

```
if xa == ya == za == 0: return base                       // same Vec3 instance
x = xa == 0 ? base.x : (encode(base.x) + xa) / 4096.0     // a zero axis keeps the base's exact double
(same for y, z)
```

So a moving axis lands on the 1/4096 grid; a still axis keeps whatever exact value the base had (spawn and
`EntityPositionSync` carry full doubles).

### 3.2 `VecDelta` (new in 26.3, `c3/network/protocol/game/VecDelta.java`)

- `Linear(short xa, ya, za)`, `stepCount() = 0` (`:125-146`): wire `xa ya za` as 3 × `short`.
- `Stepped(List<DeltaStep(short xa, ya, za, int ticks)>)`, `stepCount() = n` (`:148-226`): wire, per step,
  `VarInt ticks, short xa, short ya, short za` (`:110-115`; reading `:36-46`). Each step's delta is relative
  to the **previous step's decoded position**, the first to the codec base (`decode` `:179-195`,
  `tryEncode` `:197-222`). Decoder rejects `stepCount > readableBytes/7` (`:32-35`).
- An empty step list cannot be expressed (`stepCount 0` means `Linear`).

### 3.3 `ClientboundMoveEntityPacket`

| | 26.2 (`c2/.../ClientboundMoveEntityPacket.java`) | 26.3 (`c3/.../ClientboundMoveEntityPacket.java`) |
|---|---|---|
| `Pos` | `VarInt id, short xa, ya, za, bool onGround` (`:103-118`) | `VarInt id, VarInt properties, VecDelta` (`:99-110`) |
| `PosRot` | `VarInt id, short xa, ya, za, byte yRot, byte xRot, bool onGround` (`:135-154`) | `VarInt id, VarInt properties, VecDelta, byte yRot, byte xRot` (`:127-142`) |
| `Rot` | `VarInt id, byte yRot, byte xRot, bool onGround` (`:171-184`) | `VarInt id, bool onGround, byte yRot, byte xRot` (**order changed**, `:159-172`) |

`properties = (onGround ? 1 : 0) | stepCount << 1` (`:23-33`); `VecDelta` is 3 shorts when `stepCount == 0`,
else `stepCount × (VarInt ticks, short, short, short)`. Angles: `packDegrees(a) = (byte)floor(a × 256/360)`,
`unpackDegrees(b) = b × 360 / 256.0F` (`c3/util/Mth.java:182-188`).

Client (`c3/client/multiplayer/ClientPacketListener.java:728-755`), for a non-authoritative entity:

```
if hasPosition:
    path := delta.decode(entity.positionCodec); positionCodec.base := path.end
    hasRotation ? moveOrInterpolateTo(path, yRot, xRot) : moveOrInterpolateTo(path)       // pos only: rotation kept from target/entity
elif hasRotation: moveOrInterpolateTo(yRot, xRot)                                         // path = current target position, N ticks
entity.setOnGround(onGround)
```

Feeding: `Linear` → **one step of N ticks**. `Stepped` → the listed steps with their own durations, unless
the end equals the old target position (then one step of N). `Rot` → one N-tick step that holds position
(unless identical to the active target → ignored).

### 3.4 `ClientboundEntityPositionSyncPacket`

| 26.2 | `VarInt id, PositionMoveRotation(Vec3 pos, Vec3 delta, float yRot, float xRot), bool onGround` (`c2/.../ClientboundEntityPositionSyncPacket.java:11-20`) |
|---|---|
| 26.3 | `VarInt id, PositionPath, float yRot, float xRot, bool onGround` (`c3/.../ClientboundEntityPositionSyncPacket.java:10-24`) |

`PositionPath` (`c3/world/entity/PositionPath.java`): `VarInt type` (0 `LINEAR`, 1 `STEPPED`, `:51-56`), then
`Linear`: `Vec3` (3 × double); `Stepped`: `VarInt count` + count × (`Vec3` 3 × double, `VarInt tickOffset`)
(`PositionStep.java:8-11`, `ByteBufCodecs.collection`). Steps are absolute full-precision positions.
**Never send a `Stepped` with zero steps**: the client's `Stepped(List)` constructor calls `steps.getLast()`
(`PositionPath.java:41-43`) and will throw. No velocity field any more; 26.2's was ignored by the client
anyway.

Client (`ClientPacketListener.java:649-674`): `positionCodec.base := path.end`; if not locally
authoritative: if ticking and `distanceToSqr(entity.position, end) ≤ 4096` → `moveOrInterpolateTo(path, yRot, xRot)`,
else `snapTo(end, yRot, xRot)`; `setOnGround`. Feeds the queue exactly like a move packet (Linear → N ticks,
Stepped → listed durations), with exact floats instead of bytes for rotation.

### 3.5 `ClientboundTeleportEntityPacket` (unchanged 26.2 → 26.3)

`VarInt id, PositionMoveRotation (Vec3 pos, Vec3 delta, float yRot, float xRot), int relatives bitset, bool onGround`
(`c3/.../ClientboundTeleportEntityPacket.java:12-24`, `PositionMoveRotation.java:12-23`, `Relative.java:24`).
Client (`ClientPacketListener.java:677-698`, `setValuesFromPositionPacket` `:809-829`):
`current = PositionMoveRotation.of(entity)` (uses the **interpolation target** if active,
`PositionMoveRotation.java:25-28`, `Entity.java:4081-4083`), `new = calculateAbsolute(current, change, relatives)`;
remote entities always take the interpolate branch unless the move is > 64 blocks → `moveOrInterpolateTo(new.pos, new.yRot, new.xRot)`
(**one Linear step of N ticks**) and `setDeltaMovement`; otherwise `setPos`, `setDeltaMovement`, rotations and
`setOldPosAndRot` directly. **It does not touch the position codec base.** Vanilla never sends it to the
trackers of a player (only to riders), because players have an update interval and are not
`SERVER_AND_CLIENT` simulated (`Entity.java:3270-3293`).

### 3.6 `ClientboundSetEntityMotionPacket` (unchanged)

`VarInt id, LpVec3` (`ClientboundSetEntityMotionPacket.java:11-18`). `LpVec3` (`c3/network/LpVec3.java`):
one byte 0 for |v|<3.05e−5, else 6 bytes holding 2 scale bits + continuation flag + three 15-bit values
`round((v/scale × 0.5 + 0.5) × 32766)`, with a VarInt for the rest of the scale when `ceil(max|v|) > 3`.
For other entities the client calls `lerpMotion` (`ClientPacketListener.java:631-637`); for `RemotePlayer`
that lerps `deltaMovement` over 3 ticks and **does not move it** (2.4). Irrelevant to the hitbox.

### 3.7 Others

- `ClientboundRotateHeadPacket`: `VarInt id, byte yHeadRot` → `lerpHeadTo(yaw, 3)` (`ClientPacketListener.java:768-774`),
  a 3-tick head chase in `aiStep`. Not part of the box.
- `ClientboundAddEntityPacket`: full-double position; client `LivingEntity.recreateFromPacket` → `syncPacketPositionCodec(x,y,z)`
  and `absSnapTo` (x, z clamped to ±3e7) (`LivingEntity.java:4016-4030`, `Entity.java:1843-1850`);
  `RemotePlayer.recreateFromPacket` adds `setOldPosAndRot` (`RemotePlayer.java:82-85`). Fresh handler
  (empty queue, c = 0, R = 0, s = 1).
- `ClientboundBundlePacket`: up to 4096 sub-packets (`c3/network/protocol/BundlerInfo.java:12`), delivered as
  one queue entry, handled in one go (`ClientPacketListener.java:2468-2474`). `ClientboundPingPacket` is a
  valid sub-packet; `handlePing` writes the pong immediately on the main thread
  (`c3/client/multiplayer/ClientCommonPacketListenerImpl.java:154-157`).

---

## 4. Server side

### 4.1 Vanilla 26.3 `ServerEntity.sendChanges()` (`c3/server/level/ServerEntity.java:99-216`)

Called once per tick per tracked entity from `ChunkMap.tick()` (`ChunkMap.java:1199-1232`) when the entity's
section changed, `needsSync` is set, or it is in entity-ticking range. `ChunkMap.tick()` runs from
`ServerChunkCache.tick` (`ServerChunkCache.java:319-335`) inside `ServerLevel.tick` at `"chunkSource"`
(`ServerLevel.java:407-408`), **before** the `"entities"` section (`:426-450`). For a `ServerPlayer`, the
position it reads is whatever movement packets set before the tick began.

```
updateDataBeforeSync; passengers changed -> SetPassengers to trackers                  (:100-105)
item frames: maps every 10 ticks                                                       (:107-124)
currentPosition := entity.trackingPosition()                    // = position for players (Entity.java:3874)
interpolationTracker.updateTracking(currentPosition)           // 4.2
if entity.syncPosition: tickCount := updateInterval.nextInterval(tickCount); syncPosition := false   (:128-131)
   // DEAD for LivingEntity: the stepped tracker already cleared syncPosition in updateTracking (SteppedInterpolationTracker.java:42-45)
if needsSync || updateInterval.test(tickCount) || entityData.isDirty():                 (:133)
    yRotn, xRotn := packDegrees(yRot), packDegrees(xRot)
    shouldSendRotation := |yRotn − lastSentYRot| >= 1 || |xRotn − lastSentXRot| >= 1    // byte units (TOLERANCE_LEVEL_ROTATION = 1)
    if passenger: [Rot if changed]; tracker.clear(); base := currentPosition; sendDirtyEntityData; wasRiding := true
    elif new-behaviour minecart: handleMinecartPosRot
    else:
        teleportDelay++
        path := interpolationTracker.getPositionPath(currentPosition); interpolationTracker.clear()
        if arrow: packet := createMovePacket(path, …, true, true)
        else:
            positionChanged := |currentPosition − base|² >= 7.62939453125E-6
            packet := createMovePacket(path, yRotn, xRotn, positionChanged || tickCount % 60 == 0, shouldSendRotation)
        if needsSync || trackDelta || fallFlying:
            if |Δmovement|² > 1e-7 || (Δ>0 && movement == 0): SetEntityMotion(lastSentMovement := movement) to trackers
        if packet: send to trackers; sendDirtyEntityData; if hasPosition: base := currentPosition; if hasRotation: lastSent rot := …
        else sendDirtyEntityData
        wasRiding := false
    if |packDegrees(yHeadRot) − lastSentYHeadRot| >= 1: RotateHead                         (:202-206)
    needsSync := false
tickCount++
if syncVelocity: syncVelocity := false; SetEntityMotion(entity) to trackers AND self     (:211-215)

createMovePacket(path, y, x, sendPos, sendRot):                                          (:218-238)
    if requiresPrecisePosition || teleportDelay > 400 || wasRiding || wasOnGround != onGround:
        wasOnGround := onGround; teleportDelay := 0; return EntityPositionSync.of(entity, path)   // float rot from entity
    if sendPos:
        d := positionCodec.tryEncode(path)            // per-step deltas; null if any > ±32767 units
        if d == null || (item entity collision precision rule, :240-258): return EntityPositionSync.of(entity, path)
        return sendRot ? PosRot(id, d, y, x, onGround) : Pos(id, d, onGround)
    return sendRot ? Rot(id, y, x, onGround) : null
```

Forced syncs: on-ground flips, every 400 sends, after riding, precise-position entities (falling blocks/TNT on
collision, still happy ghasts), deltas > 8 blocks. Forced position resend every 60 ticks (`FORCED_POS_UPDATE_PERIOD`).
`needsSync` is set by `push` (`Entity.java:1978-1983`), `LivingEntity.knockback` (`:1662`), jumping/sprint
jump (`:2395`), sleeping (`:3821`), projectiles etc. `syncVelocity` by `markHurt` (`Entity.java:1985-1987`).

`UpdateInterval.periodic(p)`: `test(t) = t % p == 0`, `nextInterval(t) = t/p×p + p`
(`c3/world/entity/UpdateInterval.java:20-31`), built in `ChunkMap.java:1162-1163` from
`type.updateInterval()` and `type.trackDeltas()`.

### 4.2 `SteppedInterpolationTracker` (server half, `c3/world/entity/SteppedInterpolationTracker.java`)

```
trackedSteps = []; ticksSinceLastStep = -1; predictedDelta = 0
applyPredictedMovement(d): predictedDelta += d                       // pistons etc. (Entity.recordMovement)
updateTracking(pos):                                                  // every sendChanges
    if |predictedDelta|² > 1e-5: shift every tracked step by predictedDelta
    predictedDelta := 0; ticksSinceLastStep++
    if entity.syncPosition: syncPosition := false; addStep(pos)        // bounce on slime/bed
getPositionPath(pos): addStep(pos); return trackedSteps empty ? Linear(pos) : Stepped(copy)
addStep(pos): if ticksSinceLastStep > 0: trackedSteps.add(PositionStep(pos, ticksSinceLastStep)); ticksSinceLastStep := 0
clear(): trackedSteps.clear(); ticksSinceLastStep := 0                  // called on every send branch, sent or not
```

So the duration of each step is **server ticks since the previous step**, not a prediction of when the next
packet comes. For a player: a send every 2 ticks gives `[(pos, 2)]`; a send forced on an odd tick
(`needsSync` from knockback, entity data dirty from health/flags/pose) gives `[(pos, 1)]`; a bounce mid-interval
gives `[(bouncePos, k), (pos, 2−k)]`.

### 4.3 What changed from 26.2 (`diff c2/... c3/.../ServerEntity.java`)

26.2 sent the same schedule with plain deltas: `Pos`/`PosRot`/`Rot`, `EntityPositionSync.of(entity)` (with
velocity) on the same forcing conditions plus `deltaTooBig`; `hurtMarked` instead of `syncVelocity`;
`int updateInterval` arithmetic instead of `UpdateInterval`; `trackDeltas()` was a hard-coded type list
including `PLAYER` (`c2/world/entity/EntityType.java:446-457`); passenger packets were filtered per player.
Client side the step semantics did not exist: every target was a 3-tick chase.

### 4.4 Paper, Leaf and RevivalPaper changes (patched tree)

`lp/server/level/ServerEntity.java` (37 KB) keeps vanilla logic with these changes:

| Change | Where | Effect |
|---|---|---|
| Paper "optimise entity tracker" | `lp/server/level/ChunkMap.java:1109-1130, 1132-1144` (`newTrackerTick`) | `sendChanges()` every tick for each tracked entity that has nearby players or is in an `ENTITY_TICKING` chunk |
| Paper "fix desync when a player is added to the tracker" (`.gradle/caches/paperweight/upstreams/paper/paper-server/patches/features/0033-…patch`) | `ServerEntity.java:107-112, 160, 183-193, 284`; `ChunkMap.java:1531, 1678` | every new viewer sets `forceStateResync`; the next `sendChanges` sends a full `EntityPositionSync` (with the current tracked path) **to all viewers**; spawn packets use `entity.trackingPosition()` instead of the codec base |
| Paper hard-colliding entities | `ServerEntity.java:116-118` | `teleportDelay = 9999` → sync every send |
| Paper "allow riding players", item-frame perf, Vec3-free distance | `:126-129, 133-151, 186-190` | none for players |
| Leaf `0148 Dont send useless entity packets` | `:196, 326-335`; config `reduce-entity-move-packets`, default **false** (`leaf/src/main/java/org/dreeam/leaf/config/modules/opt/ReduceUselessPackets.java:12`) | drops zero `Linear` moves without rotation |
| Canvas `0272 Filter ClientboundSetEntityMotionPacket` | `:214-222`; default false | |
| SparklyPaper `0101` skip `distanceToSqr` | `:200` | none |
| Leaf `0257 Multithreaded Tracker` | `:482-646` (`leaf$sendChanges`), `SteppedInterpolationTracker.java:48-63` (`leaf$updateTracking`), `Entity.java` `recordMovement`/bounce hooks; config `async.async-entity-tracker.enabled`, default **false**, pinned off in `docs/revival/leaf-defaults.md:74` | tracker runs on worker threads from a `TrackerInput` snapshot; `ServerPlayer` `syncVelocity` excluded there (`:616`). Same dead `syncPosition` branch |
| Leaf `0312 Use legacy tracker ticking` | `lp/server/level/ServerChunkCache.java:535`, `lp/server/level/ServerLevel.java:1161`; default false | if true, `chunkMap.tick()` runs **after** entity ticking (Paper PR 13094 / MC-297196) |
| RevivalPaper `1003-Consistent-knockback` (+ paper 1001) | `leaf/minecraft-patches/features/1003-Consistent-knockback.patch` | only the victim's own velocity packet; nothing in the viewer movement path |

No RevivalPaper patch touches entity interpolation or the tracker's movement output.

---

## 5. Why 26.3 stutters (MC-311974, MC-311976)

Bug texts (mojira.dev, read 2026-10-21): MC-311974 "The new entity interpolation method introduced in 26.3
causes observable stutters compared to prior versions", Open, Confirmed, priority Important, assignee
FiskFille; MC-311976 (Z0rty) closed as its duplicate. Points raised there: interpolation length became
`updateInterval` (2 for players) instead of 3; the client often finishes before new data arrives; vanilla
servers send 1-2-tick segments at movement start; `currentStepTicks` starts at 1.0F; the last step "can
remain"; steps' `tickOffset` should predict the next packet rather than measure the last one.

Below, each mechanism is tied to the code and measured with the verified port.

### 5.1 M1 — Zero buffer (the main cause)

Vanilla feed for a moving player: a 2-tick step every 2 ticks. Trace (port, constant speed, packets on time,
state printed after the tick):

```
tick 15: packet [(x_12, 2)] arrives, queue empty -> start, c=1   shows x_11 (half of the step)   c=2 R=1 q=1
tick 16: (no packet)                                             shows x_12 (end), step removed   c=1 R=0 q=0
tick 17: packet [(x_14, 2)], queue empty -> start, c=1           shows x_13                       c=2 R=1 q=1
tick 18:                                                         shows x_14                       c=1 R=0 q=0
```

Smooth, delay = arrival + 1 tick, but the queue is **exactly empty** whenever the next packet is due. If that
packet is drained one tick late (network jitter, server tick jitter, or two client ticks after one drain at
low FPS), `interpolate()` finds the queue empty → `cancel()` → the entity does not move that tick
(`AbstractInterpolationHandler.java:57-58`). The next tick both packets are queued and the catch-up controller
runs it at up to ×1.25. That is the visible hitch.

### 5.2 M2 — `currentStepTicks = 1.0F` on every restart

`setStartingPoint` (`SteppedInterpolationHandler.java:110-113`) puts the first rendered tick one path-tick into
the new path (P1). With a 2-tick step the first tick jumps half a step; with a 1-tick step it jumps the whole
step (no interpolation at all). Under M1, restarts happen every 2 ticks, so this is what makes a late packet
look like *stop, then lurch*. MC-311976 proposed starting at 0 and advancing before sampling; a server cannot
change it, it can only avoid restarts (6.3).

### 5.3 M3 — Short first steps

Step durations are server ticks since the previous step (4.2). Anything that forces an off-interval send
(knockback `needsSync`, hurt/flag/pose data making `entityData.isDirty()`, a bounce) produces a `[(pos, 1)]`
step, and the next interval send another 1-tick step. From rest, M2 then shows a 1-tick step instantly: the
first movement after a hit is a snap, which is the "up to 2 ticks of jitter when a player hits a stationary
entity" in MC-311976. These 1-tick durations also **override N** (only `Linear`/`Rot`/same-end packets use N),
which is why raising N alone (the `+1` proposal in MC-311974) does not help against a vanilla server.

### 5.4 M4 — "Last step not removed"

`advance` clamps the last consumption to `R` (`:74-76`); `getNewPositionAndRotation` removes a step only when
`c ≥ tickOffset` (`:184-195`). Two cases:

- `R⁻ ≤ 1` (always the case at speed 1, e.g. the vanilla trace in 5.1): by P1 the sample is already at or past
  the end, so the last step is removed and the end shown in that same tick; the next tick cancels.
- `1 < R⁻ ≤ s'` (only while catching up, s > 1): the sample is still inside the last step, the clamp sets
  `R = 0` with that step still queued, and because of P1 `c` now sits one tick past it. The **next**
  `interpolate()` removes it, shows the end exactly and consumes 0; the tick after cancels. So
  `hasActiveInterpolation()` stays true one extra tick with `R = 0`; it is not permanent. Measured: 40 million random ticks of the port
(1-3 packets per tick, 1-3 steps each, offsets 1-40, `Linear` mixed in) never produced a state with `R = 0`,
queue non-empty and `c < first.tickOffset`; the margin `c − Σ(remaining offsets)` at `R = 0` never fell below
0.9999008. A permanently stuck step (Z0rty's "2.99995 against 3") needs a code path RemotePlayer does not
take (for example a changed `getRelativeTickSpeed()`), or a modified client. The one-tick tail still matters
for exact mirroring and is reproduced by appendix A.

### 5.5 M5 — Catch-up overshoot

When packets bunch, `R⁻ > N` drives `ts = R/N > 1` and s rises with gain `1/N` (`advance`). With a vanilla
feed the excess is drained within a few ticks, often below zero buffer, so one late packet can cause a second
starvation (M1) a few ticks later.

### 5.6 M6 — Path-time pollution

`Rot` packets, `Linear` moves, teleports, syncs with a `Linear` path and any path whose end equals the current
target position add **N** path-ticks no matter how much real time they represent (`:33-36`, `:125-127`).
Vanilla sends `Rot` on odd ticks whenever data is dirty and rotation changed, so a player turning in place adds
2 path-ticks per real tick and the controller speeds up.

### 5.7 M7 — Steps measure the past

`tickOffset` = time since the last step. With send period = step length = N the client always finishes
exactly at the next due time; nothing in the feed reserves a margin. (Z0rty: "should be a prediction of the
next packet's timing, possibly plus one tick.")

### 5.8 Measurements (port)

Discrete model: server tick t sends T's position at t, packets arrive before client tick t+3, each packet late
by +1 tick with probability p, order kept. Delay = client tick − server tick of the position on screen.

| Feed | p = 0: delay | p = 0: freeze ticks | p = 0.3: delay min..max | p = 0.3: freeze ticks / 119 | speed range |
|---|---|---|---|---|---|
| vanilla 26.3 (2-tick step every 2 ticks) | 4.000 | 0 | 4..5 | 4 | ×0.0 .. ×1.25 |
| 26.2 (Pos every 2 ticks, 3-step lerp, 26.2 port) | 4.67..5.00 (5 steady) | 0 | 4.67..5.67 | 0 | ×0.67 .. ×1.11 |
| K1: 1-tick step every tick | 3.000 | 0 | 3..4 | 1 | ×0.0 .. ×1.0 |
| K2: 2-tick step every tick | 4.0..4.68 (4.5 steady) | 0 | 4..5.15 | 0 | ×0.5 .. ×1.19 |

Continuous model (40 runs each): T's ticks → server (40 ms + |N(0, σ)|), server tick at t×50 ms with one step
per T-tick, server → V (40 ms + |N(0, σ)|), V at the stated FPS (packets drained per frame, ticks at
n×50 ms + phase). Freeze ticks per 1000 ticks of motion:

| FPS | σ | vanilla 26.3 | K1 | K2 |
|---|---|---|---|---|
| 240 | 0 ms | 0.0 | 0.0 | 0.0 |
| 240 | 5 ms | 3.9 | 0.4 | 0.0 |
| 240 | 15 ms | 12.7 | 12.1 | 0.0 |
| 240 | 30 ms | 33.8 | 38.4 | 1.1 |
| 60 | 15 ms | 13.1 | 12.2 | 0.0 |
| 60 | 30 ms | 34.3 | 38.3 | 1.2 |
| 25 | 0 ms | 0.4 | 1.1 | 0.0 |
| 25 | 15 ms | 19.5 | 18.0 | 0.0 |

Average total delay (T's tick → V's screen, ticks, FPS 60): σ 0: vanilla 3.45, K1 2.44, K2 3.87;
σ 15 ms: vanilla 3.90, K1 3.56, K2 4.28.

---

## 6. Proposal: server-side replication that a vanilla 26.3 client renders smoothly

### 6.1 Rules

For every target player T and viewer V (V ≠ T) that tracks T:

- **R1 One step per authoritative T-tick.** The source is T's simulated state at the end of each of T's client
  ticks (the authoritative simulation), not the server tick. If two T-ticks were simulated in one server tick,
  send two steps; if none, send none.
- **R2 Every step lasts K = 2 ticks** (`tickOffset = 2`), always. Never emit 1-tick steps, never use the
  vanilla `SteppedInterpolationTracker` durations for players.
- **R3 One packet per T-tick.** `ClientboundMoveEntityPacket.PosRot` with a one-step `VecDelta.Stepped`
  (`properties = onGround | 1 << 1`) carrying that tick's rotation bytes. One packet per step keeps every
  step's rotation exact (several steps in one packet get lerped rotations, 2.1). Use
  `EntityPositionSync` with a one-step `Stepped` path when a delta exceeds ±32767 units (8 blocks).
- **R4 Keep the queue alive.** If T's tick produces the same client-decoded end position *and* the same
  rotation bytes as the last one sent (T perfectly still), the client would ignore it
  (`target.is(...)`, `AbstractInterpolationHandler.java:42`) and its queue would drain. Instead toggle the x
  delta by one unit (1/4096 block = 0.24 mm) on alternate still ticks so the end differs. If only rotation
  changed, nothing special is needed: an equal end collapses to one step of **N = 2** ticks
  (`SteppedInterpolationHandler.java:33-34`), the same as K.
- **R5 Send every server tick** (no `updateInterval` gating), in one bundle per viewer per tick:
  `BundleDelimiter, [T moves …, T entity data/attributes …] for all targets, Ping(id), BundleDelimiter`.
  The bundle is applied atomically; its pong fixes the client tick exactly (7.1).
- **R6 Spawn and big jumps.** On pairing, record the exact `AddEntity` position. For a jump V would snap
  (> 64 blocks from V's current *display*, which the mirror knows), re-spawn T on V (`RemoveEntities` +
  `AddEntity`) instead of relying on `snapTo`, so no queue is translated by
  `adjustInterpolationTargetFromDeltas` (2.4).
- **R7 Suppress the vanilla movement path for player targets**: in `lp/server/level/ServerEntity.java`
  `sendChanges` (`:153-243`) and `leaf$sendChanges` (`:519-603`), the tracker steps, `createMovePacket`,
  `Rot`, forced syncs, and `forceStateResync` syncs (`:108-112, :160`) must not reach V for a `ServerPlayer`.
  Equipment, passengers, head rotation and entity data stay, but entity data for T should go through R5's bundle.
  Keep Leaf's async tracker off.
- **R8 (optional) Pose alignment.** Pose and scale act at packet time (2.5) while the position plays
  1.5 ticks later. The server can hold a pose change until the tick in which V's display (mirror) reaches the
  T-tick that changed pose. Exactness does not depend on it.

### 6.2 Why K = 2: the client controller's equilibrium

Feed K path-ticks per real tick. With s and `R⁻` (backlog after packets, before `advance`):

```
s'     = s + (max(R⁻/2, 1) − s) / 2
R⁻next = R⁻ − s' + K
```

For K ≥ 2 the fixed point is **s = K, R⁻ = 2K**. Linearised around it (e_R, e_s):
`e_s' = e_s/2 + e_R/4`, `e_R' = 3e_R/4 − e_s/2`; matrix `[[3/4, −1/2], [1/4, 1/2]]`, trace 5/4, det 1/2,
eigenvalues `5/8 ± i√7/8`, `|λ| = √½ ≈ 0.707`: errors decay ~29 % per tick, independent of K.

- Display lag behind the newest delivered end = `R⁻ − 1` path-ticks (P1) = `(2K − 1)/K` T-ticks:
  K = 2 → **1.5**, K = 4 → 1.75, K = 8 → 1.875, never 2 or more. The vanilla controller cannot hold more
  than N = 2 ticks of buffer.
- At K = 2 the steady display converges to the midpoint of T-ticks j−2 and j−1 (j = newest delivered): s → 2,
  c advances 2 per tick, and P1 puts the sample at a → 0.5 of a 2-tick step (traced: a = 0.5002 after 20
  ticks; the float fixed point need not be exactly 0.5, which is why the mirror replays rather than assumes
  it). The controller fixes `R⁻ = 4`, so this half-tick phase cannot be shifted by the server.
- K = 1 has no fixed point: with `R⁻ ≤ 2` the controller is inactive (P4); the backlog stays wherever jitter
  left it (0 or 1 tick) and s cannot go below 1, so starvation can only freeze.
- K = N = 2 is the only K for which every packet kind (`Rot`, `Linear`, same-end, teleport, sync with a
  `Linear` path) adds the same path time as a normal step. Any K ≥ 2 has the same tolerance (below), so the
  smallest, 2, has the least delay.

Tolerance to consecutive ticks with no delivery (port; per-tick displayed speed, ×1 = true speed):

| Feed | 1 silent tick | 2 silent ticks | 3 silent ticks |
|---|---|---|---|
| K1, no buffer | freeze 1 tick | freeze 2 | freeze 3 |
| K1 with a 1-tick pre-roll (buffer 1) | no freeze | freeze 1 | freeze 2 |
| **K2** | ×0.75, ×0.94, ×1.05, ×1.09 … | ×0.5, ×0.5, then ×1.0-1.31 catch-up | ×0.5, **freeze 1**, ×0.5 … |
| K4 | ×0.75, ×0.94 … | ×0.75, ×0.25 … | ×0.75, freeze 1, ×0.25 … |

### 6.3 Proof against each stutter mechanism

Notation as in 2.1. Steady state means T is tracked and the feed follows R1-R5.

- **M1 (zero buffer).** Every T-tick adds exactly 2 to R: under R3/R4 each packet reaches `addStep` either
  through `addSteps` with `tickOffset = 2` (`:36`, `:147-148` / `:158-161`) or through the same-end branch with
  N = 2 (`:33-34`); none is ignored, because R4 guarantees `!target.is(end, y, x)`. Hence `R⁻ → 4` (6.2) and
  after `advance` `R = 2`: two path-ticks are always queued beyond the displayed point at the start of the next
  tick. The queue empties only if `R⁻ ≤ 1` (P2). From equilibrium one silent tick gives
  `R⁻ = 2`, `s' = 1.5`, `R = 0.5`; a second gives `R⁻ = 0.5`, clamp, `R = 0` but the end is shown, no freeze;
  only a third silent tick reaches `cancel()`. A late packet therefore slows the display instead of freezing
  it until **two** whole ticks are missing.
- **M2 (c = 1 restart).** `setStartingPoint` runs only when the queue is empty (`:28-30`). By M1 that happens
  after ≥ 3 silent ticks or at spawn. With R4, T standing still does not drain it. Measured on a scripted
  run/stop/strafe/run (470 ticks, 60 runs): restarts per run, K2 with keep-alive = **1.0** (the spawn) at
  σ ≤ 5 ms, 3.3 at σ 15 ms; without keep-alive 3.0-6.9; K1 restarts every tick by construction.
- **M3 (short steps).** R2 forbids 1-tick steps; the first step after spawn is 2 ticks, so P1 shows half a
  step (speed ×0.5) instead of a snap, followed by the controller's ramp (×0.5, 0.5, 0.63, 0.78, 0.91, 1.0,
  1.05, 1.06, 1.05 …; worst position error 0.142 blocks against an ideal fixed-delay replay while it settles).
  A pre-roll hold step at the spawn position (`[(P0, 2), (P1, 2)]`) puts the P1 skip inside the hold, so the
  first rendered tick shows P0 rather than half a step.
- **M4 (tail).** With the queue never empty, `R = 0` is never reached in steady state (after-advance R = 2);
  the one-tick tail (5.4) occurs only after a drain and is reproduced exactly by the mirror.
- **M5 (overshoot).** The controller is damped (|λ| = 0.707). A one-tick-late packet perturbs the speed to
  ×0.75 then at most ×1.09; no oscillation into starvation (table 6.2; σ = 15 ms runs have 0 freezes).
- **M6 (time pollution).** All packets add 2 = N. A plugin-sent `Rot`/teleport for T also adds 2 and only
  shifts the delay by one T-tick for a few ticks.
- **M7.** R2 is precisely "duration = predicted time to the next update + one tick": a step is sent every
  tick but lasts two.
- **Low FPS / several ticks per frame** is the same as silent ticks; tolerated up to two in a row.
- **Clock drift.** If V's client ticks slightly faster or slower than T's stream, the K2 controller moves s a
  little around 2 and the backlog settles at a new constant; K1 would freeze periodically (slow source) or
  speed up and drain (fast source).

Fixed and known delay: in steady state the display is the T-state at **T-time (newest delivered − 1.5)**.
It is not constant during jitter recovery (that is the price of not freezing), but it is always *known*:
the server mirror (section 7) computes it exactly from the pongs.

### 6.4 Alternatives

| Mode | Steady delay after arrival | Freezes when | Notes |
|---|---|---|---|
| vanilla 26.3 | 1 tick | any late packet | M1-M7 all present |
| 26.2 client | 2 ticks | never (always chasing) | path not reproduced (low-pass) |
| **K2 + keep-alive (recommended)** | 1.5 ticks (midpoint) | ≥ 3 consecutive silent ticks | elastic, drift-adaptive, one restart per spawn |
| K1 (1-tick steps every tick) | 0 ticks | any late packet when the backlog is 0 | lowest latency, exact positions; a late packet then a double step; backlog drifts between 0 and 1 |
| K1 + 1-tick pre-roll | 1 tick | 2 consecutive silent ticks | backlog lost after any freeze (restart sets it to 0) unless the server re-pre-rolls, which costs a deliberate 1-tick hold |
| K ≥ 3 | 1.67-2 ticks | same as K2 | needs R4 also for rotation-only ticks; no gain |

K can be chosen per viewer (K1 for LAN-quality links, K2 otherwise); the mirror handles any mix.

### 6.5 Cost

One `PosRot` with one step: packet id 1 + entity id 1-3 + properties 1 + (ticks 1 + 6) + rotation 2 ≈ 12-14
bytes, plus frame. 20 visible players → about 300 B per tick → ~6 KB/s per viewer, plus one ping per viewer per
tick (5 bytes + 2 delimiters).

---

## 7. Exact reconstruction of T's box on V at V's client tick n

### 7.1 Timing model

- Number V's client ticks by its `ServerboundClientTickEndPacket`s: tick k ends with `TickEnd #k`
  (`Minecraft.java:1885`). Window `W_k = (TickEnd(k−1), TickEnd(k)]` in V's ordered serverbound stream
  (same convention as `02-client-netcode.md` section 6).
- A pong is written only during a drain, never inside a tick (`ClientCommonPacketListenerImpl.java:154-157`,
  1.1). If `Pong(id)` lands in `W_k`, the bundle containing `Ping(id)` was applied after tick k−1 and before
  tick k. Because the ping is **inside** the bundle (R5) and a bundle is applied atomically, every packet in it
  has **arrival(e) = k exactly**.
- V's attack in tick k is written during tick k, after that tick's drain, so it lands in `W_k` after every pong
  with arrival ≤ k. When the server reads an attack, it already knows every bundle V had applied before the
  ray was cast; bundles without a pong yet were not applied. The server must read V's stream in order
  (pong, attack and tick-end on one sequence).
- The attack in `W_n` used T's box per the timing rule of 2.5.

### 7.2 What to record per (V, T)

All packets the server wrote to V that change T on V's client, in write order, each with its arrival tick:
`AddEntity`, `RemoveEntities`, `MoveEntity` (Pos/PosRot/Rot), `EntityPositionSync`, `TeleportEntity`,
`SetEntityData` (pose), `UpdateAttributes` (scale), `SetPassengers` touching T, and V's own `Respawn`/level
change (clears all). Packets for T that bypass the R5 bundles have unknown arrival: either forbid them for player
targets or bracket them with pings (`02`, rules A/B) and mark the result inexact when the bracket spans a
tick boundary.

### 7.3 Pseudocode (mirrors the client; `Stepped` is appendix A, bit-exact)

```java
final class RemoteMirror {                      // V's client copy of T
    boolean present, passenger, exact = true;
    double bx, by, bz;                          // entity.getPositionCodec().getBase()
    Pose pose = Pose.STANDING; float scale = 1.0F;
    Stepped q;                                  // appendix A; owns ex/ey/ez/eyRot/exRot (entity position/rotation)
}

// ---- codec (VecDeltaCodec.decode, VecDelta.Stepped.decode) ----
static long enc(double d) { return Math.round(d * 4096.0); }
static double[] dec(double[] base, short xa, short ya, short za) {
    if (xa == 0 && ya == 0 && za == 0) return base;
    return new double[]{ xa == 0 ? base[0] : (enc(base[0]) + xa) / 4096.0,
                         ya == 0 ? base[1] : (enc(base[1]) + ya) / 4096.0,
                         za == 0 ? base[2] : (enc(base[2]) + za) / 4096.0 };
}
static Path decode(double[] base, VecDelta d) {
    if (d.stepCount() == 0) return Path.linear(dec(base, d.xa, d.ya, d.za));
    List<Step> out = new ArrayList<>(); double[] b = base;
    for (DeltaStep s : d.steps) { double[] p = dec(b, s.xa, s.ya, s.za); out.add(new Step(p, s.ticks)); b = p; }
    return Path.stepped(out);                   // end = last p
}

// ---- packet application (runs at arrival time, i.e. before tick 'arrival') ----
void apply(RemoteMirror m, Packet p) {
    switch (p) {
      case AddEntity a -> {                     // LivingEntity.recreateFromPacket + RemotePlayer
          m.present = true; m.passenger = false; m.pose = STANDING; m.scale = 1.0F;
          m.bx = a.x; m.by = a.y; m.bz = a.z;   // codec base: unclamped doubles
          m.q = new Stepped(2, clamp(a.x, -3e7, 3e7), a.y, clamp(a.z, -3e7, 3e7));
          m.q.eyRot = unpack(a.yRot) % 360.0F; m.q.exRot = Math.clamp(unpack(a.xRot), -90.0F, 90.0F) % 360.0F;
      }
      case RemoveEntities r -> m.present = false;
      case MoveEntity mv -> {                   // ClientPacketListener.handleMoveEntity :738-752
          if (mv.hasPosition()) {
              Path path = decode(base(m), mv.delta()); setBase(m, path.end());
              if (mv.hasRotation()) m.q.interpolateTo(path, unpack(mv.yRot), unpack(mv.xRot), true);
              else                  m.q.interpolateTo(path, 0, 0, false);     // keeps target/entity rotation
          } else if (mv.hasRotation()) m.q.interpolateTo(null, unpack(mv.yRot), unpack(mv.xRot), true);
      }
      case EntityPositionSync s -> {            // :649-674
          setBase(m, s.path().end());
          if (dist2(m.q.pos(), s.path().end()) > 4096.0 /* or T not ticking on V */) {
              m.q.snapTo(s.path().end(), s.yRot(), s.xRot());     // queue NOT cleared
              if (m.q.hasActiveInterpolation()) m.exact = false;   // next interpolate translates the queue
                                                                   //   only if V's world has no collision there
          } else m.q.interpolateTo(s.path(), s.yRot(), s.xRot(), true);
      }
      case TeleportEntity t -> {                // :677-698, :809-829; codec base untouched
          PMR cur = new PMR(m.q.clientPosition(), m.q.clientYRot(), m.q.clientXRot());   // target if active
          PMR nv = PositionMoveRotation.calculateAbsolute(cur, t.change(), t.relatives());
          if (dist2(cur.pos, nv.pos) > 4096.0) { m.q.setPos(nv.pos); m.q.setRot(nv.yRot, nv.xRot); m.exact &= !m.q.hasActiveInterpolation(); }
          else m.q.interpolateTo(Path.linear(nv.pos), nv.yRot, nv.xRot, true);
      }
      case SetEntityData d when d.has(DATA_POSE) -> m.pose = d.get(DATA_POSE);
      case UpdateAttributes u when u.has(SCALE) -> m.scale = (float) u.value(SCALE);
      case SetPassengers sp -> m.passenger = sp.makesTPassenger();   // position then follows the vehicle: mark inexact unless mirrored
      default -> {}
    }
}

// ---- the query ----
// events sorted by (arrival, write order). Returns the box V's attack ray tested in tick n.
AABB boxAtPick(RemoteMirror m, List<Event> events, int firstTick, int n) {
    int i = 0;
    for (int k = firstTick; ; k++) {
        while (i < events.size() && events.get(i).arrival() == k) apply(m, events.get(i++).packet());
        if (k == n) break;                                  // pick(1.0F) precedes tickEntities
        if (m.present && !m.passenger) m.q.interpolate(1.0F);   // Entity.commonTick, getRelativeTickSpeed()=1
    }
    if (!m.present) return null;
    EntityDimensions d = (m.pose == Pose.SLEEPING) ? fixed(0.2F, 0.2F)
                       : Avatar.POSES.getOrDefault(m.pose, STANDING).scale(m.scale);   // fixed dims ignore scale
    float w = d.width() / 2.0F, h = d.height();
    return new AABB(m.q.ex - w, m.q.ey, m.q.ez - w, m.q.ex + w, m.q.ey + h, m.q.ez + w);
}
```

Incremental form (what the server should actually run): keep one mirror per (V, T). When `TickEnd(k)` arrives
from V, apply every event with arrival k (all their pongs are already in), store
`boxAtPick(k)` = box from the current position and dims, then run `interpolate()` for tick k. When an attack
arrives in `W_n`, apply the events with arrival n received so far (all of them, 7.1) and read the box. Keep a
short ring buffer of `(tick, box)` for diagnostics.

`Stepped.interpolateTo(path, y, x, hasRotation=false)` must use the current target's rotation when active, else
the entity's (`AbstractInterpolationHandler.java:31-33`); appendix A implements the `hasRotation = true` case
and the rotation-only case; add the false case the same way.

### 7.4 Exactness conditions and gaps

Exact when: T is a non-riding `RemotePlayer` on V; every packet affecting T is in a pinged bundle; no snap
happens while V's queue is active (R6); V's tick rate is 20 (`getRelativeTickSpeed() = 1.0F`) and not frozen
(`ClientLevel.tickEntities` skips frozen entities, `ClientLevel.java:454-460`); T is in a chunk V ticks;
no pistons/shulkers push T on V. Otherwise mark the sample inexact and fall back to a tolerance.

Not covered by the box: rotation (irrelevant to an `AABB`), head yaw, delta movement.

Rotation fidelity, if ever needed: the port tracks rotations bit-exactly (verified), including `rotLerp`
wrap-around (a target of 170° and a packet of −170° stores 190°).

### 7.5 How to test the mirror

1. Unit: run appendix B against `client-26.3.jar` in CI (reflection on the real `InterpolationData`).
2. Integration: a headless or debug client that logs, at each `pick(1.0F)`, the tick count and every remote
   player's `getBoundingBox()`; replay the server's recorded events and compare bit for bit.
3. Fault injection: delay bundles, run the client at 15 FPS, drop to 0 FPS for 200 ms, and check both
   freezes (expected per 6.2) and mirror equality.

---

## 8. Hook points in RevivalPaper (for the implementers)

- Movement output for player targets: `lp/server/level/ServerEntity.java:153-243` (main) and `:519-603` (Leaf
  async; keep `async-entity-tracker.enabled: false`). Branch on `entity instanceof ServerPlayer`.
- New viewers: `lp/server/level/ChunkMap.java` `TrackedEntity.updatePlayer` (`:1531`, `:1678` call
  `onPlayerAdd`) and `ServerEntity.addPairing`/`sendPairingData` (`:373-434`; the pairing is already a bundle,
  so append the ping inside it and record the spawn position).
- Tracker timing: `ServerChunkCache.java:535` / `ServerLevel.java:1161` (`use-legacy-tracker-ticking`). The
  replicator should run after the authoritative simulation of the tick's T-ticks, wherever that ends up.
- Teleports of players do not go to trackers (`c3/world/entity/Entity.java:3286-3293`); the replicator sees
  them as a jump and applies R6.

---

## Appendix A — client port (bit-exact with `client-26.3.jar`)

Position, rotation, queue and controller for one remote living entity with no external moves. This exact
listing was extracted from this document, compiled, and run through appendix B: 400,000 ticks, all fields
bit-identical with `client-26.3.jar`. `interpolateTo(stepped, linearEnd, y, x)`: pass
`stepped` for a `Stepped` path, `linearEnd` for a `Linear` one, both null for rotation-only.

```java
final class Stepped {
    static final class Step { double x, y, z; float yRot, xRot; final int tickOffset;
        Step(double x, double y, double z, float yRot, float xRot, int t) { this.x=x; this.y=y; this.z=z; this.yRot=yRot; this.xRot=xRot; tickOffset=t; } }
    record PathStep(double x, double y, double z, int tickOffset) {}

    final int N;                                   // type.updateInterval(): 2 for PLAYER
    double ex, ey, ez; float eyRot, exRot;         // entity position/rotation (AABB = dims at (ex,ey,ez))
    double tx, ty, tz; float tyRot, txRot;         // InterpolationData = target
    final java.util.LinkedList<Step> remainingSteps = new java.util.LinkedList<>();
    double sx, sy, sz; float syRot, sxRot;         // lastStepPosRot
    float currentStepTicks, remainingTicks, interpolationSpeed = 1.0F;

    Stepped(int n, double x, double y, double z) { N = n; ex = x; ey = y; ez = z; }
    boolean hasActiveInterpolation() { return !remainingSteps.isEmpty(); }

    void interpolateTo(java.util.List<PathStep> stepped, double[] linearEnd, float yRot, float xRot) {
        boolean isLinear; double endX, endY, endZ;
        if (stepped == null && linearEnd == null) {                 // rotation only: path = current position
            if (hasActiveInterpolation()) { endX = tx; endY = ty; endZ = tz; } else { endX = ex; endY = ey; endZ = ez; }
            isLinear = true;
        } else if (stepped != null) { PathStep l = stepped.get(stepped.size() - 1); endX = l.x(); endY = l.y(); endZ = l.z(); isLinear = false; }
        else { endX = linearEnd[0]; endY = linearEnd[1]; endZ = linearEnd[2]; isLinear = true; }
        boolean same = tyRot == yRot && txRot == xRot
            && Double.compare(tx, endX) == 0 && Double.compare(ty, endY) == 0 && Double.compare(tz, endZ) == 0;
        if (hasActiveInterpolation() && same) return;               // ignored
        if (!hasActiveInterpolation()) { sx = ex; sy = ey; sz = ez; syRot = eyRot; sxRot = exRot; currentStepTicks = 1.0F; }
        boolean endEqualsTarget = Double.compare(endX, tx) == 0 && Double.compare(endY, ty) == 0 && Double.compare(endZ, tz) == 0;
        if (endEqualsTarget || isLinear) addStep(endX, endY, endZ, yRot, xRot, N);
        else if (yRot == tyRot && xRot == txRot) { for (PathStep s : stepped) addStep(s.x(), s.y(), s.z(), yRot, xRot, s.tickOffset()); }
        else {
            int total = 0; for (PathStep s : stepped) total += s.tickOffset();
            int off = 0;
            for (PathStep s : stepped) { off += s.tickOffset(); float a = (float) off / total;
                addStep(s.x(), s.y(), s.z(), rotLerp(a, tyRot, yRot), txRot + a * (xRot - txRot), s.tickOffset()); }
        }
        tx = endX; ty = endY; tz = endZ; tyRot = yRot; txRot = xRot;
    }

    private void addStep(double x, double y, double z, float yr, float xr, int off) {
        remainingSteps.add(new Step(x, y, z, yr, xr, off)); remainingTicks += off; }

    void interpolate(float tickSpeed) {
        if (!hasActiveInterpolation()) { remainingSteps.clear(); remainingTicks = 0.0F; interpolationSpeed = 1.0F; return; }
        double px = tx, py = ty, pz = tz; float pyRot = tyRot, pxRot = txRot;
        while (!remainingSteps.isEmpty()) {
            Step st = remainingSteps.getFirst(); int off = st.tickOffset;
            if (currentStepTicks < off) {
                float a = currentStepTicks / off;
                px = sx + (double) a * (st.x - sx); py = sy + (double) a * (st.y - sy); pz = sz + (double) a * (st.z - sz);
                pyRot = rotLerp(a, syRot, st.yRot); pxRot = sxRot + a * (st.xRot - sxRot);
                break;
            }
            currentStepTicks -= off;
            sx = st.x; sy = st.y; sz = st.z; syRot = st.yRot; sxRot = st.xRot;
            remainingSteps.removeFirst();
            if (remainingSteps.isEmpty()) { px = tx; py = ty; pz = tz; pyRot = tyRot; pxRot = txRot; }
        }
        ex = px; ey = py; ez = pz;                                    // setPos -> AABB
        eyRot = pyRot % 360.0F; exRot = Math.clamp((pxRot % 360.0F) % 360.0F, -90.0F, 90.0F);
        float targetSpeed = Math.max(remainingTicks / N, 1.0F);      // advance
        interpolationSpeed = interpolationSpeed + 1.0F / N * (targetSpeed - interpolationSpeed);
        float t = tickSpeed;
        if (t * interpolationSpeed < remainingTicks) t *= interpolationSpeed; else { t = remainingTicks; interpolationSpeed = 1.0F; }
        currentStepTicks += t; remainingTicks -= t;
    }

    static float wrapDegrees(float a) { float n = a % 360.0F; if (n >= 180.0F) n -= 360.0F; if (n < -180.0F) n += 360.0F; return n; }
    static float rotLerp(float a, float from, float to) { return from + a * wrapDegrees(to - from); }
}
```

(The verified file also models `lastPositionAndRotation`; with no external moves it never changes the result,
so it is left out here. Add `adjustInterpolationTargetFromDeltas` if snaps or pushes are to be mirrored.)

## Appendix B — verification harness

`Verify.java` (kept with the port under the research scratchpad; re-create from this description): loads
`net.minecraft.world.entity.SteppedInterpolationHandler$InterpolationData` from `client-26.3.jar` with
reflection (`advance(float,int)`, `reset()`, `setStartingPoint(Vec3,float,float)`, `addStep(Vec3,float,float,int)`,
`addSteps(PositionPath,float,float,int)`, `getNewPositionAndRotation()`, fields `remainingSteps`,
`currentStepTicks`, `remainingTicks`, `interpolationSpeed`), wraps it in the `AbstractInterpolationHandler`
logic above, and drives it and the port with the same random stream (2,000 trials × 200 ticks; per tick 0-3
packets of type rotation-only, `Linear` or `Stepped` with 1-3 steps of 1-20 ticks, random byte-quantized
rotations). Classpath: `client-26.3.jar` plus DataFixerUpper, Netty (buffer, common, codec), fastutil, Guava,
JOML, slf4j, jspecify, Gson, authlib from `~/.gradle/caches/modules-2/files-2.1`. Result:
`OK: 400000 ticks bit-identical`.
