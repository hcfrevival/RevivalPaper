# 02 · Vanilla 26.3 client netcode: what it sends, how it applies, and the timing rule

Scope: the vanilla 26.3 Java client (`client-26.3.jar`, decompiled). This report maps, for an authoritative server:

1. every serverbound packet a client tick can produce, in order;
2. how and when the client applies each movement-relevant clientbound packet;
3. the exact rule for turning "server sent X, then ping N" into "client applied X before client tick T", and the per-tick wire sequence.

Citations: `src-26.3/...` and `src-26.2/...` are relative to `~/Developer/tools/mc-src/`. `RP/...` is relative to `~/Developer/GitHub/RevivalPaper/`. Line numbers are from the decompiled sources. All paths under `src-26.3/` are under `net/minecraft/` unless they start with `com/`.

---

## 0. The rules in brief

- **One thread applies everything.** Almost every clientbound play packet is queued from the Netty thread to the main thread (`PacketUtils.ensureRunningOnSameThread`, `src-26.3/network/protocol/PacketUtils.java:21-26`). The main thread drains that queue once per frame, at the start of `runTick`, **before** any client tick of that frame (`src-26.3/client/Minecraft.java:1202`). Nothing is applied in the middle of a tick, so no packet can land between the entity tick and `sendChanges`.
- **A frame runs 0 to 10 ticks back to back after one drain** (`Minecraft.java:1215`). Two or more ticks in one frame see the same drained state and the same rotation (the mouse turn is applied after the ticks, `Minecraft.java:1236`).
- **Each tick ends with `ServerboundClientTickEndPacket`** whenever a level exists (`Minecraft.java:1884-1886`). It is still sent while a screen is open, before the client has "loaded", and while dead. On a dedicated server `pause` is always false (`Minecraft.java:1254`).
- **Order inside one tick:** `gameMode.tick` (held-slot sync) → `pick(1.0F)` → keybinds (attack, use, drop, release) → entity tick (movement) → block-entity tick → `sendChanges` (input, sprint, move) → ... → tick end (`Minecraft.java:1795-1894`). **New in 26.3:** `sendChanges` moved out of `LocalPlayer.tick()` to after all entities and block entities have ticked (`Minecraft.java:1833-1839`, versus `src-26.2/client/player/LocalPlayer.java` `tick()`).
- **Pong is a main-thread reply, written in the drain.** So a pong always falls **between** two ticks' packet groups, in the order it was drained (`ClientCommonPacketListenerImpl.java:154-157`). KeepAlive and chunk-batch replies are written from the **Netty thread** and are **not** ordered with the tick stream (`ClientCommonPacketListenerImpl.java:149-151`, `ClientPacketListener.java:2490-2493`).
- **Timing rule (section 6):** cut the client stream into windows `W_T = (TickEnd(T-1), TickEnd(T)]`. If `Pong(N)` is in `W_T`, everything the server sent before `Ping(N)` was applied before client tick `T` ran, and tick `T`'s attack ray, keybinds and movement all saw it. If the server also sent `Ping(M)` just before `X` and `Pong(M)` is in `W_U`, then `X` was not visible to any tick before `U`. When `U == T` the tick is known exactly.
- **Attacks:** the ray is cast at the start of tick `T`, before tick `T`'s movement. It uses the eye at `lerp(1.0, xo, x)` (the end of tick `T-1`) and the exact current rotation. That rotation is the one tick `T`'s move packet will carry, but the move packet arrives **after** the `ServerboundAttackPacket` in the same window. 26.3 sends `ServerboundAttackPacket(entityId)` then `ServerboundPunchPacket` (no hand). `ServerboundSwingPacket` no longer exists.
- **Lossy self-velocity:** `ClientboundSetEntityMotionPacket` is encoded with `LpVec3` (15 bits per axis). The client applies the **quantized** value. `ClientboundPlayerPositionPacket` (doubles) and `ClientboundExplodePacket.playerKnockback` (doubles, additive) are lossless.
- **Self-echo hazards:** the server echoes the player's own entity data (sprint flag, pose, using-item flag) and attributes (with `removeModifiers()`, so the client's own sprint-speed modifier is wiped) back to that player. Each echo changes client movement state at drain time.

---

## 1. Threads, the frame loop and when packets are applied

### 1.1 The frame (main loop)

`Minecraft.run` loop, once per frame (`Minecraft.java:923-935`):

1. `RenderSystem.pollEvents(...)` (`:931`). Input events land here: `KeyMapping.click` raises `clickCount` (`client/KeyMapping.java:32-34`), key up/down state changes, mouse deltas are added to `accumulatedDX/DY`, and a scroll wheel changes the selected hotbar slot **immediately** (`client/MouseHandler.java:203`). GUI callbacks for an open screen (container clicks, chat, respawn button, closing a screen) send their packets **here**, between ticks.
2. `runTick(true)` (`:932`, body `:1184-1261`):
   1. `ticksToDo = deltaTracker.advanceGameTime(Util.getMillis())` (`:1197`).
   2. **Drain:** `packetProcessor.processQueuedPackets()` (`:1202`), then `runAllTasks()` (`:1204`).
   3. **Ticks:** `for (i = 0; i < Math.min(10, ticksToDo); i++) this.tick();` (`:1215-1221`).
   4. `mouseHandler.handleAccumulatedMovement()` (`:1236`) applies the mouse turn (`MouseHandler.java:256-305`, `turnPlayer :323-350`, which calls `Entity.turn`, `world/entity/Entity.java:506-518`).
   5. `renderFrame` (`:1241`). This includes a per-frame `pick(partialTick)` (`:1316-1318`). That pick is for the crosshair only. Attacks use the pick that `tick()` recomputes before keybinds (section 2.2).

`PacketProcessor.processQueuedPackets` empties a `ConcurrentLinkedQueue` until it is empty (`network/PacketProcessor.java:34-40`). Packets that arrive on the Netty thread **during** the drain are handled in the same drain. A packet that arrives after the drain waits for the next frame's drain, which may come after zero or many ticks.

### 1.2 Tick rate and dropped ticks

- `DeltaTracker.Timer.advanceGameTime`: `deltaTicks = (now - lastMs) / targetMspt`; the residual accumulates; it returns `(int) residual` and subtracts that from the residual (`client/DeltaTracker.java:56-63`). `Util.getMillis()` is based on `System.nanoTime` (`util/Util.java:114`).
- `targetMspt = getTickTargetMillis(50)`: when the level runs normally this is `max(50, tickRateManager.millisecondsPerTick())`, otherwise 50 (`Minecraft.java:2969-2978`, timer built at `:295`). **The client never ticks faster than 20 TPS.** A server `/tick rate 10` slows it to 10 TPS. A frozen game keeps it at 20.
- Owed ticks above 10 in one frame are **dropped**: the residual was already reduced by the full `ticks` (`DeltaTracker.java:60-61` with `Minecraft.java:1215`). After a stall the client runs a burst of up to 10 ticks. It never runs more ticks than wall time allows.
- Frame caps that cause tick bursts (`com/mojang/blaze3d/platform/FramerateLimitTracker`, read with `javap`; not in the decompiled tree): `ICONIFIED_WINDOW_LIMIT = 10` fps, `AFK_LIMIT = 30` fps after `AFK_THRESHOLD_MS = 60000`, `LONG_AFK_LIMIT = 10` fps after `600000` ms, `OUT_OF_LEVEL_MENU_LIMIT = 60`. At 10 fps each frame runs about 2 ticks back to back.
- `pause` is only true in singleplayer (`hasSingleplayerServer() && gui.isPausing() && !isPublished()`, `Minecraft.java:1254`). It is never true on a dedicated server.

### 1.3 Which thread applies a clientbound packet

`Connection.channelRead0` calls `packet.handle(listener)` on the Netty thread (`network/Connection.java:147-167`). Almost every play handler begins with `PacketUtils.ensureRunningOnSameThread(packet, this, minecraft.packetProcessor())`. That call enqueues the packet and throws `RunningOnDifferentThreadException`, which is swallowed (`PacketUtils.java:21-26`, `Connection.java:156`). The packet is then applied in the next drain.

Handlers that run **on the Netty thread** with no queueing (I scanned `ClientPacketListener` and `ClientCommonPacketListenerImpl` for handlers whose first statement is not `ensureRunningOnSameThread`):

| Handler | Effect | Reply sent from Netty thread |
|---|---|---|
| `handleKeepAlive` (`ClientCommonPacketListenerImpl.java:149-151`) | `sendWhen(KeepAlive, () -> !RenderSystem.isFrozenAtPollEvents(), 1 min)` | `ServerboundKeepAlivePacket` immediately; deferred to `ClientPacketListener.tick` only while render is frozen (`:345-357`, `:395-401`) |
| `handleChunkBatchStart` / `handleChunkBatchFinished` (`ClientPacketListener.java:2485-2493`) | batch timing | `ServerboundChunkBatchReceivedPacket` immediately, **before** the chunks themselves are applied on the main thread |
| `handlePongResponse` (`:2501`), `handleDebugSample` (`:2496`), `handleLowDiskSpaceWarning` (`:2553`), combat enter/end (no-ops, `:1850-1856`), `handleDisconnect`, `handleTransfer`, unknown custom payloads | not movement | none relevant |

