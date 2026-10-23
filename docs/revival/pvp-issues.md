# 1.7 PvP issues: the checklist

Status: 2026-10-23, branch `revival/authoritative` (the classic audit, `auth-classic-audit.md`: the jump-reset alert,
the two parity switches as the user set them, the stale self-echo fixes, the keep-sprint and never-loaded holes closed;
then the ghost-hand rule, WD-4, a code review's fixes, NW-1's one-correction recovery from a 2 s freeze, and SV-2's
window clock). The live suites are green on `classic-capped` and `classic`, both trackers: on `classic-capped` cheat
suite 133/133, cheat suite 2 56/56. The
real client (run `classic6`) is exact with every hit `VALID` at localhost, 150, 250 and 300 ms. This is a list of every problem 1.7 PvP
is known to have, checked against the authoritative RevivalPaper server. It draws on the community's own record
(sources at the end) and on this repo's design, code, tests and real-client runs.

The goal it measures against: a 1.7-PvP server for vanilla 26.3 clients that is fast and exact, and that removes as
many of 1.7's problems as a server can. The current focus is classic 1.7 under authority.

Rule sets (`legacy-combat.rules`, README "Combat rules"): `classic` is plain 1.7 and the default. `classic-capped` is
classic plus the 12 CPS cap and the 0.15 s click buffer; it is the NAS world's. `neue` (tipper, DI, parry, the cap and
the buffer) is built and parked. An item that a rule set fixes says which.

Read with: `authoritative.md` (design, §15 binding), `auth-netcode.md`, `auth-combat.md`, `auth-sim.md`,
`auth-classic-audit.md`, `auth-review-2.md`, `auth-cheat-suite.md` and `auth-cheat-suite-2.md`, `auth-torture.md` (a
real client through a bad network), `auth-load.md` (bots at scale), `projectiles.md`, `client-compat.md`,
`neue-combat.md`, `README.md` (combat mechanics and `revival.yml`). Code paths are under
`leaf-server/src/main/java/gg/hcfrevival/revival/` unless they say otherwise.

