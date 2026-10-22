# 1.7 PvP issues: the checklist

Status: 2026-10-21, branch `revival/authoritative` (working tree after commit `a7a84a06`). This is a list of every
problem 1.7 PvP is known to have, checked against the authoritative RevivalPaper server. It draws on the community's
own record (sources at the end) and on this repo's design, code, tests and real-client runs.

The goal it measures against: a 1.7-PvP server for vanilla 26.3 clients that is fast and exact, and that removes as
many of 1.7's problems as a server can.

Read with: `authoritative.md` (design, §15 binding), `auth-netcode.md`, `auth-combat.md`, `auth-sim.md`,
`auth-cheat-suite.md`, `neue-combat.md` (specced, not built), `README.md` (combat mechanics and `revival.yml`).
Code paths are under `leaf-server/src/main/java/gg/hcfrevival/revival/` unless they say otherwise.

## How to read an item

Each item has:

- **Players**: what players experience.
- **Cause**: the root cause and its layer (client, protocol, server, hardware).
- **Today**: what RevivalPaper does now, with the mechanism and the file.
- **Fix**: what would fully solve it, as a concrete design, with an owner.
- **Proof**: the measurable test that shows it is solved. It says whether the test exists or still has to be written.

Status:

- **Solved**: the mechanism is built and a test that exists proves it.
- **Partial**: some of it is built, or it is built and the proof is missing, or it only works in some conditions.
- **Open**: nothing addresses it yet.
- **[limit]**: a vanilla 26.3 client makes a full fix impossible. §12 says what a light client mod would change.

Owners: **netcode** (`auth`, `auth.net`, `auth.view`, `auth.combat`, `auth.replicate`), **combat**
(`combat`, the knockback and damage rules), **sim** (`auth.sim`, the movement port), **config** (defaults and
policy in `revival.yml` and server configs), **core** (the HCFR plugins, outside this repo).

## Summary

| | Count |
|---|---|
| Solved | 20 |
| Partial | 29 |
| Open | 17 (4 of them [limit]) |
| Items | 66 |

Solved today, in short:

- Movement cheats of every kind (fly, speed, phase, timer, blink, no-fall, no-slow, jesus, spider, step).
- Reach, hitbox, through-wall and no-aim killaura hits.
- Anti-knockback.
- Exact hit registration up to the rewind cap: the server's verdict equals the client's own pick, bit for bit.
- Consistent 1.7 knockback.
- Sprint and w-tap desync.
- Sword blocking on a modern client.
- The 26.3 interpolation stutter near the fight.
- Ghost blocks and phasing through fake walls.

The biggest open risks are untested conditions rather than missing mechanisms: players with real ping (above about
150 ms the fixed rewind cap starts refusing honest hits), real jitter and loss, big fights at scale, and Lunar
Client's server-controlled combat options against the exact input grammar.

## 0. Ranked open and partial items

Ranked by player impact: how many players it touches, how often, and how badly. The tag says what kind of work it
is.

