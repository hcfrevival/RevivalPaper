# 04 · Server hooks for the authoritative player simulation

What the authoritative system has to intercept or replace on the server, in vanilla 26.3 and in the
patched RevivalPaper tree (Paper → Purpur → Gale → Leaf → RevivalPaper). The report covers each hook site, what
runs there today, what to call there instead, and which thread it runs on.

## Sources and notation

| Tag | Path | State read |
|---|---|---|
| **V** | `~/Developer/tools/mc-src/src-26.3/net/minecraft/` (decompiled 26.3 client jar, includes the server) | 26.3 |
| **P** | `~/Developer/GitHub/RevivalPaper/leaf-server/src/minecraft/java/net/minecraft/` (applied Minecraft sources) | applied-tree commit `89eb4df` ("Damage windows"), all patches through `1010` |
| **M** | `~/Developer/GitHub/RevivalPaper/leaf-server/src/minecraft/java/ca/spottedleaf/moonrise/` | same |
| **PS** | `~/Developer/GitHub/RevivalPaper/paper-server/src/main/java/` (applied CraftBukkit/Paper sources) | `b961664c0` |
| **RJ** | `~/Developer/GitHub/RevivalPaper/leaf-server/src/main/java/gg/hcfrevival/revival/` | repo `ab26baad` |
| **LJ** | `~/Developer/GitHub/RevivalPaper/leaf-server/src/main/java/org/dreeam/leaf/` | repo `ab26baad` |
| **MP** | `~/Developer/GitHub/RevivalPaper/leaf-server/minecraft-patches/features/` | repo `ab26baad` |

`SGPLI` = `server/network/ServerGamePacketListenerImpl.java`, `SCPLI` = `server/network/ServerCommonPacketListenerImpl.java`.

`revival/authoritative` moved while this was written (`611172c9` rebase, `624f21ee` w-tap fix, `d61bb1a5` edible
sword, `cee69cfd` liquid MotionShadow, `94bb5a6a`/`ab26baad` damage windows) and `applyAllPatches` re-applied the
tree. Every **P** line number below was re-checked against `89eb4df`. The tree is a generated git repo, so line
numbers drift whenever a patch changes. When a line has moved, search for the quoted code.

---

## 0. Findings that shape the design

1. **Today the client owns movement and the server only checks it.** `handlePlayerPositionChange` re-runs the
   client's *own delta* through `player.move(MoverType.PLAYER, …)` and then snaps to the client's position
   (V SGPLI:1185, 1209; P SGPLI:1704, 1742/1814). Fall damage, `onGround`, `horizontalCollision` and "known
   movement" all come from the client's flags. Nothing on the server simulates the player with their inputs.
2. **The server's own player physics runs with zero input and is then thrown away.** `tickPlayer` calls
   `doTick()`, which reaches `LivingEntity.aiStep → travel(Vec3(xxa=0,yya,zza=0))`. `xxa` and `zza` are never
   set on the server. It then `absSnapTo(firstGood…)` (P SGPLI:411-412). The position is reverted. `deltaMovement`
   is **not** reverted, so the server's `getDeltaMovement()` for a player is a no-input drift that collisions
   in `handlePlayerPositionChange` also mutate. That is why RevivalPaper keeps `MotionShadow`.
3. **The client ticks in a fixed order, so the server can step the simulation on `ServerboundClientTickEndPacket`.**
   Within one client tick the server receives: pongs and teleport accepts (packet phase at frame start), then
   attack/punch/use packets (`handleKeybinds`), then `PlayerInput` (if changed), sprint `PlayerCommand` (if
   changed), one `MovePlayer` (or none), then `ClientTickEnd` (V client/Minecraft.java:1202, 1795-1885;
   client/player/LocalPlayer.java:264-341). Today `handleClientTickEnd` only zeroes "known movement" and resets
   two flags (P SGPLI:3934-3943).
4. **Main-thread packets keep their order. `handlePong` and `handleKeepAlive` run on the Netty thread.** Main-thread
   packets go through one FIFO `PacketProcessor` queue (P network/PacketProcessor.java:42). `handlePong` is an
   empty Netty-thread handler (P SCPLI:157). To keep transaction acks ordered with movement, the pong hook has
   to re-enqueue itself onto the same queue.
5. **Paper handles packets between server ticks** (P server/MinecraftServer.java:1583 `pollTask →
   packetProcessor.executeSinglePacket()`, plus `runAllTasksAtTickStart` 1225-1250). Vanilla drains the queue
   once at tick start (V server/MinecraftServer.java:1007). So simulation steps run at arbitrary points between
   server ticks, against whatever the world state is at that moment. Collision has to use a **client-view** world
   overlay driven by block-change transactions.
6. **Velocity sent with `ClientboundSetEntityMotionPacket` is lossy** (`Vec3.LP_STREAM_CODEC`, V network/LpVec3.java):
   15 bits per axis, scaled by `ceil(max|v|)`, and magnitudes under `3.051944088384301E-5` become zero.
   `ClientboundPlayerPositionPacket` (position + delta) and `ClientboundExplodePacket.playerKnockback` are exact
   doubles. Corrections should therefore use `ClientboundPlayerPositionPacket`. Knockback the simulation applies
   must be `LpVec3.read(LpVec3.write(v))`.
7. **Paper's collision code (Moonrise `CollisionUtil`) is not bit-identical to vanilla `Entity.collide`.** It
   lacks the `|d| < 1e-7 → 0` snap. Gale/Leaf also change `Entity.move`: they skip zero moves, skip negligible
   speed-factor multiplication, and skip the sneak edge back-off by default. The simulation must be a standalone
   port of vanilla 26.3 collision and must not call the patched `Entity.move` or `collide` (§8).
8. **Every server→client packet that changes the client's physics goes through `SCPLI.send(Packet, listener)`**
   (P SCPLI:359). One per-connection outbound hook there can sandwich each packet in transaction pings. A
   `ClientboundBundlePacket` is handled atomically by the client (V client/multiplayer/ClientPacketListener.java:2468).
   `ClientboundPingPacket` is not on Paper's send-immediately bypass list (P network/Connection.java:1009-1029),
   so it stays ordered with physics packets.
9. **Player tracking today sends one position step per 2 server ticks.** `EntityTypes.PLAYER` has
   `updateInterval(2)` and `dontTrackDeltas` (V world/entity/EntityTypes.java:1173-1184). The server-side
   `SteppedInterpolationTracker` only adds intermediate steps on bounce (`syncPosition`), so each move packet
   carries a 1-step `Stepped` path with `ticks=2`. Exact per-tick paths need a per-player step buffer fed by the
   simulation and drained in `ServerEntity.sendChanges` (§5).
10. **MotionShadow approximates the 1.7 server's no-input motion using the client's own `onGround` claim.** With a
    true simulation it keeps its 1.7 role (consistent knock base), but takes `onGround` and fluid state from the
    simulation. A new motion model can base the knock on the simulated velocity, and the knock is applied to the
    simulation at the client tick its transaction is acked (§9.2).

---

## 1. Threads, ordering and when things run

### 1.1 Inbound path

- `Connection.channelRead0` runs on the connection's Netty event loop, checks Paper's packet limiter, then calls
  `genericsFtw → packet.handle(listener)` (P network/Connection.java:228-297, 300).
- Handlers that start with `PacketUtils.ensureRunningOnSameThread(packet, this, level|packetProcessor)` push
  `(listener, packet)` onto the **single server-wide** `PacketProcessor.packetsToBeHandled`
  (`ConcurrentLinkedQueue`). They throw `RunningOnDifferentThreadException`, which `channelRead0` swallows
  (P network/protocol/PacketUtils.java:17-26; P network/PacketProcessor.java:42-56, `executeSinglePacket` 23).
  - Paper unparks the main thread when the packet reaches the head of the queue.
  - Order is arrival order per connection, and global across connections.
- These handlers **do not** re-enqueue and run on Netty: `SCPLI.handleKeepAlive` (P SCPLI:111), `SCPLI.handlePong`
  (P SCPLI:157, empty), `SGPLI.handlePingRequest` (P SGPLI:3032), and the chat/command decode paths.
  `handleCustomPayload` enqueues only discarded payloads (P SCPLI:166-172).
- When the queue is drained:
  - Vanilla drains it once per tick, at tick start (V MinecraftServer.java:1003-1010
    `processPacketsAndTick → packetProcessor.processQueuedPackets()`).
  - Paper drains it at tick start (P MinecraftServer.java:1225-1250 `runAllTasksAtTickStart`, called at 1752) and
    keeps draining **while waiting for the next tick** (P MinecraftServer.java:1583 `pollTask`:
    `packetProcessor.executeSinglePacket() | pollTaskInternal()`).
  - So a client tick's packets are usually handled within milliseconds of arrival. Do not assume one move
    packet per server tick, and do not assume packets run at a fixed point in the server tick.

### 1.2 Server tick order (patched)

`MinecraftServer.tickChildren` (P MinecraftServer.java:1862):

1. `suspendFlushing()` on every player connection (`tickChildren` first statement). Packets sent from the main
   thread during the tick are written but not flushed.
2. For each level: `tickLevel` (1842) → `ServerLevel.tick` (P server/level/ServerLevel.java:1013). Parallel world
   ticking at 1933 is off by default.
   1. Block ticks, fluid ticks, raids.
   2. `getChunkSource().tick(haveTime, true)` (ServerLevel:1082) → `ServerChunkCache.tick` (P server/level/ServerChunkCache.java:523):
      - Moonrise player chunk loader `.tick()` (533). Sends chunks and forgets.
      - `tickChunks()` → `broadcastChangedChunks` (544-569). Sends **block-change packets** (§6).
      - `chunkMap.tick()` (535). This is the **entity tracker** (§5), unless
        `gameplay-mechanisms.knockback.use-legacy-tracker-ticking`.
   3. `runBlockEvents`.
   4. Entity loop. `ServerPlayer.tick()` runs here (ServerLevel:1132 `tickNonPassenger` → `tick()`). It does not
      do physics (§3).
   5. If `use-legacy-tracker-ticking` (default false): `chunkMap.tick()` here, after entities (ServerLevel:1161,
      MP 0312).
   6. If async tracker: `leaf$asyncTracker.onEntitiesTickEnd()` (ServerLevel:1174).