**Consequence:** KeepAlive and ChunkBatchReceived replies are written straight to the channel inside `channelRead`. Main-thread writes go through `eventLoop().execute(...)` tasks (section 1.4), so these Netty-thread replies can be written **ahead of** main-thread packets that were already "sent" but not yet flushed. Do not use either one as a transaction marker.

### 1.4 How serverbound packets are written

`Connection.send(packet)` calls `send(packet, null, flush=true)`, which runs `flushQueue()` and then `sendPacket` (`Connection.java:274-288`). `sendPacket` calls `doSendPacket` directly when already on the event loop, and otherwise uses `channel.eventLoop().execute(() -> doSendPacket(...))` (`:300-307`). `doSendPacket` calls `writeAndFlush` (`:309-318`). Main-thread sends are therefore FIFO tasks on the event loop and keep **program order** exactly. Every packet is flushed on its own, and nothing reorders them.

`MultiPlayerGameMode.tick()` also calls `Connection.tick()`. That flushes pending actions, ticks the listener (`ClientPacketListener.tick`) and calls `channel.flush()` (`Connection.java:347-365`, `client/multiplayer/MultiPlayerGameMode.java:293-300`).

### 1.5 Answers to the timing questions

- **Can a packet be processed between the entity tick and `sendChanges`?** No. Main-thread packet handling happens only in the drain at the top of `runTick` (`Minecraft.java:1202-1204`). (`processQueuedPackets` has no other client caller: the only other caller is `server/MinecraftServer.java:1007`.) Netty-thread handlers (section 1.3) can run at the same time as a tick, but none of them touches player movement state.
- **Can two client ticks run after one batch?** Yes, up to 10 (`Minecraft.java:1215`). There is no drain between them.
- **Can a tick run with no drain since the previous tick?** Yes (multi-tick frames). A drain can also happen with no tick (frames where `ticksToDo == 0`).

---

## 2. Sending: `Minecraft.tick()` in order (`Minecraft.java:1795-1894`)

```
1796  clientTickCount++
1797  if level != null && !pause: level.tickRateManager().tick()          // frozen/step bookkeeping
1801  if rightClickDelay > 0: rightClickDelay--
1807  if !pause && level != null: gameMode.tick()                         // [P1] SetCarriedItem, Connection.tick → ClientPacketListener.tick
1812  pick(1.0F)                                                          // attack/use ray (always runs)
1815  gui.tick();  if screen != null: missTime = 10000
1820  if overlay == null && screen == null: handleKeybinds(); missTime--  // [P2]
1828  if level != null && !pause:
1831     gameRenderer.tick()
1833     level.tickEntities()                                             // [P3] LocalPlayer.tick → aiStep → movement
1835     level.tickBlockEntities()                                        // pistons may move the player here
1838     if player != null && !player.isRemoved(): player.sendChanges()   // [P4]
1845  musicManager/soundManager tick
1849  if level != null: (if !pause) tutorial.tick(); level.tick(); animateTick; particles
1884     if connection != null && !pause: send(ServerboundClientTickEndPacket.INSTANCE)   // [P5]
1887  else if pendingConnection != null: pendingConnection.tick()
1893  keyboardHandler.tick()
```

The sections below follow this order. "SCI" means `[ServerboundSetCarriedItemPacket if the selected slot changed]`, which `MultiPlayerGameMode.ensureHasSentCarriedItem()` emits (`MultiPlayerGameMode.java:307-313`; `carriedIndex` starts at 0 for each new game mode, `:87`).

### 2.1 [P1] `gameMode.tick()` (`MultiPlayerGameMode.java:293-300`)

1. `ensureHasSentCarriedItem()` sends SCI. This is how a hotbar change from the **previous** tick's keybinds, or from a scroll between ticks, reaches the server (see hazard H9).
2. `Connection.tick()` runs `ClientPacketListener.tick()` (`ClientPacketListener.java:2784-2810`):
   - `sendDeferredPackets()`: KeepAlive replies that were deferred while render was frozen.
   - `levelLoadTracker.tickClientLoad()`. When the level is ready it calls `notifyPlayerLoaded()`, which sends `ServerboundPlayerLoadedPacket` and sets `clientLoaded = true` (`:2804-2817`). This happens **at the start of** the first tick that has movement, so `PlayerLoaded` comes before that tick's movement packets.
   - Chat key pair and session packets (not movement).

### 2.2 `pick(1.0F)`: the attack and use ray (`Minecraft.java:3009-3019`, `LocalPlayer.java:1272-1322`)

`hitResult = player.raycastHitResult(1.0F, cameraEntity)`:

- If the active item has `DataComponents.ATTACK_RANGE`, `AttackRange.getClosesetHit` is used. In vanilla only the spear has it (`world/item/Item.java:556`). Swords fall through to the default pick:
- `pick(camera, blockRange = BLOCK_INTERACTION_RANGE attr (default 4.5), entityRange = ENTITY_INTERACTION_RANGE attr (default 3.0), 1.0F)` (`LocalPlayer.java:1292-1311`; defaults `world/entity/ai/attributes/Attributes.java:28-30,41-43`):
  1. `maxDistance = max(blockRange, entityRange)`.
  2. `from = camera.getEyePosition(1.0F)` = `(lerp(1.0, xo, x), lerp(1.0, yo, y) + getEyeHeight(), lerp(1.0, zo, z))` (`Entity.java:2057-2062`). `Mth.lerp(a, p0, p1) = p0 + a*(p1-p0)` (`util/Mth.java:578-580`). **To match exactly, compute `xo + (x - xo)`, not `x`**: the two can differ by 1 ulp.
  3. Block pass: `camera.pick(maxDistance, 1.0F, false)` uses `ClipContext.Block.OUTLINE`, `Fluid.NONE` (`Entity.java:2075-2080`). If it hits, `maxDistance = distance to the hit`.
  4. `direction = getViewVector(1.0F) = calculateViewVector(getXRot(1.0F), getYRot(1.0F))`. With `partialTicks == 1.0F` these return **exactly** `getXRot()` / `getYRot()` with no lerp (`Entity.java:2023-2029`). 26.3 also dropped the old `LocalPlayer.getViewXRot` override (diff against 26.2). The trig uses `Mth.sin` / `Mth.cos`, a 65536-entry table indexed by `(long)(rad * 10430.378350470453) & 65535` (`Entity.java:2031-2038`, `util/Mth.java:50-56`).
  5. Entity pass: `ProjectileUtil.getEntityHitResult(camera, from, from + dir*maxDistance, camera.getBoundingBox().expandTowards(dir*maxDistance).inflate(1.0), CAN_BE_PICKED, maxDistanceSq)` (`world/entity/projectile/ProjectileUtil.java:110-146`). Each candidate box is `entity.getBoundingBox().inflate(getPickRadius())` (0 for players). If `from` is inside a box, that entity wins at distance 0 (`canBePickedFromInside` defaults to true, `Entity.java:2090`). Otherwise the nearest clip point wins. Same-root-vehicle entities are only taken at distance 0.
  6. The entity is used only if `entityHit.distSq(from) < blockDistanceSq` (strictly less). Both results then pass through `filterHitResult(hit, from, range)`: if the hit is not `closerThan(from, range)` (that is, `distSq >= range²`, `world/phys/Vec3.java:124-126`), it turns into a `BlockHitResult.miss` (`LocalPlayer.java:1313-1322`).
- `Player.isPickable()` returns `!isSpectator() && !isRemoved()` (`Player.java:1644`, `LivingEntity.java:3370`).

**Timing of the ray relative to movement.** `pick` runs at the start of tick `T`, before tick `T`'s entity tick:

- **Attacker position:** the end of tick `T-1`. That means after `T-1`'s movement, after other entities' pushes and pistons in `T-1`, and after anything applied in the drain before `T` (teleports, block-ack setbacks). `xo` is the position at `T-1`'s `commonTick` (`Entity.java:527-537` → `setOldPos`, `:1894-1898`), unless a teleport or setback in the drain rewrote it (sections 3.1 and 3.9).
- **Eye height:** the pose at that moment. That is the pose set at the end of `T-1`'s `Player.tick` by `updatePlayerPose` (`Player.java:284`, `:343-357`), or a pose echo applied in the drain (hazard H5).
- **Rotation:** the rotation at the start of tick `T`. Rotation changes only in the per-frame mouse turn (after the ticks of a frame) and in drained position or rotation packets. When not riding, it therefore **equals the rotation that tick `T`'s `sendChanges` reports** in `W_T` (Rot or PosRot), or the last reported rotation if `W_T` has no rotation.
- **Targets:** each remote entity's `getBoundingBox()` at that moment. That is its position after the stepped interpolation step in the client's tick `T-1` (`commonTick` → `getInterpolation().interpolate()`, `Entity.java:533-535`; `world/entity/SteppedInterpolationHandler.java` `doInterpolate`), plus any snaps drained before `T` (`interpolateTo` with 0 steps or a jump over 64 blocks, `AbstractInterpolationHandler.java:38-47`, `ClientPacketListener.java:658-663`). The rendered target is lerped by partial tick. The ray is **not**: it is cast at tick granularity.
- `hitResult` is computed **once** per tick. Every attack click consumed in that tick's keybinds uses the same `hitResult` (`Minecraft.java:1979-1981`). Two clicks in one tick send two `Attack(sameId)` + `Punch` pairs.

