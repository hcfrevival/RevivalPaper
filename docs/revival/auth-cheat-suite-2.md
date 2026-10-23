# Authoritative cheat suite, round 2

Status: 2026-10-22, branch `revival/authoritative` at `69b7b5f6` (the server-thread performance merge), run on a frozen
APFS clone with the private live runner, async and sync trackers. Code:
`leaf-server/src/revivalLiveTest/java/gg/hcfrevival/revival/livetest/AuthCheatScenarios2.java`, registered last in
`RevivalLiveTests` (after `NeueLiveScenarios`). Round 1 (`auth-cheat-suite.md`, 83/83) is unchanged and still green in
the same run.

Round 2 aims the same method at what landed after round 1: Neue (click buffer, click cap, parry, stagger, riposte,
DI), K per viewer, the post-stall timer bank, selective pongs, exact solid entities, the teleport hold and pearls, the
shooter's view for rods and snowballs, and client profiles. Every cheat sits next to an honest control in the same
scenario or the row before it.

## What is new in the harness

`AuthCheatScenarios2.Client` is round 1's `Cheater` (a fake 26.3 client that reads what the server wrote and composes
every serverbound packet, its movement the real port), plus:

- **a Punch after every attack**, as `Minecraft.startAttack` sends it (round 1 sent `Attack` alone), and the option to
  leave it out;
- **cooldowns, health and solid entities** in its own model (`ClientboundCooldownPacket`, `SolidMirrors` fed the solid
  entity packets and ticked after the player), with options to ignore solid updates or drop a copy;
- **two pong policies**: round 1's `holdPongs` (the link holds the ping and everything behind it), and
  `withholdPongs` / `pongHoldUntil` / `skipKnockPing` (the cheat reads every packet at once and only holds back, or
  skips, its pong replies: it sees the knock or the parry and chooses when the server learns it);
- **latency** (`latencyTicks`), **no chunk-batch acks**, hotbar and use actions, custom payloads (brand, channels);
- a **rotation nudge** at `ready`: the client re-sends its rotation once, as any mouse movement would (see H1 below:
  without it a client facing anything but yaw 0 is judged facing yaw 0).

Iteration aids, unset in real runs: `-Drevival.cheat2.only=<method>,<method>` runs only those scenarios, and
`-Drevival.cheat2.trace=true` logs every client's state each server tick (`REVIVAL_CHEAT2_TRACE`).

Running it: the normal live run (`./gradlew createPaperclipJar :leaf-server:revivalLiveTestPlugin && python3
scripts/revival/live-tests.py`). The suite adds about 4 minutes to the sync run and nothing to the async one (authority
refuses there). Rows print as `REVIVAL_CHEAT2`; the last step fails the run if any row leaked or hurt an honest client.

## Verdict

46 rows on the frozen tree: 30 cheats blocked, 9 honest controls fine, **4 cheats leak** (5 rows) and **2 honest-client
bugs** surfaced from the controls. Round 1 stayed 83/83 in the same run, and the async-tracker run passed all 123 of
its checks (authority refuses there, so neither cheat suite runs in it).

| | Finding | Kind | Size |
|---|---|---|---|
| L1 | An `Attack` sent without its `Punch` is no swing: block right after it and the block-hit rule never applies (a trade that fishes a full parry) | cheat, p1 | parry window 3 instead of 0 |
| L2 | The click-cadence detector counts `Punch` packets only: a 20 CPS autoclicker that leaves them out reads as no clicks at all (and viewers see no swing) | cheat, p2 | 120-tick lock detected with the Punch, 0 without |
| L3 | The stagger lockout is counted in client windows with no server-time floor: running the client fast through it ends it early | cheat, p2 | 3 of 11 ticks (150 ms) at 0 and 200 ms |
| L4 | During the stagger another sword type can block, and that block parries the riposte | cheat, p1 | the riposte parried, the parrier staggered |
| H1 | Authority starts with the client's rotation unknown (0/0) until it turns: a player facing anything but yaw 0 who hasn't moved the mouse is judged facing yaw 0 | honest, p2 | hit refused (MISSED), strafe corrected 3 times |
| H2 | A hotbar key and a right click in the same tick: the port starts using the old hand item, so the server never blocks with the new sword | honest, p1 | no server block, no simulated use; a vanilla client blocks |

All six were checked against candidate fixes in a scratch clone (not this tree): with them every row above is
blocked or honest again, every control still passes (section "Fix trial").