3. Async tracker: `onTickEnd()` for every level (MinecraftServer.java:1958).
4. `tickConnection()` (1961) → `ServerConnectionListener.tick` (P server/network/ServerConnectionListener.java:245)
   → `Connection.tick` (P network/Connection.java:653) → `SGPLI.tick` (P SGPLI:377). That runs:
   - `ClientboundBlockChangedAckPacket` if pending (378-380);
   - `tickPlayer()` (406), which calls `player.doTick()` = **player physics with zero input** (§3);
   - keepalive.
   - Spigot `player-shuffle` can shuffle connection order (ServerConnectionListener:250-252).
5. `playerList.tick()` (1963).
6. Per player: `chunkSender.sendNextChunks`, then `resumeFlushing()` (1982-1983). This is when the tick's
   packets are flushed.

Consequence for velocity and positions:

- With default tracker timing, the tracker runs **before** `doTick` and before entity ticks. A knock applied
  while packets are handled between ticks is sent by the tracker in the next tick's `chunkSource` phase. The one
  exception is the direct send in `Player.causeExtraKnockback`: a melee hit on a player sends the velocity at
  once (§4).
- Player positions the tracker sends are whatever the last handled move packet set.

### 1.3 Outbound path

- `SCPLI.send(packet)` → `send(packet, listener)` (P SCPLI:355, 359):
  - CraftBukkit compass hook;
  - `flush = !suspendFlushingOnServerThread || !server.isSameThread()` (371);
  - `connection.send(packet, listener, flush)`.
- `Connection.send` (P network/Connection.java:449-482), Paper "Optimize network":
  - A packet is written straight away (`sendPacket` 535 → `eventLoop.execute(doSendPacket)`, or inline on the
    event loop) when either of these holds:
    - `canSendImmediate` (1009-1029). That covers keepalive, chat, titles, sounds, particles, player info,
      boss bar and pong-response.
    - On the main thread, `packet.isReady()`, `pendingActions` empty and no extra packets.
  - Otherwise it goes to `pendingActions` (466) and `flushQueue` (598) → `processQueue` (613). The queue stops
    at the first `!isReady()` packet, which is an anti-xray chunk still being built.
  - Packets on the bypass list can overtake queued packets. **`ClientboundPingPacket`,
    `ClientboundSetEntityMotionPacket`, block updates and teleports are not on the bypass list**, so they keep
    relative order.
- Sends from other threads (`sendPacket` → `channel.eventLoop().execute`, 545) are ordered by submission. A ping
  injected right after a packet on the same thread stays behind it.
- Leaf's `network.OptimizeNonFlushPacketSending` (default off) uses `lazyExecute` for non-flush sends (541-543).
  It does not reorder within one connection, because the event loop is single-threaded.

### 1.4 The client contract that defines transaction semantics

From the vanilla 26.3 client. The client engineers own this. It is repeated here because it decides where the
server hooks go.

- Per frame, the client handles queued packets **once**, before running up to 10 ticks (V client/Minecraft.java:1197-1222).
- Ping handling replies at once (V client/multiplayer/ClientCommonPacketListenerImpl.java:154-157).
- A `ClientboundBundlePacket` is handled sub-packet by sub-packet, inline
  (V client/multiplayer/ClientPacketListener.java:2468-2474).
- So a bundle `[Ping(a), X]` or `[Ping(a), X, Ping(b)]` is applied atomically before the client's next tick. Its
  pong lands in the inbound stream **between `ClientTickEnd(n-1)` and the first packet of tick n**. This is an
  exact "X was applied before client tick n".
- Client tick (V client/Minecraft.java:1795-1886):
  1. `gameMode.tick()`.
  2. `pick(1.0F)` (1812). The crosshair target is computed from the eye at the position *after the previous
     tick* and the current rotation.
  3. `handleKeybinds()` (1822): `startAttack` (1657) → `MultiPlayerGameMode.attack` (V client/multiplayer/MultiPlayerGameMode.java:430-438)
     sends `ServerboundAttackPacket`, then `ServerboundPunchPacket` (Minecraft.java:1717). Use-item packets are
     sent here too.
  4. `level.tickEntities()` (1833): `LocalPlayer` physics.
  5. `player.sendChanges()` (1837):
     - `PlayerInput` if changed (LocalPlayer:266-269);
     - sprint command if changed (334-342);
     - move packet (294-327). Position is sent only when moved more than `2.0E-4` (squared) or on the
       20-tick reminder (303). Rotation is sent whenever it changed.
  6. `ServerboundClientTickEndPacket` (1885).
- Mouse turning happens after the ticks of a frame (Minecraft.java:1235 `mouseHandler.handleAccumulatedMovement`).
  The rotation used in `pick` therefore equals the rotation in the same tick's move packet. The client
  engineers should confirm this.

---

## 2. Inbound handlers

### 2.1 `handleMovePlayer` / `handlePlayerPositionChange`