### 2.3 Screens and overlays

When any screen or overlay is open, `handleKeybinds` is skipped and `missTime = 10000` (`Minecraft.java:1816-1826`). Opening a screen calls `KeyMapping.releaseAll()` (`client/gui/Gui.java:271`), which sets every key up and clears every `clickCount` (`KeyMapping.java:59-63,124-127`; toggle keys can be restored on close, `:65-71`). The entity tick, `sendChanges` and the tick end **still run**. Movement continues with the keys released, so the input packet reports the release. A keybind that opens a screen in the middle of `handleKeybinds` (chat, advancements; `Gui.handleKeybinds`, `Gui.java:332-364`) clears the clicks still waiting in that same call.

Item use is released only inside `handleKeybinds` (section 2.4e). If a screen opens while the player is blocking, the client **keeps using the item** (and keeps the slowdown) until the first tick after the screen closes.

The first attack click after a screen closes is dropped, because `missTime` is still 10000 when `startAttack` runs (`:1658`). It is cleared by `continueAttack(false)` at the end of that call (`:1632-1634`).

### 2.4 [P2] `handleKeybinds()` (`Minecraft.java:1905-2003`), in source order

a. Camera, perspective and GUI keys. These send no movement packets (opening a screen has the effects in section 2.3).

b. **Hotbar keys** 1-9: `getInventory().setSelectedSlot(i)` (`:1927-1931`). **No packet here.** SCI goes out later in this same call if an action below calls `ensureHasSentCarriedItem`, and otherwise at the start of the next tick (section 2.1).

c. **Inventory key:** `PlayerCommand(OPEN_INVENTORY)` only when riding a vehicle with a server-controlled inventory. Otherwise the screen opens client-side with no packet (`:1938-1944`).

d. **Swap offhand:** `PlayerAction(SWAP_ITEM_WITH_OFFHAND, ZERO, DOWN)` per press (`:1951-1955`). There is no client prediction: the server's slot packets do the swap.

e. **Drop:** `gameMode.dropItem`, which predicts `removeFromSelected` and then sends SCI and `PlayerAction(DROP_ITEM | DROP_ALL_ITEMS, ZERO, DOWN, seq 0)` (`MultiPlayerGameMode.java:581-589`).

f. **If `player.isUsingItem()`** (`:1965-1977`): if the use key is up, `gameMode.releaseUsingItem` sends SCI and `PlayerAction(RELEASE_USE_ITEM, ZERO, DOWN)`, then calls `player.releaseUsingItem()`. That ends `startedUsingItem` **before this tick's movement** (`MultiPlayerGameMode.java:521-525`). All attack, use and pick clicks queued for this tick are **discarded**, even in the same tick as the release. 1.7 block-hit note: a click that lands in the release tick is lost.

   **Else:**
   - **Attack clicks:** `while (keyAttack.consumeClick()) instantAttack |= startAttack()` (`:1979-1981`). `startAttack` (`:1657-1722`):
     - returns with no packet if `missTime > 0`, `hitResult == null` (and sets `missTime = 10` outside creative), `isHandsBusy()`, the item is disabled, or `cannotAttackWithItem(item, 0)` (the `MINIMUM_ATTACK_CHARGE` component).
     - Spectator: `ServerboundSpectatorActionPacket`.
     - `PIERCING_WEAPON`: SCI, then `PlayerAction(STAB)` and a client swing (`MultiPlayerGameMode.java:527-534`). There is no Punch.
     - **ENTITY**: if `ATTACK_RANGE` is absent or in range, `gameMode.attack(player, target)` sends **SCI, `ServerboundAttackPacket(entityId)`**, then runs the client-side `player.attack(target)` and `player.resetAttackStrengthTicker()` (`MultiPlayerGameMode.java:430-438`).
     - **BLOCK** (not air): `startDestroyBlock`, which may send `PlayerAction(ABORT_DESTROY_BLOCK, oldPos)` and then `PlayerAction(START_DESTROY_BLOCK, pos, dir, seq)` inside a block prediction (`MultiPlayerGameMode.java:153-211`). The client may predict-break the block **now** (section 3.9).
     - **MISS** (or block that is air): `missTime = 10` (not creative) and `resetAttackStrengthTicker()`.
     - Every path except spectator and piercing then calls `player.swing(MAIN_HAND, anim, false)` and sends **`ServerboundPunchPacket.INSTANCE`** (`:1716-1717`). `Punch` has no fields (`network/protocol/game/ServerboundPunchPacket.java`).
   - **Use clicks:** `startUseItem()` per click (`:1983-1985`, body `:1724-1789`). If `!gameMode.isDestroying()`: `rightClickDelay = 4`. If `!isHandsBusy()`, then for `MAIN_HAND` and then `OFF_HAND`:
     - ENTITY hit within `isWithinEntityInteractionRange(entity, 0)`: SCI, then `ServerboundInteractPacket(id, hand, hitLocation - entityPos, isShiftKeyDown)` (`MultiPlayerGameMode.java:448-453`). If the result is Success, stop.
     - BLOCK hit: SCI, then `ServerboundUseItemOnPacket(hand, blockHit, seq)` inside a prediction (`MultiPlayerGameMode.java:315-328`). Stop on Success or Fail; on Pass, fall through.
     - Non-empty item: SCI, then **`ServerboundUseItemPacket(hand, seq, yRot, xRot)`** with the current rotation (`MultiPlayerGameMode.java:380-411`). The packet is sent **even when the item is on cooldown**; the client result is then PASS. On Success, stop. Otherwise go on to the next hand.
   - Pick-block clicks: `PickItemFromBlock` / `PickItemFromEntity`.

g. **Held-use repeat:** `if (keyUse.isDown() && rightClickDelay == 0 && !isUsingItem()) startUseItem()` (`:1998-2000`).

h. **`continueAttack(screen == null && !instantAttack && keyAttack.isDown() && mouseGrabbed)`** (`:2002`, body `:1631-1655`):
   - If `down` is false, `missTime = 0`.
   - If `missTime <= 0 && !isUsingItem()` and the main hand has no piercing weapon:
     - holding attack on a non-air block: `continueDestroyBlock` sends `PlayerAction(START/STOP_DESTROY_BLOCK | CHANGE_DESTROY_DIRECTION ...)` as needed, plus swing and **Punch every tick** while mining (`:1645-1648`, `MultiPlayerGameMode.java:231-282`).
     - otherwise `stopDestroyBlock()`, which sends `PlayerAction(ABORT_DESTROY_BLOCK)` and calls `resetAttackStrengthTicker()` **only if it was mining** (`MultiPlayerGameMode.java:214-228`).
   - Holding attack on an entity or on air never auto-attacks.

#### 2.4.1 The client-side attack prediction that moves the attacker

`gameMode.attack` runs `Player.attack(target)` **on the client** (`Player.java:933-985`). The sequence:

- `attackStrengthScale = getAttackStrengthScale(0.5F) = clamp((attackStrengthTicker + 0.5) / (20 / ATTACK_SPEED), 0, 1)` (`Player.java:1789-1801`).
- `onAttack()` resets the ticker (`:1813-1820`).
- If `baseDamage > 0 || magicBoost > 0`: `knockbackAttack = isSprinting() && attackStrengthScale > 0.9F` (`:944-950`).
- `wasHurt = target.hurtOrSimulate(...)`. On the client this is `hurtClient`, which returns **true for `RemotePlayer`** (`client/player/RemotePlayer.java:32-34`) and false for most other entities (`Entity.java:1997-2005`).
- If `wasHurt`: `causeExtraKnockback(target, getKnockback(target) + (knockbackAttack ? 0.5F : 0), ...)` (`:969-971`).
- `causeExtraKnockback` (`Player.java:1104-1128`): **if `knockbackAmount > 0`**, it sets **`setDeltaMovement(getDeltaMovement().multiply(0.6, 1.0, 0.6))` and `setSprinting(false)` on the local player** (`:1121-1122`). It also applies a cosmetic client-side `knockback` to the remote target.
- On the client, `getKnockback` is `ATTACK_KNOCKBACK attribute / 2` **with no enchantments**, because enchantment knockback is computed only on a `ServerLevel` (`LivingEntity.java:1558-1563`).

So on a sprinting full-strength hit against a **player**, the client cuts its own x/z velocity by 0.6 and clears sprint in `handleKeybinds`, **before** that tick's movement.

