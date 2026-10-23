# Authoritative RevivalPaper

Status: design, 2026-10-21. Branch `revival/authoritative` (RevivalPaper on Leaf `ver/26.3`, Minecraft 26.3).
Research behind every decision: `docs/revival/auth-research/01..07`. Cite those reports instead of re-deriving.

## 1. Goal

A vanilla 26.3 client joins and plays normally. The server, not the client, decides where every player is and
whether every hit landed:

- **Input-driven movement.** The server re-simulates each player's movement every client tick from what the
  vanilla client already sends: key state (`ServerboundPlayerInputPacket`), rotation (`ServerboundMovePlayerPacket`),
  sprint commands, item use, and the tick marker (`ServerboundClientTickEndPacket`). The simulated position and
  velocity are the truth. The client's reported position is a claim that is compared, never trusted.
- **Client prediction.** The vanilla client already predicts its own movement. Nothing to add.
- **Replay corrections.** The Java client cannot rewind and replay its inputs, so the server does the replay: it keeps
  the true trajectory going with the client's own later inputs and sends the client only the difference, as a
  relative position + velocity correction. The client is nudged onto the true path instead of being snapped back
  in time.
- **Lag-compensated hits.** The server reconstructs, bit for bit, where the attacker's client drew the target at the
  tick the attack was made (26.3 stepped interpolation replayed from the packets it was sent and the tick it applied
  each one), and accepts the hit only if the attacker's ray, from the authoritative eye with the attacker's rotation,
  picks that target. Then the hit is applied now.
- **Smooth 26.3 entities.** Other players are sent as exact per-tick authoritative paths shaped so 26.3's stepped
  interpolation never runs dry (fixes MC-311974 server-side).
- **Tick authority.** Every client tick is accounted for. A client cannot run more ticks than real time allows.
- **Bounded input.** Aim and click timing are the player's input and cannot be made authoritative by anyone (Bedrock
  included). They are bound to authoritative ticks (attacks per client tick cap), validated against what was on
  screen, and measured (click and rotation statistics) for staff.
- **Information control (optional).** Players who cannot be seen are not sent (anti-ESP).

Non-goals for v1: vehicles (boats, horses, minecarts) are exempt (vanilla handling); a client mod is not required
and not planned.

### Threat model

| Cheat | Handled by | Outcome |
|---|---|---|
| Fly, speed, step, spider, jesus, phase, no-fall, no-slow, high-jump, long-jump, strafe/bhop, omni-sprint, keep-sprint | authoritative movement | impossible: the client's claim is ignored, it is corrected to the simulated path |
| Anti-knockback / velocity | knockback applied to the simulation at the acked tick | impossible |
| Timer / tick speed, blink, lag switch | tick clock and budget | bounded to real time; stalled players freeze for everyone |
| Fake criticals, fake ground | simulated `onGround`/`fallDistance` | impossible |
| Reach, hitbox, through-wall hits, backtrack | exact lag-compensated hit validation with a capped rewind window | impossible beyond float epsilon and the window |
| Killaura hitting targets not under the crosshair | same | impossible; killaura that also aims passes (input cheat) |
| Autoclicker, macros | attacks per client tick cap, click statistics | bounded (cap), measured |
| Aimbot / aim assist | rotation statistics (sensitivity GCD, snap analysis) | measured, not prevented |
| ESP / tracers / nametag through walls | visibility culling (opt-in) | players behind walls are not sent |
| X-ray | Paper anti-xray (existing) | existing |

## 2. Architecture

```
inbound (Netty)            main thread, in client-stream order                         outbound (any thread, mostly main)
──────────────            ─────────────────────────────────────                        ───────────────────────────────────
Pong ──re-enqueue──▶ AuthPlayer.onPong ─▶ Transactions: entries acked in window W_T ─▶ ClientView / WorldView / Mirrors apply
Input, Command,           ClientStream: buffer W_T                                       ▲
Attack, Punch, Use*,      ...                                                            │ AuthPlayer.beforeSend(packet):
Move (claim) ───────▶     TickEnd(T): ┬─ apply pre-physics actions of W_T                │   classify, wrap Bundle[Ping, packet],
TickEnd ────────────▶                 ├─ validate queued attacks (HitValidator)          │   log under the ping id
                                      ├─ Sim.tick()  (exact vanilla 26.3 port)           │
                                      ├─ Reconciler: compare claim, re-base or correct ──┘
                                      ├─ apply truth to ServerPlayer (pos, onGround, fall, events, chunks)
                                      └─ PathFeed.append(step) → tracker sends K2 paths to viewers
```

### 2.1 Packages