Vanilla: V SGPLI:1080-1244. Patched: P SGPLI:1525-1871. Main thread (`ensureRunningOnSameThread` with the
player's level).

Order of checks in the patched code:

1. **NaN/∞** in x/y/z/yaw/pitch → kick `invalid_player_movement`. Purpur adds a log line (P 1527-1536; V 1082).
2. **Two position packets in one client tick** → kick (`receivedPositionThisTick`, P 1538-1545; V 1086-1092).
   `handleClientTickEnd` clears the flag. Vanilla therefore already enforces "at most one position per client
   tick".
3. `!wonGame && !isImmobile()` (CraftBukkit, P 1547). `tickCount == 0 → resetPosition()`. Requires
   `hasClientLoaded()`.
4. **Awaiting teleport** (`updateAwaitingTeleport`, P 1914-1932): while `awaitingPositionFromClient != null`,
   only the rotation is applied (`absSnapRotationTo`, P 1555-1556). Vanilla resends the teleport after 20 ticks
   (V 1286-1297). Paper disables that (`if (false && …)`, P 1916). CraftBukkit sets `allowedPlayerTicks = 20`.
5. `handlePlayerPositionChange(x, y, z, yRot, xRot, onGround, horizontalCollision)` (P 1573):
   - Clamp horizontal to ±3.0E7 and vertical to ±2.0E7; `wrapDegrees`.
   - **Passenger**: rotation only, then `chunkSource.move` (P 1587-1590). Out of scope.
   - **Sleeping**: teleport back if `movedDist > 1`.
   - **Large-move fix (Paper)**: `movedDist = max(|target-firstGood|², |target-current|²-1, |target-lastGood|²-1)`
     (P 1600-1613).
   - **Packet rate (CraftBukkit)**: `receivedMovePacketCount++`. `deltaPackets = received - known`, where `known`
     is set in `tickPlayer` (P 414). `allowedPlayerTicks` accrues from wall-clock 50 ms units
     (`System.currentTimeMillis()/50`, P 1625-1640). If `deltaPackets > max(allowedPlayerTicks, 5)` then
     `deltaPackets = 1`. Vanilla: `> 5 → 1` (V 1155-1159).
   - **Unloaded chunks (Paper)**: `preventMovingIntoUnloadedChunks`, world config, default `false`
     (PS io/papermc/paper/configuration/WorldConfiguration.java:527). Raises `PlayerFailMoveEvent`
     `MOVED_INTO_UNLOADED_CHUNK` (P 1647-1656).
   - **Moved too quickly**, gated by gamerules `PLAYER_MOVEMENT_CHECK` and `ELYTRA_MOVEMENT_CHECK`
     (`shouldCheckPlayerMovement`, P 1873):
     - test: `movedDist - |server deltaMovement|² > max(metersPerTick, (movedTooQuicklyMultiplier·deltaPackets·speed)²)`;
     - `metersPerTick` is 100, or 300 when fall-flying;
     - `speed = walkSpeed·10`, or `flySpeed·20` when flying;
     - the multiplier defaults to 10.0 (PS org/spigotmc/SpigotConfig.java:293-296);
     - Paper raises `PlayerFailMoveEvent` `MOVED_TOO_QUICKLY` (P 1659-1675);
     - on failure it calls `teleport(current)`.
     - Vanilla uses `metersPerTick·deltaPackets` (V 1161-1167).
     - `expectedDist` comes from the server's corrupted `deltaMovement` (finding 2).
   - **Jump**: if server `onGround && !packet.onGround && yDist > 0` → Paper `PlayerJumpEvent`. A cancelled event
     teleports the player back. Otherwise `player.jumpFromGround()` (P 1683-1697). Server side this only adds
     stats and exhaustion (V ServerPlayer.java:1636).
   - `forceSendPlayerSupportBlocks` when the packet says on-ground but the server sees nothing below. At most
     every 200 ticks (P 1699-1702, 1886).
   - **Server re-collision of the client's delta**: `player.move(MoverType.PLAYER, target - lastGood)` (P 1704).
     Then CraftBukkit forces `player.onGround = isOnGround` (P 1705). Paper returns early if `move` started a
     teleport (portal) (P 1708-1711).
   - **Moved wrongly**:
     - residual `target - pos`, with **y forced to 0** by the always-true `if (yDist > -0.5 || yDist < 0.5)`
       (P 1715-1717; V 1188-1190);
     - fails when the squared residual exceeds `SpigotConfig.movedWronglyThreshold` (default 0.0625,
       PS SpigotConfig.java:288-291);
     - exempt when changing dimension, sleeping, creative, spectator or `isInPostImpulseGraceTime()`
       (V LivingEntity.java:1857);
     - Paper `PlayerFailMoveEvent` `MOVED_WRONGLY` (P 1721-1737).
   - **Snap and clip test (Paper)**: `absSnapTo(target)` (P 1742). Then, only when the move collided or the box
     changed, `hasNewCollision` uses Moonrise `CollisionUtil.getCollisions` with the border flag (P 1936-1966)
     instead of vanilla `isEntityCollidingWithAnythingNew` (V 1305-1318). `CLIPPED_INTO_BLOCK` fail event
     (P 1751-1757).
   - **Accepted branch** (P 1758-1852):
     1. Snap back to start.
     2. `PlayerMoveEvent` when the move is more than 1/256 or the turn more than 10° (P 1780-1810). A cancelled
        event calls `internalTeleport(from)`. A changed `to` teleports with cause `PLUGIN`.
     3. `absSnapTo(target)` (1814).
     4. `clientIsFloating = oyDist ≥ -0.03125 && !verticalCollisionBelow && …` (1816-1824).
     5. `chunkSource.move(player)` (1825).
     6. `setOnGroundWithMovement(clientOnGround, clientHC, clientDelta)` (1827).
     7. `doCheckFallDamage(clientDelta, clientOnGround)` (1828).
     8. `setKnownMovement(clientDelta)` via `handlePlayerKnownMovement` (1829, 3945).
     9. `resetFallDistance` if moving up.
     10. `tryResetCurrentImpulseContext`.
     11. Purpur scissors.
     12. `checkMovementStatistics`.
     13. `lastGood = pos` (1850-1852).
   - **Rejected branch**: `internalTeleport(start)` with no event (SPIGOT-1807), `doCheckFallDamage`, and
     `removeLatestMovementRecording` (P 1853-1858).

What the authoritative system does here:

- In `handlePlayerPositionChange`, for a non-passenger, non-sleeping player under authority, **do not move the
  player**. Record the claim for the current client tick: position if present, rotation, `onGround`,
  `horizontalCollision`. Then return.
- Keep checks 1-4. Check 4 already throws away positions that are in flight while a correction is pending.
- Move the accepted-branch side effects (event, chunk move, flags, fall damage, known movement, statistics) into
  the simulation step at `ClientTickEnd` (§2.4), fed with **simulated** values.
- Moved-too-quickly and moved-wrongly become redundant. Keep them behind a config switch as a fallback.

### 2.2 Teleports: sending and accepting

- **Sending.** `internalTeleport(PositionMoveRotation, Set<Relative>)` (P SGPLI:2038-2071) is the only place
  that sends `ClientboundPlayerPositionPacket` (P 2070). It:
  - asserts the main thread (`AsyncCatcher`);
  - refuses removed players;
  - sanitises NaN rotation;
  - sets `justTeleported`;
  - increments `awaitingTeleport` (wrapping at `Integer.MAX_VALUE`);
  - calls `player.teleportSetPosition(destination, relatives)` (P 2061). This moves the server player at once
    and sets `deltaMovement` (V world/entity/Entity.java:3296-3310). Relative deltas are computed against
    `PositionMoveRotation.of(entity)`, which uses **`getKnownMovement()`**, the client-reported delta;
  - sets `awaitingPositionFromClient`;
  - updates CraftBukkit's `lastPos*`.
- `teleport(…, cause)` (P SGPLI:1999-2028) wraps it with `PlayerTeleportEvent`.
- Every caller goes through `internalTeleport`:
  - `ServerPlayer.teleport(TeleportTransition)` (P server/level/ServerPlayer.java:1650, 1678);
  - respawn (P server/players/PlayerList.java:689);
  - bed exit (ServerPlayer:1854, 1892);
  - `teleportTo` (2433, 2439);
  - `CraftPlayer` teleports.
  - Exception: Leaf "Slice smooth teleports" skips the respawn/teleport pair when `smoothWorldTeleport` is set
    (MP 0090; P PlayerList:688-689).
- Rotation-only teleports use `ClientboundPlayerRotationPacket` (`ServerPlayer.forceSetRotation`,
  P ServerPlayer.java:1708-1711).
- The packet codec is `PositionMoveRotation.STREAM_CODEC`: `Vec3.STREAM_CODEC` doubles for position and delta,
  and floats for rotation (V world/entity/PositionMoveRotation.java). It is **exact**.
- **Client.** `handleMovePlayer(ClientboundPlayerPositionPacket)` → `setValuesFromPositionPacket` (V
  client/multiplayer/ClientPacketListener.java:794-829). It sets position, delta, rotation and old position.
  It then replies **at once, in the packet phase** with `ServerboundAcceptTeleportationPacket(id, x, y, z, yRot, xRot)`,
  carrying its resulting absolute position.
- **Accepting.** `handleAcceptTeleportPacket` (P SGPLI:809-834; V 555-579):
  - If `id == awaitingTeleport`: snap to `awaitingPositionFromClient`, set `lastGood`, call
    `hasChangedDimension()`, clear `awaitingPositionFromClient`, then
    `handlePlayerPositionChange(packet.x, y, z, yRot, xRot, false, false)`.
  - If `awaitingPositionFromClient` is null or the values are invalid → kick.
  - A wrong id is ignored silently.

Hooks:

- In `internalTeleport`, just before the send at P 2070, call `auth.onTeleportSent(id, destination, relatives)`.
  The simulation remembers the pending reset. Wrap the packet in a transaction too (§7), so the acked client tick
  is known even before the accept arrives.
- In `handleAcceptTeleportPacket`, after the id check, call `auth.onTeleportAccepted(id, x, y, z, yRot, xRot)`.
  The simulation state becomes the teleport result, using the client's echoed absolute position as a check, with
  `velocity = calculateAbsolute(...).deltaMovement()`. Client ticks whose packets arrived between send and accept
  belong to the old timeline. Vanilla already ignores their positions; the simulation must not step them.
- For **corrections**, send absolute position and absolute delta (no `DELTA_X/Y/Z` relatives) so the client
  takes the exact simulated velocity. Send rotation as relative zero (`Relative.ROTATION` with 0 values) so the
  camera does not snap. `calculateAbsolute` clamps relative pitch to ±90 (V PositionMoveRotation.java).

### 2.3 `handlePlayerInput`

P SGPLI:519-563 (V 434-441). Main thread.

- CraftBukkit raises `PlayerInputEvent` when the input changed.
- Paper raises `PlayerToggleSneakEvent` and only calls `setShiftKeyDown` if the event is not cancelled (P 528-539).
  The server's sneak flag can therefore disagree with the client's.
- `setLastClientInput(packet.input())` always runs (P 541).
- Purpur `parrotsAreUnaffectedByPlayerMovement`.
- `Input` is 7 booleans: forward, backward, left, right, jump, shift, sprint (V world/entity/player/Input.java:6-13).

Hook: `auth.onInput(packet.input())` at the top. The simulation reads this, never `isShiftKeyDown()`.

### 2.4 `handleClientTickEnd`: what it does today

P SGPLI:3934-3943 (V 2264-2272). Main thread.

- Fires Paper's `ClientTickEndEvent` (field at P 336).
- If no move packet arrived this client tick, sets `setKnownMovement(Vec3.ZERO)`.
- Clears `receivedMovementThisTick` and `receivedPositionThisTick`.

Nothing else uses the client tick boundary.

Hook: `auth.onClientTickEnd()` **after** the flag reset. It does the following:

1. Steps the simulation once with this tick's buffered input, sprint commands, rotation, item use, and any state
   changes acked by pongs that arrived before this tick's packets.
2. Compares against the claimed position, if one came.
3. Applies the accepted-branch side effects from §2.1 with simulated values.
4. Pushes the tick's position into the tracker step buffer (§5).
5. Advances timer accounting.

Gate it on `hasClientLoaded()` (P SGPLI:3969) and on no teleport pending. The client still sends `TickEnd`
before it has loaded, while `LocalPlayer.tick` is skipped (V client/player/LocalPlayer.java:232-242).

### 2.5 `handlePlayerCommand` (sprint and others)

P SGPLI:2892-~2990 (V 1781-1831). Main thread.

- CraftBukkit `PlayerToggleSprintEvent`. **When cancelled, the handler returns before `setSprinting`**, but the
  client keeps sprinting.
- `START_SPRINTING`: `setSprinting(true)` and `LegacyKnockback.sprintStart` (P 2914).
- `STOP_SPRINTING`: `setSprinting(false)` and `revivalSprintLedger.stop()` (P 2918).
- `STOP_SLEEPING` sets `awaitingPositionFromClient`.
- `START_FALL_FLYING` → `tryToStartFallFlying`.

Hook: `auth.onSprintCommand(start)` **before** the CraftBukkit event, because the client's sprint state is a
fact of its movement whatever plugins decide. A cancelled sprint then has to be enforced by sending a correction,
not by ignoring the packet.

### 2.6 The 26.3 attack path

- **`ServerboundAttackPacket(int entityId)`** (V network/protocol/game/ServerboundAttackPacket.java) →
  `handleAttack` (P SGPLI:3037-3077; V 1876-1903). Main thread. Checks, in order:
  1. `isImmobile`;
  2. `hasClientLoaded() && !isSpectator()`;
  3. `target = level.getEntityOrPart(id)`;
  4. Spigot self-attack kick;
  5. world border `isWithinBounds(target.blockPosition())`;
  6. **range**: `player.isWithinAttackRange(mainHand, target.getBoundingBox(), lenience)`, where `lenience` is
     Paper's `clientInteractionLeniencyDistance` or 3.0 (P 3053; PS
     io/papermc/paper/configuration/GlobalConfiguration.java:334). `AttackRange.isInRange` measures the distance
     from `getEyePosition()` (server position, i.e. the last client position) to the AABB, inside
     `[minReach − hitboxMargin − buffer, maxReach + hitboxMargin + buffer]`
     (V world/item/component/AttackRange.java:104-117). With no `ATTACK_RANGE` component it is
     `[0, entity_interaction_range]` with margin 0 (55-58). **Effective server reach is about 3 + 3 = 6 blocks to
     the box.** That is why the hit test has to be replaced;
  7. not a `PIERCING_WEAPON`;
  8. invalid targets (item, xp orb, self, unattackable arrow) → kick;
  9. `isItemEnabled`;
  10. `!cannotAttackWithItem(item, 5)`;
  11. `player.attack(target)` (P SGPLI:3064) → `Player.attack` (P world/entity/player/Player.java:1069). Its
      first statement is the Revival hit-registration cap (§9).