RevivalPaper adds a +1024 `ATTACK_SPEED` modifier while a legacy weapon is held (`RP/leaf-server/src/main/java/gg/hcfrevival/revival/combat/LegacyHitRegistration.java:11-22,52-56`). Every legacy hit is therefore "full strength", and the client predicts this slowdown on **every** sprinting hit on a player.

What happens to sprint afterwards in the same tick's `aiStep`:
- If the sprint key (or toggle-sprint) is held, `canStartSprinting()` and `keyPresses.sprint()` call `setSprinting(true)` again (`LocalPlayer.java:822-833`). `wasSprinting` never changed, so **no sprint packet** is sent.
- If sprint came from a double-tap, it stays off and `sendChanges` sends `STOP_SPRINTING`.

The server's simulation must apply **the client's** rule here (player targets only, attribute knockback only), not the server's own 1.7 knockback rules.

### 2.5 [P3] The entity tick: `LocalPlayer.tick()` (`LocalPlayer.java:232-243`)

`ClientLevel.tickEntities` calls `commonTick()` then `tick()` for each ticking entity, in `tickingEntities` order (`client/multiplayer/ClientLevel.java:454-480`). Players are always ticking (`Player.isAlwaysTicking`, `Player.java:1903-1905`) and are never frozen by `/tick freeze` (`world/TickRateManager.java:63-65`).

`commonTick` decrements `invulnerableTime`, calls `setOldPosAndRot()`, runs interpolation on the client, and increments `tickCount` (`Entity.java:527-537`). It runs **even when the client has not loaded**.

`LocalPlayer.tick()` does nothing unless `connection.hasClientLoaded()`. Once loaded, `super.tick()` runs `LivingEntity.tick` (`LivingEntity.java:2758+`):
- `baseTick` (`updateSwimming` at `Entity.java:562`);
- `updatingUsingItem()`: **stops item use if the hand item is no longer the same item** (`:3437-3446`);
- `aiStep()`.

`LocalPlayer.aiStep` (`:782-935`) does the following, in order:
- reads `wasJumping` / `wasShiftKeyDown` / `hasForwardImpulse` from the **previous** tick's input (`:793-795`);
- computes `crouching` from the previous shift state (`:797-801`);
- calls `input.tick()`, which builds `keyPresses` from the current key states (`client/player/KeyboardInput.java:23-36`);
- auto-jump can force `jump = true` in `keyPresses` (`:806-809`, `ClientInput.makeJump`);
- runs the sprint logic (`:818-843`; the double-tap window is the client option `sprintWindow`, 0-10, default 7, `client/Options.java:638-647`);
- runs creative flight toggles;
- then `super.aiStep()` (`:930`): the movement.

Packets the entity tick can emit, all **before** `sendChanges`:
- `ServerboundPlayerAbilitiesPacket`: flight toggle, spectator auto-fly, or landing while flying (`:848-865`, `:931-934`, `onUpdateAbilities :383-385`).
- `PlayerCommand(START_FALL_FLYING)` (`:870-872`).
- `PlayerCommand(START_RIDING_JUMP, n)`.
- `ServerboundContainerClosePacket` when standing in a portal with a container open (`:960-966`).
- Boat paddle packets when riding.

Ground-speed timing note: `Player.aiStep` calls `setSpeed(MOVEMENT_SPEED)` **after** `super.aiStep()` (`Player.java:449-455`). Ground movement reads that cached `speed` (`LivingEntity.java:2718-2735`). A `MOVEMENT_SPEED` change made in the drain (attributes packet, or the sprint modifier added in this tick's `aiStep`) therefore reaches ground movement **one tick later**. Air movement uses `getFlyingSpeed()`, which checks `isSprinting()` live (`Player.java:1945-1950`). A simulation that runs the same code with packets applied at the same point gets this right automatically.

**[P3b] `level.tickBlockEntities()`** (`Minecraft.java:1835`). Piston block entities move entities on the client too (`world/level/block/piston/PistonMovingBlockEntity.java:147,179`). In 26.3 this runs **before** `sendChanges`, so the position, `onGround` and `horizontalCollision` sent for tick `T` include piston moves made in tick `T`. In 26.2 they showed up a tick later.

### 2.6 [P4] `LocalPlayer.sendChanges()` (`LocalPlayer.java:264-286`)

This returns at once unless `hasClientLoaded()`. In order:

1. **`ServerboundPlayerInputPacket(input.keyPresses)`, only if `!lastSentInput.equals(keyPresses)`** (`:266-269`). `Input` is a record of 7 booleans: forward, backward, left, right, jump, shift, sprint. It is packed as one byte with bits 1, 2, 4, 8, 16, 32, 64 (`world/entity/player/Input.java`). It is the input **used by this tick's movement** (built by `input.tick()` in this tick's `aiStep`). The server must keep the last value: an unchanged input sends nothing.
2. **Riding:**
   - `MovePlayer.Rot(yRot, xRot, onGround, hc)` **every tick**;
   - if the root vehicle is locally authoritative: `ServerboundMoveVehiclePacket` and then the sprint command (`:271-277`).
3. **Not riding:** `sendPosition()` (`:294-331`):
   1. `sendIsSprintingIfNeeded()` comes **first**: if `isSprinting() != wasSprinting`, send `PlayerCommand(START_SPRINTING | STOP_SPRINTING)` and set `wasSprinting` (`:334-343`). **So the sprint command is always before the move packet of the same tick.**
   2. Only when `isControlledCamera()` (the camera entity is the player; spectating another entity sends nothing here):
      - `positionReminder++`.
      - `move = lengthSquared(x - xLast, y - yLast, z - zLast) > 4.0E-8 || positionReminder >= 20`. The threshold is `Mth.square(2.0E-4)`; `lengthSquared = dx*dx + dy*dy + dz*dz` (`Mth.java:662,738-740`).
      - `rot = (yRot - yRotLast) != 0 || (xRot - xRotLast) != 0`. Float subtraction is widened to double, so any change at all counts.
      - Send `PosRot(pos, yRot, xRot, onGround, hc)` if move and rot; `Pos(pos, onGround, hc)` if move only; `Rot(yRot, xRot, onGround, hc)` if rot only; otherwise `StatusOnly(onGround, hc)` **only if** `onGround` or `horizontalCollision` changed since the last call. Otherwise send nothing.
      - If move: set `xLast/yLast/zLast = pos` and `positionReminder = 0`. If rot: set `yRotLast/xRotLast`. **Always** set `lastOnGround` and `lastHorizontalCollision`.
   3. Wire format: doubles for x/y/z, floats for yaw/pitch, and a flag byte with `onGround = 1`, `horizontalCollision = 2` (`network/protocol/game/ServerboundMovePlayerPacket.java:10-41,120-135`).
   4. The flags are the state **after all entity and block-entity ticks of this tick**. Yaw is unwrapped (any float). Pitch is clamped to ±90 by `turn`.
   5. A position is sent at least every 20 ticks. When no `Pos` is sent, the true client position is within 2.0E-4 (Euclidean) of the **last sent** position, not of the previous tick's position.
   6. Teleports do **not** update `xLast` or `yRotLast` (section 3.1).

### 2.7 [P5] `ServerboundClientTickEndPacket` (`Minecraft.java:1883-1886`)

This is sent when `level != null` and `connection = player.connection` (`Minecraft.java:2410-2412`) is not null and `!pause`. It is sent **regardless of** `hasClientLoaded`, open screens, death (`player.isRemoved()` only skips `sendChanges`) and a frozen tick rate. It is not sent while `level == null` (login and configuration).

### 2.8 Degenerate ticks

| Situation | keybinds (P2) | movement (P3) | `sendChanges` (P4) | TickEnd (P5) |
|---|---|---|---|---|
| Screen or overlay open | skipped | yes, keys released | yes | yes |
| `!hasClientLoaded()` (after Login or Respawn, until `PlayerLoaded`) | skipped while `LevelLoadingScreen` is open | no (only `commonTick`) | no | yes |
| Dead, death animation < 20 ticks | skipped (death screen) | yes (immobile: `xxa = zza = 0`, gravity still applies) | yes | yes |
| Dead and removed (`deathTime == 20`, `LocalPlayer.java` `tickDeath`) | skipped | no | no | yes |
| Server `/tick freeze` or step | normal | normal (players never frozen) | normal | yes, at 20 TPS |
| Singleplayer pause | n/a on a dedicated server | | | |
| More than 10 owed ticks in a frame | the extra ticks never run | | | |

`LevelLoadTracker` readiness: the client waits for `LEVEL_CHUNKS_LOAD_START`, then for the player's section to compile (or the player is a spectator, dead, or outside build height), or for a 30 s timeout. A 500 ms close delay is defined (`client/multiplayer/LevelLoadTracker.java:21-22,54-63`, `WaitingForPlayerChunk.isReady`).

### 2.9 What changed from 26.2 to 26.3 on the client