| # | Item | Status | Owner | In one line |
|---|---|---|---|---|
| 1 | [HR-2](#hr-2) | Partial | netcode | Was a fixed 300 ms rewind cap; now the attacker's ping is made good up to 250 ms plus its display delay, jitter and tick waits, and past the cap a hit is judged at the target's capped-age truth (the latency-proxy proof is still to add). |
| 2 | [SV-1](#sv-1) | Partial | netcode | Authority has never run a big fight. 100+ players within 24 blocks with K2 paths (twice vanilla's movement packets) are unmeasured for MSPT and bandwidth. |
| 3 | [NW-1](#nw-1) | Partial | netcode | Every real-client run was on localhost. Real ping, jitter and loss were never exercised, so forced knocks, corrections and freezes for Wi-Fi players are unknown. |
| 4 | [CL-1](#cl-1) | Open | netcode, config, core | Lunar's Apollo combat options (no miss penalty, dig-and-use) and other PvP-client changes may break the exact input grammar and use prediction for the biggest client population. |
| 5 | [PR-1](#pr-1) | Open | combat, netcode | Rod, bow and snowball hits are judged on server positions with no lag compensation, so rod combos depend on ping. |
| 6 | [HR-6](#hr-6), [IN-1](#in-1) | Open, Partial | combat, netcode | Classic rules keep 1.7's CPS race and lose clicks into the damage window; the neue cap and click buffer are specced but not built. |
| 7 | [HR-10](#hr-10) | Open | combat, netcode | Trades and "who hits first" are decided by whose packet arrives first, so low ping wins ties. |
| 8 | [PR-7](#pr-7) | Open | combat, core | Pearl glitching: pearls land players inside fence gates, door cracks and slabs, and fly through client-only combat walls. |
| 9 | [NW-2](#nw-2) | Open | netcode, config | An honest 4.5 s network stall kicks the player ("no transaction replies"), which in HCF means a combat-logger death. |
| 10 | [SV-2](#sv-2) | Partial | combat | A server stall still eats hits: damage windows count server ticks while the clients' clicks queue up. |
| 11 | [KB-3](#kb-3) | Open [limit] | combat (metrics) | A high-ping victim's client applies the knock late, so they are harder to combo and feel "glued to the floor". |
| 12 | [KB-4](#kb-4) | Partial | combat, netcode | The knock's direction comes from server positions, not from what the attacker saw, so a strafing target at ping is knocked sideways. |
| 13 | [HR-3](#hr-3) | Partial | netcode | A low-ping cheater has about 170 ms of fake lag or backtrack inside the cap, and victims get hit "behind cover" up to 300 ms in the past. |
| 14 | [PR-3](#pr-3), [PR-4](#pr-4) | Open | combat | Potions and pearls inherit the thrower's motion and splash falls off box-to-box (26.x), so pot accuracy is not 1.7's. |
| 15 | [HR-8](#hr-8) | Open | netcode, combat | Crouching shrinks the 26.x hitbox to 1.5 (swim and crawl to 0.6), and 1.7 never did. |
| 16 | [VI-1](#vi-1) | Partial | netcode | K2 paths only within 24 blocks. Beyond that, the 26.3 stutter is back, at bow, rod and pearl range. |
| 17 | [IN-3](#in-3) | Partial | netcode | Autoclickers are capped but not flagged; the locked-cadence detector is specced, not built. |
| 18 | [IN-4](#in-4) | Partial | netcode | Aim assist is only measured, with thresholds never tuned on real mouse data. |
| 19 | [CL-2](#cl-2) | Partial | config | Non-26.3 clients (ViaVersion, Geyser) skip authority entirely and play client-trusted. |
| 20 | [WD-4](#wd-4) | Partial | netcode | Ghost hand: doors, chests and digs are not ray-checked against walls (only predicted block changes are). |
| 21 | [KB-9](#kb-9) | Open | combat | A 1.7 combo lock has no counterplay; neue DI is specced, not built. |
| 22 | [WD-1](#wd-1) | Partial | netcode | Block glitching is stopped by design (`honor-denied-predictions: false`), but no test plays the HCF case. |
| 23 | [HR-7](#hr-7) | Partial | combat, netcode | The 26.3 client picks the bare box, without 1.7's +0.1 margin; spear hits on players never land under enforce. |
| 24 | [PR-6](#pr-6) | Partial | config | Split damage windows are built but off by default, so fire ticks and arrows still eat sword hits. |
| 25 | [IN-5](#in-5) | Partial | netcode | Inventory-move is blocked, but a one-tick "switch, throw, switch back" pot macro is not. |
| 26 | [VI-3](#vi-3) | Partial | netcode | Invisible or teleporting opponents after a desync are re-paired, with no playtest metric yet. |
| 27 | [MV-5](#mv-5) | Partial | sim, netcode | Exempt ticks (pistons, shulkers, geysers, rod pulls, riptide, `vehicles: vanilla`) trust the client with no band. |
| 28 | [HR-9](#hr-9) | Open [limit] | none server-side | The vanilla miss penalty and the dropped first click after closing the inventory. |
| 29 | [CL-3](#cl-3) | Partial | config | GrimAC false flags on legit 1.7 play; the GrimAC checklist was never run with a real client. |
| 30 | [MV-3](#mv-3) | Partial | netcode | Packet crits and ground spoofing are impossible by design, but no test plays a crit cheat. |
| 31 | [SP-3](#sp-3) | Partial | netcode | Keep-sprint and sprint-packet spoofing are impossible by design, but no cheat-suite row plays them. |
| 32 | [SV-3](#sv-3) | Partial | config | Leaf options that drop or delay knockback are documented but not refused. |
| 33 | [SV-5](#sv-5) | Partial | netcode | Plugins that write velocity or teleports around the choke point get upper-bound timing and corrections. |
| 34 | [SV-4](#sv-4) | Partial | netcode | An exception in authority code falls back to the claim now, but no fault-injection test proves it never kicks. |
| 35 | [SV-6](#sv-6) | Partial | netcode | Stock Spigot movement checks no longer judge the truth, but the stock-checks run predates the fix. |
| 36 | [VI-4](#vi-4) | Partial | config, netcode | Anti-ESP is built but off by default, and pop-in when it is on is unmeasured. |
| 37 | [PR-8](#pr-8) | Solved | netcode | A far teleport or pearl into a chunk the client lacks: held until the chunk is on its way. |
| 38 | [PR-5](#pr-5) | Partial [limit] | none server-side | On high ping the heal lands late; the throw origin is already exact. |
| 39 | [VI-2](#vi-2) | Open [limit] | none server-side | Remote players are drawn about 75-125 ms + RTT behind; lag compensation hides it for hits. |
| 40 | [HR-11](#hr-11) | Open | combat | A damage window opens after 9 or 10 server ticks, depending on whether the hit came in a queued window. |
| 41 | [IN-2](#in-2) | Partial | none needed | Double-click mice: same-tick doubles are dropped; cross-tick doubles count as clicks. |
| 42 | [SP-4](#sp-4) | Open | netcode | W-tap and block-hit macros are not measured. |
| 43 | [IN-6](#in-6) | Open [limit] | none server-side | Clicks count per 20 Hz client tick, and low FPS or 4-8 kHz polling delays them. |
| 44 | [NW-3](#nw-3) | Open | netcode | Tab ping is keepalive-based, not the RTT authority judges with. |

---

## 1. Hit registration and reach

### HR-1 Ghost hits from stale server positions — Solved {#hr-1}

- **Players:** "My hit showed but didn't count", "hit reg feels off", "server lag eats hits". Worse at high ping and
  on servers whose hit check measures on server positions ([3], [22]).
- **Cause:** server. Vanilla and Paper judge the attack against where the server has the target now, not where the
  attacker's client drew it. Paper's check is also lenient (entity range + 3), which helps cheaters. 1.8 Spigot had a
  line-of-sight rule that shortened reach to 3 blocks at corners (PandaSpigot GH-244, [3]).
- **Today:** exact lag compensation. Each viewer has a `MirrorBook` of every other player: a line-by-line port of
  26.3's stepped interpolation, fed the packets the viewer acked, at the tick it applied them. `PickRay` replays the
  client's `pick(1.0F)` from the authoritative eye. `HitValidator` accepts a hit iff some candidate order lets the
  client return that target (`auth/combat/{RemoteMirror,MirrorBook,PickRay,HitValidator}.java`, wired in
  `auth/CombatWiring.java`, patch 1013). In enforce, player targets need `VALID`.
- **Fix:** nothing more for this item. See HR-2 for the cap.
- **Proof (exists):**
  - `MirrorVerify`: 2,000,000 ticks of the mirror against the real client classes, 0 mismatches.
  - `PickVerify`: 1,000,000 scenes against the client's own `LocalPlayer.pick`, 0 mismatches.
  - Real 26.3 client (`~/Developer/tools/auth-rig/README-enforce.md`, run `full3`): 159/159 attacks `VALID`. On 890
    of 890 ticks the server's would-be verdict equals the client's pick, and the mirrored box equals the client's box
    bit for bit.

### HR-2 High-ping players lose hits (fixed rewind cap) — Partial {#hr-2}

- **Players:** "My hits don't register on this server", but only for players on high ping. This is the most common
  complaint in PvP and the one an authoritative server is supposed to end.
- **Cause:** server (netcode).
  - `CombatWiring.judge` computes `cap = min(max-rewind-ms, rttBase + ack-jitter-ticks × 50 + path-step-ticks × 50)`
    = `min(300, rttBase + 300)`. That is always 300 ms, so the per-player `rttBase` term never binds.
  - `HitValidator` refuses `REWIND` when `now - stamp > cap`. The stamp is the send time of the step the attacker's
    client displayed.
  - The real-client run on localhost (RTT 1-15 ms) recorded rewinds of 100 / 111 / 121 / 155 ms (min / p50 / p95 /
    max, `enforce/runs/full3/probe.jsonl`). So a hit's rewind is about RTT + 110 ms: the K2 display delay, plus the
    wait for the client tick and the server tick.
  - From those numbers: some honest hits fail from about 145 ms RTT, most fail from about 190 ms, all fail past about
    200 ms. This is arithmetic from measured parts; no run with real latency exists (NW-1).
- **Today (2026-10-22, Overwatch's policy):** one cap for melee, Neue's held-click landing and projectiles
  (`CombatWiring.sharedRewindCap`): `min(rttBase, rtt-ceiling-ms 250)` + the display delay measured from the attacker's
  own mirror + its jitter (p90 − p10, at most the ceiling) + `rewind-slack-ms` (150). `max-rewind-ms` (600) is only a
  sanity bound. Inside the cap the hit is judged on the box the client drew (HR-1); past it, against the target's truth
  at the capped age (`HitValidator`, `TruthHistory`), so a shooter whose ping exceeds the ceiling leads moving players
  by the excess instead of losing every hit. Config version 3 moves files still holding the old 300/400 to 600.
- **Proof:** `RewindCapTest`; cheat suite: honest hits at 50, 150 and 250 ms land, at a steady 300 ms on a target
  sprinting across (rewind 445 ms, cap 513 ms); fake lag stays `INVALID`. Still to add: the real-client latency proxy
  runs, and the `rewind-stretch` anomaly.

### HR-3 Backtrack and fake lag inside the cap; hit "behind cover" — Partial {#hr-3}

- **Players:**
  - Cheaters: "backtrack" and "fake lag" hit you where you were a moment ago ([8]: Grim issues #1411, #1642).
  - Honest victims of lag-compensated servers: "I was already behind the wall".
- **Cause:** netcode trade-off. Favouring the attacker means rewinding the victim. The rewind a player may use should
  be what their connection needs, not a constant.
- **Today:**
  - A cheater who delays its own pongs delays its own knockback too, bounded by the ack deadline (cheat rows 15-17).
  - A cheater who answers pongs but holds entity packets is judged on the mirror the pongs say it has, so its old-box
    hits are `INVALID`.
  - The cap is the attacker's own (HR-2): a 20 ms player's is about 20 + display + jitter + 150, so the fake lag it
    can add freely is about the slack and its own jitter; past the cap its hits are judged at the target's truth at the
    capped age, not at the stale box it chose.
  - The gap left: the 150 ms tick-wait slack, and the `rewind-stretch` flag (not built).
- **Proof (to add):** the HR-2 cheat row. Also a victim-side metric: the share of hits taken where the victim's own
  truth was more than 1 block from the attacker's mirrored box. Report p50 and p99 per ping band in playtests.

### HR-4 Reach, hitbox expand, through-wall hits, killaura without aim — Solved {#hr-4}

- **Players:** 3.1-3.3 "legit reach" debates, hitbox mods, killaura ([2], [8]).
- **Cause:** client cheats. Server checks on stale positions need tolerance, and a 1.7/1.8 client's 0.1 pick margin
  makes cross-version tolerance worse ([2]).
- **Today:** the exact pick (HR-1) leaves no tolerance beyond float epsilon:
  - 26.3's bare box, strict `<` against `ENTITY_INTERACTION_RANGE` from the client's own attribute view, the block
    pass on the client's `WorldView`;
  - `INVALID` reasons `OUT_OF_REACH`, `BLOCKED`, `OCCLUDED`, `MISSED`;
  - an `INEXACT` mirror gives `UNKNOWN`, and in enforce a player target is then not hit (fails safe).
- **Fix:** none needed. Killaura that also aims is an input cheat (IN-4).
- **Proof (exists):** cheat suite rows 45-50 (reach 3.5, 4.5 and 6.0, through a wall, killaura without aim, 5 attacks
  in one window), with honest controls at 2.5 blocks (rows 44 and 51).

### HR-5 Honest hits refused after a lag spike or during one's own teleport — Solved {#hr-5}

- **Players:** "after that lag spike I couldn't hit him for the rest of the fight"; "I pearled and hit, and it
  didn't count".
- **Cause:** netcode.
  - Entries force-applied at the ack deadline marked the victim's mirror inexact until the next spawn, and since
    `UNKNOWN` no longer delivers, the victim became unhittable.
  - Attacks during a pending server teleport were never judged.
- **Today:**
  - `PathWiring` re-pairs a target on a viewer whose mirror went inexact (`mayRepair`, at most every
    `repair-interval-ticks` 20), so the mirror is exact again within a few ticks.
  - `AuthPlayer.runAttack` judges attacks while a teleport is pending, from the pre-teleport windows.
- **Fix:** none beyond the proof refresh.
- **Proof:** cheat rows 53, 57 and 60 (honest controls). The table in `auth-cheat-suite.md` still shows them failing,
  from the 21:45 run. Commit `a7a84a06` reports "67/68 blocked with no honest client affected" after the fixes.
  Re-run the suite and refresh the table (§13).

### HR-6 Clicks into the damage window ("didn't register" mid-combo) — Open {#hr-6}

- **Players:** in a combo, clicks that land while the victim is still immune do nothing. This is the 1.7 "hit delay"
  feel, and the root of the CPS race (IN-1): more clicks means one is more likely to land on the tick the window
  opens.
- **Cause:** server, by 1.7's design. A fresh hit opens a 10-tick window, and a click inside it is dropped unless it
  is harder (overflow). The client can't see the victim's window, and at ping it can't time it.
- **Today:** classic rules are exactly 1.7 (`LivingEntity.revivalHurtServer`, `combat/LegacyDefence.lastHurt`).
- **Fix (combat, netcode):** the neue click buffer (`neue-combat.md` §5). A valid click that the window turns away
  waits up to 3 server ticks and lands on the first tick the window opens, with reach re-checked against the
  attacker's mirror. This is opt-in per world (`rules: neue`). Classic stays as 1.7.
- **Proof (to add):** `ClickBufferTest`; the live pair from §5.4 (exactly one buffered fresh hit on the tick
  `damageCooldownTime` reaches 10, none under classic); `buffer_sim.py` targets (2.00 fresh hits/s for every rate of
  6 CPS or more with perfect aim).

### HR-7 1.7's pick margin, and attack-range items — Partial {#hr-7}

- **Players:** 1.7/1.8 clients hit "more easily". 1.7 inflated the target box by 0.1 for picking; 1.9+ removed it
  ([2], [14]: OCM #69).
- **Cause:** client. The 26.3 client picks the bare 0.6 × 1.8 box (`getPickRadius() = 0`).
- **Today:**
  - The box is kept by decision (`neue-combat.md` §1, §9): reach at the edge is about 0.1 shorter per side than 1.7.
  - Separate gap: `HitValidator` returns `UNKNOWN UNSUPPORTED` for an attacker holding an item with
    `ATTACK_RANGE` (vanilla spears), so under enforce spear hits on players never land.
- **Fix (combat, netcode):**
  - Option A (keep): document that legacy reach is measured to the bare box.
  - Option B (restore): add `attack_range { max_reach: 3.0, hitbox_margin: 0.1 }` to the client decoration of legacy
    weapons (`LegacySwordBlocking.toClient`, beside the edible-sword `consumable`). The client then picks through
    `AttackRange.getClosesetHit` → `ProjectileUtil.getHitEntitiesAlong`. That path differs from 1.7 in two ways:
    - it adds `max(0, knownMovement · look)` to the reach (sprinting at someone reaches about 0.28 further);
    - it uses the head look angle and the nearest hit location.

    So B is "1.7 margin plus a lunge", and `PickRay` must port that path bit for bit (it returns `UNSUPPORTED`
    today). Port it either way, so spears work under enforce.
- **Proof (to add):** extend `PickVerify` to the `ATTACK_RANGE` path against the real client (0 mismatches in 10^6
  scenes). For B, the rig's `attack_ranges` at eye-to-box distances 2.95-3.15 with and without sprinting, all
  `VALID` iff the client picked.

### HR-8 Modern poses shrink the hitbox — Open {#hr-8}

- **Players:** crouch-spam to dodge hits. Sprint-swimming and crawling players are tiny targets in water and in
  trapdoor gaps. 1.7 never changed the box ([15]: Java Edition 1.9 and 1.14 changes).
- **Cause:** client. A 26.3 client sizes a remote player from the pose in its entity data: crouching 1.5 tall,
  swimming and crawling 0.6. Its own eye drops to 1.27 when crouching, against 1.54 in 1.7.
- **Today:** the mirror replays the pose exactly (`RemoteMirror`, `DATA_POSE`), so hits are judged on the 26.x box.
  That is consistent, but not 1.7.
- **Fix (netcode, combat):** a `legacy-poses` option.
  - Rewrite `DATA_POSE` `CROUCHING` → `STANDING` in entity data sent to viewers. Keep the shared-flags sneak bit, so
    the nametag still dims. Viewers then see a full 1.8 box, as in 1.7, and lose the crouch animation.
  - The mirror follows by itself, because it reads the packets.
  - The sneaker's own lower eye is client-owned and stays.
  - Swim and crawl: the same rewrite gives a 1.8 box that clips into the block above a crawler. Instead, decide per
    map: no crawl spaces, no water fights.
  - Owner: netcode for the rewrite (next to `SelfEcho`), config for the decision.
- **Proof (to add):** the rig, with a sneaking target. The viewer client's `getBoundingBox()` height is 1.8, and hits
  aimed at y 1.5-1.8 above the feet are `VALID` and land.

### HR-9 Miss penalty and the dropped first click after a screen — Open [limit] {#hr-9}

- **Players:** "1.8 hit delay" ([1]: HitDelayFix). A whiff while the button is still down at the tick boundary
  blocks attack clicks until a tick sees the button up. After closing the inventory (pot refills) the first attack
  click is dropped.
- **Cause:** client. `startAttack` sets `missTime = 10` on a miss; `continueAttack(false)` clears it in any tick the
  button is up. A screen sets `missTime = 10000`, and the first click after closing it is eaten (report 02 §2.3-2.4).
  Fast clickers rarely feel the first; held-click and drag clickers do.
- **Today:** nothing server-side can change client state that is never sent. The input grammar mirrors it
  (`ATTACK_AFTER_CLOSE`, `auth/input/InputRules.java`).
- **Fix:** none for vanilla clients. Lunar's Apollo combat module has a server-controlled `DISABLE_MISS_PENALTY`
  ([20]). That makes Lunar and vanilla players play different rules, and it collides with the grammar (CL-1).
- **Proof:** rig scenario `whiff_then_click`: agent clicks against `Punch` packets, documenting how many clicks the
  client itself eats. This is a reference number, not a pass/fail.

### HR-10 Trades: the lower uplink wins ties — Open {#hr-10}

- **Players:** "low ping wins trades", "first hit is random on ping" ([5], [6]). In 1.7 the first fresh hit often
  decides the combo.
- **Cause:** server ordering. Attacks are applied in arrival order. Two players who click at the same real instant
  land in the order their packets arrive, so the lower one-way latency wins. Lag compensation fixes where, not when.
- **Today:** arrival order. When two attackers have buffered clicks on one victim, neue lands them in connection
  order (`neue-combat.md` §5.2).
- **Fix (combat, netcode):** inside one server tick, apply player-vs-player attacks in order of estimated click time,
  `arrival − rttBase / 2`, instead of arrival. This adds no delay. Bound the credit by the HR-2 cap, so lagging
  doesn't buy priority. Ties across server ticks stay inherent.
- **Proof (to add):** two `FakeClient`s with asymmetric latency (20 ms and 120 ms) click at the same real instants
  on each other 1,000 times. Measure the first-fresh-hit share: about 100/0 before, target 50 ± 3 after.

### HR-11 The damage window opens after 9 or 10 server ticks — Open {#hr-11}

- **Players:** combos that feel a tick off now and then.
- **Cause:** server phase. A victim's window decrements in the level phase. A hit applied in the packet phase opens
  the window at `S+9`; a hit from a timer-queued window (connection phase) opens it at `S+10` (`neue-combat.md` §3).
  1.7 processed packets after the world tick, so it was always 10.
- **Today:** not handled.
- **Fix (combat):** for legacy melee hits applied before this tick's level phase, set the window one tick longer, so
  every hit opens the window 10 decrements after the tick it was applied in, as in 1.7.
- **Proof (to add):** live test. Hits in both phases; the next fresh hit is possible exactly 10 server ticks later in
  both cases.

---

## 2. Knockback

### KB-1 Knockback depends on packet order and login order — Solved {#kb-1}

- **Players:** "KB feels different every hit", "relog fixes KB", standing hits lifting a different amount ([7],
  MC-52881).
- **Cause:** server. The victim's server motion depended on when their movement packets arrived relative to the
  attack, and on friction applied before the velocity went out.
- **Today:** SportBukkit's 1.7 fix, through these pieces:
  - `MotionShadow` steps a no-input 1.7 server motion (a standing victim settles at y = −0.0784);
  - `LegacyKnockback.sendVelocity` sends exactly one velocity packet per hit and restores the motion;
  - `KnockbackEngine` does base and extra knock in one event.

  These live in `combat/{MotionShadow,LegacyKnockback,KnockbackEngine}.java`, patch 1003. Under authority the knock
  is a logged entry, applied to the truth at its exact ack tick.
- **Fix:** none.
- **Proof (exists):** unit goldens (standing hit lifts 0.3608). In the rig, `kb_hit_still` gives three identical
  knocks `(0, 0.360801, −0.399988)`, each applied in the window of the client tick that drained it, bit-equal
  (`README-enforce.md`).

### KB-2 Anti-knockback and velocity cheats — Solved {#kb-2}

- **Players:** velocity cheats, "0/0 KB", cancelled velocity packets ([8]: Grim #1382).
- **Cause:** client. The server used to trust the client's position after the knock.
- **Today:** the knock enters the simulation at its ack window. A client that ignores it is corrected. One that
  withholds pongs has the knock force-applied at the deadline (`ceil(rttBase/50) + ack-jitter-ticks` windows), then
  is frozen, then kicked (`auth/net/Transactions.java`, `AuthPlayer`).
- **Fix:** none. Delaying pongs within the deadline delays one's own knock by up to about 200 ms beyond `rttBase`,
  which is indistinguishable from jitter. NW-1's measured jitter can tighten `ack-jitter-ticks`.
- **Proof (exists):** cheat rows 13-17 (ignore the knock, withhold pongs, delay 3 and 9 ticks, batch two knocks).

### KB-3 High-ping victims take the knock late — Open [limit] {#kb-3}

- **Players:** "high ping players are glued to the floor", "can't combo high ping", or the reverse, "low ping gets
  comboed easily" ([5], [6]; Hypixel threads say high-ping KB arrives 200-220 ms late).
- **Cause:** protocol and client. A vanilla client applies velocity when the packet arrives (victim one-way latency),
  and keeps moving by its own input until then. Nothing a server sends can make it apply the knock earlier.
- **Today:** the knock is exact and consistent (KB-1, KB-2): same vector, same bits. What still varies with ping is
  when it starts. Ground prediction (`ground-prediction-distance`, KnockbackSync-style, [4]) exists for
  non-authority players and is unnecessary under authority (KB-5).
- **Fix:** none on a vanilla client. Make it visible instead (combat). Log each knock's apply lag (ack window minus
  the hit's window) and show its p50/p99 per ping band in `/revival auth inspect`, so balance decisions use data. A
  client mod with rollback could apply the knock at the stamped tick (§12).
- **Proof:** a playtest metric, not pass/fail: knock apply lag per victim against `rttBase`.

### KB-4 The knock's direction comes from server positions — Partial {#kb-4}

- **Players:** a strafing target at ping is knocked sideways or "the wrong way".
- **Cause:** server. `KnockbackEngine.knockback` takes `xd`/`zd` from the attacker's and victim's server positions
  when the hit is processed. Under authority those are each player's truth after their latest processed window, at
  different real times. The attacker aimed at the mirrored box. At 100 ms and a 0.28 b/tick strafe, the two
  disagree by about 0.8 blocks sideways, or 15-20° at 2.5 blocks.
- **Today:** server positions, as 1.7 did.
- **Fix (combat, netcode):** an option `knock-direction: attacker-view`. Under authority, take `xd`/`zd` from the
  hit's own geometry: the attacker's snapshot eye to the mirrored target box at the pick tick, which `HitCheck`
  already has. Pass it through the same per-attack context the neue tipper uses (`neue-combat.md` §6.2). The
  fallback stays server positions.
- **Proof (to add):** live `FakeClient` pair, victim strafing at sprint speed, attacker link delayed 100 ms. The
  decoded knock yaw matches the attacker-view direction within 0.5° (LpVec3), against a 10-20° spread with server
  positions.

### KB-5 Ground state for the knock (on the ground client-side, airborne server-side) — Solved {#kb-5}

- **Players:** random downward or weak knocks ([4]: KnockbackSync's reason to exist; hcfr-core issue #109 asked for
  it).
- **Cause:** server. The server's `onGround` lagged the client's.
- **Today:** under enforce the `ServerPlayer`'s `onGround` and motion are the simulation's (the client's, exactly),
  so `MotionShadow` steps with the true ground state. `motion-model: authoritative` knocks from the simulated
  velocity itself (`LegacyKnockback.motion`).
- **Fix:** none.
- **Proof (exists):** rig `kb_hit_sprint`. The airborne knock at t41 equals the client's velocity bits.

### KB-6 W-tap overflow hits pull the victim into the floor — Solved {#kb-6}

- **Cause:** server. A second knock was built from falling server motion.
- **Today:** the overflow extra knock changes only server motion and is never sent, as in 1.7
  (`LegacyKnockback.targetMovement`, PR #13, commit `624f21ee`).
- **Proof (exists):** the unit tests from PR #13.

### KB-7 Hits in water and lava push players down — Solved {#kb-7}

- **Today:** liquid drag 0.8 (water) and 0.5 (lava) in `MotionShadow.step` (PR #15).
- **Proof (exists):** the unit tests from PR #15.

### KB-8 "KB feels different on every server" — Solved (as tooling) {#kb-8}

- **Players:** every network ships its own values ([7]; the Badlion and Kohi KB history).
- **Today:**
  - presets `legacy-1.7`, `legacy-1.8`, `kohi`, `minehq`, `badlion`, `mmc`, `lunar`, `velt`, `hcfr`;
  - live `/revival kb set`, and per-world and per-player profiles;
  - `RevivalKnockbackEvent` (`config/KnockbackProfile.java`, patch 1007).
- **Fix:** none in code. Which profile to use is a design call (config). The archived presets' authenticity can't be
  verified (README).

### KB-9 Combo lock with no counterplay — Open {#kb-9}

- **Players:** once comboed at low ping, a 1.7 player often can't escape.
- **Cause:** gameplay. The 1.7 knock leaves the victim no input.
- **Today:** classic is plain 1.7.
- **Fix (combat):** neue DI (`neue-combat.md` §7): the victim's held direction bends the horizontal knock by up to
  15°.
- **Proof (to add):** `NeueShapeTest` goldens; the live DI test (bend 15° ± 0.05°).

---

## 3. Sprint and w-tap

### SP-1 Sprint state is stale or desynced at the hit — Solved {#sp-1}

- **Players:** "my w-tap didn't register", sprint resets that don't count on high ping ([6], [12]).
- **Cause:** server and protocol. The server took the sprint knock from its own sprint flag (the last sprint packet)
  instead of the sprint the client attacked with. A modern client also re-sprints after a hit without a packet when
  the sprint key is held.
- **Today:**
  - Under authority the attack reads `AuthPlayer.combatSprinting()`: the sprint state at that attack's position in
    the client tick.
  - `SprintLedger` arms from the simulated sprint (`LegacyKnockback.simulatedSprint`), so packets alone can't re-arm
    it.
  - One knock per sprint start, as in 1.7 (`combat/SprintLedger.java`, patch 1004).
- **Fix:** none.
- **Proof (exists):**
  - rig `attack_sprint_hits`: the client's ×0.6 and sprint stop reproduced exactly;
  - live: a double-tap sprint sword hit deals the same damage and knock with authority on and off (`auth-netcode.md`
    "Testing").

### SP-2 Sprint latch-off, FOV flicker, sprint echo — Solved {#sp-2}

- **Players:** sprint drops twice, FOV flickers (MC-4098), 1.21.2+ clients stuck not sprinting ([12]).
- **Cause:** protocol. The server echoes sprint back through entity data and attribute syncs, and the client copies
  them.
- **Today:**
  - Legacy hits never clear the attacker's sprint flag (patch 1004).
  - `auth/view/SelfEcho.java` drops client-owned flags from self echoes.
  - `PortSimulation.sprintFromState` keeps the sprint modifier on the truth only while it sprints (cheat rows 69-70,
    fixed in the tree).
- **Proof (exists):** rig scenarios with sprinting (0 corrections); cheat rows 69-70 on the next run (§13).

### SP-3 Keep-sprint and sprint-packet spoofing — Partial {#sp-3}

- **Players:** KeepSprint cheats keep full speed after hitting ([8]: Grim #2849). "Auto w-tap" cheats send
  STOP/START sprint around every attack to always get the sprint knock.
- **Cause:** client.
- **Today:** impossible by design.
  - The simulation applies the client's own attack prediction (×0.6 x/z and sprint off on a sprinting hit on a
    player, `Simulation.clientAttack`), so a client that skips it diverges and is corrected.
  - Sprint packets that don't match the simulated input don't change the simulated sprint, so they can't re-arm the
    ledger.
- **Fix:** none in code.
- **Proof (to add):** two cheat rows.
  - Keep-sprint: skip the ×0.6 after one's own hit. Expected: the truth equals the honest reference to the bit, the
    client is corrected, and the knock is unchanged.
  - Spoofed STOP+START before each attack without a key change. Expected: `SprintArmEvent` does not fire, and the
    knock uses the held level.

### SP-4 W-tap and block-hit macros — Open {#sp-4}

- **Players:** macro w-taps and block-hits that are perfectly timed on every hit.
- **Cause:** input. A macro presses real keys, so every packet is legal.
- **Today:** nothing measures it.
- **Fix (netcode):** evidence only, like the click detector. In `InputStats`, record per attack the gap from the last
  STOP/START sprint (or block release) to the attack. Flag a gap that is fixed to the tick over 100 attacks, with
  zero spread.
- **Proof (to add):** a synthetic-population study like `neue-combat.md` §12.3: false positives per hour on human
  timing models, detection rate on fixed-delay macros.

---

## 4. Sword blocking

### BL-1 Sword blocking on a modern client — Solved {#bl-1}

- **Players:**
  - Modern servers emulating 1.8 show a shield or need resource packs ([14]: OCM #976).
  - Blocking can get stuck after closing an inventory ([13]: PandaSpigot #452).
  - `blocks_attacks` made spam-clicking look like raising a shield.
- **Cause:** client and protocol. 1.9 removed sword blocking.
- **Today:** the edible-sword trick.
  - Clients get a copy of legacy weapons with an hour-long silent `consumable` and the block animation.
  - The server never gives swords a blocking component, so Paper's shield code never runs.
  - A blocked hit takes `(damage + 1) × 0.5` with full knockback, as in 1.7.
  - Files: `combat/LegacySwordBlocking.java`, patch 1009, PR #14.
  - Under authority the simulation owns item use, including the screen case (the client keeps using while a screen
    is open, until the first tick after it closes), per §15.4.
- **Proof (exists):** rig `block_walk`, `block_sprint_jump` and `block_strafe_taps`: speed ×0.2 while blocking,
  0 corrections. Live: a number-key slot switch while blocking takes the hotbar branch with no correction.

### BL-2 Autoblock and no-slow while blocking — Solved {#bl-2}

- **Players:** cheaters attack while blocking, or block without the slowdown ([8]: Grim no-slow PRs).
- **Today:**
  - `InputRules.ATTACK_WHILE_USING`: a vanilla client discards every click while using, the release tick included,
    and so did 1.7.
  - The simulation applies the ×0.2 slowdown.
- **Proof (exists):** cheat rows 61 (autoblock) and 12 (no-slow blocking: the truth equals the honest blocking walk,
  gap 0).

---

## 5. Clicking, input and hardware

### IN-1 The CPS race (drag, butterfly, jitter) — Partial {#in-1}

- **Players:**
  - 1.7 rewards raw clicks per second: more clicks land more window openings and give more aim samples.
  - Drag and butterfly clickers reach 20-40 CPS.
  - Bans for "too fast" clicking are disputed ([9], [25]).
- **Cause:** gameplay and input.
- **Today:**
  - `max-attacks-per-client-tick` 1 (`auth/input/InputEnvelope.java`): at most 20 attacks/s, and a second click in a
    tick (same `hitResult`) is dropped.
  - Classic otherwise has no cap, as in 1.7.
- **Fix (combat, netcode):** neue's 12 CPS token bucket (0.6 tokens per client tick, burst 2) with the click buffer
  (HR-6). Together they make combo timing independent of CPS at 7 CPS or more (`neue-combat.md` §4-5, §12.1).
- **Proof (to add):**
  - `ClickBucketTest`: even 9 and 12 CPS lose 0 clicks; 20 CPS delivers 12.0 ± 0.2/s.
  - Live: 40 attacks in 40 ticks deliver 24 or 25.

### IN-2 Double-click mice and low debounce — Partial {#in-2}

- **Players:** worn switches or 0-4 ms debounce produce double clicks; players argue whether that is cheating ([10]).
- **Cause:** hardware.
- **Today:**
  - Two clicks within one client tick share one `hitResult` and the envelope drops the second, so a bounce inside a
    tick gains nothing.
  - A bounce across a tick boundary is a real extra click (an aim sample).
  - `InputStats` records the double-click ratio (`stats.double-click-ratio` 0.3).
- **Fix:** none needed beyond IN-1's cap. Hardware bounce and butterfly clicking are not separable at 50 ms
  resolution, and the cap makes the question moot.
- **Proof:** `ClickBucketTest` covers it. Document in a player FAQ that double-clicks gain nothing past 12 CPS under
  neue.

### IN-3 Autoclickers — Partial {#in-3}

- **Players:** autoclickers and mouse-firmware macros; disputes about false bans ([9]; Intave calls its click-pattern
  check unstable).
- **Cause:** input. Every click is a legal packet.
- **Today:**
  - Bounded by the envelope (and by neue's cap, when built).
  - `InputStats` (click intervals) and `AnomalyWatch` raise `AuthInputAnomalyEvent` past thresholds
    (`auth/input/*`).
  - There is no cadence detector.
- **Fix (netcode):** build the `CadenceWatch` detectors from `neue-combat.md` §12.2. `click-streak` catches a 20 CPS
  lock; `click-lock` catches any fixed rate (the least-squares residual ≤ 0.75 tick over 150 clicks). These are
  evidence for staff, never authority. Well-randomized autoclickers (±25%) stay undetectable at tick resolution;
  the cap is the defence.
- **Proof (to add):**
  - `CadenceWatchTest`.
  - Re-run the §12.3 study on recorded playtest windows (`InputStats` intervals, RVTR traces) before trusting the
    thresholds. Target: ≤ 0.01 false positives per hour on real players.

### IN-4 Aim assist and aimbot — Partial {#in-4}

- **Players:** aim assist and aimbots ([8]).
- **Cause:** input. The server can't know where a human meant to look.
- **Today:** measured, not prevented. `InputStats` fits the sensitivity GCD of rotation deltas (`aim-gcd-miss-ratio`
  0.5 over `aim-min-turns` 200). The rig's aim is computed, not mouse-driven, so it can't calibrate this
  (`README-enforce.md` "Limits").
- **Fix (netcode):** calibrate the GCD thresholds on real mouse data from known-clean players. Add snap analysis
  (rotation acceleration at target acquisition) as a second signal. Prevention stays impossible on any client.
- **Proof (to add):** false-positive rate per hour on a recorded clean playtest (target ≤ 0.01); detection rate on
  rotation streams from a known aim-assist client in a private test.

### IN-5 Pot, refill and soup macros; inventory-move — Partial {#in-5}

- **Players:** auto-pot, refill macros, inventory moves while running ([8]; Hypixel bans macros).
- **Cause:** input and client.
- **Today:** `InputRules`:
  - `CLICK_WHILE_MOVING`: a container click in a tick whose input still walks is an inventory-move (cheat row 63);
  - `ATTACK_AFTER_CLOSE`;
  - use and attack while using.

  Auto-refill while running is therefore refused. A human-speed macro (switch, throw, switch back over 2-3 ticks) is
  legal input.
- **Fix (netcode):** one more exact rule, to verify against `Minecraft.handleKeybinds` (report 02 §2.4).
  - Hotbar keys are handled before attack and use clicks, and a scroll's `SetCarriedItem` goes out at the start of
    the next tick. So a vanilla window never holds a `SetCarriedItem` to a different slot after its first
    `Attack`/`UseItem`/`UseItemOn`/`Interact`.
  - A one-tick "switch, throw, switch back" is then a grammar violation.
  - An AutoHotkey-style macro on a vanilla client can't do it in one tick: the client would use the item in the final
    slot. So only modified clients trip the rule.
- **Proof (to add):** a unit test of the rule against the client's keybind order. A cheat row: SCI(pot), `UseItem`,
  SCI(sword) in one window. Expected: the second SCI is refused and counted. Honest control: the same over two ticks
  passes.

### IN-6 Click and frame timing, polling rate — Open [limit] {#in-6}

- **Players:** "input delay", "1.8.9 has the best input"; 4000-8000 Hz mice stutter; low FPS feels laggy ([11],
  [19]).
- **Cause:** client and hardware.
  - Clicks are polled per frame and consumed by the next 20 Hz client tick, so a click waits up to 50 ms plus a
    frame.
  - Low FPS stretches that.
  - GLFW's mouse handling drops FPS at high polling rates.
- **Today:** the server judges each click at the client tick the client itself used, so timing is exact at tick
  resolution. It can't be finer than what the client sends.
- **Fix:** none server-side. Client advice in the FAQ (polling ≤ 1000 Hz on vanilla, an FPS floor). A
  click-between-ticks mod changes feel only (§12).
- **Proof:** none (out of the server's reach).

---

## 6. Projectiles, rods, potions, pearls

### PR-1 Rod, bow and snowball hits are not lag-compensated — Open {#pr-1}

- **Players:** "rod didn't hit", "rod hitbox", bow shots that pass through on ping ([16]-adjacent forum threads;
  "fixing ghost hits breaks rods in many forks", [3]).
- **Cause:** server. Projectiles are server entities. Their collision is tested against the victim's server box (the
  truth after their latest processed window). The shooter aimed at the mirrored display, which is RTT + about
  110 ms older.
- **Today:**
  - 1.7 rod and projectile rules (PR-2).
  - Collision is plain server-side.
  - A rod pull on a player is an exempt tick for the simulation (`auth/ExternalMovers.java`).
- **Fix (combat, netcode):** favour-the-shooter projectile hits.
  - For a projectile owned by a player under authority, each server tick test its swept segment against both the
    victims' server boxes and the boxes the shooter's `MirrorBook` displays now (the stamp-based rewind, bounded by
    the HR-2 cap).
  - Hit if either intersects. The non-player path is unchanged.
  - Rods, snowballs and eggs first (they are in the melee window); arrows behind an option.
- **Proof (to add):** `FakeClient` shooter with a rod and a bow against a strafing target at 0, 100 and 200 ms RTT.
  The hit rate at 100 and 200 ms stays within 5 points of 0 ms. Over-hit audit: no hit where the victim's own truth
  was farther than the cap allows.

### PR-2 1.7 rod and projectile mechanics — Solved {#pr-2}

- **Players:** 1.9+ removed rod knock ([15]: Fishing Rod); Paper drops 0-damage hits; rods feel sluggish ([14],
  PandaSpigot #339).
- **Today:** `combat/LegacyProjectiles.java`, patch 1008:
  - 0-damage hits land on players (knock, flash, window);
  - the knock comes from the shooter, with Punch folded in;
  - rods hit on impact and hook only if the hit landed;
  - 1.7 cast (1.5) and gravity (0.04);
  - the 1.7 reel pull on server motion.

  hcfr-core #321 (more projectile knock) is served by `knockback-profile` under `projectiles`.
- **Proof (exists):** the unit tests from PR #9; GrimAC checklist row 6 when run (CL-3).

### PR-3 Thrown potions, pearls and arrows inherit the thrower's motion — Open {#pr-3}

- **Players:** a pot thrown down while sprinting drifts forward, and a pearl thrown while sprinting flies further,
  unlike 1.7 ([15]: Java Edition 1.9, "projectiles account for thrower motion").
- **Cause:** server. 26.3's `Projectile.shootFromRotation` adds the shooter's known movement (its y only when
  airborne). 1.7's `EntityThrowable` and `EntityArrow` did not.
- **Today:** 26.x for potions, pearls, arrows, snowballs and eggs. The rod cast already overrides its own motion
  (`LegacyProjectiles.cast`).
- **Fix (combat):** `legacy-combat.projectiles.inherit-shooter-motion: false`. After `shootFromRotation`, subtract
  the shooter's known movement for legacy projectile types (or use 1.7's heading alone). Under authority the known
  movement is the truth's, so the throw origin and motion are exact.
- **Proof (to add):** unit goldens. A potion thrown at pitch 90 by a sprinting player has 1.7's velocity, and a pearl
  thrown at pitch 0 lands where 1.7's would.

### PR-4 Splash falloff and pot accuracy — Open {#pr-4}

- **Players:** pot accuracy debates; HCFR asked for precision changes three times (hcfr-core #13, #53, #115, closed,
  handled in core).
- **Cause:** server. 26.3's `ThrownSplashPotion.onHitAsPotion` measures box-to-box distance
  (`potionAabb.distanceToSqr(entity box inflated by the margin)`), so any box touching the potion's gets full
  strength. 1.7's `EntityPotion.onImpact` measured from the potion to the entity's position (its feet), which is
  stricter, and gave full strength to the entity it hit directly.
- **Today:** 26.x falloff, with core's own precision plugin on top.
- **Fix (combat):** `legacy-combat.potions.splash: legacy-1.7 | vanilla`. Under `legacy-1.7`, use 1.7's point
  distance and the direct-hit rule, inside the same effect box. Agree with core who owns precision, so the two don't
  stack.
- **Proof (to add):** a golden table of heal amounts at offsets 0-4 blocks for both modes, plus the RedLine pot drill
  stats (`potionAccuracy`) before and after.

### PR-5 Pot timing on high ping — Partial [limit] {#pr-5}

- **Players:** "ghost pots", high-ping players potting at five hearts ([5]).
- **Cause:** protocol and client. The heal reaches the client one RTT after the throw, plus flight time.
- **Today:** the `UseItem` is replayed inside its window, before that tick's movement, with the `ServerPlayer` at the
  truth of the previous tick. So the potion spawns exactly where the client threw it. The delay is inherent.
- **Fix:** none for the delay (§12).
- **Proof (to add):** live. A sprinting `FakeClient` throws a potion; the potion's spawn position equals the client's
  eye at that tick, to the bit.

### PR-6 Fire, arrows and mob hits swallowed by sword combos — Partial {#pr-6}

- **Players:** a fire tick turns the next sword hit into a no-knock overflow; poison and arrows vanish during combos.
- **Cause:** server. 26.x has one damage window for everything.
- **Today:** per-channel windows (melee, projectile, mob, other) are built (`combat/DamageWindows.java`, PR #18), but
  `damage-windows.enabled` defaults to `false`.
- **Fix (config):** decide for HCFR, then turn it on per world after a playtest.
- **Proof (exists, unit):** the tests from PR #18. A playtest A/B of combo length with fire aspect and bows.

### PR-7 Pearl glitching — Open {#pr-7}

- **Players:** HCF's classic: pearls landing inside fence gates, door cracks, slabs, stairs and panes, then walking
  through. Pearling through client-only combat and claim walls. Pearls into 1×1 gaps ([16]; MC-2164).
- **Cause:** server.
  - Vanilla teleports the owner to the pearl's hit position with no check that the player's box fits.
  - The 26.x client lets a box that starts inside a block move out of it (and pushes itself out).
  - Fake walls exist only on the client, so the server's pearl flies through them.
- **Today:**
  - Server teleports are transactions. The truth takes the destination with client semantics, and the simulation
    then moves exactly as the client would, glitch included.
  - Fake walls are honoured for walking (WD-3), not for pearls.
  - Nothing in RevivalPaper checks the landing.
- **Fix (combat, with core for rules):** a `legacy-combat.pearls` section.
  - **Safe landing:** sweep the player's box back along the pearl's last segment until it fits the collision world
    (as the thrower's `WorldView` has it), and land there. If no position fits, refund and cancel (the HCF "taliban"
    rule).
  - **Pass-through list:** blocks a pearl may carry through (open fence gates, string, and so on), configurable,
    because HCF servers allow some on purpose.
  - **Fake walls count:** collide the pearl against the thrower's `WorldView` sent states, so combat walls stop
    pearls as well as feet.
  - Owner: combat. Core supplies its region rules.
- **Proof (to add):** a pearl suite in `AuthLiveScenarios` with 30 known glitch spots (fence gate open and closed,
  door crack, trapdoor, slab gap, stair corner, pane, cobweb, a fake glass wall). For each spot: the truth box never
  ends past the blocking plane, the refund fires when no spot fits, and an honest pearl onto open ground lands on
  vanilla's spot.

### PR-8 Teleport into a chunk the client hasn't received — Solved {#pr-8}

- **Players:** a long pearl or `/tp` falls into the ground, or snaps back once.
- **Cause:** client and server ordering. The client reads a missing chunk as void air and falls (it does on vanilla
  servers too).
- **Today (2026-10-22):** fixed. A server teleport (`/tp`, plugin teleports, pearls) whose destination chunk under the
  player's box is neither held by the client nor already sent to it is held in `AuthPlayer` (`HeldPacket`) until it is:
  Moonrise usually sends it within a tick or two; after `teleport-chunk-send-ticks` (2) the netcode sends it itself, and
  after `teleport-hold-max-ticks` (100) the teleport goes out regardless (a chunk the server can't load). The chunk then
  reaches the client ahead of the teleport, so it never stands over void air. On the 03:03 NAS jar the chunk-seeding
  change had made this fatal (the truth and the client fell through the gym floor together, 0 corrections).
- **Proof:** live `auth: a 220-block teleport` and `auth: a 1000-block teleport` (the 1000 one into chunks nobody had
  loaded): the player stands on the floor at the destination, truth and client bit for bit, no correction.

---

## 7. World desync: glitching and ghost blocks

### WD-1 Block glitching (place in a denied spot, then climb) — Partial {#wd-1}

- **Players:** HCF's other classic. Place a block inside a claim, where the server cancels it; the client's ghost
  block holds you up for a jump over 2-block walls ([17]: BlockGlitchFix, Grim #1142).
- **Cause:** client prediction against server denial.
- **Today:** `WorldView` predictions are admitted only if a vanilla client would make them
  (`auth/view/PredictionRules.java`). A placement the server denied is not honoured by the truth
  (`honor-denied-predictions: false`), and after a revert a truth box inside a block snaps to the prediction-time
  position (§15.2).
- **Fix:** none in code.
- **Proof (to add):** the actual HCF case. In a cheat-suite row, an honest `FakeClient` places a block that a plugin
  cancels (`BlockPlaceEvent`), predicts it, jumps on it, and tries to climb a 2-high wall. Expected: the truth never
  stands on it, one correction, no climb. (Row 66 covers placing without holding a block, which is a different case.)

### WD-2 Ghost blocks for movement — Solved {#wd-2}

- **Players:** walking on or into blocks that aren't there; "ghost liquids" ([17]; PandaSpigot #344).
- **Today:** `WorldView` is "what this client was sent":
  - persistent sent states;
  - pre-change states pinned until their broadcast is acked;
  - predictions with an ack deadline and the revert snap.

  The simulation reads it, not the live world (`auth/view/WorldView.java`, `Authority.onBlockChanged`).
- **Proof (exists):** the real client's 80 gym traces, bit-exact (doors, trapdoors, floor bits). Cheat rows 66-68
  (no prediction admitted, spam with acks held, a protected break).

### WD-3 Phasing through client-only combat and claim walls — Solved {#wd-3}

- **Players:** walking through combat-tag glass walls with a desync or a forged packet.
- **Today:** fake blocks sent to a player persist in their `WorldView` until replaced, so the truth collides with
  them. A forged correction accept is now a protocol violation, and `resync()` keeps the sent states
  (`AuthPlayer.resync`, cheat row 32 fixed in the tree).
- **Proof:** cheat row 32 (the fake wall holds) on the next run (§13).

### WD-4 Ghost hand: interacting through walls — Partial {#wd-4}

- **Players:** opening doors, chests and gates through walls; freecam door breaks ([17]: Grim #1800). In HCF this is
  a raiding cheat.
- **Cause:** client. The server checks interaction distance, not line of sight.
- **Today:** predicted block changes must be on the client's crosshair (`PredictionRules`). Other interactions and
  dig progress use Paper's checks (`auth-netcode.md` "Gaps").
- **Fix (netcode):** validate every `UseItemOn` and `START_DESTROY_BLOCK` target with `PickRay`'s block pass, from the
  truth eye and the window's rotation, on the attacker's `WorldView`. The client can only touch the block its pick
  returns. Refuse others (enforce), count them.
- **Proof (to add):** cheat rows "open a chest behind a wall" and "break a door through a wall" (refused), with honest
  controls; zero refusals across the rig's 100 scenarios.

---

## 8. Seeing other players

### VI-1 The 26.3 interpolation stutter (MC-311974) — Partial {#vi-1}

- **Players:** opponents stutter and freeze on 26.3 (MC-311974, MC-311976). RevivalPaper production moved back to
  26.2 because of it (README).
- **Cause:** client. 26.3's stepped interpolation runs dry with vanilla's every-2-ticks cadence.
- **Today:** K2 paths: one 2-tick stepped move per simulated client tick, a keep-alive nudge, and pre-roll
  (`auth/replicate/PathFeed.java`, `auth/PathWiring.java`, patch 1014). They apply only within
  `replication.exact-radius` 24 blocks. Beyond that is vanilla cadence (freezes possible), which covers bow, rod and
  pearl aim at range.
- **Fix (netcode):** extend K2 to every tracked player the viewer could aim at with a ranged item: the viewer holds a
  bow, rod, pearl or snowball, and the target is within 64 blocks. Or raise `exact-radius` to 48, after SV-1 measures
  the cost.
- **Proof:**
  - Exists: rig, 0 freezes in 2,865 moving ticks within 24 blocks.
  - To add: `view_target_strafe` at 30 and 45 blocks, 0 freezes.

### VI-2 Opponents are drawn behind (interpolation delay) — Open [limit] {#vi-2}

- **Players:** "I'm hitting where he was" ([18]; MC-302347).
- **Cause:** client. The 26.3 client shows remote players about 1.5 steps behind the newest data. Measured: a hit's
  rewind is about RTT + 110 ms.
- **Today:** lag compensation judges against exactly that display (HR-1), so hits are fair. The visual lag stays.
  K = 2 is the smallest queue that never runs dry under jitter (report 03).
- **Fix:** none on a vanilla client (§12).
- **Proof:** none (inherent). HR-2's histogram documents it.

### VI-3 Invisible or teleporting opponents after a desync — Partial {#vi-3}

- **Players:** invisible players, opponents teleporting, "desynced positions".
- **Cause:** late or force-applied packets, or big jumps.
- **Today:**
  - An inexact mirror is re-paired (HR-5).
  - A jump of more than 48 blocks re-spawns the target on that viewer (`respawn-distance`).
  - Tracking range is vanilla's.
- **Fix (netcode):** none in mechanism. Add metrics: re-pairs per player-hour and `UNKNOWN INEXACT_TARGET` verdicts per
  1,000 attacks, in `/revival auth inspect` and `debug.hud`.
- **Proof (to add):** playtest budgets: ≤ 1 re-pair per player-hour in fights, and ≤ 1 inexact verdict per 1,000
  attacks.

### VI-4 ESP, tracers, nametags through walls — Partial {#vi-4}

- **Players:** ESP cheats.
- **Today:** visibility culling (`auth/visibility/*`, patch 1015). Players with no line of sight beyond 8 blocks are
  not sent, with RTT-expanded boxes. It is off by default (`visibility.enabled: false`). It costs up to 81 rays per
  hidden pair. Sounds and particles still leak positions.
- **Fix (config, netcode):** decide per mode. Measure pop-in (the extra time before a peeking player appears, against
  culling off) before turning it on for HCF.
- **Proof:**
  - Exists: live, a player behind a wall is removed and re-spawned when the wall goes.
  - To add: a rig peek test with a strafe around a corner at 6 b/s. The appearance delay against culling off is ≤ 1
    tick at p99.

---

## 9. Movement cheats and corrections

### MV-1 Fly, speed, step, spider, jesus, phase, no-fall, no-slow — Solved {#mv-1}

- **Today:** authoritative movement. A bit-exact port of the 26.3 client's movement tick runs every client tick from
  the client's own inputs, and claims are compared, never trusted (`auth/sim/MovementSim.java` and the rest,
  `auth/reconcile/Reconciler.java`).
- **Proof (exists):**
  - cheat rows 1-12;
  - the real client: 5,331/5,331 claims exact, 0 corrections in 100 scenarios;
  - offline: 0 differences in 4,800,000 fuzzed ticks against vanilla's own code, and 80/80 golden client traces.

### MV-2 Timer, blink, lag switch — Solved {#mv-2}

- **Today:** `auth/net/TickClock.java` (a Grim-style balance timer on the ack clock, bank 10, a 1 s queue cap). A
  player who stops sending ticks freezes for everyone. A window held open past 512 packets is a protocol violation
  (cheat row 28, fixed in the tree).
- **Proof (exists):** cheat rows 24-27, plus row 28 on the next run.

### MV-3 Fake crits and ground spoofing — Partial {#mv-3}

- **Players:** packet crits (tiny hops claimed as falls) and ground spoof.
- **Today:** impossible by design. `onGround` comes from the simulation, and `fallDistance` follows server rules fed
  by simulated deltas (§15.5). `LegacyAttack.canCriticalAttack` reads them.
- **Proof:**
  - Exists: row 10 (no-fall: 18 onGround lies counted, damage equal to an honest fall), and rig `attack_jump_crits`
    (honest crits).
  - To add: a packet-crit cheat row (claims of +0.0625/−0.0625 hops before each attack). Expected: no crit.

### MV-4 Rubber-banding of honest players — Solved {#mv-4}

- **Players:** setbacks and lagbacks from anticheats ([25]: Grim's setbacks need tuning; corrections that snap you
  back in time).
- **Today:**
  - Corrections are relative position + velocity, forecast to the client's apply tick, and absolute only when they
    would land inside a block.
  - The accept is checked as a claim.
  - The correction loop after a cheat is fixed (`AuthPlayer`, `Simulation.correction`).
- **Proof (exists):** 0 corrections in 100 real-client scenarios on localhost. Latency is NW-1.

### MV-5 Exempt states trust the client — Partial {#mv-5}

- **Players:** none, unless a cheater stands next to a piston, a shulker, a boat or a geyser.
- **Cause:** sim scope. These movers need client state the server can't mirror exactly.
- **Today:** ticks near pistons, shulker boxes, solid entities, geysers and rod pulls, and riptide ticks, are exempt:
  the claim goes through vanilla's checks and the simulation re-seeds after (`auth/ExternalMovers.java`). Vehicles are
  denied by default (`vehicles: deny`); under `vanilla` they are client-trusted. The design's bounded band (push ≤ 0.05
  per pusher per tick) isn't built.
- **Fix (sim, netcode):** build the band. During `EXTERNAL_MOVER` ticks, accept the claim only within the simulated
  truth ± the pushers' maximum push. Count exempt ticks per reason in `inspect`.
- **Proof (to add):** cheat rows: fly and speed while touching a piston, a shulker and a boat. Expected: bounded by
  the band, corrected past it.

### MV-6 Teleport accept spoofing and forged accepts — Solved {#mv-6}

- **Today:** under enforce every teleport accept is judged as a claim, and vanilla never takes its coordinates
  (`AuthPlayer.onTeleportAccept`). A teleport never accepted is a protocol violation past the deadline. An accept for
  a correction never sent is a protocol violation (cheat rows 30-32, fixed in the tree).
- **Proof:** rows 30-33 on the next run (§13).

---

## 10. Network quality

### NW-1 Real ping, jitter and packet loss — Partial {#nw-1}

- **Players:** Wi-Fi and long-distance players: rubber-banding, delayed knocks, freezes ([24]; "Grim is sensitive to
  poor player networks", [25]).
- **Cause:** network. TCP head-of-line blocking turns loss into bursts.
- **Today:** the design allows for it: ack deadlines with `ack-jitter-ticks` 4, a timer bank of 10 with a 1 s queue
  cap, the forecast of corrections to the apply tick (`n̂ = round(rttBase/50)`). But the real client ran only on
  localhost (RTT 0.6-15 ms, no jitter or loss: `README-enforce.md` "Limits"). The forecast with `n̂ > 1`, forced
  entries under jitter, and freezes are unexercised.
- **Fix (netcode):** a latency harness in the rig.
  - A per-direction delay and jitter proxy, plus dummynet for loss.
  - The 100 scenarios at 50, 100, 150 and 250 ms; ±10 and ±30 ms jitter; 0.5% and 2% loss.
  - Tune `ack-jitter-ticks` and the correction forecast from the measured spread.
- **Proof (to add), budgets:**
  - 0 corrections and 0 forced entries at ≤ 150 ms with ±30 ms jitter;
  - ≤ 1 correction per minute at 2% loss;
  - every client-picked attack `VALID` (with HR-2).

### NW-2 An honest lag spike kicks the player — Open {#nw-2}

- **Players:** a 5-second Wi-Fi hiccup in an HCF fight disconnects you, and the combat logger dies.
- **Cause:** policy. Past `max-unacked-ms` (1,500) the player is frozen (windows refused). At 3× (4.5 s) they are
  kicked: "Timed out (no transaction replies)". Paper's keepalive timeout is 30 s
  (`paper.playerconnection.keepalive`).
- **Today:** as above (`Transactions`, `AuthPlayer.onServerTick`; cheat row 14).
- **Fix (netcode, config):** freeze at `max-unacked-ms`, but kick only at `max-frozen-ms` (default 15,000), or at
  Paper's keepalive timeout. A frozen player is already harmless: it doesn't move, its knocks are force-applied,
  and it stays hittable where it froze. Keep the immediate kick for protocol violations (forged and out-of-order
  pongs).
- **Proof (to add):** rig with a 6 s full network block mid-walk. The client stays connected, is frozen on others'
  screens, resumes with ≤ 1 correction, and its windows run in order.

### NW-3 Tab ping doesn't match what authority judges with — Open {#nw-3}

- **Players:** arguments about who is "really" lagging; ping spoofing as an excuse.
- **Cause:** server. Tab latency comes from keepalives. Authority uses ack RTT (`rttBase`, the p10 of 256 samples,
  or of the last 32 when that is higher).
- **Fix (netcode):** report the ack RTT (a smoothed median) as the player's latency to the tab list and the API.
  Show `rttBase` and the rewind p50 in `debug.hud`.
- **Proof (to add):** unit test. The tab latency equals the median ack RTT within 5 ms over a simulated stream.

---

## 11. Server performance and server-side bugs

### SV-1 Authority in big fights — Partial {#sv-1}

- **Players:** TPS drops in big fights, "entity lag", "chunk lag" ([23]: PandaSpigot's tracker and flush work; [24]).
- **Cause:** server load.
  - Movement simulation for everyone.
  - Mirrors per viewer and target.
  - K2 paths: one move per tick per pair within 24 blocks, against vanilla's one every 2 ticks.
  - One ping bundle per connection per tick.
  - Visibility rays.
  - Death drops, potions and arrows.
- **Today:** micro-benchmarks only:
  - simulation 5.9-7.6 µs per player tick with 0 allocation (`auth-sim.md` §5);
  - `MirrorBook` 66 ns per target per viewer tick;
  - `HitValidator` 0.6 µs per attack among 20;
  - `PathFeed` 46 ns per pair per tick (`auth-combat.md` §3.4).

  No whole-server run has more than 2 players under authority.
- **Fix (netcode):** a stress harness.
  - 50, 100 and 200 `FakeClient`s under enforce in one 30 × 30 arena (everyone within `exact-radius`).
  - Scripted fights: strafing, sprint hits, pots, pearls, deaths with full inventories.
  - Plus a socket variant for real bytes.
  - Record MSPT p50/p99, authority's share of MSPT (per-subsystem timers in `AuthStats`), outbound bytes/s per
    connection, pongs per tick, and the GC allocation rate.
  - A/B: `exact-radius` 24 against 0.
- **Proof (to add), budgets:** MSPT p99 < 25 ms at 100 players; authority ≤ 15% of MSPT; bandwidth ≤ 2× vanilla
  cadence; 0 timer refusals on honest fake clients.

### SV-2 Server lag eats hits — Partial {#sv-2}

- **Players:** "server lag eats hits", combos that break on a TPS drop.
- **Cause:** server clock. During a stall, clients keep ticking. Their windows queue and are judged correctly, against
  the client tick each click was made in. But damage windows only count server ticks, so clicks from queued windows
  meet windows that should already have opened by real time. The knock then goes out late.
- **Today:** verdicts are exact and the timer allows bursts (bank 10), so hits are refused for the right reason, not
  lost to bad geometry. Damage cadence is still server-tick-based (`DamageWindows`, `damageCooldownTime`).
- **Fix (combat):** for legacy melee between players, keep each victim's melee window in wall-clock milliseconds
  (`openAtMs = hitMs + windowTicks × 50`). Check it in `revivalHurtServer` in place of the tick counter, so a stall
  doesn't stretch immunity. Leave vanilla's counter for the hurt animation and plugins. Neue's buffer (HR-6) covers
  short stalls too.
- **Proof (to add):** lag-injection live test. A plugin sleeps the main thread 200 ms every 40 ticks while a
  `FakeClient` combos at 12 CPS. Landed fresh hits per second ≥ 1.95, against 2.00 without lag. Today, measure the
  loss first.

### SV-3 Leaf and Paper settings that drop or delay knockback — Partial {#sv-3}

- **Today:** authority refuses to start with `async-entity-tracker` or `parallel-world-ticking`.
  `reduce-entity-motion-packets` (can drop knockback), `OptimizeNonFlushPacketSending` and `use-legacy-tracker-ticking`
  are only documented (`leaf-defaults.md` §1b-1c).
- **Fix (config):** refuse (stay off, log once) with `reduce-entity-motion-packets` or `reduce-entity-move-packets`
  on. Warn on `OptimizeNonFlushPacketSending`.
- **Proof (to add):** unit test of the refusal; a live run with each flag on shows `/revival auth status` refusing.

### SV-4 An exception in authority code kicks the player — Partial {#sv-4}

- **Players:** "Internal server error" in a fight.
- **Cause:** a port exception outside the guarded `sim.tick` (`README-enforce.md` root cause 3).
- **Today:** every simulation and combat call in a window falls back to the client's claim for that tick and re-seeds
  (`auth-netcode.md` "Enforce extras").
- **Fix:** none in code.
- **Proof (to add):** fault injection. A test hook throws in each guarded path (reconcile, belief, correction, apply,
  judge, drain) once per 100 windows over a 5-minute fight: 0 kicks, re-seeds counted.

### SV-5 Plugins and anticheats that write physics packets around the choke point — Partial {#sv-5}

- **Players:** rubber-banding when another plugin teleports or knocks players.
- **Cause:** PacketEvents, ProtocolLib or Grim writing velocity, teleports or blocks directly.
- **Today:** `auth/net/BypassAuditor.java` notices them. Blocks get upper-bound timing; teleports and velocity mark
  the player for resync. GrimAC's movement, velocity and setback handling must be off for players under authority.
- **Fix (config, core):** route core's knockback and teleports through Bukkit (the choke point). Document the Grim
  config.
- **Proof (to add):** live. A plugin sends `SetEntityMotion` through PacketEvents: counted, resynced, ≤ 1 correction.

### SV-6 Stock movement checks fight the truth — Partial {#sv-6}

- **Players:** a player knocked off a ledge gets "moved too quickly" setbacks in a loop (`README-enforce.md` root
  cause 4).
- **Today:** patch 1012's `revivalApplyTruth` skips Paper's moved-too-quickly, moved-wrongly and clipped-into-block
  checks for the truth. The last stock-checks run (`stock2`) predates that fix.
- **Proof (to add):** re-run `enforce.py --stock-checks`. Expected: 0 setbacks on the second player in
  `attack_sprint_hits`.

---

## 12. Clients, mods and eligibility

### CL-1 Lunar's Apollo combat options and PvP-client mods against the exact grammar — Open {#cl-1}

- **Players:** most HCF players use Lunar. HCFR enabled Lunar's combat module on Arena (hcfr-core #34: "can not swing
  and pot or hitreg feels off").
- **Cause:** client. Apollo's combat module (server-controlled) has `DISABLE_MISS_PENALTY`,
  `DISABLE_BLOCK_MISS_PENALTY` and `ALLOW_DIG_AND_USE` ("as in 1.7", [20]). A client running them sends packet
  sequences a vanilla 26.3 client never sends. The authority's input grammar and use prediction assume vanilla:
  - `ATTACK_AFTER_CLOSE`;
  - the use-start rules in `ClientItems` and `PredictionRules` (a vanilla client can't start using while
    destroying).

  So a Lunar player may have legit attacks dropped, or be corrected for a use the model said couldn't start. Not
  verified either way.
- **Today:** no brand or Apollo awareness in `auth`.
- **Fix (netcode, config, core):**
  1. Verify what each Apollo option changes in the packet stream (a Lunar session with `debug.log`).
  2. Make the grammar and use prediction a function of the client's declared rule set. Read the brand and Apollo's
     handshake, and take the options from what core itself sent through Apollo.
  3. Decide the fairness policy. Options differ between Lunar and vanilla players, so either keep Apollo combat off
     on authoritative servers, or accept the asymmetry openly.
- **Proof (to add):** a Fabric stand-in mod that reproduces the Apollo options, driven by the rig: 0 grammar
  violations, 0 corrections across the 100 scenarios. Then a 30-minute manual Lunar session: `grammar-violations`
  and corrections both 0.

### CL-2 Non-26.3 clients bypass authority — Partial {#cl-2}

- **Players:** ViaVersion-translated clients (1.8 via ViaRewind) and Geyser players play on vanilla handling: Paper's
  lenient reach, client-trusted movement, and different hit rules (1.8's 0.1 pick margin).
- **Today:** `auth/Eligibility.java` turns authority off for them. Fail-open by design.
- **Fix (config):** `authoritative.ineligible: allow | deny | separate`. In enforce worlds, deny, or route those
  players to non-authoritative arenas.
- **Proof (to add):** a join test with a Via-translated client under each policy.

### CL-3 Other anticheats false-flag legit 1.7 play — Partial {#cl-3}

- **Players:** bans for w-tapping, fast clicking or sprint behaviour ([8]: Grim #2818, #2866, WTapA).
- **Today:** `grimac-checklist.md` lists the risky checks, but it was never run with a real client. Under enforce,
  authority supersedes Grim's movement, velocity and reach.
- **Fix (config):** run the checklist. Turn off the Grim checks authority supersedes for players under authority. Keep
  Grim only for what authority doesn't do, if anything.
- **Proof (to add):** the checklist's pass criteria, recorded with Grim's version and config.

---

## 13. What a vanilla client can't fix, and what a light client mod would change

The design's non-goal stands: no mod is required (`authoritative.md` §1). This section says what is lost by that.

A mod is never a security boundary: anything it does, a cheat client can fake. So a mod can only improve how the game
feels for honest players and widen what the server's rules can express. The authority must keep judging every packet
as it does now.

| Limit | Why the server can't | What a light mod could do | Already in the ecosystem |
|---|---|---|---|
| Remote players drawn RTT + about 110 ms behind (VI-2) | Interpolation is client code | Render with K=1 or extrapolate; the server's mirror must then port the mod's renderer exactly | Extrapolate mods [18] |
| Knock applied on arrival (KB-3) | The client applies velocity when it drains it | Client-side rollback: re-run its own last N ticks with the knock at the tick the server stamped | None known |
| Miss penalty, dropped click after a screen (HR-9) | `missTime` is client state | Disable it | Apollo `DISABLE_MISS_PENALTY` [20], HitDelayFix [1] |
| 1.7 pick margin, crouch box (HR-7, HR-8) | Pick and box are client code (partly reachable through `attack_range` and pose rewrites) | 1.7 boxes and eye heights | Animatium `pick_inflation`, `old_sneak_height` [1] |
| Clicks quantized per tick (IN-6) | Input is consumed in the client tick | Sub-tick timestamps for feel; the server can't trust them | Click Between Ticks [11] |
| Hit feedback after RTT (ghost-hit feel) | Damage on others is server-decided | Predict the hit flash and sound, then roll back if refused | DiolezHitregistry [22] |
| Neue body hitboxes (`neue-combat.md` §9) | The client picks the box | Pick model parts, mirrored by the server | Combo's own client |
| 1.7 movement physics | The authority ports 26.3's client movement, by design | Not without a full client | none |

The realistic lever is Apollo: Lunar already exposes server-controlled combat options to most of this crowd (CL-1).

## 14. Test backlog (the proofs to add)

Grouped by harness. Each line points at its item.

**Unit** (`./gradlew :leaf-server:runRevivalTests`):

- rewind cap from p90 RTT (HR-2);
- one-tick slot-switch rule (IN-5);
- `ClickBucketTest`, `ClickBufferTest`, `NeueShapeTest`, `CadenceWatchTest` (HR-6, IN-1, IN-3, KB-9);
- 1.7 throw motion and splash goldens (PR-3, PR-4);
- tab latency from ack RTT (NW-3);
- Leaf-flag refusal (SV-3).

**Cheat suite** (`AuthCheatScenarios`). First re-run it and refresh the table in `auth-cheat-suite.md`: the table
is from 21:45, and fixes for rows 28, 30-32, 53, 57, 60, 62 and 69-70 landed after it. New rows:

- toggled fake lag (HR-2, HR-3);
- keep-sprint and sprint-packet spoofing (SP-3);
- packet crits (MV-3);
- the HCF block glitch with a cancelled placement (WD-1);
- ghost-hand chest and door (WD-4);
- one-tick pot switch (IN-5);
- exempt-band fly and speed by a piston, a shulker and a boat (MV-5);
- PacketEvents velocity bypass (SV-5).

**Live** (`AuthLiveScenarios`):

- asymmetric-latency trade order (HR-10);
- damage-window phase (HR-11);
- knock direction at 100 ms (KB-4);
- rod and bow at 0/100/200 ms (PR-1);
- pearl glitch suite (PR-7);
- potion spawn origin (PR-5);
- lag injection (SV-2);
- fault injection (SV-4).

**Real client** (`~/Developer/tools/auth-rig/enforce`):

- the latency, jitter and loss harness over all 100 scenarios (NW-1, HR-2);
- a 6 s network block (NW-2);
- a dedicated far teleport (PR-8);
- the K2 view at 30 and 45 blocks (VI-1);
- a sneaking target's box (HR-8);
- `ATTACK_RANGE` picks (HR-7);
- the peek test (VI-4);
- a `--stock-checks` re-run (SV-6);
- the Apollo stand-in mod (CL-1);
- `whiff_then_click` reference counts (HR-9).

**Load:** the 50/100/200-player stress harness with MSPT, bytes and GC budgets (SV-1).

**Playtest** (with `debug.hud`):

- rewind and knock-apply-lag histograms per ping band (HR-2, KB-3);
- re-pairs and inexact verdicts (VI-3);
- damage-window split A/B (PR-6);
- real click and rotation data to calibrate IN-3 and IN-4;
- the GrimAC checklist (CL-3);
- a Lunar session (CL-1).

## Sources

Community (claims tagged in the research as forum summaries were not read in full; numbers here come from code where
code exists):

1. Animatium (1.7 visuals and mechanics toggles: miss penalty, pick inflation, sneak height):
   https://github.com/Legacy-Visuals-Project/Animatium. HitDelayFix petition:
   https://www.change.org/p/lunar-client-add-the-hitdelayfix-back-in-lunar-and-badlion-client
2. GrimAC reach check (0.1 inflation for 1.7/1.8 clients, cancel threshold):
   https://raw.githubusercontent.com/GrimAnticheat/Grim/2.0/common/src/main/java/ac/grim/grimac/checks/impl/combat/Reach.java
3. PandaSpigot GH-244 hit registration fix: https://github.com/hpfxd/PandaSpigot/issues/244. Fork claims about ghost
   hits and rods: https://builtbybit.com/threads/%F0%9F%A6%8A-foxspigot-2-0-%E2%80%94-now-available.732125/
4. KnockbackSync: https://github.com/CASELOAD7000/knockback-sync (hcfr-core #109 asked for it).
5. Loka forum on knockback sync and ping: https://forums.lokamc.com/threads/loka-should-add-the-knockback-synch-plugin.9247/page-2.
   Ping floor: https://forums.lokamc.com/threads/ping-floor.3425/page-2
6. Hypixel threads: https://hypixel.net/threads/explaining-knockback.1631885/ ,
   https://hypixel.net/threads/issue-knockback-with-lower-ping-players.2131777/
7. KohiKB: https://github.com/MWHunter/KohiKB. Kohi constants gist:
   https://gist.github.com/sathonay/f77a9ab4c15b8372a1dc49b808145997. Badlion KB history:
   https://www.badlion.net/forum/thread/113863
8. GrimAC knockback handler and issues (blink, backtrack, KeepSprint, velocity bypass, 1.7/1.8 false flags):
   https://github.com/GrimAnticheat/Grim/issues/1411 , /1642 , /1382 , /2849 , /2818 , /2866
9. Intave click-pattern docs: https://docs.intave.ac/mechanics/checks-04-clickpatterns.html
10. Debounce: https://www.gloriousgaming.com/blogs/guides-resources/debounce-time-what-it-is-and-how-it-affects-gaming
11. Click Between Ticks: https://www.curseforge.com/minecraft/mc-mods/click-between-ticks
12. Sprint echo and FOV flicker: https://feedback.minecraft.net/hc/en-us/community/posts/44595390237837-Let-s-talk-about-sprinting ,
    MC-4098
13. PandaSpigot blocking stuck: https://github.com/hpfxd/PandaSpigot/issues/452
14. OldCombatMechanics: https://github.com/kernitus/BukkitOldCombatMechanics (issues #69 hitbox, #976 shield
    visual). PandaSpigot rod request: https://github.com/hpfxd/PandaSpigot/issues/339
15. Minecraft Wiki: Splash Potion, Ender Pearl, Fishing Rod, Combat, Java Edition 1.9, Combat Tests
    (https://minecraft.wiki/).
16. Pearl fixes: https://modrinth.com/mod/quickpearl ,
    https://builtbybit.com/resources/better-pearls-fix-ender-pearl-exploit.104799/
17. Block glitch: https://modrinth.com/plugin/blockglitchfix , https://github.com/GrimAnticheat/Grim/issues/1142 ,
    https://github.com/GrimAnticheat/Grim/issues/1800
18. Interpolation delay and extrapolation: https://github.com/CCPCT/Extrapolate
19. Mouse polling: https://www.mado-hosting.com/minecraft-mods/raw-input-buffer , MC-124743
20. Lunar Apollo combat module: https://lunarclient.dev/apollo/developers/modules/combat
21. Mojira: MC-311974, MC-311976 (26.3 stutter), MC-302347 (visual delay), MC-297196 (knockback delay).
22. Client-side hit prediction: https://modrinth.com/project/DsL0pTWX
23. PandaSpigot (tracker flush consolidation, tick loop): https://github.com/hpfxd/PandaSpigot
24. Ping spikes on chunk loads: https://github.com/Tuinity/Tuinity/issues/34
25. Anticheat review (custom knockback breaks default checks; network sensitivity):
    https://mineguard.pro/en/blog/best-minecraft-anticheats-2026

Repository and project:

- RevivalPaper PRs #3-#19 (issues are disabled on `hcfrevival/RevivalPaper`). hcfr-core issues #13, #34, #53, #54
  (true 1.7 crits, "not fixing"; `LegacyAttack.canCriticalAttack` already follows 1.7.10), #109, #115, #321, #354.
- `docs/revival/auth-research/02` (client clicks and screens), `03` (interpolation), `06` (prior art).
- Real-client evidence: `~/Developer/tools/auth-rig/README-enforce.md`, `enforce/runs/full3/probe.jsonl` (rewind
  ages).