- **`ServerboundPunchPacket`** (unit packet) → `handlePunch` (P SGPLI:2858-2889; V 1772-1779). Swing and
  `resetAttackStrengthTicker`. CraftBukkit/Paper ray-traces for `PlayerInteractEvent LEFT_CLICK_AIR` and fires
  `PlayerArmSwingEvent`. 26.2 had `ServerboundSwingPacket` instead (diff of the two packet directories).
- **Interact**: `ServerboundInteractPacket` → `handleInteract` (P SGPLI:3080-3171) with
  `isWithinEntityInteractionRange(box, lenience)` (P 3096).
- **Spears**: `PlayerAction.STAB` → `PiercingWeapon.attack` (V SGPLI:1344-1356).
- Client side, the client predicts the attack (`player.attack(entity)` in `MultiPlayerGameMode.attack`), but
  `Entity.hurtOrSimulate → hurtClient` returns false for players and living entities (V Entity.java:1997-2005).
  So the **attacker's 0.6 slowdown and sprint stop never happen on the attacker's client**. They exist only in
  server state. The simulation must not apply them unless the server sends them.

Hook: in `handleAttack`, after the target lookup and border check, call `auth.onAttack(target, packet)`. It parks
the attack until the same client tick's `ClientTickEnd`, where the tick's rotation is known. There it validates:

- the ray from the eye position *after the previous client tick*, using this tick's rotation;
- against the target's **client-view** box at that client tick (from the tracker transaction log);
- with the client's `pick` rules.

Then it calls the original `player.attack(target)` path, so every Paper and Revival event keeps firing. The
vanilla/Paper `isWithinAttackRange` stays as a broad phase or is removed.

### 2.7 Item use and slot changes

All on the main thread.

- `handleUseItem` (P SGPLI:2328-2390). In order:
  - `isImmobile`;
  - Spigot `checkLimit(timestamp)`;
  - `ackBlockChangesUpTo(seq)`;
  - `absSnapRotationTo(packet rot)`;
  - CraftBukkit ray trace and `PlayerInteractEvent`. Cancelled → `resyncUsingItem` plus an inventory resync;
  - `gameMode.useItem` → `Item.use`. Revival 1009 makes legacy swords usable (`startBlocking`), which leads to
    `startUsingItem`.
- `handleUseItemOn` (P 2234-2326): block placement. Always resends two `ClientboundBlockUpdatePacket`s
  (2316-2317).
- `handlePlayerAction`:
  - `RELEASE_USE_ITEM` → `releaseUsingItem()` (P 2154-2157);
  - dig actions ack the sequence. Paper may send the ack at once (P 2158-2185).
- `handleSetCarriedItem` (P 2459-2483): `PlayerItemHeldEvent`. A cancelled event sends `ClientboundSetHeldSlotPacket`.
  `stopUsingItem()` runs when the main-hand slot changed.

The client predicts item-use slowdown from **its copy** of the item. RevivalPaper sends legacy swords with a
`CONSUMABLE` (3600 s, `BLOCK` animation, silent) and optional `USE_EFFECTS` that the server stack lacks
(RJ combat/LegacySwordBlocking.java:33, 81-95; MP 1009 `ItemStack` codec and `HashedStack`).

Hooks:

- The simulation evaluates item components through `LegacySwordBlocking.toClient(stack, stack.getComponentsPatch())`
  (the client view), never the raw server stack.
- `auth.onUseItem(hand)`, `auth.onReleaseUseItem()` and `auth.onCarriedItem(slot)` record client-predicted
  use state for the current client tick.
- Server overrides travel back to the client as packets and go through the transaction layer: `resyncUsingItem`
  sends entity data, plus `SetHeldSlot` and container slot packets.

### 2.8 `handlePong`, keepalive and latency

- `SCPLI.handlePong` is **empty and runs on Netty** (P SCPLI:157; V 91).
- `handleKeepAlive` (P SCPLI:111-152), Paper "improve keepalives":
  - one keepalive per second;
  - `latency = pingCalculator5s.getAvgLatencyMS()`;
  - out-of-order or unknown ids → `disconnectAsync`;
  - Gale `keepalive.send-multiple` is an alternative path (P 116-125, 293-308).
- `latency()` is read by RevivalPaper's ground prediction (RJ combat/LegacyKnockback.java:185).
- No code anywhere sends `ClientboundPingPacket` today. Only the protocol classes reference it.

Hooks:

- `handlePong`:
  - For `ServerGamePacketListenerImpl`, first call `PacketUtils.ensureRunningOnSameThread(packet, this, server.packetProcessor())`
    so the ack takes its place in the same FIFO as the movement packets.
  - Then `auth.onPong(id)`. It records "every transaction up to `id` is applied before the next client tick" and
    the measured RTT.
- Reserve an id range so plugin anticheats can share the channel. The GrimAC fork the server runs uses
  PacketEvents pings. Ignore ids outside the range.
- Alternative: stamp an inbound sequence number in `Connection.channelRead0` on Netty and order by it. That is
  more invasive.

### 2.9 Client load, respawn, death

- `handleAcceptPlayerLoad` → `markClientLoaded` (P SGPLI:836, 3980-3992). `hasClientLoaded()` is false while
  waiting for respawn or during the 60-tick load timeout (P 3969). It gates movement and attacks.
- Respawn sends `ClientboundRespawnPacket` (P PlayerList.java:688; ServerPlayer.java:1661). It creates a new
  client `LocalPlayer` with zero velocity, followed by a teleport.
- Hooks:
  - `auth.reset(...)` on respawn and dimension change;
  - `auth.onClientLoaded()` in `markClientLoaded`;
  - `markClientUnloadedAfterDeath` (P 3994) pauses the simulation.

### 2.10 Vehicles

`handleMoveVehicle` (P SGPLI:567-~760) keeps a separate client-authoritative path with the same CraftBukkit
checks. Leave it out of scope: vehicles stay client-authoritative, or are disabled in authoritative arenas.

---

## 3. Server-side player tick: what runs, and in what order

- **`ServerPlayer.tick()`** runs in the level entity loop (P ServerPlayer.java:745-811):
  - Revival `revivalMotionShadow.tick(this)` (747) and `revivalHitRegistration.tick(this)` (748);
  - CraftBukkit `joining`;
  - `tickClientLoadTimeout`, `gameMode.tick`, warden tracker, damage cooldown;
  - container broadcast (Paper interval);
  - spectator camera follow (`absSnapTo(camera)`, `chunkSource.move`);
  - criteria;
  - `trackStartFallingPosition`;
  - `updatePlayerAttributes` (creative reach modifiers; crouch waypoint range);
  - advancements, post effects, Purpur night vision;
  - Leaf `Protocols.tickPlayer` (810).
  - **No physics.**
- **`SGPLI.tickPlayer()`** runs from the connection tick (P 406-470; V 326-371):
  1. `resetPosition()` (`firstGood = lastGood = pos`);
  2. `xo/yo/zo = pos`;
  3. **`player.doTick()`** (P ServerPlayer.java:842). `super.tick()` is `Player.tick` (V Player.java:231-285):
     - `noPhysics = isSpectator()`;
     - sleep handling;
     - `updateIsUnderwater`;
     - `LivingEntity.tick` → `Entity.tick/baseTick`. Fire, lava damage, portal, freezing, and
       `updateInWaterStateAndDoFluidPushing`, which changes server `deltaMovement`;
     - **`LivingEntity.aiStep`** (V LivingEntity.java:3040-3172):
       - zeroes tiny motion; for players, horizontal is zeroed below `9.0E-6` squared (3060);
       - `applyInput()`: `xxa,zza *= 0.98`, which is always 0 on the server (3175);
       - jump (`jumping` is false on the server);
       - `updateFallFlying`;
       - `travel(Vec3(xxa,yya,zza))` (3135), because a player is `AUTHORITATIVE_SIDE_AND_SERVER`
         (V Player.java:1259) and `isEffectiveAi()` is true on the server (1264) → `canSimulateMovement()`
         (V Entity.java:3765-3771). This is **zero-input travel**: gravity, friction and server collisions;
       - `applyEffectsFromBlocks()` (3139): cobweb stuck, berry bush, powder snow, fire, portals and pressure
         plates, from `movementThisTick`;
       - freezing;
       - `pushEntities()` (3168, cramming damage);
     - `Player.aiStep` (V Player.java:442-488): regeneration, `inventory.tick`, `setSpeed(MOVEMENT_SPEED)`,
       item and xp pickup (`touch`);
     - position clamp at ±2.9999999E7;
     - `attackStrengthTicker++`, cooldowns;
     - `updatePlayerPose()`;
     - then food, statistics, and the health/food/xp packets (P ServerPlayer.java:842-~920).
  4. **`player.absSnapTo(firstGood…)`** (P 412) reverts the position. `deltaMovement`, `onGround` and collision
     flags keep whatever zero-input travel left in them.
  5. `knownMovePacketCount = receivedMovePacketCount`;
  6. flying kick: `clientIsFloating` for more than `ceil(80·max(0.08/gravity, 1))` ticks (P 415-424;
     `getMaximumFlyingTicks` P 459);
  7. vehicle bookkeeping.