- `sendChanges` moved out of `LocalPlayer.tick()`. It now runs after all entities **and** block entities have ticked (`Minecraft.java:1837-1839`).
- `ServerboundSwingPacket` was removed. Attacks and mining now send `ServerboundPunchPacket` with no hand. Use and interaction swings send nothing; the server decides on them. Swings reach other clients as `ClientboundSwingAnimationPacket(entityId, hand, SwingAnimation)`.
- `LocalPlayer.drop` moved into `MultiPlayerGameMode.dropItem`, which now calls `ensureHasSentCarriedItem` first.
- Teleport accept: 26.2 sent `AcceptTeleportation(id)` and then `MovePlayer.PosRot(x, y, z, yRot, xRot, false, false)`. 26.3 sends a single `AcceptTeleportation(id, x, y, z, yRot, xRot)` and then calls `gameMode.stopDestroyBlock()` (diff of `handleMovePlayer`).
- New clientbound packets: `ClientboundAddTransientBlockPacket`, `ClientboundSwingAnimationPacket`; `VecDelta` / `MovementPacket` (stepped entity paths, covered elsewhere).

---

## 3. Receiving: handlers for movement-relevant packets

Every handler below runs on the main thread in the drain, in server send order, before the frame's ticks. "Self" means the packet targets the local player.

### 3.1 `ClientboundPlayerPositionPacket(id, PositionMoveRotation change, Set<Relative>)` → `handleMovePlayer` (`ClientPacketListener.java:794-806`)

1. If not riding: `setValuesFromPositionPacket(change, relatives, player, interpolate = false)` (`:809-830`):
   - `current = PositionMoveRotation.of(player)` = (`getClientPositionAndRotation()`, which for the local player is its current position and rotation; `getKnownMovement()`, which is `getDeltaMovement()`) (`world/entity/PositionMoveRotation.java:25-28`, `Entity.java:4081-4083,4191-4193`).
   - `new = calculateAbsolute(current, change, relatives)` (`PositionMoveRotation.java:38-65`):
     - position per axis: `(X in rel ? cur.x : 0.0) + change.x`;
     - `yRot = (Y_ROT in rel ? cur.yRot : 0) + change.yRot`;
     - `xRot = clamp((X_ROT in rel ? cur.xRot : 0) + change.xRot, -90, 90)`;
     - velocity: start from `cur.delta`. If `ROTATE_DELTA` is set, rotate it by `xRot(rad(cur.xRot - new.xRot))` and then `yRot(rad(cur.yRot - new.yRot))`. Then per axis: `DELTA_x in rel ? rotated + change.delta : change.delta`.
   - The client then calls `setPos(new.pos)`, `setDeltaMovement(new.delta)` (finite values only, `Entity.java:3899-3903`), `setYRot`, `setXRot`, and `setOldPosAndRot(calculateAbsolute((oldPosition, 0, yRotO, xRotO), change, relatives))`. So `xo` becomes the old `xo` plus the offset for relative axes, or the new absolute value. This feeds the next attack ray's `lerp`.
   - `onGround`, `fallDistance`, `xLast/yLast/zLast`, `yRotLast/xRotLast`, `positionReminder` and `wasSprinting` are **not** touched.
2. **Always** (even when riding, where the position is not applied): send `ServerboundAcceptTeleportationPacket(id, player.getX(), getY(), getZ(), getYRot(), getXRot())`, the post-apply state in full doubles and floats (`:801-802`; record at `network/protocol/game/ServerboundAcceptTeleportationPacket.java:9`). **There is no move-packet echo in 26.3.** The vanilla 26.3 server treats this accept as a position packet (`server/network/ServerGamePacketListenerImpl.java:555-578`).
3. `blockStatePredictionHandler.onTeleport()`: `lastTeleportSequence = currentSequence`. Later block-ack setbacks for older predictions are suppressed (section 3.9) (`:803`).
4. `gameMode.stopDestroyBlock()`: if the client was mining, it sends `PlayerAction(ABORT_DESTROY_BLOCK)` **right after** the accept, and resets the attack-strength ticker (`:804-806`).

Velocity through this packet is exact (`Vec3.STREAM_CODEC`, three doubles). A "velocity-only teleport" with X/Y/Z/Y_ROT/X_ROT all relative and zero offsets keeps the position exact (`x + 0.0 == x`) and sets the velocity exactly. It still costs a teleport accept, `onTeleport()` and an abort of mining.

### 3.2 `ClientboundPlayerRotationPacket(yRot, relativeY, xRot, relativeX)` → `handleRotatePlayer` (`:832-844`)

The client sets yaw and pitch (relative or absolute, pitch clamped), then calls `setOldRot()`. It **immediately sends `MovePlayer.Rot(yRot, xRot, onGround = false, horizontalCollision = false)`** from the drain, outside any tick group, with **false flags**. `yRotLast` is not updated, so tick `T`'s `sendChanges` sends `Rot` again.

### 3.3 `ClientboundSetEntityMotionPacket(id, movement)` for self → `handleSetEntityMotion` (`:631-637`)

`entity.lerpMotion(movement)`. For `LocalPlayer` this is the base `Entity.lerpMotion`, which **replaces** `deltaMovement` (`Entity.java:2755-2757`; only `RemotePlayer` and some minecart and arrow classes override it). The effect starts with the next tick's movement, after that tick's attack slowdown in `handleKeybinds`.

**The vector is lossy:** `Vec3.LP_STREAM_CODEC` → `LpVec3` (`network/LpVec3.java:24-75`).
- `scale = ceilLong(max(|x|, |y|, |z|))`.
- Each component is `round((c/scale * 0.5 + 0.5) * 32766)` in 15 bits.
- Decode: `(min(q, 32766) * 2.0 / 32766.0 - 1.0) * scale`.
- If the absolute maximum is `< 3.051944088384301E-5`, the decoded value is `Vec3.ZERO`.
- For `|v| <= 1` the step is about 6.1e-5. For example, 0.4 decodes as 0.3999877...

**The server must simulate `LpVec3.read(LpVec3.write(v))`, not `v`.**

### 3.4 `ClientboundExplodePacket` → `handleExplosion` (`:1368-1389`)

- Optionally plays a sound.
- **Always** adds one `explosionParticle` at `center`.
- Calls `trackExplosionEffects` (block particles).
- `playerKnockback.ifPresent(player::pushFromExplosion)`, which is `Entity.push`: `setDeltaMovement(getDeltaMovement().add(x, y, z))` when the values are finite (`Entity.java:1974-1983`).

The knockback is full-precision doubles (`ClientboundExplodePacket.java` codec) and **additive**. Nothing else changes on the player.

### 3.5 `ClientboundPlayerAbilitiesPacket` → `handlePlayerAbilities` (`:2102-2111`)

Sets `flying`, `instabuild`, `invulnerable`, `mayfly`, `flyingSpeed` and `walkingSpeed` directly. `walkingSpeed` does not change the speed attribute on the client (that base comes from the attributes packet).

`ClientboundGameEventPacket CHANGE_GAME_MODE` calls `gameMode.setLocalMode(...)` → `GameType.updatePlayerAbilities` (`:1560-1561`, `MultiPlayerGameMode.java:104-115`, `world/level/GameType.java:68-86`). This rewrites `mayfly`, `instabuild` and `invulnerable` immediately, and sets `flying` to true for spectator and false for survival or adventure. No abilities packet is needed for that.

### 3.6 `ClientboundUpdateAttributesPacket` → `handleUpdateAttributes` (`:2368-2392`)

For each snapshot: `instance.setBaseValue(base)`, then **`instance.removeModifiers()`**, then `addTransientModifier` for each modifier sent (`AttributeInstance.java:134-138`). The server's snapshot holds **all** its modifiers, including the server's view of the sprint modifier (`ClientboundUpdateAttributesPacket.java:30-37`). The client's sprint modifier, which `LivingEntity.setSprinting` adds and removes locally (`LivingEntity.java:2313-2320`), is replaced by the server's state as of send time. This lasts until the client next calls `setSprinting`.

Speed attributes reach ground movement one tick late (section 2.5). The interaction-range attributes change the next ray.

### 3.7 Mob effects

- **Add:** `handleUpdateMobEffect` → `forceAddEffect(new MobEffectInstance(...), null)` (`:1808-1828`). On the client, `onEffectAdded` applies **no attribute modifiers** (`LivingEntity.java:1087-1093`). Speed and slowness reach movement only through the attributes packet. Jump boost, levitation and slow falling act on **presence** in `activeEffects`.
- **Remove:** `handleRemoveMobEffect` → `removeEffectNoUpdate` (`:1993-1998`).
- **The client never expires an effect:** `tickClient` only counts the duration down (`LivingEntity.java:877-879`, `world/effect/MobEffectInstance.java:243-250`). An effect lasts on the client until the remove packet is drained.

### 3.8 `ClientboundSetHealthPacket` → `handleSetHealth` (`:1244-1249`)

