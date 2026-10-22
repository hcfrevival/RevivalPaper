# Authoritative netcode

Status: built 2026-10-21 on `revival/authoritative`. Design: `authoritative.md` (§15 binding). Research:
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
| `auth.CombatWiring` | `ViewerMirrors` + `AttackJudge` over `auth.combat.MirrorBook`/`HitValidator` |
| `auth.AttackJudge`, `auth.ViewerMirrors`, `auth.PathSink` | Plug-in interfaces (`Authority.set*`) |
| `auth.Eligibility` | Real 26.3 connection only (ViaVersion protocol, Floodgate) |
| `auth.AuthStats` | Per-player counters for `/revival auth inspect` |
| `auth.net.ClientStream` | Windows `W_T`, the input grammar, sticky client state (input, rotation, `xLast`, position reminder) |
| `auth.net.Transactions` | Ping ids, outbound log, per-tick bundles, FIFO acks, deadlines, RTT base |
| `auth.net.OutboundClassifier` | Which clientbound packets change the client's physics (report 04 §7 + §15.6) |
| `auth.net.TickClock` | Grim balance timer (real-time ceiling, ack-clock floor, bank) |
| `auth.net.Wire` | `LpVec3` round trip (what the client decodes) |
| `auth.view.ClientView` | Client-owned state: attributes, effects, abilities, game mode (local and tab list), food, items, echoed flags/pose, tick rate, loaded/riding/dead/camera, teams, cooldowns, use prediction |
| `auth.view.ClientAttributes` | Vanilla client `AttributeInstance` semantics (same map types and history) |
| `auth.view.ClientItems` | Client-visible stacks (with `LegacySwordBlocking.toClient`) |
| `auth.view.WorldView` | What this client was sent: persistent sent states, pinned pre-change states, predictions, chunk set. Implements `SimWorld` and `PickWorld` |
| `auth.view.SelfEcho` | Enforce-mode rewrite of self entity data and attributes (§3.3) |
| `auth.reconcile.Reconciler` | Exact / budget / correct decision, correction ids, in-flight, rate limit |
| `auth.input.InputEnvelope`, `InputStats` | Attack cap; click and yaw-GCD statistics |
| `auth.trace.TraceRecorder` | RVTR v1 writer (report 07 §4) |
| `config.AuthoritativeSettings` | `revival.yml` `authoritative:` |
| `command.AuthCommand` | `/revival auth status | mode | inspect | trace` |

## How a client tick runs

1. **Arrival.** Gameplay packets of a player under authority are not handled: the hook at the top of each handler
   (`AuthPlayer.intercept`) appends them to the current window. Pongs are acked at once (RTT, FIFO) and the entries they
   release are attached to the window at that point; teleport accepts are recorded there too (vanilla still handles
   server-teleport accepts at once). Chat, commands, keep-alive and custom payloads are immediate.
2. **`TickEnd(T)`** closes `W_T` and queues it. Queued windows run while the tick clock allows; a window that waited
   past `timer-queue-cap-ms` is refused (its actions update the client model but are not handled, no movement).
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
     (from what the client applied: not loaded until `PlayerLoaded`, riding, dead, camera, spectator, sleeping, warmup),
     then the port; the claim is compared; observe moves the simulation onto the claim; enforce reconciles, corrects
     and writes the truth through `revivalApplyTruth` (the vanilla accepted-move path);
   - input, sprint and move packets replay (the move only in observe or exempt states), then `TickEnd` replays so
     vanilla's per-tick resets land at the right point.

## Transactions