- **Fall damage.** Players never take fall damage from `Entity.move`, because `isLocalInstanceAuthoritative()`
  is false for them on the server (V Entity.java:3747-3749, check at ~813). It comes only from
  `doCheckFallDamage(xa, ya, za, onGround)` (V Entity.java:1596-1603) in `handlePlayerPositionChange`, with the
  client's delta and `onGround`.

Hooks:

- `LivingEntity.aiStep`, the `else if (this.canSimulateMovement() && this.isEffectiveAi()) this.travel(input);`
  line (P LivingEntity.java, same statement): skip it for a `ServerPlayer` under authority, so zero-input travel
  no longer corrupts `deltaMovement`.
  - Equivalently, restore `deltaMovement := auth.velocity()` after `doTick()` at P SGPLI:412.
- Block effects (`applyEffectsFromBlocks`) should run from the simulation's recorded per-tick movements.
  `Entity.addMovementThisTick` (P Entity.java:1577; V 963-972) and the `Entity.Movement` record (V 4304-4311) are
  **private**, so the patch needs a small public accessor. For example,
  `public void revivalRecordMovement(Vec3 from, Vec3 to, Vec3 delta) { this.addMovementThisTick(new Entity.Movement(from, to, delta)); }`,
  called once per simulated client tick, the same way vanilla `Entity.move` records at P Entity.java:1353.
  The next `doTick → applyEffectsFromBlocks()` (V Entity.java:946-957) then consumes these movements. Do not use
  the public `applyEffectsFromBlocks(from, to)` (V 988): it applies effects at once and outside the normal
  per-tick batching.
- Keep the rest of `doTick` (damage, effects, food, stats). It is server state, not client physics.

---

## 4. How velocity reaches the client today

Every site, in the patched tree.

| # | Site | What happens | Thread |
|---|---|---|---|
| a | `Player.causeExtraKnockback` (P Player.java:1261-1315), legacy hit | `LegacyKnockback.sendVelocity` (P 1288-1292; RJ LegacyKnockback.java:156-175): clears `syncVelocity`, fires `PlayerVelocityEvent` with the knock, sends `ClientboundSetEntityMotionPacket(id, knock)` **immediately** via `player.connection.send`, sets the shadow when the model is `VANILLA_1_7`, then restores `setDeltaMovement(oldMovement)`. | main (attack handling) |
| b | same, non-legacy | Vanilla/CraftBukkit (P 1293-1314): if the victim is a `ServerPlayer && syncVelocity` → `PlayerVelocityEvent` → `send(new ClientboundSetEntityMotionPacket(entity))` (1309) → `syncVelocity = false` → restore `oldMovement`. | main |
| c | `ServerEntity.sendChanges` (P server/level/ServerEntity.java:256-278) | Any `syncVelocity` left set by `markHurt` (P LivingEntity.java:1613 in `hurtServer`), `CraftEntity.setVelocity` (PS org/bukkit/craftbukkit/entity/CraftEntity.java:227-238, which also sets the Revival shadow at 237), projectiles, mob hits or explosion damage: `PlayerVelocityEvent` → `sendToTrackingPlayersAndSelf(SetEntityMotion(entity))`. The server **keeps** the knocked `deltaMovement`; it is never restored. | main, in the tracker pass |
| d | Leaf `AsyncTracker.handlePlayer` (LJ async/tracker/AsyncTracker.java:80-109) | Replaces (c) for players when `async.async-entity-tracker` is on. `ServerEntity:616` excludes players. | main, at tracker tick start |
| e | `ServerEntity` `needsSync` delta (P 198-224) | Players `dontTrackDeltas`, but `needsSync`, set by `knockback`/`push`, makes it send `SetEntityMotion(lastSentMovement)` to **trackers only**. Leaf's `filterClientboundSetEntityMotionPacket` can drop it. | tracker |
| f | `MaceItem` (P world/item/MaceItem.java:68, 135); `ApplyEntityImpulse` (P world/item/enchantment/effects/ApplyEntityImpulse.java:35) | Direct `SetEntityMotion` to self or others. | main |
| g | Explosion | `ClientboundExplodePacket(..., Optional<Vec3> playerKnockback, ...)` per player within 64 blocks (P ServerLevel.java:2337-2346). **Exact doubles** (V network/protocol/game/ClientboundExplodePacket.java:34). The client adds it to its velocity. | main |
| h | Teleport delta | `ClientboundPlayerPositionPacket` (§2.2). Exact. | main |

Encoding of `SetEntityMotion` (V network/LpVec3.java):

- `scale = ceilLong(max(|x|,|y|,|z|))`;
- each component is quantized as `pack(v/scale) = round((v/scale·0.5 + 0.5)·32766)` into 15 bits;
- decoded as `(min(q, 32766)·2/32766 − 1)·scale`;
- below `3.051944088384301E-5` the whole vector is sent as `0`;
- a scale above 3 adds a VarInt continuation.

The client applies it with `entity.lerpMotion(movement)` (V client/multiplayer/ClientPacketListener.java:631-637).

Hooks:

- **Outbound hook** (§7): any `SetEntityMotion` whose id is the receiving player's own, any `ExplodePacket`
  with knockback, and any `PlayerPosition` packet is recorded as a pending simulation event under a transaction.
  The value recorded is the **decoded** vector (`LpVec3` round trip) for motion, and exact for the other two.
- `LegacyKnockback.sendVelocity` and vanilla (b) keep their packets. Afterwards the server's `deltaMovement`
  should follow the simulation, not `oldMovement`. Write `player.setDeltaMovement(auth.velocity())` in place of
  the restore when the player is under authority. Path (c)'s non-restore becomes harmless, because the
  simulation owns the value.

---

## 5. Tracker output: how player positions reach other clients

Players are tracked with `clientTrackingRange 32` (×16 blocks, scaled), `updateInterval(2)` and
`dontTrackDeltas` (V EntityTypes.java:1173-1184; same at P EntityTypes.java:1209-1210).
`TrackedEntity` gets `UpdateInterval.periodic(type.updateInterval())` (P ChunkMap.java:1015).

### `ServerEntity.sendChanges`

P 114-279; vanilla V 99-216.

- Every call:
  - `interpolationTracker.updateTracking(entity.trackingPosition())` (P 154). `trackingPosition()` is
    `position()` (V Entity.java:3874-3876).
- Then, when `forceStateResync || needsSync || updateInterval.test(tickCount) || entityData.isDirty()` (P 160):
  - Passengers: rotation only.
  - Otherwise:
    - `position = interpolationTracker.getPositionPath(currentPosition)` and `clear()` (P 179-180);
    - `createMovePacket(position, …)` (P 281-305):
      - `ClientboundEntityPositionSyncPacket` (full precision, carries the `PositionPath`) when forced, when
        `getRequiresPrecisePosition`, when `teleportDelay > 400`, when `wasRiding`, or when `onGround` changed;
      - otherwise `VecDeltaCodec.tryEncode(position)` gives `ClientboundMoveEntityPacket.Pos/PosRot` with a
        `VecDelta.Linear` or `VecDelta.Stepped`. Units are 1/4096 block; a delta beyond 32767 units
        (V network/protocol/game/VecDeltaCodec.java) falls back to `EntityPositionSync`;
      - `Rot` only when the position did not change;
      - "changed" means `|cur − base|² ≥ 7.62939453125E-6`, or `tickCount % 60 == 0`.
    - Leaf `reduce-useless-entity-move-packets` (default off) drops zero linear deltas (P 196, 327-334).
  - Head rotation.
  - `needsSync = false`, `forceStateResync = false`.
- Then the `syncVelocity` send (§4c).

### Server-side `SteppedInterpolationTracker`

P world/entity/SteppedInterpolationTracker.java. `LivingEntity.createInterpolationHandler` returns it on the
server (V LivingEntity.java:3302-3305; V SteppedInterpolationHandler.java:17-19).

- `updateTracking` shifts pending steps by the accumulated `predictedDelta`. `recordMovement(MoverType.PLAYER)`
  feeds that delta (P Entity.java:1431-1442).
- It increments `ticksSinceLastStep`.
- It adds a step **only if `entity.syncPosition`**, which is set only on a block bounce (V Entity.java:882).
- `getPositionPath` appends the current position with `ticksSinceLastStep`.
- So a player today sends **one step every 2 server ticks with `tickOffset = 2`**. 26.3 clients read these steps
  through the stepped interpolation behind MC-311974/311976.

### Where the tracker runs

- **Paper `newTrackerTick`** (P ChunkMap.java:1109-1130): for each tracker entity, `moonrise$tick(nearbyPlayers)`
  updates `seenBy`; then `sendChanges()` if it has players or is `ENTITY_TICKING`. `ChunkMap.tick()` returns
  through it (P 1132-1144). Vanilla's per-section loop is dead code.
- **Leaf async tracker** (`async.async-entity-tracker.enabled`, default false, LJ async/tracker/AsyncTracker.java):
  - `tick` (56) runs `handlePlayer` on main, then submits `TrackerTask`s (LJ TrackerTask.java:19).
  - Tasks run on the `Leaf Async Tracker Thread` pool and call `ServerEntity.leaf$sendChanges(ctx, …)`
    (P ServerEntity.java:487-~620). That reads entity state racily, plus `TrackerInput` captures from
    `recordMovement` and bounce (P Entity.java:1434-1441, 1486-1492).
  - Packets are collected per connection in `TrackerCtx` and **sent on main** in `onEntitiesTickEnd` (111) or
    `onTickEnd` (125) → `TrackerCtx.handle` (LJ TrackerCtx.java:143-~190). They are bundled when
    `ASYNC_TRACKER_PROTOCOL_LIB_FIX` (384-420).
  - `seenBy` becomes a synchronized set (P ChunkMap.java:1318).
- `ChunkMap.move(player)` (P 940-970) no longer walks trackers (Paper). It updates the distance manager and
  Moonrise maps (`PlatformHooks.updateMaps`). Chunk loading follows `ServerChunkCache.move` (P 818-825), called
  from `handlePlayerPositionChange` (P SGPLI:1825) and from the camera/passenger paths.