Calls `hurtTo(health)`, `setFoodLevel` and `setSaturation`. The food level gates sprinting: `isSprintingPossible` → `hasEnoughFoodToDoExhaustiveManoeuvres` (food > 6 or `mayfly`; `Player.java:1559-1561`, `world/food/FoodData.java:92-94`). `shouldStopRunSprinting` stops sprint in the `aiStep` of the first tick after a `SetHealth` with food 6 or below is drained, before that tick's movement (`LocalPlayer.java:937-941,1165-1169`).

### 3.9 Block changes, acks and prediction (`ClientLevel.java:184-238`, `client/multiplayer/prediction/BlockStatePredictionHandler.java`)

- **Prediction:** `startPrediction` increments `currentSequenceNr`. Any `setBlock` made while predicting is applied to the world **at once** and records the server-known old state with `playerPos = player.position()` (`MultiPlayerGameMode.java:285-291`, `BlockStatePredictionHandler.java:18-26,53-57`). Predicted breaks and placements happen in `handleKeybinds`, so **this tick's movement already collides with the predicted world.** The packets that carry the sequence (`UseItemOn`, `UseItem`, `PlayerAction START/STOP_DESTROY_BLOCK`) arrive in the same window, before the move packet.
- **`ClientboundBlockUpdatePacket` / `ClientboundSectionBlocksUpdatePacket`** → `setServerVerifiedBlockState(pos, state, 19)` (`ClientPacketListener.java:847-850,932-935`). If that position has a pending prediction, the server state is **only stored** and the world keeps the predicted block. Otherwise it is applied at once (`ClientLevel.java:204-208`).
- **`ClientboundBlockChangedAckPacket(seq)`** → `endPredictionsUpTo(seq)`: for every pending position with `sequence <= seq`, call `syncBlockState(pos, serverState, lastTeleportSequence < seq ? recordedPlayerPos : null)` (`BlockStatePredictionHandler.java:39-51`). `syncBlockState` sets the block. **If the player now collides with it** (`isColliding`), the client calls `player.absSnapTo(recordedPlayerPos)` (`ClientLevel.java:210-219`). This is a **client-side setback with no packet**: position and `xo` are reset to where the player stood when the prediction started (`Entity.java:1843-1850`), and velocity is kept. The next `Pos` packet shows the result.
- **Chunks:** `handleLevelChunkWithLight` → `replaceWithPacketData`. Collision is available to the next tick (`:853+`). While the chunk under the player is missing, the client replaces gravity with `movementY = -0.1` (or 0 at minimum Y) (`LivingEntity.java:2474-2479`). Chunk arrival must be tracked with a ping. `ChunkBatchReceived` does not work for this (section 1.3).

### 3.10 `ClientboundSetEntityDataPacket` for self → `handleSetEntityData` (`:640-646`)

`assignValues` writes every item and calls `onSyncedDataUpdated` with no filter (`network/syncher/SynchedEntityData.java:110-118`). The server sends dirty data to **tracking players and self** (`server/level/ServerEntity.java:370-386`; still true in Leaf, `RP/leaf-server/src/minecraft/java/net/minecraft/server/level/ServerEntity.java:457-480`). Movement-relevant items:

- `DATA_SHARED_FLAGS_ID`:
  - bit 3 (sprint) → `isSprinting()` (`Entity.java:2821-2827`). The flag is written directly, so the speed modifier is untouched. A flipped flag makes `sendIsSprintingIfNeeded` send a sprint command, and `canStartSprinting` may set sprint again. This is the latch RevivalPaper's `SprintLedger` works around (`RP/.../combat/SprintLedger.java:5-13`).
  - bit 1 (crouch) is **ignored for movement**: `LocalPlayer.isShiftKeyDown()` reads `input.keyPresses.shift()` (`LocalPlayer.java:693-695`).
  - bit 4 (swimming) feeds the hysteresis in `updateSwimming` (`Entity.java:1689-1697`).
  - bit 7 (fall flying).
- `DATA_POSE` → `refreshDimensions()` (`Entity.java:3520-3550`). This changes the bounding box and eye height until `updatePlayerPose` at the **end** of the next `Player.tick`. The next tick's attack ray and movement both run with the echoed pose.
- `DATA_LIVING_ENTITY_FLAGS` (bit 0 using, bit 1 offhand) → `LocalPlayer.onSyncedDataUpdated` (`:597-611`):
  - server says using and the client is not: `startUsingItem(hand)`;
  - server says not using and the client is: **`stopUsingItem()`**.

  This turns the item-use slowdown on or off at drain time.

### 3.11 Item use and slots for self

- `ClientboundEntityEventPacket` id 9 on self calls `completeUsingItem()`. If the client is using the item, that calls `stopUsingItem()` (`Player.java:413-415`, `LivingEntity.java:3578-3594`).
- **`ClientboundSetHeldSlotPacket`** → `setSelectedSlot(slot)` (`:720-725`). Because `carriedIndex` now differs, the client **echoes `SetCarriedItem(slot)`** at the start of its next tick (section 2.1).
- `ClientboundContainerSetSlotPacket` (container 0) → `inventoryMenu.setItem(slot, stateId, stack)` (`:1416-1446`). `ClientboundSetPlayerInventoryPacket` → `inventory.setItem` (`:1458-1462`). `ClientboundContainerSetContentPacket` → `initializeContents` (`:1465-1473`). `ClientboundSetEquipmentPacket` → `setItemSlot` (`:1522-1527`).
- If the used hand's item becomes a **different item**, the client stops using it in the next tick's `updatingUsingItem`, before `aiStep` (`LivingEntity.java:3437-3446`). The same item with new components keeps the use going.
- Equipment does **not** change attributes on the client (`detectEquipmentUpdates` runs only on the server, `LivingEntity.java:2787`).

### 3.12 Sword blocking, as the client sees it

RevivalPaper sends legacy weapons with a `CONSUMABLE` decoration: 3600 s, `ItemUseAnimation.BLOCK`, silent, no particles. It optionally sends `USE_EFFECTS` too (`RP/.../combat/LegacySwordBlocking.java:33,81-94`).

On right-click the client runs `Item.use` → `Consumable.startConsuming`. There is no `FOOD` component, so `canConsume` is true. `consumeTicks = 72000 > 0`, so the client calls `player.startUsingItem(hand)` and gets `InteractionResult.CONSUME`, which has no swing (`world/item/Item.java:205-210`, `world/item/component/Consumable.java:62-75,95-98`).

On the wire this is **[SCI] `UseItem(hand, seq, yRot, xRot)`**, or `UseItemOn` first when looking at a block, or `Interact` first when looking at an entity.

`LocalPlayer.startUsingItem` sets `startedUsingItem` (`:563-570`). **The slowdown applies to the same tick's movement:**
- `applyInput` → `modifyInput`: `input * 0.98`, then `* UseEffects.speedMultiplier` (default **0.2**, `UseEffects.java:10`) while `isUsingItem() && !isPassenger()`, then the sneak factor, then square-to-circle correction (`LocalPlayer.java:707-748`).
- `isSlowDueToUsingItem()` (using and `!canSprint`, which is false by default) blocks **starting** a sprint and resets the double-tap timer (`:577-579,818-820,1171-1178`).
- It does **not** stop a sprint already in progress (`shouldStopRunSprinting`, `:937-941`).

The slowdown ends in the tick whose keybinds send `RELEASE_USE_ITEM` (section 2.4f): that tick's movement is not slowed.

### 3.13 Ping, keepalive and tick rate

- `ClientboundPingPacket(int id)` → `handlePing` sends `ServerboundPongPacket(id)` **on the main thread, during the drain** (`ClientCommonPacketListenerImpl.java:154-157`). The vanilla server's `handlePong` is empty (`server/network/ServerCommonPacketListenerImpl.java:91-92`), so the hook point is free.
- `ClientboundKeepAlivePacket`: replied on the Netty thread (section 1.3). Not ordered with ticks.
- `ClientboundTickingStatePacket(tickRate, isFrozen)` → `setTickRate` and `setFrozen` (`ClientPacketListener.java:701-708`). The client's tick period becomes `max(50 ms, 1000/rate)` from the next frame's `advanceGameTime` (section 1.2).
- `ClientboundTickingStepPacket(steps)` → `setFrozenTicksToRun` (`:711-717`). Neither packet changes local-player ticking, because players are never frozen.

### 3.14 `ClientboundRespawnPacket` → `handleRespawn` (`:1258-1347`)

- Builds a **new `LocalPlayer`**.
- With `KEEP_ENTITY_DATA` (2, `ClientboundRespawnPacket.java:17-19`) it copies `lastSentInput`, `wasSprinting`, the entity data, `deltaMovement`, `yRot` and `xRot` (`:1297-1325`). Otherwise `lastSentInput = Input.EMPTY`, `wasSprinting = false`, `resetPos()` and `yRot = -180`.
- Attributes are kept in full with `KEEP_ATTRIBUTE_MODIFIERS` (1); otherwise only base values are kept.
- `setClientLoaded(false)` (`:1307`). Movement stops until `PlayerLoaded`. TickEnd continues.
- The new player's `xLast/yLast/zLast` and `yRotLast` start at 0, so its first `sendPosition` is almost always a `PosRot`.

