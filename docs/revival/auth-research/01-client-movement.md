# 01 — Vanilla 26.3 local-player movement pipeline (spec for a server-side port)

Status: research, 2026-10-21. Source of truth: the decompiled **vanilla** 26.3 client jar.

All paths below are relative to `~/Developer/tools/mc-src/src-26.3/net/minecraft/` unless they start with
`leaf-server/` (= `~/Developer/GitHub/RevivalPaper/leaf-server/`). Line numbers are from those files.

Tags used throughout:

- **[CLIENT]**: class exists only in the client jar (`client/...`). A server port must re-implement it.
- **[COMMON]**: class is also in the server jar (`world/...`). The server has a copy, **but RevivalPaper's copy is
  patched by Paper/Purpur/Gale/Leaf and is not bit-identical to vanilla** (section 9). Port from the vanilla
  decompile, not from `leaf-server/src/minecraft/java`.

---

## 0. Summary of the findings that shape the design

1. One client tick = one `Minecraft.tick()` (`client/Minecraft.java:1795`). Server packets are applied only at
   **frame** start (`Minecraft.java:1202`), before up to 10 ticks run back to back (`:1215`). Mouse rotation is
   applied between ticks (`:1236`), so the yaw/pitch used by a tick is exactly the one the same tick sends.
2. Everything the local player sends for tick T is emitted before `ServerboundClientTickEndPacket` of tick T
   (`:1885`), in a fixed order (section 1.2). Buffer serverbound packets until TickEnd, then simulate one tick.
3. The physics is almost entirely **COMMON** code (`Entity`, `LivingEntity`, `Player`, blocks). The client-only part
   is small: `LocalPlayer.aiStep` (input, sprint, crouch, flight toggle, elytra start, water sink), `applyInput`/
   `modifyInput` (input scaling), `LocalPlayer.move` (auto-jump), and a handful of `LocalPlayer` overrides that
   **change the meaning** of common getters (`isShiftKeyDown`, `isCrouching`, `isUsingItem`, `isUnderWater`,
   `isHorizontalCollisionMinor`, `isSuppressingSlidingDownLadder`). A port that reuses `Player`'s defaults
   (which is what `ServerPlayer` runs) gets these wrong.
4. Auto-jump **is visible to the server**: it sets `jump=true` in `input.keyPresses`, which is exactly what
   `ServerboundPlayerInputPacket` carries (`LocalPlayer.java:805-809`, `ClientInput.java:21-31`,
   `LocalPlayer.java:266-268`). The server never needs the client option.
5. Movement physics uses **no RNG**. Hidden client state is all derivable from inputs plus server→client packets
   (section 7), with a short list of exceptions (other entities' client-side pushes, rockets, pistons,
   hotbar-key timing, `Math.sin`/`Math.cos` ulp drift; section 7.3).
6. The local player is moved client-side by things **other than its own tick**: pushes from every mob, remote
   player, boat and minecart that ticks on the client (`ClientLevel.java:542-546`, `RemotePlayer.java:59-61`,
   `LivingEntity.java:3227-3251`); firework rockets while gliding; geysers, pistons and shulker boxes in
   `tickBlockEntities` (after the player's tick, before `sendChanges`); and its own client-side `Player.attack`
   (×0.6 horizontal slowdown and sprint stop, `Player.java:1104-1131`).
7. The server echoes the player's own entity data and attributes back to itself
   (`server/level/ServerEntity.java:370-386`, same in `leaf-server/.../ServerEntity.java:457-480`). The client
   accepts the whole shared-flags byte (sprint, swim, glide, sneak bits) and the pose, and **replaces** all
   attribute modifiers, including its own sprint modifier (`ClientPacketListener.java:2368-2392`). These echoes are
   state changes the simulation must model, or the server must stop sending them to self (section 8.6).
8. RevivalPaper's server engine diverges from vanilla in movement math (Moonrise collisions, Gale "skip zero
   move", "skip negligible speed-factor multiply", Lithium `CubePointRange` multiply, Leaf attribute maps, Paper
   yaw wrapping). The port must carry its own vanilla math (section 9).

---

## 1. Scheduling: frames, ticks, packet order

### 1.1 Frame loop [CLIENT] `client/Minecraft.java`

`runTick(advanceGameTime)` (`:1184`):

1. `ticksToDo = deltaTracker.advanceGameTime(now)` (`:1197`). Tick length comes from the client
   `TickRateManager` (`:295`, `getTickTargetMillis`), so a server `/tick rate` changes the client's cadence.
2. `packetProcessor.processQueuedPackets()` then `runAllTasks()` (`:1202-1204`): **every queued clientbound packet is
   handled here, once per frame**, including `ClientboundPingPacket` → immediate `ServerboundPongPacket`
   (`client/multiplayer/ClientCommonPacketListenerImpl.java:154-157`).
3. `for i < min(10, ticksToDo): tick()` (`:1215-1220`). Several ticks can run with no packet handling in between.
4. `mouseHandler.handleAccumulatedMovement()` (`:1236`) → `Entity.turn` (`world/entity/Entity.java:506-518`):
   rotation changes happen **between ticks**, never inside one.

Consequence for transactions: a server→client change bracketed by a ping is applied before the first tick whose
`TickEnd` follows that ping's `Pong` in the serverbound stream. All packets that arrived before a frame apply to the
first tick of that frame together.

### 1.2 One client tick [CLIENT] `Minecraft.tick()` `:1795-1891`

| Step | Line | What runs | Serverbound packets it can emit (in this order) |
|---|---|---|---|
| 1 | `:1808` | `gameMode.tick()` → `ensureHasSentCarriedItem` (`client/multiplayer/MultiPlayerGameMode.java:293-313`), `connection.tick()` → `ClientPacketListener.tick()` (sends `ServerboundPlayerLoadedPacket` once, `ClientPacketListener.java:2804-2816`) | `SetCarriedItem` (slot changed since the last send), `PlayerLoaded` |
| 2 | `:1812` | `pick(1.0F)` (crosshair target from the current end-of-last-tick pose) | none |
| 3 | `:1822` | `handleKeybinds()` (`:1905-2003`): hotbar keys (`:1924-1936`, no packet yet), inventory, drop, swap, release-use (`:1965-1968`), attacks (`:1979-1981`), use (`:1983-1985`, `:1998-2000`), `continueAttack` | `PlayerAction` (drop/swap/release), `SetCarriedItem` (from `attack`/`interact`/`useItem` helpers), `Attack` (`:1695` → `MultiPlayerGameMode.java:430-438`) then `Punch` (`:1717`; also `:1647` from `continueAttack`), `UseItemOn`/`UseItem`/`Interact`, block-dig actions |
| 4 | `:1833` | `level.tickEntities()` → `ClientLevel.tickNonPassenger` (`client/multiplayer/ClientLevel.java:454-480`): for each ticking entity in insertion order: `commonTick()` then `tick()`. The local player is one of them | from `LocalPlayer.aiStep`: `PlayerAbilities` (flight toggle/landing, `LocalPlayer.java:852`, `:864`, `:933`), `PlayerCommand START_FALL_FLYING` (`:871`), `PlayerCommand START_RIDING_JUMP` (`:914`) |
| 5 | `:1835` | `level.tickBlockEntities()` | none (but moves the player, section 6.6) |
| 6 | `:1838` | `player.sendChanges()` (`LocalPlayer.java:264-282`) | `PlayerInput` (only if `keyPresses` changed), then `sendPosition` (`:294-332`): `PlayerCommand START/STOP_SPRINTING` (if changed, `:334-343`), then one of `MovePlayer.PosRot/Pos/Rot/StatusOnly` |
| 7 | `:1859` | `level.tick()` (world border lerp, time) | none |
| 8 | `:1885` | `connection.send(ServerboundClientTickEndPacket.INSTANCE)` | `ClientTickEnd` |

Notes:

- `LocalPlayer.tick()` and `sendChanges()` are no-ops until `connection.hasClientLoaded()`
  (`LocalPlayer.java:233`, `:265`), but `ClientTickEnd` is sent every unpaused tick. Simulation starts with the
  bundle that follows `ServerboundPlayerLoadedPacket` (sent in step 1 of the first loaded tick) and stops again on
  respawn/dimension change (`ClientPacketListener.java:536`, `:1307` reset `clientLoaded`).
- Packets sent from the frame (between ticks) belong before the next bundle: `Pong`, `AcceptTeleportation`
  (from `handleMovePlayer`, `ClientPacketListener.java:794-807`), container clicks, chat, and `SetCarriedItem`
  only when another packet helper flushes it (otherwise it waits for step 1).