Evidence is named the way it runs: unit tests by class (`./gradlew :leaf-server:runRevivalTests`), live and cheat-suite
rows by the name the suite prints (`AuthLiveScenarios`, `AuthCheatScenarios`, `AuthCheatScenarios2`,
`NeueLiveScenarios`, `RevivalLiveTests`), real-client runs by the rig's run name. "Real client, 2026-10-22, run X" is
a run of the real vanilla 26.3 client (the enforce-neue rig) under enforce on `classic-capped`, through the torture
rig's latency proxy, not yet written up in the repo. Conditions read like `r150j30`: 150 ms RTT, ±30 ms jitter.

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
- **[limit]**: a vanilla 26.3 client makes a full fix impossible. §13 says what a light client mod would change.
- **Accepted**: the user chose to keep it (1.7's own behaviour, or a client limit with the light mod declined).

Owners: **netcode** (`auth`, `auth.net`, `auth.view`, `auth.combat`, `auth.replicate`), **combat**
(`combat`, the knockback and damage rules), **sim** (`auth.sim`, the movement port), **config** (defaults and
policy in `revival.yml` and server configs), **core** (the HCFR plugins, outside this repo).

## Summary

| | Count |
|---|---|
| Solved | 40 |
| Partial | 21 |
| Accepted | 8 (5 of them [limit]) |
| Open | 0 |
| Items | 69 |

Since the last refresh (2026-10-21, `a7a84a06`): 16 items moved to Solved (HR-2, HR-6, IN-1, IN-2, IN-5, PR-3, NW-2,
SV-6; SP-3, MV-3 and WD-1 once their cheat rows ran, one of which found a hole; WD-4 (the ghost-hand rule), SV-3 (Leaf's
packet reductions refused), SV-4 (faults injected into authority code) and SV-2 (the melee window on the clicks' clock
through a server stall) on 2026-10-23; and HR-11, which was never an issue under authority) and 6 from Open to Partial
(KB-9, PR-1, PR-4, PR-7, CL-1; SP-4, the tap-lock alerts, on 2026-10-23). The classic audit added 3 items, all Partial:
BL-3, SP-5 and KB-10.

Solved today, in short:

- Movement cheats of every kind (fly, speed, phase, timer, blink, no-fall, no-slow, jesus, spider, step).
- Reach, hitbox, through-wall and no-aim killaura hits, including a made-up eye offset (classic audit #1).
- Anti-knockback and pong games: ignored, withheld, delayed, batched or selectively skipped knocks, and stalls used to
  void ack deadlines.
- Exact hit registration at real ping: a real client's hits are VALID and its pick equals the server's verdict, up to
  300 ms and ±30 ms of jitter.
- Consistent 1.7 knockback.
- Sprint and w-tap desync, spoofed sprint packets, and the stale sprint syncs that broke block-hitting at 250-300 ms.
- Keep-sprint, packet crits and HCF's block glitch (a cancelled placement climbed over a wall).
- Sword blocking on a modern client.
- The CPS race and clicks lost into the damage window, in `classic-capped` (the NAS world) and `neue`.
- Server lag eating hits: through a server stall, fresh hits keep 1.7's 10-tick cadence, none late and none early.
- One-tick pot swaps, off-hand pots behind a sword, silent aim on throws, and inventory-move.
- 1.7 throws and flight.
- The 26.3 interpolation stutter near the fight.
- Ghost blocks and phasing through fake walls.
- Ghost hand: chests, doors and digs through walls.
- A bug in authority code: it falls back to the client's claim for one tick and never kicks (191 injected faults in a
  fight).
- A stalled player: frozen, never kicked, still hittable where it stopped.
- Far teleports and pearls into chunks the client lacks.
- Paper's movement checks fighting the truth.

The biggest open risks are now scale and clients, not one player's exactness. A real 26.3 client is exact up to 300 ms
with ±30 ms of jitter, and 300 ms Wi-Fi stalls cost it no correction. 200 players spread over a map run exact at MSPT
p99 8.6 ms, and 100 recover from a 2.3 s server stall in 7 s (SV-1). Not shown yet: 200 in one fight, Lunar with
HCFR's Apollo combat options against the exact input grammar (CL-1). Two attackers on one victim, and lethal ties,
still go to the lower uplink (HR-10). The user decided the two 1.7-parity switches on 2026-10-22:
`stop-sprint-on-block` on in the NAS world (BL-3), `hide-cooldown-on-items` off (SP-5).

## 0. Ranked open and partial items

Ranked by player impact for classic 1.7 under authority: how many players it touches, how often, and how badly.
Solved items are left out.

| # | Item | Status | Owner | In one line |
|---|---|---|---|---|
| 1 | [SV-1](#sv-1) | Partial | netcode, config | 200 players on one map run exact at MSPT p99 8.6 ms, 50 at 125 ms are exact, and 100 heal from a 2.3 s stall in 7 s. 200 in one fight needs a second machine to measure. |
| 2 | [CL-1](#cl-1) | Partial | netcode, config, core | Most HCF players use Lunar. Frame-time packets no longer kick, but no rule reads HCFR's Apollo combat options and no recorded Lunar session has checked them against the grammar. |
| 3 | [NW-1](#nw-1) | Partial | netcode | Steady links, Wi-Fi hiccups and 0.5% loss are clean on a real client, and a 2 s freeze recovers with one correction; 2% loss at 150 ms still costs corrections and hits. |
| 4 | [PR-7](#pr-7) | Partial | combat, core | Pearls land only where a standing box fits and stop at fake walls; no running-server glitch suite, and core isn't told of a refund. |
| 5 | [HR-3](#hr-3) | Partial | netcode | A low-ping cheater has about the 150 ms slack plus its jitter of free backtrack inside its cap; using it on every hit raises `rewind-stretch`, on thresholds from simulated and torture-rig play only. |
| 6 | [KB-4](#kb-4) | Partial | combat, netcode | The base knock points between server positions, not along what the attacker saw; unmeasured at ping. |
| 7 | [VI-1](#vi-1) | Partial | netcode, config | Paths are exact within 24 blocks. `ranged-radius` extends them to 64 for a viewer aiming a bow, rod or thrown item; it is built and off until §15.6 is signed off. |
| 8 | [IN-4](#in-4) | Partial | netcode | Aim assist is measured (yaw GCD), with thresholds never tuned on real mouse data. |
| 9 | [KB-10](#kb-10) | Partial | netcode | Jump-reset macros raise an alert, on thresholds from simulated players only. |
| 10 | [IN-3](#in-3) | Partial | netcode | Autoclickers are capped at 12 CPS, and a 20 CPS lock or any fixed rate is flagged; the thresholds await real click data. |
| 11 | [PR-4](#pr-4) | Partial | combat, core | 1.7's splash falloff is on; core's own pot precision may stack on it, and no pot drill compares them. |
| 12 | [MV-5](#mv-5) | Partial | sim, netcode | Geysers, riptide, `vehicles: vanilla` and solid copies that went inexact still trust the client, within 1 block. |
| 13 | [CL-2](#cl-2) | Partial | config | ViaVersion and Geyser players play client-trusted unless `ineligible: deny`; hcfr-core runs neither today. |
| 14 | [CL-3](#cl-3) | Partial | config | GrimAC false flags on legit 1.7 play; the GrimAC checklist was never run with a real client. |
| 15 | [PR-6](#pr-6) | Partial | config | Split damage windows are built and live-tested but off by default, so fire ticks and arrows still eat sword hits. |
| 16 | [VI-4](#vi-4) | Partial | config, netcode | Anti-ESP is built but off by default, and pop-in when it is on is unmeasured. |
| 17 | [SV-5](#sv-5) | Partial | netcode | Plugins that write velocity or teleports around the choke point get upper-bound timing and corrections. |
| 18 | [VI-3](#vi-3) | Partial | netcode | Invisible or teleporting opponents after a desync are re-paired and counted, with no playtest budget yet. |
| 19 | [HR-7](#hr-7) | Partial | combat, netcode | The 26.3 client picks the bare box, without 1.7's +0.1 margin; spear stabs land under enforce but aren't lag-compensated. |
| 20 | [SP-4](#sp-4) | Partial | netcode | Tick-locked w-tap and block-hit macros raise an alert; macros timed in milliseconds mostly don't, and no real tap data has checked it. |
| 21 | [KB-9](#kb-9) | Partial | combat | Classic keeps 1.7's combo lock by design; DI is built in `neue`, which is parked. |

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
  client's `pick(1.0F)` from the authoritative eye (HR-4). `HitValidator` accepts a hit iff some candidate order lets
  the client return that target (`auth/combat/{RemoteMirror,MirrorBook,PickRay,HitValidator}.java`, wired in
  `auth/CombatWiring.java`, patch 1013). In enforce, player targets need `VALID`.
- **Fix:** nothing more for this item. HR-2 covers the cap.
- **Proof (exists):**
  - `MirrorVerify`: 2,000,000 ticks of the mirror against the real client classes, 0 mismatches.
  - `PickVerify`: 1,000,000 scenes against the client's own `LocalPlayer.pick`, 0 mismatches.
  - Real 26.3 client, run `full3` (`~/Developer/tools/auth-rig/README-enforce.md`): 159/159 attacks `VALID`. On 890 of
    890 ticks the server's would-be verdict equals the client's pick, and the mirrored box equals the client's box bit
    for bit.
  - Torture rig, steady links from 0 to 250 ms with up to ±30 ms jitter (`auth-torture.md`): 3,759 of 3,759 client
    hits `VALID`; the verdict equals the client's pick on 31,775 of 31,775 ticks, the box bit-equal on all.
  - Real client, 2026-10-22, run `classic4-bh` (block-hit and sprint-hit scenarios at direct, r150j30, r250j30 and
    r300j10): pick = verdict 360/360 at direct and 535/535 at each latency.

### HR-2 High-ping players lose hits (the rewind cap) — Solved {#hr-2}

- **Players:** "My hits don't register on this server", but only for players on high ping. This is the most common
  complaint in PvP and the one an authoritative server is supposed to end.
- **Cause:** server (netcode). A hit's rewind is about RTT + 110-135 ms: the K2 display delay, plus the waits for the
  client tick and the server tick (`full3` probe, the torture rig's rewind ages). Until 2026-10-22 the cap was a fixed
  300 ms (`min(max-rewind-ms, rttBase + 300)`), so honest hits failed from about 145 ms RTT. The load harness measured
  14.6% of honest hits refused `REWIND` at about 125 ms (`auth-load.md` finding 3, the 21:32 jar).
- **Today:** Overwatch's policy, one cap for melee, the click buffer's landing and projectiles
  (`CombatWiring.sharedRewindCap`):
  - `min(rttBase, rtt-ceiling-ms 250)` + the display delay measured from the attacker's own mirror + its jitter
    (p90 − p10, at most the ceiling) + `rewind-slack-ms` (150). `max-rewind-ms` (600) is only a sanity bound. Config
    version 3 moves files still holding the old 300/400 to 600.
  - Ages are on the lag-compensation clock (`Authority.clockMillis`), so a server hitch is never charged to the
    attacker (torture F6).
  - Inside the cap the hit is judged on the box the client drew (HR-1). Past it, against the target's truth at the
    capped age (`HitValidator`, `TruthPaths`): a shooter whose ping exceeds the ceiling leads moving players by the
    excess instead of losing every hit.
  - A hit the judge called `VALID` skips Paper's range check on current positions for that target (patch 1018
    `Lag-compensated reach`, `AuthPlayer.judgedReach`; classic audit #7), so chase hits on fast targets land.
- **Fix:** none. Past about 350 ms of steady ping a shooter must lead moving targets: that is the designed limit.
- **Proof (exists):**
  - unit `RewindCapTest` (honest hits fit at every ping; the old flat cap refused them above 190 ms; withheld pongs
    don't widen it), `HitValidatorTest` (past the cap a leading attacker hits the truth at the capped age, and the stale
    box misses);
  - cheat rows "combat: an honest hit at 50 ms of latency (control)", the same at 150 and 250 ms, "combat: an honest hit
    at 300 ms of latency on a target sprinting across" and "combat: an honest hit at 300 ms of latency on a target
    running straight away with Speed IV"; live "neue enforce: past the rewind cap (450 ms)" (aiming where it shows the
    victim misses, leading lands);
  - torture rig: every client hit `VALID` on steady links up to `r250j30` (306/306), the closest 61 ms under the cap;
    `r300j10` 309/309. At `r400j10` the old cap refused 261 of 309 (`REWIND`, run `ceiling`); with the capped-age
    judge, real client, 2026-10-22, run `classic6-400`: 324 of 355 `VALID`, the 31 others on strafing targets (19
    `MISSED`, 12 `OUT_OF_REACH`): past the ceiling the shooter leads by the excess, as designed;
  - real client, run `classic1` (direct, r80j10, r150j30): hits VALID/INVALID/UNKNOWN 283/0/0, 305/0/0, 255/0/0;
  - real client, 2026-10-22, run `classic4-bh`: 37/0/0, 40/0/0, 41/0/0 and 42/0/0 at direct, r150j30, r250j30 and
    r300j10.

### HR-3 Backtrack and fake lag inside the cap; hit "behind cover" — Partial {#hr-3}

- **Players:**
  - Cheaters: "backtrack" and "fake lag" hit you where you were a moment ago ([8]: Grim issues #1411, #1642).
  - Honest victims of lag-compensated servers: "I was already behind the wall".
- **Cause:** netcode trade-off. Favouring the attacker means rewinding the victim. The rewind a player may use should
  be what their connection needs, not a constant.
- **Today:**
  - A cheater who delays its own pongs delays its own knockback too, bounded by the ack deadline (KB-2).
  - A cheater who answers pongs but holds entity packets is judged on the mirror the pongs say it has, so its old-box
    hits are `INVALID`.
  - The cap is the attacker's own (HR-2), sized from its mirror's measured display delay, not from its K: perfectly
    regular pongs that earn K1 buy no extra rewind (cheat suite 2).
  - A 20 ms player's cap is about 20 + display + jitter + 150 ms, so the fake lag it can add freely is about the
    150 ms slack plus its own jitter. Past the cap its hits are judged at the target's truth at the capped age, not at
    the stale box it chose.
  - A stall widens it for a while (found 2026-10-23 in torture `freeze2`): the jitter term is the p90 of the last 256
    acks, so a 2 s stall's late pongs keep it at the ceiling (`rtt-ceiling-ms`, 250 ms more) for about 13 s. A 20 ms
    player who stalls on purpose gets about 520 ms of cap instead of 280 for that time. Measured in the NW-1 runs
    (`nw1-base-*`, `nw1-fix-*`, 2026-10-23): after a 2 s client freeze the raw term reads 730 to 930 ms, against 25 to 100
    before, and is still there when the scenario ends 5.5 s later; the same term moved a K1 viewer back to K2 ("own
    jitter 759 ms"). `rttBase` itself (the last 32 acks' p10) is lifted 100 to 200 ms for under a second.
  - The `rewind-stretch` alert (2026-10-23, `auth.input.RewindWatch`): every valid melee hit's rewind, less the
    attacker's `rttBase` and the display delay its mirror measured, is its excess; `AuthInputAnomalyEvent`
    `rewind-stretch` fires when the median of the last `stats.rewind-stretch-hits` (30) valid hits is at least
    `stats.rewind-stretch-ms` (100). Evidence for staff, never an action; `/revival auth inspect` shows the median, the
    highest and the count.
- **Fix (netcode):** calibrate `rewind-stretch` on recorded fights; make the jitter term forget a stall's drain sooner
  (a §15.7 estimator change, for sign-off). The slack itself is the price of honest tick waits.
- **Proof:**
  - Exists: cheat rows "combat: fake lag (pongs on time, the victim's movement shown 10 ticks late)" and "combat:
    backtrack past the rewind cap (pongs held 500 ms)" (both refused); cheat suite 2 "replication: perfectly regular
    pongs to get K1, then a 500 ms backtrack" (refused).
  - Exists: the honest excess on real clients through the torture proxy (2026-10-23, every tick the client showed
    the target, runs `grid1` and `audit4-marathon`): median 5-61 ms on steady links from 0 to 250 ms with up to 30 ms
    of jitter (p99 under 135, the most 159), 26-76 ms through 300 ms stalls every 4-5 s and 1 s stalls every 15 s.
    Unit `RewindWatchTest`. Live rows "rewind-stretch: a client that holds its packets 150 ms before each swing"
    (flagged) and "honest: hits at the client's own pace (control)" (not flagged), 2026-10-23: the cheat's 16 valid hits at a median 154 ms, the
    control's at 5 ms.
  - To add: false positives per hour on recorded fights, and a victim-side metric: the share of hits taken where the
    victim's own truth was more than 1 block from the attacker's mirrored box, p50 and p99 per ping band in playtests.

### HR-4 Reach, hitbox expand, through-wall hits, killaura without aim — Solved {#hr-4}

- **Players:** 3.1-3.3 "legit reach" debates, hitbox mods, killaura ([2], [8]).
- **Cause:** client cheats. Server checks on stale positions need tolerance, and a 1.7/1.8 client's 0.1 pick margin
  makes cross-version tolerance worse ([2]).
- **Today:** the exact pick (HR-1) leaves no tolerance beyond float epsilon:
  - 26.3's bare box, strict `<` against `ENTITY_INTERACTION_RANGE` from the client's own attribute view, the block
    pass on the client's `WorldView`;
  - `INVALID` reasons `OUT_OF_REACH`, `BLOCKED`, `OCCLUDED`, `MISSED`;
  - an `INEXACT` mirror gives `UNKNOWN`, and in enforce a player target is then not hit (fails safe);
  - the eye is the truth's. It follows the client's claim (within `attack-eye-offset-cap`, 0.5 per axis) only for a
    gap the server caused: entries forced at their deadline, a refused window, a denied break, a revert snap
    (`AuthPlayer.gapCaused`). Every claim since the client was last on the truth must also land within
    `attack-eye-forecast-tolerance` (0.03) of the belief, with no block between the truth and the claim. A made-up gap
    casts from the truth and counts as `eye offsets refused`. Before classic audit #1, any claim within 0.5 per axis
    moved the eye: 0.5-0.7 blocks of free reach, and hits through panes and doors.
- **Fix:** none needed. Killaura that also aims is an input cheat (IN-4).
- **Proof (exists):** cheat rows "combat: reach 3.5", "combat: reach 4.5", "combat: reach 6.0", "combat: through a wall
  at 2.5", "combat: killaura without aim (target 2 blocks to the side)" and "combat: 5 attacks in one window
  (autoclicker)", with the honest controls "combat: honest hit at 2.5 (control)" and "combat: honest hit at 2.5 after
  the cheats (control)"; classic audit #1 rows "combat: claims 0.49 toward a target 3.3 from the true eye before the
  swing", "combat: creeps 0.025 a tick toward a target 3.3 from the true eye, then swings" and "combat: claims 0.49
  through a glass pane it touches, target 1.5 behind the pane".

### HR-5 Honest hits refused after a lag spike or during one's own teleport — Solved {#hr-5}

- **Players:** "after that lag spike I couldn't hit him for the rest of the fight"; "I pearled and hit, and it
  didn't count".
- **Cause:** netcode.
  - Entries force-applied at the ack deadline marked the victim's mirror inexact until the next spawn, and since
    `UNKNOWN` no longer delivers, the victim became unhittable.
  - Attacks during a pending server teleport were never judged.
  - Authority started with the client's rotation unknown (0/0) until the mouse moved, so a player facing anything but
    yaw 0 had its hits judged facing yaw 0 (cheat suite 2 H1).
  - A target whose link stopped became unhittable once its last path step aged past the attacker's cap (review 2
    X2-01).
- **Today:**
  - `PathWiring` re-pairs a target on a viewer whose mirror went inexact (`mayRepair`, at most every
    `repair-interval-ticks` 20), so the mirror is exact again within a few ticks; never while the viewer's own acks lag
    (review 2 PT2-03: a re-pair that is itself forced loops).
  - `AuthPlayer.runAttack` judges attacks while a teleport is pending, from the pre-teleport windows.
  - Activation seeds the stream's rotation from the `ServerPlayer`.
  - Past the cap, a target whose path stopped is judged where it stopped, while its `ServerPlayer` is still there and
    no server teleport is pending (`combat/TruthPaths`, `AuthPlayer.stillAt`).
- **Fix:** none.
- **Proof (exists):** cheat rows "combat: honest hit at 2.5 while a server teleport is pending (control)", "combat: one
  lag spike past the ack deadline -> then an honest hit at 2.5 (control)" and "combat: hit a target whose link stopped
  2 s ago, where it stopped" (`VALID` at 2050 ms past a 202 ms cap); cheat suite 2 "honest: a client facing yaw 180
  that hasn't turned since authority started attacks and strafes" (the attack lands `VALID`, the strafe 17/17 exact).

### HR-6 Clicks into the damage window ("didn't register" mid-combo) — Solved (`classic-capped`, `neue`) {#hr-6}

- **Players:** in a combo, clicks that land while the victim is still immune do nothing. This is the 1.7 "hit delay"
  feel, and the root of the CPS race (IN-1): more clicks means one is more likely to land on the tick the window
  opens.
- **Cause:** server, by 1.7's design. A fresh hit opens a 10-tick window, and a click inside it is dropped unless it
  is harder (overflow). The client can't see the victim's window, and at ping it can't time it.
- **Today:**
  - `classic-capped` and `neue` have the click buffer (`click-buffer-seconds` 0.15, `neue-combat.md` §5): a valid
    click that the window turns away waits up to 3 server ticks and lands on the first tick the window opens, with
    reach re-checked on the attacker's mirror and a line of sight (`combat/NeueCombat`, `combat/NeueState`; the landing
    pass runs first in `AuthPlayer.onServerTick`). One held click per attacker, one attempt.
  - `classic` stays exactly 1.7 (`LivingEntity.revivalHurtServer`, `combat/LegacyDefence.lastHurt`): a click into the
    window is lost.
- **Fix:** none for `classic-capped`, the NAS world. Plain `classic` keeps 1.7's behaviour on purpose.
- **Proof (exists):**
  - unit `ClickBufferTest` (a click 3 ticks before the window opens lands on the opening tick, 4 expires; out of reach
    at the opening is dropped for good; classic holds nothing);
  - live "neue enforce: classic-capped: a click 1 tick before the window opens lands on the opening tick", the same at
    2 and 3 ticks, and "... a click 4 ticks before the window opens expires"; control "neue enforce: classic: a click
    into the window is lost";
  - cheat suite 2 "classic-capped click-buffer: an honest click into the window lands on the opening tick (control)",
    with three cheats on it blocked (pre-loading clicks from out of reach, a target walking out before the window
    opens, held clicks spammed across two targets);
  - real client, run `full1` (rule set `neue`, three conditions): 141 buffered landings, 141 on the opening tick.

### HR-7 1.7's pick margin, and attack-range items — Partial {#hr-7}

- **Players:** 1.7/1.8 clients hit "more easily". 1.7 inflated the target box by 0.1 for picking; 1.9+ removed it
  ([2], [14]: OCM #69).
- **Cause:** client. The 26.3 client picks the bare 0.6 × 1.8 box (`getPickRadius() = 0`).
- **Today:**
  - The box is kept by decision (`neue-combat.md` §1, §9): reach at the edge is about 0.1 shorter per side than 1.7.
  - Spears (2026-10-23, corrected): a vanilla spear has `PIERCING_WEAPON`, so its attack is a stab
    (`Minecraft.startAttack` sends `PlayerAction STAB`, never an `Attack`), which the server runs along its own line
    (`PiercingWeapon.attack`, `getHitEntitiesAlong` from the attacker's truth): it lands under enforce, but on the
    targets' current boxes, not lag-compensated. The input grammar takes a stab as an attack (a stab while charging the
    spear is `attack-while-using`). An `Attack` from an item with `ATTACK_RANGE` and no piercing (a datapack weapon) is
    judged on the client's own attack-range pick (`PickRay.castAttackRange`, `AttackRange.getClosesetHit`: the head's
    last-tick yaw, the reach from the minimum to the maximum plus the known movement, the margin), falling back to the
    usual pick as the client does; before, it was `UNKNOWN UNSUPPORTED` and never landed on a player.
- **Fix (combat, netcode):**
  - Option A (keep): document that legacy reach is measured to the bare box.
  - Option B (restore): add `attack_range { max_reach: 3.0, hitbox_margin: 0.1 }` to the client decoration of legacy
    weapons (`LegacySwordBlocking.toClient`, beside the edible-sword `consumable`). The client then picks through
    `AttackRange.getClosesetHit` → `ProjectileUtil.getHitEntitiesAlong`. That path differs from 1.7 in two ways:
    - it adds `max(0, knownMovement · look)` to the reach (sprinting at someone reaches about 0.28 further);
    - it uses the head look angle and the nearest hit location.

    So B is "1.7 margin plus a lunge", and `PickRay` ports that path now (`castAttackRange`).
  - Spears' stabs: lag-compensate them as attacks are (the hits the attacker's client saw along its line, on the
    mirrors), which means taking `STAB` off vanilla's server-side line.
- **Proof:**
  - Exists: unit `PickRayTest` (the attack-range pick: past a sword's reach, under the minimum reach the usual pick, the
    margin, the nearest hit, the known movement, a block before the minimum reach, the head's yaw);
    `InputRulesTest.aSpearsStabIsAnAttack`; cheat rows "spear: an honest stab at 3.9 blocks, past a sword's reach
    (control)" and "spear: a stab at 5.5 blocks, past the spear's reach".
  - Exists: rig `SpearVerify` (2026-10-23), `castAttackRange` against the client's own `AttackRange.getClosesetHit`
    and `raycastHitResult`'s block filter: 10^6 random scenes, 0 mismatches in the result, the entity and the hit's
    bits (260,567 entity results, 58,862 of them through the margin; 521,282 block, 218,151 miss), and `canPickAlong`
    against every candidate order in each entity scene.
  - To add: for B, the rig's `attack_ranges` at eye-to-box distances 2.95-3.15 with and without sprinting, all `VALID`
    iff the client picked; for the stabs, lag compensation and its rows.

### HR-8 Modern poses shrink the hitbox — Accepted (the modern box) {#hr-8}

- **Players:** crouch-spam to dodge hits. Sprint-swimming and crawling players are tiny targets in water and in
  trapdoor gaps. 1.7 never changed the box ([15]: Java Edition 1.9 and 1.14 changes).
- **Cause:** client. A 26.3 client sizes a remote player from the pose in its entity data: crouching 1.5 tall,
  swimming and crawling 0.6. Its own eye drops to 1.27 when crouching, against 1.54 in 1.7.
- **Today:** the mirror replays the pose exactly (`RemoteMirror`, `DATA_POSE`), so hits are judged on the 26.x box.
  That is consistent, but not 1.7. The classic audit re-confirmed it (tracked here, not a new item).
- **Decided 2026-10-23 (the user):** keep the modern crouch box; viewers keep seeing the crouch.
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

### HR-9 Miss penalty and the dropped first click after a screen — Accepted [limit] {#hr-9}

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
- **Proof:** rig scenario `whiff_then_click` (not built; `client-compat.md` §9.1 R1): agent clicks against `Punch`
  packets, documenting how many clicks the client itself eats. This is a reference number, not a pass/fail.

### HR-10 Trades: the lower uplink wins ties — Accepted (1.7's order) {#hr-10}

- **Players:** "low ping wins trades", "first hit is random on ping" ([5], [6]). In 1.7 the first fresh hit often
  decides the combo.
- **Cause:** server ordering. Attacks are applied in arrival order. Lag compensation fixes where, not when, but under
  authority that narrows the problem. In a 1v1 trade both hits land: each is judged on what its own attacker saw
  (HR-1), on a different victim, and each knock reaches its victim after the attacker's uplink plus the victim's
  downlink, so with symmetric links both knocks land at the same real time. Arrival order still decides two cases:
  who lands fresh when two attackers hit one victim inside its window, and a lethal tie (the second attacker is
  already dead when its attack arrives).
- **Today:** arrival order. When two attackers have held clicks on one victim (`classic-capped`, `neue`), connection
  order decides who lands fresh (`neue-combat.md` §5.2).
- **Decided 2026-10-23 (the user):** keep 1.7's arrival order, and no kill trades (a blow from a player already dead on
  the server stays dropped). Ordering by click time would delay every hit's knock by up to a tick.
- **Fix, if wanted later (combat, netcode):** inside one server tick, apply player-vs-player attacks in order of estimated click time,
  `arrival − rttBase / 2`, instead of arrival. This adds no delay. Bound the credit by the HR-2 cap, so lagging
  doesn't buy priority. Ties across server ticks stay inherent.
- **Proof (to add):** two `FakeClient`s with asymmetric latency (20 ms and 120 ms) click at the same real instants
  on each other 1,000 times. Measure the first-fresh-hit share: about 100/0 before, target 50 ± 3 after.

### HR-11 The damage window opens 9 or 10 server ticks after a hit — Solved (not an issue under authority) {#hr-11}

- **Players:** combos that feel a tick off now and then.
- **Cause (claimed):** server phase, as `neue-combat.md` §3 put it: a hit handled between ticks (the packet phase) and
  one from a timer-queued window (the connection phase) would open the victim's window a tick apart.
- **Today:** checked in the code, both phases come after the tick's level phase and before the next one. A player's
  window counts down in `ServerPlayer.tick()`, which the level's entity tick runs (`ServerPlayer.java`, the
  `damageCooldownTime--` in `tick()`, not `doTick()`). Queued windows run in the connection phase, after the levels
  (`SGPLI.tick` → `AuthPlayer.onServerTick`, before that player's own `doTick`), and every other window runs as a
  packet task between ticks. So a hit handled in or after server tick S gets its first countdown in S+1, and the
  window reopens after the level phase of S+10 either way: always 10 ticks, as in 1.7. What stays is tick quantization
  (a click is handled at the end of the tick it arrives in), which 1.7 had too. The classic audit refuted the claim for
  this reason.
- **Fix:** none.
- **Proof:** the code order above; real client `full1` (Neue, the click buffer): every buffered click landed on the
  tick the window opened (43/43, 48/48 and 50/50 at localhost, 80 and 150 ms), paced and queued windows alike.

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
  (`README-enforce.md`). Every knock applied in the window the client applied it: 848 of 848 on the torture rig's steady
  links, 53 of 53 at each condition of run `classic1`.

### KB-2 Anti-knockback and velocity cheats — Solved {#kb-2}

- **Players:** velocity cheats, "0/0 KB", cancelled velocity packets ([8]: Grim #1382).
- **Cause:** client. The server used to trust the client's position after the knock.
- **Today:** the knock enters the simulation at its ack window, and a client that ignores it is corrected. One that
  withholds pongs has the knock force-applied at the deadline (`auth/net/Transactions.java`, `AuthPlayer`):
  - `ceil(min(rttBase, rtt-ceiling-ms) / 50) + ack-jitter-ticks` paced windows, and at most that plus
    `max-banked-ticks` windows of any kind (torture F3);
  - any ping as old as `max-unacked-ms` is due whatever the window counts say, and a stall can't push the stamps past
    what the client can catch up (review 2 X2-02: before, a stall voided deadlines for as long as it had lasted);
  - then the player is frozen, never kicked for a stall (NW-2). Skipping only the knock's ping is a kick.
- **Fix:** none. Delaying pongs within the deadline delays one's own knock by up to about 200 ms beyond the RTT, which
  is indistinguishable from jitter. The torture rig saw 0 forced entries on steady links up to ±30 ms with
  `ack-jitter-ticks` 4, and keeps it. Jumping in the knock's own window is KB-10.
- **Proof (exists):** cheat rows "anti-knockback: pongs normally, claims ignore the knock", "anti-knockback: withhold
  every pong", "anti-knockback: delay pongs 3 ticks to choose when the knock lands" (bounded: within the deadline),
  "anti-knockback: delay pongs 9 ticks to choose when the knock lands" (lands at +5) and "anti-knockback: batch two
  knocks into one drain past the deadline"; cheat suite 2 "pongs: skip only the knock's ping, answer the rest"
  (kicked) and "pongs: hold only a knock's pong 8 ticks (knock-aware lag switch), three knocks" (each at its deadline,
  +5); unit `TransactionsTest.aPingTooOldIsDueWhateverTheWindowsSay`,
  `ArrivalStampsTest.windowsWaitingForTheMainThreadWidenTheCredit`.

### KB-3 High-ping victims take the knock late — Accepted [limit] {#kb-3}

- **Players:** "high ping players are glued to the floor", "can't combo high ping", or the reverse, "low ping gets
  comboed easily" ([5], [6]; Hypixel threads say high-ping KB arrives 200-220 ms late).
- **Cause:** protocol and client. A vanilla client applies velocity when the packet arrives (victim one-way latency),
  and keeps moving by its own input until then. Nothing a server sends can make it apply the knock earlier.
- **Today:** the knock is exact and consistent (KB-1, KB-2): same vector, same bits, applied in the window the client
  applied it. What still varies with ping is when it starts. Ground prediction (`ground-prediction-distance`,
  KnockbackSync-style, [4]) exists for non-authority players and is unnecessary under authority (KB-5).
- **Fix:** none on a vanilla client. Make it visible instead (combat). Log each knock's apply lag (ack window minus
  the hit's window) and show its p50/p99 per ping band in `/revival auth inspect`, so balance decisions use data. A
  client mod with rollback could apply the knock at the stamped tick (§13).
- **Proof:** a playtest metric, not pass/fail: knock apply lag per victim against `rttBase`. Not built.

### KB-4 The knock's direction comes from server positions — Partial {#kb-4}

- **Players:** a strafing target at ping is knocked sideways or "the wrong way".
- **Cause:** server. `KnockbackEngine.knockback` takes `xd`/`zd` from the attacker's and victim's server positions
  when the hit is processed. Under authority those are each player's truth after their latest processed window, at
  different real times. The attacker aimed at the mirrored box. At 100 ms and a 0.28 b/tick strafe, the two
  disagree by about 0.8 blocks sideways, or 15-20° at 2.5 blocks (arithmetic, not measured).
- **Today:** as 1.7: the base knock points from the attacker's server position to the victim's, and the sprint and
  Knockback extra knock follows the attacker's yaw, which is its own aim. Unmeasured. The classic audit listed this
  item's claim as refuted, with no reason recorded; the code is unchanged.
- **Fix (combat, netcode):** an option `knock-direction: attacker-view`. Under authority, take `xd`/`zd` from the
  hit's own geometry: the attacker's snapshot eye to the mirrored target box at the pick tick, which `HitCheck`
  already has. Pass it through the same per-attack context the neue tipper uses (`neue-combat.md` §6.2). The
  fallback stays server positions.
- **Proof (to add):** live `FakeClient` pair, victim strafing at sprint speed, attacker link delayed 100 ms. First
  measure the spread with server positions; with the option, the decoded knock yaw matches the attacker-view direction
  within 0.5° (LpVec3).

### KB-5 Ground state for the knock (on the ground client-side, airborne server-side) — Solved {#kb-5}

- **Players:** random downward or weak knocks ([4]: KnockbackSync's reason to exist; hcfr-core issue #109 asked for
  it).
- **Cause:** server. The server's `onGround` lagged the client's.
- **Today:** under enforce the `ServerPlayer`'s `onGround` and motion are the simulation's (the client's, exactly),
  so `MotionShadow` steps with the true ground state. `motion-model: authoritative` knocks from the simulated
  velocity itself (`LegacyKnockback.motion`).
- **Fix:** none.
- **Proof (exists):** rig `kb_hit_sprint`: the airborne knock at t41 equals the client's velocity bits; 0 corrections
  at localhost, r80j10 and r150j30 (`full1`).

### KB-6 W-tap overflow hits pull the victim into the floor — Solved {#kb-6}

- **Cause:** server. A second knock was built from falling server motion.
- **Today:** the overflow extra knock changes only server motion and is never sent, as in 1.7
  (`LegacyKnockback.targetMovement`, PR #13, commit `624f21ee`).
- **Proof (exists):** the unit tests from PR #13; live "a w-tapped overflow hit on a falling player sends no velocity".

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

### KB-9 Combo lock with no counterplay — Partial {#kb-9}

- **Players:** once comboed at low ping, a 1.7 player often can't escape.
- **Cause:** gameplay. The 1.7 knock leaves the victim no input.
- **Today:** `classic` and `classic-capped` are plain 1.7 (live "neue enforce: classic-capped: held keys don't bend
  the knock, no DI"). `neue` has DI: the victim's held keys bend the horizontal knock by up to 15°
  (`combat/NeueShape`, `neue-combat.md` §7). `neue` is built and parked.
- **Fix (combat, config):** none for classic, by design. DI is a rule-set call (`influence-degrees`).
- **Proof (exists, `neue`):** unit `NeueShapeTest` (influence goldens); live "neue enforce: DI bends the knock toward
  the victim's held keys"; cheat suite 2 "neue DI: strafe keys sent, claims that don't strafe" (the bend costs the
  movement it stands for); real client, runs `full1` and `neue2` (`n_knock_recv`): the DI knock 13.167° off the plain
  one, 0 error against the formula.

### KB-10 Jump-reset macros — Partial {#kb-10}

- **Players:** velocity macros that jump in the very tick a knock lands, so the knock carries less. A human can time
  it now and then; a macro does it every time.
- **Cause:** client. Under authority the knock reaches the truth in the window whose pong acks it, and a client that
  reads the knock can press jump in that same window. Every packet is legal input.
- **Today:** an alert, never an action (classic audit #8). The server counts knocks applied on the ground, those whose
  own window pressed jump (a rising edge), and how often the player presses jump on the ground anyway
  (`AuthPlayer`, `AuthStats`). `AuthInputAnomalyEvent` `jump-reset` fires at a share of 0.65 or more that is also 4×
  the player's own rate, over 40 grounded knocks or more (`stats.jump-reset-*`). Holding the pong to choose the window is
  bounded by the ack deadline (KB-2).
- **Fix (netcode):** calibrate the thresholds on real players.
- **Proof:**
  - Exists: live rows "jump-reset: a macro that jumps in the window of every knock it takes on the ground" (flagged)
    and "honest: a player who jumps on 3% of its ground ticks at random, knocked as often (control)" (not flagged).
    Simulated: honest styles from 2% to 95% of grounded ticks, and humans hitting the exact tick a fifth of the time,
    stay under 1 in 10,000 per 40 knocks; a macro on 95% of knocks is always flagged.
  - To add: false positives per hour on recorded playtests.

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
  - The sprint ledger has a simulation-owned mode (`SprintLedger.simulated`). Under enforce sprint packets move
    nothing, refused windows included; the simulation starts and stops it from the change between tick ends
    (`sendIsSprintingIfNeeded`). A held sprint key's re-sprint after a hit is no change (1.7's held level), and a
    double-tap player's hit leaves it stopped. Before classic audit #2, a spoofed `STOP_SPRINTING` re-armed a held
    sprint.
  - One knock per sprint start, as in 1.7 (`combat/SprintLedger.java`, patch 1004).
- **Fix:** none.
- **Proof (exists):**
  - unit `SprintLedgerTest.underAuthorityStopPacketsMoveNothing`;
  - cheat row "sprint knock: an honest w-tap re-arms it (control)", and the cheats in SP-3;
  - live: "auth: a double-tap sprint sword hit and an axe hit deal the same damage and knock with authority on and
    off";
  - real client: `attack_sprint_hits` reproduces the client's ×0.6 and sprint stop exactly; run `classic4-bh` (real
    client, 2026-10-22: `c_blockhit_sprint`, `attack_sprint_hits` and the block-hit scenarios) has 0 corrections and
    100.00% exact claims at direct, r150j30, r250j30 and r300j10.

### SP-2 Sprint latch-off, FOV flicker, sprint echo — Solved {#sp-2}

- **Players:** sprint drops twice, FOV flickers (MC-4098), 1.21.2+ clients stuck not sprinting ([12]).
- **Cause:** protocol. The server echoes sprint back through entity data and attribute syncs, and the client copies
  them (MC-69459 for attributes).
- **Today:**
  - Legacy hits never clear the attacker's sprint flag (patch 1004).
  - `auth/view/SelfEcho.java` drops client-owned flags from self echoes, and under enforce the truth's
    `movement_speed` has the sprint modifier only while it sprints (`PortSimulation.sprintFromState`).
  - A self movement-speed sync that differs from what the client holds only by the sprint modifier is not sent
    (`SelfEcho.withoutSprintOnly`, counted as `sprintSyncsDropped`). At 250-300 ms a late one undid the client's next
    toggle, and a sprint block-hitter blocked at sprint speed while the truth walked (classic audit #15).
  - In the working tree, a self shared-flags byte whose server-owned bits (fire, invisible, glowing) are the ones last
    sent is left out of the echo too (`flagEchoesDropped`): at 300 ms the echo of a block-hit's sprint stop landed
    after the re-sprint, and the next hit went out without the sprint knock (classic audit #16).
- **Proof (exists):**
  - cheat rows "self-echo: STOP_SPRINTING before acking the sprint attribute sync" and "self-echo: sprint dropped
    without STOP_SPRINTING, attribute sync acked late";
  - unit `ClientAttributesTest.aSyncThatOnlyTogglesTheSprintModifierIsNotSent`,
    `SelfEchoTest.aByteWhoseServerBitsTheClientHasIsNotSent`, `SelfEchoTest.aServerBitChangeOrAMeantSprintStillGoesOut`;
  - real client, 2026-10-22, run `classic4-bh`: before the attribute-sync fix, sprint block-hitting took 6 corrections
    at r250j30 and 4 at r300j10; after it, 0 at every condition. The real-client run of the shared-flags fix is still
    to do.

### SP-3 Keep-sprint and sprint-packet spoofing — Solved {#sp-3}

- **Players:** KeepSprint cheats keep full speed after hitting ([8]: Grim #2849). "Auto w-tap" cheats send
  STOP/START sprint around every attack to always get the sprint knock.
- **Cause:** client.
- **Today:** blocked.
  - The simulation applies the client's own attack prediction (×0.6 x/z and sprint off on a sprinting hit on a
    player, `Simulation.clientAttack`), so a client that skips it diverges and is corrected. Until 2026-10-22 it was
    not: the correction's "the hit was weak" branch (`PortSimulation.retryAttackStrength`, there for an attack-strength
    ticker a tick off) took the KeepSprint claim as a weak hit and kept it, while the knock had gone out as the full
    sprint knock. The cheat row below found it. The branch now flips only to a strength the client's ticker could
    have had, and a legacy sword's maxed attack speed is full strength at any of them. A code review (2026-10-23)
    found it still open for weapons with a real cooldown (axes, maces): reset is weak for them, so every full-strength
    sprint hit read as a possible weak one. The branch now runs only while the ticker counts from a reset the port
    can't pin to a tick (an item change, design H9: its one tick either way, and reset only a tick after it); counted
    from a hit or a dig's stop the ticker is exact.
  - Under enforce sprint packets don't move the sprint ledger at all (SP-1).
  - A `UseItem` and its release in one window are refused (`release-without-use`), so a block that lasts no tick
    re-arms nothing; under authority the block re-arm comes from a tick that really ran with a legacy block up, once
    per block (classic audit #5).
- **Fix:** none beyond the above.
- **Proof:**
  - Exists (keep-sprint): cheat row "keep-sprint: the client skips its own x0.6 and sprint stop after a sprint hit"
    (corrected, the truth bit-equal to the control's, the knock the control's 0.9), control "keep-sprint: an honest
    sprint hit (control)". With an axe, a sprint hit one cooldown after the last: "keep-sprint: the same axe hit, the
    client skipping its own x0.6 and sprint stop" (corrected; it leaked, 0 corrections, on the rule before),
    control "keep-sprint: an honest sprint hit with an axe, one cooldown after the last (control)".
  - Exists (spoofing): cheat rows "sprint knock: a spoofed STOP_SPRINTING in a click's window, sprint key held",
    "sprint knock: STOP_SPRINTING, then START_SPRINTING with a click (SuperKB)" and "sprint knock: a use and its
    release in one window (a block for no tick)", with the controls "sprint knock: an honest w-tap re-arms it
    (control)" and "sprint knock: an honest block-hit, the release a tick later, re-arms it (control)"; unit
    `InputRulesTest.aReleaseOnlyComesFromAWindowThatStartedUsing`.

### SP-4 W-tap and block-hit macros — Partial {#sp-4}

- **Players:** macro w-taps and block-hits that are perfectly timed on every hit.
- **Cause:** input. A macro presses real keys, so every packet is legal.
- **Today:** an alert, never an action (2026-10-23, `auth.input.TapWatch`). A tap is what a hit is followed by:
  forward let go and pressed again (a w-tap, the keys as the client sent them), or use held and let go (a block-hit, as
  the simulation runs it). A tap that starts within 10 client ticks of an attack is one sample: its lead (the attack to
  the tap's first tick) and its length. `AuthInputAnomalyEvent` `w-tap-lock` or `block-hit-lock` fires when
  `stats.tap-lock-samples` (100) taps in a row are the same sample to the tick; `/revival auth inspect` shows each run,
  its longest and the count.
  - What it catches: a macro run on the client's ticks (a cheat client's w-tap module) every time, at the 100th tap. A
    macro timed in milliseconds lands on two neighbouring ticks with the click's phase in its tick, so it is caught only
    when its delays sit on 50 ms multiples with under about 2 ms of jitter.
- **Fix (netcode):** calibrate on recorded taps; a macro timed in milliseconds needs a distribution test (two
  neighbouring values at a steady ratio), which needs real human data first.
- **Proof:**
  - Exists: unit `TapWatchTest`; the tap study (2026-10-23, a replica of `TapWatch` with the click at a uniform phase in
    its tick and the key read once a tick). Humans: 4,000 fights of 300 taps for each timing spread of 5, 10, 15, 20,
    30 and 50 ms (lead mean 0-150 ms, length 50-200 ms): 0 flagged; the longest run of one sample was 60 at a 5 ms
    spread (no human holds that), 17 at 20 ms. Macros: tick-locked, all flagged at tap 100; timed in milliseconds,
    lead/length 50/100 ms or 0/50 ms with 0 jitter all flagged, with 2 ms 5% and 37%, with 8 ms none; 20/60, 30/75 and
    10/110 ms none. Live rows "w-tap: a macro that lets go of forward 1 tick after every hit, for 2 ticks" and
    "block-hit: a macro that blocks 1 tick after every hit, for 2 ticks" (flagged at 20 for the row), and "honest: a
    w-tap and a block-hit after every hit, timed as a human (control)" (a 20 ms spread, not flagged).
  - To add: runs of recorded taps from playtests (false positives per hour).

### SP-5 Swapping to the sword: the first hits skip 1.7's attacker slowdown — Accepted {#sp-5}

- **Players:** after a rod, a pot or a pearl, the first sword hits keep the attacker's speed and sprint. 1.7 slowed
  the attacker ×0.6 and stopped its sprint on every sprint hit.
- **Cause:** client. A rod, pot or pearl is not a legacy weapon, so it keeps 26.x's attack cooldown. On the swap the
  client learns of the sword's no-cooldown modifier only from the server's attribute update, a round trip later, so
  its first hits count as weak (strength under 0.9) and skip the ×0.6 and the sprint stop, while the server lands a
  full 1.7 hit with the sprint knock. The simulation follows the client, so nothing is corrected: it is a parity gap.
- **Today:** switch `legacy-combat.hide-cooldown-on-items` (default off, per world, classic audit #11). With it on, the
  no-cooldown modifier stays while an item with no attack speed of its own is held (a rod, a pot, a pearl, food, a
  block; never the empty hand, so fists stay 26.x), so the client already has it when the sword comes
  (`combat/LegacyHitRegistration`). It also takes the cooldown off those items' own hits, which is why it is a switch.
- **Fix (config):** decided 2026-10-22: off (the user keeps those items 26.x); the gap stays, by choice.
- **Proof:**
  - Exists: live "hide-cooldown-on-items: the rod keeps the modifier", "hide-cooldown-on-items: an axe drops it",
    "hide-cooldown-on-items: the empty hand drops it (fists stay 26.x)" and "hide-cooldown-on-items off: the rod drops
    it (26.x, the default)"; real client, 2026-10-22, run `classic3-hi` (`c_rod_swap`, `c_pot_swap`, `c_pearl_swap`):
    clean, no input-grammar rule fired.
  - To add: on a real client with the switch on, the first sword hit after a swap slows the client ×0.6.

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
    is open, until the first tick after it closes), per §15.4. A hotbar key and a right click in one tick block with
    the new sword, as a vanilla client does (`Simulation.startUsingItem(hand, view)`; cheat suite 2 H2: the port used
    to start the old item, so the server never blocked).
- **Proof (exists):** rig `block_walk`, `block_sprint_jump` and `block_strafe_taps`: speed ×0.2 while blocking,
  0 corrections at localhost, r80j10 and r150j30 (`full1`); real client, 2026-10-22, run `classic4-bh`: 0 corrections
  in the block-hit scenarios at four conditions up to r300j10. Live: "auth: a number-key slot switch while blocking
  takes the hotbar branch, not a correction". Cheat suite 2: "honest: take the sword and block in the same tick".

### BL-2 Autoblock and no-slow while blocking — Solved {#bl-2}

- **Players:** cheaters attack while blocking, or block without the slowdown ([8]: Grim no-slow PRs).
- **Today:**
  - `InputRules.ATTACK_WHILE_USING`: a vanilla client discards every click while using, the release tick included,
    and so did 1.7.
  - `InputRules.RELEASE_WITHOUT_USE`: a block raised and released in one window is refused (classic audit #5).
  - The simulation applies the ×0.2 slowdown.
- **Proof (exists):** cheat rows "combat: attack while blocking (autoblock)", "no-slow: blocking with a sword at
  walking speed" (the truth equals the honest blocking walk, gap 0) and "sprint knock: a use and its release in one
  window (a block for no tick)"; live "auth: input grammar: attacks while blocking are dropped and reported"; unit
  `InputRulesTest.anAttackOrUseWhileUsingIsDroppedIncludingTheReleaseTick`.

### BL-3 A 26.3 client keeps sprinting through a sword block — Solved (switch on in the NAS world) {#bl-3}

- **Players:** blocking while sprinting moves about 30% faster than 1.7's blocking walk, and keeps the sprint-jump
  boost. 1.7 dropped the sprint when a block started.
- **Cause:** client. 26.3 stops a sprint only on no forward input, hunger, blindness or a hard collision; using an item
  slows to ×0.2 of sprint speed but keeps the sprint. 1.7's blocking walk fell under the sprint threshold and stopped
  it. Sneaking is the same (×0.3).
- **Today:** switch `sword-blocking.stop-sprint-on-block` (default off, classic audit #6). Under enforce, a legacy
  block stops the sprint as a server-meant write: the client gets it in the echo, the truth at the ack. Releasing the
  block with the sprint key held sprints again and re-arms the knock itself, so `rearm-sprint-on-block` is skipped
  while it is on. Off keeps 26.3's behaviour. Sneaking is unchanged.
- **Fix (config):** decided 2026-10-22: on in the NAS world (`sword-blocking.stop-sprint-on-block: true`), off by
  default for other servers. Since then the stop waits for a block held `stop-sprint-after-ticks` (4): sent at a
  block-hit's first tick it landed after the release at 250-300 ms and cancelled the re-sprint in the tick of the next
  hit; a block-hit's knock comes from `rearm-sprint-on-block`, and a held block walks as in 1.7.
- **Proof:**
  - Exists: real client, 2026-10-22, run `classic5-stopsprint` (switch on): 0 corrections at direct and r150j30. At
    direct the client never sprints through a block tick (0 of 189 blocking ticks, against 45 of 189 with the switch
    off); at 150 ms the stop lands one round trip late (19 of 189). Run `classic7-stopsprint` (on, r250j30 and
    r300j10): 0 corrections, 11 and 10 of 189 blocking ticks still sprinting, and the cost of a late stop: 1 of 4
    landed sprint block-hits at each went out without the sprint knock, because the stop cancelled the client's
    re-sprint in the tick of its next attack. With the switch off every landed sprint block-hit is armed at every ping
    up to 300 ms (run `classic6`). Unit `SelfEchoTest.aServerBitChangeOrAMeantSprintStillGoesOut` (the meant stop goes
    out).
  - Exists: real client, 2026-10-22, run `classic8-stopsprint` (switch on, stop after 4 ticks): 0 corrections and every
    claim exact at localhost, 150, 250 and 300 ms; every landed sprint block-hit armed at all four; the 29-tick held
    block sprinting on only 3 to 9 of its ticks (29 with the switch off).
  - To add: a live or cheat row with the switch on; none of the suites turns it on.

---

## 5. Clicking, input and hardware

### IN-1 The CPS race (drag, butterfly, jitter) — Solved (`classic-capped`, `neue`) {#in-1}

- **Players:**
  - 1.7 rewards raw clicks per second: more clicks land more window openings and give more aim samples.
  - Drag and butterfly clickers reach 20-40 CPS.
  - Bans for "too fast" clicking are disputed ([9], [25]).
- **Cause:** gameplay and input.
- **Today:**
  - `max-attacks-per-client-tick` 1 (`auth/input/InputEnvelope.java`): at most 20 attacks/s, and a second click in a
    tick (same `hitResult`) is dropped.
  - `classic-capped` and `neue`: a 12 CPS token bucket (0.6 tokens per client tick, burst 2,
    `auth/input/ClickBucket.java`) with the click buffer (HR-6). From about 7 CPS, combo timing no longer depends on
    CPS. What's left is aim sampling up to the cap: in simulation, an autoclicker's edge over a 12 CPS human with
    shaky aim falls from 7-8% to about 1% (`neue-combat.md` §5.3, §12.1).
  - `classic` has no cap, as in 1.7.
- **Fix:** none for `classic-capped`. Plain `classic` keeps 1.7's race on purpose.
- **Proof (exists):**
  - unit `ClickBucketTest` (even clickers up to the cap lose nothing; twenty a second lands twelve);
  - live "neue enforce: classic-capped: an even 12 CPS clicker keeps every click" and "neue enforce: classic-capped: a
    locked 20 CPS clicker delivers at most 12 a second plus the burst";
  - cheat suite 2 "classic-capped click-cap: an even 12 CPS clicker (control)", "classic-capped click-cap: a 30 tps
    timer, an attack in every window for 4 s" and "classic-capped click-cap: two attacks in every window for 2 s".

### IN-2 Double-click mice and low debounce — Solved {#in-2}

- **Players:** worn switches or 0-4 ms debounce produce double clicks; players argue whether that is cheating ([10]).
- **Cause:** hardware.
- **Today:**
  - Two clicks within one client tick share one `hitResult` and the envelope drops the second, so a bounce inside a
    tick gains nothing.
  - A bounce across a tick boundary is a real extra click (an aim sample). In `classic-capped` the 12 CPS cap makes
    clicks past 12 a second worthless.
  - `InputStats` records the double-click ratio, shown in `/revival auth inspect`. Its alert
    (`stats.double-click-ratio`) is 1.0 since config v6: at 0.3 it flagged honest butterfly clickers (classic audit
    #12).
- **Fix:** none needed beyond IN-1's cap. Hardware bounce and butterfly clicking are not separable at 50 ms
  resolution, and the cap makes the question moot.
- **Proof (exists):** `ClickBucketTest`; `RevivalConfigTest.version5DoubleClickAlertStopsFlaggingButterflyClickers`.
  A player FAQ line, that double-clicks gain nothing past 12 CPS, is still to write.

### IN-3 Autoclickers — Partial {#in-3}

- **Players:** autoclickers and mouse-firmware macros; disputes about false bans ([9]; Intave calls its click-pattern
  check unstable).
- **Cause:** input. Every click is a legal packet.
- **Today:**
  - Bounded by the envelope, and by the 12 CPS cap in `classic-capped` and `neue` (IN-1).
  - Detector A, `click-streak` (`auth/input/CadenceWatch.java`): a run of 100 one-click ticks raises
    `AuthInputAnomalyEvent`, which catches a 20 CPS lock. It counts `max(punches, attacks)` per window, and a window
    with more attacks than punches is an `attack-without-punch` grammar count, so leaving out the `Punch` hides nothing
    (cheat suite 2 L2).
  - Detector B, `click-lock` (any fixed rate, `neue-combat.md` §12.2), in the same class since 2026-10-23: over the
    last 150 intervals between clicks of an unbroken eligible chain, every interval k or k+1 ticks, the mean at 8 CPS
    or faster, and no click more than 0.75 ticks off their least-squares line. The alert waits for the lock to hold
    150 more clicks: a steady human's chance lock rarely lasts (`neue-combat.md` §12.2). `/revival auth inspect` shows
    the current and the longest lock.
  - `InputStats` (click intervals) and `AnomalyWatch` raise the other alerts (`auth/input/*`).
- **Fix:** none in code. Both detectors are evidence for staff, never authority. Well-randomized autoclickers (±25%)
  stay undetectable at tick resolution; the cap is the defence.
- **Proof:**
  - Exists (A): unit `CadenceWatchTest`; cheat suite 2 "classic-capped cadence: a 20 CPS autoclicker that leaves out
    the Punch packet" (detected with and without the `Punch`).
  - Exists (B): unit `CadenceWatchTest` (12, 10, 20 and 25 CPS locks; a late click breaks one; spread and slow
    rates never lock; a seeded steady human at 12 CPS never fires over 10,000 clicks).
  - To add: re-run the §12.3 study on recorded playtest windows (`InputStats` intervals, RVTR traces) before trusting
    the thresholds. Target: ≤ 0.01 false positives per hour on real players.

### IN-4 Aim assist and aimbot — Partial {#in-4}

- **Players:** aim assist and aimbots ([8]).
- **Cause:** input. The server can't know where a human meant to look.
- **Today:** measured, not prevented. `InputStats` fits the sensitivity GCD of rotation deltas (`aim-gcd-miss-ratio`
  0.5 over `aim-min-turns` 200), with up to two sensitivities per session (a zoom) and smoothed-camera turns skipped
  (`client-compat.md` N7). The rig's aim is computed, not mouse-driven, so it can't calibrate this; the server rightly
  flags it as `aim-gcd` (`README-enforce.md`).
- **Fix (netcode):** calibrate the GCD thresholds on real mouse data from known-clean players. Add snap analysis
  (rotation acceleration at target acquisition) as a second signal. Prevention stays impossible on any client.
- **Proof:**
  - Exists: unit `InputStatsTest` (one mouse never misses; a zoom is the second step; a smoothed camera is skipped;
    synthetic aim still misses).
  - To add: false-positive rate per hour on a recorded clean playtest (target ≤ 0.01); detection rate on rotation
    streams from a known aim-assist client in a private test.

### IN-5 Pot, refill and soup macros; inventory-move — Solved {#in-5}

- **Players:** auto-pot, refill macros, inventory moves while running ([8]; Hypixel bans macros).
- **Cause:** input and client.
- **Today:** `InputRules`, under enforce:
  - `CLICK_WHILE_MOVING`: a container click in a tick whose input still walks is an inventory-move; a click and a
    close every tick while the keys stay down never opened a screen (review E8);
  - `ATTACK_AFTER_CLOSE`, and attack or use while using;
  - `SLOT_AFTER_ACTION`: a `SetCarriedItem` to another slot after the window's first attack or use is dropped, and the
    server's slot is sent back. Hotbar keys are handled before clicks (`Minecraft.handleKeybinds`), so a one-tick
    "switch, throw, switch back" is a modified client;
  - `OFFHAND_AFTER_MAIN`: an off-hand use, use-on or interact is dropped when the main hand holds an item that starts
    using (an off-hand pot behind a sword);
  - `USE_ROTATION`: a `UseItem` is replayed with the tick's rotation, counted, never dropped, so a pot, rod or pearl
    can't be aimed where the claims never looked (classic audit #3).

  A macro at a human's speed (switch, throw, switch back over 2-3 ticks) is legal input, as a human doing it is.
- **Fix:** none.
- **Proof (exists):**
  - unit `InputRulesTest` (`noHotbarChangeAfterTheTicksFirstAttackOrUse`,
    `noOffHandUseBehindAMainHandItemThatStartsUsing`, `aUseRotationOffTheTicksIsCountedNeverDropped`,
    `containerClicksWhileWalkingAreAnInventoryMove`, `clickAndCloseEveryTickWhileWalkingNeverOpenedAScreen`);
  - cheat rows "use: a pot thrown at the feet under level claims, the sword back in the same tick (silent auto-pot)",
    "use: an off-hand pot thrown while a sword is in the main hand", "inventory-move: container click while walking",
    with the controls "use: an off-hand pot thrown with an empty main hand (control)" and "honest: a pot refill through
    the inventory screen mid-fight, then the throw (control)";
  - real client, 2026-10-22, run `classic3-hi` (`c_rod_swap`, `c_pot_swap`, `c_pearl_swap`): no input-grammar rule
    fired. Real client, 2026-10-22, run `classic6`, scenario `c_pot_refill` (E, a shift-click from the main inventory,
    Esc, number key, the throw, the sword, twice): 0 corrections, no grammar drop, every claim exact, at localhost,
    150, 250 and 300 ms.

### IN-6 Click and frame timing, polling rate — Accepted [limit] {#in-6}

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
  click-between-ticks mod changes feel only (§13).
- **Proof:** none (out of the server's reach).

---

## 6. Projectiles, rods, potions, pearls

### PR-1 Rod, bow and snowball hits depend on ping — Solved (rods, snowballs, eggs; arrows by choice) {#pr-1}

- **Players:** "rod didn't hit", "rod hitbox", bow shots that pass through on ping ([16]-adjacent forum threads;
  "fixing ghost hits breaks rods in many forks", [3]).
- **Cause:** server. Projectiles are server entities. Their collision was tested against the victim's server box (the
  truth after their latest processed window). The shooter aimed at the mirrored display, which is RTT + about
  110 ms older.
- **Today:** the shooter's view (patch 1017, `projectiles.md` §2):
  - For a rod, snowball or egg thrown by a player under enforce (`lag-compensation.projectiles`), each tick tests
    other players at the box the shooter's client drew after its newest tick, while that mirror is exact and within
    the shooter's rewind cap (HR-2), else at the server box (`combat/ProjectileHits`, `combat/ProjectileRewind`,
    `CombatWiring` as `ProjectileViews`). "Replace, not union": the server box isn't also tested. Non-players use
    server boxes.
  - Arrows stay on server boxes by default: long flights are dodgeable, so ping-free bows are a choice (add
    `minecraft:arrow`).
  - 1.7's hit detection applies to every legacy projectile (PR-2): 26.3's growing margin gave a rod at rod range half
    1.7's target width, the measurable part of "rod hitbox".
  - Blocks are the server's; only pearls stop at fake walls (PR-7).
- **Fix (combat, netcode):** built for rods, snowballs and eggs. Left: the arrow decision (config).
- **Proof:**
  - Exists: unit `ProjectileRewindTest` (the view after the shooter's newest tick, the inclusive cap, fallbacks) and
    `ProjectileHitsTest`; cheat suite 2 "projectiles: an honest snowball at 6 blocks (control)" and "projectiles: an
    honest rod at 6 blocks (control)" (still targets), "projectiles: a snowball at a target backtracked 500 ms (pongs
    held)" and "projectiles: a rod at a target backtracked 500 ms (pongs held)" (both miss: the view is used only
    within the cap).
  - Exists: real client, 2026-10-22, runs `classic6` and `classic9-projectiles`, a target strafing 4.5 blocks off:
    rods 6 of 45 casts hit at localhost, at r150j30 and at r300j10 (the same casts: the scenario is scripted, so a hit
    judged on what the shooter saw doesn't depend on ping; on server boxes the shooter would aim about 400 ms behind at
    300 ms), snowballs 4, 3 and 7 of 40 (knocked targets make each run's later throws differ); 0 corrections, every
    claim exact. The over-hit bound is the cap: cheat suite 2's 500 ms backtrack rows miss.

### PR-2 1.7 rod and projectile mechanics — Solved {#pr-2}

- **Players:** 1.9+ removed rod knock ([15]: Fishing Rod); Paper drops 0-damage hits; rods feel sluggish ([14],
  PandaSpigot #339).
- **Today:** `combat/LegacyProjectiles.java` (patch 1008) and, since 2026-10-22, patch 1017 (`projectiles.md`):
  - 0-damage hits land on players (knock, flash, window), pearl hits included, as on 1.7 CraftBukkit;
  - the knock comes from the shooter, with Punch folded in;
  - rods hit on impact and hook only if the hit landed;
  - 1.7's cast (1.5) and hook path (`flight: legacy-1.7`);
  - 1.7's hit detection (`hit-detection: legacy-1.7`): boxes grown by 0.3 from the first tick, 1.7's intercept, the
    owner immune for 5 ticks;
  - the 1.7 reel pull on server motion.

  hcfr-core #321 (more projectile knock) is served by `knockback-profile` under `projectiles`.
- **Proof (exists):** unit `LegacyProjectilesTest` (reel pull, cast, Punch), `ProjectileHitsTest` (rod-range margins
  by tick; 100,000 segments against `AABB.clip`), `LegacyThrowTest.legacyRodMoves17sHookPath`; live "snowball knocks
  away from the thrower" and "rod then sword in the window: full damage, no second knock"; GrimAC checklist row 6
  when run (CL-3).

### PR-3 Thrown potions, pearls and arrows inherit the thrower's motion — Solved {#pr-3}

- **Players:** a pot thrown down while sprinting drifts forward, and a pearl thrown while sprinting flies further,
  unlike 1.7 ([15]: Java Edition 1.9, "projectiles account for thrower motion").
- **Cause:** server. 26.3's `Projectile.shootFromRotation` adds the shooter's known movement (its y only when
  airborne). 1.7's `EntityThrowable` and `EntityArrow` did not.
- **Today:** `throw: legacy-1.7` (the default; `combat/LegacyThrow.shoot`, patch 1017 at the top of
  `Projectile.shootFromRotation`). A player's throw of a legacy type (snowball, egg, pearl, splash potion, an arrow
  from a legacy launcher) starts at 1.7's origin (eye 1.62 − 0.1, 0.16 to the side), with 1.7's heading and spread and
  none of the thrower's motion. `flight: legacy-1.7` moves 1.7's path inside 26.x's tick (`LegacyThrow.gravity`).
  Under authority the throw comes from the client's own tick (PR-5).
- **Fix:** none. `throw: vanilla` and `flight: vanilla` keep 26.x.
- **Proof (exists):** unit `LegacyThrowTest`: a pot thrown straight down is (0, −0.5, 0), nothing of the thrower's
  motion in it; 1.7's flight in 26.x's tick order over 200 ticks at six pitches (1e-9); pearl distances by pitch (26.x
  falls 1.4-2.1 blocks short); a self-pot heals 7 standing and 6 sprinting, as 1.7. A one-off check against a running
  1.7.10 server is still to do (`projectiles.md` §11).

### PR-4 Splash falloff and pot accuracy — Partial {#pr-4}

- **Players:** pot accuracy debates; HCFR asked for precision changes three times (hcfr-core #13, #53, #115, closed,
  handled in core).
- **Cause:** server. 26.3's `ThrownSplashPotion.onHitAsPotion` measures box-to-box distance
  (`potionAabb.distanceToSqr(entity box inflated by the margin)`), so any box touching the potion's gets full
  strength. 1.7's `EntityPotion.onImpact` measured from the potion to the entity's position (its feet), which is
  stricter, and gave full strength to the entity it hit directly.
- **Today:** `splash-potion.falloff: legacy-1.7` (the default; `combat/LegacySplash`, patch 1017): each entity's
  strength is `1 − √d/4` from the potion's start-of-tick position to the entity's feet, and 1 for the entity it hit.
  26.3 was a full block more forgiving at every distance (`projectiles.md` §1.1). Core's own precision plugin may still
  run on top.
- **Fix (combat, core):** agree with core who owns precision, so the two don't stack.
- **Proof:**
  - Exists: unit `LegacySplashTest` (both falloff tables, harming),
    `LegacyThrowTest.aStandingSelfPotBurstsHalfABlockUpOn17` (heals 7 standing, 6 sprinting).
  - To add: the RedLine pot drill stats (`potionAccuracy`) before and after, with core's precision off.

### PR-5 Pot timing on high ping — Accepted [limit] {#pr-5}

- **Players:** "ghost pots", high-ping players potting at five hearts ([5]).
- **Cause:** protocol and client. The heal reaches the client one RTT after the throw, plus flight time.
- **Today:** the `UseItem` is replayed inside its window, before that tick's movement, with the `ServerPlayer` at the
  truth of the previous tick and the tick's own rotation (`use-rotation`, classic audit #3). So the potion spawns
  where and how the client threw it, from 1.7's origin (PR-3). The delay is inherent.
- **Fix:** none for the delay (§13).
- **Proof:**
  - Exists: cheat row "use: a pot thrown at the feet under level claims, the sword back in the same tick (silent
    auto-pot)": the pot flies along the tick's aim, not the packet's.
  - To add: live. A sprinting `FakeClient` throws a potion; its spawn position equals 1.7's origin from the client's
    eye at that tick, to the bit.

### PR-6 Fire, arrows and mob hits swallowed by sword combos — Partial {#pr-6}

- **Players:** a fire tick turns the next sword hit into a no-knock overflow; poison and arrows vanish during combos.
- **Cause:** server. 26.x has one damage window for everything.
- **Today:** per-channel windows (melee, projectile, mob, other) are built (`combat/DamageWindows.java`, PR #18), but
  `damage-windows.enabled` defaults to `false`. `projectiles.md` §8 recommends turning it on for HCFR combat worlds
  after one playtest.
- **Fix (config):** decide for HCFR, then turn it on per world after a playtest.
- **Proof:**
  - Exists: unit `DamageWindowsTest`; live "windows on: fire doesn't eat the next sword hit", "windows on: an arrow
    lands with knockback inside a sword window", "windows on: rod then sword is unchanged" and the rest of that set.
  - To add: a playtest A/B of combo length with fire aspect and bows.

### PR-7 Pearl glitching — Partial {#pr-7}

- **Players:** HCF's classic: pearls landing inside fence gates, door cracks, slabs, stairs and panes, then walking
  through. Pearling through client-only combat and claim walls. Pearls into 1×1 gaps ([16]; MC-2164).
- **Cause:** server.
  - Vanilla teleports the owner to the pearl's hit position with no check that the player's box fits.
  - The 26.x client lets a box that starts inside a block move out of it (and pushes itself out).
  - Fake walls exist only on the client, so the server's pearl flew through them.
- **Today:** `legacy-combat.projectiles.ender-pearl` (on by default; `combat/PearlLanding`, patch 1017,
  `projectiles.md` §6):
  - **Safe landing:** the pearl lands only where a standing box of the thrower fits in both the server world and the
    thrower's client blocks. Otherwise the feet move back along the pearl's last flight in 1/16 steps, up to 1.5. If
    nothing fits, the teleport is dropped and the pearl refunded (`no-landing: refund`).
  - **Fake walls count:** the pearl also collides with the thrower's `WorldView`, so combat and claim walls stop
    pearls as well as feet.
  - **Pass-through:** `pass-through` lists blocks pearls may cross (empty by default).
  - Server teleports are transactions, held until the destination chunk is on its way (PR-8).
  - Not covered: entity colliders (boats, shulkers) in the fit test. Core isn't told of a refund
    (`RevivalPearlRefundEvent`, `projectile-hooks.md` A1, not added), so core's own pearl timer keeps running.
- **Fix (combat, core):** the refund event for core. Core's region rules see the adjusted landing in
  `PlayerTeleportEvent` (cause `ENDER_PEARL`).
- **Proof:**
  - Exists: unit `PearlLandingTest` (an honest landing on open ground is vanilla's; a one-block wall, a closed fence
    gate, a closed door, a slab and a stair step, a glass pane, a ceiling hit from below, a crawl space with no fit
    (refunded), a fake wall only the client has, pass-through blocks); cheat suite 2 "pearls: through a fake wall the
    client dropped from its world" (the truth lands short of it) and "pearls: a throw down the lane (control)"; real
    client `n_pearl` (`full1`, 0 corrections) and run `classic2-far` (real client, 2026-10-22: far teleports and
    pearls, 0 divergences).
  - To add: the pearl suite on a running server (`AuthLiveScenarios`), with the spots above plus an open fence gate, a
    door crack, a trapdoor and cobweb: the truth box never ends past the blocking plane, and the refund fires when no
    spot fits.

### PR-8 Teleport into a chunk the client hasn't received — Solved {#pr-8}

- **Players:** a long pearl or `/tp` falls into the ground, or snaps back once.
- **Cause:** client and server ordering. The client reads a missing chunk as void air and falls (it does on vanilla
  servers too).
- **Today:** a server teleport (`/tp`, plugin teleports, pearls) whose destination chunk under the player's box is
  neither held by the client nor already sent to it is held in `AuthPlayer` (`HeldPacket`) until it is. Moonrise
  usually sends it within a tick or two; after `teleport-chunk-send-ticks` (2) the netcode sends it itself, and after
  `teleport-hold-max-ticks` (100) the teleport goes out regardless (a chunk the server can't load). The chunk then
  reaches the client ahead of the teleport, so it never stands over void air. The origin's `ForgetLevelChunk`s go out
  as they come: holding them behind the teleport was tried and reverted (`3638e753`), because the `SetChunkCacheCenter`
  that must precede the destination's chunks already drops the origin's on the client, and the held forgets only made
  the server's record wrong (six divergences at far teleports in `classic1`).
- **Proof (exists):** live "auth: a 220-block teleport: the chunk arrives before the teleport, the player stands, no
  correction" and "auth: a 1000-block teleport: the chunk arrives before the teleport, the player stands, no
  correction" (into chunks nobody had loaded); cheat suite 2 "teleport hold: never ack a chunk batch, then a 400-block
  teleport" (out 3 ticks later, the netcode sending the chunk) and "pearls: a long throw into a chunk the client lacks,
  chunk batches never acked"; real client `full1` (far teleports of 218 to about 2,800 blocks, 0 corrections at three
  conditions) and, after the revert, run `classic2-far` (real client, 2026-10-22): 0 divergences.

---

## 7. World desync: glitching and ghost blocks

### WD-1 Block glitching (place in a denied spot, then climb) — Solved {#wd-1}

- **Players:** HCF's other classic. Place a block inside a claim, where the server cancels it; the client's ghost
  block holds you up for a jump over 2-block walls ([17]: BlockGlitchFix, Grim #1142).
- **Cause:** client prediction against server denial.
- **Today:** `WorldView` predictions are admitted only if a vanilla client would make them
  (`auth/view/PredictionRules.java`). A placement the server denied is not honoured by the truth
  (`honor-denied-predictions: false`), and after a revert a truth box inside a block snaps to the prediction-time
  position (§15.2). Denied breaks are WD-2.
- **Fix:** none in code.
- **Proof (exists):** cheat row "world: a block a plugin cancels, its ghost climbed over a 2-high wall (block glitch)":
  a `BlockPlaceEvent` cancel, the client predicting the block and keeping its ghost past the server's ack; the client
  stood on it (claims up to y +2.25), the truth only ever jumped from the floor (y +1.25) and stayed behind the wall,
  corrected, not kicked. Control "world: a block placed in front of a 2-high wall, climbed and over (control)": the
  same climb with the block allowed, 0 corrections. "world: place a block without holding one, stand on it" covers
  placing without holding a block.

### WD-2 Ghost blocks for movement — Solved {#wd-2}

- **Players:** walking on or into blocks that aren't there; "ghost liquids" ([17]; PandaSpigot #344).
- **Today:** `WorldView` is "what this client was sent":
  - persistent sent states;
  - pre-change states pinned until their broadcast is acked;
  - predictions with an ack deadline and the revert snap;
  - breaks the client predicts and the server denies (a protected block) are holes in the client's world only: the
    belief and the forecast read them, the truth never does, and the client's own restore snap at the ack is modelled
    (the NAS block-glitch fix, 2026-10-22: a Lunar player breaking protected glass and honey was corrected every 2 ticks
    for seconds).

  The simulation reads it, not the live world (`auth/view/WorldView.java`, `Authority.onBlockChanged`).
- **Proof (exists):** the real client's 80 gym traces, bit-exact (doors, trapdoors, floor bits), and the gym set with 0
  corrections at three conditions (`full1`, `classic1`); cheat rows "world: place a block without holding one, stand
  on it", "world: spam placements with block acks held" and "world: break a protected block, walk through before the
  revert"; the honest NAS rows "world: hold attack on a protected honey block underfoot, then walk off (honest)",
  "world: break a protected wall block and walk into the gap (honest)" and "world: dig a protected glass pane and walk
  into the gap (honest)".

### WD-3 Phasing through client-only combat and claim walls — Solved {#wd-3}

- **Players:** walking through combat-tag glass walls with a desync or a forged packet.
- **Today:** fake blocks sent to a player persist in their `WorldView` until replaced, so the truth collides with
  them. A forged correction accept is a protocol violation, and `resync()` keeps the sent states
  (`AuthPlayer.resync`). Pearls stop at them too (PR-7).
- **Proof (exists):** cheat row "teleport: accept an id never sent (correction band) to drop fake walls" (the fake wall
  stops truth and client alike); cheat suite 2 "pearls: through a fake wall the client dropped from its world".

### WD-4 Ghost hand: interacting through walls — Solved {#wd-4}

- **Players:** opening doors, chests and gates through walls; freecam door breaks ([17]: Grim #1800). In HCF this is
  a raiding cheat.
- **Cause:** client. The server checks interaction distance, not line of sight.
- **Today:** under enforce, a `UseItemOn` (a door, chest or gate opened, a block placed) or a `START_DESTROY_BLOCK`
  that a vanilla client couldn't have aimed is the input grammar's `unseen-target` (`AuthPlayer.handRule`). A click
  passes when its block is on the client's crosshair: the server's pick from the tick's eye and rotation on the
  client's own `WorldView`, as for predictions. Failing that, it passes when its point is on the block's outline and no
  other block's outline stands between the eye and the point (`PickRay.clipTo`). The outline counts where it leaves
  the cube (a lectern's top, a piston's arm, an open shulker's lid). A point merely near the block isn't enough:
  vanilla's handler takes any point within a block of its centre, and one beside the wall would be in sight while the
  block behind it isn't. A light block's outline is read with the player's held item (it has one only for a player
  holding light). A dig names no point, so it passes on the crosshair or when any point of a 5x5 grid across the face
  is in sight, centre first. While the port isn't stepped (an exempt tick, or the first after one, before the re-seed)
  the eye is the server player's, which vanilla moved to the claims. An `unseen-target` is not handled. Its sequence is
  still acked, so the client's prediction reverts, and it counts toward `stats.grammar-violations`. A dig is only
  checked at its start: vanilla finishes no dig it never started. Entity interactions are not checked. (The exempt
  eye, the light block and the grid came from a code review, 2026-10-23.)
- **Fix:** none in code.
- **Proof (exists):** cheat rows "ghost hand: opens a chest behind a wall" (the chest stays shut, `unseen-target` 1)
  and "ghost hand: starts digging a block behind a wall" (`unseen-target` 1). Their controls, "honest: opens a chest
  in sight (control)", "honest: starts digging a block in sight (control)" and "honest: right-clicks a lectern's top,
  above its cube (control)", count 0. Unit `InputRulesTest.anUnseenTargetIsCountedAndDropped`. Real client, 2026-10-23, run `classic10-blocks`
  (localhost and r150j30): 97 block clicks and 24 dig starts per condition, none `unseen-target`, 0 corrections, every
  claim exact. The clicks: a door toggled across a sweep of its wall, a chest, a lectern's top, a trapdoor, placements
  across a sweep, a 4-block sneaking backward bridge, and digs across a sweep of a wall.

---

## 8. Seeing other players

### VI-1 The 26.3 interpolation stutter (MC-311974) — Partial {#vi-1}

- **Players:** opponents stutter and freeze on 26.3 (MC-311974, MC-311976). RevivalPaper production moved back to
  26.2 because of it (README).
- **Cause:** client. 26.3's stepped interpolation runs dry with vanilla's every-2-ticks cadence.
- **Today:** K paths: one stepped move per simulated client tick, a keep-alive nudge, and pre-roll
  (`auth/replicate/PathFeed.java`, `auth/PathWiring.java`, patch 1014), within `replication.exact-radius` 24 blocks.
  K is per viewer: one whose own ack jitter stays under 25 ms for 5 s gets 1-tick steps (K1, 50-70 ms less view
  delay), else 2 (K2). A switch continues the paths in place (review 2 PT2-06), and a target walking into range is fed
  at the viewer's K (PT2-04). Beyond 24 blocks is vanilla cadence: in the torture rig the target froze on 97 of 6,138
  moving ticks there (1.6%, 4% at 30 ms jitter), which covers bow, rod and pearl aim at range.
  - Built (2026-10-23), off by default: `replication.ranged-radius`. A viewer holding a `ranged-items` item in either
    hand (bow, crossbow, rod, pearl, snowball, eggs, trident, wind charge), or one that held one within
    `ranged-linger-ticks` (100, so a pearl-sword switch doesn't drop the far players and re-add them), gets K paths out
    to that radius. Their mirrors are re-paired when inexact, as near ones are, which keeps lag-compensated arrows exact.
    The hands are read once a server tick per viewer. With the default of 0, nothing changes and the check isn't run.
- **Fix (netcode, config):** turn `ranged-radius` on at 64. That changes design §15.6 ("K2 paths, keep-alives and
  mirrors only within a combat radius ... beyond it vanilla-cadence output"), so it needs the maintainer's sign-off.
  The cost is a stepped move every K ticks per far player while a viewer aims, against vanilla's every 2 ticks while it
  moves, plus keep-alive nudges for still ones. Every server's revival.yml already holds the 0 by then, so the new
  default needs a `RevivalConfig` migration.
- **Proof:**
  - Exists: rig `full3`, 0 freezes in 2,865 moving ticks within 24 blocks; torture steady links, 0 freezes inside the
    radius once the harness stopped respawning the target (`duelfix`); live "auth: the viewer's mirror of a walking
    player never freezes and trails it by the K2 delay" and "auth: K per viewer: one-tick steps, the mirror exact and a
    tick less behind".
  - Exists: live rows "auth: ranged paths: a viewer holding a bow gets the walker's path out to ranged-radius (VI-1)",
    "...stays on the feed through ranged-linger-ticks" and "...after it, the vanilla cadence again, from a position sync"
    (with a sword at 30 blocks, the vanilla cadence; at the default 0, a bow changes nothing). Torture set `far` (real
    client, 2026-10-23, runs `far1` and `far2`, `ranged-radius` 64): the target strafe-jumping 30 and 45 blocks away at 0
    to 250 ms with up to 30 ms of jitter. Holding a sword (the vanilla cadence) it froze on 51 of 2,117 moving ticks
    (2.4%; 4.3% at 30 ms of jitter); holding a bow, on 0 of 2,103. 0 corrections, every claim exact, and the mirror's
    box equal to the client's on all 4,800 ticks).
  - To add: the sign-off, then the default.

### VI-2 Opponents are drawn behind (interpolation delay) — Accepted [limit] {#vi-2}

- **Players:** "I'm hitting where he was" ([18]; MC-302347).
- **Cause:** client. The 26.3 client shows remote players about 1.5 steps behind the newest data. Measured with a
  real client (torture rig): what one player does reaches another's screen RTT + 135-170 ms later with K2 paths
  (144 ms at RTT 0); a hit's rewind is about RTT + 110-135 ms.
- **Today:** lag compensation judges against exactly that display (HR-1), so hits are fair. The visual lag stays. K1
  takes 50-70 ms off for low-jitter viewers (VI-1); K2 is the smallest queue that never runs dry under more jitter
  (report 03, torture K1).
- **Fix:** none on a vanilla client (§13).
- **Proof:** none (inherent). The torture rig's view latency table documents it.

### VI-3 Invisible or teleporting opponents after a desync — Partial {#vi-3}

- **Players:** invisible players, opponents teleporting, "desynced positions".
- **Cause:** late or force-applied packets, or big jumps.
- **Today:**
  - An inexact mirror is re-paired (HR-5), never while the viewer's own acks lag (load P0, review 2 PT2-03).
  - A jump of more than 48 blocks re-spawns the target on that viewer (`respawn-distance`).
  - Re-pairs are counted (`AuthStats.mirrorRepairs`): 0 per 150 s window at 20 and 50 load bots, 36-86 at 100
    (`auth-load.md`).
  - Tracking range is vanilla's.
- **Fix (netcode):** none in mechanism. Show re-pairs per player-hour and `UNKNOWN INEXACT_TARGET` verdicts per 1,000
  attacks in `/revival auth inspect` and `debug.hud`.
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
  - Exists: live "auth: visibility: a player behind a wall is not sent" and "auth: visibility: shown again as soon as
    the wall is gone".
  - To add: a rig peek test with a strafe around a corner at 6 b/s. The appearance delay against culling off is ≤ 1
    tick at p99.

---

## 9. Movement cheats and corrections

### MV-1 Fly, speed, step, spider, jesus, phase, no-fall, no-slow — Solved {#mv-1}

- **Today:** authoritative movement. A bit-exact port of the 26.3 client's movement tick runs every client tick from
  the client's own inputs, and claims are compared, never trusted (`auth/sim/MovementSim.java` and the rest,
  `auth/reconcile/Reconciler.java`).
- **Found and fixed 2026-10-22:** a client that never reported `PlayerLoaded` had its moves replayed to vanilla once
  Paper's client-load timeout passed (about 3 s), a full bypass (cheat row "load: never sends PlayerLoaded, then claims
  a flight of 0.5 a tick past the server's load timeout": 10 blocks up in 20 ticks before the fix, none after). A move
  from a client that hasn't loaded is now never replayed. The chunk-unknown exemption now needs a chunk the server has
  sent (classic audit #19).
- **Proof (exists):**
  - cheat rows "fly: claims rising 0.25/tick", "fly: jump then hover", "speed: claims 1.5x the walk", "step: 1.0 block
    step-up", "phase: through a wall", "phase: through a closed door", "vclip: claims 6 blocks down through the
    floor", "spider: claims climbing a plain wall", "jesus: claims walking on water", "no-fall: claims onGround through
    a 12-block fall", "no-slow: sneaking at walking speed", "no-slow: blocking with a sword at walking speed";
  - the real client: 19,256 claims exact and 0 corrections over 116 scenarios at localhost, r80j10 and r150j30
    (`full1`); 100.00% exact at three conditions (`classic1`); 35,165 of 35,165 on the torture rig's steady links;
  - offline: 0 differences in 4,800,000 fuzzed ticks against vanilla's own code, and 80/80 golden client traces;
  - honest eating and drinking under enforce: "honest: a golden apple eaten under enforce (control)" and "honest: a
    Speed potion drunk under enforce, then a sprint (control)" (classic audit #9).

### MV-2 Timer, blink, lag switch — Solved {#mv-2}

- **Today:** `auth/net/TickClock.java`: a Grim-style balance timer on the ack clock, bank 10. The time a stall's burst
  forfeits is owed back, at most one bank, so an honest stall drains at once (torture F4); a window that waited past
  `timer-queue-cap-ms` (400) is refused. A player who stops sending ticks freezes for everyone. A window past 512
  packets overflows (a kick in enforce).
- **Proof (exists):** cheat rows "timer: 22 tps for 4 s", "timer: 30 tps for 4 s", "timer: blink (1.2 s without
  packets, then the burst)", "timer: 15 tps for 3 s, then a 10-tick burst", "timer: TickEnd never sent" and "timer: two
  move claims in one window"; cheat suite 2 "timer: three fake 1.2 s stalls back to back, walking" and "timer: freeze
  1.2 s, then run the 24 missed ticks at once".

### MV-3 Fake crits and ground spoofing — Solved {#mv-3}

- **Players:** packet crits (tiny hops claimed as falls) and ground spoof.
- **Today:** impossible by design. `onGround` comes from the simulation, and `fallDistance` follows server rules fed
  by simulated deltas (§15.5). `LegacyAttack.canCriticalAttack` reads them. The classic audit re-confirmed the missing
  row (tracked here).
- **Proof:**
  - Exists: "no-fall: claims onGround through a 12-block fall" (onGround lies counted, damage equal to an honest
    fall), and rig `attack_jump_crits` (honest crits, 0 corrections at three conditions).
  - Exists: cheat row "crits: claims a 0.0625 hop off the ground before the hit (packet criticals)": the hop
    corrected, the truth never off the ground, the damage the control's (8.0, no critical); control "crits: an honest
    hit standing on the ground (control)".

### MV-4 Rubber-banding of honest players — Solved {#mv-4}

- **Players:** setbacks and lagbacks from anticheats ([25]: Grim's setbacks need tuning; corrections that snap you
  back in time).
- **Today:**
  - Corrections are relative position + velocity, forecast to the tick the client applies them, measured from where
    pings land (`auth/net/LandingLag.java`: the NAS glitch fix, 2026-10-22; `round(rtt / 50)` put every correction one
    tick short at some pings). They go absolute only when they would land inside a block or keep missing.
  - They forecast the client with every knock still on its way (torture F5), and hold while forced entries are
    outstanding, for at most one ack deadline (review 2 X2-04: a 450 ms+ link was otherwise never corrected again).
  - The accept is checked as a claim.
- **Proof (exists):** 0 corrections on a real client: on the torture rig's steady links from 0 to 250 ms with ±30 ms
  jitter (44,519 ticks), at r80j10 and r150j30 (`full1`, `classic1`), and at r250j30 and r300j10 (real client,
  2026-10-22, run `classic4-bh`); 0 corrections for 20 and 50 load bots (`auth-load.md`). Stalls and big fights are
  NW-1 and SV-1.

### MV-5 Exempt states trust the client — Partial {#mv-5}

- **Players:** none, unless a cheater stands next to a piston, a shulker, a boat or a geyser.
- **Cause:** sim scope. These movers need client state the server can't always mirror exactly.
- **Today:**
  - Pistons and shulker boxes are banded: a push is matched only while the truth's box is in what the event moves (a
    piston's lane, a box's lid side), and only the part of the claim along the push is adopted, with its sign, at most
    0.51 a tick and the push's travel (a piston 1.02, a lid 0.6). Before classic audit #4, any 0.6 claim within 14
    blocks of any event was adopted: a self-made claim-wall climb.
  - Boats, shulkers and happy ghasts are the port's own colliders, at the boxes the client holds
    (`auth/combat/SolidMirrors.java`). A rod pull is modelled velocity, not an exemption.
  - Still exempt, vanilla taking the claim within `external-movers.max-move` (1.0): a solid entity in contact whose
    client copy isn't exact (until it is re-paired) and a potent sulfur geyser (`auth/ExternalMovers.java`). Riptide is
    exempt too (not ported). Vehicles are denied by default (`vehicles: deny`); under `vanilla`, riding is
    client-trusted.
- **Fix (sim, netcode):** port riptide; bound the geyser and inexact-copy windows tighter if a cheat row shows they
  matter.
- **Proof:**
  - Exists: unit `ExternalMoversTest` (lane, axis, sign, travel); cheat rows "piston: pushed along its lane, the
    claims adopted (control)", "piston: a piston 10 blocks off toggled, the claims rise 0.55 a tick (a claim-wall
    climb)" and "piston: in the lane, the claims rise 0.55 a tick instead of moving with the push"; cheat suite 2
    "solids: stand on a boat the server moved away (the client keeps its copy)", "solids: walk through a boat the server
    moved into the lane (the client ignores the move)", "solids: walk through a shulker spawned ahead, pongs held (the
    spawn's window)" and "solids: phase through a shulker the client dropped from its world" (0 mover exemptions); real
    client `n_boat_stand`, `n_boat_slide`, `n_boat_jump` (`full1`: 0 corrections, 0 exemptions).
  - To add: fly and speed during a geyser or inexact-copy exemption, bounded by `max-move`. "combat: attack from an
    exempt state (riding)" covers attacks only.

### MV-6 Teleport accept spoofing and forged accepts — Solved {#mv-6}

- **Today:** under enforce every teleport accept is judged as a claim, and vanilla never takes its coordinates
  (`AuthPlayer.onTeleportAccept`). An accept for a correction or a teleport never sent is a protocol violation. A
  teleport never accepted is counted, not kicked: the truth applied it at its pong, so it gains nothing. A server
  teleport whose pong misses the deadline is finished for the truth at the force, and its late landing re-applies
  nothing (review 2 X2-03, X2-05: holding a pearl's pong used to hide the truth and roll it back, and relative
  teleports were applied twice).
- **Proof (exists):** cheat rows "teleport: accept a server teleport with spoofed coordinates", "teleport: never
  accept a server teleport", "teleport: accept an id never sent (correction band) to drop fake walls", "teleport:
  accept a vanilla id never sent", "non-finite: NaN accept for a server teleport", "non-finite: NaN accept for our
  correction" and "teleport: a server teleport that reaches the client 1.2 s late, walking".

---

## 10. Network quality

### NW-1 Real ping, jitter and packet loss — Partial {#nw-1}

- **Players:** Wi-Fi and long-distance players: rubber-banding, delayed knocks, freezes ([24]; "Grim is sensitive to
  poor player networks", [25]).
- **Cause:** network. TCP head-of-line blocking turns loss into bursts.
- **Today:** measured with the real client through a latency proxy (torture rig, `auth-torture.md`; the enforce-neue
  runs):
  - Steady links from 0 to 250 ms RTT with up to ±30 ms jitter, bandwidth caps and bursty delivery: 0 corrections, 0
    forced entries, every claim exact, every hit `VALID`. The same for sprint block-hitting at r250j30 and r300j10, ack
    RTT p50 9, 163, 260 and 312 ms (real client, 2026-10-22, run `classic4-bh`).
  - Wi-Fi hiccups: after the stall fixes (torture F2-F5, review 2 X2-02 and X2-04), a 300 ms stall every 4-5 s in a
    minute-long duel costs 0 corrections (`glitch-fix7-marathon`, seven conditions; torture `audit4`, real client,
    2026-10-22: 0).
  - Hitches: client hitches up to 1 s and server hitches up to 1.2 s cost 0 corrections (`glitch-fix7-hitch`; again
    2026-10-23, `nw1-fix-hitch`, five conditions). The 2 s client freeze, past `max-unacked-ms`, is below.
  - Hits made during a long stall can still be lost: 176 of 184 `VALID` after a 1 s stall (`stallfix3-marathon`).
  - Real internet: the server has run on the NAS over Playit since 2026-10-21, played with Lunar; those reports
    (block glitching, jitter) led to the block-glitch fix (WD-2, MV-4).
  - Loss (real client, 2026-10-23, run `classic11-loss`; extras, combat, far). The proxy is TCP-level, so a lost
    segment shows as what TCP makes of it: held for a fast retransmit or a timeout, `max(200, RTT + 150)` ms, with
    everything behind it (netem `loss_pct`, per chunk, each way). 0.5% at 80 ms: 0 corrections, every claim exact,
    338 of 350 hits `VALID`. 2% at 80 ms: 6 corrections, all in one Speed-effect scenario, 99.95% exact, no hit
    refused. 2% at 150 ms: 36 corrections and 42 `INVALID MISSED`, nearly all in the long duel: a 300 ms retransmit
    outlasts the ack deadline (7 paced windows at 150 ms), so knocks and the target's path steps are forced before the
    client has them, and its view of the target falls behind the server's mirror (95 forced entries, 183 mirror-box
    differences).
  - A 2 s client freeze, past `max-unacked-ms` (torture `t_hitch_client_over`: a duel, the target knocking every 15
    ticks; 2026-10-23, three runs per condition): no kick (keep-alives go out unbundled, NW-2), and the recovery is one
    correction at localhost, r80j10, r150j30, r150j10 and r250j30 in all 15 runs (`nw1-fix-1..3`). Each is relative,
    0.22 blocks (the strafe tick the truth refused), lands the client on the truth to the nanometre, and lands 100 to
    700 ms after the freeze ends (localhost to 250 ms). The NAS build before it (`nw1-base-1..3`) took 4/4/4, 6/14/4,
    4/25/2, 22/2/2 and 12/4/19, about half absolute, up to 4 blocks.
  - What chained (traced tick by tick in `freeze2` and those runs; `auth-netcode.md` has the mechanism now):
    - The first correction went out absolute (the refused tick left the belief stale) in the client's catch-up burst,
      forecast from its first windows while the rest of the burst waited off the wire: it landed 10 to 15 windows late,
      a block off. Now the belief runs the refused tick, a claim it explains clears the stale flag, and the correction
      waits for the burst (`TickEnd`s waiting for the main thread, a catch-up tick last).
    - The landing lag measured the freeze: a burst's first flush, with the rest of the burst behind it, read 11 windows
      at 80 ms, and the freeze aged every older landing out, so the first corrections after it were forecast 9 or 10
      windows long at 80 and 150 ms. Now nothing around a freeze is sampled, and landings age by the client's ticks.
    - The belief never took the knocks the client applied, and the accept backstop forecast without the knocks still on
      their way or later in its drain, so it sent a knock's flight as a correction (1.9 blocks); a deferred correction
      missed the knocks of its own window's drain. Both take them now.
    - Every second backstop went absolute, converging or not, carrying the forecast's inputs and yaw. Now only a
      backstop that doesn't halve the gap counts.
    - With those (single runs) one correction remained, plus a 0.2 to 2.4 mm one: the first was decided while the gap
      still moved (its velocity not yet died out, a knock on its way), landed a window off its forecast, and the next
      knock froze the miss for the persist rule. Now it waits until the gap settles: the knock lands (it replaces both
      velocities), or the drift over the forecast is under `correction-persist-min`.
  - Two earlier attempts, each with one of these alone, moved the counts within one run's noise (`freeze3`, `freeze4`).
- **Fix (netcode):** for loss, an ack deadline that waits, bounded, while the entry's bytes are still unacked by the
  client's TCP (`TCP_INFO` on Linux, the NAS): a retransmit is the network, a pong held back with the bytes delivered is
  the client. A plain longer deadline would hand the same slack to a client holding pongs.
- **Proof:**
  - Exists: the torture runs above (`grid1`, `stall1`, `marathon`, `marathon2`, `hitch1`, then `stallfix3-marathon`,
    `stallfix4-hitch`, `glitch-fix7-*`), `full1` and `classic1`, and real client, 2026-10-22, runs `classic4-bh` and
    torture `audit4`; 2026-10-23, `classic11-loss`, torture `freeze2`, and `nw1-base-1..3` against `nw1-fix-1..3`
    with the regression runs `nw1-fix-hitch`, `nw1-fix-combat` and `nw1-fix-marathon` (`auth-torture.md`, "NW-1").
  - To add: at 0.5% and 2% real loss, ≤ 1 correction per minute and every client-picked attack `VALID`.

### NW-2 An honest lag spike kicks the player — Solved {#nw-2}

- **Players:** a 5-second Wi-Fi hiccup in an HCF fight disconnects you, and the combat logger dies.
- **Cause:** policy. Past `max-unacked-ms` (1,500) the player was frozen and at 3× (4.5 s) kicked: "Timed out (no
  transaction replies)". A frozen target also became unhittable once its last path step aged past the attackers' caps
  (review 2 X2-01).
- **Today:** a stall freezes and never kicks (`AuthPlayer.updateFrozen`, `onServerTick`). The frozen player doesn't
  move, its knocks are force-applied at their deadline, and it stays hittable where it stopped: past the cap the judge
  asks its truth at the capped age, which is its newest step while its `ServerPlayer` is still there and no teleport is
  pending (`combat/TruthPaths`, `AuthPlayer.stillAt`). Paper's keep-alive timeout (30 s) disconnects a connection that
  is really gone. Only protocol violations kick at once (forged, skipped or unknown pongs, overflow); ordering-only
  ones kick past `client-profiles.protocol-kick-rate`. Keep-alives go out unbundled (2026-10-23): one inside the tick's
  bundle waited for the client's main thread, so after a stall past a second the client answered a later, lone one
  first, and Paper kicked it for a keep-alive answered out of order (torture `freeze1`, a 2 s client hitch at 150 ms).
- **Fix:** none.
- **Proof (exists):** cheat rows "stall: 4.5 s with nothing in or out, walking" (not kicked, frozen during the stall,
  recovered with 1 correction), "stall: 12.0 s with nothing in or out, standing" (0 corrections), "stall: 1.0 s with a
  server teleport sent into it" (no violation) and "combat: hit a target whose link stopped 2 s ago, where it stopped"
  (`VALID` at 2050 ms past a 202 ms cap); the torture marathon and hitch runs: no kick after the stall fixes.

### NW-3 Tab ping doesn't match what authority judges with — Solved (they match) {#nw-3}

- **Players:** arguments about who is "really" lagging; ping spoofing as an excuse.
- **Cause (claimed):** tab latency comes from keepalives, while authority times with the ack RTT (`rttBase`, a low
  percentile of the last 256 acks, or of the last 32 when that is higher).
- **Today:** the two measure the same path and agree. Paper's improved keepalives give the tab a 5-second average of
  per-second keepalive round trips (`ServerCommonPacketListenerImpl.handleKeepAlive`). The 26.3 client answers a
  keepalive on its network thread and the authority's pings on its main thread, so the tab can read up to a frame and a
  tick lower. (Until 2026-10-23 a keepalive that went into the tick's bundle was answered on the main thread too, which
  kicked stalled clients, NW-2.) `rttBase` is lower by design: a floor for the
  timing caps, not a ping to show. Seconds where the ack RTT runs more than `pong-lag-ms` (150) past the keepalive
  are counted (`pongsBehindKeepAlive`, review E5).
- **Fix:** none.
- **Proof:** real client, 2026-10-22, run `classic6` (about 43,000 samples): the tab latency median against the median
  last ack RTT: 7.0 and 10.1 ms at localhost, 167 and 157 at r150j30, 264 and 257 at r250j30, 308 and 310 at r300j10.

---

## 11. Server performance and server-side bugs

### SV-1 Authority in big fights — Partial {#sv-1}

- **Players:** TPS drops in big fights, "entity lag", "chunk lag" ([23]: PandaSpigot's tracker and flush work; [24]).
- **Cause:** server load.
  - Movement simulation for everyone.
  - Mirrors per viewer and target.
  - K paths: one move per tick per pair within 24 blocks, against vanilla's one every 2 ticks.
  - One ping bundle per connection per tick.
  - Visibility rays.
  - Death drops, potions and arrows.
- **Today:** measured with honest bots on real TCP playing 1.7 HCF in one arena (`auth-load.md`, the merged build
  `69b7b5f6`):
  - 20 and 50 bots: 0 corrections, every compared claim exact, every hit `VALID` (5,466 and 17,447), 0 forced entries.
    The server thread runs 0.09 and 0.15 cores; authority costs about 100 µs per player per tick. Down 54 KiB/s per
    player at 50 (vanilla, `mode off`, sent 96 on the earlier jar).
  - 100 bots (`auth-load.md`, "Stall re-run on the classic build", run `audit7-hitch100`, the current tree): MSPT mean
    6.4 ms, p99 7.1 ms. Server hitches of 450 ms and 2.3 s were injected: every bot was back to 0 corrections within 6 s
    and 7 s, and none looped (the 09:30 build left 5 bots looping after a 441 ms hitch and 47 after a 2.3 s one).
    98.6% of judged hits `VALID` (the rest mostly `MISSED` inside the correction bursts). Forced entries are still about
    700 a second, from the in-JVM bots' ack RTT.
  - 50 bots at about 125 ms RTT (run `audit7-n50-125ms`): every claim exact, 0 corrections, 0 forced entries, 18,426
    hits accepted, 0 `REWIND`; MSPT p99 3.3 ms.
  - 200 bots spread over a 320×320 map, fighting locally (each seeing 46 players on average, 87 at most; run
    `audit8f-n200-spread`): every claim exact (600,713), 0 corrections, 0 forced entries, all 46,103 hits accepted;
    TPS 20, MSPT p99 8.6 ms. 200 in one 64×64 box overwhelms the single-machine harness (the in-JVM bots ack 400+ ms
    late on localhost: run `audit7-n200-nolocator`), so that case needs the bots on another machine.
  - The vanilla locator bar is the largest O(N²) cost: `gamerule locator_bar false` cut bandwidth by 55% at 50.
  - Memory: no leak; players who quit are released (review 2 PT2-02).
- **Fix (netcode, config):**
  - Separate the server's Netty from the bots' (bots in a second JVM, per-bot forced counts and ack-RTT p99), to see
    whether the forced entries at 100 are the server's.
  - `gamerule locator_bar false` on PvP servers.
  - Re-run the matrix on the current tree, plus 50 bots at about 125 ms RTT on the new rewind cap, 100 with the locator
    bar off, and 200.
- **Proof, budgets:** met: at 100 players every bot back to 0 corrections within 10 s of a server hitch (6 s after
  450 ms, 7 s after 2.3 s), MSPT p99 < 25 ms (7.1 ms), bandwidth ≤ 2× vanilla (below vanilla). To add: ≥ 99% of honest
  hits `VALID` at 100 through a stall (98.6%), and 200 players (the bots on a second machine). Met too: 0 `REWIND` for
  50 bots at 125 ms.

### SV-2 Server lag eats hits — Solved {#sv-2}

- **Players:** "server lag eats hits", combos that break on a TPS drop.
- **Cause:** server clock. During a stall, clients keep ticking. Their windows queue and are judged correctly, against
  the client tick each click was made in. But damage windows only count server ticks, so clicks from queued windows
  meet windows that should already have opened by real time. The knock then goes out late. After the stall, Paper's
  catch-up ticks run back to back and count a window down in a few milliseconds, so a hit dealt in the stall's burst
  gets its next window early.
- **Today:**
  - Verdicts don't charge a hitch to the attacker: rewind ages are on the lag-compensation clock
    (`Authority.clockMillis`, torture F6), and a stall's burst drains at once (F4). On the torture rig, server hitches
    of 0.3-1.2 s cost 0 corrections and every hit stayed `VALID` (`stallfix4-hitch`).
  - The melee window follows the clicks' clock around a stall (2026-10-23, `damage-windows.melee-wall-clock`, on by
    default; `auth-combat.md` §6). While the server is or was a tick behind its schedule since the window's fresh hit
    (`Authority.behindSince`), a legacy melee click on a player that authority delivers sets the victim's counter,
    before its damage, to what is left of the window between its arrival (its window's `TickEnd` off the wire) and the
    arrival of the click that opened the window, rounded to whole ticks (`DamageWindows.catchUp`). A window real time
    opened is open; one the catch-up ticks counted down early stays shut. A plugin's `setNoDamageTicks`, or a window
    another damage or an untracked hit opened, stands. The click buffer reads the same counter, and right after a stall
    a held click waits a tick for the clicks the client sent through it (`AuthPlayer.landingWaits`).
  - On schedule nothing changes: the window is vanilla's counter, which still drives the hurt animation, plugins and
    every other damage.
- **Fix:** none left for player melee under authority. Projectiles, mobs and other damage, and players outside
  authority, still count server ticks.
- **Proof (exists):**
  - unit `DamageWindowsTest` (a stall doesn't keep the window shut; catch-up ticks don't open it early; jitter rounds to
    the client's spacing; a plugin's write and another fresh window win; only a fresh hit restarts the clock; on
    schedule the counter is vanilla's bit for bit over 2,000 jittered clicks), `PauseClockTest` (behind from a hitch on,
    never on per-tick jitter);
  - cheat suite 2 "server lag: a still target clicked at 12 CPS through a 200 ms stall every 40 ticks"
    (`auth-cheat-suite-2.md`, "Server lag"): a plugin sleeps the main thread 200 ms every 35-45 ticks while a fake
    client on its own thread, its TickEnds stamped as they arrive, clicks a still target 2, 2, 1 ticks apart. Fresh hits
    a second over 600 client ticks, and the gaps between them in real time:

    | Rule set | No stalls | Stalls, the window on server ticks (before) | Stalls, the clicks' clock |
    |---|---|---|---|
    | `classic` | 2.00, every gap 10 ticks | 1.95: 5 of 58 gaps 12-14 ticks | 2.00, every gap 10 ticks |
    | `classic-capped` | 2.00, every gap 10 ticks | 2.00, but 4 gaps of 7-8 ticks and 4 of 12-13 | 2.01, every gap 9-11 ticks |

    The full live runs of 2026-10-23; runs of the row alone gave the same. On `classic-capped` the click buffer hid the
    loss in the rate: the hits the stalls delayed were made up by hits the catch-up ticks let in early. The 9s and 11s
    are a held click's landing (at the start of a tick) next to a direct click (mid-tick), half a tick apart in one
    server tick.

### SV-3 Leaf and Paper settings that drop or delay knockback — Solved {#sv-3}

- **Today:** authority refuses (stays off, logs once) with any of Leaf's `async-entity-tracker`,
  `parallel-world-ticking`, `reduce-entity-motion-packets` (drops velocity packets: knockback) or
  `reduce-entity-move-packets` (drops move packets) on (`Authority.refusal`). `OptimizeNonFlushPacketSending` is warned
  once when authority runs: packets keep their order, but one sent without a flush waits for the next.
  `use-legacy-tracker-ticking` stays documented (`leaf-defaults.md` §1b-1c): it moves when the tracker runs, not what
  it sends.
- **Fix:** none in code.
- **Proof (exists):** unit `LeafRefusalTest.packetReductionsKeepAuthorityOff`; live "authority refuses to start with
  Leaf's async tracker".

### SV-4 An exception in authority code kicks the player — Solved {#sv-4}

- **Players:** "Internal server error" in a fight.
- **Cause:** a port exception outside the guarded `sim.tick` (`README-enforce.md` root cause 3).
- **Today:** every simulation and combat call in a window (a tick, a drain entry, a correction, an attack's judge)
  falls back to the client's claim for that tick and re-seeds (`auth-netcode.md` "Enforce extras"). Turning authority
  off and back on no longer throws in activation (review 2 R2C-01: `ArrivalStamps`' handler is `@Sharable`, and a
  refusing pipeline falls back to main-thread time); before, it kicked everyone online.
- **Fix:** none in code.
- **Proof (exists):**
  - R2C-01: unit `ArrivalStampsTest.installsAgainAfterAnUninstall`.
  - Fault injection, cheat suite 2 "server faults: the port throws on 1 call in 8 of each heavy method (its first
    included), the judge on 1 attack in 4, through a 300-tick fight". The live test swaps in a port that throws (a
    proxy over `Simulation`, through `Authority.setSimulationFactory`) and a judge that throws. Both players are under
    enforce, and the victim ignores knockback so corrections run too: 191 faults (the tick 76, the belief 63, a
    correction 26, the strength retry 12, the attack 4, a velocity 1, a re-seed 2, the judge 7), every one counted as
    a simulation error, nobody kicked, both still under enforce, and with the faults gone the attacker is exact again
    (0 corrections, 12/12 claims).

### SV-5 Plugins and anticheats that write physics packets around the choke point — Partial {#sv-5}

- **Players:** rubber-banding when another plugin teleports or knocks players.
- **Cause:** PacketEvents, ProtocolLib or Grim writing velocity, teleports or blocks directly.
- **Today:** `auth/net/BypassAuditor.java` notices them (as `Packet` objects or pre-encoded). Blocks get upper-bound
  timing; teleports and velocity mark the player for resync. GrimAC's movement, velocity and setback handling must be
  off for players under authority (warned once when its packets show up).
- **Fix (config, core):** route core's knockback and teleports through Bukkit (the choke point). Document the Grim
  config.
- **Proof:**
  - Exists: unit `BypassAuditorTest` (packet level, pre-encoded, skipped expectations).
  - To add: live. A plugin sends `SetEntityMotion` through PacketEvents: counted, resynced, ≤ 1 correction.

### SV-6 Stock movement checks fight the truth — Solved {#sv-6}

- **Players:** a player knocked off a ledge gets "moved too quickly" setbacks in a loop (`README-enforce.md` root
  cause 4).
- **Today:** patch 1012's `revivalApplyTruth` skips Paper's moved-too-quickly, moved-wrongly and clipped-into-block
  checks for the truth. The truth's move skips the second collision pass while the `ServerPlayer` is where the last
  truth write left it (`Entity.revivalMoveResolved`; review 2 R2C-08: after a server push the collided branch runs).
- **Fix:** none.
- **Proof (exists):** live "auth: projectile knocks on a running victim reach client and truth at their ack tick"
  checks that Paper's movement checks never judged a truth under enforce (`PlayerFailMoveEvent`: 0); `TruthPathCheck`
  (the truth path equals the claim's branch in state, events and packets over 420 ticks); the torture and enforce-neue
  rigs run with stock Spigot thresholds (0.0625 / 10) and `player_movement_check` on, and the second player under
  authority took 0 corrections in `full1`.

---

## 12. Clients, mods and eligibility

### CL-1 Lunar's Apollo combat options and PvP-client mods against the exact grammar — Partial {#cl-1}

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
- **Today:**
  - `auth/view/ClientProfile.java` reads the brand, the `lunar:apollo` channel, the Apollo combat options the server
    pushed (by reflection, `client-profiles.apollo-rules: follow`) and Lunar's version and mods, shown by `inspect`.
  - One rule reads it (2026-10-22): `ATTACK_AFTER_CLOSE` doesn't hold while the server has turned a player's miss
    penalty off through Apollo (`InputRules.screenEatsClick`), so a Lunar player's first click after a pot refill is
    neither dropped nor counted toward a staff alert, whichever way Lunar treats it. The option is server truth; a
    client faking Lunar gains one click a tick earlier after a screen. Dig-and-use needs nothing: no rule gates a use
    while destroying.
  - Kicks for packets a client sends outside the vanilla tick, `client-compat.md`'s top risk, are closed:
    ordering-only violations (a pong after the tick's keybinds, a second input or sprint command) are counted and kick
    only past `client-profiles.protocol-kick-rate` (20 in 60 s).
  - A client claiming Lunar gets nothing for it: reach and autoblock are refused as for any client.
  - The NAS world has been played with Lunar over the internet since 2026-10-21. Whether HCFR's Apollo combat options
    were pushed there is not recorded.
- **Fix (netcode, config, core):**
  1. Verify what each Apollo option changes in the packet stream (a Lunar session with `debug.log`).
  2. Done for `ATTACK_AFTER_CLOSE`; any future dig or use rule must read the profile too (`client-compat.md` §7 N5).
  3. Decide the fairness policy. Options differ between Lunar and vanilla players, so either keep Apollo combat off
     on authoritative servers, or accept the asymmetry openly.
- **Proof:**
  - Exists: cheat rows "pongs: pong placed after a keybind packet" and "grammar: two PlayerInput packets in one tick"
    (counted, not kicked); cheat suite 2 "client profile: claims Lunar (lunarclient brand, lunar:apollo registered)";
    unit `InputRulesTest.anAttackAfterACloseIsFineWhenTheServerTurnedTheMissPenaltyOff`.
  - To add: the Apollo stand-in in the rig agent (`client-compat.md` §9.1 R1: the miss-penalty and dig-use variants,
    `whiff_then_click`, `refill_then_click`), with 0 corrections and no grammar violation except the expected
    `attack-after-close`. Then a recorded 30-minute Lunar session with HCFR's Apollo options on: `grammar-violations`
    and corrections both 0.

### CL-2 Non-26.3 clients bypass authority — Partial {#cl-2}

- **Players:** ViaVersion-translated clients (1.8 via ViaRewind) and Geyser players play on vanilla handling: Paper's
  lenient reach, client-trusted movement, and different hit rules (1.8's 0.1 pick margin).
- **Today:** `auth/Eligibility.java` turns authority off for them (fail-open). `authoritative.ineligible: deny`
  refuses them while the mode is enforce (review E4); the default is `allow`. hcfr-core deploys neither ViaVersion nor
  Geyser, so no such player can join HCFR today (`client-compat.md` §10).
- **Fix (config):** `deny` on authoritative servers, or a `separate` policy that routes those players to
  non-authoritative arenas (not built). Check the K paths sent to an ineligible viewer (`client-compat.md` N9).
- **Proof (to add):** a join test with a Via-translated client and a Geyser client under each policy, plus the N9
  viewer check.

### CL-3 Other anticheats false-flag legit 1.7 play — Partial {#cl-3}

- **Players:** bans for w-tapping, fast clicking or sprint behaviour ([8]: Grim #2818, #2866, WTapA).
- **Today:** `grimac-checklist.md` lists the risky checks, but it was never run with a real client. Under enforce,
  authority supersedes Grim's movement, velocity and reach.
- **Fix (config):** run the checklist. Turn off the Grim checks authority supersedes for players under authority. Keep
  Grim only for what authority doesn't do, if anything.
- **Proof (to add):** the checklist's pass criteria, recorded with Grim's version and config.

---

## 13. What a vanilla client can't fix, and what a light client mod would change

The design's non-goal stands: no mod is required (`authoritative.md` §1). This section says what is lost by that. Decided 2026-10-23 (the user): vanilla only, no light mod; the [limit] items below are accepted.

A mod is never a security boundary: anything it does, a cheat client can fake. So a mod can only improve how the game
feels for honest players and widen what the server's rules can express. The authority must keep judging every packet
as it does now.

| Limit | Why the server can't | What a light mod could do | Already in the ecosystem |
|---|---|---|---|
| Remote players drawn RTT + 135-170 ms behind with K2, 50-70 ms less with K1 (VI-2) | Interpolation is client code | Render with K=1 or extrapolate; the server's mirror must then port the mod's renderer exactly | Extrapolate mods [18] |
| Knock applied on arrival (KB-3) | The client applies velocity when it drains it | Client-side rollback: re-run its own last N ticks with the knock at the tick the server stamped | None known |
| Miss penalty, dropped click after a screen (HR-9) | `missTime` is client state | Disable it | Apollo `DISABLE_MISS_PENALTY` [20], HitDelayFix [1] |
| 1.7 pick margin, crouch box (HR-7, HR-8) | Pick and box are client code (partly reachable through `attack_range` and pose rewrites) | 1.7 boxes and eye heights | Animatium `pick_inflation`, `old_sneak_height` [1] |
| Clicks quantized per tick (IN-6) | Input is consumed in the client tick | Sub-tick timestamps for feel; the server can't trust them | Click Between Ticks [11] |
| Hit feedback after RTT (ghost-hit feel) | Damage on others is server-decided | Predict the hit flash and sound, then roll back if refused | DiolezHitregistry [22] |
| Neue body hitboxes (`neue-combat.md` §9) | The client picks the box | Pick model parts, mirrored by the server | Combo's own client |
| 1.7 movement physics | The authority ports 26.3's client movement, by design | Not without a full client | none |

The realistic lever is Apollo: Lunar already exposes server-controlled combat options to most of this crowd (CL-1).

## 14. Test backlog (the proofs to add)

Grouped by harness. Each line points at its item. The proofs this list used to hold for HR-2, HR-6, IN-1, IN-5, PR-3,
PR-8, NW-2, SV-2 and SV-6 exist now (see each item).

**Unit** (`./gradlew :leaf-server:runRevivalTests`):

- tab latency from ack RTT (NW-3);

**Cheat suite** (`AuthCheatScenarios`). Green on `classic-capped` (133/133; cheat suite 2 56/56); the table in
`auth-cheat-suite.md` predates the classic-audit rows. New rows:

- fly and speed during a geyser or inexact-copy exemption (MV-5);
- PacketEvents velocity bypass (SV-5);
- `stop-sprint-on-block` on (BL-3).

**Live** (`AuthLiveScenarios`):

- asymmetric-latency trade order (HR-10);
- knock direction at 100 ms (KB-4);
- rod and snowball at 0/100/200 ms on a strafing target (PR-1);
- the pearl glitch suite on a running server (PR-7);
- potion spawn origin, to the bit (PR-5);
- Via and Geyser joins under each `ineligible` policy (CL-2).

**Real client** (`~/Developer/tools/auth-rig/enforce-neue`, `torture`):

- real packet loss at 0.5% and 2% (NW-1);
- the K view at 30 and 45 blocks (VI-1);
- a sneaking target's box (HR-8);
- `ATTACK_RANGE` picks (HR-7);
- the peek test (VI-4);
- the Apollo stand-in (R1) with `whiff_then_click` and `refill_then_click` (CL-1, HR-9);
- the first sword hit after a swap, with `hide-cooldown-on-items` on (SP-5);
- the shared-flags echo fix at 300 ms (SP-2).

**Load** (`~/Developer/tools/auth-rig/load`): re-run on the current tree; 100 bots recovering from a 500 ms hitch,
with the bots in a second JVM; 50 at about 125 ms RTT; 100 with the locator bar off; 200 (SV-1).

**Playtest** (with `debug.hud`):

- knock-apply-lag per ping band (KB-3);
- the victim-side rewind metric (HR-3);
- re-pairs and inexact verdicts (VI-3);
- damage-window split A/B (PR-6);
- RedLine `potionAccuracy` before and after the legacy falloff, core's precision off (PR-4);
- real click, rotation and jump data to calibrate IN-3, IN-4 and KB-10;
- the GrimAC checklist (CL-3);
- a recorded Lunar session with HCFR's Apollo options on (CL-1).

**1.7.10 reference** (once): a stationary straight-down Health II pot heals 7, sprinting 6; a level pearl from
standing lands 15.3 blocks out (PR-3, PR-4; `projectiles.md` §11).

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
21. Mojira: MC-311974, MC-311976 (26.3 stutter), MC-302347 (visual delay), MC-297196 (knockback delay), MC-69459
    (the client copies attribute syncs).
22. Client-side hit prediction: https://modrinth.com/project/DsL0pTWX
23. PandaSpigot (tracker flush consolidation, tick loop): https://github.com/hpfxd/PandaSpigot
24. Ping spikes on chunk loads: https://github.com/Tuinity/Tuinity/issues/34
25. Anticheat review (custom knockback breaks default checks; network sensitivity):
    https://mineguard.pro/en/blog/best-minecraft-anticheats-2026

Repository and project:

- RevivalPaper PRs #3-#19 (issues are disabled on `hcfrevival/RevivalPaper`). hcfr-core issues #13, #34, #53, #54
  (true 1.7 crits, "not fixing"; `LegacyAttack.canCriticalAttack` already follows 1.7.10), #109, #115, #321, #354.
- `docs/revival/auth-research/02` (client clicks and screens), `03` (interpolation), `06` (prior art).
- Audits and reviews: `auth-classic-audit.md` (2026-10-22, the classic lens on this checklist), `auth-review-2.md`,
  `auth-cheat-suite-2.md`.
- Real-client evidence: `~/Developer/tools/auth-rig/README-enforce.md` (runs `full3`, `stock2`; enforce-neue `full1`,
  `neue2`, `classic1`), `enforce/runs/full3/probe.jsonl` (rewind ages), `auth-torture.md` (the torture rig's runs).
  Runs of 2026-10-22 not yet written up, cited as "real client, 2026-10-22": `classic2-far`, `classic3-hi`,
  `classic4-bh`, `classic5-stopsprint` (enforce-neue on `classic-capped`) and torture `audit4`.
- Load: `auth-load.md` (bots on real TCP, `~/Developer/tools/auth-rig/load/runs/`).
- Real network: the authoritative server on the NAS over Playit since 2026-10-21, played with Lunar.