**The server must reset its copy of the last input and last sprint state exactly as the client does.**

`handleLogin` is the same with a fresh player and a new `MultiPlayerGameMode` (`carriedIndex = 0`). See `:499-569`, with `setClientLoaded(false)` at `:536`.

### 3.15 Other packets for self

- `ClientboundMoveEntityPacket` and `ClientboundEntityPositionSyncPacket` for self are **ignored** apart from the position-codec base, because `isLocalInstanceAuthoritative()` is true for the local player (`:649-674,728-755`; `Entity.java:3747-3754`, `Player.java:1245-1252`).
- `ClientboundTeleportEntityPacket` for self: `interpolate = isTickingEntity(self)`, which is true, so it **interpolates** the local player and sets its delta (`:677-698`). Use `ClientboundPlayerPositionPacket` for self, never this.
- `ClientboundSwingAnimationPacket` → `livingEntity.swing(hand, animation, false)`: visual only (`:1123-1128`). It shares `swingState` with the client's own swings. `ClientboundAnimatePacket` is now only wake, crit and magic crit (`:1098-1112`).
- **`ClientboundAddTransientBlockPacket(pos, state)` (new in 26.3)** → `levelExtractor.queueTransientBlock(new TransientBlock(pos, state, 1.0 s, now))` (`:1182-1187`, `client/renderer/extract/TransientBlock.java:6-7`). It is **render-only**: a block drawn for up to 1 s, or until its section re-meshes, so a landed falling block does not flicker (sent by `FallingBlockEntity.java:204`). There is no collision effect.
- `ClientboundBundlePacket`: all sub-packets are handled in one drain, in order (`:2468-2474`).

---

## 4. Remote entities as the attacker's client sees them (for lag compensation)

- Remote players use `SteppedInterpolationHandler` (`LivingEntity.java:3303-3305`). Move and position-sync packets set targets at drain time. **The position changes only inside `commonTick` → `interpolate()`**, or at once on a snap.
- The interpolation speed adapts to the backlog (`SteppedInterpolationHandler.java` `InterpolationData.advance`). The target's on-screen path therefore depends on **which client tick each move packet was drained before**. Bracket entity-movement packets with pings as well (another report covers the interpolation maths).
- The attack ray in tick `T` (section 2.2) uses each target's box **after the attacker's tick `T-1` interpolation step**, plus snaps drained before `T`. Pose changes of the target (`DATA_POSE`, so 1.5 high when crouching) take effect at drain time through `refreshDimensions`.
- Remote players are pushable on the client. `RemotePlayer.aiStep` calls `pushEntities()` (`RemotePlayer.java:42-63`), and the local player's own `pushEntities` (`LivingEntity.java:3227-3251`) pushes the local player too. **The local player's movement therefore depends on remote positions as the client sees them, and on `tickingEntities` order.** Keep player collision off (team `collisionRule never`), or simulate it with lag-compensated positions.

---

## 5. The client's serverbound stream, window by window

Let `TickEnd(T)` be the T-th `ServerboundClientTickEndPacket` since `ClientboundLoginPacket` set the level. Window `W_T` holds every packet after `TickEnd(T-1)` up to and including `TickEnd(T)`. Every main-thread packet belongs to exactly one window, in this order:

```
W_T:
 (a) Between ticks: zero or more frames since tick T-1. Each frame contributes:
       (a1) GUI and input-event packets (pollEvents): ContainerClick / ContainerButtonClick / ContainerClose
            (closing a screen) / SetCreativeModeSlot / PlaceRecipe / SelectBundleItem / SignUpdate / chat
            and commands / ClientCommand(PERFORM_RESPAWN) from the death screen / ClientInformation ...
       (a2) Drain replies, in the order the server sent the causing packets:
            Pong(n)
            AcceptTeleportation(id, x, y, z, yRot, xRot) [+ PlayerAction(ABORT_DESTROY_BLOCK) if mining]
            MovePlayer.Rot(yRot, xRot, false, false)    ← reply to ClientboundPlayerRotationPacket
            ConfigurationAcknowledged, cookie and resource-pack replies, ...
     (Netty-thread KeepAlive and ChunkBatchReceived replies can appear anywhere; ignore their position.)
 (b) gameMode.tick:  [SetCarriedItem(slot)] [PlayerLoaded] [deferred KeepAlive] [chat session]
 (c) handleKeybinds (skipped if a screen or overlay is open):
       PlayerAction(SWAP_ITEM_WITH_OFFHAND)*
       ([SCI] PlayerAction(DROP_ITEM | DROP_ALL_ITEMS))*
       if using:   [SCI] PlayerAction(RELEASE_USE_ITEM)            (all clicks discarded)
       else:       per attack click: [SCI] Attack(id), Punch | [ABORT_DESTROY] START_DESTROY(seq), Punch | Punch
                                     | SpectatorAction | [SCI] PlayerAction(STAB)
                   per use click:    [SCI] Interact | [SCI] UseItemOn(seq) | [SCI] UseItem(hand, seq, yRot, xRot)  (per hand)
                   per pick click:   PickItemFromBlock | PickItemFromEntity
       held use (rightClickDelay == 0, not using): same as a use click
       continueAttack: mining packets + Punch (every tick while mining) | PlayerAction(ABORT_DESTROY_BLOCK)
 (d) entity tick:  PlayerAbilities | PlayerCommand(START_FALL_FLYING) | PlayerCommand(START_RIDING_JUMP)
                   | ContainerClose (portal) | PaddleBoat ...
 (e) sendChanges (only once loaded):
       [PlayerInput(keys)]                                      only if changed
       not riding:  [PlayerCommand(START_SPRINTING | STOP_SPRINTING)]  then  PosRot | Pos | Rot | StatusOnly | nothing
       riding:      Rot (every tick) [MoveVehicle] [sprint command]
 (f) ClientTickEnd
```

How the server uses a window:

- Everything in (a) and (b) took effect **before** tick `T`'s ray and movement. (a) and (b) cannot always be told apart, but the difference never matters for movement.
- (c) is applied after the ray and before movement. That covers the attack slowdown, start or release of item use, and predicted block changes.
- (d) happens during movement (abilities and fall-flying start in `aiStep`, before `super.aiStep()`).
- (e) is the result of tick `T`. Compare it with the simulation, including the "should a `Pos` have been sent" rule in section 2.6.
- **Simulate on `TickEnd(T)`, not on each move packet.** The rotation for an `Attack` in (c) only arrives in (e). A window can contain no move packet at all. Paper and Leaf handle packets as they arrive, between server ticks too (`RP/leaf-server/src/minecraft/java/net/minecraft/server/MinecraftServer.java:1236-1251,1583`; vanilla handles them at tick start, `src-26.3/server/MinecraftServer.java:1002-1012`). A window can therefore be split across server ticks, and one server tick can hold several windows. Buffer per window.
- **Timer:** count windows. The client cannot produce more than one per `max(50 ms, server mspt)` on average (section 1.2). Bursts of up to 10 after a stall are legitimate. Ticks owed above 10 per frame are lost, never made up.

---

## 6. The transaction rule

**Facts used:**
- The client applies clientbound packets in server send order (TCP plus a FIFO queue; bundles are applied whole).
- Drains happen only between ticks (`Minecraft.java:1202` versus `:1215`).
- A pong is written during the drain that handles its ping (`ClientCommonPacketListenerImpl.java:156`).
- Main-thread writes keep program order (section 1.4).

**Rule A (upper bound).** The server sends `X`, then `Ping(N)`. Let `Pong(N)` arrive in window `W_T`.

The drain that handled `Ping(N)` ran after tick `T-1` (the pong comes after `TickEnd(T-1)`) and before tick `T` (the pong comes before `TickEnd(T)`, and drains never run inside a tick). `X` was handled in that same drain or an earlier one.

**So `X` is visible to every part of tick `T`** (gameMode.tick, ray, keybinds, movement, sendChanges) **and to every later tick.**

**Rule B (lower bound).** The server sends `Ping(M)`, then `X`. Let `Pong(M)` arrive in `W_U`.

`X` was handled in the drain that handled `Ping(M)` or a later one, and that drain ran after tick `U-1`. **So no tick before `U` saw `X`.**

**Bracket.** Send `Ping(M), X, Ping(N)`. The first client tick that sees `X` is some `t*` with `U <= t* <= T`.
- If `Pong(M)` and `Pong(N)` land in the **same window** (no `TickEnd` between them), then `t* = U = T` **exactly**.
- If they land in different windows, `X` became visible at the start of one of the windows `U..T`, at the drain that handled it. Run the simulation for each candidate start and keep the branch that matches the client's reported positions. When none match, use the latest candidate.