- **Hotbar timing ambiguity**: a number key changes the slot in step 3 of tick T (used by tick T's physics) but
  its `SetCarriedItem` is only sent at step 1 of T+1 (`Minecraft.java:1931`, `MultiPlayerGameMode.java:307-313`).
  A scroll-wheel change happens in the frame and is also sent at step 1 of T+1. The two cases are
  indistinguishable on the wire. Only matters for item-dependent physics (scaffolding collision, attack-strength
  reset, `updatingUsingItem`), see section 7.3.
- Position/rotation in the `MovePlayer` packet are the values **after** tick T's movement. The rotation is the one
  tick T used (rotation never changes inside a tick except: sleeping forces `xRot = 0`,
  `LivingEntity.java:2864-2866`; riding clamps via `onPassengerTurned`).
- Pos is sent only when moved more than `2.0E-4` (squared check) or every 20 ticks (`LocalPlayer.java:303`); rot
  only when changed. Rotation floats are the **raw, unwrapped** client floats.

### 1.3 The local player's call tree for one tick (order is load-bearing)

`ClientLevel.tickNonPassenger(player)` (`ClientLevel.java:471-480`):

```
Entity.commonTick()                                   [COMMON] Entity.java:527-538
  invulnerableTime--; setOldPosAndRot(); (client) interpolation.interpolate(); tickCount++
LocalPlayer.tick()                                    [CLIENT] LocalPlayer.java:232-242
  dropSpamThrottler.tick()
  AbstractClientPlayer.tick()                         [CLIENT] AbstractClientPlayer.java:47-50 (visual state only)
    Player.tick()                                     [COMMON] Player.java:231-285
      noPhysics = isSpectator()
      if (spectator || passenger) setOnGround(false)
      (sleep counters; bed check is server-only :247)
      updateIsUnderwater()  -> LocalPlayer override     [CLIENT] LocalPlayer.java:1212-1229 (+ Player.java:304-307)
      LivingEntity.tick()                             [COMMON] LivingEntity.java:2758-2873
        Entity.tick() -> LivingEntity.baseTick()      [COMMON] LivingEntity.java:420-517
          swingState.tick(); (firstTick sleep fixup)
          Entity.baseTick()                           [COMMON] Entity.java:540-594
            computeSpeed(); inBlockState = null; passenger cleanup; boardingCooldown--
            handlePortal() (server only, :2722-2741)
            canSpawnSprintParticle -> spawnSprintParticle (visual, RNG)
            wasInPowderSnow = isInPowderSnow; isInPowderSnow = false
            wasEyeInWater = isEyeInFluid(WATER)
            updateFluidInteraction()   (fluid heights + CURRENT PUSH + fall reset in water)
            updateSwimming()  -> Player override when flying
            (client) clearFire()
            if isInLava(): fallDistance *= 0.5
            checkBelowWorld(); firstTick = false
          hurtTime--, damageCooldownTime--, tickDeath, tickEffects() (client: durations--), yHeadRotO/yBodyRotO
        updatingUsingItem(); updateSwimAmount()
        (server-only block :2762-2795)
        aiStep() -> LocalPlayer.aiStep()              [CLIENT] LocalPlayer.java:782-935   (section 3)
          ... super.aiStep() -> AbstractClientPlayer.aiStep (updateBob) -> Player.aiStep -> LivingEntity.aiStep
        body/head rotation smoothing (visual), angle wrap of *O fields
        fallFlyTicks; sleeping xRot = 0
        refreshDirtyAttributes()   (SCALE -> refreshDimensions)        LivingEntity.java:2868, :1135-1170
        elytraAnimationState.tick(); currentImpulseContextResetGraceTime--
      clamp x/z to ±2.9999999E7 (Player.java:261-266)
      attackStrengthTicker++, itemSwapTicker++, main-hand change -> resetAttackStrengthTicker (:268-277)
      turtle helmet, cooldowns.tick()
      updatePlayerPose()                                Player.java:343-357   (pose for NEXT tick's box)
  firstPersonHandsAndItems.tick(); ambient sounds     (visual/audio)
```

Passenger path instead: `ClientLevel.tickPassenger` → `commonTick()` + `rideTick()`
(`ClientLevel.java:482-494`; `LocalPlayer.rideTick` `:1002-1010` → `LivingEntity.rideTick` `:3297-3300` →
`Entity.rideTick` `Entity.java:2467-2473`: delta = 0, `tick()`, `vehicle.positionRider`). Vehicles are out of scope
for v1 (section 10).

---

## 2. Input [CLIENT]

### 2.1 Key sampling — `KeyboardInput.tick()` `client/player/KeyboardInput.java:22-36`

```
keyPresses = Input(up.isDown, down.isDown, left.isDown, right.isDown, jump.isDown, shift.isDown, sprint.isDown)
forwardImpulse = calculateImpulse(forward, backward)   // equal -> 0, else ±1
leftImpulse    = calculateImpulse(left, right)
moveVector     = new Vec2(leftImpulse, forwardImpulse).normalized()
```

- `Vec2.normalized()` (`world/phys/Vec2.java:59-62`): `dist = (float)Math.sqrt(x*x+y*y)`; `dist < 1.0E-4F` → ZERO,
  else `(x/dist, y/dist)` in float. Diagonals are `(±0.70710677F, ±0.70710677F)`.
- `ClientInput.hasForwardImpulse()` = `moveVector.y > 1.0E-5F` (`ClientInput.java:17-19`).
- Toggle-sneak/toggle-sprint options only change `isDown()`; the `Input` record (and the packet) carries the
  resulting logical state. `Input` codec: 7 bit flags (`world/entity/player/Input.java`).
- `ClientInput.makeJump()` (`ClientInput.java:21-31`) rebuilds `keyPresses` with `jump=true`.
- Mouse/keyboard events are processed at frame time; `isDown` is sampled when `input.tick()` runs inside aiStep.
  Opening a screen releases keys, which shows up in the next `Input`.

The server can rebuild `moveVector` exactly from the 4 direction bits of the latest `ServerboundPlayerInputPacket`
(sent only on change: `LocalPlayer.java:266-269`; `lastSentInput` survives respawn via the constructor,
`:173`, `:183`).

### 2.2 Input to `xxa`/`zza` — `LocalPlayer.applyInput()` `:707-720`

Only when `isControlledCamera()` (`minecraft.getCameraEntity() == this`, `:759-761`; false only while
spectating another entity). Otherwise `LivingEntity.applyInput()` (`LivingEntity.java:3175-3178`) just decays the
old `xxa/zza` by `0.98F`.

```
Vec2 m = modifyInput(input.getMoveVector());          // :722-738
xxa = m.x;  zza = m.y;  jumping = input.keyPresses.jump();
(xBob/yBob: camera only)

modifyInput(v):
  if v.lengthSquared() == 0: return v
  v = v.scale(0.98F)
  if isUsingItem() && !isPassenger(): v = v.scale(useItem USE_EFFECTS.speedMultiplier)   // default 0.2F
  if isMovingSlowly(): v = v.scale((float) getAttributeValue(SNEAKING_SPEED))          // default 0.3
  return modifyInputSpeedForSquareMovement(v)                                          // :740-757

modifyInputSpeedForSquareMovement(v):
  length = v.length()                      // (float)Math.sqrt
  if length <= 0: return v
  dir = v.scale(1.0F / length)
  ax = |dir.x|, ay = |dir.y|
  tan = ay > ax ? ax/ay : ay/ax
  d = (float)Math.sqrt(1.0F + tan*tan)
  return dir.scale(min(length * d, 1.0F))
```

All float math. `isUsingItem()` is the `LocalPlayer` override (`startedUsingItem`, `:572-575`); the multiplier
comes from the **client's copy** of the used stack (`:581-583`, `world/item/component/UseEffects.java`: default
`canSprint=false, speedMultiplier=0.2F`). RevivalPaper's sword block gives the client's sword copy a consumable
component (`docs/revival/grimac-checklist.md` row 2), so the client slows to 0.2 with no `USE_EFFECTS`.
`isMovingSlowly()` = `isCrouching() || isVisuallyCrawling()` (`:702-704`), where `isCrouching()` is the
`crouching` field (section 3, step 4) and `isVisuallyCrawling()` = pose SWIMMING and not in water
(`Entity.java:2837-2839`).

`yya` is never set for players (only `Mob.java:441`), so it is always 0.

### 2.3 Input vector to velocity — `Entity.moveRelative` / `getInputVector` [COMMON] `Entity.java:1807-1822`

```
lenSq = input.lengthSqr()                       // input = Vec3(xxa, 0, zza) (float -> double)
if lenSq < 1.0E-7: add nothing
mv = (lenSq > 1.0 ? input.normalize() : input).scale(speed)     // speed float widened
sin = Mth.sin(yRot * 0.017453292F); cos = Mth.cos(yRot * 0.017453292F)   // float*float, table lookup
delta += (mv.x*cos - mv.z*sin, mv.y, mv.z*cos + mv.x*sin)
```

`Mth.sin/cos` (`util/Mth.java:35-56`): 65536-entry float table built with `Math.sin`, index
`(int)((long)(i * 10430.378350470453) & 65535L)`. **The argument depends on the raw float yaw**, so the port must
keep the exact float the client sent; Paper wraps it (`leaf-server/.../ServerGamePacketListenerImpl.java:1585`),
which changes `yRot * 0.017453292F` and can shift the table index.

---

## 3. `LocalPlayer.aiStep()` [CLIENT] `client/player/LocalPlayer.java:782-935`, step by step

State captured **before** the new input is sampled is marked (prev).

1. `if sprintTriggerTime > 0: sprintTriggerTime--` (`:783-785`).
2. Portal/nausea screen effects and `processPortalCooldown()` (`:787-792`; visual, RNG for a sound). Skip.
3. `wasJumping = keyPresses.jump()` (prev), `wasShiftKeyDown = keyPresses.shift()` (prev),
   `hasForwardImpulse = input.hasForwardImpulse()` (prev) (`:793-795`).
4. **Crouching** (`:797-801`), computed with the **previous** tick's shift key (input not yet re-sampled):
   ```
   crouching = !abilities.flying && !isSwimming() && !isPassenger()
            && canPlayerFitWithinBlocksAndEntitiesWhen(CROUCHING)
            && (isShiftKeyDown() || !isSleeping() && !canPlayerFitWithinBlocksAndEntitiesWhen(STANDING))
   ```
   `canPlayerFitWithinBlocksAndEntitiesWhen(pose)` = `level.noCollision(this,
   getDimensions(pose).makeBoundingBox(position()).deflate(1.0E-7))` (`Player.java:373-375`): blocks **and** solid
   entities (boats, shulkers) **and** world border (`world/level/CollisionGetter.java:36-38`).
5. `input.tick()` (`:802`) — new `keyPresses`, `moveVector`.
6. Auto-jump (`:804-809`): `if autoJumpTime > 0: autoJumpTime--; wasAutoJump = true; input.makeJump()`.
7. `moveTowardsClosestSpace` ×4 (`:811-816`), only if `!noPhysics && !isPassenger()` (the passenger guard is new in
   26.3), with `w = getBbWidth()`, in this order: `(x-w*0.35, z+w*0.35)`, `(x-w*0.35, z-w*0.35)`,
   `(x+w*0.35, z-w*0.35)`, `(x+w*0.35, z+w*0.35)`; each call reads the position/velocity left by the previous one.
   `LocalPlayer.moveTowardsClosestSpace(x,z)` (`:465-492`, no RNG, unlike
   `Entity.moveTowardsClosestSpace` `Entity.java:3031`): if `suffocatesAt(BlockPos.containing(x, y, z))`
   (`:494-498`: `collidesWithSuffocatingBlock` on `AABB(px, bb.minY, pz, px+1, bb.maxY, pz+1).deflate(1e-7)`),
   check directions in order WEST, EAST, NORTH, SOUTH; pick the smallest distance-to-edge whose neighbour does not
   suffocate; set `deltaMovement.x = 0.1*stepX` (keeping y,z) or `.z = 0.1*stepZ`.
8. Sprint trigger reset (`:818-820`): `if wasShiftKeyDown || isSlowDueToUsingItem() && !isPassenger() || keyPresses.backward(): sprintTriggerTime = 0`.
   `isSlowDueToUsingItem()` = `isUsingItem() && !USE_EFFECTS.canSprint` (`:577-579`).
9. Sprint start (`:822-834`):
   ```
   if canStartSprinting():                       // :1171-1178
     if !hasForwardImpulse (prev):               // a fresh forward press this tick
        if sprintTriggerTime > 0: setSprinting(true)
        else sprintTriggerTime = options.sprintWindow   // client option, default 7, 0..10 (client/Options.java:638-645)
     if keyPresses.sprint(): setSprinting(true)
   canStartSprinting = !isSprinting() && input.hasForwardImpulse() (current)
                     && isSprintingPossible(abilities.flying)
                     && !isSlowDueToUsingItem()
                     && (!isFallFlying() || isUnderWater())
                     && (!isMovingSlowly() || isUnderWater())
   isSprintingPossible(allowShallow) = !isMobilityRestricted()            // BLINDNESS, Player.java:1935-1937
                     && (isPassenger() ? vehicle.canSprint() && vehicle.isLocalInstanceAuthoritative()
                                       : foodLevel > 6 || abilities.mayfly)  // Player.java:1559-1561, FoodData.java:92-94
                     && (allowShallow || !isInShallowWater())              // Entity.java:1671-1673
   ```
   The double-tap path depends on `sprintWindow` (hidden option). The result is visible on the wire as a
   `START_SPRINTING` command sent at the end of the same tick, so the server can take the sprint state from the
   bundle and only validate it against `canStartSprinting`.
10. Sprint stop (`:836-844`), using collision flags from the **previous** tick's move:
    ```
    if isSprinting():
      if isSwimming(): stop if !isSprintingPossible(true) || !isInWater()
                                || !input.hasForwardImpulse() && !onGround() && !keyPresses.shift()   // :943-945
      else:            stop if !isSprintingPossible(abilities.flying) || !input.hasForwardImpulse()
                                || horizontalCollision && !minorHorizontalCollision                   // :937-941
    ```
    `setSprinting` (`LivingEntity.java:2313-2320`) sets shared flag 3 and removes/adds the
    `SPEED_MODIFIER_SPRINTING` transient modifier (`sprinting`, `0.30000001192092896`, `ADD_MULTIPLIED_TOTAL`,
    `LivingEntity.java:159-161`) on `MOVEMENT_SPEED` **immediately**, so it affects this tick's travel.
11. Creative/spectator flight (`:846-868`):
    - `mayfly` and `minecraft.gameMode.isSpectator()` (the **local game mode** from `ClientboundGameEventPacket`,
      not the tab-list mode): force `flying = true` and send abilities.
    - else on a fresh jump press that is not auto-jump (`!wasJumping && jump && !wasAutoJump`):
      `jumpTriggerTime == 0` → `jumpTriggerTime = 7`; else if `!isSwimming() && (vehicle == null ||
      jumpableVehicle != null)`: toggle `flying`; if now flying and `onGround()` → `jumpFromGround()`; send
      abilities; `jumpTriggerTime = 0`.
    - `jumpTriggerTime` is decremented later in `Player.aiStep` (`Player.java:443-445`).