- Ping ids live in `[-2^30, -65537]` (below GrimAC's `-(n & 0x7FFF)`), start at a random point and count down.
  Correction teleport ids live below that band.
- During a server tick (flushes suspended) everything from the first physics packet on is gathered into one bundle
  `[Ping, ...]`, sent at `resumeFlushing`: one pong per connection per tick in steady state. Between ticks each physics
  packet gets its own bundle. Tracker bundles get the ping inserted. A heartbeat ping goes out every tick that sent none.
- Chunk packets (Paper extra packets, anti-xray readiness), the login packet and full bundles go out unbundled with a
  ping right after them (upper bound, flagged on the entry).
- A pong must match the oldest outstanding ping; skipped ids are a violation (released anyway), unknown ids in our band
  are a violation, recent duplicates are ignored, other ids pass through.
- Deadline: a ping sent after window `n` still unanswered at window `n + ceil(rttBase/50) + ack-jitter-ticks` has its
  entries force-applied. Past `max-unacked-ms` the player is frozen (windows refused); three times that, kicked in
  enforce. `rttBase` is the 10th percentile of the last 256 ack round trips.
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
| 1011 | `SGPLI.tick` | `onServerTick` (queued windows, unanswered-ping freeze/kick) |
| 1011 | `SGPLI.handle{PlayerInput, MovePlayer, PlayerCommand, ClientTickEnd, AcceptPlayerLoad, SetCarriedItem, PlayerAction, UseItem, UseItemOn, Interact, Punch, PickItemFromBlock, PickItemFromEntity, PlayerAbilities, ContainerClick, ContainerButtonClick, SetCreativeModeSlot, BundleItemSelected, ClientCommand}` | `if (revivalAuth.intercept(packet)) return;` |
| 1011 | `SGPLI.handleAcceptTeleportPacket` | `onTeleportAccept` (our correction ids skip vanilla) |
| 1011 | `SGPLI.internalTeleport` | `onTeleportSent` (server teleports are transactions) |
| 1011 | `LevelChunk.setBlockState` | `Authority.onBlockChanged` (pin the pre-change state) |
| 1011 | `ClientboundMoveEntityPacket`, `ClientboundEntityEventPacket` | `revivalEntityId()` accessors |
| 1012 | `SGPLI.revivalApplyTruth` | the truth through `handlePlayerPositionChange` |
| 1012 | `LivingEntity.aiStep` travel | skipped while `revivalAuth.skipsTravel()` |
| 1013 | `SGPLI.handleAttack` | `intercept` (attacks judged in the window) |

All listed in `scripts/revival/markers.txt`.

## Plugging in

- **Movement port:** `Authority.setSimulationFactory(player -> new PortSimulation(player.player()))` is the default.
  `PortSimulation` maps netcode calls to `MovementSim` and syncs `SimPlayer`'s client-view fields from `ClientView`
  before each tick; `WorldView` is the `SimWorld`.
- **Combat:** `CombatWiring` is the default judge and mirror feed. Each viewer's `MirrorBook` lives in
  `AuthPlayer.combatState`; packets about other players arrive through `ViewerMirrors.onApplied` with their arrival
  tick and send time; `onTickEnd(T)` runs before the window's first attack is judged. `AttackerSnapshot` comes from the
  simulation (`xo/x`, `eyeHeight`, `box`), `tickYRot/tickXRot` and the client's interaction-range attributes.
- **Replication:** `PathSink.onTick` gets every simulated (or exempt-claimed) client tick outside pending server
  teleports. `PathFeed` and the tracker hook (patch 1014) are not wired yet.
- **Reconcile:** `Reconciler` decides (exact / budget / correct / in flight / rate limited). In enforce the simulation
  keeps a belief (the client's own state: re-based on the truth at every exact claim, moved onto a diverged claim,
  stepped with the client's inputs). A correction runs truth and belief `n̂ = round(rttBase / 50)` ticks ahead with the
  latest input and sends `Δpos`, `Δvel` relative (`Simulation.correction`), so the client lands on the truth at the
  tick it applies it; without a belief it falls back to relative position and absolute velocity. The accept is checked
  as a claim (residual); the truth is written through `revivalApplyTruth`.

## Config (`revival.yml`)

`authoritative.mode` (default `off`), `rebase-epsilon` (0), `correction-threshold`, `correction-budget`,
`correction-budget-decay`, `max-corrections-per-second`, `max-rewind-ms`, `timer-drift-ms`, `max-banked-ticks`,
`timer-queue-cap-ms`, `ack-jitter-ticks`, `max-unacked-ms`, `vehicles` (`deny`/`vanilla`), `honor-denied-predictions`,
`max-attacks-per-client-tick`, `heartbeat-ping`, `replication.{path-step-ticks, keep-alive, exact-radius}`,
`visibility.{enabled, always-visible-distance}`, `stats.anomaly-alerts`, `trace.directory`.

Authority refuses (stays off, logs once) with Leaf `async.async-entity-tracker` or `async.parallel-world-ticking` on.
Per player: only real connections speaking this protocol (not ViaVersion-translated, not Floodgate). `/revival auth
inspect` warns when the client's own team collision rule is not `NEVER` (other players could push it).

## Commands

`/revival auth status`, `/revival auth mode <off|observe|enforce|config>` (until restart), `/revival auth inspect
<player>`, `/revival auth trace start|stop <player>` (RVTR v1 to `trace.directory/<uuid>/<epochMs>.rvtr`). Permission
`revival.command.auth`.

## Testing

```bash
export JAVA_HOME=~/Developer/tools/jdk25/Contents/Home
./gradlew :leaf-server:runRevivalTests      # unit: transactions, windows, timer, LpVec3, attributes, self echo, classifier, config
./gradlew createPaperclipJar :leaf-server:revivalLiveTestPlugin && python3 scripts/revival/live-tests.py
```

The live run (`AuthLiveScenarios`, `FakeClient`) joins a fake player that speaks the 26.3 protocol (pongs from inside
bundles, one accept per teleport, vanilla `sendChanges` order), with a client-side model (the same port, fed what the
client applied) producing its positions. Under `observe` it checks: one window per client tick; a server teleport is a
transaction applied exactly at its ack window and the accept matches it; a velocity packet goes out behind our ping and
is applied at the window its pong arrives in, as `LpVec3` decodes it; an honest 40-tick walk matches the simulation bit
for bit (47/47 claims exact, flags equal); unknown pongs are violations and foreign ones pass. Then `enforce`: refused
(stays observe) until the client is on a team with collision rule `NEVER`; after the team packets are acked, an honest
30-tick walk gets zero corrections and the server player sits exactly on the simulated position with the zero-input
travel skipped; a client that puts itself half a block off gets exactly one correction, accepts it right after its pong,
and is back on the true path bit for bit while the server player never took the cheated position. With Leaf's async
tracker on (the second run) it checks that authority refuses. All pass (2026-10-21).

## Gaps

- Enforce: the simulation as the only writer of pose/sprint/swim/glide/shift/use on the `ServerPlayer` and the skip of
  the server's own recomputation (§15.4), sim velocity read by `getVelocity`/`LegacyKnockback`/`MotionShadow` (§15.5),
  `vehicles: deny` mount refusal, attacks validated from the client's eye while a correction is in flight (§15.3): not
  done. A correction whose accept doesn't follow its pong is a violation and frees the reconciler for a new one; there is
  no separate timer for a correction whose pong never comes (its ack deadline force-applies it).
- Block predictions are admitted only where the server's own handling made the same change; judging what a vanilla
  client would have predicted (pick, held item, `BlockPlaceContext`, dig progress) is not done. Every
  sequence-carrying packet is acked when its window runs, even when Paper's spam limiter or the immobile check then
  drops it.
- World border lerp, entity colliders (boats, shulkers), pistons and rod pulls are not modelled (counted as external
  movers); `BlockEvent` pistons are only counted.
- Bounded branching for one-tick ambiguities (hotbar key vs scroll) is not done.
- The Netty bypass auditor (§15.1) and per-tick replication bundles (§8 R5, patch 1014) are not done.
- `ContainerSetSlot`/`SetContent` for a container other than the inventory menu resync the client items from the
  server inventory at the ack (approximation).
- Between server ticks each physics packet gets its own ping (not one per handler invocation); in-tick traffic is
  coalesced.
- Item stacks in logged inventory packets are converted to the client's copy at ack time, not snapshotted when logged
  (§15.6).
- Trace: no world snapshot (zero hash, ±48 box), chunk packets not recorded.