### Hook for exact per-tick paths

- After each simulation step (§2.4), append `PositionStep(simPos_k, 1)` to a per-player buffer. Use larger tick
  offsets if client ticks are skipped (timer deficit or load).
- In `ServerEntity.sendChanges`, for an entity that is a `ServerPlayer` under authority:
  1. Replace `interpolationTracker.getPositionPath(currentPosition)` (P 179) with `auth.drainPath(currentPosition)`.
  2. Treat the update interval as 1, or set `needsSync` whenever the buffer is non-empty.
  3. Keep `positionCodec.setBase(currentPosition)` consistent with the last step sent.
- The path's tick offsets are in **client ticks of the moving player**. Sending them every server tick keeps the
  viewer's interpolation fed at 20 Hz.
- With the async tracker on, the same drain has to be captured on main into the player's `TrackerInput`.
  Simpler: refuse authority mode when the async tracker is enabled.
- Also hook `TrackedEntity.updatePlayer`/`addPairing` (P ChunkMap.java:1488) only to log what each viewer was
  sent (start-of-tracking `EntityPositionSync`). The lag-compensation history needs it.
- Viewer-side transaction tracking for lag compensation: every move-entity packet *to a viewer* goes through
  that viewer's `SCPLI.send` (§7). The viewer's log of what it received, and when its pong confirms it, comes
  from the same outbound hook.

---

## 6. Where block changes reach a player

All go through `player.connection.send` (§7).

| Sender | Location | Packets |
|---|---|---|
| Chunk change broadcast | `ChunkHolder.broadcastChanges` (P server/level/ChunkHolder.java:275-319), from `ServerChunkCache.broadcastChangedChunks` (P 559-569) in the level's `chunkSource` phase | `ClientboundBlockUpdatePacket` (one change in a section) or `ClientboundSectionBlocksUpdatePacket`, plus block-entity packets; light updates to border players |
| Use on block | `SGPLI.handleUseItemOn` (P 2316-2317) | 2 × `BlockUpdate` (`pos` and `pos.relative(dir)`) |
| Prediction ack | `SGPLI.tick` (P 378-380); `handlePlayerAction` fast path (P 2177-2181) | `ClientboundBlockChangedAckPacket(seq)`. The client keeps its predicted blocks until this arrives. |
| Support blocks | `forceSendPlayerSupportBlocks` (P 1886-1912) | `BlockUpdate` |
| Dig failures | `ServerPlayerGameMode` (P server/level/ServerPlayerGameMode.java:203, 216, 244, 288, 362) | `BlockUpdate` |
| Chunks | `PlayerChunkSender.sendChunk` (P server/network/PlayerChunkSender.java:81-96, anti-xray), from Moonrise `RegionizedPlayerChunkLoader` (M patches/chunk_system/player/RegionizedPlayerChunkLoader.java:443) | `ClientboundLevelChunkWithLightPacket`; `ClientboundForgetLevelChunkPacket` (470) |
| Plugin fake blocks | `CraftPlayer.sendBlockChange`, `sendMultiBlockChange`, `sendBlockChanges` (PS org/bukkit/craftbukkit/entity/CraftPlayer.java:922-990) | `BlockUpdate` / `SectionBlocksUpdate`. **HCF combat-tag walls are client-only blocks that the client collides with.** |
| Falling blocks | `FallingBlockEntity` (V world/entity/item/FallingBlockEntity.java) | `ClientboundAddTransientBlockPacket` (new in 26.3) |

Hook: in the outbound hook, keep a per-player **client-view world overlay** for collision:

- record `(pos → state)` for every block packet, under the transaction that delivers it;
- record chunk loads and forgets;
- record client predictions: a block placed on `UseItemOn` with sequence `s`, kept until a
  `BlockChangedAck ≥ s` is acked.

The simulation reads collision shapes from the overlay first, then from the live world.

---

## 7. The per-connection outbound hook (transactions)

**Choke point:** `ServerCommonPacketListenerImpl.send(Packet<?>, ChannelFutureListener)` (P SCPLI:359-381).
Every play packet for a player goes through it:

- tracker packets, including the Leaf async tracker's `TrackerCtx.sendPacket` → `connection.send`;
- `ChunkHolder.broadcast`;
- `CraftPlayer`;
- `internalTeleport`.

`Connection.send` is only called directly for disconnect and pong-response (P SGPLI:3032-3035).

Packets that change the receiving player's own physics, and must be wrapped:

| Packet | Typical sender |
|---|---|
| `ClientboundPlayerPositionPacket`, `ClientboundPlayerRotationPacket` | `internalTeleport`, `forceSetRotation` |
| `ClientboundSetEntityMotionPacket` (id == self) | §4 |
| `ClientboundExplodePacket` (knockback present) | §4g |
| `ClientboundPlayerAbilitiesPacket` (flying, mayfly, flying and walking speed) | `ServerPlayer.onUpdateAbilities` (P ServerPlayer.java:1684, 2496), `PlayerList` (P 202) |
| `ClientboundUpdateAttributesPacket` (self: movement speed, jump strength, gravity, step height, scale, safe fall distance, movement efficiency, water movement efficiency, sneaking speed, interaction ranges…) | `ServerEntity.sendDirtyEntityData` (`sendToTrackingPlayersAndSelf`), `TrackerCtx.handleSyncAttribute` |
| `ClientboundUpdateMobEffectPacket` / `ClientboundRemoveMobEffectPacket` (speed, slowness, jump boost, levitation, slow falling, dolphin's grace, blindness…) | `ServerPlayer.onEffectAdded`/`Updated`/`Removed` |
| `ClientboundSetEntityDataPacket` (self: pose, shared flags, living-entity flags for item use, ticks frozen) | tracker `sendDirtyEntityData` |
| `ClientboundBlockUpdatePacket`, `ClientboundSectionBlocksUpdatePacket`, `ClientboundBlockChangedAckPacket`, `ClientboundLevelChunkWithLightPacket`, `ClientboundForgetLevelChunkPacket`, `ClientboundAddTransientBlockPacket` | §6 |
| `ClientboundGameEventPacket` (game mode change) | `ServerPlayer.setGameMode` |
| `ClientboundRespawnPacket` | §2.9 (simulation reset) |
| `ClientboundSetPassengersPacket` (self) | tracker |
| `ClientboundContainerSetSlotPacket`, `ClientboundSetHeldSlotPacket`, `ClientboundSetPlayerInventoryPacket` (held item or elytra changes use-speed and glide) | inventory code |
| World border (`ClientboundInitializeBorderPacket`, `SetBorderSize`, `SetBorderLerpSize`, `SetBorderCenter`, …) | `PlayerList.sendLevelInfo` / border listener |
| `ClientboundTickingStatePacket` / `ClientboundTickingStepPacket` (the client's own tick rate, needed for timer checks) | `ServerTickRateManager` (V server/ServerTickRateManager.java:34, 38, 122-123) |
| `ClientboundAddEntityPacket`, `ClientboundRemoveEntitiesPacket`, `ClientboundMoveEntityPacket`, `ClientboundEntityPositionSyncPacket` for other entities | tracker. Needed for the attacker's view (lag compensation), for hard collision (boats, shulkers), and for **entity push**: on the client, nearby players push the local player through `Entity.push(Entity)` (V Entity.java:1937-1966) when the team collision rule allows it (V EntitySelector.java:29-51). |

What to call:

- `auth.beforeSend(packet)` returns either the packet unchanged or a `ClientboundBundlePacket[Ping(id), packet]`.
  The ping comes first, so its pong marks "applied before tick n". Add a trailing ping only if both edges are
  needed. Coalesce: one ping per flush window per player is enough.
- Look inside existing `ClientboundBundlePacket`s (tracker pairing, Leaf protocol-lib bundles). Bundles are
  limited to 4096 sub-packets.
- **Thread**: usually main. Plugins may call `sendBlockChange` and similar from async threads, and Leaf
  `TrackerCtx` sends on main. Make the per-connection transaction log thread-safe (MPSC or `synchronized`).
  Assign the ping id and log the entry in the same critical section as the `connection.send`, so log order
  equals wire order.
- A ping inside a bundle is valid: `ClientboundPingPacket implements Packet<ClientCommonPacketListener>`, and
  `ClientGamePacketListener` extends it, so it fits `Packet<? super ClientGamePacketListener>`.

---

## 8. Collision code and bit-exactness

The simulation must reproduce the **client's** vanilla collision. The patched server differs from it in these
places:

1. **Paper `Entity.collide`** (P Entity.java:1802-1891) calls `CollisionUtil.performCollisions` (M
   patches/collisions/CollisionUtil.java:1811-1856) directly. Vanilla `collideWithShapes` → `Shapes.collide`
   (V world/phys/shapes/Shapes.java:223-233) returns `0.0` once `|distance| < 1e-7` and there is at least one
   shape. That is the first statement of each loop iteration, and it is repeated after each shape. Moonrise's
   per-axis loops (CollisionUtil:1687-1739) never snap. Results then differ in the last few ulps, by up to 1e-7
   per axis. The collision flags can differ too: `xCollision = !Mth.equal(delta.x, movement.x)` on the client,
   compared with a non-zero residual on the server. Paper keeps the snap in `VoxelShape.collide`
   (P world/phys/shapes/VoxelShape.java:878-884), but `Entity.collide` does not use it.
2. **Order.** Moonrise collides every AABB (entity hard collisions and single-box voxels), then every voxel shape,
   per axis. Vanilla walks the collider list in order: entity colliders, border, blocks. Each step is a monotone
   min/max with an `-1e-7` acceptance (V VoxelShape `collideX`; M CollisionUtil:105-122). The order only matters
   in epsilon corner cases.
3. **Collider gathering.** Moonrise uses `getCollisionsForBlocksOrWorldBorder(…, COLLISION_FLAG_CHECK_BORDER)`.
   Vanilla uses `collectCollidersIgnoringWorldBorder`, which adds the border shape only when
   `isInsideCloseToBorder` (V Entity.java:1182-1210 and the helper below it). The x/z-zero case uses a smaller
   cut box (P 1817-1822). It is equivalent.
4. **Gale "VMP skip entity move if movement is zero"** (P Entity.java:1316-1329) returns early and sets
   `onGround = false` when `delta == 0 && !boundingBoxChanged`.
5. **Gale "skip negligible planar movement multiplication"** (P Entity.java:1418-1426) skips
   `deltaMovement *= blockSpeedFactor` when `|dx|, |dz| ≤ 1e-6`. Vanilla always multiplies (V Entity.java:831-832).
6. **Leaf `performance.optimize-player-movement`** (default **on**) skips `maybeBackOffFromEdge`, the sneak edge
   back-off (P Entity.java:1330; MP 0203).
7. `Entity.move` asserts `TickThread.ensureTickThread` (P Entity.java:1278). It is main-thread only.

Conclusion:

- Implement the simulation's `move`, `collide`, `collideWithShapes`, `Shapes.collide` and the step-up candidates
  as a **verbatim port of vanilla 26.3** in `gg.hcfrevival.revival.auth`. Do not call `Entity.move`/`collide`.
- Read block collision shapes with `BlockState.getCollisionShape(level, pos, CollisionContext.of(player))` and
  pass them through the client-view overlay (§6). Moonrise caches shape data, but the coordinates are the same
  values.
- Entity hard collisions (boats, shulkers) come from the client-view entity set (§5/§7).
- Pin Leaf `optimize-player-movement` to false only if a server-side sneak-edge check against the live `Entity`
  is ever wanted. It does not affect a standalone port.

---

## 9. RevivalPaper combat hooks and how authority changes them

### 9.1 Patches and logic

The Minecraft-side 1000-series patches (MP 1000-1010) are minimal calls into RJ:

| Patch | Hook sites (P) | What it does |
|---|---|---|
| 1000 RevivalPaper config | `DedicatedServer` | Loads `revival.yml`. |
| 1001 Legacy attack damage | `Player.attack` (1087-1093, 1108, 1112, 1116) | 1.7 damage, Sharpness, crits, no sweep (`LegacyAttack`). |
| 1002 Knockback engine | `LivingEntity.knockback` (2190-2195), `Player.attack` sprint line (1100), `causeExtraKnockback` | `LegacyKnockback.targetMovement` replaces `targetMovement` before Paper's `EntityKnockbackEvent`. |
| 1003 Consistent knockback | `ServerPlayer` field (216), `ServerPlayer.tick` (747), `Player.causeExtraKnockback` (1288-1292); paper-patch 1001 `CraftEntity.setVelocity` (PS 237) | `MotionShadow` and the one-packet `sendVelocity`. |
| 1004 Sprint | field (217), `handlePlayerCommand` (P SGPLI:2914, 2918), `Player.attack` (1100), `causeExtraKnockback` (1279-1283) | `SprintLedger`: the sprint knock is armed by `START_SPRINTING`, used up by a legacy hit, re-armed only by stop then start. Legacy hits never clear the server sprint flag. |
| 1005 Damage immunity and defence | `LivingEntity` `lastHurt` (1582, 1594), armor/protection (2538-2608), `causeExtraKnockback` (1271) | 1.7 armor and Protection; extra knock on overflow hits. |
| 1006 Hit registration | field, `ServerPlayer.tick` (748), `Player.attack` (1070) | `ATTACK_SPEED +1024` transient modifier while a legacy weapon is held (no cooldown bar); optional attacks-per-tick cap; `beforeAttack` also clears `revivalBaseKnockTarget` (RJ LegacyHitRegistration.java:36-41). |
| 1007 Live profiles | `ServerPlayer` field, `handlePlayerCommand`, `LivingEntity` 2206 | Per-player knockback profiles; `RevivalKnockbackEvent`, `SprintArmEvent`. |
| 1008 Projectiles | `LivingEntity` 1580/1593, `FishingHook`, `AbstractArrow` | Rod, bow, snowball and egg knock from the shooter; the rod reel changes only the victim's MotionShadow (RJ LegacyProjectiles.java:147-170). |
| 1009 Sword blocking | `LivingEntity.isBlocking` (4664) and the blocking modifier (2577-2582); `Item` use, animation and duration; `ItemStack` codec; `HashedStack` | Server swords are usable; clients see a `CONSUMABLE` decoration. |
| 1010 Damage windows | `LivingEntity.hurtServer` (1496-1506), `couldPossiblyBeHurt` (1489) | Separate invulnerability windows per damage channel. No movement effect. |

Every hook runs on the main or entity-tick thread. AGENTS.md requires the state to be owned by the player's
tick thread.

### 9.2 MotionShadow today

RJ combat/MotionShadow.java.

**State.** `x, y, z`: the 1.7.10 server's motion for this player. A 1.7 server stepped player motion with no
input, and movement packets never changed it.

**Step.** Once per **server** tick, from `ServerPlayer.tick` (P 747), which runs in the level entity phase before
`doTick`. `tick(player)` (47-55) reads:

- `onGround = player.onGround()`. That is the **client's last claim**, forced at P SGPLI:1705 and 1827;
- block friction under the player, but only while horizontal motion is non-zero;
- fluid drag from server `isInWater()`/`isInLava()`. These were computed by the previous `doTick` baseTick at the
  client-claimed position;
- `Attributes.GRAVITY`.

`step` (63-86):

1. Each `|v| < 0.005` component becomes 0.
2. If `onGround && y < 0` then `y = 0`.
3. In liquid: `x,z *= drag (0.8 water, 0.5 lava)`, `y = y·drag − 0.02`, and stop.
4. Otherwise `y = (y − g)·0.98`.
5. Horizontal `*= onGround ? (float)friction·0.91F : 0.91`.

Steady state standing on the ground: `y = −g·0.98 = −0.0784`.

**Read.** `LegacyKnockback.motion(victim, current, profile)` (RJ LegacyKnockback.java:178-191):

- For `MotionModel.NONE`, or a victim that is not a player, it returns `current` (the server's `deltaMovement`).
- Otherwise it returns the shadow.
- With `groundPredictionDistance > 0` (0 in the `legacy-1.7` and `hcfr` presets, RJ config/KnockbackProfile.java:70, 92)
  and an airborne player, it may return `settled(shadow)`. That happens when `MotionShadow.landedForClient(ping −
  offset, knownMovement.y, distanceToGround, g, max)` says the client has already landed, from keepalive latency
  and a ballistic estimate (MotionShadow.java:93-124: `landingTicks` 97, `landedForClient` 118).

**Knock.** `KnockbackEngine.knockback(base, xd, zd, yaw, level, profile, resistance, rng)` (RJ
combat/KnockbackEngine.java:28-57):

- `x = base.x/friction − xd/dist·h`
- `y = min(base.y/frictionV + v, verticalCap)`
- `z = base.z/friction − zd/dist·h`
- plus the extra knock `(−sin·level·eh, ev, cos·level·eh)`
- then each axis is clamped to ±`velocityClamp`.

**Write.**

- `SPORTBUKKIT` (default) never writes the knock into the shadow, so every hit starts from the same no-input
  base: consistent knockback whatever the victim's packets did.
- `VANILLA_1_7` sets `shadow = knock` in `sendVelocity` (LegacyKnockback.java:169-171) and for projectiles
  (LegacyProjectiles.java:103-105), so combos compound.
- Overflow extra knocks set the shadow with no packet (LegacyKnockback.java:125-130, from commit `624f21ee`).
- The attacker slowdown scales the **attacker's** shadow (`onExtraKnock`, 236-248). The attacker's client never
  sees that slowdown (§2.6).
- Rod reel adds the pull to the shadow (LegacyProjectiles.java:161).
- `CraftEntity.setVelocity` sets the shadow (PS CraftEntity.java:237).

**Send.** `sendVelocity` sends the knock (exact double, `LpVec3`-quantised on the wire) and restores the server
`deltaMovement` to `oldMovement`.

### 9.3 What changes with a true simulated velocity

1. **Inputs to the shadow become authoritative.** Step the shadow with the **simulated** `onGround` and fluid
   state, not the client's claim. Either keep the per-server-tick cadence (true to 1.7, which stepped in the
   entity tick) or step per client tick at `ClientTickEnd`. The 1.7 server ticked at 20 Hz independently of the
   client, so per server tick stays the faithful choice. The ping-based ground prediction
   (`landedForClient`, `distanceToGround` ray casts) becomes unnecessary: the simulation knows whether the victim
   was on the ground at the last acked client tick and can extrapolate to the expected arrival tick from the
   measured transaction RTT.
2. **A new motion model, e.g. `AUTHORITATIVE`.** The knock base is `auth.velocityAt(latestSimulatedClientTick)`,
   the velocity the victim's client really had. Optionally step it forward with the victim's last input over the
   one-way delay in client ticks, so the base matches the moment the packet lands. This is the "26.x but correct"
   model. `SPORTBUKKIT`/`VANILLA_1_7` keep the 1.7 feel and keep reading the shadow.
3. **Applying the knock.** The client *replaces* its velocity with the packet value at the frame its bundle is
   handled. The simulation should:
   1. log `LpVec3.roundTrip(knock)` under the knock's transaction;
   2. at the client tick whose pong confirms it, set `sim.velocity = decoded knock` before stepping that tick;
   3. keep simulating the in-between ticks with the old velocity, exactly as the client did.
4. **Server `deltaMovement`.** Replace the `setDeltaMovement(oldMovement)` restores (P Player.java:1312;
   RJ LegacyKnockback.java:173) with `setDeltaMovement(auth.velocity())`. Then plugins, `PlayerVelocityEvent`
   readers, moved-too-quickly, `PositionMoveRotation.of`, and Bukkit `getVelocity` all see the true value.
   `PlayerVelocityEvent` keeps reporting the knock.
5. **SprintLedger.** Arm and consume from the simulated sprint state, which is the client sprint command applied
   to the client tick, independent of the `PlayerToggleSprintEvent` result. Its 1.7 rule (one sprint knock per
   sprint start) stays as it is.
6. **Hit registration.** The `ATTACK_SPEED` modifier is sent through attribute packets and changes the
   **client's** `cannotAttackWithItem` and `getAttackStrengthScale`. The simulation and attack validation must
   use the attribute state acked by the client (§7), not the server's live modifier.
7. **Sword blocking.** Use slowdown, `canSprint` while using, and use duration come from the client-view
   `CONSUMABLE` and `USE_EFFECTS` decoration (`LegacySwordBlocking.toClient`). The simulation reads items through
   it.

---

## 10. Hook map

**Thread**: N = Netty event loop of that connection; M = server main thread, either packet processing between
ticks or tick phases; A = any thread; T = Leaf async tracker pool.

Proposed calls are on a per-player `AuthPlayer` held as `ServerPlayer.revivalAuth`, with logic in
`gg.hcfrevival.revival.auth`. Each hook stays a one-line call with `// Revival - Authority` markers, per AGENTS.md.

| # | Concern | Patched site (P unless noted) | Today | Call / replacement | Thread |
|---|---|---|---|---|---|
| 1 | Input keys | `SGPLI.handlePlayerInput` 519 (before Paper events; `setLastClientInput` at 541) | stores the input; sneak gated by event | `revivalAuth.onInput(packet.input())` | M |
| 2 | Sprint | `SGPLI.handlePlayerCommand` 2892, before the CraftBukkit `PlayerToggleSprintEvent` block | event, `setSprinting`, Revival ledger (2914/2918) | `revivalAuth.onSprintCommand(action)`; enforce a cancelled event by correction | M |
| 3 | Rotation / claimed position | `SGPLI.handleMovePlayer` 1525 → `handlePlayerPositionChange` 1573 (after the passenger and sleeping branches, before the CraftBukkit counters at 1622) | re-collides the client delta, snaps, runs every check | `if (revivalAuth.recordClaim(x, y, z, yRot, xRot, onGround, hc, hasPos, hasRot)) return;` | M |
| 4 | Simulation step | `SGPLI.handleClientTickEnd` 3934, after the flag reset at 3941-3942 | Paper event; zero known movement | `revivalAuth.onClientTickEnd()`: step, compare, side effects (`absSnapTo`, `chunkSource.move`, `setOnGroundWithMovement`, `doCheckFallDamage`, `setKnownMovement`, stats, `PlayerMoveEvent`), path buffer, timer | M |
| 5 | Teleport sent | `SGPLI.internalTeleport` 2038, just before `send` at 2070 | `teleportSetPosition`, `awaitingPositionFromClient` | `revivalAuth.onTeleportSent(awaitingTeleport, destination, relatives)` | M |
| 6 | Teleport accepted | `SGPLI.handleAcceptTeleportPacket` 809, inside `id == awaitingTeleport` | snap; `handlePlayerPositionChange` | `revivalAuth.onTeleportAccepted(id, x, y, z, yRot, xRot)` | M |
| 7 | Correction | new `revivalAuth.correct()` → `internalTeleport(new PositionMoveRotation(simPos, simVel, 0, 0), Relative.ROTATION)` | n/a | absolute position and delta, relative-zero rotation | M |
| 8 | Transaction ack | `SCPLI.handlePong` 157 | empty | `if (this instanceof SGPLI) PacketUtils.ensureRunningOnSameThread(packet, this, server.packetProcessor()); revivalAuth.onPong(id)` | N → M |
| 9 | Outbound physics packets | `SCPLI.send(Packet, listener)` 359, before `connection.send` at ~374 | send | `packet = revivalAuth.beforeSend(packet)`: classify, log, wrap `[Ping, packet]` | A (mostly M) |
| 10 | Attack | `SGPLI.handleAttack` 3037: after the target lookup, self-kick and border check; replaces the range check at 3053 | Paper lenience range (≈6 blocks); `player.attack` | `revivalAuth.queueAttack(target)`; at tick end `validateLagCompensated()` → `player.attack(target)` | M |
| 11 | Swing / punch | `SGPLI.handlePunch` 2858 | swing, reset attack strength | `revivalAuth.onPunch()` (attack-strength ticker for the client-tick model) | M |
| 12 | Interact | `SGPLI.handleInteract` 3080 (range at 3096) | Paper lenience range | optional lag-compensated check | M |
| 13 | Use item | `SGPLI.handleUseItem` 2328 (start); `handleUseItemOn` 2234 (prediction sequence); `handlePlayerAction` `RELEASE_USE_ITEM` 2154; `handleSetCarriedItem` 2459 | server use state | `revivalAuth.onUseItem(hand, seq)`, `onUseItemOn(hit, seq)`, `onRelease()`, `onCarried(slot)`; items read through `LegacySwordBlocking.toClient` | M |
| 14 | Client loaded / respawn | `SGPLI.markClientLoaded` 3985; `markClientUnloadedAfterDeath` 3994; `PlayerList.respawn` (688-689); `ServerPlayer.teleport(TeleportTransition)` (1650/1678) | timers | `revivalAuth.onLoaded()`, `pause()`, `reset(level, pos)` | M |
| 15 | Zero-input travel | `LivingEntity.aiStep`, the `else if (this.canSimulateMovement() && this.isEffectiveAi()) this.travel(input);` statement (V 3134-3136) | moves the server player with no input, corrupting `deltaMovement` | skip when `this instanceof ServerPlayer sp && sp.revivalAuth.active()`; or restore `setDeltaMovement(auth.velocity())` after `SGPLI.tickPlayer` 412 | M |
| 16 | Block effects from movement | in `onClientTickEnd`, before the next `doTick` | from `move()` (`addMovementThisTick`, private, P Entity.java:1577) | new public accessor `Entity.revivalRecordMovement(from, to, delta)` → `addMovementThisTick(new Movement(from, to, delta))`, once per simulated tick | M |
| 17 | Velocity bookkeeping | `Player.causeExtraKnockback` restore at 1312; `LegacyKnockback.sendVelocity` restore (RJ 173); `ServerEntity.sendChanges` 256-278 | restore `oldMovement`, or keep the knock | `setDeltaMovement(auth.velocity())` when under authority; packets unchanged (logged by #9) | M |
| 18 | Knock base | `LegacyKnockback.motion` (RJ 178-191) | shadow or ground-prediction heuristic | new `MotionModel.AUTHORITATIVE` → `auth.velocityForKnock()`; legacy models keep the shadow fed by simulated `onGround`/fluid | M |
| 19 | MotionShadow step | `ServerPlayer.tick` 747 → `MotionShadow.tick` (RJ 47-55) | client `onGround` | read `auth.onGround()` and `auth.inWater()`/`inLava()` when active | M |
| 20 | Tracker path | `ServerEntity.sendChanges` 179 (`getPositionPath`) and the interval test at 160 | 1 step per 2 ticks | `auth.drainPath(currentPosition)`; force a send when the buffer is non-empty | M (T if the async tracker is on: refuse, or capture into `TrackerInput` in `AsyncTracker.get` / `TrackerTask`) |
| 21 | Viewer log for lag compensation | same outbound hook #9 on the **viewer's** connection (`MoveEntity`, `EntityPositionSync`, `AddEntity`, `RemoveEntities`) plus `TrackedEntity.updatePlayer` 1488 | none | `viewerAuth.logEntityUpdate(...)` | A/M |
| 22 | Block overlay | hook #9, plus `SGPLI.ackBlockChangesUpTo` 2449 / the `tick()` ack at 378 | none | overlay entries per transaction; predictions per sequence | M |
| 23 | Timer | `onClientTickEnd` counting vs. `Util.getNanos()`, plus tick-rate packets via #9 | CraftBukkit `allowedPlayerTicks` (wall clock) | balance-based client-tick budget | M |
| 24 | Kick paths left as they are | NaN, double-position, flying kick (`tickPlayer` 415-424) | as is | flying kick reads the simulation's `clientIsFloating` | M |

---

## 11. Configuration preconditions for authority mode

- `leaf-global.yml` `async.async-entity-tracker.enabled: false`. Racy reads and off-main packet construction
  (§5); `docs/revival/leaf-defaults.md` already pins it off.
- `async.parallel-world-ticking.enabled: false`. Packets for one player could otherwise be handled while another
  level ticks.
- `performance.reduce-packets.reduce-entity-move-packets: false`.
- Leave `network.OptimizeNonFlushPacketSending: false`.
- `paper-global.yml` `collisions.enable-player-collisions: false` (PS GlobalConfiguration.java:276; P
  PlayerList.java:325-330 puts players on a `NEVER` collision team). That removes player-on-player push from the
  client's physics, so the simulation does not need other players' exact client-side positions to stay exact.
- Keep Paper `chunks.prevent-moving-into-unloaded-chunks` off. The simulation decides.
- `use-legacy-tracker-ticking` decides whether the tracker runs before or after entity ticks. With per-client-tick
  path buffers the timing only shifts sends by under one tick. Keep `false` until it has been A/B tested
  (leaf-defaults.md).

---

## 12. Open points for other reports

- **Client physics port**: autojump. Clients may have `autoJump` on, which the server cannot see; it jumps without
  `Input.jump`.
- Client tick skipping above 10 ticks per frame (V Minecraft.java:1215), and client pause. Multiplayer clients do
  not pause.
- Exactly which client state a `SetEntityMotion` replaces (`lerpMotion` on `LocalPlayer`).
- Player-on-player push, if collisions stay on.
- Viewer-side interpolation (MC-311974/6): what step cadence and tick offsets keep 26.3's stepped interpolation
  smooth.
