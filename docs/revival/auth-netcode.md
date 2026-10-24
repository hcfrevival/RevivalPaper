# Authoritative netcode

Status: built 2026-10-21 on `revival/authoritative` (Phase B: replication, §15.3-15.6, input grammar, bypass audit,
visibility). Design: `authoritative.md` (§15 binding). Research:
`auth-research/02` (client stream, transaction rule), `04` (hook sites), `07` (FakeClient, RVTR).

This is the layer between the network and the movement port (`auth.sim`) and the combat package (`auth.combat`):
it decides **when** the client saw what, and feeds the simulation and the mirrors in the client's order.

## What exists

| Class | Job |
|---|---|
| `auth.Authority` | Static hook entry points (pong, outbound choke point, flush suspend/resume, block-change journal); server-wide mode, refusal, plug-in switches |
| `auth.AuthPlayer` | Per-player façade, field `ServerPlayer.revivalAuth`. Buffers each client tick's packets, runs windows at `TickEnd`, applies acked packets, drives the simulation, judges attacks, applies the truth in enforce |
| `auth.AuthMode` | `off` / `observe` / `enforce` |
| `auth.Simulation` | What the netcode needs from the movement port |
| `auth.PortSimulation` | `Simulation` over `auth.sim.MovementSim`/`SimPlayer`; keeps `SimPlayer`'s client-view fields in step with `ClientView` |
| `auth.EchoSimulation` | Stub that follows claims (every tick exempt `no-simulation`) |
| `auth.CombatWiring` | `ViewerMirrors` + `AttackJudge` over `auth.combat.MirrorBook`/`HitValidator`; `ProjectileViews` for projectile hits and pearl walls; `sharedRewindCap`, the one rewind cap of the judge, Neue's landing and projectiles |
| `auth.AttackJudge`, `auth.ViewerMirrors`, `auth.PathSink` | Plug-in interfaces (`Authority.set*`) |
| `auth.Eligibility` | Real 26.3 connection only (ViaVersion protocol, Floodgate, reached through their plugin class loaders) |
| `auth.view.ClientProfile` | Which client a player runs (§15.8): brand, `lunar:apollo` channel, the Apollo combat options the server pushed (by reflection), Lunar version and mods; shown by `inspect`, read by no rule yet |
| `auth.AuthStats` | Per-player counters for `/revival auth inspect` |
| `auth.net.ClientStream` | Windows `W_T`, the input grammar, sticky client state (input, rotation, `xLast`, position reminder) |
| `auth.net.Transactions` | Ping ids, outbound log, per-tick bundles, FIFO acks, deadlines, RTT base |
| `auth.net.OutboundClassifier` | Which clientbound packets change the client's physics (report 04 §7 + §15.6) |
| `auth.net.TickClock` | Grim balance timer (real-time ceiling, ack-clock floor, bank) |
| `auth.net.Wire` | `LpVec3` round trip (what the client decodes) |
| `auth.view.ClientView` | Client-owned state: attributes, effects, abilities, game mode (local and tab list), food, items, echoed flags/pose, tick rate, loaded/riding/dead/camera, teams, cooldowns, use prediction |
| `auth.view.ClientAttributes` | Vanilla client `AttributeInstance` semantics (same map types and history) |
| `auth.view.ClientItems` | Client-visible stacks (with `LegacySwordBlocking.toClient`) |
| `auth.view.WorldView` | What this client was sent: persistent sent states, pinned pre-change states, predictions, chunk set. Implements `SimWorld` and `PickWorld`. Keeps the chunk of its last read (held or not, the server's `LevelChunk`) while its chunk record and the server's full-chunk map are unchanged |
| `auth.view.SelfEcho` | Enforce-mode rewrite of self entity data and attributes (§3.3) |
| `auth.reconcile.Reconciler` | Exact / budget / correct decision, correction ids (and the last 32 sent), in-flight Δ, rate limit |
| `auth.reconcile.RebaseGuard` | With `rebase-epsilon` on: signed drift cap per axis per window of ticks |
| `auth.input.InputEnvelope`, `InputStats` | Attack cap; click and yaw-GCD statistics (two sensitivities, smoothed-camera turns skipped) |
| `auth.input.ClickBucket`, `CadenceWatch` | Neue's click cap (neue-combat §4.3) and the click-streak detector (§12.2 A) |
| `auth.input.InputRules` | The in-tick input grammar of a vanilla client (attack/use while using, attack after use, attack after a container close, container click while walking, anything after the move claim, a block clicked or dug out of sight) |
| `auth.input.AnomalyWatch` | `AuthInputAnomalyEvent` once per threshold crossing |
| `auth.net.BypassAuditor` | Netty handlers around the encoder: physics packets written around the choke point (as `Packet` objects or pre-encoded) |
| `auth.view.PredictionRules` | Which block changes a vanilla client predicts (a held block item, the block on its crosshair) |
| `auth.PathWiring` | Replication: `PathFeed` per player, K2 steps to viewers in `exact-radius` (`ranged-radius` for a viewer aiming a ranged item, off by default), handovers, re-pairs (R6 and inexact mirrors) |
| `auth.ExternalMovers` | Pistons, shulker boxes (block events) and shulker lids opening into the player: claims adopted within a band; a boat/shulker/happy ghast in contact whose client copy isn't exact, and launching geysers: the port's `EXTERNAL_MOVER` exemption, vanilla's claim bounded; rod pulls are modelled velocity |
| `auth.combat.SolidMirrors` | The boats, shulkers and happy ghasts this client holds, at the boxes it holds them while its player ticks (boats: `LinearInterpolationHandler`; shulkers: snap and peek; ghasts: `RemoteMirror`), in its entity-section order: `WorldView.collectEntityColliders`, the port's colliders (auth-combat §3.5). Fed by `SOLID_ENTITY` entries; copies gone inexact are re-paired |
| `auth.DebugHud` | `debug.hud` action-bar line and `debug.log` console lines (corrections, rejected hits, unjudged hits on players) |
| `auth.trace.TraceRecorder` | RVTR v1 writer (report 07 §4) |
| `config.AuthoritativeSettings` | `revival.yml` `authoritative:` |
| `command.AuthCommand` | `/revival auth status | mode | inspect | trace` |

## How a client tick runs

1. **Arrival.** Gameplay packets of a player under authority are not handled: the hook at the top of each handler
   (`AuthPlayer.intercept`) appends them to the current window. Pongs are acked at once (RTT, FIFO) and the entries they
   release are attached to the window at that point; teleport accepts are recorded there too (vanilla still handles
   server-teleport accepts at once). Chat, commands, keep-alive and custom payloads are immediate.
2. **`TickEnd(T)`** closes `W_T` and queues it, stamped with when it came off the wire (`ArrivalStamps`, a Netty
   handler in front of the connection's) and whether it is paced: a window that came less than `catch-up-gap-ms` after
   the one before with no pong in its drain is a catch-up tick of a burst (at most `max-banked-ticks` in a row). Queued
   windows run while the tick clock allows; a window that waited past `timer-queue-cap-ms` is refused (its actions
   update the client model but are not handled, no movement). The clock's bank caps idle time before a window's
   arrival, not time it waited in the server (a hitch), and the time the bank forfeits is owed back, at most one bank
   and within one bank's time, to the catch-up windows right behind it, so a stall's burst drains at once instead of
   leaving the player a bank behind (torture F4); time before the ack clock is never owed.
3. **A window runs** (`AuthPlayer.runWindow`), in arrival order:
   - entries past their ack deadline are force-applied; predictions past it expire;
   - drain: acked entries are applied to `ClientView`/`WorldView`/the simulation/the mirrors (velocity as `LpVec3`
     decodes it, teleports with client semantics, attribute `removeModifiers` + re-add, effect add/remove, chunk and
     block updates, prediction acks with the revert snap, respawn resets); accepts are checked as claims;
   - keybind and GUI packets are replayed through their handlers; the client's own effects are applied around them
     (slot, drop, release, use start, block predictions after the server handled them); each `Attack` steps the
     viewer's mirrors to tick `T`, applies the client's attack prediction, and is judged (`AttackJudge`); in enforce an
     invalid or over-cap attack is not replayed;
   - the simulated tick runs before the first `PlayerInput`/sprint/move packet (or at the end): exempt states first
     (from what the client applied: not loaded until `PlayerLoaded`, riding until a passenger list for its vehicle leaves
     it out (every vehicle's list is logged: a dismount's left the view riding, review 3 #2), dead, camera, spectator,
     sleeping, warmup),
     then the port; the claim is compared; observe moves the simulation onto the claim; enforce reconciles, corrects
     and writes the truth through `revivalApplyTruth` (the vanilla accepted-move path);
   - input, sprint and move packets replay (the move only in observe or exempt states, never while not loaded or, under
     enforce, warming up: review 3 #1), then `TickEnd` replays so vanilla's per-tick resets land at the right point. An
     exempt tick's path step is where vanilla's handler left the player then, not its claim (review 3 #7).
4. **Enforce extras.**
   - The input grammar (`InputRules`): an attack or use while the client uses an item (the release tick included), an
     attack after a use or after a container close in the same tick, a container click while the tick's input walks,
     anything after the move claim, a block click or dig start the client couldn't have aimed (`unseen-target`,
     pvp-issues WD-4: the block isn't on its crosshair, and another block's outline stands between its eye and the
     clicked point, or every sampled point of the dug face): not handled (the client's own prediction still runs, and
     reverts at the ack), counted, logged, and past
     `stats.grammar-violations` an `AuthInputAnomalyEvent`. A second position claim in one tick, more than 512 gameplay
     packets in one tick (drain replies have their own cap: a client back from a stall answers every ping it queued,
     review 3 #4) and a forged correction accept are protocol violations (kick); a second input, sprint command or other
     move packet (review 3 #5), a pong after the tick's keybinds and a server teleport never accepted are counted and
     kick only past `protocol-kick-rate` (§15.8).
   - The simulation is the only writer of pose, swimming, gliding and item use on the `ServerPlayer` (§15.4); the
     server's own recomputation is skipped. A change the server makes on purpose marks the field "meant": its self echo
     goes out as is and the simulation takes it at the ack; every other self echo of a client-owned field says what the
     client already has. The truth's `movement_speed` has the sprint modifier only while it sprints; a sync on its way
     that says the opposite is followed by a fresh one. A self attribute sync that differs only by the sprint modifier
     from what the client will hold once the syncs on their way land (the last movement-speed snapshot sent; a sync
     replaces the client's whole modifier set) is not sent at all (`SelfEcho.withoutSprintOnly`, counted as
     `sprintSyncsDropped`): the client copies any sync (MC-69459), and at 250 ms a stale one left a sprint block-hitter
     blocking at sprint speed. (Judged against the acked view, a sync undoing one still on its way was dropped and the
     client kept a soul-speed modifier on plain ground: code review 2026-10-23.) Likewise a self shared-flags byte whose
     server-owned bits (fire, invisible, glowing) are the ones last sent is left out of the echo (`flagEchoesDropped`):
     at 300 ms the echo of a block-hit's sprint stop landed after the re-sprint and undid it in the tick of the next hit,
     which went out without the sprint knock. Both records follow every self sync sent, filtered or not (an exempt
     tick's goes out raw).
   - The server's motion is the simulation's (written with the truth, except while a server velocity waits for the
     tracker): `getVelocity`, 26.x knockback and the move checks read it; `motion-model: authoritative` knocks from it.
     The sprint knock arms from the simulated sprint, and the attack reads the sprint the client attacked with.
   - Corrections are decided a window late, so a hotbar change by number key (report 02 H9) or the attack-strength reset
     tick can explain the claim first (bounded branching). They forecast truth and belief to the apply tick with every
     knock the client takes before it: the rest of the drain being run (their pongs are in, the drain that applies them
     isn't), then those on their way; the client's forecast takes all of them at its start, the truth's those not forced
     yet. An accept with a residual is forecast again the same way from where the client says it is. A correction that
     would land the client inside a block or keeps coming back, or a backstop that didn't halve the gap of the one before
     it, goes out absolute (position and velocity).
   - The belief is the client's own state (pvp-issues NW-1): stepped with the client's inputs in every tick the client
     ran, refused ones included (a freeze, the timer: only the belief runs them), and taking what the client applies:
     knocks, explosions and rod pulls in its drain (a forced one when it lands), its own sprint-hit slowdown, item use and
     release, our corrections. After a refused tick or forced entries it is stale, and the next correction absolute,
     until a claim lands where it put the client (within `correction-persist-min`): its velocity is the client's then.
   - The apply tick is measured, not guessed (NAS glitch 2026-10-22, `LandingLag`): every ping is stamped at its flush
     with the window the client is in (past the last `TickEnd` handled by real time, or by the `TickEnd`s off the wire
     that wait for the main thread) and how it was flushed (windows run between server ticks flush as they end; the
     server tick's bundle at its end), and its pong window gives that flush kind's trip. A correction is forecast
     `(window the client is in - window being run) + the median trip of its flush kind over the client's last
     landing-sample-ms of ticks` (a freeze doesn't age them), else `ceil(rttBase / 50) - 1` trip; a scope that ran
     windows and sent nothing gets a lone probe ping every `landing-probe-ms`. Pings flushed while no `TickEnd` came for
     two ticks (a stall, a client freeze) or right after one that ended a longer gap, and pongs answered in that drain,
     are not measured: a freeze's catch-up ticks are still on the wire then. `round(rtt / 50)` put every correction one
     tick short at 60 ms (or at 30 ms behind a busy server tick), so each accept was a tick of motion off and the
     backstop chased the client every other tick for as long as it moved.
   - A correction waits while windows the client already sent are queued behind the one running, or wait for the main
     thread off the wire, or the newest was a catch-up tick (a freeze's burst runs window by window, and a jittery link
     splits it), at most `max-banked-ticks` windows in a row: the truth runs them, inputs and all, instead of a forecast
     across them. While the belief explains the client it also waits until the gap stops moving: a knock on its way
     replaces both velocities where it lands (wait for it, at most an ack deadline of windows), otherwise the gap's
     velocity dies out with the client's drag (wait while the gap is within `attack-eye-offset-cap`, so its attacks are
     cast from its own eye, at most `correction-persist-ticks`). Settled is a drift under `correction-persist-min` over the
     forecast; a relative correction then lands the client on the truth in whatever window it arrives (NW-1: one landing
     a window off with the gap still moving missed by 0.2 to 2.4 mm, and the next knock froze that for a second
     correction). The accept backstop counts against `max-corrections-per-second` like any correction.
   - A claim off by less than `correction-persist-min` (1e-6, sub-µm) never counts toward `correction-persist-ticks`:
     counted, never corrected (a teleport aborts a dig and cancels block-prediction snaps; a relative one can't remove a
     rounding gap, only add one every 20 ticks).
   - Breaks the client predicts and the server denies (a protected block) are holes in the client's world only
     (`WorldView` holes): the belief, the forecast's client and the crosshair pick read them, the truth never does. A
     claim the belief explains, in a gap that opened in a tick touching a hole after an exact claim, is held (no
     correction); at the break's ack the client gets the block back and, with no teleport since and its box inside it,
     snaps to where it broke it (`syncBlockState`), which the belief mirrors. A correction there would have cancelled
     that snap (`onTeleport`) and left the client inside the restored block, falling through it. Honored predictions'
     reverts follow the same teleport rule now, and snap at the right block (the revert read its position after the
     fast iterator's `remove()`, which had already shifted another entry into the slot).
   - A forced server teleport is finished for the truth at the force (review X2-03): no longer pending, the truth is
     written and replicated from there, the next correction is absolute; its real pong re-applies nothing (one absolute
     resync) and only starts awaiting the accept. Forced entries hold corrections, the catch-up resync and re-pairs for
     one ack deadline after a forcing began, then one window acts and the hold starts over (X2-04: a 450 ms+ link, or
     one holding its pongs, was otherwise never corrected again).
   - Activation seeds the stream's rotation from the `ServerPlayer` (the client sends it only when it turns; cheat suite
     2 H1) and resets the window stamps (R2C-04). A use click after a hotbar key in one tick uses the new item (H2). An
     `Attack` is a click for the cadence detector whether or not its `Punch` came, and a window with more attacks than
     punches is an input-grammar count (`attack-without-punch`, counted, never dropped; L2). Every attack the grammar
     admits, and a `Punch` in a Neue lockout, records a Neue swing (L1).
   - Any exception from the port or the combat code (a tick, a drain entry, a correction, an attack's judge) falls back
     for that tick to the client's claim through vanilla's handler and re-seeds; it never kicks the player.
   - At activation the client's chunk list is seeded from Moonrise's per-player sent set, so a session that was already
     running doesn't read the server's loaded chunks for the client's.
   - Server teleports complete at their pong window (the `ServerPlayer` writes the truth again at once); the accept is
     only a claim. Players are hit only on a `VALID` verdict; the attacker's eye is the truth's, or its own claim
     bounded by the in-flight correction while one is out.

## Transactions

- Ping ids live in `[-2^30, -65537]` (below GrimAC's `-(n & 0x7FFF)`), start at a random point and count down.
  Correction teleport ids live below that band.
- During a server tick (flushes suspended) everything from the first physics packet on is gathered into one bundle
  `[Ping, ...]`, sent at `resumeFlushing`: one pong per connection per tick in steady state. Between ticks each physics
  packet gets its own bundle. Tracker bundles get the ping inserted. A heartbeat ping goes out every tick that sent none.
- Chunk packets (Paper extra packets, anti-xray readiness), the login packet and full bundles go out unbundled with a
  ping right after them (upper bound, flagged on the entry).
- Keep-alives are never bundled. A vanilla client answers a lone keep-alive on its network thread, at once, but a
  bundle's packets only when its main thread runs. Stalled past a second, it answered a later, lone keep-alive before a
  bundled one, and Paper kicks for a keep-alive answered out of order (torture `freeze1`: a 2 s client hitch at
  150 ms). They go out as they come, ahead of an open bundle; no transaction rides on them.
- A pong must match the oldest outstanding ping; skipped ids are a violation (released anyway), unknown ids in our band
  are a violation, recent duplicates are ignored, other ids pass through.
- Deadline: a ping sent after window `n` still unanswered `ceil(rttBase/50) + ack-jitter-ticks` paced windows later,
  or that many plus `max-banked-ticks` windows of any kind, has its entries force-applied (torture F3: a burst the
  client ticked before it could have had the packet doesn't count). A ping is stamped with the window the client is in
  by real time (an upstream stall doesn't leave a stale stamp), at most `max-banked-ticks` past the last `TickEnd` handled
  plus the `TickEnd`s that came off the wire and wait for the main thread (review X2-02: a stall stamped further let the
  client void its deadlines for as long as it had stalled; the waiting ones the client did send, and a server hitch's own
  flush stamped short of them was due as soon as they ran), never lower than the ping before it. A ping as old as `max-unacked-ms` (net of time the server was behind its own schedule) is due whatever
  the window counts say. A force-applied
  teleport, correction or knock is kept until its ping's real pong and handed back then (`LANDED`): a server teleport
  stays pending for the client until it lands (the truth takes it again there, its late accept is matched, never a
  violation; torture F2); a knock that lands makes the next correction one absolute resync. No correction is sent while
  forced entries are outstanding (when it would land, and on what, is unknown), nor in the window whose drain lands one
  (its pong already cleared it, but the client applies it in that tick: torture r80down300w, a knock forced one window
  before its pong was corrected as if the client would never have it, and five corrections chased the double). Corrections forecast the client with
  every knock still on its way to it (logged-unacked, torture F5), and an absolute one is held while any is. Once every forced entry has landed, the next correction is absolute (unless the claim after the
  landing is where the belief put the client) and the viewer's mirrors are re-paired (load P0: never while its acks lag). Past `max-unacked-ms` the player is frozen (windows refused) for as long as
  the stall lasts, and never kicked for it (pvp-issues NW-2: in HCF a kick is a combat-logger death); Paper's keep-alive
  timeout still applies. `rttBase` (`RttBaseline`) is the 10th percentile of the last 256 ack round trips, or of the last 32 when
  that is higher: a ping that went up (Wi-Fi, a route change) is followed once 29 of the last 32 acks are above the
  base, about 1.5 s, instead of the 12 s the long window alone took (every entry force-applied meanwhile, mirrors
  inexact, hits UNKNOWN). It falls through the long window, once a tenth of its samples are lower; a lone spike moves
  neither window. Both windows are kept sorted as they slide: exact on every ack, nothing allocated.
- The per-tick bundle's list is reused once its ping is acked (the pong proves Netty's unbundler is done with it;
  review PT-16). A forced ping reuses nothing. A new bundle takes the spare that held the most and is grown once to what
  recent bundles held (a max decaying 1/32 per bundle), not packet by packet.
- Every packet to every player passes `Transactions.send`, so what the packet's class decides is worked out once per
  class (`OutboundClassifier.ClassInfo`: the family, whether it is always bundleable, whether the tracked-entity sets look
  at it). Only the self/remote families test the entity id, only a packet Paper may hold (`isReady`/`getExtraPackets`
  overridden, the login) is asked per packet, and the trace recorder is asked once per packet.
- Verified on the code: a ping inside a bundle encodes (the game protocol registers `CLIENTBOUND_PING`), the client's
  bundler only rejects terminal packets, `handleBundlePacket` handles sub-packets inline on the main thread, and
  `handlePing` answers from that drain (`PacketBundlePacker`, `ClientPacketListener.handleBundlePacket`,
  `ClientCommonPacketListenerImpl.handlePing`).

## Hooks

| Patch | Site | Call |
|---|---|---|
| 1011 | `ServerPlayer` field | `revivalAuth = new AuthPlayer(this)` |
| 1011 | `SCPLI.handlePong` | `Authority.onPong` (re-enqueue ours onto the main packet queue, then ack) |
| 1011 | `SCPLI.send` | `Authority.send` in place of `connection.send` |
| 1011 | `SCPLI.suspendFlushing` / `resumeFlushing` | per-tick bundle open / sent (or heartbeat) |
| 1011 | `SGPLI.tick` | `onServerTick` (queued windows, the unanswered-ping freeze) |
| 1011 | `SGPLI.handle{PlayerInput, MovePlayer, PlayerCommand, ClientTickEnd, AcceptPlayerLoad, SetCarriedItem, PlayerAction, UseItem, UseItemOn, Interact, Punch, PickItemFromBlock, PickItemFromEntity, PlayerAbilities, ContainerClick, ContainerButtonClick, SetCreativeModeSlot, BundleItemSelected, ClientCommand}` | `if (revivalAuth.intercept(packet)) return;` |
| 1011 | `SGPLI.handleAcceptTeleportPacket` | `onTeleportAccept` (our correction ids skip vanilla) |
| 1011 | `SGPLI.internalTeleport` | `onTeleportSent` (server teleports are transactions) |
| 1011 | `LevelChunk.setBlockState` | `Authority.onBlockChanged` (pin the pre-change state) |
| 1011 | `ServerChunkCache.moonrise$setFullChunk` | `revivalFullChunks++`: `WorldView` keeps the chunk of its last read while the full-chunk map is unchanged |
| 1011 | `ClientboundMoveEntityPacket`, `ClientboundEntityEventPacket` | `revivalEntityId()` accessors |
| 1012 | `SGPLI.revivalApplyTruth` | the truth through `handlePlayerPositionChange`, with Paper's moved-too-quickly, moved-wrongly and clipped-into-block checks skipped for it (the simulation is the check). The truth path: `Entity.revivalMoveResolved` runs `move` with the truth's delta as the collided movement (no second collision pass), and the branch's interim positions (the move, the snap to the target, the snap back to the start for `PlayerMoveEvent`) skip the waypoint updates (`revivalQuietSnapTo`, `revivalQuietWaypoints` in `setPosRaw`); entity sections still follow every write, and the final snap runs everything. `revivalApplyTruth(..., false)` is the branch exactly as a claim takes it, the reference the live test `TruthPathCheck` compares against |
| 1012 | `LivingEntity.aiStep` travel | skipped while `revivalAuth.skipsTravel()` |
| 1011 | `SGPLI.handleContainerClose` (the client's one-argument overload) | main thread, then `intercept` (server-initiated closes stay immediate) |
| 1012 | `SGPLI.revivalCompleteServerTeleport` | vanilla's accept bookkeeping at a server teleport's pong, without the accept's coordinates |
| 1012 | `Player.tick` / `updateSwimming`, `LivingEntity.updateFallFlying` / `updatingUsingItem` | skipped while `Authority.ownsClientState` (§15.4: the simulation writes them) |
| 1012 | `Entity.setSharedFlag` / `setPose`, `LivingEntity.setLivingEntityFlag` / `resyncUsingItem` | `Authority.onServerFlag/Pose/Use/Resync`: a server change to a client-owned field is "meant" and its echo goes out as is |
| 1012 | `Entity.startRiding` | `Authority.refusesMount` (`vehicles: deny`) |
| 1013 | `SGPLI.handleAttack` | `intercept` (attacks judged in the window) |
| 1014 | `ServerEntity.sendChanges` / `addPairing` / `removePairing`, movement output | `Authority.drainPaths`, `onPaired`, `onUnpaired`, `sendMovement` (K2 paths, R7 suppression) |
| 1015 | `ChunkMap.TrackedEntity.updatePlayer` / `updatePlayerMulti`, after the vanish check | `Authority.visibleTo` (design §11) |
| 1016 | `LivingEntity.revivalHurtServer` (after the removed/dead check; the window rule), `SGPLI.handlePunch`, `Player.attack` (no-damage sound) | `NeueCombat.parried`, `windowRejected`, `swung`, `quiet` (neue-hooks.md P, W, S, Q) |
| 1017 | `Projectile.shootFromRotation` / `hitTargetOrDeflectSelf`, `ThrowableProjectile.tick`, `FishingHook.checkCollision`, `AbstractArrow.findHitEntities`, `ThrownEnderpearl.onHit`, `ThrownSplashPotion.onHitAsPotion` | `LegacyThrow`, `ProjectileHits`, `PearlLanding`, `LegacySplash` (projectile-hooks.md H1-H9) |

All listed in `scripts/revival/markers.txt`.

## Plugging in

- **Movement port:** `Authority.setSimulationFactory(player -> new PortSimulation(player.player()))` is the default.
  `PortSimulation` maps netcode calls to `MovementSim` and syncs `SimPlayer`'s client-view fields from `ClientView`
  before each tick; `WorldView` is the `SimWorld`. The local and tab-list game modes go to `setLocalGameMode` and
  `setTabListGameMode` at their own acks (login and respawn included); attached firework rockets the client holds
  (`AddEntity` with `DATA_ATTACHED_TO_TARGET` on the player, until `RemoveEntities`) boost after every tick, the belief's
  and the forecasts' too; a respawn carries the hidden `autoJumpOption` and `sprintWindow` over; seeds use
  `velocityAfterMove` (auth-sim-requests §1); `ExternalMovers` sets `externalMover`.
- **Combat:** `CombatWiring` is the default judge and mirror feed. Each viewer's `MirrorBook` lives in
  `AuthPlayer.combatState`; packets about other players arrive through `ViewerMirrors.onApplied` with their arrival
  tick and send time; `onTickEnd(T)` runs before the window's first attack is judged. `AttackerSnapshot` comes from the
  simulation (`xo/x`, `eyeHeight`, `box`), `tickYRot/tickXRot` and the client's interaction-range attributes. A living
  non-player target gets the block clip only (`judgeNonPlayer`, 2026-10-24): its tracker's sends feed its `SentTrail`
  through `Authority.sendMovement`.
- **Replication:** `PathSink.onTick` (`PathWiring`) appends every simulated client tick to the player's `PathFeed` (an
  exempt tick's from where vanilla's handler left the player);
  viewers within `exact-radius` get one 2-tick stepped move per tick in their per-tick bundle and no vanilla movement
  packets for that player (patch 1014); viewers farther away keep the vanilla cadence. A viewer coming into range starts
  from the vanilla codec base; one leaving gets an `EntityPositionSync` at that base; a jump the client would snap, or a
  viewer mirror that went inexact (a lag spike past the ack deadline), re-pairs the player on that viewer.
- **Reconcile:** `Reconciler` decides (exact / budget / correct / in flight / rate limited). In enforce the simulation
  keeps a belief (the client's own state: re-based on the truth at every exact claim, moved onto a diverged claim,
  stepped with the client's inputs in every tick it ran, refused ones too (`tickBelief`), taking the client's knocks
  (`beliefVelocity`), its attack slowdown and its item use). A correction runs truth and belief to the apply tick
  (`LandingLag`) with the latest input and the knocks the client takes first, and sends `Δpos`, `Δvel` relative
  (`Simulation.correction`), so the client lands on the truth at the tick it applies it; it waits until the gap stops
  moving (`velocityGap`). Without a belief it falls back to relative position and absolute velocity. The accept is
  checked as a claim (residual); the truth is written through `revivalApplyTruth`.

## Config (`revival.yml`)

All under `authoritative.` (defaults in brackets):

| Key | |
|---|---|
| `mode` [`off`] | `off`, `observe` or `enforce`; `/revival auth mode` overrides it until restart |
| `rebase-epsilon` [0], `rebase-drift-cap` [1e-9], `rebase-drift-window-ticks` [20] | adopt claims this close (ulp scale at most), with the creep guard |
| `correction-threshold` [1e-3], `correction-budget` [0.01], `correction-budget-decay` [0.95] | small divergences go into a decaying budget |
| `correction-persist-ticks` [20] | a claim off by less than the threshold this many ticks in a row is corrected anyway |
| `correction-repeat-limit` [2] | the same correction (or accepts that keep needing one without halving the gap) this many times goes out absolute |
| `correction-persist-min` [1e-6] | a claim off by less than this never counts toward the persist rule (counted as sub-µm) |
| `landing-probe-ms` [500], `landing-sample-ms` [2000] | a between-ticks flush that sent nothing gets a lone ping when its kind wasn't measured this long; corrections use the landings of the client's last this long of ticks (a freeze doesn't age them) |
| `max-corrections-per-second` [5] | |
| `max-rewind-ms` [600], `rewind-jitter-factor` [1.0], `rewind-slack-ms` [150] | the per-attacker rewind cap (HR-2, one method for melee, Neue's landing and projectiles): `min(rttBase, rtt-ceiling-ms)` + the client's measured display delay + jitter (spread capped at the ceiling) + slack; `max-rewind-ms` is only a sanity bound. Past the cap a hit is judged at the target's truth at the capped age (`HitValidator`): a high-ping shooter leads moving players; a player outside authority is judged where it stands while it hasn't moved since its tracker last sent it (review 3 #3) |
| `attack-eye-offset-cap` [0.5] | off the truth, attacks are cast from the claim only within this per axis (review E3) |
| `attack-eye-forecast-tolerance` [0.03] | ...and only for a gap the server caused (entries forced at their deadline, a refused window, a denied break, a revert snap) while every claim since the client was last on the truth landed within this of its belief, with no block between the truth and the claim; a made-up gap casts from the truth and counts as `eye offsets refused` (classic audit #1) |
| `block-clip-non-player-targets` [true] | design §7.1 (2026-10-24): a hit on a living non-player target (a mob, a villager, an armor stand, a mannequin) is refused, `INVALID BLOCKED`, when a block on the attacker's world view stands between its eye and everywhere the target may have been drawn over its view lag (`auth-combat.md` §2.2, pvp-issues WD-5); reach and aim stay vanilla's. A new key: no config migration |
| `timer-drift-ms` [120], `timer-ack-settle-ms` [100], `max-banked-ticks` [10], `timer-queue-cap-ms` [400], `catch-up-gap-ms` [15] | the tick clock; pongs bind the timer for later frames, or after the settle (review timer-backlog); a burst's forfeited time is owed back (F4); catch-up windows don't count for deadlines (F3) |
| `rtt-ceiling-ms` [250] | the ping lag compensation makes good, and what every slack (deadlines, visibility growth, correction lead) is sized from: `min(rttBase, this)` (review E5) |
| `ack-jitter-ticks` [4], `max-unacked-ms` [1500] | ack deadline; past `max-unacked-ms` the player is frozen (NW-2: never kicked for a stall) |
| `client-profiles.protocol-kick-rate` [20], `client-profiles.apollo-rules` [`follow`] | §15.8: ordering-only violations kick only past this many in 60 s (at most 4096, the most it counts: a higher value never kicked, review 3 #11); Apollo options read into the profile |
| `ineligible` [`allow`] | `deny` refuses ViaVersion/Geyser players while the mode is enforce (review E4) |
| `seed-max-move` [10] | a seed's claimed move and velocity, per axis (review E1) |
| `teleport-chunk-send-ticks` [2], `teleport-hold-max-ticks` [100] | PR-8: a server teleport into a chunk the client hasn't been sent waits for it; the netcode sends it itself after the first, and lets the teleport go after the second |
| `vehicles` [`deny`] | `deny` refuses mounting under enforce; `vanilla` rides exempt (client-trusted) |
| `max-attacks-per-client-tick` [1], `heartbeat-ping` [true], `bypass-audit` [true] | (`honor-denied-predictions` did nothing and is gone: config v7 removes it from the file, review 3 #8) |
| `replication.path-step-ticks` [2], `keep-alive` [true], `exact-radius` [24], `pre-roll` [true], `respawn-distance` [48], `path-buffer` [64], `repair-interval-ticks` [20] | K2 paths; a viewer whose mirror of a target went inexact gets it re-spawned at most this often |
| `replication.ranged-radius` [0], `ranged-linger-ticks` [100], `ranged-items` [bow, crossbow, fishing_rod, ender_pearl, snowball, egg, blue_egg, brown_egg, trident, wind_charge] | pvp-issues VI-1: a viewer holding one of the items in either hand, or within the linger after, gets paths out to `ranged-radius` instead of `exact-radius`; 0 (off) until design §15.6 is signed off for 64 |
| `replication.low-jitter-step-ticks` [1], `low-jitter-ms` [25], `high-jitter-ms` [35], `step-switch-seconds` [5] | K per viewer (torture K1): a viewer whose own ack jitter (p90 − p10 of a full 256-ack window) stays under 25 ms for 5 s gets K1, over 35 ms for 5 s K2 again; a switch continues the paths fed to it at the new K from their next step (no re-pair) |
| `mirror-history-ticks` [16] | lag compensation history per viewer-target pair; hits read their own tick's box and measure the rewind from its stamp, so 16 outlasts the 12-tick rewind cap (64 before config v4, migrated; review PT-16) |
| `external-movers.block-window-ticks` [12], `block-range` [14], `band` [0.6], `drift-cap` [1.6], `entity-range` [0.1], `max-move` [1.0], `geyser-depth` [24] | pushes (pistons, shulker boxes, shulker lids) while the truth's box is in what they move (a piston's lane, `block-range` long, a box's lid side): of a claim within the band only the part along the push, with its sign, at most 0.51 a tick and the push's travel (a piston 1.02, a lid 0.6), drift-capped (classic audit #4); a solid entity in contact whose client copy isn't exact, and a launching geyser (an erupting or continuous potent sulfur, unbroken water up to the feet and air within vanilla's 4 water blocks, `geyser-depth` below the feet at most: review 3 #6), exempt, vanilla's claim within `max-move` |
| `visibility.enabled` [false], `always-visible-distance` [8], `check-interval-ticks` [2], `hide-after-checks` [2], `max-speed` [1.0], `max-checks-per-tick` [512], `effect-reach` [1.5] | anti-ESP; the check budget is server-wide |
| `stats.anomaly-alerts` [true], `double-click-ratio` [1.0: 0.3 flagged butterfly clickers, classic audit #12; config v6 moves 0.3 up], `double-click-min-attack-ticks` [40], `aim-gcd-miss-ratio` [0.5], `aim-min-turns` [200], `click-streak-ticks` [100], `click-lock-clicks` [150], `click-lock-residual` [0.75], `click-lock-min-cps` [8.0], `grammar-violations` [3], `jump-reset-share` [0.65], `jump-reset-base-ratio` [4.0], `jump-reset-min-knocks` [40], `tap-lock-samples` [100], `rewind-stretch-hits` [30], `rewind-stretch-ms` [100], `pong-lag-ms` [150] | `AuthInputAnomalyEvent` thresholds (click-lock: that many intervals between clicks, each k or k+1 ticks, none of the clicks further than the residual from their least-squares line, at the minimum rate or faster, held for that many clicks more before the alert, neue-combat §12.2 detector B; jump-reset: the share of grounded knocks whose own window pressed jump, at or past `jump-reset-share` and `jump-reset-base-ratio` times the player's own grounded jump rate, classic audit #8; w-tap-lock and block-hit-lock: that many hits in a row followed by the same tap to the tick, pvp-issues SP-4; rewind-stretch: the median of the last valid hits' rewind past rttBase and the measured display delay, HR-3); seconds of pongs behind the keep-alive (counted) |
| `trace.directory` [`traces`], `debug.hud` [false], `debug.hud-interval-ticks` [5], `debug.log` [false] | |

Knockback profiles take `motion-model: authoritative` (knock a player under authority from its simulated motion; others
as `sportbukkit`).

Authority refuses (stays off, logs once) with Leaf `async.async-entity-tracker`, `async.parallel-world-ticking`,
`reduce-packets.reduce-entity-motion-packets` or `reduce-packets.reduce-entity-move-packets` on (pvp-issues SV-3), and
warns once with `network.OptimizeNonFlushPacketSending` on (a packet sent without a flush waits for the next one).
Per player: only real connections speaking this protocol (not ViaVersion-translated, not Floodgate). `/revival auth
inspect` warns when the client's own team collision rule is not `NEVER` (other players could push it).

## Commands

`/revival auth status`, `/revival auth mode <off|observe|enforce|config>` (until restart), `/revival auth inspect
<player>`, `/revival auth trace start|stop <player>` (RVTR v1 to `trace.directory/<uuid>/<epochMs>.rvtr`). Permission
`revival.command.auth`.

## Testing

```bash
export JAVA_HOME=~/Developer/tools/jdk25/Contents/Home
./gradlew :leaf-server:runRevivalTests      # unit
./gradlew createPaperclipJar :leaf-server:revivalLiveTestPlugin && python3 scripts/revival/live-tests.py
```

Unit (`leaf-server/src/test/java/gg/hcfrevival/revival/auth/**`): transactions (ids, FIFO, bundles, deadlines, stamps),
landings (a burst's waiting `TickEnd`s, aged by the client's windows), the drain's knocks for a forecast, the belief's
knocks and the gap's velocity, the client stream (windows, grammar, second claims, overflow, window age), the tick
clock, `LpVec3`, attributes, self echo (meant fields, speed syncs), the classifier (rockets, pistons and shulker boxes),
the reconciler (in-flight Δ, bounded eye, forged and stale accepts), the rebase guard, the input rules (one test per
rule), the anomaly watch, the bypass auditor (packet level, pre-encoded, skipped expectations), prediction rules,
occlusion and visibility hysteresis, the sprint modifier from state, config.

Live (`AuthLiveScenarios`, `FakeClient`, a client-side model running the same port on what the client applied): under
`observe` one window per client tick, server teleports and velocity applied exactly at their ack window, an honest walk
bit for bit, pong violations. Under `enforce`: the collision-rule precondition; an honest walk with no correction and the
server player on the truth; one correction for a client half a block off; replication (every move a one-step 2-tick
path, the viewer's mirror never freezes, trails by a steady K2 delay, stays exact); §15.4 (the server pose follows the
simulation while sneaking; a pose the server sets reaches the client and the simulation owns it again after the ack; a
number-key slot switch while blocking takes the hotbar branch, no correction); §15.5 (a plugin's `setVelocity` from
`PlayerMoveEvent` and a cancelled flight toggle with `setVelocity` reach client and truth at the ack tick with no
correction; a double-tap sprint sword hit and a charged axe hit deal the same damage and knock with authority on and
off, judged `VALID`); `vehicles: deny`; the input grammar (attacks while blocking dropped and reported, a container click
while walking dropped and the container resent); death and respawn (no tick of the dying client judged; the new local
player simulated from a standstill); visibility (a player behind a wall is removed from the viewer's client and spawned
again when the wall goes). Then `TruthPathCheck`: the port walks a course (pressure plate, soul sand, cobweb, slab, a
staircase to a 5-block drop, a pool, a ladder, a 3-block drop, a pit, turns on the spot) for 420 ticks, and two fresh
players take those moves, one through the claim's branch (`revivalApplyTruth(..., false)`) and one through the truth path,
each with the block effects and fluid state of the player's own tick in between: after every move the state (position,
rotation, box, ground and collision flags, fall distance, motion, supporting block, steps, stuck multiplier, movement
records, known movement, health, food, statistics, the handler's lastGood/lastPos/floating bookkeeping), the events (move,
jump, game events, damage, statistics, exhaustion, block insides, plate presses, redstone) and the packets to the player
and to a watcher (sounds field by field, pitch aside) must be the same; only waypoint packets may differ (166 → 56 on the
course: the interim positions no longer send any). Then `AuthCheatScenarios` (the cheat suite, docs/revival/auth-cheat-suite.md). With Leaf's async tracker on (the
second run) authority refuses.

## Gaps

- The belief takes the client's knocks, attack slowdown and item use, but self attribute syncs, self entity-data echoes
  (sprint, pose, use flags) and server teleports only by re-basing on an exact claim: a correction forecast while the
  client is off the truth across one of them misses by it (`PortSimulation.attributes`, shortcut). The settle wait
  needs the gap to stop moving; a gap whose velocity outlasts it (in the air, `correction-persist-ticks`) is corrected
  unsettled, and a knock landing on that correction's miss leaves a sub-mm gap for the persist rule.
- Prediction admission follows a vanilla client's held item and crosshair; dig progress per tick is left to Paper's
  own break checks. Every sequence-carrying packet is acked when its window runs, even when Paper then drops it.
  Block clicks and dig starts are checked for sight (`unseen-target`); entity interactions are not.
- World border lerp is not modelled. Boats, shulkers and happy ghasts are the port's colliders at the boxes the client
  holds (`SolidMirrors`, auth-combat §3.5); a tick touching one whose copy isn't exact (until a re-pair), or in a
  launching potent sulfur geyser's water, is exempt (vanilla takes the claim within `external-movers.max-move`, a re-seed
  after).
  Pistons, shulker boxes and shulker lids opening into the player are banded, rod pulls modelled.
- CraftBukkit's `justTeleported`: after a teleport, the first `PlayerMoveEvent` whose `from` lags the player returns
  before the move is written. `applyTruth` checks the ServerPlayer landed on the truth (no teleport pending, not a
  passenger) and writes it once more if not (no second event: its `from` is the target by then); counted as
  `truthRewrites`. Live: "the first moves after a teleport write the truth".
- Rewind ages are on the lag-compensation clock (`Authority.clockMillis`): wall time less how far the server is behind
  its own tick schedule, a tick running past its slot included (a hitch is off it while it runs and until the catch-up
  ticks run; jitter never accumulates; skipped ticks stay off), so a server hitch is never charged to the attacker
  (torture F6). It is the wall clock: a step of it (NTP, a VM or NAS resume) shifts every stamp for a moment, a shortcut
  noted at `Authority.clockMillis` (review 3 #9).
- Config version 3 moves a file still holding an old default to the new one (`max-rewind-ms` 300/400 → 600,
  `rtt-ceiling-ms` 300 → 250, `external-movers.entity-range` 1.0 → 0.1); version 5 moves `timer-queue-cap-ms` 1000 →
  400 (the clock drains backlogs now); values an owner chose stay; version 7 removes `honor-denied-predictions`, which
  did nothing (review 3 #8). Defaults are still written back into revival.yml, so every future default change needs
  such a rule.
- Kick policy (§15.8): only forged or unknown replies, skipped pings, overflow (a tick of 512 gameplay packets, 4096
  drain replies or 65536 released entries) and a second position claim kick at once (review 3 #4, #5); ordering-only
  violations, any other extra move packet among them, are counted (named in
  `inspect`) and kick past `protocol-kick-rate` in 60 s; a stall freezes. The keep-alive RTT isn't used against the
  ack RTT for any action: seconds with pongs more than 150 ms behind the keep-alive are only counted (review E5).
- A tick whose truth stands over a chunk our record lacks while the server has it loaded, with the client claiming
  ground, is exempt `chunk-unknown` (load-test regression, 2026-10-22); chunks Moonrise had sent at activation are held,
  and until the first ack after activation any loaded chunk counts as held.
- The bypass audit logs a bypassed packet at the next ping (upper bound): a client applies it earlier, so the truth
  can be a tick late on it and corrects; GrimAC's movement, velocity and setback handling must be off for players under
  authority (warned once when its packets show up).
- `ContainerSetSlot`/`SetContent` for a container other than the inventory menu resync the client items from the
  server inventory at the ack, once per window, with no snapshot (approximation; review PT-12).
- Visibility samples 9 points per box (81 rays when hidden); it costs most when many players hide from each other.
- Trace: no world snapshot (zero hash, ±48 box), chunk packets not recorded.