12. Elytra start (`:870-872`): `if jump && !justToggledCreativeFlight && !wasJumping && !onClimbable() &&
    tryToStartFallFlying()` → send `START_FALL_FLYING`. `tryToStartFallFlying` (`Player.java:1430-1437`):
    `!isFallFlying() && canGlide() && !isInLiquid()` → `setSharedFlag(7, true)` locally.
    `canGlide` = `!abilities.flying && LivingEntity.canGlide()` (`Player.java:1395-1398`,
    `LivingEntity.java:3210-3222`: not on ground, not passenger, no LEVITATION, some slot has a usable glider).
13. `wasFallFlying = isFallFlying()` (`:874`).
14. Water sink (`:875-877`): `if isInWater() && keyPresses.shift() && isAffectedByFluids(): goDownInWater()` →
    `delta.y += -0.03999999910593033` (`LivingEntity.java:2399-2401`). Only `LocalPlayer` does this; the server's
    `Player` path never calls it.
15. Water vision timer (`:879-885`) — visual.
16. Flying vertical input (`:887-900`), only when `flying && isControlledCamera()`:
    `inputYa = (jump ? 1 : 0) - (shift ? 1 : 0)`; if non-zero: `delta.y += inputYa * abilities.getFlyingSpeed() * 3.0F`.
17. Rideable jump charge (horses, `:902-928`) — vehicle, out of scope.
18. `super.aiStep()` (`:930`) → `AbstractClientPlayer.aiStep` (`updateBob`, visual) → `Player.aiStep` (section 4).
19. Landing ends creative flight (`:931-934`): `if onGround() && flying && !spectator: flying = false`, send
    abilities.

---

## 4. `Player.aiStep` and `LivingEntity.aiStep` [COMMON]

### 4.1 `Player.aiStep()` `world/entity/player/Player.java:442-481`

1. `jumpTriggerTime--` if > 0.
2. `tickRegeneration()` (no-op), `inventory.tick()` (item inventoryTick, no movement).
3. `if abilities.flying && !isPassenger(): resetFallDistance()`.
4. `super.aiStep()` → `LivingEntity.aiStep`.
5. `yHeadRot = yRot`; `setSpeed(MOVEMENT_SPEED)` (unused for players: `Player.getSpeed()` returns the attribute
   directly, `:1413-1416`).
6. Item/XP pickup `touch` (client: no effect on movement), shoulder entities.

### 4.2 `LivingEntity.aiStep()` `world/entity/LivingEntity.java:3040-3173`

```
1  if noJumpDelay > 0: noJumpDelay--
2  if !isInterpolating() && !canSimulateMovement(): delta *= 0.98          // never for the local player
3  lerpHeadSteps (0 for the local player)
4  equipment.tick(this)                                                     // inventoryTick, no movement
5  velocity floor (players):  if delta.horizontalDistanceSqr() < 9.0E-6: x = z = 0
                              if |delta.y| < 0.003: y = 0                    // :3059-3078
6  applyInput()                                                             // LocalPlayer override (2.2)
7  if isImmobile(): jumping = false; xxa = zza = 0                          // dead or sleeping (Player.java:854-857)
   (server-only serverAiStep)
8  JUMP (:3094-3118):
   if jumping && isAffectedByFluids():                                      // Player: !abilities.flying
       fluidHeight = isInLava() ? getFluidHeight(LAVA) : getFluidHeight(WATER)
       inWaterAndHasFluidHeight = isInWater() && fluidHeight > 0
       threshold = getFluidJumpThreshold()                                  // eyeHeight < 0.4 ? 0 : 0.4 (Entity.java:3845-3847)
       if inWaterAndHasFluidHeight && (!onGround() || fluidHeight > threshold): jumpInLiquid(WATER)    // y += 0.04
       else if isInLava() && (!onGround() || !isInShallowFluid(LAVA)):       jumpInLiquid(LAVA)     // y += 0.04
       else if (onGround() || inWaterAndHasFluidHeight && fluidHeight <= threshold) && noJumpDelay == 0:
            jumpFromGround(); noJumpDelay = 10
   else: noJumpDelay = 0
9  if isFallFlying(): updateFallFlying()      // client: checkFallDistanceAccumulation() only (:3188-3208)
10 input = Vec3(xxa, yya(=0), zza)
11 if hasEffect(SLOW_FALLING) || hasEffect(LEVITATION): resetFallDistance()
12 if controlling passenger is a Player: travelRidden   // not for players
   else if canSimulateMovement() && isEffectiveAi(): travel(input)          // Player.isEffectiveAi: !client || isLocalPlayer (Player.java:1264-1266)
13 if !isClientSide || isLocalInstanceAuthoritative(): applyEffectsFromBlocks()   // section 6.1
14 (client) calculateEntityAnimation (visual); (server) freezing/frost
15 if autoSpinAttackTicks > 0: autoSpinAttackTicks--; checkAutoSpinAttack(beforeTravelBox, bb)   // riptide, :3253-3274
16 pushEntities()                                  // local player: no-op, see 6.5
```

`checkFallDistanceAccumulation` (`Entity.java:3021-3025`): `if delta.y > -0.5 && fallDistance > 1.0: fallDistance = 1.0`.

`jumpFromGround()` (`LivingEntity.java:2385-2397`; `Player`/`LocalPlayer` do not override it):

```
jumpPower = (float)JUMP_STRENGTH * 1.0F * getBlockJumpFactor() + getJumpBoostPower()     // :2376-2382
          // boost = hasEffect(JUMP_BOOST) ? 0.1F * (amplifier + 1.0F) : 0
if jumpPower <= 1.0E-5F: return
delta = (delta.x, max((double)jumpPower, delta.y), delta.z)
if isSprinting(): a = yRot * 0.017453292F; delta += (-Mth.sin(a) * 0.2, 0, Mth.cos(a) * 0.2)
```

`getBlockJumpFactor()` (`Entity.java:1122-1126`): factor of the block at `blockPosition()`, or if that is `1.0`, of
the block at `getBlockPosBelowThatAffectsMyMovement()`. Honey = `0.5`.

---

## 5. `travel` and its branches [COMMON]

### 5.1 `Player.travel(input)` `Player.java:1369-1393`

```
if isPassenger(): LivingEntity.travel(input); return
if isSwimming():                                   // Player.isSwimming = !flying && !spectator && flag 4 (:1649-1651)
    lookY = getLookAngle().y
    mult = lookY < -0.2 ? 0.085 : 0.06
    if lookY <= 0 || jumping || !fluidState(BlockPos.containing(x, y + 1.0 - 0.1, z)).isEmpty():
        delta.y += (lookY - delta.y) * mult
if abilities.flying:
    origY = delta.y; LivingEntity.travel(input); delta.y = origY * 0.6
else LivingEntity.travel(input)
```

`getLookAngle()` = `calculateViewVector(xRot, yRot)` (`Entity.java:2031-2039`, float sin/cos):
`(sin(-yRot°)·cos(xRot°), -sin(xRot°), cos(-yRot°)·cos(xRot°))` with `Mth` tables.

### 5.2 `LivingEntity.travel(input)` `LivingEntity.java:2426-2434`

```
if shouldTravelInFluid(fluidState at blockPosition()):   // isInLiquid() && isAffectedByFluids() && !canStandOnFluid (players: false)
    travelInFluid(input)
else if isFallFlying(): travelFallFlying(input)
else travelInAir(input)
```

### 5.3 `travelInAir` `LivingEntity.java:2464-2491` (ground, air, ladders, flying, levitation, slow falling)

```
posBelow = getBlockPosBelowThatAffectsMyMovement()                       // BEFORE the move
blockFriction = onGround() ? computeModifiedFriction(block(posBelow).getFriction(), (float)FRICTION_MODIFIER) : 1.0F
   computeModifiedFriction(f, m) = Mth.clamp(1.0F - (1.0F - f) * m, 0.0F, 1.0F)          // :528-530 (float)
movement = handleRelativeFrictionAndCalculateMovement(input, blockFriction)              // :2674-2684
   moveRelative(getFrictionInfluencedSpeed(blockFriction), input)
   setDeltaMovement(handleOnClimbable(delta))
   move(SELF, delta)                                                     // section 6
   m = delta
   if (horizontalCollision || jumping) && (onClimbable() || wasInPowderSnow && canEntityWalkOnPowderSnow(this)):
        m.y = 0.2
movementY = m.y
if (lev = getEffect(LEVITATION)) != null: movementY += (0.05 * (lev.amp + 1) - m.y) * 0.2
else if !isClientSide || level.hasChunkAt(posBelow): movementY -= getEffectiveGravity()
else if getY() > level.getMinY(): movementY = -0.1          // CLIENT-ONLY branch: chunk under the player not loaded
else movementY = 0.0
if shouldDiscardFriction(): delta = (m.x, movementY, m.z)
else:
   airDrag  = computeModifiedFriction(0.91F, (float)AIR_DRAG_MODIFIER)
   friction = blockFriction * airDrag                                      // float
   vertical = omnidirectionalAirMover() ? airDrag : computeModifiedFriction(0.98F, (float)AIR_DRAG_MODIFIER)   // players: false
   delta = (m.x * friction, movementY * vertical, m.z * friction)
```

`getFrictionInfluencedSpeed(f)` (`:2718-2724`):

```
onGround() ? (f > 0.6 ? getSpeed() * (0.21600002F / (f*f*f)) : getSpeed())     // float; note 0.6F > 0.6 (double) is TRUE
           : getFlyingSpeed()
Player.getFlyingSpeed() (Player.java:1945-1951):
   abilities.flying && !isPassenger() ? (sprinting ? abilities.flyingSpeed * 2.0F : abilities.flyingSpeed)
                                      : (sprinting ? 0.025999999F : 0.02F)
```

Default block friction is `0.6F` (`world/level/block/state/BlockBehaviour.java:1012`), so the `f > 0.6` branch
runs on normal ground too (`0.6F` widens to `0.6000000238…`). Port the comparison literally.

`handleOnClimbable(d)` (`:2701-2716`): if `onClimbable()`: `resetFallDistance()`;
`x,z = clamp(±0.15000000596046448)`; `y = max(y, -0.15000000596046448)`; if `y < 0 &&
!getInBlockState().is(SCAFFOLDING) && isSuppressingSlidingDownLadder() && this instanceof Player`: `y = 0`.
`LocalPlayer.isSuppressingSlidingDownLadder()` = `!flying && isShiftKeyDown()` (`LocalPlayer.java:399-401`).

`onClimbable()` (`Player.java:1995` → `false` when flying; `LivingEntity.java:1742-1760`): not spectator; state at
`blockPosition()` (`getInBlockState()` cache): gliding through `CAN_GLIDE_THROUGH` → false; `CLIMBABLE` tag → true;
open trapdoor above a ladder with the same facing → true. Side effect: writes `lastClimbablePos` (not used by
physics).

`getEffectiveGravity()` (`:2421-2424`): `falling = delta.y <= 0; falling && hasEffect(SLOW_FALLING) ?
min(getGravity(), 0.01) : getGravity()`; `getGravity()` = `isNoGravity() ? 0 : GRAVITY attribute`
(`Entity.java:1573-1575`, `LivingEntity.java:2416-2418`; default `0.08`).