All logic is plain source under `leaf-server/src/main/java/gg/hcfrevival/revival/auth/` (AGENTS.md rule 1). Patches
only add one-line calls into `gg.hcfrevival.revival.auth.Authority` / `AuthPlayer`.

| Package | Owns |
|---|---|
| `auth` | `Authority` (static hook entry points), `AuthPlayer` (per-player façade, field `ServerPlayer.revivalAuth`), `AuthMode` |
| `auth.net` | `ClientStream` (windows), `Transactions` (ping ids, outbound log, ack), `OutboundClassifier`, `TickClock` (timer) |
| `auth.view` | `ClientView` (client-owned state as the client holds it), `ClientAttributes` (client attribute semantics), `ClientItems` (client-visible stacks, use-item state), `WorldView` (block overlay, predictions, chunks, border) |
| `auth.sim` | the exact physics port: `SimPlayer` (state), `SimWorld` (world queries), `MovementSim` (the tick), `SimCollision`, `SimFluids`, `SimBlocks`, `SimMath` |
| `auth.reconcile` | `Reconciler`, `Correction` |
| `auth.combat` | `RemoteMirror` (26.3 stepped interpolation port), `MirrorBook` (per viewer), `PickRay` (client pick), `HitValidator` |
| `auth.replicate` | `PathFeed` (K2 per-tick paths), tracker integration |
| `auth.input` | `InputEnvelope` (click caps), `InputStats` (click and aim statistics) |
| `auth.visibility` | `Occlusion` (anti-ESP, opt-in) |
| `auth.trace` | `TraceRecorder` (RVTR v1, report 07 §4) |
| `config` | `AuthoritativeSettings` (revival.yml `authoritative:` section) |
| `command` | `/revival auth ...` |

### 2.2 Threading

Everything in `AuthPlayer` is owned by the main thread (AGENTS.md "Performance, threading"). The two exceptions:

- `AuthPlayer.beforeSend` can be called from any thread (plugins send block changes async). It only touches
  `Transactions`' outbound log, which is guarded by a per-connection lock taken in the same critical section as
  `connection.send`, so log order equals wire order.
- `handlePong` runs on Netty; its hook re-enqueues the pong onto `server.packetProcessor()` with
  `PacketUtils.ensureRunningOnSameThread`, so pongs are processed in stream order with the movement packets.

Authority refuses to start (logs why, stays `off`) when Leaf `async.async-entity-tracker`,
`async.parallel-world-ticking`, `performance.reduce-packets.reduce-entity-motion-packets` or
`performance.reduce-packets.reduce-entity-move-packets` is enabled (pvp-issues SV-3: the last two drop velocity and
move packets). `network.OptimizeNonFlushPacketSending` is warned once.

## 3. Client stream and transactions

Rules come from report 02 §5–6 and report 03 §7.1.

### 3.1 Windows

`W_T` = every serverbound packet after `TickEnd(T-1)` up to `TickEnd(T)`. `ClientStream` assigns each inbound packet
to the current window in arrival order. Packets in a window, in client order: drain replies (pongs, teleport
accepts, rotation replies), `SetCarriedItem`/`PlayerLoaded`, keybind actions (attack + punch, use, release, drop,
swap), aiStep packets (abilities, fall flying), `PlayerInput` (only on change), sprint command, one move packet or
none, `TickEnd`.

Processing in stream order:

- `Pong(id)`: `Transactions.ack(id, T)`; apply every outbound entry logged before that ping to `ClientView`,
  `WorldView` and the viewer's mirrors. Their effect is "applied before tick T".
- Teleport accept for one of our corrections: `Reconciler.onCorrectionAccepted(...)`. Accept for a server
  teleport: the simulation resets to the client's reported post-teleport state.
- Rotation reply to `ClientboundPlayerRotationPacket` (`MovePlayer.Rot` between ticks, flags false): rotation only.
- Keybind and aiStep packets: recorded into the window as `TickActions`.
- `PlayerInput`: becomes the window's key state (sticky).
- Move packet: the window's `Claim` (position if present, rotation if present, onGround, horizontalCollision).
- `TickEnd(T)`: run the tick (§5).

### 3.2 Transactions

- Ping ids: per connection, negative, counting down from `-1`; wrap past `Integer.MIN_VALUE` to `-1`. Pongs with
  non-negative ids are foreign and pass through untouched.
- Every physics-relevant packet to the player (`OutboundClassifier`, list in report 04 §7) is wrapped as
  `ClientboundBundlePacket[Ping(id), packet]` (a ping is inserted first into an existing bundle). The client handles
  a bundle in one drain, so `Pong(id)` in `W_T` means the packet was applied before tick T **exactly**.
