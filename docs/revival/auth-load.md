# Authoritative RevivalPaper under load

Status: measured 2026-10-21/22. N honest bots on real TCP connections played 1.7 PvP in one arena on an `enforce`
server. Harness and usage: `~/Developer/tools/auth-rig/load/README.md`. Raw results:
`~/Developer/tools/auth-rig/load/runs/`. The main matrix is `runs/final-2132/`, the comparisons are
`runs/final-2132-{off,lag,nolocator}/`, and the earlier, superseded runs stay alongside them.

**Build under test:** `revivalpaper-auth-20261021-2132.jar` (sha256 `f6f31eb7…c8bc1f`). It is the last frozen-clone
build that passed unit and live tests (`arena/build-server-jar.sh`, b7a2b4fb plus 9 uncommitted files). JFR line numbers
in this file come from that jar. File:line references to code point at the tree as of 00:16 (a7a84a06 plus WIP),
frozen in `load/snapshot.noindex/RevivalPaper`. The methods are the same, the lines may move. A build of that 00:16 tree
was also tested; see [Current tree](#the-current-tree-0016-truth-falls-through-floors).

**Machine:** M3 Pro, 12 cores, 18 GB, macOS. The server JVM ran Aikar's flags at 4 GB, with view and simulation
distance 6/4 and compression at 256. Other agents shared the machine: two fuzzers, gradle builds, live tests and
Spotlight kept the load average at 4-9 before the server started. Runs waited for a load average under 7, and any run
that went past 10 was cut short. That cut every attempt at a full 150 s N=100 with the locator bar off.

## Summary

- **N ≤ 20: right.** 0 corrections, every simulated tick exact, every hit VALID (2,404 and 5,187 hits). The server
  thread runs 0.08 cores, 1.3 pongs per server tick, and 15-31 KiB/s per player down.
- **N = 50: one player in fifty gets stuck.** 49 bots stay exact. One bot that was hit in its first ticks after joining
  takes about 20 corrections a second for the whole run, and all 451 of its hits are rejected `NOT_PRESENT`. With
  latency (≈125 ms RTT), **14.6 % of all honest hits are rejected `REWIND`**.
- **N = 100: the authority collapses.** 77 of 100 bots are stuck in correction loops: 1,212 corrections/s, 140k
  force-applied entries, 0 VALID hits out of 58,717 attacks. The server keeps 20 TPS, so this is a correctness
  collapse, not a CPU one.
- **Cost.** At N=50 the authority adds about 0.04 server-thread cores (+33 %) over vanilla movement. MSPT does not show
  it: most of the work runs between ticks. The real scaling costs are O(N²) and mostly not ours. **The vanilla locator
  bar is the #1 packet type** (1,025/s per player at N=50, 55 % of the bandwidth). Truth application runs Paper's whole
  move handler: 4 `setPos` per player per tick, each one O(N) waypoint work. That is 20 % of the server thread at N=50
  and 41 % at N=100.
- **Network.** Enforce sends *less* than vanilla (83.6 vs 96.1 KiB/s per player at N=50, 135 vs 200 at N=100). It
  sends 1.3-1.7 pongs per server tick and peaks at 155 serverbound packets/s against Paper's limit of 500.
- **Memory.** Nothing grows: live heap after a full GC moved +2 to +4 MB over each 150 s window, and auth.* stayed
  flat (3.4 MB at N=50, 8 MB at N=100). GC is a non-issue: 19-49 young pauses per window, max 12.8 ms, under 0.1 % of
  wall time.

## Method in one paragraph

Each bot is a real 26.3 client connection: login, configuration, compression, then the game protocol, through
Minecraft's own `Connection` on its own Netty threads. It moves with RevivalPaper's own port (`PortSimulation`), fed
only what its socket received. It keeps other players as `RemoteMirror`s and attacks only when its own `PickRay`
(client pick replay) has an entity under the crosshair, so an honest bot should never be corrected or rejected. It runs
60 frames and 20 ticks a second on its own clock and plays HCF: sprint-strafing, w-taps, crit jumps, block-hits, 8-14
CPS with jitter and lulls, rod casts, pots, and pearls (server teleports). Each N gets a fresh server: 30 s warm-up,
class histogram, 150 s with JFR `profile`, second histogram. The bots run inside the server JVM, but their threads are
reported apart.

Two harness bugs were found and fixed on the way. Runs before the fix (`runs/m1-*`, `m3-*`, `lag-2132`) overstate the
corrections.

1. The network thread queued a bundle's packets one by one while the frame thread drained them, so a frame could take
   half a bundle. Its pong then landed in one client tick and its entries in the next, and the server rightly
   corrected. Bundles are now queued whole.
2. The emulated link scheduled each write on its own, and Netty's deadline stamping could swap two writes. A swapped
   pong is a kick (`skipped pings`). Writes now go through one FIFO pump.

## Results (21:32 jar, final harness)

### Server

| N | TPS | MSPT mean | p95 | p99 | max | server thread (cores) | server Netty (cores) | GC pauses (total / max) | live heap after GC, start → end |
|---|---|---|---|---|---|---|---|---|---|
| 10 | 20.00 | 1.54 | 4.56 | 5.90 | 13.4 | 0.08 | 0.09 | 0 | 278 → 280 MB |
| 20 | 20.00 | 1.27 | 3.61 | 5.16 | 10.1 | 0.08 | 0.17 | 3.0 / 1.6 ms | 294 → 297 MB |
| 50 | 20.00 | 2.15 | 2.68 | 2.97 | 11.1 | 0.16 | 0.68 | 56 / 12.8 ms | 347 → 351 MB |
| 100 | 20.00 | 6.24 | 6.87 | 7.32 | 11.3 | 0.40 | 2.04 | 90 / 2.8 ms | 434 → 437 MB |
| 50, `mode off` | 20.00 | 2.67 | 3.35 | 4.23 | 31.4 | 0.12 | 0.68 | 81 / 27.2 ms | |
| 100, `mode off` | 20.00 | 7.78 | 10.80 | 18.13 | 99.2 | 0.36 | 2.45 | 162 / 10.9 ms | |
| 50, locator bar off | 20.00 | 1.98 | 2.50 | 2.82 | 10.4 | 0.12 | 0.33 | 18 / 2.0 ms | |
| 50, ≈125 ms RTT | 20.00 | 2.19 | 2.69 | 3.05 | 8.1 | 0.18 | 0.73 | 183 / 6.5 ms | |

Server-thread CPU is `jdk.ThreadCPULoad`. MSPT moves with whatever else the machine was doing, so read it as
"far from 50", not to the decimal. On a quieter machine the first matrix (`runs/m1-2132`) measured 0.50 and 0.88 ms at
N=10 and N=20.

**MSPT misses most of the authority's work.** At N=50 enforce shows a lower MSPT than vanilla (2.15 vs 2.67) while
using more of the server thread (0.16 vs 0.12 cores). Windows run when `TickEnd` arrives, and packets are processed
between ticks (`processPacketsAndTick`, `managedBlock`). At N=100 the server thread did about 20 ms of work per 50 ms
tick, but MSPT shows 6.2. Watch the server thread's CPU, not MSPT.

### Authority (bots are honest: expect 0 corrections, every hit VALID)

| N | windows | exact / compared | corrections | bots corrected | attacks | VALID | rejected (reason) | forced entries | mirrors / viewer |
|---|---|---|---|---|---|---|---|---|---|
| 10 | 30,100 | 30,100 / 30,100 | 0 | 0 | 2,404 | 2,404 | – | 0 | 9.0 of 9 |
| 20 | 60,161 | 60,161 / 60,161 | 0 | 0 | 5,187 | 5,187 | – | 0 | 19.0 of 19 |
| 50 | 150,300 | 147,417 / 147,597 | 2,701 | 1 | 17,410 | 16,959 | NOT_PRESENT 451 | 0 | 48.0 of 49 |
| 100 | 300,919 | 102,985 / 106,430 | 181,895 | 77 | 58,717 | **0** | NOT_PRESENT 44,953, REWIND 115 | 140,363 | 26.0 of 99 |
| 100, locator off (50 s) | | | 12,302 | 17 | 12,384 | 10,458 | REWIND 919, NOT_PRESENT 911, MISSED 75 | 7,062 | 93.2 of 99 |
| 50, ≈125 ms RTT | 150,339 | | 2,100 | 4 | 20,958 | 16,039 | **REWIND 3,078**, NOT_PRESENT 1,805 | 0 | 45.3 of 49 |

"Over cap" multi-clicks (two clicks inside one 50 ms tick, at 14 CPS) were 14, 19, 91 and 282, all dropped by
`max-attacks-per-client-tick` as designed. Server teleports from pearls (28-133 per run) were all applied exactly.

### Network, per player

| N | down KiB/s | up KiB/s | clientbound packets/s | pings/s | serverbound packets/s | pongs per server tick | Paper limiter, peak 7 s rate |
|---|---|---|---|---|---|---|---|
| 10 | 15.5 | 1.01 | 646 | 26.4 | 79 | 1.32 | 98 / 500 |
| 20 | 31.2 | 1.02 | 1,282 | 26.2 | 80 | 1.31 | 105 / 500 |
| 50 | 83.6 | 1.05 | 3,466 | 26.9 | 84 | 1.35 | 139 / 500 |
| 100 | 135.2 | 1.60 | 6,441 | 34.7 | 109 | 1.73 | 155 / 500 |
| 50, `mode off` | 96.1 | 0.85 | 3,591 | 0 | 56 | 0 | 68 / 500 |
| 100, `mode off` | 199.9 | 0.87 | 7,371 | 0 | 58 | 0 | 72 / 500 |
| 50, locator bar off | 37.8 | 1.08 | 2,398 | 27.2 | 84 | 1.36 | 137 / 500 |

The limiter peak is the server's own counter (`Connection.allPacketCounts.getRate()`, sampled every second on each
bot's connection). The bot-side 7 s window matched it to within 1 %. Pongs stay under the §15.1 target of 2 per
server tick. Upstream is 1-1.6 KiB/s whatever N is.

Clientbound packets per player per second (top types, enforce):

| packet | N=10 | N=20 | N=50 | N=100 | note |
|---|---|---|---|---|---|
| TrackedWaypoint | 185 | 393 | 1,025 | 1,479 | vanilla locator bar: every moving player, to every player, each position change |
| MoveEntity.PosRot | 144 | 278 | 756 | 1,579 | K2 paths: one step per target per tick (vanilla off: 544 at N=50) |
| RotateHead | 97 | 210 | 582 | 1,290 | vanilla, every tick a target turns |
| SetEntityData | 42 | 91 | 285 | 714 | sprint/use flags of others |
| SwingAnimation | 24 | 52 | 144 | 287 | |
| UpdateAttributes | 22 | 48 | 147 | 418 | others' sprint modifier toggles |
| SetEquipment | 18 | 40 | 126 | 255 | rod/pot/pearl swaps |
| Ping | 26 | 26 | 27 | 35 | ours |
| SetEntityMotion | 15 | 31 | 88 | 97 | knockback |
| EntityPositionSync | 8 | 21 | 51 | 65 | |

### Where the server thread goes

JFR execution samples on `Server thread`, enforce. "auth" means any auth.* frame on the stack; the time inside it
includes the vanilla code auth calls.

| N | samples | in auth | sim | view | combat | net | replicate | `applyTruth → revivalApplyTruth` (vanilla move handler) |
|---|---|---|---|---|---|---|---|---|
| 10 | 726 | 52 % | 27 % | 19 % | 1 % | 2 % | 1 % | 9.6 % |
| 20 | 712 | 60 % | 25 % | 18 % | 4 % | 4 % | 2 % | – |
| 50 | 1,642 | 70 % | 22 % | 16 % | 12 % | 8 % | 4 % | 20.3 % |
| 100 | 4,437 | 86 % | 19 % | 13 % | 5 % | 13 % | 4 % | 40.7 % |

Per player per client tick, the authority costs the server thread **≈ 40 µs over vanilla at N=50**: 0.16 − 0.12 cores
over 50 players × 20 ticks. That is under the §14 target of 50 µs, but it counts only the server thread. The O(N²)
parts grow per player: mirrors, replication and the waypoint work triggered by truth writes.

### Allocation (JFR sampled)

| N | server thread | of which under auth.* frames | auth-only sites (excluding the vanilla move handler) | server Netty | bots |
|---|---|---|---|---|---|
| 10 | 7.9 MB/s | 7.3 | – | 2.8 | 3.0 |
| 50 | 60.0 MB/s | 54.7 | ≈ 9 | 67.8 | 66.4 |
| 100 | 179.5 MB/s | 168.3 | ≈ 22 | 243.3 | 235.6 |

Most of what sits under auth.* is vanilla code that `applyTruth` triggers: the waypoint manager's Guava tables and
iterators (`HashMap$KeyIterator` alone was 15 MB/s at N=50), `TrackedWaypoint` packets and AABBs. The auth-only sites
at N=100:

| MB/s | site |
|---|---|
| 6.4 | `Transactions.send` → `Connection.send` lambda per packet (Paper's off-loop send) |
| 5.3 | `Transactions.append` (`Object[]`: growing the open bundle) |
| 2.6 / 2.4 / 2.1 / 1.3 | `PathFeed.Viewer.sendMove`: `MoveEntity.PosRot`, `List12`, `Stepped.DeltaStep`, `VecDelta.Stepped` per step per viewer (`replicate/PathFeed.java:284`) |
| 2.0 | `MirrorEvent.of` → `MirrorEvent.Move` per applied move (`combat/MirrorEvent.java:105`) |

G1 keeps up: young pauses only, max 12.8 ms. No allocation problem at this scale, but every byte of it scales O(N²).

### Memory growth

Class histograms bracket each window, each taken after a full GC. Live heap moved +2.1, +2.9, +3.8 and +3.5 MB at
N=10, 20, 50 and 100, and the largest growth was JDK filler arrays and CHM nodes. auth.* live bytes were flat:
0.41 → 0.41, 0.96 → 0.96, 3.42 → 3.50 and 8.29 → 7.92 MB. `Transactions` sent and acked counts matched (520,417 sent,
521,301 acked at N=100, counting acks of pings sent before the window). Outstanding pings averaged 0.01-0.2. **No
leak.** There is one untested risk: `WorldView.sent` and `pinned` keep an entry per block position the client was
sent, until chunk data replaces them. The arena had no block edits, so a long HCF session with heavy building should
be measured on its own.

## Findings

### 1. Correction loops that never converge (netcode, P0)

**What happens:** a player who diverges once while being hit is corrected about once per tick (20/s) for the rest of
the session. All of their hits are rejected (`NOT_PRESENT`) while it lasts. 1 of 50 bots at N=50, 4 of 50 at ≈125 ms
RTT, 77 of 100 at N=100.

**Trace** (`traceOnCorrection`, `LoadBot029`, both sides tick by tick, in `runs/fix50-2132/trace/`):

1. Join: the server seeds the truth at activation with velocity 0 (`seedFromServer`, `AuthPlayer.java:1620`, the
   `seedStill` branch). The client has been ticking on the ground with `vy = -0.0784`. On its first tick the truth
   does not touch the floor (`onGround` false), while the client does.
2. A knockback lands in that tick. The truth applies it with air friction (×0.91), the client with ground friction
   (×0.546), so the paths split by 0.32 blocks.
3. The corrections that follow carry **absolute velocity** (`sendFallback` → `sendCorrection(..., sim.vx(), ...,
   false)`, `AuthPlayer.java:1835`; `sendAbsolute`, `:1908`). The trace shows one arriving right after the client
   applied the next knock (`setVelocity vy=0.4608`, then a correction setting `vy=-0.0784`): it overwrites the knock on
   the client, but the truth keeps it. That is a new divergence, and in a fight a knock comes every few ticks, so it
   never ends.
4. Near landing the client is set 0.07 blocks into the floor and then pops back. The ±0.0716 y corrections repeat at
   every landing.

The 00:16 tree already has `repeating(...)` and inside-block → absolute fallbacks for "relative corrections chase it
one tick behind forever". They do not fix this, because the fallback is absolute velocity too.

**Fix:**

- Make velocity corrections relative (`DELTA_X/Y/Z`), so they compose with knocks in flight. If one must be absolute,
  hold it while a velocity entry to that client is unacked, or rebase the belief on the absolute value at its ack.
- Seed at activation with the resting state the client really has: on the ground, `vy = (0 - 0.08) × 0.98`. Or run
  the first window from the claim.
- Add a breaker: after k consecutive corrections without one exact claim, send one absolute resync (position and
  velocity) and stop forecasting until a claim matches.

### 2. N=100: the stuck loops cascade and every hit is rejected (netcode + combat, P0)

The cascade starts during warm-up, about 8 s after the last of 100 joins, while chunk sends and pairing for 100
players load both sides. The sequence:

- RTT jumps to 365-719 ms, and entries pass their ack deadline (`forcedEntries` 140k). The RTTs come from the
  same-build run with debug logging on, `runs/m3-2132/n100/console.log`: 979 corrections logged at RTT ≥ 100 ms.
- Mirrors are repaired or dropped: 26 of 99 per viewer on average.
- Loops spread: 77 bots, 1,212 corrections/s, 847 "teleport accept for a correction never sent or not in flight"
  violations.
- It never recovers, though RTT returns to 16-21 ms.

The same N with the locator bar off (less Netty and server load) had 17 stuck bots and 84 % VALID hits. That run was
cut at 50 s by the machine.

**Fix:** finding 1 removes the loop. The rest is recovering after a stall:

- After a stall, resync every affected client once (absolute), instead of forcing entries one by one.
- Re-pair mirrors only after the viewer's ack clock is current again.
- Keep `NOT_PRESENT` from eating every hit of a player whose own correction is in flight.

Add an N=100 bot run (`load.py --n 100`) to CI-adjacent checks once it passes.

### 3. The rewind cap rejects honest hits at ordinary pings (combat, P1)

At ≈125 ms RTT (50 ms ± 25 ms each way), **3,078 of 20,958 honest hits (14.6 %) were rejected `REWIND`**: the
mirror stamp was more than `max-rewind-ms` 300 behind. Rewind adds up the RTT, K=2 interpolation (100 ms), up to one
tick of frame alignment and jitter, which is ≈ 300 ms right at 125 ms RTT. The same happens without latency whenever
the server stalls: a class-histogram full GC rejected one honest hit at N=10 with rewind 329 ms. At N=100 with the
locator bar off, the 623 ms tick gave 919 REWINDs.

**Fix:** cap per player at `rttBase + jitter + K·50 + 2·50 ms`, with an absolute ceiling of about 500 ms. Measure
rewind in the attacker's client ticks (the ack clock), not in server wall time, so a server stall doesn't count
against the client.

### 4. Input held through activation is lost (netcode, P1)

A `PlayerInput` sent before the first `TickEnd` (activation) never reaches the window state, and the client only sends
input when it changes. A player holding W through a join therefore runs on empty keys on the server and is corrected
every tick until they change keys. Found on the first bot run: 1,555 corrections in 30 s, the server tracing
`keys=` while the bot held `WR`. **Fix:** at activation, seed the window's sticky keys from
`ServerPlayer.getLastClientInput()`. The bots now keep their hands off the keys for 10 ticks after every (re)spawn,
which a vanilla client doesn't do.

### 5. The current tree (00:16): truth falls through floors (netcode, P0, WIP)

A frozen clone of the tree at 00:16 (a7a84a06 plus WIP, `load/jars/revivalpaper-load-20261022-0016.jar`) was tested.
With 3 bots joining 120 ms apart, **every truth fell through the arena floor from its first tick**: sim y 61.65 at
tick 11 while the claims sat at 65.0 on the ground. At tick 12 a −4.06-block correction pulled each honest client into
the void. A single bot did not fall.

The suspect is the new `WorldView.seedChunks` (`view/WorldView.java:302`), which seeds the chunk list at activation from
Moonrise's per-player sent set. A chunk whose packet went out just before activation is in neither that set nor
the transaction log, so it is never acked, and the view has no colliders there. Idle ticks carry no position, so 10
ticks of falling went unnoticed. Traces: `load/runs/diagfall3-0016/trace/{server,bot}-LoadBot002.txt` and
`load/runs/diagfall3-0016/n3/console.log`.

Also in that build: honest bots' picks missed (0 of 5,569 clicks hit), because their own models fell too.

### 6. The vanilla locator bar dominates traffic and the server thread (config, P0 for an HCF server)

The 26.x locator bar sends each moving player's waypoint to every player whenever the position changes, so it is
O(N²). At N=50 it is 1,025 packets/s per player, 30 % of all packets and **55 % of the bandwidth**. Turning it off
(`gamerule locator_bar false`) gave:

- bandwidth 83.6 → 37.8 KiB/s per player;
- server Netty 0.68 → 0.33 cores;
- server thread 0.16 → 0.12 cores;
- GC pauses 56 → 18 ms total.

Paper already short-circuits `ServerWaypointManager.updateWaypoint/updatePlayer` when the gamerule is off. HCF has no
use for the bar.

### 7. Truth application re-runs Paper's whole move handler (sim/netcode, P1)

`AuthPlayer.applyTruth` (`AuthPlayer.java:1938`) calls `revivalApplyTruth`, which calls `handlePlayerPositionChange`
(`ServerGamePacketListenerImpl.java:1586`). For a truth that is already resolved, that handler:

- runs `player.move(MoverType.PLAYER, …)` (`:1747`): a second collision pass and an extra `setPos`;
- then calls `absSnapTo` three times: to target (`:1785`), back to start for `PlayerMoveEvent` (`:1805`), and to
  target again (`:1857`).

Each `setPos` → `setPosRaw` (`Entity.java:5468/5472`) runs O(N) waypoint updates and moves entity sections. Drill
(`jfr/Drill.java … AuthPlayer.applyTruth`) at N=100: `absSnapTo` 51 % and `setPos` 36 % of this path, which is
40.7 % of the server thread. Vanilla movement pays the same per move packet. Under authority it is 20 Hz exactly, for
every player.

**Fix:** a truth-only apply path:

- one `absSnapTo`, no `player.move`;
- `PlayerMoveEvent` built from the from/to `Location`s, without moving the entity back and forth;
- the side effects §15.5 lists (fall damage, statistics, `revivalRecordMovement`) called directly.

This removes 3 of 4 `setPos` and the collision pass, and with the locator bar on cuts the waypoint work by 4×.

### 8. Block reads in the port (sim, P2)

`WorldView.blockState` (`view/WorldView.java:113`) does `hasChunk` (`:157`: `getChunkAtIfLoadedImmediately`, a
concurrent hash lookup), up to three overlay map probes, then `Level.getBlockState`, which looks the chunk up again.
`SimCollision.gatherBlocks` (`sim/SimCollision.java:302`) also asks `isChunkLoaded` per block before that. Around 80
blocks per gather and several gathers a tick (`collide`, `moveTowardsClosestSpace` × 4 corners, `noCollision`, inside
blocks) make **14.6 % of the server thread at N=50** (`hasChunk` 6.1 %, overlay `get` 3.8 %, `getBlockState` 3.7 %,
self 1 %).

**Fix:**

- Cache the last chunk (key and `LevelChunk`) in `WorldView` and read the section directly.
- Hoist `isChunkLoaded` per (x>>4, z>>4) column in `gatherBlocks`.
- Keep one "overlay empty" flag instead of three `isEmpty` checks.

### 9. Per-packet cost on the O(N²) replication path (netcode/replicate, P2)

At N=100, `auth.net` is 12.9 % of the server thread:

- `Transactions.send` takes a `synchronized` block per packet, runs `OutboundClassifier.classify`, `bundleable` (which
  calls `isReady`/`getExtraPackets`) and `traceState` (2.9 % self, for a recorder that is null), and grows the bundle
  array;
- Paper's `Connection.send` allocates a lambda per packet (6.4 MB/s);
- `PathFeed` builds 4 objects per step per viewer.

**Fix:**

- Classify replication packets once per target per tick, not once per viewer: the same `PosRot` path step goes to
  every viewer.
- Presize the bundle array per connection.
- Skip `traceState` when no recorder is set.
- Send the coalesced bundle with one `Connection.send`.
- Reuse the `VecDelta.Stepped` / `DeltaStep` per target per tick.

RotateHead (1,290/s per player at N=100) could ride in the path step's rotation (`MoveEntity.PosRot` already carries
yRot): K2 sends both every tick.

### 10. Smaller notes

- Keep-alives are pulled into the open bundle (`Transactions.send`: bundleable and no listener) and so are answered at
  the client's frame rate, not on its network thread. Harmless, but the bot had to learn it.
- In `mode off` the hooks cost the server thread about 1.8 % self (`Authority.send` passthrough).
- `RemoteMirror` work per viewer per target (`startInterpolating`, `adjustInterpolationTargetFromDeltas`, `decode`,
  `interpolateTo`) is 8-10 % of the server thread at N=50. It is O(N²) by design and allocation-free. Only
  `exact-radius` limits it.
- JIT stayed busy (0.3-0.8 cores) through every window, with about 200 deopts per window. Not investigated.

## Optimizations, ranked by impact

| # | Change | Owner | Effect measured or expected |
|---|---|---|---|
| 1 | Velocity corrections relative (or held while a knock is unacked), resting-state seed at activation, consecutive-correction breaker | netcode | removes the stuck players: 1/50 at N=50, 77/100 at N=100, all their hits rejected; the precondition for N≥50 |
| 2 | `gamerule locator_bar false` on PvP servers | config | −55 % bandwidth, −0.35 Netty cores and −25 % server thread at N=50; O(N²) packets gone |
| 3 | Stall recovery: one absolute resync per client after forced entries, re-pair mirrors when acks are current | netcode | the N=100 cascade (140k forced entries, 0 VALID hits) |
| 4 | Rewind cap per player (`rttBase + jitter + K·50 + 100 ms`, ceiling about 500), measured on the ack clock | combat | 14.6 % of honest hits rejected at 125 ms RTT → about 0 |
| 5 | Truth-only apply path (one `absSnapTo`, no `player.move`, no start/target ping-pong for `PlayerMoveEvent`) | sim/netcode | 20 % (N=50) to 41 % (N=100) of the server thread → about a quarter of that; 3 of 4 waypoint/section updates |
| 6 | Fix `seedChunks` at activation (include chunks in flight, or fall back to the live world until the first chunk ack) | netcode | the current tree's fall-through-floor bug |
| 7 | Sticky keys seeded from `getLastClientInput()` at activation | netcode | no correction storm for a player holding a key through a join or respawn |
| 8 | Chunk cache in `WorldView`, per-column `isChunkLoaded` in `gatherBlocks` | sim | about 10 % of the server thread at N=50 |
| 9 | Replication per target per tick (classify once, reuse steps, one send per bundle, no `traceState` when off, presized bundles) | netcode/replicate | about half of `auth.net` (12.9 % at N=100), −20 MB/s allocation at N=100 |
| 10 | Fold RotateHead into the K2 path step | replicate | −20 % of packets per player at N=100 |

## Reproduce

```bash
cd ~/Developer/tools/auth-rig/load
python3 -I load.py --jar ../arena/dist/revivalpaper-auth-20261021-2132.jar --n 10,20,50,100      # the matrix
python3 -I load.py --n 50 --mode off                                                            # vanilla baseline
python3 -I load.py --n 50 --set locatorBar=false                                                # finding 6
python3 -I load.py --n 50 --set latencyMs=50 --set jitterMs=25                                  # finding 3
python3 -I load.py --n 50 --duration 60 --no-jfr --revival log=true --set traceOnCorrection=true # finding 1, traces in plugins/AuthLoad/trace/
python3 -I report.py runs/<stamp>
java jfr/Drill.java runs/<stamp>/n100/server.jfr 'AuthPlayer.applyTruth' 'Server thread' 4
```