Practical scheme:
- After each server tick, once that tick's player-affecting packets have been queued, send one `Ping(n)`. Ping `n-1` is then the pre-ping for everything sent in tick `n`.
- For a critical change (self velocity, teleport, ability or attribute flip, block change near the player, use-item flag), it is also worth sending `Ping` immediately before and after it, in one flush, so both pongs very likely fall in the same window.
- Use your own ID space, for example negative IDs. Nothing in vanilla 26.3 uses pongs on the server.
- Keep Leaf's `async.async-entity-tracker` **off** (the default, `RP/docs/revival/leaf-defaults.md:74`). Otherwise velocity and entity-data packets are queued from tracker threads and can overtake the main thread's ping.

What "applied before tick T" means for each kind of packet:

| Packet | Takes effect in client tick T as |
|---|---|
| SetEntityMotion (self) | `deltaMovement = LpVec3 decode(v)` at the start of T, before T's attack slowdown and movement |
| PlayerPosition (self) | position, velocity and rotation set at the start of T. The attack ray of T uses the new pose, eye and rotation (`xo` per section 3.1). The accept reply is in the (a2) part of `W_T` |
| Explode knockback | `deltaMovement += v` at the start of T |
| Abilities / game mode | T's `aiStep` |
| Attributes | `MOVEMENT_SPEED` for ground movement from T+1 (cached `speed`). Air speed, ranges and others from T |
| Effect add / remove | presence from T |
| SetHealth (food) | sprint checks in T's `aiStep` |
| Block / section update | T's collisions, unless a prediction is pending at that position |
| BlockChangedAck | prediction resolved at the start of T. A possible `absSnapTo` setback before T's ray and movement |
| Entity data (self) | sprint flag, pose (box and eye for T's ray and movement until the end of `Player.tick`), use flag |
| SetHeldSlot | selected slot from T. SCI echo in the (b) part of `W_T` |
| Chunk data | T's collisions and gravity |
| Remote entity movement | interpolation target from T. The position moves in T's `commonTick`, and the attack ray of T+1 sees it |

---

## 7. Ordering hazards

- **H1. The attack's rotation arrives after the attack.** The ray uses the rotation that `W_T`'s `Rot`/`PosRot` (in part (e)) carries, or the previous one. Do not validate an `Attack` until `TickEnd(T)`. `UseItem` carries its own `yRot`/`xRot`. `Interact` carries the hit location relative to the target (`MultiPlayerGameMode.java:450-451`).
- **H2. The client applies attacker slowdown on its own.** On a sprinting hit against a **player** whose attack scale is above 0.9 with `baseDamage > 0` (always true under RevivalPaper's +1024 attack speed), the client multiplies x/z by 0.6 and clears sprint before T's movement. It re-sprints in the same tick with no packet if the sprint key is held. It sends `STOP_SPRINTING` if not. Mob targets do not trigger it (`hurtClient` is false). Enchantment knockback is ignored on the client (section 2.4.1).
- **H3. `SetEntityMotion` is quantized** (`LpVec3`, section 3.3). Use the decoded value or a lossless channel (section 3.1 or 3.4).
- **H4. Attribute echo wipes the client's modifiers** (`removeModifiers`, section 3.6). A server-side `setSprinting` (vanilla attack, Paper's sprint interruption) makes an attributes packet with the server's sprint modifier. That overrides the client's own until the client next toggles sprint.
- **H5. Entity-data self-echo:**
  - **sprint flag:** flips `isSprinting()`, which leads to sprint packets or re-sprinting.
  - **pose:** changes T's box and eye height. For example, a quick sneak tap gives a stale CROUCHING echo for one tick.
  - **using flag:** starts or stops item use. A release followed by a re-block across two server ticks echoes `false` while the client is blocking again, and drops the slowdown for a tick.

  Either filter these out of the self-echo or simulate them exactly at their pong-bounded drain.
- **H6. The `PlayerRotation` reply is a `MovePlayer.Rot` with `onGround = false` and `horizontalCollision = false`,** sent between ticks (section 3.2). It is not a tick's movement. Do not treat it as one, and do not trust its flags.
- **H7. The teleport accept is a position packet; there is no move echo** (section 3.1). The accept is followed by `ABORT_DESTROY_BLOCK` when the client was mining. `xLast` and `yRotLast` are not updated, so T's `sendChanges` compares against the pre-teleport last-sent values.
- **H8. Block prediction and setback.** Predicted breaks and placements change collision in T's keybinds, before movement. The ack can `absSnapTo` the player to the position at prediction time **without a packet** (section 3.9), unless a teleport was handled after that prediction.
- **H9. Hotbar change attribution is ambiguous by one tick.** A number key is consumed in T's keybinds and changes the item for T's entity tick (`updatingUsingItem` can stop blocking in T). Its SCI goes out in T's (c) only if an action follows; otherwise in (b) of `W_{T+1}`. A scroll between T-1 and T changes the slot for T, and its SCI goes out in (b) of `W_T`. So an SCI at the start of a window means "changed before this tick" (scroll) or "changed before the previous tick" (number key). Try both branches when it matters (item use, attack strength).
- **H10. Item use while a screen is open.** Release only happens in keybinds, so a screen opened mid-block keeps the client slowed (section 2.3). The first attack click after a screen closes is dropped.
- **H11. Netty-thread replies are unordered.** `KeepAlive` and `ChunkBatchReceived` are written from the Netty thread and can jump ahead of queued main-thread packets. Never use them as transactions. Use a ping after chunk data to know when collision and gravity changed (section 3.9).
- **H12. Multi-tick frames.** Up to 10 ticks run with no drain and the same rotation. Pongs never fall between them. A pong can sit at the start of any window, followed by several tick windows with no pongs.
- **H13. Ticks without movement still send `TickEnd`:** not loaded, removed while dead, or screens open with movement still running. `PlayerLoaded` sits in (b) of the first moving tick. After a respawn without `KEEP_ENTITY_DATA`, the client's last-sent input is `EMPTY` and `wasSprinting = false`.
- **H14. The input packet is change-only and is the input of this tick's movement.** But `wasJumping`, `wasShiftKeyDown`, `hasForwardImpulse` (for double-tap sprint) and `crouching` read the **previous** tick's input (`LocalPlayer.java:793-801`). Auto-jump shows up as `jump = true`. The double-tap window `sprintWindow` (0-10 ticks) is a client option the server cannot see, so accept `START_SPRINTING` within the maximum window.
- **H15. In 26.3, pistons and other entities act between movement and `sendChanges`.** Block-entity pistons move the player before the packet is sent. Remote-player pushes change velocity after the local player has moved. Both depend on client-side state, and pushes on `tickingEntities` order. Turn off player collision.
- **H16. Client-side effects never expire;** only the remove packet ends them (section 3.7). Speed and slowness act only through attributes.
- **H17. `TeleportEntity` aimed at self interpolates the local player** (section 3.15). Never send it to the owning player.
- **H18. Tick rate.** The client clamps to at most 20 TPS (`max(50, mspt)`) and keeps ticking players at 20 TPS when frozen. Timer checks must use `min(20, serverRate)` while running normally, and 20 when frozen.
- **H19. Exactness of the attack ray.** Eye = `xo + 1.0*(x - xo)` (not `x`), `+ (double) eyeHeight` as a float widened to double. Rotation at partial 1.0 is exact. Entity box clip is strict `<` against block distance, and range is strict `<` (`closerThan`).

---

## 8. Constants

| Constant | Value | Where |
|---|---|---|
| Position-send threshold | `lengthSquared > 4.0E-8` (that is, 2.0E-4²) | `LocalPlayer.java:303` |
| Position reminder | `>= 20` ticks | `LocalPlayer.java:303` |
| Max ticks per frame | 10 | `Minecraft.java:1215` |
| Client tick period | `max(50, server mspt)` ms when running normally, else 50 | `Minecraft.java:2969-2978` |
| Use-key repeat delay | 4 ticks | `Minecraft.java:1726` |
| Miss penalty | `missTime = 10` (not creative) | `Minecraft.java:1663,1710` |
| Entity / block interaction range | 3.0 / 4.5 | `Attributes.java:28-30,41-43` |
| Default use speed multiplier | 0.2, `canSprint = false` | `UseEffects.java:10` |
| Input scale | `0.98 * useMultiplier * sneakAttr`, then square-to-circle | `LocalPlayer.java:722-748` |
| Attacker slowdown | x/z `* 0.6`, `setSprinting(false)` | `Player.java:1121-1122` |
| `LpVec3` | 15 bits per axis, `* 32766`, zero below `3.051944088384301E-5` | `LpVec3.java:8-18,65-75` |
| Unloaded-chunk fall | `movementY = -0.1` | `LivingEntity.java:2476-2477` |
| Client load wait | 30 s timeout, 500 ms close delay | `LevelLoadTracker.java:21-22` |
| Server client-load timeout | 60 ticks | `ServerGamePacketListenerImpl.java:237,2298-2319` |
| Frame caps | 10 fps iconified, 30 fps AFK (60 s), 10 fps long AFK (600 s) | `FramerateLimitTracker` (javap) |
| Sprint double-tap window | option 0-10, default 7 | `Options.java:638-647` |