- Exception: packets Paper must hold until ready (anti-xray chunks, `isReady() == false`) are not bundled; a ping is
  sent right after them, giving the upper bound (rule A). Chunk timing only matters for the unloaded-chunk gravity
  branch.
- Outbound entries store the **decoded** value the client will apply: `LpVec3` round trip for
  `SetEntityMotion`, exact doubles for teleports and explosion knockback.
- Transactions also time the connection: `ackRtt` (send → pong), in ticks and ms.

### 3.3 Self-echo policy

The server echoes the player's own entity data and attributes to them (report 02 §3.6, §3.10). For players under
authority, `AuthPlayer.beforeSend` rewrites self packets:

- `SetEntityData` for self: drop fields that are client-owned (shared flags bits sprint 3, swim 4, fall-flying 7;
  `DATA_POSE`; living-entity using-item flags) when the server did not mean to change them; when other bits of the
  flags byte must go, client-owned bits are set to the simulation's latest values. Whatever is sent is logged and
  modelled at its ack tick.
- `UpdateAttributes` for self: sent (server-owned), with the `sprinting` modifier on `movement_speed` present iff the
  simulation's latest sprint state is on. `ClientAttributes` models the client's `removeModifiers` + re-add exactly.

## 4. Client view and world view

`ClientView` holds the client's copy of everything movement reads (report 01 §7): attributes with client
semantics (base + modifiers, recomputed like `AttributeInstance`, hashing order included), effects (presence and
amplifier; never expire client-side), abilities (flying, mayfly, flying speed), game mode (local and tab-list),
food level, client-visible items (main hand, feet, glider slots, used stack through `LegacySwordBlocking.toClient`),
use-item state, shared flags and pose as the client holds them. It changes only through acked outbound entries and
the client's own actions in its windows.

`WorldView implements SimWorld`: the live server world overlaid with

- block changes logged but not yet acked (the client still has the old state), keyed by position;
- client predictions: blocks the client changed itself on `UseItemOn` / dig with sequence `s`, held until
  `BlockChangedAck ≥ s` is acked, with `BlockStatePredictionHandler` semantics including the revert snap
  (report 02 §3.9);
- chunks the client holds (loads/forgets acked); world border as the client lerps it.

Reads of collision shapes go through the overlay, then the live world. The simulation never reads Paper's patched
collision helpers (report 07 §1.6): only `BlockState`, `FluidState` and raw `VoxelShape` coordinates.

## 5. The client tick on the server

On `TickEnd(T)` (report 01 §11.4 is the full procedure):

1. `TickClock.onTick()`: within budget? If not, the window is refused (no movement), counted, and the divergence
   that follows is corrected.
2. Pre-physics actions of `W_T` in order: carried slot, release/use item (client prediction rules), the client's
   own attack prediction for each `Attack` (×0.6 and sprint off on a sprinting full-strength hit on a player,
   report 02 §2.4.1), block predictions into `WorldView`.
3. Hit validation for each queued attack (§7), using the eye at the end of tick T-1 and tick T's rotation.
4. `MovementSim.tick(simPlayer, clientView, worldView, keys, rotation)` — the exact port.
5. `Reconciler.compare(claim)`.
6. Apply the truth to the `ServerPlayer` (§6.3) and append a path step to `PathFeed`.
7. `InputStats`, `TraceRecorder` if recording.

Unsupported states are **exempt**: riding, sleeping, dead, not loaded, spectator camera on another entity, and any
state the port does not cover yet. Exempt ticks use vanilla handling of the claim; on leaving the state the
simulation is re-seeded from the client's next claim. Exempt ticks are counted per reason.

## 6. Authority and corrections

### 6.1 Modes

`authoritative.mode` (global, per-player override through the API):

- `off`: vanilla/Paper movement handling. No hooks act.
- `observe`: everything runs (simulation, transactions, mirrors, statistics) but the claim stays the truth and
  nothing is corrected or rejected. For rollout and debugging.
- `enforce`: the simulation is the truth; corrections and hit rejection are on.

### 6.2 Reconciler

Per tick with a claim position: `d = |claim - truth|`.

- `d ≤ rebase-epsilon` (default `1e-7`): exact. Adopt the claim's position into the truth (kills float drift).
- `d ≤ correction-threshold` (default `1e-3`): add `d` to a decaying budget; correct when the budget passes
  `correction-budget`.
- otherwise: correct now.

Ticks without a position claim check the client's own rule: the truth must stay within `2.0E-4` of the last position
the client sent, unless the 20-tick reminder is due (report 02 §2.6).