## Results (2026-10-22 08:46-09:02, sync run nonce `5fba39d4`)

Outcomes as the suite printed them. "Blocked" means every assertion held: the truth is the honest one, the action was
refused, corrected or counted, and the honest phase after it (where there is one) was exact.

| # | Cheat | Outcome | Evidence |
|---|---|---|---|
| 1 | honest: a client facing yaw 180 that hasn't turned since authority started attacks and strafes | **honest client affected** | FAILED: the attack straight ahead landed: 0 (client yaw 180.0000, the server's tick rotation 0.0000, verdict INVALID MISSED); FAILED: the strafe needed no correction: 3 (exact 13/14); after one mouse movement: landed 1 (tick rotation -151.7129) |
| 2 | honest: take the sword and block in the same tick | **honest client affected** | control, a tick apart: the server blocks true, the truth uses the sword true; FAILED: in one tick: the server blocks false, the truth uses the sword false (a vanilla client does both) |
| 3 | neue click-buffer: an honest click into the window lands on the opening tick (control) | control | two fresh hits, the second held and landed (health 4.0, buffered landings 1) |
| 4 | neue click-buffer: pre-load clicks from out of reach (3.6), walk into reach for the opening tick | blocked | every pre-loaded click refused: judged out of reach 5, over the click cap (not judged) 1, judged valid 0; nothing held while out of reach; nothing landed when the window opened (health 20.0, buffered landings 0); the attacker ended 2.2272 from the target's face (in reach at the opening tick) |
| 5 | neue click-buffer: click from the edge of reach, the target walks out before the window opens | blocked | the click was made, judged valid 1, held false; no landing (dropped out of reach 1, health 20.0); the target walked to 6.4273 from the attacker |
| 6 | neue click-buffer: spam held clicks across two targets | blocked | at most one held click landed (buffered landings 1, health 12.0 and 20.0); clicks judged valid 3, invalid 0 |
| 7 | neue click-cap: an even 12 CPS clicker (control) | control | every click delivered: 24 of 24 (over the cap 0) |
| 8 | neue click-cap: a 30 tps timer, an attack in every window for 4 s | blocked | delivered 56 in 5496.8364 ms (real-time bound 75, 10.1877 a second); clicks past the cap counted: 35; windows run 142 of 150 sent, refused 29; honest again: 0 corrections, 12/12 claims exact |
| 9 | neue click-cap: two attacks in every window for 2 s | blocked | delivered 25 in 45 windows (one a window at most, bucket bound 30; judged valid 65); second attacks dropped by the envelope: 40, over the cap 15 |
| 10 | neue parry: block-spam to fish for parries | blocked | control: a rested fresh block parries (1); parries while spamming: 0 (window now 0) |
| 11 | neue parry: attack without the Punch packet, then block (a block-hit that parries) | **leaked** | control: a block-hit doesn't parry (window 0, parries 0); FAILED: the block raised one tick after the attack parried 1 (its window 3 ticks; with the Punch: 0) |
| 12 | neue cadence: a 20 CPS autoclicker that leaves out the Punch packet | **leaked** | control: detected (longest one-click run 120 ticks, click-streak alerts 1; the target saw 30 swings); delivered 73, over the cap 47; FAILED: the same clicker without Punch packets: longest one-click run 0 ticks, click-streak alerts 0; the target saw 0 swings from it (30 with the Punch) |
| 13 | neue stagger: ignore the recoil (anti-knockback on the parry) | blocked | parried and staggered (1); the truth took the recoil: thrown 1.9325 back; the ServerPlayer too: 1.9325; the client was corrected: 4 (claims stayed 1.9325 back); the recoil's ack started the lockout (window 48); honest again: 0 corrections, 12/12 claims exact |
| 14 | neue stagger: keep clicking through the lockout with the recoil's pong withheld | blocked | parried, the lockout's start known (window 53, forced entries 31); first attack delivered in window 63, the recoil acked in 53 (expected at least +10); 16 server ticks after the parry |
| 15 | neue stagger: swap to an axe and keep clicking through the lockout | blocked | parried, the lockout's start known (window 49, forced entries 0); first attack delivered in window 59, the recoil acked in 49 (expected at least +10); 11 server ticks after the parry |
| 16 | neue stagger: a click held on another target lands during the lockout? | blocked | parried (1 staggers); the held click never landed (buffered landings 0, the second target's health 20.0) |
| 17 | neue stagger: an honest attacker's lockout after a parry (control) | control | honest: first attack 11 server ticks after the parry (window 59, recoil acked in 49; windows run 32, refused 0, forced 0) |
| 18 | neue stagger: run the lockout's windows at 3x speed (timer) | **leaked** | parried, then an attack delivered; FAILED: first attack 8 server ticks after the parry (window 59, recoil acked in 49; windows run 38, refused 3, forced 0); an honest attacker's came 11 ticks after; lockout in windows: first attack 10 windows after the ack |
| 19 | neue stagger: bank 8 ticks (a fake stall, pongs held), then run the lockout at 3x speed | blocked | parried, then an attack delivered; first attack 18 server ticks after the parry (window 76, recoil acked in 68; windows run 60, refused 20, forced 28); an honest attacker's came 11 ticks after; lockout in windows: first attack 8 windows after the ack |
| 20 | neue stagger: an honest attacker's lockout after a parry at 200 ms (control) | control | honest: first attack 15 server ticks after the parry (window 63, recoil acked in 53; windows run 32, refused 0, forced 0) |
| 21 | neue stagger: run the lockout's windows at 3x speed (timer) at 200 ms | **leaked** | parried, then an attack delivered; FAILED: first attack 12 server ticks after the parry (window 63, recoil acked in 53; windows run 38, refused 3, forced 0); an honest attacker's came 15 ticks after; lockout in windows: first attack 10 windows after the ack |
| 22 | neue stagger: swap to another sword type and block during the lockout (parry the riposte) | **leaked** | the honest player parried the first attack; FAILED: a block went up during the lockout: true (with minecraft:iron_sword); FAILED: the riposte was parried by the staggered player: 1 (riposter staggered true, its health 20.0, the cheater's 20.0) |
| 23 | neue riposte: a mob's hit on a fresh block (riposte farming on mobs) | blocked | no parry, no riposte (parries 0, riposte ready false); the zombie's hit landed on the block (health 20.0 -> 18.0) |
| 24 | neue DI: strafe keys sent, claims that don't strafe | blocked | knocked ((-0.16858980363641574, 0.3608, 0.36273609981615396)); DI bent the knock -14.7646 degrees; the truth strafed with the keys before the hit: -0.4302 (the claims: -0.9841 now, truth -1.2285); the claims that didn't strafe were corrected: 5; honest again: 0 corrections, 12/12 claims exact |
| 25 | replication: an honest hit at 2.5 under K1 (control) | control | an honest hit lands: landed 1 (judged valid 1, invalid 0, verdict VALID); K 1 (K1 after 16.0 s) |
| 26 | replication: perfectly regular pongs to get K1, then a 500 ms backtrack | blocked | refused: landed 0 (judged valid 0, invalid 1, K 1, verdict INVALID MISSED) |
| 27 | replication: flap K (erratic and regular pongs, 3 s each, twice) | blocked | K 1 at 5.0 s; K 2 at 11.0 s; K switched 1 times in 11.0500 s (at most one per 5 s of steady readings; every switch re-pairs the players fed to the viewer) |
| 28 | replication: reach 3.5 right after the flapping | blocked | refused: landed 0 (judged valid 0, invalid 1, verdict INVALID OUT_OF_REACH); K 2 |
| 29 | replication: an honest hit at 2.5 right after the flapping (control) | control | an honest hit lands: landed 1 (judged valid 1, invalid 0, verdict VALID); K 2 |
| 30 | timer: three fake 1.2 s stalls back to back, walking | blocked | each burst ran at most 20 windows at once (bank 10, owed at most 10); windows that moved 111 in 115 server ticks (real-time budget 126); the ServerPlayer stayed put during each stall once the windows sent before it ran (+0.0000); the truth walked 23.7008, no further than 111 ticks of walking (23.7008); honest again: 0 corrections, 12/12 claims exact |
| 31 | timer: freeze 1.2 s, then run the 24 missed ticks at once | blocked | the burst ran 20 of 24 at once (bank 10, owed at most 10); windows that moved 49 in 54 server ticks (budget 65); refused 0, queued 5, truth walked 5.1769; honest again: 0 corrections, 12/12 claims exact |
| 32 | pongs: skip only the knock's ping, answer the rest | blocked | the knock's ping was skipped (1); kicked: 'Invalid transaction replies' (skipped 1, violations 1); the truth's velocity before the kick: vz 0.0000, z +0.0000 |
| 33 | pongs: hold only a knock's pong 8 ticks (knock-aware lag switch), three knocks | blocked | each knock landed by its deadline, not the client's +8: +5 (deadline 5), +5 (deadline 5), +5 (deadline 5); forced entries 18; rtt base 46 ms, corrections 19; honest again: 0 corrections, 12/12 claims exact |
| 34 | solids: stand on a boat the server moved away (the client keeps its copy) | blocked | control: client and truth stand on the boat (truth y +0.5625, client +0.5625, corrections 0); the client claimed the boat's old top for 2 ticks after the move, then took the corrections; the truth fell to the floor (+0.0000); the ServerPlayer too (+0.0000); corrections 3; honest again: 0 corrections, 12/12 claims exact |
| 35 | solids: walk through a boat the server moved into the lane (the client ignores the move) | blocked | claimed 1.3842 past the boat's face; the truth stopped at the boat (-1.192e-08 past touching); the ServerPlayer too (-1.192e-08); corrections 6; honest again: 0 corrections, 12/12 claims exact |
| 36 | solids: walk through a shulker spawned ahead, pongs held (the spawn's window) | blocked | claimed 1.2552 into the shulker's box; the truth stopped at the shulker (-1.192e-08 past touching); the ServerPlayer too (-1.192e-08); forced 43, corrections 7, mover exemptions 0; honest again: 0 corrections, 12/12 claims exact |
| 37 | solids: phase through a shulker the client dropped from its world | blocked | claimed 1.0424 into the shulker's box; the truth stopped at the shulker (-1.192e-08 past touching); corrections 6, mover exemptions 0; honest again: 0 corrections, 12/12 claims exact |
| 38 | pearls: a throw down the lane (control) | control | the pearl carried the thrower past where the wall would be (+14.3269, client and truth together) |
| 39 | pearls: through a fake wall the client dropped from its world | blocked | the truth landed short of the fake wall (z max -0.0614 past touching; now +4.1386 from the throw); the ServerPlayer too (-0.0614); honest again: 0 corrections, 12/12 claims exact |
| 40 | teleport hold: never ack a chunk batch, then a 400-block teleport | blocked | held 1, chunks sent by the netcode 1; the teleport went out 3 server ticks later (hold cap 100, chunk send after 2); the truth stands at the destination (x +400.5000, y +0.0000); not kicked () |
| 41 | pearls: a long throw into a chunk the client lacks, chunk batches never acked | blocked | landed 50.5881 out; teleports held 0, chunks the netcode sent 0 (0: the landing chunk had been sent before the throw); the pearl carried the thrower 50.5881 out; the truth stands on the floor there (y 0.0000 from it); not kicked (), corrections 0 |
| 42 | projectiles: an honest snowball at 6 blocks (control) | control | the snowball hit: 1 |
| 43 | projectiles: a snowball at a target backtracked 500 ms (pongs held) | blocked | missed: 0 hits (the target is at x +3.0000) |
| 44 | projectiles: an honest rod at 6 blocks (control) | control | the rod hit: 1 |
| 45 | projectiles: a rod at a target backtracked 500 ms (pongs held) | blocked | missed: 0 hits (the target is at x +3.0000) |
| 46 | client profile: claims Lunar (lunarclient brand, lunar:apollo registered) | blocked | the server reads it as Lunar (brand LUNAR 'lunarclient:v2.18.3-2511', apollo true); reach 4.5 refused (landed 0); autoblock refused (landed 0, blocking true); the second input counted as for any client (1) |

## Leaks: root cause and fix

### L1 (p1) An attack without its Punch hides the swing from the parry rules (row 11)

A vanilla 26.3 click on an entity sends `Attack` then `Punch` (`Minecraft.startAttack`). Neue's block-hit rule (§8.2: a
block raised under 6 ticks after a swing gets no parry window) reads the last swing, `NeueState.swung`, and the only
thing that records it under authority is the `Punch`:

- `handlePunch`'s hook (`leaf-server/minecraft-patches/features/1016-Neue-combat.patch:15`);
- `NeueCombat.beforeAttack` does record "an attack is a swing" (`NeueCombat.java:297`), but returns before it for the
  netcode's own replays (`NeueCombat.java:292-293`, `entered`);
- `AuthPlayer.runAttack` (`AuthPlayer.java:1761`) never records one, and `InputRules` has no rule that an `Attack` is
  followed by a `Punch`.

So a client that attacks without the `Punch` and blocks the next tick gets the full 3-tick window on a block-hit
(measured: window 3 and a parry, against 0 and none with the `Punch`). Trading hits while fishing parries is exactly
what the rule exists to stop. A second path to the same state: the parried attack's own `Punch` comes in the lockout
and is not replayed (`AuthPlayer.java:1695`), so a staggered player's last swing is not on record either (L4 uses it).

Fix: record the swing for every `Attack` the grammar admits, first thing in `AuthPlayer.runAttack`:
`gg.hcfrevival.revival.combat.NeueCombat.swung(this.player);` (the click swung on the client whatever the server makes
of it), and keep recording swings in the lockout even though the `Punch` isn't replayed. Optionally count an `Attack`
with no `Punch` in its window as an input-grammar anomaly (counted, not kicked: §15.8).

### L2 (p2) The cadence detector reads Punch packets only (row 12)

`AuthPlayer.java:1579`: `this.cadence.onWindow(cadenceEligible, this.punchesThisTick)`, with `punchesThisTick` counted at
`AuthPlayer.java:1694`. The same 20 CPS lock that detector A flags with the `Punch` (a 120-tick one-click run, one
`click-streak` alert) reads as zero clicks without it: longest run 0, no alert. The bucket still caps what lands (73
delivered, 47 over the cap in those 6 s), so the gain is evidence for staff, not hits. Side effect: viewers get no
`SwingAnimation` for those attacks (30 swings seen with the `Punch`, 0 without), a NoSwing cheat that hides the
attacker's rhythm from the player reading it for parries.

Fix: `this.cadence.onWindow(cadenceEligible, Math.max(this.punchesThisTick, this.attacksThisTick))` (every attack is a
click), and the grammar anomaly of L1. Optionally swing the server player for a delivered attack whose window had no
`Punch`, so viewers see what a vanilla client would have shown.

### L3 (p2) The stagger lockout has no server-time floor (rows 18 and 21)

`NeueState.lockedOut` (`NeueState.java:260-276`), client-clock branch at `NeueState.java:268`: locked while
`tick <= staggerAck + staggerTicks - 1`, the attacker's own window count. The tick clock lets a client run ahead of real
time by its standing allowance (`timer-drift-ms` 120 plus `timer-ack-settle-ms` behind the ack clock,
`TickClock.java:67-69`), about three windows. A client that runs its ticks 3x fast once it sees the recoil delivers its
first attack 8 server ticks after the parry against an honest client's 11 (12 against 15 at 200 ms of latency). It
gains 150 ms of a 500 ms lockout; banking more by withholding pongs does not help (row 19: the recoil is then forced at
its deadline and the burst is refused past `timer-queue-cap-ms`, 18 ticks).

The parry already has the floor this needs (§8.2, `max(T_now - T_B, S_now - S_B - jitter)`). Fix, the same shape:
record the server tick the recoil was applied at (`NeueState.motionApplied`, fed by `NeueCombat.applied`,
`NeueCombat.java:180-184`, which would pass `now()`), and lock while
`tick <= staggerAck + staggerTicks - 1 || serverNow < staggerAckServer + staggerTicks - jitter`. With a jitter of 1 tick
(an ack window that came in a tick late doesn't cost an honest click) the trial left 1 tick: 10 against 11, 14 against
15. Not `pacedTick`: a timer that spaces its windows past `catch-up-gap-ms` counts as paced.

### L4 (p1) Blocking, and parrying, with another sword type during the stagger (row 22)

Neue's stagger refuses blocks through the held sword's item cooldown only (`NeueCombat.java:457-461`,
`addCooldown(weapon, ticks)`: the cooldown group is the item id). Nothing else stops a block in the lockout:
`AuthPlayer.runPacket`'s `UseItem` (`AuthPlayer.java:1665`) doesn't look at it, and `NeueCombat.parried`
(`NeueCombat.java:206-247`) never asks whether the parrier is itself staggered. A parried attacker that takes an iron
sword (a tick) and blocks (the next) is blocking inside its lockout with a full parry window (its last swing is not on
record, L1), and the parrier's riposte the tick after is parried: the riposte is lost, the parrier is staggered, and
the cheater gets a riposte of its own. neue-combat §0 and §8.1 say a staggered player can't attack or block for 0.5 s.

Fix, both: in `NeueCombat.parried`, `if (state.staggered() && lockedOut(parrier)) return false;`; in
`NeueCombat.stagger`, put the cooldown on every legacy blocking weapon in the hotbar (each type its own group), so the
vanilla client refuses the block itself and its prediction stays exact (`ClientView.predictsUse` reads the same
cooldowns). Recording swings in the lockout (L1) also zeroes the window of a block raised within 6 ticks.

## Honest clients hurt (found by the controls)

### H1 (p2) The stream's rotation is unknown when authority starts (row 1)

A vanilla client sends its rotation only when it changes. The join teleport's accept carries it, but arrives before
authority starts at the next `TickEnd`; `AuthPlayer.activate` (`AuthPlayer.java:690`) calls `stream.forgetClient()`
(`AuthPlayer.java:703`, `ClientStream.java:656-660`), which clears `rotationKnown` (read nowhere:
`ClientStream.java:601`) and never seeds the rotation. Until the player moves the mouse its tick rotation is 0/0: an
honest attack straight ahead at yaw 180 is judged `INVALID MISSED`, its strafe is simulated the wrong way (3
corrections in 10 ticks), and the activation seed uses that yaw. It went unseen because every round-1 cheater faces
yaw 0. A player who joins and walks or attacks before touching the mouse is the real-world case.

Fix: after `forgetClient()`, `this.stream.setRotation(this.player.getYRot(), this.player.getXRot());` (the last rotation
vanilla took from this client). The harness works around it with one rotation re-send at `ready` (a mouse twitch).

### H2 (p1) A hotbar key and a right click in one tick: the port uses the old item (row 2)

`Minecraft.handleKeybinds` handles hotbar keys before use clicks, and `LivingEntity.startUsingItem` (26.3 client,
`LivingEntity.java:3510-3513`) reads `getItemInHand` then, so a vanilla client that takes the sword and right-clicks in
one tick blocks with it. The port's `PortSimulation.startUsingItem` (`PortSimulation.java:329-331`, into
`MovementSim.startUsingItem`, `MovementSim.java:1514-1522`) reads `SimPlayer.mainHand`, which `PortSimulation.sync`
(`PortSimulation.java:610-612`) refreshes only when the tick runs: it starts using the previous item, and the tick stops
that use (the item changed). The truth never uses the sword and the simulation's state wins over the server player's
(`ownsClientState`), so the server doesn't block at all. A vanilla client in that case is blocking: slowed to 0.2 on its
screen (corrections) and taking unblocked hits. The swap-and-block is a common 1.7 move (rod or pot to sword, then
block-hit). The suite's own model is the same port, so it shows no correction here; the row checks the server against
vanilla's order.

Fix: `PortSimulation.startUsingItem` reads the hands from the `ClientView` the netcode has just updated (pass the view,
or sync the hands when `AuthPlayer.runPacket` handles `SetCarriedItem`, `AuthPlayer.java:1622`).

## Fix trial

The six fixes above, applied in a scratch clone of the same tree (`AuthPlayer`, `NeueState`, `NeueCombat`,
`PortSimulation`; 33 lines added, 3 changed; not in this tree), then the whole live run again, both trackers: **every
live test passed**, 707 checks in the sync run with round 1 at 83/83 and this suite at **46/46**; the async run passed
too. The rows that changed:

| Row | 69b7b5f6 | With the fixes |
|---|---|---|
| H1 rotation at activation | attack MISSED at tick rotation 0, strafe 3 corrections | lands at -180 (yaw 180), 0 corrections (17/17 exact) |
| H2 hotbar and block in one tick | the server doesn't block, the truth doesn't use | both block |
| L1 attack without the Punch | block-hit window 3, parried | window 0, no parry |
| L2 cadence without the Punch | longest run 0, no alert | longest run 120, one `click-streak` alert |
| L3 lockout at 3x speed | 8 ticks (honest 11); 12 at 200 ms (honest 15) | 10 (honest 11); 14 at 200 ms (honest 15): 1 tick of jitter allowance left |
| L4 another sword in the lockout | blocked and parried the riposte | no block, the riposte lands as a crit |

The trial's lockout floor allows 1 tick of jitter (`LOCKOUT_JITTER_TICKS`); `parry-jitter-ticks` (2) would be the
consistent key to reuse. The unit tests were not run against the trial (`NeueStateTest`, the port's tests): the
`motionApplied` overload keeps the old signature, but `PortSimulation.startUsingItem` reading the hand from the view
needs its own golden test.

## Blocked, and what it rests on

- **Click buffer.** Pre-loaded clicks from 3.6 are judged `INVALID` and never held (5 judged out of reach, 1 over the
  click cap). A held click is re-checked for reach on the attacker's mirror when the window opens; in the final run the
  walking target was out of reach on it and the click was dropped. In an earlier iteration the same scenario landed with
  the view at 2.62 and the target's truth at 3.33: that is the view lag inside the rewind cap (HR-3, by design), not a
  bypass. One held click per attacker: alternating clicks over two shut targets land once.
- **Click cap.** A 30 tps timer delivered 56 attacks in 5.5 s of real time, under the real-time bound (75) and the
  same 10-11 a second an honest 20 CPS clicker gets; two attacks per window are cut to one by the envelope before the
  bucket. An even 12 CPS clicker loses nothing.
- **Parry.** Block-spam has no rest (window 0 for every block after the first); a mob's hit never parries or grants a
  riposte; the recoil is a logged knock (ignored, it is corrected); withholding the recoil's pong only delays the lockout
  (forced at the deadline, 16 ticks); an axe is locked out like a sword; a click held on another target is dropped by
  the stagger.
- **DI.** The bend comes from the keys the truth moved with (`ClientStream.advanceInput`): keys sent without strafing
  strafe the truth (the claims are corrected), so the 15 degrees cost the movement they stand for.
- **K per viewer.** Perfectly regular pongs earn K1 (after 16 s here) and nothing else: the rewind cap is measured from
  the mirror's own display delay, not from K, so a 500 ms backtrack under K1 is refused. Flapping the jitter switched K
  once in 11 s (each side needs 5 s of steady readings, and K1 needs a full 256-ack window); hits right after a switch
  are judged on the re-paired mirror (reach refused, honest landed).
- **Timer bank.** Manufactured stalls back to back (pongs held, nothing out, client ticking) and a frozen client
  replaying 24 ticks at once both run at most 20 windows in a burst and never more than real time plus the bank; the
  server player doesn't move once the queue sent before the stall has drained.
- **Selective pongs.** Skipping only the knock's ping is an instant kick (skipped id, §15.8). Holding only a knock's pong
  (a knock-aware lag switch that reads every packet) lands each knock at its deadline, +5 windows, not the client's +8,
  and leaves the client's RTT base untouched (46 ms).
- **Solid entities.** The server's copy of what the client holds moves at the client's ack: a client that keeps a boat
  the server moved away falls with the truth after 2 ticks of claims on its old top; one that ignores a boat moved into
  its lane, or drops a shulker, is stopped at the face; a shulker spawned ahead while its pong is withheld is forced into
  the truth at the deadline, before the walker reaches it. No external-mover exemption in any of them.
- **Pearls and the teleport hold.** A pearl stops at a fake wall the client dropped (the server clips against what it
  sent); a 400-block teleport with no chunk batch ever acked goes out 3 ticks later, the netcode sending the chunk
  itself; a 50-block pearl with no acks lands on the floor (its chunk had been sent already, so no hold).
- **Projectiles.** The shooter's view is used only while it is within the rewind cap: a snowball or a rod thrown at a
  target that the cheater's stale view (pongs held 500 ms) still shows, 3 blocks from where it is, misses. The same
  throws at a still target hit (controls).
- **Client profile.** A non-Lunar client claiming `lunarclient` and `lunar:apollo` is read as Lunar and gets nothing for
  it: reach 4.5 and autoblock refused, a second input counted. Profiles relax no rule yet (§15.8).

## Not covered, and limits

- A copy made inexact or a placeholder (`ExternalMovers`, the `EXTERNAL_MOVER` exemption with vanilla's checks within
  `external-movers.max-move`) needs authority to restart or a lost log; a client can't trigger either cheaply (it
  freezes first), so no row attacks that window directly.
- The suite's clients share the port with the server, so a port inexactness (H2) shows only as server state against
  vanilla's order, not as a correction.
- The rotation nudge in `ready` (H1's workaround) should come out once H1 is fixed.
- Timing-sensitive rows (buffer landing, lockout ticks) were stable across five iterations except the walking-target
  buffer row, which landed (bounded) once and was dropped otherwise.

## Status (netcode, 2026-10-22)

H1, H2 and L2 are fixed in the netcode (`AuthPlayer.activate` seeds the stream's rotation from the `ServerPlayer`;
`Simulation.startUsingItem(hand, view)` takes the hands the view holds after the tick's hotbar key; the cadence counts
`max(punches, attacks)` and a window with more attacks than punches is an `attack-without-punch` grammar count, never a
drop). With the Neue agent's L1/L3/L4 as they stood in the main tree at 11:00, the sync live run had this suite at
**46 of 46 blocked** (honest H1: the attack at yaw 180 lands VALID, the strafe 17/17 exact). L1's netcode half (calling
`NeueCombat.swung` for every attack the grammar admits, and for a `Punch` in the lockout; neue-hooks.md) is parked while
classic comes first: L1 only matters for Neue's parry window.

## Server lag (SV-2, 2026-10-23)

One row measures what a server stall does to an honest combo (pvp-issues SV-2), with the window on server ticks and on
the clicks' clock (`damage-windows.melee-wall-clock`, `auth-combat.md` §6): "server lag: a still target clicked at 12
CPS through a 200 ms stall every 40 ticks". It takes about 2 minutes of the sync run.

- **The stall.** The driver's task sleeps the main thread 200 ms every 35-45 server ticks (seeded): a plugin's hitch.
  The gaps vary so the stalls fall on every point of the 10-tick hit cycle; at a fixed 40 the cycle settles, after the
  first hit a stall delays, on a phase the stalls miss.
- **The clicker** is a `FakeClient` on its own thread (`AuthCheatScenarios2.Clicker`), ticking every 50 ms of real time
  whatever the server does, half a tick off the server's schedule: a still player clicking the target 2, 2, 1 ticks
  apart (12 CPS, a click on every tick a window can open). It is on `wire` (new in `FakeClient`): its packets pass the
  channel's pipeline, where authority's `ArrivalStamps` stamps its TickEnds as they arrive, and queue for the main
  thread through the packet processor, as a real connection's do. Clients ticked by the driver can't do this: the
  stall stops them too.
- **The target** is an honest client against its wall, its health topped up every tick.
- **Three phases** of 600 client ticks: no stalls; stalls with the window on server ticks (`melee-wall-clock: false`,
  the server before SV-2); stalls on the clicks' clock. Each reports fresh hits a second (first to last fresh hit) and
  the gaps between fresh hits in real time, between the times their clicks reached the server (a held click's: its
  landing), in ticks. The row passes when the control and the clock both keep at least 1.95 a second with no gap of 8
  or less, and the clock has at most one gap of 12 or more (a hiccup of the machine's own).

Results, the full live runs of 2026-10-23 (sync tracker; this suite 56/56 on `classic-capped`, 49/49 on `classic`),
fresh hits a second and the gaps in ticks:

| Rule set | No stalls | Stalls, the window on server ticks | Stalls, the clicks' clock |
|---|---|---|---|
| `classic` | 2.00, `{10=59}` | 1.95, `{10=53, 12=2, 13=2, 14=1}` | 2.00, `{10=59}` |
| `classic-capped` | 2.00, `{10=60}` | 2.00, `{7=2, 8=2, 9=2, 10=49, 11=1, 12=2, 13=2}` | 2.01, `{9=9, 10=42, 11=9}` |

Runs of the row alone (`-Drevival.livetest.only`) gave the same numbers, also on top of NW-1's freeze recovery
(`4b1282ab`), except that on `classic-capped` a few gaps of 9 and 11 come and go between runs, in the control too.

On `classic` the stalls delayed 5 hits by 2-4 ticks each. On `classic-capped` the click buffer evens out the rate, but
the cadence breaks both ways: a stall delays some hits and its catch-up ticks let others in 2-3 ticks early. The clock
keeps 1.7's cadence through the stalls on both. On `classic-capped` it needs the landing pass to wait a tick after a
stall while the attacker's windows queued behind it still wait for the main thread: before that, the held click from
before the stall landed at the stall's end and started the next window there (3 to 5 hits 3-4 ticks late in two runs,
1.97-1.98 a second). The 9s and 11s are a held click's landing (in the connection phase, at the start of a tick) next to
a direct click (mid-tick), half a tick apart in the same server tick.

The clicker trips the `click-lock` alert (an exact 12 CPS for 300 ticks), as an autoclicker should.
