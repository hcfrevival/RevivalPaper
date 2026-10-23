# Authoritative RevivalPaper through a bad network

Status: measured 2026-10-22, 01:38 to 04:26 EDT. The real vanilla 26.3 client played RevivalPaper in `enforce` through a
TCP proxy that adds latency, jitter, stalls, bandwidth caps and bursty delivery. Rig and usage: [Reproduce](#reproduce),
`~/Developer/tools/auth-rig/torture/`. Raw runs: `torture/runs/` (`grid1`, `stall1`, `hitch1`, `k1`, `tune-*`,
`marathon`, `marathon2`, `bots2`, `duelfix`, `ceiling`, `vanilla-fartp`; each has `results.json` and `analysis.txt`).

**Build under test:** `torture/jars/revivalpaper-torture-20261022-0129.jar` (sha256 `92c7799e…32c5b35e`), built by
`torture/build-jar.sh` from a frozen APFS clone of `revival/authoritative@f7a0096e` plus 10 uncommitted files (fingerprint
`6159c029451f51aa`, Minecraft sources at `8605989 Authoritative projectiles`). It has the netcode's 01:10 join fix. Every
file:line below points at that clone, `torture/snapshot.noindex/RevivalPaper/leaf-server/src/main/java/gg/hcfrevival/revival/auth/`
(short: `auth/`). The methods are the same in the live tree; the lines may have moved.

**Server:** `~/Developer/hcfr-servers/auth-torture` (a clone of the gym world), Aikar's flags at 3 GB, `revival.yml`
defaults with `authoritative.mode: enforce` and `debug.log: true`, stock Spigot movement thresholds (0.0625 / 10) and
`player_movement_check` on, Leaf's async tracker off, Paper's 30 s keep-alive. One real client (the rig agent drives keys,
mouse buttons and rotation; game logic untouched), one in-process second player `RigTarget` (the enforce rig's
`FakeClient`, under authority like any connection), and for (b) 12 load-harness bots on real TCP. Other agents shared the
machine (load average 3 to 6).

## Verdict

**On a steady link the authority is exact: nothing an honest player does is corrected or rejected, at any RTT up to the
ceiling and any jitter tested. Anything that interrupts the link breaks it, and one 300 ms Wi-Fi hiccup at the wrong
moment kicks an honest player.**

- **Steady links: perfect.** 16 conditions, RTT 0 to 250 ms, jitter (RTT standard deviation) 0 to 30 ms, gaussian and
  uniform, 1 Mbit/s down / 256 kbit/s up, bursty 30 ms delivery. 44,519 client ticks: **0 corrections**, every one of
  35,165 position claims exact, **3,759 of 3,759 client hits VALID**, the server's would-be verdict equal to the client's
  own pick on 31,775 of 31,775 ticks and the mirrored box bit-equal on all of them, **848 of 848 knockbacks applied in the
  window the client applied them**, 0 timer refusals, 0 forced entries, windows run at most 2.2 ms (p99) after their
  `TickEnd` arrives, 0 view freezes of the strafing target inside `exact-radius`. Lag compensation stays honest at 250 ms:
  the closest a hit came to the rewind cap was 61 ms (p10 margin 91 to 146 ms per condition).
- **Fighting 12 load bots** (RTT 30 to 250): the same, 768 of 768 hits on moving, fighting bots VALID, 111 of 111 of
  their knocks at the ack, 0 corrections.
- **Every network stall kicks.** A 300 ms stall (both ways, one way down, one way up), a 1 s stall and a 2 s stall:
  7 of 8 stall conditions ended in `Invalid transaction replies` 9 s to 2.6 min after joining (P0, [F2](#f2-p0-a-stalled-server-teleport-kicks-the-player)).
  The one that survived (RTT 30) still force-applied entries after 37 of 41 stalls and lost 5 % of its hits.
- **Every far teleport drops the player into the void,** at every latency including localhost (P0, [F1](#f1-p0-a-far-teleport-drops-the-player-through-the-floor-into-the-void)).
- **Hitches leave the player behind for good.** A client hitch of 500 ms or more leaves 50 to 100 ms of windows queued
  that never drain; with the bank used up, a 300 ms server lag spike then leaves the player's windows 350 ms late for the
  rest of the run, and a 1.2 s spike almost a second. Every later hit of that player comes back `REWIND` (63 to 73 of
  ~110 hits), viewers see it late, and each hitch adds to the backlog (P0, [F4](#f4-p0-the-tick-clock-never-drains-a-backlog)).
- **Stalls without teleports: correction storms.** A knock force-applied 2 to 5 ticks before the client got it starts a
  correction chain that counts the knock twice and re-arms on every later knock. In a minute-long sprint duel with a
  300 ms hiccup every 4 to 5 s: up to 505 corrections (up to 2.6 blocks) and 7 to 22 % of the client's hits lost (P0 for
  HCF, [F3](#f3-p0-the-ack-deadline-counts-windows-the-client-sent-before-it-could-have-the-packet),
  [F5](#f5-p0-corrections-count-an-in-flight-knock-twice)).
- **Latency budget.** Input to what another player sees is **RTT + 135 to 170 ms** with K2 paths (144 ms at RTT 0),
  steady under jitter (p90 within ~40 ms of p50 at 30 ms jitter). The authority's own share is the window wait, 0.2 ms.
  **K1 paths take 50 to 70 ms off** with no freezes up to 10 ms of jitter; see [K1](#k1-against-k2).

## Rig

| Part | What |
|---|---|
| `torture/proxy/netem.py` | asyncio TCP proxy, per direction: base delay; jitter gaussian N(0, j) or uniform U(-j, j); TCP order kept (a late chunk holds the ones behind it); periodic or one-off stalls (a release inside a stall window moves to its end: buffered, then a burst); bandwidth cap (serialization); batching to 30 ms boundaries. The profile file is re-read live. Logs every chunk: arrive, release, write (epoch µs). `proxy/selftest.py` checks it against an echo server: +1.4 ms overhead per hop, jitter and stalls as configured |
| `torture/agent/` | a copy of the rig agent with epoch-µs stamps on every tick, frame, drained and sent packet, `aim: nearest`, and a `hitch` step (the client thread sleeps, then catches up) |
| `torture/probe/` | a copy of the enforce probe: `us` on every line, a per-server-tick `net` line (RTT base/high/last, queued windows, bank, oldest ping, frozen, refused, forced, corrections, keep-alive), the judge's own rewind cap (`CombatWiring.sharedRewindCap`) in the would-be verdicts, `rigact lag <ms>` (a long server tick), the rig player healed every tick |
| `torture/loadbots/` | a copy of the load harness plugin (arena at y 150) |
| `torture/torture.py` | one server session, the proxy, then one client launch per condition; a condition can switch the link mid-run (`step30to250`, the `*w` conditions) |
| `torture/analyze.py`, `tables.py`, `drill_queue.py`, `build-doc.py` | the numbers below |

Jitter is quoted as the RTT's standard deviation: each direction gets N(0, j/√2). The measured RTT is the server's own
ack RTT (ping to pong), so it includes up to one client frame (16.7 ms at 60 fps).

Scenario sets (`torture/scenarios.py`): **combat** (16): the enforce rig's knockback, blocking, effects and attack
scenarios on a strafing `RigTarget`, plus `t_duel_long` (300 ticks: strafing W+A/D, aiming, 10 CPS, the target
strafe-jumps and hits back every 15 ticks) and `t_kb_chain` (20 knocks alternating ±x while strafing). **move** (16): a
cross-section of the gym (sprint-jump chains, stairs, slabs, ice, slime, water, ladder, ceiling, flush wall, sneak edge,
cobweb, crawl, honey), nearest-first from the arena, then `t_far_tp`. **hitch**: client hitches of 250/500/1000 ms and
2000 ms, server hitches of 300/700/1200 ms, during a duel. **marathon**: one teleport, then a 1,200-tick duel, with the
stall profiles switched on only after the teleport. **far** (pvp-issues VI-1): the rig player watches the target
strafe-jump 30 and 45 blocks away, past `exact-radius`, holding a sword and then a bow (`t_far_{sword,bow}_{30,45}`,
200 ticks each, swords first so the bow's `ranged-linger-ticks` doesn't carry over); run it with `--revival
replication.ranged-radius=64.0`. **bots**: 600 ticks in the load-bot arena aiming at the nearest player.

What each column means:

- *corrections*: `AuthStats.corrections` over the condition's scenarios (absolute ones in brackets); *client
  PlayerPosition corrections*: correction teleports (ids below the ping band) the client actually drained.
- *forced entries*: entries applied at their ack deadline instead of their ack (`AuthStats.forcedEntries`); *timer
  refused*: windows the tick clock refused; *player frozen*: times the player went frozen (oldest ping or open window
  past `max-unacked-ms`); *max queued*: most windows waiting for the tick clock at a server tick.
- *knocks at ack*: self `SetEntityMotion`/`Explode` the client drained at run tick `t`, against the window `T0 + t` the
  server applied the velocity in.
- *client hits VALID*: every `Attack` the client sent (it only sends one when its own pick hit) and the judge's verdict.
- *pick = verdict*, *mirror box bit-equal*: per tick, the server's would-be verdict on `RigTarget` (same eye, rotation,
  mirrors, world view and cap as the judge) against the client's own pick, and the box `MirrorBook` sampled against the
  box the client picked against.
- *K2 view freezes*: client ticks where `RigTarget`'s displayed position did not move while its server position did,
  within 24 blocks (`exact-radius`, K2 paths).
- *window wait*: from the proxy writing a tick's `ClientTickEnd` to the server to the server simulating that tick.
- *view*: `RigTarget`'s input (its server tick, in process) to the rig client showing that position (matched on the
  target's own path, newest first). *input -> viewer* = the rig client's `TickEnd` upstream + window wait + view: the
  end-to-end latency between two players on this link.

## Results

### (a) Steady links

| condition | run | measured ack RTT p10/p50/p90/p99 (ms) | claims exact | corrections (abs) | correction size p50/max (blocks) | client PlayerPosition corrections | forced entries | timer refused | player frozen | max queued | knocks at ack | client hits VALID | pick = verdict | mirror box bit-equal | K2 view freezes | status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `base` | grid1 | 4/11/17/18 | 2200/2200 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 53/53 | 157/157 | 1365/1365 | 1365/1365 | 0/1050 | ok |
| `r30j0` | grid1 | 37/43/49/52 | 2200/2200 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 53/53 | 160/160 | 1365/1365 | 1365/1365 | 0/1008 | ok |
| `r30j10` | grid1 | 26/40/51/62 | 2199/2199 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 53/53 | 161/161 | 1365/1365 | 1365/1365 | 2/946 | ok |
| `r30j30` | grid1 | 15/43/73/97 | 2198/2198 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 53/53 | 157/157 | 1365/1365 | 1365/1365 | 5/818 | ok |
| `r80j0` | grid1 | 85/90/98/100 | 2199/2199 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 53/53 | 162/162 | 1365/1365 | 1365/1365 | 4/1050 | ok |
| `r80j10` | grid1 | 77/90/100/112 | 2199/2199 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 53/53 | 264/264 | 2200/2200 | 2200/2200 | 0/1217 | ok |
| `r80j30` | grid1 | 58/89/121/145 | 2198/2198 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 53/53 | 260/260 | 2275/2275 | 2275/2275 | 0/1146 | ok |
| `r150j0` | grid1 | 154/161/169/171 | 2197/2197 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 53/53 | 255/255 | 2275/2275 | 2275/2275 | 0/1328 | ok |
| `r150j10` | grid1 | 147/162/176/187 | 2198/2198 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 53/53 | 244/244 | 2275/2275 | 2275/2275 | 0/1128 | ok |
| `r150j30` | grid1 | 130/162/191/215 | 2197/2197 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 53/53 | 254/254 | 2275/2275 | 2275/2275 | 0/1009 | ok |
| `r250j0` | grid1 | 255/264/268/271 | 2196/2196 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 53/53 | 307/307 | 2275/2275 | 2275/2275 | 0/1167 | ok |
| `r250j10` | grid1 | 247/263/277/289 | 2195/2195 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 53/53 | 310/310 | 2275/2275 | 2275/2275 | 0/1215 | ok |
| `r250j30` | grid1 | 230/260/291/310 | 2196/2196 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 53/53 | 306/306 | 2275/2275 | 2275/2275 | 0/1056 | ok |
| `r80bw` | stall1 | 77/90/100/115 | 2199/2199 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 53/53 | 252/252 | 2275/2275 | 2275/2275 | 0/1328 | ok |
| `r80burst` | stall1 | 103/118/135/146 | 2198/2198 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 53/53 | 275/275 | 2275/2275 | 2275/2275 | 0/1280 | ok |
| `r150j30u` | stall1 | 129/156/191/210 | 2196/2196 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 53/53 | 235/235 | 2275/2275 | 2275/2275 | 0/1200 | ok |

The 2 to 5 K2 view freezes in `r30j10`, `r30j30` and `r80j0` are all in `t_duel_long` of the first five conditions: the
harness respawned `RigTarget` there, and the fresh player fell through the floor ([F1](#f1-p0-a-far-teleport-drops-the-player-through-the-floor-into-the-void)).
From `r80j10` on the target is kept; the re-run of `kb_hit_sprint` + `t_duel_long` for those five conditions
(`duelfix`) is clean: 508 of 508 hits VALID, 1,800 of 1,800 picks and boxes equal, 110 of 110 knocks at the ack, 0 view
freezes. Beyond 24 blocks (vanilla
tracker cadence) the target froze on 97 of 6,138 moving ticks (1.6 %; 4 % at 30 ms jitter): that is vanilla's 2-tick
cadence, not the authority.

| condition | TickEnd upstream p50/p99 (ms) | window wait p50/p99/max (ms) | rewind age p50/max (ms) | rewind cap p50 (ms) | cap - age min/p10 (ms) | hit distance server vs client max | view (target tick -> shown) p10/p50/p90 (ms) | input -> viewer p50/p90 (ms) |
|---|---|---|---|---|---|---|---|---|
| `base` | 0.1/1.4 | 0.2/1.7/26.8 | 122/134 | 265 | 131/136 | 0 (1 ulp) | 118/143/152 | 144/153 |
| `r30j0` | 15.8/17.2 | 0.2/1.4/13.4 | 154/160 | 300 | 139/144 | 0 (1 ulp) | 133/158/165 | 174/182 |
| `r30j10` | 20.7/35.2 | 0.2/1.4/12.6 | 160/182 | 314 | 105/138 | 0 (1 ulp) | 137/159/168 | 180/196 |
| `r30j30` | 31.8/73.1 | 0.2/1.2/13.0 | 190/230 | 344 | 61/130 | 0 (1 ulp) | 148/166/183 | 198/236 |
| `r80j0` | 41.2/42.2 | 0.2/0.8/1.5 | 188/238 | 349 | 115/117 | 0 (1 ulp) | 149/172/216 | 213/258 |
| `r80j10` | 45.8/60.6 | 0.2/0.9/4.9 | 215/236 | 361 | 107/136 | 0 (1 ulp) | 184/190/199 | 236/251 |
| `r80j30` | 54.9/98.9 | 0.2/0.6/1.0 | 234/301 | 402 | 92/134 | 0 (1 ulp) | 172/193/210 | 248/288 |
| `r150j0` | 76.4/78.6 | 0.2/0.9/13.0 | 279/293 | 417 | 124/130 | 0 (1 ulp) | 220/227/236 | 304/314 |
| `r150j10` | 80.8/95.5 | 0.2/0.9/11.5 | 304/329 | 431 | 98/116 | 0 (1 ulp) | 211/234/248 | 315/337 |
| `r150j30` | 91.5/132.9 | 0.2/0.9/19.7 | 308/379 | 484 | 112/146 | 0 (1 ulp) | 206/228/245 | 319/359 |
| `r250j0` | 126.5/127.2 | 0.2/1.1/4.3 | 399/410 | 514 | 102/109 | 0 (1 ulp) | 264/296/301 | 422/428 |
| `r250j10` | 131.0/145.2 | 0.3/2.2/17.2 | 401/436 | 528 | 98/113 | 0 (1 ulp) | 256/277/296 | 408/435 |
| `r250j30` | 141.2/183.7 | 0.2/1.4/23.5 | 399/476 | 571 | 91/130 | 0 (1 ulp) | 254/278/296 | 419/460 |
| `r80bw` | 46.6/60.8 | 0.2/0.8/8.3 | 230/245 | 365 | 116/125 | 0 (1 ulp) | 186/201/209 | 248/263 |
| `r80burst` | 61.7/87.1 | 0.2/1.2/2.1 | 235/303 | 400 | 111/123 | 0 (1 ulp) | 187/204/219 | 266/295 |
| `r150j30u` | 94.6/113.0 | 0.2/1.1/5.6 | 293/357 | 465 | 97/125 | 0 (1 ulp) | 209/227/246 | 322/357 |

- Windows never wait on a steady link: p99 0.6 to 2.2 ms after the `TickEnd` reaches the server, at every jitter.
  (Before matching the `ClientTickEnd` chunk exactly, jittered links looked like 50 ms waits: the 26.3 client flushes every
  packet on its own, so a tick is several TCP chunks with different jitter.)
- Hit distance on the server equals the client's pick distance to one ulp: Neue's tipper/sourspot classification
  (`HitCheck.distance()`) is the client's own at every latency.
- The view half is ~145 ms at RTT 0: 100 ms of K2 path (two client ticks per step) plus the wait for the client's next
  tick and the pick at its start.

### (a) Stalls, RTT step

| condition | run | measured ack RTT p10/p50/p90/p99 (ms) | claims exact | corrections (abs) | correction size p50/max (blocks) | client PlayerPosition corrections | forced entries | timer refused | player frozen | max queued | knocks at ack | client hits VALID | pick = verdict | mirror box bit-equal | K2 view freezes | status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `r30s300` | stall1 | 27/40/51/68 | 2193/2195 | 3 (1) | 0.002/0.003 | 9 | 187 | 0 | 0 | 0 | 52/53 | 244/258 (INVALID REWIND 11, UNKNOWN INEXACT_TARGET 3) | 2168/2200 | 2127/2200 | 28/986 | ok |
| `r80s300` | stall1 | 77/88/104/330 | 0/0 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 | KICKED |
| `r150s300` | stall1 | 147/163/178/397 | 1273/1315 | 40 (16) | 0.482/1.942 | 40 | 79 | 0 | 0 | 0 | 46/47 | 258/297 (INVALID REWIND 19, UNKNOWN INEXACT_TARGET 10, INVALID OUT_OF_REACH 4, INVALID MISSED 6) | 1874/1985 | 1904/1985 | 50/1082 | KICKED |
| `r250s300` | stall1 | 248/262/277/451 | 244/244 | 0 (0) | -/- | 0 | 8 | 0 | 0 | 0 | 8/8 | 0/0 | 255/255 | 245/255 | 3/59 | KICKED |
| `r80down300` | stall1 | 77/89/100/116 | 1485/1608 | 35 (10) | 0.276/1.287 | 35 | 49 | 0 | 0 | 0 | 46/47 | 277/291 (INVALID REWIND 9, UNKNOWN INEXACT_TARGET 5) | 2173/2215 | 2121/2215 | 88/1253 | KICKED |
| `r80up300` | stall1 | 76/90/100/115 | 305/305 | 0 (0) | -/- | 0 | 3 | 0 | 0 | 0 | 8/8 | 0/0 | 315/315 | 313/315 | 2/67 | KICKED |
| `r80stall1000` | stall1 | 76/90/101/114 | 66/66 | 0 (0) | 18.333/18.333 | 0 | 0 | 0 | 0 | 11 | 3/3 | 0/0 | 75/75 | 71/75 | 0/0 | KICKED |
| `r80stall2000` | stall1 | 77/89/100/114 | 1183/1185 | 5 (3) | 1.011/6.610 | 5 | 204 | 203 | 3 | 0 | 43/44 | 231/231 | 1732/1805 | 1556/1805 | 14/1092 | KICKED |
| `step30to250` | stall1 | 41/253/283/299 | 2200/2200 | 0 (0) | -/- | 0 | 28 | 0 | 0 | 0 | 53/53 | 269/309 (INVALID REWIND 9, UNKNOWN INEXACT_TARGET 31) | 2195/2275 | 2196/2275 | 10/1181 | ok |

| condition | stalls | with forced entries | with corrections | with refusals | with freezes | max queued | window wait 2-4 s after a stall, p50/max (ms) |
|---|---|---|---|---|---|---|---|
| `r30s300` | 41 | 37 | 3 | 0 | 0 | 0 | 0/0 |
| `r80s300` | 2 | 2 | 1 | 0 | 0 | 0 | -/- |
| `r150s300` | 28 | 28 | 4 | 0 | 0 | 0 | 0/0 |
| `r250s300` | 6 | 6 | 1 | 0 | 0 | 0 | 0/0 |
| `r80down300` | 38 | 36 | 4 | 0 | 0 | 0 | 0/0 |
| `r80up300` | 8 | 8 | 1 | 0 | 0 | 0 | 0/0 |
| `r80stall1000` | 1 | 1 | 1 | 0 | 0 | 11 | -/- |
| `r80stall2000` | 7 | 7 | 3 | 7 | 7 | 0 | 0/0 |

Every kick is the same: `AuthTorture broke the client protocol under authority: accept for a server teleport never sent`,
then `lost connection: Invalid transaction replies` ([F2](#f2-p0-a-stalled-server-teleport-kicks-the-player)). The
scenarios teleport the player about 35 times per condition, so a stall lands on one within seconds to minutes.

The **marathon** runs take the teleports out: the stall profile is switched on after the only teleport, and the player
fights for a minute (walking, so it drifts out of reach and lands fewer hits; `marathon2` repeats it sprint-strafing).
No kicks without teleports, but every stall profile produces correction storms: each stall force-applies whatever is in
flight (F3), the knock among it starts an F5 chain, and at RTT ≥ 150 the chain re-arms on every later knock. After the
first 1 s stall the player's windows ran ~550 ms late for the rest of the minute (F4) and 365 of 431 hits came back
`REWIND`.

`marathon2` is the same minute sprint-strafing, so the rig player stays in reach: 599 of 599 hits VALID on the steady
link, then with a 300 ms stall every 5 s (every 4 s one way) up to 505 corrections per minute (87 to 505; none in the
down-only run) and 7 to 22 % of hits lost (`REWIND`, `UNKNOWN INEXACT_TARGET`, and `INVALID MISSED` while the attacker's eye is off during a correction);
after one 1 s stall, 524 of 589 hits lost to the backlog.

| condition | run | measured ack RTT p10/p50/p90/p99 (ms) | claims exact | corrections (abs) | correction size p50/max (blocks) | client PlayerPosition corrections | forced entries | timer refused | player frozen | max queued | knocks at ack | client hits VALID | pick = verdict | mirror box bit-equal | K2 view freezes | status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `r80j10` | marathon | 78/90/100/112 | 1202/1202 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 79/79 | 44/44 | 1240/1240 | 1240/1240 | 0/1126 | ok |
| `r30s300w` | marathon | 28/40/52/66 | 1148/1183 | 17 (5) | 0.468/1.016 | 17 | 78 | 0 | 0 | 0 | 77/79 | 63/68 (INVALID REWIND 3, UNKNOWN INEXACT_TARGET 2) | 1230/1240 | 1221/1240 | 27/967 | ok |
| `r80s300w` | marathon | 77/90/101/136 | 858/1036 | 71 (22) | 0.353/1.617 | 70 | 94 | 0 | 0 | 0 | 72/79 | 95/104 (INVALID REWIND 5, UNKNOWN INEXACT_TARGET 4) | 1219/1240 | 1218/1240 | 32/1014 | ok |
| `r150s300w` | marathon | 148/162/177/292 | 234/321 | 215 (99) | 0.388/3.546 | 214 | 50 | 0 | 0 | 0 | 73/77 | 65/76 (INVALID REWIND 7, UNKNOWN INEXACT_TARGET 4) | 1218/1240 | 1204/1240 | 46/1081 | ok |
| `r250s300w` | marathon | 248/262/278/296 | 26/132 | 176 (79) | 0.600/2.894 | 175 | 77 | 0 | 0 | 0 | 72/79 | 366/593 (INVALID REWIND 48, UNKNOWN INEXACT_TARGET 33, INVALID MISSED 143, INVALID PICKED 2, INVALID OUT_OF_REACH 1) | 746/1240 | 1177/1240 | 52/994 | ok |
| `r80down300w` | marathon | 77/90/101/115 | 753/986 | 89 (28) | 0.285/1.447 | 89 | 32 | 0 | 0 | 0 | 74/79 | 90/98 (INVALID REWIND 3, UNKNOWN INEXACT_TARGET 3, INVALID PICKED 2) | 1224/1240 | 1210/1240 | 76/1190 | ok |
| `r80up300w` | marathon | 78/90/100/113 | 678/969 | 103 (31) | 0.499/1.498 | 102 | 23 | 0 | 0 | 0 | 72/78 | 70/77 (INVALID REWIND 4, UNKNOWN INEXACT_TARGET 3) | 1228/1240 | 1231/1240 | 25/1072 | ok |
| `r80stall1000w` | marathon | 78/90/100/113 | 133/137 | 81 (38) | 0.979/4.307 | 81 | 91 | 0 | 0 | 12 | 72/75 | 61/431 (INVALID REWIND 365, UNKNOWN INEXACT_TARGET 5) | 485/1230 | 1168/1230 | 56/1145 | ok |
| `r80stall2000w` | marathon | 77/90/101/117 | 550/893 | 85 (29) | 0.294/7.135 | 85 | 126 | 122 | 3 | 0 | 69/72 | 78/79 (INVALID MISSED 1) | 1212/1240 | 1132/1240 | 10/1076 | ok |

| condition | stalls | with forced entries | with corrections | with refusals | with freezes | max queued | window wait 2-4 s after a stall, p50/max (ms) |
|---|---|---|---|---|---|---|---|
| `r30s300w` | 12 | 12 | 2 | 0 | 0 | 0 | 0/1 |
| `r80s300w` | 12 | 12 | 8 | 0 | 0 | 0 | 0/1 |
| `r150s300w` | 12 | 12 | 10 | 0 | 0 | 0 | 0/1 |
| `r250s300w` | 12 | 12 | 12 | 0 | 0 | 0 | 0/1 |
| `r80down300w` | 15 | 15 | 9 | 0 | 0 | 0 | 0/0 |
| `r80up300w` | 15 | 14 | 9 | 0 | 0 | 0 | 0/0 |
| `r80stall1000w` | 4 | 4 | 4 | 0 | 0 | 11 | 548/550 |
| `r80stall2000w` | 3 | 3 | 3 | 3 | 3 | 0 | 0/0 |

| condition | run | measured ack RTT p10/p50/p90/p99 (ms) | claims exact | corrections (abs) | correction size p50/max (blocks) | client PlayerPosition corrections | forced entries | timer refused | player frozen | max queued | knocks at ack | client hits VALID | pick = verdict | mirror box bit-equal | K2 view freezes | status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `r80j10` | marathon2 | 79/90/100/113 | 1202/1202 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 79/79 | 599/599 | 1240/1240 | 1240/1240 | 0/987 | ok |
| `r30s300w` | marathon2 | 27/41/51/68 | 896/1096 | 87 (32) | 0.257/0.830 | 87 | 220 | 0 | 0 | 0 | 77/79 | 555/599 (INVALID REWIND 31, UNKNOWN INEXACT_TARGET 12, INVALID PICKED 1) | 1157/1240 | 1229/1240 | 24/927 | ok |
| `r80s300w` | marathon2 | 78/90/101/126 | 246/277 | 405 (196) | 0.172/1.638 | 404 | 251 | 0 | 0 | 0 | 75/79 | 529/599 (INVALID REWIND 36, UNKNOWN INEXACT_TARGET 12, INVALID MISSED 19, INVALID PICKED 3) | 1098/1240 | 1216/1240 | 36/1125 | ok |
| `r150s300w` | marathon2 | 147/162/177/270 | 338/345 | 209 (101) | 0.671/2.556 | 208 | 258 | 0 | 0 | 0 | 77/79 | 470/599 (INVALID REWIND 48, UNKNOWN INEXACT_TARGET 13, INVALID MISSED 63, INVALID PICKED 5) | 975/1240 | 1204/1240 | 59/1138 | ok |
| `r80down300w` | marathon2 | 78/90/101/116 | 1202/1202 | 0 (0) | -/- | 0 | 42 | 0 | 0 | 0 | 79/79 | 558/599 (INVALID REWIND 27, UNKNOWN INEXACT_TARGET 14) | 1169/1240 | 1211/1240 | 74/1197 | ok |
| `r80up300w` | marathon2 | 78/90/100/115 | 36/38 | 505 (251) | 0.175/1.892 | 504 | 40 | 0 | 0 | 0 | 75/79 | 517/599 (INVALID REWIND 28, UNKNOWN INEXACT_TARGET 26, INVALID PICKED 9, INVALID MISSED 19) | 1099/1240 | 1225/1240 | 27/1093 | ok |
| `r80stall1000w` | marathon2 | 78/90/101/116 | 140/144 | 81 (38) | 0.616/2.589 | 80 | 239 | 0 | 0 | 11 | 71/75 | 65/589 (INVALID REWIND 512, UNKNOWN INEXACT_TARGET 12) | 183/1230 | 1165/1230 | 55/1114 | ok |

| condition | stalls | with forced entries | with corrections | with refusals | with freezes | max queued | window wait 2-4 s after a stall, p50/max (ms) |
|---|---|---|---|---|---|---|---|
| `r30s300w` | 12 | 12 | 4 | 0 | 0 | 0 | 0/0 |
| `r80s300w` | 12 | 12 | 10 | 0 | 0 | 0 | 0/0 |
| `r150s300w` | 12 | 12 | 9 | 0 | 0 | 0 | 0/0 |
| `r80down300w` | 15 | 15 | 0 | 0 | 0 | 0 | 0/1 |
| `r80up300w` | 15 | 15 | 15 | 0 | 0 | 0 | 0/0 |
| `r80stall1000w` | 4 | 4 | 4 | 0 | 0 | 11 | 554/555 |

### Hitches

| condition | run | measured ack RTT p10/p50/p90/p99 (ms) | claims exact | corrections (abs) | correction size p50/max (blocks) | client PlayerPosition corrections | forced entries | timer refused | player frozen | max queued | knocks at ack | client hits VALID | pick = verdict | mirror box bit-equal | K2 view freezes | status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `base` | hitch1 | 5/12/17/20 | 431/439 | 41 (18) | 0.272/2.067 | 41 | 6 | 4 | 1 | 23 | 35/38 | 204/307 (INVALID REWIND 98, UNKNOWN INEXACT_TARGET 3, INVALID MISSED 2) | 431/640 | 635/640 | 36/556 | ok |
| `r80j10` | hitch1 | 77/91/102/118 | 198/204 | 93 (44) | 0.398/2.413 | 91 | 14 | 4 | 1 | 23 | 33/37 | 159/282 (INVALID REWIND 92, UNKNOWN INEXACT_TARGET 13, INVALID MISSED 15, INVALID OUT_OF_REACH 2, INVALID PICKED 1) | 385/640 | 620/640 | 33/457 | ok |
| `r150j30` | hitch1 | 130/161/193/229 | 199/211 | 68 (30) | 0.681/3.275 | 67 | 19 | 3 | 1 | 22 | 34/38 | 166/298 (INVALID REWIND 90, UNKNOWN INEXACT_TARGET 14, INVALID MISSED 26, INVALID PICKED 2) | 369/640 | 616/640 | 33/423 | ok |

| condition | scenario | hitch | length (ms) | max queued | window wait 2-4 s after (ms, p50) | oldest ping (ms) | frozen ticks | refused | forced | corrections |
|---|---|---|---|---|---|---|---|---|---|---|
| `base` | `t_hitch_client` | client | 302 | 0 | 2 | 207 | 0 | 0 | 0 | 0 |
| `base` | `t_hitch_client` | client | 552 | 2 | 59 | 451 | 0 | 0 | 0 | 0 |
| `base` | `t_hitch_client` | client | 1053 | 2 | 68 | 992 | 0 | 0 | 1 | 0 |
| `base` | `t_hitch_server` | server | 342 | 2 | 350 | 8 | 0 | 0 | 1 | 24 |
| `base` | `t_hitch_server` | server | 754 | 7 | 352 | 15 | 0 | 0 | 2 | 9 |
| `base` | `t_hitch_server` | server | 1245 | 23 | 955 | 16 | 0 | 3 | 0 | 3 |
| `base` | `t_hitch_client_over` | client | 2054 | 20 | 19 | 1954 | 9 | 1 | 2 | 5 |
| `r80j10` | `t_hitch_client` | client | 303 | 0 | 1 | 304 | 0 | 0 | 1 | 0 |
| `r80j10` | `t_hitch_client` | client | 552 | 2 | 44 | 547 | 0 | 0 | 3 | 19 |
| `r80j10` | `t_hitch_client` | client | 1050 | 2 | 102 | 1051 | 0 | 0 | 3 | 16 |
| `r80j10` | `t_hitch_server` | server | 355 | 2 | 392 | 106 | 0 | 0 | 1 | 12 |
| `r80j10` | `t_hitch_server` | server | 755 | 8 | 359 | 104 | 0 | 0 | 2 | 7 |
| `r80j10` | `t_hitch_server` | server | 1210 | 23 | 996 | 105 | 0 | 3 | 0 | 3 |
| `r80j10` | `t_hitch_client_over` | client | 2053 | 20 | 51 | 2052 | 11 | 1 | 4 | 20 |
| `r150j30` | `t_hitch_client` | client | 303 | 0 | 1 | 351 | 0 | 0 | 1 | 0 |
| `r150j30` | `t_hitch_client` | client | 552 | 2 | 53 | 650 | 0 | 0 | 5 | 11 |
| `r150j30` | `t_hitch_client` | client | 1052 | 2 | 61 | 1151 | 0 | 0 | 5 | 12 |
| `r150j30` | `t_hitch_server` | server | 338 | 2 | 287 | 211 | 0 | 0 | 1 | 10 |
| `r150j30` | `t_hitch_server` | server | 713 | 7 | 307 | 200 | 0 | 0 | 2 | 7 |
| `r150j30` | `t_hitch_server` | server | 1216 | 22 | 969 | 203 | 0 | 2 | 0 | 2 |
| `r150j30` | `t_hitch_client_over` | client | 2051 | 20 | 33 | 2053 | 11 | 1 | 5 | 14 |

"window wait 2-4 s after" is the median wait of the player's windows 2 to 4 s after the hitch ended: the backlog the
hitch left behind ([F4](#f4-p0-the-tick-clock-never-drains-a-backlog)). It carries over from scenario to scenario: the
duel after the client hitches started 68 ms behind.

- Client hitches up to 1 s never freeze or refuse (the oldest ping peaks at 992 to 1,151 ms, under `max-unacked-ms`), and
  at localhost they cost no correction. At RTT 80 and 150 the 500 ms and 1 s hitches each force-apply the knock in flight
  and set off 11 to 19 corrections ([F3](#f3-p0-the-ack-deadline-counts-windows-the-client-sent-before-it-could-have-the-packet),
  [F5](#f5-p0-corrections-count-an-in-flight-knock-twice)).
- Server hitches never freeze anyone (pongs queue with everything else), but each one adds its length to the backlog and
  the hits judged after it come back `REWIND` (63 to 73 of 107 to 118 per run, [F6](#f6-p1-the-rewind-cap-charges-server-hitches-and-stalls-to-the-attacker)).
- The 2 s client hitch freezes the player as designed (9 to 11 frozen ticks, one refused window), then recovers through
  5 to 20 corrections instead of one.

### (b) Load bots as targets and attackers

The rig client sprint-strafes in the load-bot arena for 600 ticks against 12 bots (their own emulated link: 40 ms each
way, 10 ms jitter) that chase, hit, w-tap, block-hit, rod, pot and pearl. Everything holds: 0 corrections, every claim
exact, 768 of 768 client hits on moving, fighting bots VALID, 111 of 111 bot knocks applied at the ack. The view and pick
columns need `RigTarget` and are empty here; from the client's own per-tick view of the bots, 0 to 2 one-tick holds in
3,000 to 5,200 moving bot-ticks within 24 blocks per condition.

| condition | run | measured ack RTT p10/p50/p90/p99 (ms) | claims exact | corrections (abs) | correction size p50/max (blocks) | client PlayerPosition corrections | forced entries | timer refused | player frozen | max queued | knocks at ack | client hits VALID | pick = verdict | mirror box bit-equal | K2 view freezes | status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `r30j10-bots` | bots2 | 26/40/49/63 | 600/600 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 30/30 | 202/202 | 0/0 | 0/0 | 0/0 | ok |
| `r80j10-bots` | bots2 | 77/90/100/112 | 600/600 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 25/25 | 161/161 | 0/0 | 0/0 | 0/0 | ok |
| `r150j30-bots` | bots2 | 151/177/196/226 | 600/600 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 33/33 | 224/224 | 0/0 | 0/0 | 0/0 | ok |
| `r250j30-bots` | bots2 | 247/276/296/323 | 600/600 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 23/23 | 181/181 | 0/0 | 0/0 | 0/0 | ok |

### K1 against K2

`replication.path-step-ticks: 1` (`torture/runs/k1`, combat attack and view scenarios only; the "K2 view freezes"
column counts K1 paths here). Compare with the same conditions in the steady tables: the view is 55 to 68 ms shorter at
RTT 0, 80 and 150 (27 ms at RTT 30, where the client's tick phase happened to line up worse), with no freezes up to 10 ms
of jitter and 11 frozen ticks of 796 at 30 ms; `MirrorBook` stays bit-exact (1,000 of 1,000 boxes per condition) and every
hit is VALID. The `r80s300` row is F2-F6 again, not K1.

| condition | run | measured ack RTT p10/p50/p90/p99 (ms) | claims exact | corrections (abs) | correction size p50/max (blocks) | client PlayerPosition corrections | forced entries | timer refused | player frozen | max queued | knocks at ack | client hits VALID | pick = verdict | mirror box bit-equal | K2 view freezes | status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `base` | k1 | 4/8/16/18 | 591/591 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 19/19 | 212/212 | 1000/1000 | 1000/1000 | 0/991 | ok |
| `r30j0` | k1 | 37/45/50/52 | 591/591 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 19/19 | 251/251 | 1000/1000 | 1000/1000 | 0/936 | ok |
| `r80j10` | k1 | 76/89/100/113 | 591/591 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 19/19 | 264/264 | 1000/1000 | 1000/1000 | 0/984 | ok |
| `r150j30` | k1 | 129/164/192/213 | 591/591 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 19/19 | 243/243 | 1000/1000 | 1000/1000 | 11/796 | ok |
| `r80s300` | k1 | 77/89/100/351 | 422/510 | 34 (11) | 0.329/1.303 | 34 | 65 | 0 | 0 | 0 | 16/18 | 276/305 (INVALID REWIND 19, UNKNOWN INEXACT_TARGET 8, INVALID MISSED 1, INVALID PICKED 1) | 940/1000 | 985/1000 | 25/924 | ok |

| condition | TickEnd upstream p50/p99 (ms) | window wait p50/p99/max (ms) | rewind age p50/max (ms) | rewind cap p50 (ms) | cap - age min/p10 (ms) | hit distance server vs client max | view (target tick -> shown) p10/p50/p90 (ms) | input -> viewer p50/p90 (ms) |
|---|---|---|---|---|---|---|---|---|
| `base` | 0.2/0.4 | 0.3/1.4/9.5 | 84/94 | 216 | 121/125 | 0 (1 ulp) | 77/84/91 | 84/92 |
| `r30j0` | 16.0/16.7 | 0.3/1.3/1.7 | 98/137 | 249 | 112/148 | 0 (1 ulp) | 122/131/134 | 148/151 |
| `r80j10` | 46.1/60.1 | 0.2/1.0/5.2 | 169/187 | 309 | 117/128 | 0 (1 ulp) | 113/122/129 | 168/183 |
| `r150j30` | 91.3/133.3 | 0.2/1.2/23.3 | 259/332 | 412 | 60/125 | 0 (1 ulp) | 162/173/212 | 265/326 |
| `r80s300` | 47.1/269.5 | 0.3/1.8/2.9 | 182/454 | 309 | -145/114 | 0.9 | 130/138/145 | 186/201 |

### Tuning experiments

`ack-jitter-ticks: 10`, and with `rewind-slack-ms: 350` (`tune-ack10`, `tune-ack10-slack350`, combat set):

| condition | run | measured ack RTT p10/p50/p90/p99 (ms) | claims exact | corrections (abs) | correction size p50/max (blocks) | client PlayerPosition corrections | forced entries | timer refused | player frozen | max queued | knocks at ack | client hits VALID | pick = verdict | mirror box bit-equal | K2 view freezes | status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `r80s300` | tune-ack10 | 77/90/99/134 | 1171/1171 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 47/47 | 265/281 (INVALID REWIND 16) | 1528/1570 | 1570/1570 | 16/1102 | ok |
| `r150s300` | tune-ack10 | 147/162/177/385 | 1170/1170 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 47/47 | 277/300 (INVALID REWIND 23) | 1597/1645 | 1645/1645 | 32/1233 | ok |
| `r80s300` | tune-ack10-slack350 | 77/90/101/366 | 1168/1168 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 47/47 | 248/248 | 1570/1570 | 1570/1570 | 19/1177 | ok |
| `r150s300` | tune-ack10-slack350 | 147/163/177/398 | 1171/1171 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 47/47 | 308/308 | 1645/1645 | 1645/1645 | 22/1257 | ok |

Past the RTT ceiling (`ceiling`, combat set): at 300 ms every hit still lands (the 150 ms slack covers 50 ms past
the 250 ms ceiling); at 400 ms 261 of 309 come back `REWIND`, as designed: past the ceiling the shooter has to lead.
Movement, knockback and mirrors stay exact at both.

| condition | run | measured ack RTT p10/p50/p90/p99 (ms) | claims exact | corrections (abs) | correction size p50/max (blocks) | client PlayerPosition corrections | forced entries | timer refused | player frozen | max queued | knocks at ack | client hits VALID | pick = verdict | mirror box bit-equal | K2 view freezes | status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `r300j10` | ceiling | 299/312/326/339 | 1167/1167 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 47/47 | 309/309 | 1570/1570 | 1570/1570 | 0/1004 | ok |
| `r400j10` | ceiling | 397/412/427/438 | 1165/1165 | 0 (0) | -/- | 0 | 0 | 0 | 0 | 0 | 47/47 | 48/309 (INVALID REWIND 261) | 1076/1645 | 1645/1645 | 0/1092 | ok |

| condition | TickEnd upstream p50/p99 (ms) | window wait p50/p99/max (ms) | rewind age p50/max (ms) | rewind cap p50 (ms) | cap - age min/p10 (ms) | hit distance server vs client max | view (target tick -> shown) p10/p50/p90 (ms) | input -> viewer p50/p90 (ms) |
|---|---|---|---|---|---|---|---|---|
| `r300j10` | 155.2/169.9 | 0.4/2.5/24.0 | 452/474 | 529 | 56/67 | 0 (1 ulp) | 289/314/323 | 469/487 |
| `r400j10` | 205.2/220.4 | 0.3/1.8/3.3 | 551/580 | 535 | -45/-28 | 0 (1 ulp) | 338/363/373 | 568/586 |

## Fix status (netcode, 2026-10-22, tree of 05:36)

Re-run with the default `ack-jitter-ticks` (4) and `rewind-slack-ms` (150) on `torture/jars/revivalpaper-stallfix-0555.jar`
(marathon, `runs/stallfix3-marathon`) and `-0613.jar` (hitch, `runs/stallfix4-hitch`: the clock also counts a hitch
still running); unit 465/465 and both private live runs green on the same tree. Before → after:

| Condition (marathon, a 1-minute duel) | Corrections | Exact claims | Forced | Hits VALID | Backlog after a stall |
|---|---|---|---|---|---|
| r30s300w | 17 → 0 | 97.0 → 100 % | 78 → 0 | 63/68 → 59/59 | 0 → 0 |
| r80s300w | 71 → 0 | 82.8 → 100 % | 94 → 0 | 95/104 → 113/113 | 0 → 0 |
| r150s300w | 215 → 0 | 72.9 → 100 % | 50 → 0 | 65/76 → 47/47 | 0 → 0 |
| r80down300w | 89 → 0 | 76.4 → 100 % | 32 → 22 | 89/98 → 73/74 | 0 → 0 |
| r80up300w | 103 → 0 | 70.0 → 100 % | 23 → 0 | 70/77 → 71/71 | 0 → 0 |
| r80stall1000w | 81 → 0 | 97.1 → 100 % | 91 → 20 | 61/431 → 176/184 | 11 windows, 548 ms → 2 windows, 50 ms (upstream + queue p50 592 → 93 ms) |

No kick anywhere. Hitch set (`t_hitch_client` 250/500/1000 ms, `t_hitch_server` 300/700/1200 ms, `t_hitch_client_over`
2 s): max queued 22-23 → 1, forced 6-19 → 0, upstream + queue p50 67-149 → 1-95 ms, `t_hitch_client` and
`t_hitch_server` 0 corrections and every hit VALID (the server hitches had 25 `INVALID REWIND` per condition on the
first clock, 63-73 % `REWIND` before). Not at target: `t_hitch_client_over` (a 2 s client hitch, past
`max-unacked-ms`, so a freeze by design) still takes 19-27 corrections after the freeze (3-27 across two runs; 5-37 before): a
claim stays ~1e-4 off the truth for many ticks after it, corrected again and again; next to look at.

K per viewer (`runs/stallfix7-k1`, combat set, `-0649.jar`): 0 corrections, every claim exact, every hit VALID
(255/268/302/243), every pick and box equal, in base, r30j0, r80j10 and r150j30; the viewer moved to K1 once in base and
r30j0 (own jitter 14-17 ms) and stayed K2 at r80j10 and r150j30, no flapping. Its view latency on this shared machine
(load 3-9) varied run to run by more than K1's 50 ms (r30j0 p50 116-186 ms over three runs), so the K1 gain is shown by
the forced-K1 run above (`k1`) and the live K scenarios (half a tick on localhost), not by these numbers.

| | What changed |
|---|---|
| F1 | Fixed before this run (PR-8): a teleport into chunks the client lacks is held until they are sent. |
| F2 | A force-applied server teleport stays pending for the client: `Transactions` keeps forced teleports, corrections and knocks until their ping's real pong and hands them back then (`LANDED`); the teleport lands there (the truth takes it again, its accept is awaited and matched), never "accept for a server teleport never sent". Live: cheat suite "stall: 1.0 s with a server teleport sent into it". |
| F3 | Deadlines count paced windows (`ClientStream`: a window within `catch-up-gap-ms` of the one before with no pong is a catch-up tick, at most `max-banked-ticks` in a row), capped at the deadline plus `max-banked-ticks` windows of any kind; pings are stamped with the window the client is in by real time (never lower than the ping before). |
| F4 | `ArrivalStamps` stamps each `TickEnd` off the wire; the tick clock's bank caps idle time before a window's arrival (a hitch is credited), and the time the bank forfeits is owed back to the catch-up windows right behind it (at most one bank, within one bank's time), so a stall's burst drains at once. `timer-queue-cap-ms` 400 (config v5). |
| F5 | No correction while forced entries are outstanding (the client's link is stalled: when one would land, and on what, is unknown); a forced knock that lands makes the next correction one absolute resync. Corrections forecast the client with every knock still in flight (logged-unacked); an absolute correction is held while any is. |
| F6 | Rewind ages are on `Authority.clockMillis()`: wall time less how far the server is behind its tick schedule, counting a tick still running past its slot (stamps and the judge's "now" alike). A hitch is off the clock while it runs and until the catch-up ticks run, so an attack it held is judged a tick after its box; per-tick jitter never accumulates (a first version summing positive overruns drifted 9 s in 10 min: every hit past the cap, judged on the truth). |
| F7 | Fixed before this run: `rttBase` follows a worse link in ~1.5 s (the last 32 acks). |
| K | K per viewer: a viewer's own ack jitter under `replication.low-jitter-ms` (25) for `step-switch-seconds` (5) gets `low-jitter-step-ticks` (1), above `high-jitter-ms` (35) for 5 s K2 again; a switch re-pairs the players fed to it (without the 5 s, a join's or a teleport's ack burst flapped K2/K1 two seconds apart). K1 only on a full 256-ack jitter window: a session's first acks under-read a jittery link (a 22 ms reading at RTT 150 jitter 30 put a viewer on K1, and its hits were judged past a cap sized from that reading). Live: "K per viewer" scenarios (K1 0.5 tick behind K2 on localhost, the mirror exact, no freeze). |
| Load P0 | Once every forced entry has landed: one absolute resync if the client is off, and the viewer's mirrors re-paired then (periodic mirror and solid re-pairs wait while forced entries are outstanding). Activation seeds the sticky keys from `getLastClientInput()` and a grounded player's resting vy. |

## NAS block-glitch fix (netcode, 2026-10-22, after the merge)

From a real Lunar playtest on the NAS (RTT 31 ms): after breaking protected glass panes and honey under and next to
itself, the player was corrected every 2 ticks for seconds wherever it ran, alternating absolute and relative, each
accept a tick of motion off; only standing still stopped it. Reproduced here with the vanilla client at **RTT 60**
(`r60j0`, new condition, with `r110j0`): the 2 s freeze's aftermath took 54 corrections at 60 ms and 37 at 110 ms
(`runs/glitch-base60`), alternating every 2 ticks exactly as on the NAS.

- **Corrections forecast to the wrong tick.** `n̂ = round(rttBase / 50)` is one tick short whenever the trip to the
  client's next tick rounds down: RTT 50-74, 100-124 ..., or 30 ms behind a busy server tick (the NAS). Every accept was
  one tick of motion off, the backstop (not rate-limited) chased it every other tick, and its absolute fallback used the
  same forecast. Fix: `LandingLag` measures where pings land (pong window minus the window the client was in at the
  flush, per flush kind, median of the last 16 within `landing-sample-ms`, none from a stall, a client freeze or a
  catch-up), a correction is forecast `(window the client is in − window being run) + trip`, a correction waits while
  windows are queued behind the one running (at most `max-banked-ticks`), the backstop counts against twice
  `max-corrections-per-second`, and a within-threshold claim re-bases the belief on the truth (a belief left to its own
  velocity drifted and carried a phantom jump into the next forecast).
- **The honey.** A break the server denies stays a hole on the client until its ack; holding attack re-breaks it every
  tick it is back, so the client sinks a little and vanilla's restore snaps it up again. Our correction cancelled that
  snap (`onTeleport`), left the client inside the restored block, and it fell through. Fix: `WorldView` holes (the
  belief and forecast read them, the truth never does), claims the belief explains in a hole are held, the restore's
  snap is modelled (and the revert's block position, read after a fast iterator's `remove()`, fixed). Live: 40 re-breaks,
  0 corrections.
- **Sub-µm gaps** never count toward `correction-persist-ticks` (`correction-persist-min` 1e-6): no teleport every 20
  ticks for a rounding gap. Such a gap now stays until a collision or a real correction removes it, so `exact%` in the
  hitch set reads 64-99 % where every claim is within a µm.

| `t_hitch_client_over` (2 s client freeze) | before (`glitch-base60`, `merge-hitch`) | after (`glitch-fix7-hitch`) |
|---|---|---|
| r30j0 | 4 | 4 |
| r60j0 | 54 | 4 |
| r110j0 | 37 | 4 |
| r80j10 | 13 | 10 |
| r150j30 | 20 | 16 |

`t_hitch_client` (0.3/0.55/1.05 s) and `t_hitch_server` (0.36/0.76/1.26 s): 0 corrections before and after. Marathon
(`glitch-fix7-marathon`): 0 corrections and 100 % exact in all seven conditions, as before.

## Failures: root cause, fix, owner

Ordered by what a player feels. The owners: **netcode** (`auth/`, `net/`, `view/`, patches), **combat** (`CombatWiring`,
`combat/`), **sim** (the port).

### F1 (P0) A far teleport drops the player through the floor into the void

Every condition, localhost included: `t_far_tp` (224 blocks, into a chunk the client had forgotten) ended with the client
at y ≈ −900 and still falling, 0 corrections. The same happened at join in the first smoke run (a client saved at its
last position, then teleported 200 blocks) and to every freshly spawned `RigTarget` that was mid-jump when it arrived
(the five `t_duel_long` runs of `grid1`).

What happens (`grid1/base`, probe and `t_far_tp.settle.jsonl`): the teleport lands; the chunk follows a tick later; in
between the client reads void air and falls 0.078, so when the chunk arrives its box is inside the floor and vanilla
collision lets it through (a box already inside a shape doesn't collide with it). The truth does exactly the same, bit
for bit (`claim exact true` all the way down), because the world view answers void air for a chunk the client lacks
(`auth/view/WorldView.java:116-125`) and the `chunk-unknown` exemption covers only a client that claims ground
(`auth/AuthPlayer.java:1721-1727`: "A client that really lacks the chunk (a far teleport) falls with the truth"). Nothing
checks the truth against the server's world when the chunk lands (`WorldView.onChunk`, `auth/view/WorldView.java:382`).
Vanilla Paper rescues this client: with the authority off (`vanilla-fartp`, the same two far teleports at RTT 0 and 150)
the client lands at y 65.0 every time; at localhost Paper's movement check sends one setback teleport right after it
sinks in. The authority took
that check over (patch 1012) and now follows the client into the void. The 22:21 jar corrected this case once (README-enforce
root cause 1); the 01:10 join fix ("falls with the truth") turned it into a fall.

Fix (netcode): (1) send the destination chunk before the teleport: in `onTeleportSent` (`auth/AuthPlayer.java:1012`), if
the chunk under the destination is not in the client's chunk record, have Moonrise send it first (or hold the teleport
packet until it is sent) — no fall at all; (2) backstop: while the truth stands over a chunk the client lacks, hold it
in place (exempt, no gravity, the way the vanilla client holds the player on its loading screen at login); when the
chunk's ack lands, if the client's claim is below or inside a collision shape the truth's view now has, send one absolute
correction to the held position. One correction per far teleport at worst, never a fall.

### F2 (P0) A stalled server teleport kicks the player

7 of 8 stall conditions: `accept for a server teleport never sent` → `Invalid transaction replies` (`stall1`
`console.log` 02:45:18, 02:59:38, 03:00:29, 03:03:17, 03:04:04, 03:04:38, 03:06:57). An upstream-only 300 ms stall is
enough (`r80up300`).

Sequence (`r80s300`, 9 s after join, the first scenario's `/tp` 24 blocks): the stall holds the teleport's pong; the
deadline passes and the teleport entry is force-applied (`auth/AuthPlayer.java:1227-1236`). Applying it completes the
server teleport and resets the reconciler, but a forced entry (`exact = false`) is not put on `awaitingAccepts`
(`auth/AuthPlayer.java:2352-2356`, `else if (exact)`), so `teleportsPending` drops to 0 while the client hasn't even
received the teleport. The next claims (the client still at the old spot) are compared with the teleported truth and
corrected by the teleport's own Δ (`correction AuthTorture tick 164 dpos (-16.000000, 0.000000, -18.000000)`); the client
then applies the teleport *and* the relative correction, ends up 24 blocks off, is corrected back, and finally sends the
teleport's honest accept. That accept matches nothing, is parked in `earlyAccepts` (`auth/AuthPlayer.java:2301-2308`)
and 2 × deadline windows later `checkAwaitingAccepts` calls it forged and kicks (`auth/AuthPlayer.java:985-988`).

Fix (netcode): a force-applied server teleport stays a pending teleport for the client: keep `teleportsPending` up and
put its id on `awaitingAccepts` (forced or not) until its real pong/accept arrives; no corrections while it is out; a
late accept for a teleport the server completed is matched, never a protocol violation. With F3 fixed it is rarely
forced at all.

### F3 (P0) The ack deadline counts windows the client sent before it could have the packet

Every stall and every hitch force-applies whatever is in flight: 187 forced entries in 37 of 41 stalls at RTT 30, and in
every stall of every other condition; after server hitches too (the client's windows queued during the hitch arrive as a
burst). Forced path steps make the viewer's mirrors inexact (73 of 2,200 mirror boxes differ in `r30s300`, 255 pick
disagreements in `hitch1/r80j10`, `UNKNOWN INEXACT_TARGET` verdicts); forced knocks are F5; a forced teleport is F2.

Cause: the deadline is `ceil(min(rttBase, 250) / 50) + ack-jitter-ticks` *windows* (`auth/AuthPlayer.java:1228`,
`auth/net/Transactions.java:704-711`), stamped with the last `TickEnd` the server had seen (`Transactions.Context.windowTick`,
`auth/net/Transactions.java:72`). After a both-way stall the client's catch-up windows, all sent before it received
anything, arrive in one burst and push the count past the deadline before the pongs can come back (they need one more
RTT). In an upstream stall the pings sent meanwhile are stamped with the stale window, so the burst counts against them
too. A steady link never comes close (0 forced in 16 conditions up to 30 ms jitter), so the jitter allowance is fine; the
count is what's wrong.

Fix (netcode): advance deadlines only on *paced* windows: a window whose `TickEnd` closes less than ~15 ms after the
previous one and whose drain carries no pong is a catch-up tick and doesn't count; or stamp pings with the client tick
the server *expects* the client to be in (from the tick clock), not the last one seen. Anti-KB is unchanged: a client
withholding pongs while ticking at a normal pace still hits the deadline at the same window.

### F4 (P0) The tick clock never drains a backlog

After a server hitch the player's windows run late for good: 350 ms behind after a 300 ms hitch, 955 ms after 1.2 s
(`hitch1`, median wait 2-4 s later; the queue holds 19 to 23 windows with the bank at 0.1 to 1.2 ticks for the rest of
the scenario). Client hitches of 500 ms and 1 s leave 50 to 100 ms; the backlog carries over between scenarios and every
new hitch adds to it (`drill_queue.py`, `hitch1/base`: `t_hitch_server` starts 68 ms behind, ends 955 ms behind). Network
stalls do it too: one 1 s stall left 11 windows queued and the player 545 ms behind (median) for the rest of a one-minute
duel (`marathon/r80stall1000w`). While behind, the truth is behind: every hit of that player is judged up to a second
after its click and comes back `REWIND` (63 to 73 of 107 to 118 hits in `t_hitch_server`, 365 of 431 in the marathon),
viewers see it late, and knocks land late.

Cause: `TickClock.tryRun` (`auth/net/TickClock.java:46-65`) caps the bank at `max-banked-ticks` (floor `now − 10 ×
period`) and binds it to the ack clock (floor `ackClock − timer-drift-ms`): unused client time beyond that is forfeited.
When more windows than that are waiting, the clock forfeits the client's real time, runs one window per period, and
since the client keeps sending one per period the queue never shrinks (it is refused only once a window has waited 1 s,
`timer-queue-cap-ms`, so the backlog settles just under a second). For a server hitch the windows even arrived on time;
they waited in the server's own task queue, and the floor is taken at run time (`now`), not at arrival. Catch-up windows
inherit the previous frame's ack clock (`auth/net/ClientStream.java:443-450`), so pongs never vouch for them.

Fix (netcode): time spent with windows already queued is owed to the client, not idle time: apply the `max-banked-ticks`
floor only to time the queue was empty, from the window's arrival (Netty read time), and let a window run whenever its
own ack clock covers it (the client provably reached `ackClock − drift`: Grim's transaction timer, without a wall-clock
ceiling for windows that are already waiting). A speed cheat still can't get ahead of its own acks; a lag-switch blink
stays bounded by `max-unacked-ms`.

### F5 (P0) Corrections count an in-flight knock twice

A knock force-applied 2 to 5 ticks before the client applies it (F3) sets off a correction chain: `r150s300`
`t_duel_long` took 40 corrections (16 absolute, up to 1.94 blocks) in one 300-tick duel; the hitch scenarios up to 37
each, up to 3.3 blocks. Probe trace (`r150s300`, T0+97 to +124): `setVelocity` forced at T+97; correction at T+98 with
Δ = (0.12, 0.94, −1.09); the client drains the knock at t99 (TCP order: the knock went out before the correction) and the
correction at t102 on top of it: double knock; T+102's correction is Δ = (0.10, −1.03, 0.40), undoing it, and so on,
while the target keeps hitting. Hits during the loop fail (`INVALID MISSED`/`OUT_OF_REACH`: the attacker's eye is off the
truth by more than `attack-eye-offset-cap`).

With knocks coming faster than a chain settles, it never ends. In the marathon duel (a knock every 15 ticks, no
teleports) the first stall's forced knock started a chain at t233 that re-armed on every later knock for the rest of the
minute: corrections of 1.6, 1.2, 0.8, 0.8, 0.2, 1.1, 0.0, 1.5, … blocks every 3 to 4 ticks. `marathon/r150s300w` took 215
corrections (up to 3.5 blocks) and `r250s300w` 176, where 143 of the client's hits came back `INVALID MISSED` and only
20 % of claims were exact. An HCF fight lands a knock every half second, so one hiccup at RTT ≥ 150 means rubber-banding
until the fight ends.

Cause: `PortSimulation.correction` forecasts the client from `belief` (its acked state) for `n̂` ticks
(`auth/PortSimulation.java:391-425`, called from `resolveCorrection`, `auth/AuthPlayer.java:2038`). Entries already sent
but not acked (the knock) reach the client before the correction, but the forecast leaves them out, so Δ contains them
again.

Fix (netcode/sim): forecast the client with every physics entry sent before the correction applied at its expected
arrival (n̂), or send no correction while a force-applied entry's real ack is outstanding and re-base the belief at that
ack (then the remaining error is the true timing error, one correction at most).

### F6 (P1) The rewind cap charges server hitches and stalls to the attacker

- Server hitches: every hit after one comes back `REWIND` (F4 makes it last; the hitch alone adds its length to every
  rewind age judged after it). The age is `System.currentTimeMillis()` at the judge minus the shown step's stamp
  (`auth/CombatWiring.java:189-191`, `auth/combat/HitValidator.java:60-63`, stamps from `auth/PathWiring.java:104`), so
  time the server spent frozen counts against the client.
- Stalls: hits made during or right after a stall are `REWIND` (5 to 13 % of hits in the stall conditions). During a
  both-way stall the client really looks at a stale box (rewind 346 to 554 ms against caps of 209 to 436 ms); the cap's
  `display` term is 0 exactly then (`displayDelay`, `auth/CombatWiring.java:149-154`: newest acked = shown when nothing new
  arrives), and the jitter term (p90 − p10 of 256 acks) doesn't see stalls that are a few % of the samples.
- Upstream-only stalls: the attack packet is held, the box it was aimed at was fresh, and the age still grows by the
  stall: pure transit time, no backtrack gained.

Fix (combat, with netcode for the clock): measure the age without server pauses (subtract tick overruns between the
stamp and the judge, or stamp in server ticks); for the stall cases this is a policy choice, see the recommendations.

### F7 (P1) The RTT base takes ~5-10 s to follow a worse link

`step30to250`: after the link went from 30 to 250 ms, the next scenario (5 s) had 28 forced entries and 3 of 43 hits
VALID (9 `REWIND`, 31 `UNKNOWN INEXACT_TARGET`); the one after was clean (22 of 22). `rttBase` is the 10th percentile of
the last 256 acks, recomputed every 32 (`auth/net/Transactions.java:743-756`): it needs ~230 new samples (9 s at ~25 acks/s)
to move up. Deadlines and the rewind cap are sized from it. Fix (netcode): follow increases fast (e.g. the max of the
long-window p10 and the min of the last 16 samples), decreases slowly; a client can't fake a higher minimum without
really delaying its pongs (and its own view).

### F8 (P2) Harness and lesser notes

- `RigTarget` respawns logged in at (0, 0, 0) and were teleported 200 blocks: F1's fall for a joining player who is
  airborne (the exemption needs a ground claim). Real players joining into a plugin teleport hit the same path.
- Stalls freeze remote players on screen for the stall (28 to 88 K2 freeze ticks per stall condition): no data, nothing to
  show; vanilla has no extrapolation. Not a bug.
- `r80stall2000` (above `max-unacked-ms`): every 2 s stall froze the player (by design) and refused ~29 windows; recovery
  then cost up to 6.6-block corrections (F3 + F5) before the kick (F2).

## Tuning recommendations

The bar is Overwatch 1: honest players never corrected, every hit they see lands, remote players smooth and as fresh as
the tick rate allows, and a lag spike costs the moment it happens, not the next minute. Numbers are from the tables above.

| Knob | Now | Measured | Recommendation |
|---|---|---|---|
| Ack deadline: `ceil(min(rttBase, 250) / 50) + ack-jitter-ticks` windows | `ack-jitter-ticks: 4` | steady links up to 30 ms jitter (RTT p99 310 ms): 0 forced. Any stall or hitch: forced entries in nearly every one, which feeds F2, F5 and inexact mirrors. With 10: 0 forced, 0 corrections, 0 kicks in `r80s300`/`r150s300` (`tune-ack10`) | Fix the count (F3: catch-up windows don't advance deadlines), then keep **4**: 200 ms over the RTT is plenty for jitter and keeps the anti-KB window small. Until F3 lands, **8 to 10** stops the stall kicks and correction loops at the price of 200 to 300 ms more anti-KB delay for a pong-withholding cheater (the truth still applies the knock at the deadline) |
| Rewind cap: `min(rttBase, rtt-ceiling-ms) + display + (p90 − p10) × rewind-jitter-factor + rewind-slack-ms` | 250 / 1.0 / 150, sanity 600 | steady links: closest hit 61 ms under the cap (p10 margin 91 to 146 ms per condition) at RTT 0 to 250 and jitter 0 to 30: well sized, keep the slack at 150. Stalls: 5 to 13 % `REWIND`. Server hitches: 63 to 73 % `REWIND` | (1) measure the age without server pauses (F6), not negotiable: a server lag spike is never the attacker's fault. (2) keep the formula for steady links. (3) stalls are a policy call: a bounded **starvation allowance** = min(300 ms, the gap the attacker's own acks show right now) honours hits during a real Wi-Fi hiccup and gives a lag-switcher at most 300 ms, only while its own link is visibly broken (its movement is queued or refused meanwhile); `rewind-slack-ms: 350` instead buys the same for everyone, always: with `ack-jitter-ticks: 10` it made both 300 ms stall runs perfect (0 corrections, 0 forced, 248/248 and 308/308 hits VALID, every pick and box equal; `tune-ack10-slack350`), at +200 ms of backtrack for everyone |
| `rtt-ceiling-ms` | 250 | at 300 and 400 ms RTT (`ceiling`) | see the ceiling table: past the ceiling the shooter leads; this is the designed limit |
| `rttBase` estimator | p10 of 256 acks | 5 to 10 s to follow a 30 → 250 ms step, with 3 of 43 hits VALID meanwhile | follow increases within ~1 s (F7) |
| K (replication `path-step-ticks`) | 2 | K1 cuts the view by 55 to 68 ms (base 143 → 84, RTT 80/j10 190 → 122, RTT 150/j30 228 → 173), 0 freezes up to 10 ms jitter, 11 of 796 moving ticks frozen at 30 ms jitter; mirrors bit-exact either way (1,000 of 1,000 boxes) | **K per viewer**: K1 while the viewer's own jitter (`rttHigh − rttBase`) is under ~25 ms, K2 above ~35 ms (hysteresis, re-pair on switch). Most HCF players on wired or good Wi-Fi get one tick (50 ms) back on every remote player |
| Tick clock: `max-banked-ticks` 10, `timer-drift-ms` 120, `timer-ack-settle-ms` 100, `timer-queue-cap-ms` 1000 | | 0 refusals and ≤ 2.2 ms window waits on every steady link; client hitches up to 1 s caught up without refusals; but any backlog becomes permanent (F4) | Fix F4 (credit for time windows spent queued, ack-clock-bounded catch-up). Then the bank of 10 (500 ms) is right for bursts after stalls, and `timer-queue-cap-ms` can drop to ~400: a window that waited that long is a cheat or a broken link, not a backlog |
| `max-unacked-ms` | 1500 | a 1 s client hitch peaked at 992 / 1,051 / 1,151 ms oldest ping at RTT 0 / 80 / 150: no freeze; 2 s froze as designed | keep 1500 (covers a 1 s hitch up to RTT ~250 + jitter); after F3/F5 the recovery from a real freeze should be one correction, not 5 to 37 |
| Corrections: `correction-threshold` 1e-3, budget 0.01/0.95, `max-corrections-per-second` 5, `correction-repeat-limit` 2 | | 0 corrections in 35,165 steady claims, so no false positives to tune away. Every correction seen comes from F1-F5 | keep; after F5, one correction per real divergence. The rate limit spaces the F5 loop 2 to 4 ticks apart; it is not the cause |

Where the latency budget goes (input to what another player sees, two players on the same link):

| | RTT 0 | RTT 80 | RTT 150 | RTT 250 |
|---|---|---|---|---|
| K2 (now) | 144 ms | 213 to 248 | 304 to 319 | 408 to 422 |
| K1 (`k1` run) | 84 | 168 | 265 | not run |
| of which network | 0 | ~80 | ~150 | ~250 |
| of which the server's window wait | 0.2 | 0.2 | 0.2 | 0.3 |

The authority itself adds nothing measurable (the window runs within 1 ms of the `TickEnd`); the rest is the network,
the K-tick path and the client's own tick quantization (up to 50 ms waiting for the next client tick, plus the pick at
its start). At 20 Hz, K1 is the floor short of client-side extrapolation, which vanilla doesn't do.

Priority order for the netcode owner: F2 and F1 (kicks and void falls: players lose fights and gear), F4 (one lag spike
breaks a player for the session), F3 + F5 (stall → rubber band), then F6/F7 and K per viewer.

## Reproduce

```bash
cd ~/Developer/tools/auth-rig
torture/build-jar.sh                                   # frozen APFS clone -> torture/jars/revivalpaper-torture-<stamp>.jar
python3 -I torture/proxy/selftest.py                   # the proxy against an echo server (ports 25650/25651)
python3 -I torture/torture.py --list                   # conditions
python3 -I torture/torture.py --boot-only --jar torture/jars/revivalpaper-torture-<stamp>.jar
python3 -I torture/torture.py --out torture/runs/grid --conditions base,r30j0,...,r250j30 --debug-log
python3 -I torture/torture.py --out torture/runs/hitch --conditions base,r80j10 --set hitch
python3 -I torture/torture.py --out torture/runs/marathon --conditions r80s300w,r80stall1000w --set marathon
python3 -I torture/torture.py --out torture/runs/bots --conditions none --bots 12 --bot-conditions r80j10
python3 -I torture/torture.py --out torture/runs/k1 --conditions r80j10 --set combat --revival replication.path-step-ticks=1
python3 -I torture/analyze.py torture/runs/NAME --json torture/runs/NAME/results.json   # tables + failures
python3 -I torture/analyze.py torture/runs/NAME --cond r150s300 --scenario t_duel_long --ticks
python3 -I torture/build-doc.py                        # this file from torture/doc-template.md
```

Ports: server 127.0.0.1:25600, proxy 25601. `torture.py` refuses to start when either is taken, starts one server, one
proxy and one client per condition, stops only the pids it started, and stops a client that was kicked.
`--revival key=value` (dotted for nested keys) overrides `revival.yml` for one run; every run starts from the defaults
(`auth-torture/revival.default.yml`, saved the first time a jar wrote `revival.yml`). `--revival` can only set a key that
file holds: after a jar adds keys, move it aside so the next `--jar` boot saves a new one.

## Limits

- One real client. The second player is in process (no network of its own); the load bots run their own emulated link
  (40 ms each way, 10 ms jitter) and are not real clients.
- The proxy is TCP-level: loss shows up only as what it does to TCP (a stall), which is what the stall conditions model.
- Stall phases are fixed (every 4/5/15/20 s from the profile switch), so which teleport or knock a stall lands on is
  chance; the marathon runs remove the teleports.
- The view latency is measured at the client's pick (start of a client tick), not at the rendered frame, which
  interpolates another half tick on average.
- The far-teleport and FakeClient findings are shown with the gym's void world; a world with ground under the arena
  would stop the fall at the next floor, not prevent it.
