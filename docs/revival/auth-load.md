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

**Status (netcode, 2026-10-22 05:36 tree):** activation seeds a grounded player with the resting vy
`(0 - gravity) * 0.98F`; corrections forecast the client with every knock still in flight to it and hold an absolute
velocity while one is (so no correction overwrites a knock the client applies first); a forced knock that lands, and
the moment every forced entry has landed, make the next correction one absolute resync. The torture rig's stall runs
are in auth-torture.md (Fix status). **Re-measured on 69b7b5f6** ([merged build re-run](#merged-build-re-run-2026-10-22-0930)):
N=50 had 0 corrections. At N=100, loops still start after a stall of a few hundred ms (even the harness's full GC) and
don't converge (5 bots at 5-10/s for a whole window).

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

**Status (netcode, 2026-10-22):** deadlines count paced windows (a catch-up burst doesn't force entries); after forced
entries, one absolute resync per affected client and the viewer's mirrors re-paired once its acks are current;
periodic mirror and solid-entity re-pairs wait while any forced entry is outstanding (a re-pair that is itself forced
loops). **Re-measured on 69b7b5f6** ([merged build re-run](#merged-build-re-run-2026-10-22-0930)). The join storm is
fixed: 0 corrections for 12 s after the 100th join, mirrors 99 of 99, 92-97 % of hits VALID. Recovery from a stall of a
few hundred ms at N=100 is not fixed.

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

**Status:** fixed 2026-10-22: activation seeds the sticky keys from `ServerPlayer.getLastClientInput()`.

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

**Done** (2026-10-22, [server-thread pass](#server-thread-pass-2026-10-22)), differently from the sketch above: the
branch runs as before, in its order, so every event sees what it saw (the player held at the start during
`PlayerMoveEvent`, step sounds and game events from the interim position); the truth's move skips the collision pass
and the interim positions skip the waypoint updates. Only the final position runs them.

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

**Done** (2026-10-22, [server-thread pass](#server-thread-pass-2026-10-22)).

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

**Partly done** (2026-10-22, [server-thread pass](#server-thread-pass-2026-10-22)): classification per packet class, the
recorder asked once, bundles grown once. Not done: per-target classification and step reuse in `PathFeed`, RotateHead
folding, and Paper's per-packet `Connection.send` lambda (the packets that still go out one by one are mostly waypoint
packets to other players, sent between ticks while their connection has no bundle open; bundling them would change the
framing on the wire).

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
| 5 | Truth-only apply path (one `absSnapTo`, no `player.move`, no start/target ping-pong for `PlayerMoveEvent`) | sim/netcode | 20 % (N=50) to 41 % (N=100) of the server thread → about a quarter of that; 3 of 4 waypoint/section updates. **Done**: 30 → 21 % (N=50), 45 → 29 % (N=100); the rest is the one write's vanilla waypoint update |
| 6 | Fix `seedChunks` at activation (include chunks in flight, or fall back to the live world until the first chunk ack) | netcode | the current tree's fall-through-floor bug |
| 7 | Sticky keys seeded from `getLastClientInput()` at activation | netcode | no correction storm for a player holding a key through a join or respawn |
| 8 | Chunk cache in `WorldView`, per-column `isChunkLoaded` in `gatherBlocks` | sim | about 10 % of the server thread at N=50. **Done**: sim + view 49 → 31 ms/s at N=50 |
| 9 | Replication per target per tick (classify once, reuse steps, one send per bundle, no `traceState` when off, presized bundles) | netcode/replicate | about half of `auth.net` (12.9 % at N=100), −20 MB/s allocation at N=100. **Partly done**: per-class classification, recorder once, bundles grown once |
| 10 | Fold RotateHead into the K2 path step | replicate | −20 % of packets per player at N=100 |

## Server-thread pass (2026-10-22)

Findings 7, 8 and part of 9, as three commits on top of 07c5ca46. Behaviour is unchanged except for one thing: the
waypoint (locator bar) updates of the truth's interim positions, superseded inside the same call, are no longer sent.

| Change | What it removes |
|---|---|
| Truth path (patch 1012, `Entity.revivalMoveResolved`, `revivalQuietSnapTo`) | the second collision pass of the truth's move, and the waypoint updates of its three interim positions (`setPosRaw` still moves entity sections and the box for them); one position per tick runs the waypoint updates |
| `WorldView` chunk cache (patch 1011 counter, `WorldView.chunkHeld`, `SimCollision.gatherBlocks`) | two hash lookups per block read (the client's chunk record and the server's full-chunk map); the block comes from the cached `LevelChunk`; `isChunkLoaded` once per chunk column in a gather |
| Per-class classification (`OutboundClassifier.ClassInfo`, `Transactions.send`) | the type lookup, `isReady`/`getExtraPackets`/`isTerminal` calls, `track` instanceof chains and repeated recorder lookups per packet; bundle lists grown packet by packet |

**Equivalence.** `TruthPathCheck` (live suite, docs/revival/auth-netcode.md "Testing") replays 420 port-simulated ticks
over a course through the claim's branch and through the truth path: state, events and packets match on every move
(279 move events, 6 jumps, fall damage, a swim, 57 block insides, a plate, 28 sounds); waypoint packets 166 → 56. A
mutation (no snap back to the start before `PlayerMoveEvent`) fails it at move 6. The port's checks are unchanged: the
80 golden client traces replay exact, the oracle fuzz (`fuzz-seeds.sh 3000 400 20 21 22 23`, 4.8M ticks) has 0 runs
that differ, the unit suite (446) passes, and the live suites pass (sync 522 checks, cheat suite 82/82, Neue; async 123).

**Load** (same harness, jars built from 07c5ca46 and from the pass, runs interleaved per N so both see the same
machine; other agents kept the load average at 4-12, and runs cut short by it are left out). Run directories
`runs/perf-{base,v1,final}-*`.

| N | build | server thread (cores) | auth µs / player / tick | server Netty (cores) | down KiB/s per player | TrackedWaypoint / s per player | server-thread allocation MB/s | MSPT mean |
|---|---|---|---|---|---|---|---|---|
| 50 | 07c5ca46 | 0.17, 0.18 | 122, 132 | 0.88, 0.85 | 121, 103 | 1,917, 1,487 | 63, 59 | 2.05, 2.36 |
| 50 | pass | 0.14, 0.17, 0.14 | 99, 109, 98 | 0.61, 0.57, 0.50 | 68, 60, 58 | 736, 547, 447 | 36, 34, 33 | 2.21, 2.53, 2.38 |
| 100 | 07c5ca46 | 0.49, 0.49 | 211, 214 | 2.58, 1.89 | 217, 218 | 3,256, 3,472 | 192, 218 | 9.85, 16.12 |
| 100 | pass | 0.36, 0.40 | 146, 160 | 2.02, 1.99 | 136, 131 | 1,305, 1,268 | 104, 108 | 5.97, 7.62 |

- **Server thread: about −20 % at N=50 and N=100**, auth µs per player per tick −20 to −25 %. Truth application went
  from 30 % (N=50) and 45 % (N=100) of the server thread to 21 % and 29 %: what is left is the one position write's
  vanilla waypoint update (an O(players) loop over Guava tables, `ImmutableSet.copyOf` and `CraftPlayer.canSee` per
  pair) and the chunk map's move, which every vanilla move pays too. Sim + view (block reads): 49 → 31 ms/s at N=50,
  96 → 64 ms/s at N=100.
- **Network:** −60 % locator-bar packets, about −40 % bandwidth per player, −0.3 Netty cores at N=50.
- **Allocation on the server thread: about halved** (the waypoint manager's tables and packets, and the bundle growth).
- **Honesty unchanged:** N=50 0 corrections in every run of both builds; hits VALID except 1-4 `INVALID MISSED` per
  15k-16k attacks in both builds alike (the harness's pick, not the authority). N=100 keeps finding 2's collapse in both
  builds (4-9k corrections with the pass, 20-25k without, same load); it is the netcode's, not this pass's.

What is left on the server thread, by size: the locator bar's O(N²) waypoint work in the one remaining write
(finding 6: `locator_bar false`, or Leaf's experimental `performance.optimize-waypoint`, would remove most of it), the
mirrors (finding 10), replication (`PathFeed`, §9), and the port itself (`gatherBlocks` now reads blocks straight from the
chunk; the rest is the exact port's collision work).

**Found on the way** (both branches, unchanged): CraftBukkit's `justTeleported`. After any teleport, the first move whose
`PlayerMoveEvent` fires with `from` (Paper's last event position, which lags while moves stay under the 1/256 threshold)
not where the player stands returns early and leaves the `ServerPlayer` at the start of that move; the next tick writes
the truth. `TruthPathCheck` meets it once, crossing the cobweb after the join teleport. Fixed on the netcode side:
`AuthPlayer.applyTruth` checks the player landed on the truth and writes it once more if not (`truthRewrites`; no second
event, its `from` is the target by then). `TruthPathCheck` does the same on both paths, and the live scenario "the first
moves after a teleport write the truth" checks it in play.

## Merged build re-run (2026-10-22 09:30)

The stall-robustness batch (2d7982f7) merged with the server-thread pass: 69b7b5f6, "Merge the server-thread performance
pass".

**Build:** `load/jars/revivalpaper-load-20261022-0928.jar` (sha256 `1d39a531…e8a45`). It was built with `createPaperclipJar`
in a frozen APFS clone of 69b7b5f6 (`load/snap-69b7b5f6.noindex/`). In the clone, the working tree's uncommitted live-test
files were reverted, and every applied Minecraft and Paper commit was diffed against its patch file. They match, apart from
the line offsets in Paper's patches. The harness, flags and settings are the same as above: enforce, locator bar on,
view/simulation distance 6/4, and a 150 s window with JFR after a 30 s warm-up.

Runs:

- `runs/merged-0930/{n20,n50}`: the N=20 and N=50 matrix.
- `runs/merged-0930-n100/n100`: run A, N=100.
- `runs/merged-0930-n100b/n100`: run B, N=100, cut at 75 s.
- `runs/merged-0930-n100c/n100`: run C, N=100, cut at 66 s.
- `runs/merged-0930-join100/n100`: no warm-up and no JFR, so the window opens at the 100th join.

**The machine was not quiet.** Another agent ran torture-rig cycles in parallel the whole time. Each cycle was a real
client, a server and the netem proxy, about 2 cores, with a gradle build between cycles. What overlapped what:

- glitch-fix1 overlapped N=20 and N=50, and glitch-fix2 overlapped run A.
- A gradle build followed by glitch-fix4 overlapped the join run.
- Gradle builds cut run B at 75 s (load 12.2) and, with glitch-fix5, run C at 66 s (load 13.0).
- Two more N=100 attempts were cut in warm-up by load over 12, once with macOS memory pressure (`ReportMemoryException`).

The N=100 windows therefore contain machine hitches. They show in the plugin's MSPT, and in run A's JFR, `jdk.CPULoad`
has the machine at 100 % at both of that run's hitches:

| Run | Hitches |
|---|---|
| A | 441 ms at 8 s, 184 ms at 68 s |
| join | 239 ms at 13 s, **2,332 ms** at 23 s (during the other agent's gradle build), 852 ms at 115 s |
| B | 214 ms at 32 s |
| C | 233, 172 and 116 ms at 22-26 s |

In run A's JFR, the server thread shows neither a spike nor a gap during those ticks: the process was descheduled, and
the server was not doing extra work. These hitches make useful stall tests, but MSPT and CPU at N=100 are noisier than in
the tables above.

### Summary

- **N=20 and N=50: exact.** 0 corrections, every compared claim exact, and every hit VALID (5,466 and 17,447). That
  includes no `INVALID MISSED`, where the pass build had 1-4 per run. 0 forced entries, and every mirror present.
- **Finding 2's join-storm collapse is fixed.** With the window opened at the 100th join, there were 0 corrections for the
  first 12 s. Run B converged within 9 s of the full GC that opens every JFR window. Mirrors are 99.0 of 99 in every
  N=100 run (the 21:32 jar had 26 of 99), there are no late mirror events, and teleport-accept and reconciler violations
  went from 847 (21:32) and 211 (pass) to 0-33. Hits at N=100 are 92-97 % VALID in the runs with a 30 s warm-up
  (77.5 % after the 2.3 s hitch), against 0 % (21:32) and 90.7 % (pass).
- **Not fixed: recovery from a stall of a few hundred ms or more at N=100.**
  - After a 239 ms hitch, everything recovered in 8 s.
  - The class histogram's full GC, which opens every JFR window, was enough in run C: one bot looped at exactly 20
    corrections/s (one per tick) from 11 s on, with no machine hitch (MSPT max ≤ 12 ms until 22 s). Run B, with the
    same GC, converged.
  - After the 441 ms hitch (run A), **5 bots stayed in correction loops for the rest of the window**, at 5-10
    corrections/s each.
  - After the 2.3 s hitch (join run), 47 bots looped. The total decayed from 250/s to 70/s over two minutes and never
    reached 0.
  - The torture rig's result ("hitch ≤ 1 s: 0 corrections") was measured with one client. It doesn't hold at 100.
- **Cost unchanged from the pass.** The server thread runs 0.09, 0.15 and 0.38-0.43 cores at N=20, 50 and 100. Auth
  costs 109, 96 and 155-180 µs per player per tick. N=100 MSPT depends on the loops: 7.5 ms with none, 11.8 ms with 5
  bots looping (below).
- **Network.** 1.05-1.07 pongs per server tick at every N (21:32: 1.31-1.73). Bandwidth is 20, 54 and 139-141 KiB/s per
  player.
- **Forced entries at N=100: still 130k-233k per window (pass: 314k).** Run B forced 131,709 in 75 s (1,750/s on
  average) and had 0 corrections from 10 s on. The harness can't yet tell whether they come in bursts or steadily. N=50
  has none.

### Server

| N | build / run | TPS | MSPT mean / p99 / max | server thread (cores) | auth µs / player / tick | server Netty (cores) | server-thread alloc MB/s | GC pauses (count / total / max ms) |
|---|---|---|---|---|---|---|---|---|
| 20 | merged | 20.00 | 1.70 / 5.94 / 20.3 | 0.09 | 109 | 0.14 | 10.8 | 2 / 10.3 / 6.9 |
| 20 | 21:32 | 20.00 | 1.27 / 5.16 / 10.1 | 0.08 | ≈ 120 | 0.17 | – | – / 3.0 / 1.6 |
| 50 | merged | 20.00 | 2.70 / 6.53 / 23.6 | 0.15 | 96 | 0.47 | 32.0 | 11 / 22.0 / 3.7 |
| 50 | pass | 20.00 | 2.21-2.53 | 0.14-0.17 | 98-109 | 0.50-0.61 | 33-36 | |
| 50 | 21:32 | 20.00 | 2.15 / 2.97 / 11.1 | 0.16 | ≈ 112 | 0.68 | 60.0 | – / 56 / 12.8 |
| 100 | merged A (5 bots looping) | 19.95 | 11.83 / 19.1 / 442 | 0.38 | 155 | 1.58 | 103 | 45 / 131 / 6.6 |
| 100 | merged B (75 s, converged) | 20.00 | 7.54 / 22.2 / 214 | 0.43 | 178 | 2.39 | 132 | 23 / 56 / 4.6 |
| 100 | merged C (66 s, 1 bot looping) | 19.24 | 8.97 / 22.2 / 233 | 0.43 | 180 | 2.31 | 139 | 18 / 81 / 9.9 |
| 100 | pass | 20.00 | 5.97, 7.62 | 0.36-0.40 | 146-160 | 1.99-2.02 | 104-108 | |
| 100 | 21:32 | 20.00 | 6.24 / 7.32 / 11.3 | 0.40 | ≈ 172 | 2.04 | 179.5 | – / 90 / 2.8 |

The 21:32 µs values are derived: server-thread cores × auth share ÷ (N × 20). Run B's higher CPU came while the machine's
load climbed from 5 to 12 (run C's from 4 to 13), so read 155-180 µs as one range.

**N=100 MSPT tracks the loops, not the CPU.** A window whose tick clock has no budget waits for the server tick
(`onServerTick → runReady`), so its work lands inside the tick:

| Run | windows run from the server tick | MSPT mean |
|---|---|---|
| A | 29 % of the server thread | 11.8 ms |
| B | 4.5 % | 7.5 ms |
| C (1 bot looping) | 8.7 % | 9.0 ms |
| pass | 9 % | |
| N=50 | about 0 | |

The join run shows the same thing in time: 6-8 ms before its 2.3 s hitch, 10-11 ms after it while 47 bots looped. Total
server-thread CPU stays at 0.38-0.43 cores either way.

Where the server thread goes is close to the pass's split:

| | N=20 | N=50 | N=100 (A / B) |
|---|---|---|---|
| in auth | 48 % | 64 % | 81-83 % |
| `applyTruth` (the one position write's vanilla waypoint and chunk-map update) | 13 % | 19 % | 29-31 % |
| `sim` | 15 % | 17 % | 13 % |

### Authority (bots are honest: expect 0 corrections, every hit VALID)

| N | build / run | exact / compared | corrections | bots corrected | attacks | VALID | rejected | forced entries | mirrors / viewer |
|---|---|---|---|---|---|---|---|---|---|
| 20 | merged | 60,141 / 60,141 | 0 | 0 | 5,466 | 5,466 (100 %) | – | 0 | 19.0 of 19 |
| 50 | merged | 150,300 / 150,300 | 0 | 0 | 17,447 | 17,447 (100 %) | – | 0 | 49.0 of 49 |
| 100 | merged A | 280,078 / 282,325 | 8,125 | 53 | 35,414 | 32,696 (92.3 %) | MISSED 2,309, REWIND 182, OUT_OF_REACH 119, other 42 | 232,805 | 99.0 of 99 |
| 100 | merged B (75 s) | 149,243 / 150,163 | 534 | 33 | 16,544 | 16,099 (97.3 %) | MISSED 320, REWIND 46, OUT_OF_REACH 8, other 10 | 131,709 | 99.0 of 99 |
| 100 | merged C (66 s) | 125,137 / 126,516 | 2,191 | 37 | 15,396 | 14,608 (94.9 %) | MISSED 524, REWIND 161, OUT_OF_REACH 19, other 35 | 173,117 | 99.0 of 99 |
| 100 | merged, from the 100th join | 249,946 / 251,115 | 16,018 | 47 | 35,873 | 27,790 (77.5 %) | MISSED 7,566, OUT_OF_REACH 247, REWIND 201, other 57 | 132,556 | 99.0 of 99 |
| 100 | pass (`perf-final-0618`) | 271,300 / 273,266 | 8,585 | 73 | 34,508 | 31,299 (90.7 %) | MISSED 2,670, OUT_OF_REACH 159, REWIND 106, other 52 | 314,487 | 97.8 of 99 |
| 100 | 21:32 | 102,985 / 106,430 | 181,895 | 77 | 58,717 | 0 | NOT_PRESENT 44,953, REWIND 115 | 140,363 | 26.0 of 99 |

How to read the N=100 rows:

- "Other" includes 9-40 attacks dropped with a VALID verdict (by a gate before the hit lands).
- Over-cap multi-clicks (N=20, N=50, N=100 A and B) were 24, 88, 152 and 72, all dropped by
  `max-attacks-per-client-tick`.
- Pearl teleports (61, 120, 148) were applied exactly, with one `truthRewrites` each (the `justTeleported` fix).
- The new counters at N=100:
  - `forcedLanded` 28-81 and `correctionsHeld` 158-462.
  - `absoluteCorrections` 228 (B) and 3,918-7,799 (with loops).
  - `mirrorRepairs` 36-86.
  - `pathStepSwitches` 208-399. N=20 and N=50 have 0 of all of these.
- `MISSED` follows the loops: 320 (B), 2,309 (A), 7,566 (join). When a bot's own position is being corrected, its pick
  and the server's rewind see different eyes.

### Network, per player

| N | down KiB/s | up KiB/s | clientbound packets/s | TrackedWaypoint/s | pings/s | serverbound packets/s | pongs per server tick | Paper limiter, peak 7 s rate |
|---|---|---|---|---|---|---|---|---|
| 20 | 19.9 | 0.98 | 1,033 | 131 | 21.2 | 75 | 1.06 | 86 / 500 |
| 50 | 54.1 | 1.00 | 2,819 | 343 | 21.3 | 78 | 1.07 | 90 / 500 |
| 100 (A / B) | 141.0 / 138.7 | 1.03 / 1.00 | 6,438 / 6,291 | 1,394 / 1,407 | 21.0 / 21.4 | 79 | 1.05 / 1.07 | 117 / 109 of 500 |
| 21:32: 20, 50, 100 | 31.2, 83.6, 135.2 | 1.0-1.6 | 1,282, 3,466, 6,441 | 393, 1,025, 1,479 | 26-35 | 80-109 | 1.31-1.73 | 105-155 / 500 |

At N=100 the locator bar is still the biggest O(N²) item: `TrackedWaypoint` is 1,400/s per player, and the vanilla
waypoint update under `applyTruth` allocates 28 MB/s of `AzimuthWaypoint` alone. Finding 6 still applies. At N=50, the top
auth allocation site is now `PathFeed.move:173`: the stall batch's shared step cache still builds one `List.of(DeltaStep)`
per step it constructs (10 MB/s sampled).

### Memory

| | N=20 | N=50 | N=100 A | N=100 B |
|---|---|---|---|---|
| live heap after a full GC, change over the window | +2.6 MB | +3.8 MB | +21.9 MB | +14.5 MB |
| auth.* classes, start → end | 1.01 → 1.03 MB | 3.60 → 3.58 MB | 10.73 → 10.90 MB | 10.69 → 10.64 MB |

At N=100 the growth is `long[]` (+8.0 / +5.2 MB) and `Packet[]` (0.5 → 2.7 / 0.5 → 1.9 MB). The number of instances
stays the same. These are `Transactions`' entry arrays (`entryPackets`, `entrySeqs`, `entryMillis`) growing to the
outstanding-entries high-water mark: up to 2,061 / 1,180 / 3,872 entries outstanding on one connection. The pass build
showed the same (+11-16 MB at N=100). This is not a leak, but those arrays never shrink back. Run C grew +42.8 MB:
`long[]` +8.3 MB, plus 6.0 MB of `MoveEntityPacket.PosRot` and 4.6 MB of `List12` (its steps) still referenced at the
end. That is the outbound log of the connection with 445 unacked pings, holding what it was sent. It is bounded by the
log's memory cap, but it shows what one stuck connection costs.

### The join storm (finding 2), second by second

Corrections per second, all bots summed, from the plugin's series:

| Window | What happens |
|---|---|
| From the 100th join (`join100`) | t 1-12: **0** (MSPT 6-8 ms; one 48 ms tick). t 13: 239 ms hitch → 59, 67, 52, 18, 13, 8, 14, then **0** at t 21-22. t 23: **2,332 ms** hitch → 194-249/s for 20 s, 144 at 60 s, 115 at 90 s, 85 at 120 s, 72 at 147 s (47 bots; 7 of them at about 1,200 corrections each) |
| Run B (30 s warm-up; the window opens with the class-histogram full GC) | t 2-9: 127, 102, 120, 73, 61, 23, 8, 16 → **0 from t 10 to t 75** (one blip of 4 at t 18), through a 214 ms tick and the machine's load rising from 5 to 12 |
| Run A (same, plus a 441 ms hitch at t 8) | t 2-7: 51-165 → t 10-13: 152-190 → about 38/s from t 48 → 184 ms hitch at t 68: 130 → **about 37/s flat from t 91 to t 150**, mostly 5 bots |
| Run C (same as B; machine hitches at t 22-26) | t 2-10: 24-91 → **exactly 20/s from t 11 to t 21**, all LoadBot073 (MSPT max 8-9 ms) → hitches: 187, 167, 94 … → back to 20/s from t 47 to t 63 |
| Pass build (`perf-final-0618`, same harness) | 195-390/s after the histogram GC, flat at about 97/s at the end (73 bots corrected) |

So 100 joins no longer start a cascade, and a 239 ms hitch healed in about 8 s. What is left is a loop that can start
at a stall of a few hundred ms and then does not converge: the histogram's full GC in C, 441 ms in A, 2.3 s in the join
run. B, with the same GC as C, shows that it doesn't always start. The looping bots look the same in every run:

- **Few claims compared.** In run A, LoadBot017/086/082/046/014 were compared in 76-215 of their ~3,000 windows. Bots
  without a loop were compared in 2,100-2,960. In the join run, the 7 worst were compared in 526-576. In run C,
  LoadBot073 was compared in 36 of ~1,300 windows, and one connection, probably LoadBot073's, had up to **445 pings
  unacked** (about 21 s; 23-42 in the other runs). The bot logged no errors and kept receiving: it saw 602 of the 603
  corrections.
- **A steady correction rate.** In run A, 5-10 per second (1,442, 1,398, 939, 783 and 781 over the window). LoadBot073
  in run C got one per tick. The expectation was about 1 per divergence.
- **Their own hits rejected** (`MISSED`) in A and the join run.

**Hypothesis (to check with `--set traceOnCorrection=true` on one of these bots).** Two things meet at N=100:

- No correction is sent while forced entries are outstanding.
- The one absolute resync waits until every forced entry has landed.

If forced entries keep coming at run B's average of 1,750/s, a looping bot may never reach "all landed". Its
corrections then stay held, or go out late against a moving target.

**Forced entries at N=100, open question.** Outstanding pings peak at 23-42 on some connection (1-2 s of pings), so some
acks run past the deadline (`ceil(slack RTT / 50 ms) + ack-jitter-ticks` paced windows, about 250-300 ms). The cause is
either the server's Netty write path (1.6-2.4 cores) or the in-JVM bots' receive threads (1.3-2.2 cores on 4 threads).
This harness can't separate the two. Per-bot forced counts and an ack-RTT p99 per connection in `Measure`, or bots in a
second JVM, would settle it.

**K per viewer at N=100.** There were 208-399 `pathStepSwitches` per window (386 in run C's 66 s): about 4 per viewer
in 150 s, each one re-pairing every player fed to that viewer. N=20 and N=50 had 0. A switch needs 5 s of steady jitter
on one side of the 25 ms K1 threshold. So at N=100 some viewers' p90 - p10 ack jitter keeps crossing it: mean ack RTT
29-32 ms against an rttBase of 21-24 ms, with outstanding pings peaking at 23-445. Not investigated.

**Not run this time:** N=100 with the locator bar off, and N=50 at ≈125 ms RTT (finding 3). Both are still asked for in
NOTES.md.

## Stall re-run on the classic build (2026-10-22 20:05)

**Build:** `torture/jars/revivalpaper-audit7.jar` (sha256 `43b0a61d…58f86`): commit 3638e753 plus the working tree that
became the next commit (the jump-reset alert, the two classic switches, no self echoes of client-owned sprint state).
It carries the review-2 netcode batch, including X2-04: forced entries hold corrections for one ack deadline at most.
**Run:** `runs/audit7-hitch100`. N=100, the same harness and settings as above (locator bar on, view and simulation
distance 6/4), a 30 s warm-up, a 150 s window, no JFR (so no class-histogram GC opens the window). Two server hitches
were injected with `load hitch`: 450 ms at 30 s and 2,300 ms at 70 s (`load.py --hitch 30:450 --hitch 70:2300`). The
machine was quiet apart from this run.

**Finding 2's loops are gone.** Corrections per second, all bots summed:

| Window | What happens |
|---|---|
| t 1-30 | **0** |
| 450 ms hitch at t 30 | 3, 5, 54, 3, 1, then **0 from t 36** (6 s) |
| t 55: a bot-side hiccup (the bots' pongs fell to 1,587/s from 2,250, then 3,686 the next second; server MSPT max 10 ms) | 141, 49, 10, 1, then **0 from t 60** (4 s) |
| 2,300 ms hitch at t 70 (every bot frozen: `freezes` 100) | 561, 95, 42, 35, 16, 4, then **0 from t 77** (7 s) to the end of the window (t 148) |

Every bot took some corrections (1,020 in all, 54 at most for one bot), and none looped. The 09:30 build left 5 bots
looping after a 441 ms hitch and 47 after a 2.3 s one.

| | audit7, hitches injected | 09:30 merged B (converged) |
|---|---|---|
| TPS | 19.69 (the 2.3 s hitch) | 20.00 |
| MSPT mean / p99 / max | 6.38 / **7.08** / 2,315 (the hitch) | 7.54 / 22.2 / 214 |
| claims compared / bit-exact / sub-µm / over threshold | 294,601 / 267,575 / 26,166 / 606 | 150,163 / 149,243 / - / 235 |
| corrections (absolute) | 1,020 (469) | 534 (228) |
| attacks queued / accepted / rejected | 31,073 / 29,604 / 407 (98.6% of judged hits `VALID`) | 16,544 / 16,099 / 384 |
| rejected by reason | MISSED 370, OUT_OF_REACH 8, OCCLUDED 3, REWIND 2, VALID but gated 24 | MISSED 320, REWIND 46, OUT_OF_REACH 8 |
| forced entries | 102,657 | 131,709 |
| mirrors per viewer, late events | 99.0 of 99, 0 | 99.0 of 99, 0 |
| old gen after GC, start → end | 464 → 464 MB | |
| down / up per player | 146.9 / 1.01 KiB/s | 138.7 / 1.00 KiB/s |

How to read it:

- **Sub-µm claims** (`subMicronClaims`, counted since the NAS block-glitch fix in 98008afa) are claims off the truth by
  less than `correction-persist-min`. They are never corrected: before that fix a 1e-7 gap that rounding kept was
  "corrected" by a teleport every 20 ticks. A few bots carry such a gap and count it on every claim; it moves nothing
  (the eye offset refusals, 27,577, are the same gaps). The real client has none: every real-client run is 100.00%
  exact.
- **Self echoes no longer sent:** `flagEchoesDropped` 24,526 and `sprintSyncsDropped` 21,876 (about 1.6 and 1.5 per player
  per second): the client's own sprint changes, which it already had.
- **Forced entries are still about 700 per second** at N=100 (the sampled connection's ack RTT: base 105 ms, high 483 ms,
  the in-JVM bots' receive threads at 100 bots). They no longer start loops; the harness still can't tell how much of
  that RTT is the server's Netty and how much the bots'.
- `MISSED` (370) still follows the correction bursts: a bot being corrected picks from an eye the server doesn't use.

Still not run: 100 bots with the locator bar off, 50 at about 125 ms RTT on the current rewind cap, and 200.

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