Ground-tick fixed point (useful for RevivalPaper's knockback): standing still, after the vertical collision the
restitution step sets `delta.y = 0` (6.2 step 12) and `travelInAir` then gives `(0 − 0.08) × 0.98 = −0.0784` for the
next tick, the same value `MotionShadow` models (`leaf-server/src/main/java/gg/hcfrevival/revival/combat/MotionShadow.java`).

### 5.4 `travelInFluid` `:2498-2560`

```
isFalling = delta.y <= 0; oldY = getY(); g = getEffectiveGravity()
if isInWater(): travelInWater(...) else travelInLava(...)
floatInLiquidWhileRidden(ENTITY_FLOATABLE)          // vehicles only

travelInWater:
  slow = isSprinting() ? 0.9F : 0.8F; speed = 0.02F
  ww = (float)WATER_MOVEMENT_EFFICIENCY; if !onGround(): ww *= 0.5F
  if ww > 0: slow += (0.54600006F - slow) * ww; speed += (getSpeed() - speed) * ww
  if hasEffect(DOLPHINS_GRACE): slow = 0.96F
  moveRelative(speed, input); move(SELF, delta)
  m = delta; if horizontalCollision && onClimbable(): m.y = 0.2
  m = m.multiply(slow, 0.800000011920929, slow)
  delta = getFluidFallingAdjustedMovement(g, isFalling, m)
  jumpOutOfFluid(oldY)

travelInLava:
  moveRelative(0.02F, input); move(SELF, delta)
  if isInShallowFluid(LAVA):  delta *= (0.5, 0.800000011920929, 0.5); delta = getFluidFallingAdjustedMovement(g, isFalling, delta)
  else delta *= 0.5
  if g != 0: delta.y += -g / 4.0
  jumpOutOfFluid(oldY)

getFluidFallingAdjustedMovement(g, falling, m) (:2686-2699):
  if g != 0 && !isSprinting():
     y = falling && |m.y - 0.005| >= 0.003 && |m.y - g/16| < 0.003 ? -0.003 : m.y - g/16
jumpOutOfFluid(oldY) (:2562-2567):
  if horizontalCollision && isFree(d.x, d.y + 0.6000000238418579 - getY() + oldY, d.z): d.y = 0.30000001192092896
  isFree = noCollision(bb.move(...)) && !containsAnyLiquid(bb.move(...))      (Entity.java:688-694)
```

`isInShallowFluid(tag)` = `getFluidHeight(tag) <= getFluidJumpThreshold()` (`:2540-2542`).

### 5.5 `travelFallFlying` `:2579-2625`

```
if onClimbable(): travelInAir(input); stopFallFlying()       // flips flag 7 true then false (:2595-2598)
else:
  last = delta; lastSpeed = last.horizontalDistance()
  delta = updateFallFlyingMovement(last); move(SELF, delta)
  (server only) handleFallFlyingCollisions -> wall damage

updateFallFlyingMovement(m):
  look = getLookAngle(); lean = xRot * 0.017453292F (float)
  lookHor = sqrt(look.x² + look.z²); moveHor = m.horizontalDistance(); g = getEffectiveGravity()
  lift = Mth.square(Math.cos(lean))                 // java.lang.Math.cos, NOT Mth.cos
  m += (0, g * (-1.0 + lift * 0.75), 0)
  if m.y < 0 && lookHor > 0: c = m.y * -0.1 * lift;            m += (look.x*c/lookHor, c, look.z*c/lookHor)
  if lean < 0 && lookHor > 0: c = moveHor * -Mth.sin(lean) * 0.04; m += (-look.x*c/lookHor, c*3.2, -look.z*c/lookHor)
  if lookHor > 0: m += ((look.x/lookHor*moveHor - m.x)*0.1, 0, (look.z/lookHor*moveHor - m.z)*0.1)
  return m.multiply(0.9900000095367432, 0.9800000190734863, 0.9900000095367432)
```

The client **never clears the gliding flag itself on landing**; only the server does (`updateFallFlying`
server branch `:3190-3194` → `setSharedFlag(7, false)` → echoed). Until that echo arrives the client keeps running
`travelFallFlying` on the ground. Model it via the transaction that carries the flags update.

### 5.6 Branch summary

| Situation | Branch | Speed fed to `moveRelative` | Drag after move |
|---|---|---|---|
| On ground | `travelInAir` | `speed*(0.21600002F/f³)` (f = modified block friction) | `x,z *= f*airDrag`; `y = (y-g)*0.98` |
| Airborne | `travelInAir` | `0.02F` / `0.025999999F` sprinting | `x,z *= 0.91`; `y = (y-g)*0.98` |
| Creative/spectator flight | `travelInAir` + `Player.travel` y reset | `flyingSpeed` (×2 sprint) | `x,z *= 0.91` (or ground friction if onGround); `y = origY*0.6` |
| Ladder/vine/scaffolding | `travelInAir` + `handleOnClimbable` | as above | clamps ±0.15, `y=0.2` on push/jump |
| Powder snow (leather boots) | `travelInAir` | as above | `y=0.2` when colliding/jumping and `wasInPowderSnow` |
| Water | `travelInWater` | `0.02F` → depth strider lerp | `x,z *= 0.8/0.9/0.96…`, `y *= 0.8`, `-g/16` |
| Lava | `travelInLava` | `0.02F` | `*0.5` (shallow: `(0.5,0.8,0.5)`) and `-g/4` |
| Elytra | `travelFallFlying` | none (look-driven) | `(0.99,0.98,0.99)` |
| Levitation | `travelInAir` | normal | `y += (0.05*(amp+1) - y)*0.2` instead of gravity |
| Slow falling | gravity capped `0.01` while falling | — | — |

---

## 6. `Entity.move` and everything it touches [COMMON]

### 6.1 Ordered body of `Entity.move(SELF, delta)` `Entity.java:741-836` (as the local player runs it)

`LocalPlayer.move` (`LocalPlayer.java:1017-1025`) wraps it: remembers x/z, calls `super.move`, then
`updateAutoJump(dx, dz)` (client option) and `addWalkedDistance` (visual).

```
 1  (IDE assertion only)
 2  if noPhysics (spectator): setPos(pos + delta); recordMovement; all collision flags = false; return   // onGround untouched
 3  if PISTON: delta = limitPistonMovement(delta) (±0.51 per axis per game tick, :1142-1173)
 4  if stuckSpeedMultiplier.lengthSqr() > 1.0E-7:
        if moverType != PISTON: delta = delta.multiply(stuckSpeedMultiplier)
        stuckSpeedMultiplier = ZERO; setDeltaMovement(ZERO)              // web/berry/powder snow (6.4)
 5  delta = maybeBackOffFromEdge(delta, moverType)                         // Player.java:864-911 (6.3)
 6  movement = collide(delta)                                              // :1182-1211 (6.2)
 7  len = movement.lengthSqr()
    if len > 1.0E-7 || delta.lengthSqr() - len < 1.0E-7:
        if fallDistance != 0 && len >= 1.0:                                // long moves: clip for FALLDAMAGE_RESETTING blocks
            to = pos + movement.normalize().scale(min(movement.length(), 8.0))
            if level.clip(ClipContext(pos, to, Block.FALLDAMAGE_RESETTING, Fluid.WATER, this)) != MISS: resetFallDistance()
        movementThisTick.add(Movement(pos, pos+movement, delta))          // max 100, merges first two (:963-972)
        setPos(pos + movement); recordMovement
 8  xCollision = !Mth.equal(delta.x, movement.x); zCollision = !Mth.equal(delta.z, movement.z)   // |b-a| < 9.999999747378752E-6
    horizontalCollision = xCollision || zCollision
 9  movedVertically = |delta.y| > 0
    if movedVertically || isLocalInstanceAuthoritative():                  // CLIENT: always
        verticalCollision = delta.y != movement.y                          // exact compare
        verticalCollisionBelow = verticalCollision && delta.y < 0
        setOnGroundWithMovement(verticalCollisionBelow, horizontalCollision, movement)   // :705-709 -> checkSupportingBlock
10  minorHorizontalCollision = horizontalCollision ? isHorizontalCollisionMinor(movement) : false
11  effectPos = getOnPosLegacy(); effectState = blockState(effectPos)
12  if isLocalInstanceAuthoritative():                                     // CLIENT only
        checkFallDamage(movement.y, onGround(), effectState, effectPos)    // 6.1.1
13  if isRemoved(): return
    if canSimulateMovement() && (movedVertically && verticalCollision || horizontalCollision):
        restituteMovementAfterCollisions(effectState, xCollision, zCollision, movement)   // :844-886 (6.2.4)
14  if !isClientSide || isLocalInstanceAuthoritative(): movement emission (step sounds, game events, moveDist)  // SKIP in port
15  f = getBlockSpeedFactor(); delta = delta.multiply(f, 1.0, f)          // unconditional in vanilla
```

`isHorizontalCollisionMinor` is a **LocalPlayer override** (`LocalPlayer.java:1134-1149`); `Entity`'s default is
`false` (`Entity.java:1051-1053`), which is what `ServerPlayer` uses:

```
r = yRot * 0.017453292F; s = Mth.sin(r); c = Mth.cos(r)                   // doubles from float table values
gx = xxa*c - zza*s; gz = zza*c + xxa*s
aLen2 = gx² + gz²; mLen2 = movement.x² + movement.z²
if aLen2 < 9.999999747378752E-6 || mLen2 < 9.999999747378752E-6: false
else Math.acos((gx*movement.x + gz*movement.z) / Math.sqrt(aLen2 * mLen2)) < 0.13962633907794952
```

`checkSupportingBlock(onGround, movement)` (`:715-735`): if on ground, test box
`(minX, minY-1.0E-6, minZ, maxX, minY, maxZ)` with `level.findSupportingBlock` (`CollisionGetter.java:140-154`:
iterate `BlockCollisions`, keep the colliding block whose center is closest to `position()`, ties broken by
`BlockPos.compareTo`). If none and `onGroundNoBlocks` was false, retry with the box moved by `-movement.x, -movement.z`.
`onGroundNoBlocks = result.isEmpty()`. Off ground: clear both.

`getOnPos(offset)` (`:1101-1120`): with a supporting block: offset ≤ 1e-5 → that block; otherwise
`supporting.atY(floor(y - offset))` unless the supporting block is a fence (only when offset ≤ 0.5), wall or fence
gate (then the supporting block itself). Without one: `(floor x, floor(y - offset), floor z)`.
`getOnPosLegacy` = offset `0.2F`; `getBlockPosBelowThatAffectsMyMovement` = `0.500001F`; `getOnPos()` = `1.0E-5F`.

`getBlockSpeedFactor()`: `Player` (`Player.java:1827-1830`) returns `1.0F` when flying or gliding; otherwise
`LivingEntity` (`LivingEntity.java:524-526`) = `Mth.lerp((float)MOVEMENT_EFFICIENCY, Entity.factor, 1.0F)`;
`Entity` (`Entity.java:1128-1136`): factor of the block at `blockPosition()`; if it is water or a bubble column use
it as is; else if it is `1.0` use the factor at `getBlockPosBelowThatAffectsMyMovement()`. Soul sand and honey =
`0.4`. Soul Speed works through `MOVEMENT_EFFICIENCY` (server-applied, synced).

#### 6.1.1 `checkFallDamage` on the client (step 12)

`LivingEntity.checkFallDamage` (`LivingEntity.java:374-405`):

```
if !isInWater(): updateFluidInteraction()     // RE-EVALUATES FLUIDS AFTER EVERY move(), incl. current push (6.5)
(server only: landing particles)
Entity.checkFallDamage(ya, onGround, state, pos)   // Entity.java:1605-1623
    if !isInWater() && ya < 0: fallDistance -= (float) ya          // NB: cast to float, then double subtract
    if onGround:
        if fallDistance > 0: state.getBlock().fallOn(level, state, pos, this, fallDistance); gameEvent HIT_GROUND
        resetFallDistance()
if onGround: lastClimbablePos = empty
```

This mid-move fluid update is easy to miss: on the tick the player enters water, `wasTouchingWater` flips inside
`move()`, the water current is applied to `deltaMovement` before restitution and the speed factor, and the
**second** `move()` of the same tick (if any) skips it because `isInWater()` is now true. `ServerPlayer` does not run
this path (the gate is `isLocalInstanceAuthoritative()`, false on the server for players: `Entity.java:3747-3749`,
`Player.java:1245-1252`); the server applies fall logic later through `doCheckFallDamage` from the move packet.

### 6.2 `collide(delta)` `Entity.java:1182-1307`

```
aabb = bb
entityColliders = level.getEntityCollisions(this, aabb.expandTowards(m).expandTowards(0, maxUpStep(), 0))   // 26.3 adds the step expansion
ms = m.lengthSqr() == 0 ? m : collideBoundingBox(this, m, aabb, level, entityColliders)
xC = m.x != ms.x; yC = m.y != ms.y; zC = m.z != ms.z                      // exact
onGroundAfter = yC && m.y < 0
if maxUpStep() > 0 && (onGroundAfter || onGround()) && (xC || zC):
    grounded = onGroundAfter ? aabb.move(0, ms.y, 0) : aabb
    stepBox = grounded.expandTowards(m.x, maxUpStep(), m.z)
    if !onGroundAfter: stepBox = stepBox.expandTowards(0, -9.999999747378752E-6, 0)
    colliders = collectCollidersIgnoringWorldBorder(this, level, entityColliders, stepBox)
    skip = (float) ms.y
    for h in collectCandidateStepUpHeights(grounded, colliders, maxUpStep(), skip):   // sorted ascending floats
        s = collideWithShapes(Vec3(m.x, h, m.z), grounded, colliders)
        if s.horizontalDistanceSqr() > ms.horizontalDistanceSqr():
            return s.subtract(0, aabb.minY - grounded.minY, 0)
return ms
```

- `collideBoundingBox` (`:1240-1245`): colliders for `aabb.expandTowards(m)`, then `collideWithShapes`.
- `collectCollidersIgnoringWorldBorder(Entity, …)` (`:1259-1275`; the name is misleading): list order is
  **entity colliders, then the world-border shape if `worldBorder.isInsideCloseToBorder(source, box)`, then block
  colliders** from `level.getBlockCollisions(source, box)`.
- `collectCandidateStepUpHeights` (`:1213-1238`): for each collider, for each Y coordinate:
  `rel = (float)(coord - box.minY)`; skip if `rel < 0` or `rel == skip`; stop that collider when `rel > maxStep`;
  else add to a `FloatArraySet`; `FloatArrays.unstableSort` ascending.
- `collideWithShapes` (`:1289-1307`): axis order `Direction.axisStepOrder(m)` (`core/Direction.java:390-392`):
  `|m.x| < |m.z| ? Y,Z,X : Y,X,Z`. Per axis with non-zero movement:
  `Shapes.collide(axis, box.move(resolved), shapes, amount)` (`world/phys/shapes/Shapes.java:223-233`: iterate shapes
  **in list order**; return `0.0` as soon as `|distance| < 1.0E-7`; else `shape.collide`).
  `VoxelShape.collideX` (`world/phys/shapes/VoxelShape.java:253-300`) uses `1.0E-7` epsilons and the shape's
  coordinate lists. Because of the early `< 1.0E-7 → 0` exit, **shape order matters at the 1e-7 scale**; keep it.
- `maxUpStep()` (`LivingEntity.java:4040-4044`): `(float)STEP_HEIGHT` (0.6), or `max(…, 1.0F)` when a player
  controls the entity (vehicles only).
- Block colliders: `BlockCollisions` (`world/level/BlockCollisions.java:42-112`): cursor over
  `floor(min-1e-7)-1 … floor(max+1e-7)+1` on each axis, x fastest then y then z (`core/Cursor3D.java`);
  skips null chunks; edge/corner cells only for `hasLargeCollisionShape` / moving pistons; shape from
  `context.getCollisionShape(state, level, pos)`; full cubes tested with a box intersect, others with
  `Shapes.joinIsNotEmpty(shape, entityShape, AND)`.
- Entity colliders: `EntityGetter.getEntityCollisions` (`world/level/EntityGetter.java:54-70`): entities in
  `testArea.inflate(1e-7)` with `source.canCollideWith(e)` → `e.canBeCollidedWith(source)`: boats
  (`AbstractBoat.java:122`), live shulkers (`Shulker.java:479`), adult live happy ghasts when (client only) the
  player's feet are at or above their box top, or when they are on their "still" timeout
  (`HappyGhast.java:579-589`). Their **client-interpolated** boxes are used.
- `CollisionContext.of(player)` (`world/phys/shapes/CollisionContext.java:24-31`,
  `EntityCollisionContext.java`): `descending = isDescending()` (= `isShiftKeyDown()`, i.e. the current
  `keyPresses.shift()` on `LocalPlayer`), `entityBottom = getY()` at creation, `heldItem = getMainHandItem()`,
  plus the entity itself (powder snow reads `fallDistance` and feet item). Context-dependent shapes:
  scaffolding (`ScaffoldingBlock.java:51-57`, `:130-140`), powder snow (`PowderSnowBlock.java:116-137`:
  `fallDistance > 2.5` → 0.9-high shape; leather boots + above + not descending → full), source liquid only for
  entities that can stand on fluid (never players).

#### 6.2.4 Restitution — `restituteMovementAfterCollisions` `Entity.java:844-886`

Runs on the client and on the server (gate `canSimulateMovement()`). This replaced the old
`updateEntityMovementAfterFallOn`; slime and bed bounces now come from `Block.getBounceRestitution()`.

```
restitution = isSuppressingBounce() (= shift) ? 0.0 : getEntityBounciness()        // LivingEntity: BOUNCINESS attr, default 0 (:2197-2199)
cur = delta; out = cur
if xC: out.x = -cur.x * restitution        // with restitution 0 this yields ±0.0 (sign kept), not 0.0
if zC: out.z = -cur.z * restitution
bounced = restitution > 0 && (xC || zC)
if verticalCollision:
   if verticalCollisionBelow:
      restitution = (-cur.y <= getEffectiveGravity() || isSuppressingBounce() || effectState.is(SUPPRESSES_BOUNCE))
                    ? 0.0 : max(restitution, blockBounciness(effectState))           // LivingEntity: no 0.8 factor (:888-895)
   if restitution > 0:
      portion = movement.y / cur.y
      gComp = portion * getEffectiveGravity()
      drag  = Mth.lerp(portion, 1.0, (double) getAirDrag())                         // LivingEntity.getAirDrag :2494-2496
      bounced = true
   else gComp = 0; drag = 1
   out.y = (gComp - cur.y) * drag * restitution
if bounced: effectState.bounceOn(...) (sound only), gameEvent BOUNCE, syncPosition = true
delta = out
```

Bounce values (`world/level/block/Blocks.java`): slime `bounceRestitution(1.0F)` + friction `0.8F` (`:3039`);
beds and shelf mushroom `0.75F` + `fallDistanceReduction(0.5F)` (`:721-722`, `:2728-2729`). The `<=` gravity
test is new in 26.3 (26.2 used `<`).

### 6.3 Sneak edge back-off — `Player.maybeBackOffFromEdge` `Player.java:864-931`

```
maxDown = maxUpStep()
if !flying && !(delta.y > 0) && (type == SELF || type == PLAYER) && isStayingOnGroundSurface() && isAboveGround(maxDown):
    dx = delta.x; dz = delta.z; stepX = signum(dx)*0.05; stepZ = signum(dz)*0.05
    while dx != 0 && canFallAtLeast(dx, 0, maxDown):  if |dx| <= 0.05 {dx = 0; break}; dx -= stepX
    while dz != 0 && canFallAtLeast(0, dz, maxDown):  if |dz| <= 0.05 {dz = 0; break}; dz -= stepZ
    while dx != 0 && dz != 0 && canFallAtLeast(dx, dz, maxDown):
        dx = |dx| <= 0.05 ? 0 : dx - stepX;  dz = |dz| <= 0.05 ? 0 : dz - stepZ
    return (dx, delta.y, dz)
isAboveGround(m) = onGround() || fallDistance < m && !canFallAtLeast(0, 0, m - fallDistance)
canFallAtLeast(dx, dz, h) = noCollision(AABB(minX+1e-7+dx, minY-h-1e-7, minZ+1e-7+dz, maxX-1e-7+dx, minY, maxZ-1e-7+dz))
```

`isStayingOnGroundSurface()` = `isShiftKeyDown()` (`Player.java:300-302`) → **current** `keyPresses.shift()` on
`LocalPlayer` (`LocalPlayer.java:693-695`). Leaf can skip this step server-side
(`performance.optimize-player-movement`, `docs/revival/leaf-defaults.md:53`); the port must always run it.

### 6.4 Inside-block effects — `applyEffectsFromBlocks` `Entity.java:946-1018`, `checkInsideBlocks` `:1325-1428`

Called once per tick after `travel` (`LivingEntity.java:3138-3140`; the gate passes on the client).

```
final = movementThisTick (copy); movementThisTick.clear()
if final empty: final = [Movement(oldPosition(), position())]                      // no axis order
else if final.last.to.distanceToSqr(position()) > 9.999999439624929E-11: final.add(Movement(last.to, position()))
if isAffectedByBlocks() (!removed && !noPhysics):
   if onGround(): blockState(getOnPosLegacy()).stepOn(...)                         // slime: 6.4.1
   checkInsideBlocks(final, insideEffectCollector); insideEffectCollector.applyAndClear(this)
   (fire bookkeeping; rain extinguish; client never sets negative fire ticks)
```

`checkInsideBlocks` walks each movement: with an axis-dependent original movement, it splits the path per axis in
`axisStepOrder(originalDelta)` order, with a shared budget of 16 iterations; otherwise one segment. Each segment
uses `BlockGetter.forEachBlockIntersectedBetween(from, to, bbAtTarget.deflate(1e-5), visitor)`
(`world/level/BlockGetter.java:173-260`: start-box cells, then a DDA along the furthest corner with `AABB.clip`,
then target-box cells). Per cell: skip air; `insideBlock = shape == block() || collidedWithShapeMovingFrom(from,
to, shape AABBs)`; `insideFluid = collidedWithFluid(...)`; dedupe by `visitedBlocks`; for blocks call
`state.entityInside(level, pos, this, collector, isPrecise)` then `onInsideBlock`; for fluids
`fluidState.entityInside`. Stops when `!isAlive()`. The visit order decides the order of movement effects, so port
the traversal exactly. Note: a piston push after the player's tick (6.6) adds a `Movement` that is consumed by the
**next** tick's `applyEffectsFromBlocks`.

`InsideBlockEffectApplier.StepBasedCollector` (`world/entity/InsideBlockEffectApplier.java:32-…`) queues typed
effects (`FREEZE`, `CLEAR_FREEZE`, `FIRE_IGNITE`, `LAVA_IGNITE`, `EXTINGUISH`, `InsideBlockEffectType.java`) and
applies them per step in enum order. The only movement-relevant one is `FREEZE` → `setIsInPowderSnow(true)`, read
next tick through `wasInPowderSnow`.

#### 6.4.1 Block table (movement-relevant behaviour on the client)

| Block | Hook | Effect on movement | File:line |
|---|---|---|---|
| Cobweb | `entityInside` | `makeStuckInBlock((0.25, 0.05000000074505806, 0.25))`, with WEAVING `(0.5, 0.25, 0.5)` | `WebBlock.java:19-28` |
| Sweet berry bush | `entityInside` | `makeStuckInBlock((0.800000011920929, 0.75, 0.800000011920929))` for living entities except fox/bee (damage server-only) | `SweetBerryBushBlock.java:75-91` |
| Powder snow | `entityInside` | if `getInBlockState()` is powder snow: `makeStuckInBlock((0.8999999761581421, 1.5, 0.8999999761581421))`; FREEZE effect | `PowderSnowBlock.java:56-92` |
| Honey | `entityInside` | slide: if not on ground, `y <= pos.y+0.9375-1e-7`, `oldY = d.y/0.98+0.08 < -0.08`, and touching a side (`dx or dz + 1e-7 > 0.4375 + w/2`): `if oldY < -0.13: d = (d.x*k, newY(-0.05), d.z*k)` with `k = -0.05/oldY`, else `(d.x, newY(-0.05), d.z)`; `newY(v) = (v-0.08)*0.9800000190734863`; `resetFallDistance()` | `HoneyBlock.java:57-107` |
| Honey | factors | speed `0.4F`, jump `0.5F` | `Blocks.java:4760` |
| Soul sand | factor | speed `0.4F` | `Blocks.java:2143` |
| Ice, packed ice, frosted ice | friction | `0.98F` | `Blocks.java:2075`, `:3122`, `:3707` |
| Blue ice | friction | `0.989F` | `Blocks.java:4203` |
| Slime | friction / bounce / `stepOn` / `fallOn` | friction `0.8F`, restitution `1.0`; `stepOn`: if `|d.y| < 0.1 && !isSteppingCarefully()` (shift): `d.x,d.z *= 0.4 + |d.y|*0.2`; `fallOn`: fall damage ×0 unless shifting | `SlimeBlock.java:15-30`, `Blocks.java:3039` |
| Beds, shelf mushroom | bounce | restitution `0.75`, fall reduction `0.5` | `Blocks.java:721`, `:2728` |
| Bubble column | `entityInside` (only `isPrecise`) | nothing solid/fluid above: `onAboveBubbleColumn` → `y = dragDown ? max(-0.9, y-0.03) : min(1.8, y+0.1)`; else `onInsideBubbleColumn` → `y = dragDown ? max(-0.3, y-0.03) : min(0.7, y+0.06)` + `resetFallDistance()`. `Player` skips both while flying | `BubbleColumnBlock.java:46-57`, `Entity.java:2958-3011`, `Player.java:310-321` |
| Scaffolding | collision shape + climbable | context-dependent shape; no slide suppression inside | `ScaffoldingBlock.java:51-57`, `:130-140` |
| Ladders, vines, etc. | `CLIMBABLE` tag | `handleOnClimbable` | `LivingEntity.java:1742-1760` |

`makeStuckInBlock` (`Entity.java:3062-3065`): `resetFallDistance(); stuckSpeedMultiplier = m`. `Player` override
(`Player.java:1482-1488`) skips it while flying. The multiplier is consumed by the **next** `move()` (6.1 step 4).

### 6.5 Fluids — `Entity.updateFluidInteraction` `Entity.java:1699-1723`, `EntityFluidInteraction` (26.3 rewrite)

```
inAny = fluidInteraction.update(this, ignoreCurrent = !isPushedByFluid())   // Player.isPushedByFluid = !flying (Player.java:1658)
inWater = isInFluid(WATER); inLava = isInFluid(LAVA)
if inWater: resetFallDistance(); if !wasTouchingWater && !firstTick: doWaterSplashEffect()   // FX + RNG
wasTouchingWater = inWater
if isPushedByFluid():
   if inWater: applyCurrentTo(WATER, this, 0.014)
   if inLava:  applyCurrentTo(LAVA, this, FAST_LAVA dimension ? 0.007 : 0.0023333333333333335)
```

`EntityFluidInteraction.update` (`world/entity/EntityFluidInteraction.java:38-108`):

- Box = `getFluidInteractionBox()` = `bb.deflate(0.001)` (vehicle may modify, `Entity.java:4263-4272`).
- Cells `floor(min) … ceil(max)-1`, loop **x outer, y middle, z inner**.
- Bail out (everything 0) unless every chunk in the box ±1 x/z is loaded FULL and some section has fluid
  (`hasFluidAndLoaded`, `:110-138`).
- Per fluid cell with `fluidTop = y + fluidState.getHeight() >= box.minY`: a **tracker per fluid holder**
  (source and flowing water are different holders) and a **current accumulator per tag** (WATER, LAVA).
  Eyes: if `x == blockX && z == blockZ && eyeY >= y` and `eyeY <= y + getHeightForCamera()` (26.3: a source with
  a sturdy face above counts as full, `world/level/material/FluidState.java:53-62`).
  `tracker.height = max(fluidTop - bb.minY, tracker.height)`;
  `current.height = max(tracker.height, current.height)`; `flow = getFlow(...)`, scaled by `current.height` when
  `current.height < 0.4`; accumulate. The running max makes the result depend on the loop order.
- `applyTo` (`:215-233`): players use the **average** (`accumulated / count`; non-players normalize);
  `impulse = avg * scale`; if `|d.x| < 0.003 && |d.z| < 0.003 && |impulse| < 0.0045000000000000005`:
  `impulse = impulse.normalize() * 0.0045000000000000005`; `addDeltaMovement(impulse)`.
- `getFluidHeight(tag)` = max height of trackers whose holder is in the tag; `isInFluid` = height > 0;
  `isEyeInFluid` = any matching tracker with `eyesInside`.

Water/underwater predicates — **the client and server differ**:

- `isInWater()` = `wasTouchingWater` (`Entity.java:1646-1648`).
- `Entity.isUnderWater()` = `wasEyeInWater && isInWater()` (`:1667-1669`) — what `ServerPlayer` uses.
- `LocalPlayer.isUnderWater()` = `wasUnderwater` (`LocalPlayer.java:1207-1209`), set at the start of
  `Player.tick` from `isEyeInFluid(WATER)` of the **previous** fluid update (`Player.java:259`, `:304-307`).
  Used by `canStartSprinting` and `Entity.updateSwimming`.
- `isInShallowWater()` = `isInWater() && !isUnderWater()` (uses the override).
- `isInLava()` = `!firstTick && isInFluid(LAVA)` (`Entity.java:1803-1805`).

Swimming flag — `Entity.updateSwimming` (`Entity.java:1689-1697`, in `baseTick` after the fluid update;
`Player.updateSwimming` `:1400-1406` forces false while flying):

```
if isSwimming(): setSwimming(isSprinting() && isInWater() && !isPassenger())
else setSwimming(isSprinting() && isUnderWater() && !isPassenger() && fluidState(blockPosition()).is(WATER))
```

Hysteresis: the result depends on the previous flag, which the server's entity-data echo can overwrite (8.6).

### 6.6 Movers outside the player's own tick (client side)

These change the local player's `deltaMovement` or position on the client in the same client tick, outside
`LocalPlayer.tick`:

1. **Pushes from other entities** (6.7).
2. **Firework rocket while gliding**: the rocket entity's tick (`world/entity/projectile/FireworkRocketEntity.java:114-140`)
   sets the attached entity's delta: `d += (look*0.1 + (look*1.5 - d)*0.5)` per axis, every tick while
   `isFallFlying()`. Order relative to the player follows the client tick list.
3. **`tickBlockEntities` after the player tick, before `sendChanges`** (`Minecraft.java:1835`):
   - Potent sulfur geysers (26.x): `PotentSulfurBlockEntity.LAUNCH_ENTITY_TICKER` runs on the client too
     (`PotentSulfurBlock.java:140-163`, `PotentSulfurBlockEntity.java:115-133`): for each entity in the column,
     `checkFallDistanceAccumulation()`, then if `canSimulateMovement()`, not flying, not passenger, not
     `NOT_AFFECTED_BY_GEYSERS`, and `d.y < 0.30000001192092896 + waterBlocks*0.1`: `d.y += 0.20000000298023224`.
   - Moving pistons: `PistonMovingBlockEntity.tick` moves collided entities with `move(PISTON, …)` and sets
     slime-launch velocity on the client (`world/level/block/piston/PistonMovingBlockEntity.java:118-179`, `:291-317`).
   - Opening shulker boxes (`ShulkerBoxBlockEntity`, `MoverType.SHULKER_BOX`).
   These run **after** this tick's physics, so their effect shows in the position/velocity of the next tick (and a
   piston `move` adds to the next tick's inside-block movements).
4. **Shulker mob peek** (`MoverType.SHULKER`, `Shulker.java`).
5. **Riptide** `checkAutoSpinAttack` (`LivingEntity.java:3253-3274`): touching any entity → `d *= -0.2`.
6. **Own attack** (6.8).
7. **Block prediction revert snap** (8.4).

### 6.7 Pushing

- The local player **never pushes and is never pushed through its own `pushEntities`**:
  `ClientLevel.getPushableEntities(pusher, box)` returns only the local player, and only when
  `pusher != player` (`ClientLevel.java:542-546`).
- It **is** pushed when another entity ticks on the client and calls `push` on it:
  - every `LivingEntity` (mobs) via `LivingEntity.aiStep` → `pushEntities` (`LivingEntity.java:3168`, `:3227-3251`)
    → `doPush(e)` → `e.push(this)` (`:3276-3278`);
  - every remote player via `RemotePlayer.aiStep` → `pushEntities` (`client/player/RemotePlayer.java:43-62`);
  - boats (`AbstractBoat.java:279`) and minecarts (`OldMinecartBehavior.java:363`, `NewMinecartBehavior.java:521`, `:544`).
- Filter `EntitySelector.pushableBy(pusher)` (`world/entity/EntitySelector.java:29-55`): no spectators,
  `localPlayer.isPushable()` (`LivingEntity.java:3375-3379`: alive, not spectator, **not on a climbable**), and
  team collision rules of both sides as the **client** sees them (pusher's rule `NEVER` → nothing).
- Push math, `LivingEntity.push(Entity)` → skip while sleeping (`:2337-2341`) → `Entity.push(Entity)`
  (`Entity.java:1937-1966`), with `this` = local player, `e` = pusher:
  ```
  if isPassengerOfSameVehicle(e) or either noPhysics: return
  xa = e.x - x; za = e.z - z; dd = max(|xa|, |za|)
  if dd >= 0.009999999776482582:
     dd = sqrt(dd); xa /= dd; za /= dd
     pow = 1.0 / dd; if pow > 1.0: pow = 1.0
     xa *= pow; za *= pow
     xa *= 0.05000000074505806; za *= 0.05000000074505806     // two separate multiplies, keep the order
     if !isVehicle() && isPushable(): push(-xa, 0, -za)        // d += (-xa, 0, -za), Entity.java:1978-1983
  ```
- Positions are the pushers' **client-interpolated** positions, and the effect lands before or after the local
  player's physics depending on tick-list order (`Int2ObjectLinkedOpenHashMap` insertion order,
  `world/level/entity/EntityTickList.java:13`): entities added before the local player tick first.
- RevivalPaper can remove the player-player case with Paper's `collisions.enable-player-collisions: false`: every
  team packet is rewritten to `NEVER` and team-less players are put on a hidden `collideRule_*` team
  (`leaf-server/.../ClientboundSetPlayerTeamPacket.java:190-193`, `MinecraftServer.java:689-693`,
  `PlayerList.java:327-328`). Mob pushes remain.

### 6.8 Client-side attack effects on the attacker (`Player.attack`, runs on the client)

`MultiPlayerGameMode.attack` (`MultiPlayerGameMode.java:430-438`) sends `ServerboundAttackPacket`, then calls
`player.attack(target)` locally, then `resetAttackStrengthTicker()`. This happens in step 3 of the tick, **before**
the movement step. In `Player.attack` (`Player.java:933-989`):

```
strength = getAttackStrengthScale(0.5F)          // attackStrengthTicker vs ATTACK_SPEED (syncable)
knockbackAttack = isSprinting() && strength > 0.9F
wasHurt = target.hurtOrSimulate(...)             // client: hurtClient(); RemotePlayer -> true (RemotePlayer.java:32-34); mobs -> false
if wasHurt: causeExtraKnockback(target, getKnockback(...) + (knockbackAttack ? 0.5F : 0), ...)
causeExtraKnockback (Player.java:1104-1131): if amount > 0: delta *= (0.6, 1.0, 0.6); setSprinting(false)
getKnockback on the client = ATTACK_KNOCKBACK / 2 (no enchantments) (LivingEntity.java:1558-1563); ATTACK_KNOCKBACK is not syncable -> base 0 for players
```

So a sprinting, full-strength hit on a player multiplies the attacker's horizontal velocity by 0.6 and stops
sprinting **on the client**, once per `Attack` packet (several per tick are possible, `Minecraft.java:1979-1981`).
The `STOP_SPRINTING` for it is sent at the end of the tick. RevivalPaper's sprint ledger must treat this as part of
the authoritative simulation, not as a server-only decision.

---

## 7. State the simulation needs, and where it comes from

### 7.1 Attributes read by movement

| Attribute | Default | Syncable | Read at |
|---|---|---|---|
| `movement_speed` | player base `0.10000000149011612` (`Player.java:208`) | yes | `Player.getSpeed`, `travelInWater` |
| `sneaking_speed` | 0.3 | yes | `modifyInput` |
| `jump_strength` | 0.41999998688697815 | yes | `getJumpPower` |
| `step_height` | 0.6 | yes | `maxUpStep`, edge back-off |
| `gravity` | 0.08 | yes | `getDefaultGravity` |
| `scale` | 1.0 | yes | `getDimensions` (`LivingEntity.java:3762-3764`), refresh at end of tick |
| `movement_efficiency` | 0 | yes | block speed factor lerp |
| `water_movement_efficiency` | 0 | yes | `travelInWater` |
| `friction_modifier` (new) | 1.0 | yes | ground friction |
| `air_drag_modifier` (new) | 1.0 | yes | air drag, restitution drag |
| `bounciness` (new) | 0.0 | yes | restitution |
| `attack_speed` | 4.0 | yes | client attack strength (6.8) |
| `attack_knockback` | 0 | **no** | client attack slowdown (6.8) |
| `safe_fall_distance`, `fall_damage_multiplier` | 3, 1 | yes | fall damage only |

Definitions: `world/entity/ai/attributes/Attributes.java`. Client value computation
(`AttributeInstance.java:149-167`): `base + Σ ADD_VALUE`, then `+ base×Σ ADD_MULTIPLIED_BASE`, then
`× Π(1 + ADD_MULTIPLIED_TOTAL)`, then `RangedAttribute.sanitizeValue` (NaN → min, clamp). Per-operation maps are
`Object2ObjectOpenHashMap<Identifier, …>` (`:53-55`); iteration order (and so floating-point product order with
several `ADD_MULTIPLIED_TOTAL` modifiers such as sprint ×1.3, Speed, Slowness) depends on hashing and the exact
add/remove history. The client's history is: `handleUpdateAttributes` → `setBaseValue`, `removeModifiers()`,
`addTransientModifier` for each modifier **in packet order** (`ClientPacketListener.java:2368-2392`), plus its own
`setSprinting` remove/add of `sprinting`. Leaf changes the server's maps to insertion-ordered `ArrayMap`s
(`0149-optimize-attribute.patch`), so the server's own `getValue()` is not a valid stand-in.

Client attribute changes come **only** from server packets plus the local sprint modifier: equipment modifiers
(`LivingEntity.java:2762-2795`, `:2952-2991`), effect modifiers (`:1087-1125`), powder-snow frost
(`:3147-3160`, `:536-558`) are applied server-side and synced. `SCALE` changes refresh dimensions only in
`refreshDirtyAttributes` at the **end** of the next `LivingEntity.tick` (`:2868`, `:1135-1170`;
`AttributeMap.java:26-31`).

### 7.2 Effects, equipment, abilities, game mode, food

- Effects: client `activeEffects` change only through `ClientboundUpdateMobEffectPacket`/`RemoveMobEffect`
  (`ClientPacketListener.java:1808`, `:1993`). `tickClient` (`MobEffectInstance.java:243-250`) counts durations
  down but **never removes** an effect; it can downgrade to a hidden effect locally when the top one runs out
  (amplifier change without a packet). Read by movement: `JUMP_BOOST` (amplifier), `SLOW_FALLING`, `LEVITATION`
  (amplifier), `DOLPHINS_GRACE`, `BLINDNESS` (sprint), `WEAVING` (cobweb). Speed/Slowness act through attributes.
- Equipment (client copies from inventory/equipment packets): main hand (collision context for scaffolding,
  attack-strength reset in `Player.tick` `:270-277`, `updatingUsingItem` `LivingEntity.java:3437-3446`), feet
  (leather boots for powder snow), any slot glider (`canGlide`), the used stack (`USE_EFFECTS`).
- Abilities: `ClientboundPlayerAbilitiesPacket` sets everything (`ClientPacketListener.java:2102-2111`); the client
  changes `flying` itself (3, steps 11 and 19) and reports it.
- Game mode: `Player.isSpectator()` reads the **tab-list** mode (`AbstractClientPlayer.java:32-35`,
  `PlayerInfoUpdate`), while flight forcing reads `MultiPlayerGameMode` local mode. Switching to spectator zeroes
  `delta.y` (`LocalPlayer.java:1200-1204`, called from `ClientPacketListener.java:2045`).
- Food: `ClientboundSetHealthPacket` (`ClientPacketListener.java:1244-1249`), read as `foodLevel > 6`.

### 7.3 Hidden client state and non-determinism

Kept on the client, never sent, but derivable from the input stream and server packets (keep them in the sim):

`sprintTriggerTime`, `jumpTriggerTime`, `noJumpDelay`, `autoJumpTime`/`wasAutoJump` (only matters with `mayfly`),
`crouching`, `wasFallFlying`, `startedUsingItem`/`usingItemHand`/`useItem`/`useItemRemaining`,
`attackStrengthTicker`/`lastItemInMainHand`, `xxa`/`zza`/`jumping`, previous `keyPresses`, `stuckSpeedMultiplier`,
`fallDistance`, `isInPowderSnow`/`wasInPowderSnow`, `wasTouchingWater`/`wasEyeInWater`/`wasUnderwater`, fluid
trackers, `onGround`/collision flags/`minorHorizontalCollision`, `mainSupportingBlockPos`/`onGroundNoBlocks`,
`movementThisTick`, pose and dimensions, `firstTick`, `autoSpinAttackTicks`, attribute-map history, effect
durations/hidden chains, `lastSentInput`/`wasSprinting` (decide when packets are sent), `inBlockState` cache.

Truly unknown or ambiguous to the server:

1. **Client options**: `autoJump` (its *result* is in `Input.jump`), `sprintWindow` (result is in the sprint
   command), toggle-sneak/sprint (result in `Input`), mouse sensitivity (result in rotation). `autoJumpEnabled`
   starts `true` and is refreshed from options only at the end of `sendPosition` (`LocalPlayer.java:160`, `:330`),
   so the first loaded tick auto-jumps even with the option off — still visible in `Input`.
2. **Hotbar timing** (1.2): one-tick ambiguity on which tick first used a new slot.
3. **Pushes, rockets, entity colliders** depend on other entities' client-interpolated positions and on the client
   tick-list order.
4. **`Math.sin`/`Math.cos`**: `Mth`'s table is built with `Math.sin` (`util/Mth.java:35-39`) and elytra uses
   `Math.cos` directly; intrinsics may differ by 1 ulp between JVMs/CPUs. Table values are rounded to float, so a
   difference rarely survives, but it is not guaranteed.
5. **Frame batching**: several ticks per frame share one packet-application point (1.1); transactions make this
   observable after the fact, not predictable.
6. **Client-only chunk state**: `travelInAir`'s `-0.1` branch and `hasFluidAndLoaded` depend on which chunks the
   client holds (chunk/forget packets).

RNG: movement math uses none. `this.random`/`level.getRandom()` calls on the path are FX only (portal sound
`LocalPlayer.java:974`, sprint particles `Entity.java:1779-1797`, splash `:1725-1764`, effect particles
`LivingEntity.java:881-887`, honey slide `HoneyBlock.java:109-119`).

---

## 8. Server→client changes the local simulation must apply at the right tick

All are applied by the client at frame start (1.1). With ping/pong brackets the server knows the first tick that
saw each one.

1. **Position** `ClientboundPlayerPositionPacket` (`ClientPacketListener.java:794-829`): not while riding;
   `setPos`, `setDeltaMovement(new delta)`, rotation, old pos; then `AcceptTeleportation(id, x, y, z, yRot, xRot)`.
   Everything else (onGround, fallDistance, collision flags, sprint, stuck multiplier) is kept.
2. **Velocity** `ClientboundSetEntityMotionPacket` → `Entity.lerpMotion` = `setDeltaMovement` for the local player
   (`:631-637`, `Entity.java:2755-2757`). **Explosion** knockback → `pushFromExplosion` = `push` (adds,
   `:1388`, `Entity.java:1974-1976`).
3. **Blocks/chunks**: block updates, section updates, chunk load/forget, world border (tick-lerped in
   `level.tick`, after `sendChanges`).
4. **Block prediction**: blocks the client places/breaks are changed locally at once; server updates for those
   positions are held until `ClientboundBlockChangedAck` (`ClientLevel.java:184-208`,
   `client/multiplayer/prediction/BlockStatePredictionHandler.java:18-51`). On ack, a reverted block that the
   player now collides with **snaps the player** back to its position at prediction time
   (`ClientLevel.java:210-219`, unless a teleport happened since). Bridging/pillaring depend on this.
5. **Attributes** (7.1), **effects** (7.2), **abilities**, **food**, **game mode**, **teams** (push rules).
6. **Entity-data echo of self** (`ServerEntity.java:370-376` → `sendToTrackingPlayersAndSelf`): the client
   assigns the whole shared-flags byte (`Entity` flags 1 shift, 3 sprint, 4 swim, 7 glide; plus fire/invisible/
   glowing) and `DATA_POSE` (`SynchedEntityData.java:110-118`; pose change → `refreshDimensions` immediately,
   `Entity.java:3520-3550`). The local player's `isShiftKeyDown()` ignores flag 1 (override), but sprint, swim,
   glide and pose are taken from the echo until the client changes them again. Also `DATA_LIVING_ENTITY_FLAGS`
   (using item) → `LocalPlayer.onSyncedDataUpdated` starts/stops item use (`LocalPlayer.java:597-612`), entity
   event 9 completes use (`Player.java:413-415`), and `DATA_NO_GRAVITY`.
   Recommendation: for the player's own connection, rewrite the flags byte and pose to the values the simulation
   predicts for the client at arrival, or drop the self copy when only client-owned bits changed, and send
   deliberate corrections inside ping brackets. The same applies to the self `UpdateAttributes` for
   `movement_speed` (the sprint modifier inside it).

---

## 9. Why the port cannot reuse RevivalPaper's `Entity`/`LivingEntity`

Branches that take a different path on a server (vanilla, before any fork patches):

| Location | Condition | Client (local player) | Server (`ServerPlayer`) |
|---|---|---|---|
| `Entity.move` `:799` | `movedVertically \|\| isLocalInstanceAuthoritative()` | always updates `onGround`/vertical flags | only when `delta.y != 0` |
| `Entity.move` `:813` | `isLocalInstanceAuthoritative()` | `checkFallDamage` per move (fall distance, `fallOn`, mid-move fluid update) | skipped (done later via `doCheckFallDamage`) |
| `Entity.move` `:820` | `canSimulateMovement()` | restitution | restitution |
| `Entity.move` `:824` | `!isClientSide \|\| localAuth` | step sounds/events | same (FX) |
| `Entity.commonTick` `:533` | client | interpolation step | none |
| `Entity.baseTick` `:563-586` | server | `clearFire()` | fire damage/ticks |
| `Entity.handlePortal` `:2723` | server | none | portal teleport |
| `LivingEntity.travelInAir` `:2474` | `!isClientSide \|\| hasChunkAt(below)` | `-0.1`/`0` if chunk missing | always gravity |
| `LivingEntity.travelFallFlying` `:2588` | server | — | wall damage |
| `LivingEntity.updateFallFlying` `:3190` | server | only `checkFallDistanceAccumulation` | stops gliding, durability |
| `LivingEntity.aiStep` `:3045` | `!canSimulateMovement()` | — | — |
| `LivingEntity.aiStep` `:3086` | server | — | `serverAiStep` |
| `LivingEntity.aiStep` `:3138` | `!client \|\| localAuth` | inside-block effects | same |
| `LivingEntity.aiStep` `:3147` | server | — | freezing, frost speed modifier |
| `LivingEntity.tick` `:2762` | server | — | equipment attribute modifiers |
| `LivingEntity.tickEffects` `:860` | server | durations--, no removal | effect ticks, removal |
| `LivingEntity.updateUsingItem` `:3485` | server | never completes use | completes use |
| `LivingEntity.getKnockback` `:1560` | server | attribute/2 | + enchantments |
| `Entity.handleOnAbove/InsideBubbleColumn` `:2963`, `:2998` | `canSimulateMovement()` | applies | applies |
| `ClientLevel.getPushableEntities` vs `Level` | client | local player only | all pushables |
| `Player.isClientAuthoritative` / `isLocalClientAuthoritative` `Player.java:1245-1252` | — | `isLocalInstanceAuthoritative() = true` | `false` |

`LocalPlayer` overrides that `ServerPlayer` does not have: `isShiftKeyDown` (current input), `isCrouching`
(`crouching` field, previous-tick shift), `isUsingItem` (`startedUsingItem`), `isUnderWater` (`wasUnderwater`),
`isHorizontalCollisionMinor` (real angle test), `isSuppressingSlidingDownLadder` (`!flying`), `applyInput`,
`aiStep`, `move` (auto-jump), `getViewYRot`.

RevivalPaper's server copies also diverge numerically (see `docs/revival/leaf-defaults.md:157-205`):

- Paper/Moonrise replaces `Entity.collide` with `CollisionUtil` (`leaf-server/src/minecraft/java/net/minecraft/world/entity/Entity.java:1802-1862`), plus Leaf's collision-list cache that casts `level` to `ServerLevel`.
- Gale `0051-Skip-entity-move-if-movement-is-zero`: zero delta returns early with `onGround = false`.
- Gale `0070-Skip-negligible-planar-movement-multiplication`: skips the speed-factor multiply when `|d.x|,|d.z| <= 1e-6` or the factor is within 1e-6 of 1.
- Leaf `0263-optimize-movement-vector-normalization`: different arithmetic for the long-move fall clip.
- Lithium `0045-Replace-division-by-multiplication-in-CubePointRange`: `index * (1.0/parts)` instead of `index / parts` (differs by an ulp for non-power-of-two `parts`).
- Leaf `0149-optimize-attribute`, `0014` (live collision context), `0201` (skip edge back-off), `0226`/`0228`/`0196` (inside-block checks), `0247` (`getOnPos`), `0295` (fluid map).
- Paper wraps the client's yaw (`leaf-server/.../ServerGamePacketListenerImpl.java:1585`, `:1556`).
- Vanilla `ServerPlayer.doTick` (`server/level/ServerPlayer.java:687-690`) runs `Player.tick` → `travel` with
  zero input every server tick. The authoritative simulation replaces this; leaving both running moves the player twice.

Conclusion: the simulator must be plain source under `gg.hcfrevival.revival` (as `AGENTS.md` requires) carrying
its own copies of the vanilla 26.3 math listed in this document, reading only raw world data (block states, fluid
states, shape coordinate lists) from the server.

---

## 10. Side effects to skip in a server simulation

Sounds and particles: step/swim/splash/fall sounds (`Entity.java:909-944`, `:1472-1540`, `:1725-1764`;
`LivingEntity.java:1825-1826`), sprint particles (`Entity.java:1779-1797`), honey slide FX (`HoneyBlock.java:45-54`,
`:109-119`), effect particles, ambient/portal sounds, bob/walk animation/body rotation, water vision, item activation.

Game events: `STEP`, `SWIM`, `SPLASH`, `HIT_GROUND`, `BOUNCE`, `FLAP`, `ELYTRA_GLIDE`.

World/entity mutations owned by the real server: `fallOn` damage and farmland trampling (`FarmlandBlock`), turtle
eggs, dripstone, magma/cactus/campfire/berry damage, frost walker (location-changed enchantment effects run
server-side only: `LivingEntity.java:475-479`, `:560-562`), big dripleaf tilt, pressure plates/tripwire, powder-snow
`destroyBlock`, `causeFallDamage`/`hurt`, durability, stats, advancements, `level.playSound`, `bounceOn` sounds,
`Leashable.tickLeash`, portal processing, effect application, air supply.

Keep (they change movement inputs): `fallDistance` bookkeeping, `resetFallDistance` calls, `setIsInPowderSnow`,
`makeStuckInBlock`, `lastClimbablePos` can be dropped, `moveDist`/`nextStep` can be dropped (only sounds use them).

---

## 11. Proposed port

### 11.1 Placement and test strategy

- `leaf-server/src/main/java/gg/hcfrevival/revival/auth/sim/` (plain source, no NMS entity subclassing).
  One `ClientMovementSim` per player, fed by the packet bundle of each client tick.
- Differential tests: the 26.3 client jar (`~/Developer/tools/mc-src/client-26.3.jar`) contains every common
  class; a test can load it in an isolated class loader and drive vanilla `Entity.move`/`collide`/`travel` on a
  synthetic `Level` to compare bit-for-bit with the port. Recorded traces (bundles + server packets + the client's
  reported position each tick) give a second, end-to-end check: the target is zero corrections for honest clients.

### 11.2 Minimal state struct (`ClientPlayerState`)

```java
// kinematics
double x, y, z;  double xo, yo, zo;           // xo/yo/zo = position at commonTick
double dx, dy, dz;                             // deltaMovement
float yRot, xRot;                              // raw client floats (never wrapped)
AABB bb; float width, height, eyeHeight; Pose pose; boolean firstTick;
// contact
boolean onGround, horizontalCollision, verticalCollision, verticalCollisionBelow, minorHorizontalCollision;
BlockPos mainSupportingBlockPos /*nullable*/; boolean onGroundNoBlocks;
double fallDistance; double stuckX, stuckY, stuckZ;
List<Movement> movementThisTick;               // from, to, axisDependentOriginalMovement (nullable)
// fluids
FluidTrackers fluids;                          // per fluid holder: height, eyesInside; per tag current accumulator
boolean wasTouchingWater, wasEyeInWater, wasUnderwater, isInPowderSnow, wasInPowderSnow;
// input
Input keys, prevKeys; float moveX, moveY; float xxa, zza; boolean jumping;
// timers and client fields
int noJumpDelay, jumpTriggerTime, sprintTriggerTime, autoJumpTime; boolean crouching, wasFallFlying;
// flags (as the CLIENT holds them; echoes overwrite them)
boolean sprinting, swimming, fallFlying, noGravity; boolean noPhysics;
// abilities / mode
boolean flying, mayfly; float flyingSpeed; boolean tabListSpectator, localModeSpectator;
// item use + attack
boolean usingItem; Hand usingHand; ItemView useItem; int useItemRemaining;
int attackStrengthTicker; ItemView lastMainHand;
// attributes and effects as the client holds them
ClientAttributeMap attributes;                 // vanilla AttributeInstance semantics + pending SCALE refresh
ClientEffects effects;                         // amplifier/duration/hidden chain per effect
ItemView mainHand, feet; boolean hasUsableGlider; int foodLevel;
boolean sleeping, alive, passenger;            // v1: passenger => fall back to vanilla server handling
int autoSpinAttackTicks;
```

### 11.3 World-query interface (the client's view, per player)

```java
interface ClientWorldView {
  BlockState blockState(int x, int y, int z);            // server world + per-player overlay:
  FluidState fluidState(int x, int y, int z);            //   not-yet-applied server changes, predicted blocks
  boolean hasChunkAt(int blockX, int blockZ);            // client-loaded chunks (travelInAir -0.1 branch)
  boolean chunkFullLoaded(int chunkX, int chunkZ);       // hasFluidAndLoaded
  boolean sectionHasFluid(int chunkX, int sectionY, int chunkZ);
  WorldBorderView border();                              // center/size/lerp as the client ticks it
  List<AABB> solidEntityBoxes(AABB area, SimContext c);  // boats, shulkers, happy ghasts at client-interpolated positions
  List<PushSource> pushersInTickOrder();                 // optional; or disable via team NEVER
  int minY();
}
```

Derived helpers implemented in the sim from vanilla code: `collisionShape(state, pos, ctx)` (needs `ctx` =
descending, entityBottom, heldItem, fallDistance, feet item), `BlockCollisions` iteration, `findSupportingBlock`,
`noCollision`, `collidesWithSuffocatingBlock`, `containsAnyLiquid`, `clip(FALLDAMAGE_RESETTING, WATER)`,
`forEachBlockIntersectedBetween`, fluid `getHeight`/`getHeightForCamera`/`getFlow`, and block constants (friction,
speed, jump, bounce, tags `CLIMBABLE`, `CAN_GLIDE_THROUGH`, `SUPPRESSES_BOUNCE`, `FENCES`, `WALLS`,
`FALLDAMAGE_RESETTING`, trapdoor/ladder facing).

### 11.4 Per-tick procedure (one call per `ServerboundClientTickEndPacket`)

Inputs: the ordered bundle of serverbound packets since the previous TickEnd, and the server→client changes the
transaction layer says were applied before this tick.

```
0.  apply server->client changes due before this tick, in arrival order            [frame start, Minecraft.java:1202]
1.  bundle pre-physics packets in order: SetCarriedItem, PlayerAction(release/swap/drop),
    Attack (6.8: x0.6 + sprint=false when the client predicate holds), UseItem/UseItemOn (start use if the client
    would predict it), block place/break predictions -> world overlay                 [Minecraft.java:1808-1822]
2.  if !clientLoaded: return (TickEnd only)
3.  commonTick: xo,yo,zo = pos                                                         [Entity.commonTick]
4.  Player.tick prologue: noPhysics = spectator; if spectator||passenger onGround=false;
    wasUnderwater = eyeInWater(prev fluid state)                                       [Player.java:231-259]
5.  baseTick: wasInPowderSnow = isInPowderSnow; isInPowderSnow = false; wasEyeInWater = eyeInWater;
    updateFluidInteraction (heights, current push, fall reset); updateSwimming;
    if inLava fallDistance *= 0.5; firstTick = false                                   [Entity.java:540-594]
6.  effects: durations-- / hidden downgrade                                            [LivingEntity.tickEffects]
7.  updatingUsingItem (stop if hand item type changed)                                 [LivingEntity.java:3437]
8.  LocalPlayer.aiStep steps 1..17 (section 3), using bundle Input as keys
      cross-check: sprint state vs START/STOP_SPRINTING in bundle; flying vs Abilities; glide vs START_FALL_FLYING
9.  Player.aiStep: jumpTriggerTime--, flying -> resetFallDistance                      [Player.java:442-453]
10. LivingEntity.aiStep: noJumpDelay--, velocity floor, applyInput (2.2), immobile, jump, glide check,
    slow-fall/levitation fall reset, Player.travel (5), applyEffectsFromBlocks (6.4), riptide
11. LocalPlayer.aiStep step 19 (landing ends flight)
12. LivingEntity.tick tail: refresh dirty attributes (SCALE -> dimensions)             [LivingEntity.java:2868]
13. Player.tick tail: clamp x/z; attack ticker; main-hand reset; updatePlayerPose      [Player.java:261-284]
14. external movers in client tick order: rockets, pushes, geysers/pistons             [6.6, 6.7]
15. compare with the bundle's MovePlayer (pos/onGround/horizontalCollision); on divergence beyond tolerance:
    send ClientboundPlayerPositionPacket with position + delta, inside a ping bracket
16. world border / client time tick                                                    [Minecraft.java:1859]
```

Out of scope for v1 (fall back to today's server handling or correct loosely): riding, creative flight edge cases
(`wasAutoJump`), riptide, spectator camera, happy ghast colliders.