**Correction** (report 06 §7.3): one `ClientboundPlayerPositionPacket` with `relatives = {X, Y, Z, Y_ROT, X_ROT,
DELTA_X, DELTA_Y, DELTA_Z}`, `change = (Δpos, Δvel, 0, 0)`, bundled with a ping, teleport id from our own id space.

- `Δpos = truth(T).pos - claim(T).pos`.
- `Δvel = truth(T).vel - belief(T).vel`, where `belief` is the client's velocity: the simulation re-run from the
  client's claimed path (the claimed movement of tick T run through the port's post-move velocity rules).
- While a correction is in flight, the client's claims are expected to be off by the known Δ; the truth keeps
  advancing on the client's inputs, and nothing else is corrected. The accept (in `W_m`) carries the client's exact
  post-correction position: the residual `truth(m-1) - accept.pos` is checked like a claim. A second correction is
  sent only if the residual is over the threshold.
- Velocity-only divergence is fixed with zero Δpos.
- Never correct exempt states. Rate-limit corrections (`max-corrections-per-second`).

The vanilla accept handling (`absSnapTo(awaitingPositionFromClient)`) is skipped for our ids; server-initiated
teleports keep vanilla semantics and re-seed the simulation at their accept.

### 6.3 The truth on the `ServerPlayer`

After each simulated tick in `enforce` (the same as the tail of `handlePlayerPositionChange` today): position
(`absSnapTo` + chunk-source move), `setOnGroundWithMovement`, fall distance and `doCheckFallDamage` from the
simulation, known movement, statistics, `PlayerMoveEvent` per tick (cancel → absolute correction back to `from`),
`deltaMovement = sim velocity`. The vanilla zero-input `travel` in `doTick` is skipped for players under authority
(report 04 hook 15), so the player is moved once.

## 7. Combat

### 7.1 Hit validation

Attacks are queued in `handleAttack` (after Paper's target lookup, self and world-border checks; Paper's lenient
range check is replaced) and validated at `TickEnd(T)`:

- eye = simulated position at the end of tick T-1 as `xo + 1.0*(x - xo)` + eye height of the pose then;
- rotation = tick T's claimed rotation (or the last one);
- targets = every entity the attacker's client could pick, with player targets taken from the attacker's
  `MirrorBook` (exact boxes after the attacker's tick T-1 interpolation step);
- the client's `pick(1.0F)` replayed exactly (`PickRay`, report 02 §2.2): block clip (OUTLINE, no fluids) on the
  attacker's `WorldView`, entity clip, strict `<` comparisons, interaction range from the client's attributes;
- valid iff the pick returns the attacked entity. Then `player.attack(target)` runs as today (RevivalPaper combat,
  events). Invalid: dropped, counted, in `observe` only logged.
- Non-player targets: vanilla/Paper validation, unchanged.
- Rewind window cap `max-rewind-ms` (default 300): an attack that relies on mirror state older than the cap is
  invalid (anti-backtrack, ping spoof).

### 7.2 Knockback

Knockback is sent as today; the outbound entry applies the decoded velocity to the simulation at its ack tick. The
RevivalPaper `MotionShadow` keeps its 1.7 role (knock base) but steps with the simulation's `onGround` and fluid
state; a new knockback-profile `motion-model: authoritative` uses the simulated velocity as the base. `SprintLedger`
arms and consumes from the simulated sprint state.

## 8. Replication (other players)

Report 03 §6, rules R1–R7, recommended K = 2:

- One step per simulated client tick of the target, every server tick, each step `tickOffset = 2`;
  `MoveEntity.PosRot` with a one-step `Stepped` delta per step (rotation exact); `EntityPositionSync` with a stepped
  path past ±8 blocks; a 1/4096-block keep-alive toggle when still.
- One bundle per viewer per server tick ending in a ping, so every viewer's mirror knows the exact arrival tick.
- Vanilla movement output for player targets is suppressed in `ServerEntity` (and Leaf's async path, which is
  refused anyway).
- Big jumps (> 64 blocks from the viewer's mirrored display) re-spawn the target on that viewer.

## 9. Tick clock

Balance timer on the ack clock (report 06 §9.3): each `TickEnd` costs one tick; the budget refills at the client's
tick rate (`min(20, server rate)`, 20 when frozen) measured from the send time of the newest acked ping minus
`timer-drift-ms` (default 120). Bursts are allowed up to `max-banked-ticks` (default 10, the client's own per-frame
cap). Windows over budget are refused. A player who stops sending ticks does not move (no extrapolation).

## 10. Input envelope and statistics

- `max-attacks-per-client-tick` (default 1): extra `Attack` packets in one window are dropped (the client still
  applies its own attack prediction for each; the simulation does too).
- `InputStats` per player: attacks per tick histogram, click intervals in ticks, double-click ratio, rotation deltas
  and their sensitivity-GCD fit. `/revival auth inspect <player>` shows them; `AuthInputAnomalyEvent` (API) fires
  past configurable thresholds. Detection, not authority, by design.
- **Input grammar** (`InputGrammar`, authority): the vanilla client's keybind and screen pipeline makes some packet
  combinations impossible (report 02 §2.3–2.4). In `enforce` they are rejected, exactly, with no thresholds:
  - an `Attack` (or use click) in a window where the client was using an item at the start of that tick's keybinds:
    the client discards every click while using, even in the release tick (report 02 §2.4f). Kills autoblock.
  - an `Attack` in the same window as a screen close (`ContainerClose` from the client): the first attack click after
    a screen closes is dropped (`missTime = 10000`, report 02 §2.3).
  - container clicks in a window whose tick-T keys hold movement (screens release every key, report 02 §2.3):
    inventory-move and auto-refill-while-running. Toggle-key restoration on close is accounted for.
  - more than one move claim, or movement packets after `TickEnd` ordering rules (vanilla already kicks a second
    position claim, report 05 §0.2).
  Each rule is a pure function of the window and the simulated client state, unit-tested against the client code it
  mirrors.

## 11. Visibility (opt-in)

`visibility.enabled` (default false). For each viewer and player target inside tracking range: line of sight from
the viewer's eye region (expanded by the movement possible within the viewer's RTT) to the target's box (expanded by
the target's) through the viewer's `WorldView`; targets with no line of sight beyond `always-visible-distance`
(default 8, footsteps range) are not sent. Re-shown with `AddEntity` (mirror restart).

## 12. revival.yml

```yaml
authoritative:
  mode: enforce              # off | observe | enforce
  rebase-epsilon: 1.0E-7
  correction-threshold: 1.0E-3
  correction-budget: 0.01
  max-corrections-per-second: 5
  max-rewind-ms: 300
  timer-drift-ms: 120
  max-banked-ticks: 10
  max-attacks-per-client-tick: 1
  replication:
    path-step-ticks: 2
    keep-alive: true
  visibility:
    enabled: false
    always-visible-distance: 8.0
  stats:
    anomaly-alerts: true
```

## 13. Hooks

One mechanic per patch: `1011-Authoritative-netcode` (pong, outbound choke point, window packets, tick end, teleport
accept, client load/respawn), `1012-Authoritative-movement` (claim handling, truth on the player, zero-input travel,
velocity restores, MotionShadow inputs), `1013-Lag-compensated-hits` (attack queue), `1014-Authoritative-paths`
(tracker output for player targets), `1015-Visibility` (tracker pairing). Hook sites: report 04 §10. Every hook is
listed in `scripts/revival/markers.txt`.

## 14. Proof

- **Port exactness, offline:** the port runs on the vanilla 26.3 server jar (report 07 §1) against an in-memory
  world; a differential fuzzer compares its collision with vanilla's on ≥ 10^6 adversarial moves and ≥ 10^4 walks:
  zero differences.
- **Golden client traces:** a real vanilla 26.3 client driven by the rig agent (inputs only; the agent logs the
  client's own position and velocity each tick) runs scripted scenarios in a deterministic "movement gym" world
  that the offline harness can rebuild; the port must reproduce every tick bit for bit.
- **Live, fake clients:** `FakeClient` (report 07 §3) drives the real packet handlers: honest movement (zero
  corrections), knockback at the acked tick, and a cheat suite (fly, speed, timer, blink, no-fall, phase,
  anti-knockback, reach, through-wall, backtrack, autoclick) that must be corrected or rejected.
- **Live, real client:** a real 26.3 client plays scripted scenarios on an `enforce` server: zero corrections, every
  legit hit accepted; a second client watches the first without interpolation freezes.
- **Cost:** per player per client tick simulation cost measured; target < 50 µs.

## 15. Binding decisions after the design review (supersede §1-14 where they differ)

Three adversarial reviews (netcode timing, a cheater's view, coverage/integration) found blockers. These rules
replace anything above that contradicts them.

### 15.1 Transactions

- **Ids.** Our ping ids live in a disjoint band below `-65536` (GrimAC uses `-(n & 0x7FFF)`), randomized inside the
  band; a per-connection FIFO of outstanding ids. A pong must match the **oldest** outstanding id: unknown, future or
  skipped ids are a protocol violation (kick in `enforce`, log in `observe`); duplicates are ignored. Pongs with ids
  outside our band are foreign and pass through. Correction teleport ids use their own disjoint band; an accept with
  an id we never sent is an unmodelled teleport (resync + flag). Non-finite accepts are rejected.
- **Grammar of drain replies.** Pongs and teleport accepts are only legal in a window's drain part, before any
  keybind/aiStep/sendChanges packet of that window; a pong after the window's first keybind packet is a violation.
  The reply to `ClientboundPlayerRotationPacket` is identified by adjacency: the `MovePlayer.Rot` immediately after
  the pong of the bundle that carried the rotation packet.
- **Ping rate.** Coalesce: during a server tick (flushing suspended), every classified packet for a connection is
  gathered into one bundle per flush with one ping (split at 4096); between ticks one ping per handler invocation.
  Replication bundles (§8 R5) are part of that same per-tick bundle. A heartbeat ping every server tick even when
  nothing is classified (the timer and deadlines need acks). Target ≤ 2 pongs per server tick per connection in
  steady state, documented against Paper's `packet-limiter`.
- **Never bundled:** packets with `getExtraPackets()` (all chunk packets) and not-ready packets: trailing ping.
  `ClientboundPlayerInfoUpdatePacket` (self game mode) is bundled, never trailed.
- **Deadline.** Each entry has an ack deadline in the player's own `TickEnd`s: an entry sent after `TickEnd(n)` that
  is unacked at `TickEnd(n + ceil(rttBase/50) + ack-jitter-ticks)` is **force-applied** to the truth at that tick
  (and the client is corrected as needed). Past `max-unacked-ms` (default 1500) the player is frozen, then kicked;
  log and mirror entries are dropped. `rttBase` = long-window low percentile of ack RTT (kernel TCP RTT when
  available), never the latest pong; a short-window low percentile lifts it when that is higher, so a ping that went
  up is followed in about 1.5 s (`RttBaseline`: p10 of the last 256 acks, or of the last 32).
- **Bypass audit.** A Netty outbound auditor next to the encoder notices classified packets that did not come through
  our choke point (PacketEvents/ProtocolLib/Grim direct writes): blocks are logged with upper-bound timing, teleports
  and velocity mark the player for resync and count. In `enforce`, GrimAC movement/velocity/setback handling must be
  off for authoritative players (documented; detected when its packets show up).

### 15.2 World view = what this client was sent

`WorldView` is a persistent per-player model of **the last state sent** for every position, not "live minus unacked":

- an entry per position (and per section for section updates, per chunk for chunk data) is applied at its ack and
  persists until a later packet for that position/section/chunk replaces it, so CraftPlayer fake blocks (HCF claim
  and combat walls) stay in the simulation as long as the client has them;
- block changes made on the server but **not yet sent** to this player are pinned: a hook at
  `ChunkHolder.blockChanged` (or `LevelChunk.setBlockState`) records the pre-change state for every player watching
  that chunk until the broadcast packet is logged and acked;
- the live world is read only where no entry exists; chunk send/forget resets a chunk's entries;
- Leaf smooth world teleport refuses authority (or forces a resync);
- the same view feeds `PickRay` and `Occlusion` (the latter also checks the live world).

Client predictions (dig/place with sequence `s`):

- admitted only if a vanilla client would make them, judged from the client view (the client pick from the truth
  eye, a held block item with count, `BlockPlaceContext` rules, dig progress per tick from tool/effects/ground/fluid);
- sequences strictly increasing; every sequence-carrying packet the server drops (Paper's spam limiter, immobile)
  is still acked (move `ackBlockChangesUpTo` before those early returns);
- predictions expire at the ack deadline;
- a placement the server denied is **not honored by the truth** (`honor-denied-predictions: false`; stops HCF block
  glitching); after any revert, if the truth box intersects a collidable block, the truth snaps to the
  prediction-time position whatever `lastTeleportSequence` says.

### 15.3 Teleports, exempt states, eligibility

- **Server teleports are transactions.** Log the `PositionMoveRotation` and relatives. Windows in flight before its
  pong window are still simulated (the client really ran them) but are never written to the `ServerPlayer`, never
  fire events, never emit path steps, never judged, and no correction is sent while a server teleport is pending.
  At the pong window: `truth = calculateAbsolute(truth at that window, change, relatives)` with client semantics
  (onGround, fallDistance, sprint kept; `ROTATE_DELTA` with the client's rotation at the drain). The accept is
  checked as a claim against that state, never used as a seed. Pending corrections are dropped. The `ServerPlayer`
  stays at the destination meanwhile; vanilla's resend stays.
- **Our corrections** are a separate entry kind: an acked correction changes only the reconciler's view of the
  client, never the truth. A correction counts as applied at its pong window; the matching accept must be the next
  main-thread packet after that pong, else violation + resend (absolute if needed) with a timeout. While a
  correction is in flight, attacks are validated from the client's eye (`claim(T-1)`, bounded by truth ± the
  in-flight Δ).
- **Exempt states are driven by the client stream**: not loaded from the Respawn/Login ack until `PlayerLoaded`
  (frozen: no physics, claims ignored, `hasClientLoaded()` not used for the sim gate); riding, death, camera from the
  acks of `SetPassengers`, health/death, `SetCamera`. At the Respawn ack reset sticky input, sprint state, `xLast`,
  `yRotLast`, `positionReminder` per the KEEP flags. Leaving an exempt state re-seeds from server-owned state (the
  dismount position, the respawn/teleport destination, the last truth); the first claim after is a claim.
- **Vehicles in `enforce`:** `authoritative.vehicles: deny` (default; mounting is cancelled for authoritative
  players) or `vanilla` (exempt, documented as client-trusted). Attacks from exempt states are refused in `enforce`.
- **Eligibility per player:** a real connection (`isRealPlayer`), protocol 26.3 (Via API when present), not
  Geyser/Floodgate. Everyone else is `off` (vanilla handling, vanilla tracker output); NPC `ServerPlayer`s are
  never touched (no skipped travel, no path suppression).
- **Preconditions per player, checked:** the client sees itself with team collision rule `NEVER` (tracked from
  logged team packets) or authority stays `observe` for that player; Leaf async tracker/parallel ticking off.

### 15.4 The window runs at TickEnd, in client order

All gameplay packets of a window are **buffered and replayed at `TickEnd(T)` through their original handlers, in
the order they arrived**, interleaved with the simulation at the client's points: drain replies → keybind actions
(`SetCarriedItem`, `PlayerAction`, `UseItem`, `UseItemOn`, `Interact`, `Attack`, `Punch`, pick, container packets)
→ aiStep packets → simulation tick → `PlayerInput`/sprint `PlayerCommand`/move claim. Each `Attack` is validated at
its own position in that replay, so Punch, the client's own sprint stop and block-starts keep today's order.
Chat, commands, keepalive, pong and accept stay immediate.

After the replay the **simulation is the only writer** of the `ServerPlayer`'s pose, sprint, swimming, gliding and
shift flags and its item-use state; the server's own per-tick recomputation of those (`updatePlayerPose`,
`updateSwimming`, gliding, use ticking that would end use) is skipped for authoritative players. Intended server
writes (use completion, `resyncUsingItem`, `stopFallFlying`, sprint interruption, plugin setters) go through an
`AuthPlayer` method that marks the field as server-meant and sends it inside a transaction; every other self change
to a client-owned field is dropped from the self copy of the packet (copy before rewriting; viewers keep theirs).
The truth applies the sprint speed modifier only while its own sprint flag is on (MC-69459 is not copied).

**Input grammar** (§10) rejects impossible windows: use after release in one window, any keybind packet after the
move claim, pongs/accepts after keybind packets, more than one move claim or input per window, more actions per
window than a vanilla client can produce. Actions in timer-queued windows run when the window runs; actions in
refused windows are dropped, but client-state changes they imply (sprint, use, slot) still update the simulated
client so the model stays exact.

**Bounded branching** for documented one-tick ambiguities (hotbar number key vs scroll, report 02 H9; the attack
strength reset tick): simulate both legal branches, keep the one that reproduces the claim, the more restrictive
one when there is no claim. "Zero corrections" in §14 means zero beyond these documented cases.

### 15.5 Reconciler, timer, velocity, fall damage, events

- `rebase-epsilon` defaults to **0** (the port is bit-exact). When enabled it is ulp-scale (≤ 1e-10), never adopts a
  claim whose box overlaps or is closer to a collision shape than the truth's, and adopted drift per axis is capped
  per window of ticks (the 1e-7 creep through doors and floors is in the cheat suite).
- **Corrections propagate the gap to the predicted apply tick:** keep a full belief state (shadow client state,
  re-based on every exact claim, stepped with the client's inputs); at divergence run truth and belief forward over
  `n̂ = round(ackRtt/50)` ticks with the latest inputs and send `Δpos = truth(m̂-1) - belief(m̂-1)`, `Δvel` likewise;
  the accept residual stays as a backstop. Avoid velocity-only corrections while the client is mining (a teleport
  aborts digging); model the `ABORT_DESTROY_BLOCK` and `onTeleport()` that follow every correction.
- **Timer (Grim model):** allowed `TickEnd`s ≤ `(now - t0)/period + bank`, with the ack clock minus
  `timer-drift-ms` as the floor; bank ≤ `min(max-banked-ticks, ticks since (newest acked ping send time - drift))`.
  Over-budget windows are queued and run as budget refills; refused only past `timer-queue-cap-ms` (default 1000).
  A client with no pongs stops moving within the ack deadline.
- **Velocity:** `ServerPlayer.deltaMovement` is not overwritten while `syncVelocity` is set (knocks waiting for the
  tracker survive); the sim velocity lives in `AuthPlayer` and is what `getVelocity`, `LegacyKnockback` and
  `MotionShadow` read. Server velocity writes are logged and applied at their ack like any entry.
- **Fall damage:** the `ServerPlayer`'s own `fallDistance` keeps today's server rules (fed by `doCheckFallDamage` with
  the simulated delta and onGround, the moved-upwards reset, pearl resets, plugin setters); it is never copied from
  the simulation's client-side value, which is for physics only.
- **Events and side effects:** keep Paper's `PlayerMoveEvent` threshold (1/256 squared, 10°) and its from/to/lastPos
  contract (player held at `from` during the call); cancel and `setTo` run as server teleports under §15.3. Port every
  side effect of Paper's accepted-move branch with simulated values: `PlayerJumpEvent` and `jumpFromGround`
  (stats, exhaustion), `clientIsFloating`, moved-upwards fall reset, `tryResetCurrentImpulseContext`,
  `forceSendPlayerSupportBlocks`, lastGood/lastPos, and `Entity.revivalRecordMovement(from, to, delta)` once per
  simulated tick so block effects (portals, plates, fire) sweep the path. Cancelling sprint/sneak/jump events changes
  only the server's view; the simulation follows the client's real input.

### 15.6 Coverage and performance

- `OutboundClassifier` also covers: `SetHealth` (food), `Cooldown` (item cooldowns, counted down per client tick),
  `EntityEvent` 9 for self (use completion) and 31 from a hook (rod pull: modelled or exempt), `ContainerSetContent`,
  `SetPlayerTeam` (push rules), `BlockEvent` (pistons), `SetCamera`, self `PlayerInfoUpdate` (game mode). The
  decorated client stack (`LegacySwordBlocking.toClient`) is stored when the entry is logged and the codec encodes
  that same stack.
- External movers: player pushes are removed by the collision-rule precondition; mob/boat pushes, pistons, geysers,
  shulker boxes and rockets are modelled from mirrored/acked state where ported, otherwise the tick is exempt with a
  **bounded band** (push ≤ 0.05 per pusher per tick, only when such a mover is in contact) and counted.
- K2 paths, keep-alives and mirrors only within a combat radius (`replication.exact-radius`, default 24 blocks) of
  each viewer; beyond it vanilla-cadence output (still logged). Measured cost covers sim + mirrors + path packets +
  events.

### 15.7 Hits

- Rewind age = the server tick of the target path point the pick box came from. Cap
  `min(max-rewind-ms, rttBase + jitter + K·50 ms)`; a sustained ack RTT far above `rttBase` is flagged.
- Non-player entities never veto a hit in v1 (only blocks and mirrored players take part in the pick).
- Exempt attackers: refused in `enforce`.

### 15.8 Kick policy and client profiles (2026-10-22, after the Lunar/client-compat study)

Most HCF players run Lunar Client (and some Fabric mods), not vanilla. A kick in HCF is a combat-logger death, so
protocol strictness must never cost an honest player a fight. This supersedes the "kick in enforce" parts of §15.1
and §15.4 for ordering-only violations:

- **Counted, not kicked** (kick only past a sustained rate, `client-profiles.protocol-kick-rate`): a pong or accept
  after a window's keybind packets, a second `PlayerInput` or sprint command in one window, and other packets sent
  outside the vanilla tick pipeline that carry no advantage. They still feed statistics and staff alerts.
- **Still an instant kick:** unknown, future or skipped pong ids; forged accepts (ids never sent, non-finite);
  window or log overflow; a second position claim in one window (vanilla 26.3 already kicks that).
- **Client profiles** per player (`docs/revival/client-compat.md`): built from the brand, the `lunar:apollo`
  channel and the Apollo options the server pushed to that player. Input-grammar rules relax per profile only
  where the client provably behaves differently (verified by the manual Lunar session or the rig's Apollo stand-in),
  never on a guess, and relaxed rules never count toward anomaly alerts.
