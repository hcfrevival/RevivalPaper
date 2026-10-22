# Authoritative cheat suite

Status: 2026-10-21, branch `revival/authoritative`. Code:
`leaf-server/src/revivalLiveTest/java/gg/hcfrevival/revival/livetest/AuthCheatScenarios.java`, registered after
`AuthLiveScenarios` in `RevivalLiveTests`. Design: `authoritative.md` (§1 threat model, §14 proof, §15 binding).

## What it is

The suite plays a modified client against a server in `authoritative.mode: enforce`. Every cheater is a fresh fake player
on a team with collision rule `NEVER` (the §15.3 precondition), put under enforce before its first `TickEnd`, in a
cleared arena 24 blocks east of spawn.

The `FakeClient` only carries the connection. The `Cheater` (inside `AuthCheatScenarios`) reads what the server wrote
(`FakeClient.raw`, bundles in order) and composes every serverbound packet itself:

- **Lag model.** Each clientbound packet gets a release tick; pings can be answered at once, delayed N client ticks,
  held, released all at once, reordered, duplicated or replaced by forged ids. Keep-alives and chunk batches are
  answered immediately, as a real client does from the Netty thread.
- **Movement.** The cheater moves with the real port (`PortSimulation` with its own `ClientView`/`WorldView`), fed what it
  applied (teleports and corrections, self motion, attributes, effects, abilities, entity data, inventory, block updates).
  An honest cheater is bit-exact; a cheat is an edit of that state (rebase, zeroed velocity, ignored packets, different
  keys than the ones sent).
- **Windows.** `LocalPlayer.sendChanges` order by default, but any part can be rearranged: actions before or after the
  drain, extra move packets, no `TickEnd`, forged accepts, blink (buffer everything, then burst), tick rates other than 20.

Every scenario asserts its expected outcome:

1. the truth (`AuthPlayer.simulation()`) and the `ServerPlayer` (sampled every tick, plus every `PlayerMoveEvent`) never
   take the cheated state; where the keys are known, the truth is compared with an honest reference run of the port from
   the same start, to the bit (`gap 0.0000` below);
2. the client is corrected or the action is refused (corrections, `PrePlayerAttackEntityEvent`, `InventoryClickEvent`,
   kick reasons);
3. the violation is counted (`AuthStats`, `Transactions`, `ClientStream`);
4. an honest client right after the cheat is unaffected: 27 ticks later it gets no correction and its claims are compared
   again. Combat scenarios use honest hits at 2.5 blocks as controls instead.

Outcomes are collected and printed as `REVIVAL_CHEAT` lines; blocked ones are `REVIVAL_LIVE_PASS` lines. The last step
fails the run if any cheat leaked or any honest control was affected, so every scenario always runs.

Running it: `./gradlew createPaperclipJar :leaf-server:revivalLiveTestPlugin && python3 scripts/revival/live-tests.py`.
The cheat suite adds about 5 minutes to the sync-tracker run and nothing to the async one (authority refuses there).
Tip while other agents rebuild: the runner unpacks into the shared `leaf-server/build/revival-live` cache, so a rebuild
during a run corrupts it (`ZipFile invalid LOC header`). A copy of the script, the two jars and the cache under a scratch
root (the script finds everything relative to itself) runs isolated.

## Results (tree at 21:45, run `d51d72d3`)

51 of 70 scenarios pass. 7 cheats still leak (bold); 12 lines are an honest client hurt by the server's own handling: 9
correction loops after a cheat (A) and 3 honest hits refused by the fix for the combat leaks (B, C).

| # | Cheat | Outcome | Evidence |
|---|---|---|---|
| 1 | fly: claims rising 0.25/tick | blocked, honest client affected (A) | claimed +7.5; truth and ServerPlayer y max +0.0; 29 corrections |
| 2 | fly: jump then hover | blocked, honest affected (A) | claims held ≥ +1.0 for 38 ticks; truth y max +1.2522 (a jump), landed; 36 corrections |
| 3 | speed: 1.5x the walk | blocked | truth = honest walk (5.1369, gap 0); claims up to 0.216 ahead; 24 corrections |
| 4 | step-up 1.0 | blocked | truth = honest path; claimed standing on the block; truth y +0, z stops 1.2e-8 short of the face |
| 5 | phase through a wall | blocked | claimed 5.0 past the face; truth/ServerPlayer z stop at the face; 29 corrections |
| 6 | phase through a closed door | blocked | same as 5 |
| 7 | vclip 6 down through the floor | blocked, honest affected (A) | claimed y -6; truth/ServerPlayer y min +0; 14 corrections |
| 8 | spider (climb a plain wall) | blocked, honest affected (A) | claimed +5; truth = honest path, y +0; 24 corrections |
| 9 | jesus (walk on water) | blocked, honest affected (A) | claims crossed the water; truth never stood on it, sank to -1.83; 22 corrections |
| 10 | no-fall (onGround through 12 blocks) | blocked | 9.0 damage, an honest fall 9.0 (server fall distance 10.81 both); 18 onGround lies counted |
| 11 | no-slow sneaking | blocked | truth = honest sneak (1.6922, gap 0); claims up to 0.233 ahead |
| 12 | no-slow blocking | blocked | truth = honest blocking walk (1.0274, gap 0); claims up to 0.266 ahead |
| 13 | anti-KB: pongs, claims ignore the knock | blocked, honest affected (A) | truth and ServerPlayer knocked 3.20 back, +1.25 up; 19 corrections; forced 0 |
| 14 | anti-KB: withhold every pong | blocked | 27 entries forced at the deadline (5 windows); knock on the truth (+2.17); frozen at 1740 ms; kicked at 4990 ms "Timed out (no transaction replies)" |
| 15 | anti-KB: delay pongs 3 ticks | bounded (lag within the deadline) | knock lands at window +3, the client's choice; deadline 5 |
| 16 | anti-KB: delay pongs 9 ticks | blocked, honest affected (A) | knock forced at +5, not the client's +9 |
| 17 | anti-KB: batch two knocks past the deadline | blocked, honest affected (A) | first knock lands alone (window 22), second at 24; 12 forced |
| 18 | pongs out of order | blocked | kicked "Invalid transaction replies" |
| 19 | pong: unknown id in our band | blocked | kicked |
| 20 | pong: future id | blocked | kicked |
| 21 | pong: duplicate of an acked id | blocked | ignored, counted as a duplicate, no violation |
| 22 | pong: foreign ids (4242, -1234) | blocked | passed through, no violation |
| 23 | pong after a keybind packet | blocked (counted only) | 1 grammar violation; no kick; no movement gain |
| 24 | timer 22 tps for 4 s | blocked | 82 of 88 windows ran in 4002 ms (budget 91); truth walked exactly 82 ticks |
| 25 | timer 30 tps for 4 s | blocked | 90 of 120 ran (budget 91), 8 refused past the 1 s queue cap |
| 26 | blink 1.2 s, then a burst | blocked | ServerPlayer frozen during the blink; burst ran 10 of 24 at once (bank 10), then ≤ 1 per tick |
| 27 | 15 tps for 3 s, then 10 at once | blocked | the ack clock let 2 of 10 run at once |
| 28 | **TickEnd never sent** | **leaked** | nobody moved, but the open window held 661 packets (cap 512); no kick, no freeze |
| 29 | two move claims in one window | blocked (counted only) | 1 grammar violation; neither claim taken; not kicked (vanilla kicks) |
| 30 | **accept a server teleport with spoofed coordinates** | **leaked** | ServerPlayer at +6.0 for 10 ticks (vanilla took the accept's coordinates) |
| 31 | **never accept a server teleport** | **leaked** | truth took the teleport and walked 4.06 on; ServerPlayer frozen 4.06 blocks away |
| 32 | **accept an id never sent (correction band)** | **leaked** | fake wall worked as control; after the accept `sentCount` 10 → 0 and the truth walked 5.02 past the fake wall |
| 33 | accept a vanilla id never sent | blocked | nothing moved; counted as an accept mismatch |
| 34 | NaN position claim | blocked | kicked "Invalid move player packet received"; no non-finite packet out |
| 35 | infinite position claim | blocked | kicked "Invalid transaction replies" |
| 36 | NaN rotation claim | blocked | kicked |
| 37 | NaN accept for a server teleport | blocked | vanilla kick |
| 38 | NaN accept for our correction | blocked | counted; correction not left in flight |
| 39 | 1e-7 creep into a door (epsilon 0) | blocked | truth stayed at the face after 100 creeping claims |
| 40 | 1e-7 creep into a fence gate (epsilon 0) | blocked | same |
| 41 | 0.9e-10 creep into a door (epsilon 1e-10) | blocked | 1300 claims: truth 9.9e-10 in from touching, never past the face (`RebaseGuard` + `clearPath`) |
| 42 | 0.9e-10 creep down into the floor (epsilon 1e-10) | blocked | gravity resets it every tick |
| 43 | claims 0.05 inside a wall | blocked | truth at the face; 5 corrections |
| 44 | combat control: honest hit at 2.5 | passes | valid, lands |
| 45-47 | reach 3.5 / 4.5 / 6.0 | blocked | each judged invalid, no hit |
| 48 | through a wall at 2.5 | blocked | invalid |
| 49 | killaura without aim | blocked | invalid |
| 50 | 5 attacks in one window | blocked | 1 lands, 4 over cap |
| 51 | control after the cheats | passes | lands |
| 52 | reach 4.5 while a server teleport is pending | blocked | not delivered |
| 53 | control: honest hit at 2.5 while a teleport is pending | **honest affected (B)** | refused |
| 54 | backtrack past the rewind cap (pongs held 500 ms) | blocked | invalid |
| 55-56 | after one lag spike past the ack deadline: no-aim / reach 4.5 | blocked | not delivered |
| 57 | control: honest hit after that lag spike | **honest affected (C)** | refused (victim mirror stays inexact) |
| 58-59 | after a pong placed behind an attack: no-aim / reach 4.5 | blocked | not delivered |
| 60 | control: honest hit after that | **honest affected (C)** | refused |
| 61 | autoblock (attack while blocking) | blocked | grammar drop |
| 62 | **attack in the window that closes a screen** | **leaked** | landed, 0 grammar violations |
| 63 | inventory-move (container click while walking) | blocked | click not handled |
| 64 | mount under `vehicles: deny` | blocked | forced mount refused |
| 65 | attack while riding (`vehicles: vanilla`) | blocked | exempt 11 ticks, attack refused |
| 66 | place a block without holding one, stand on it | blocked, honest affected (A) | no prediction admitted; truth never on it, walked through the spot |
| 67 | spam placements with block acks held | blocked | 5 predictions, all expired at the deadline with acks still held; every sequence acked |
| 68 | break a protected block, walk through | blocked | refused break is no prediction; truth stops at it |
| 69 | **self-echo: STOP_SPRINTING before acking the sprint attribute sync** | **leaked (bounded)** | the truth kept the sprint modifier while not sprinting for 2 ticks |
| 70 | **self-echo: drop sprint without STOP_SPRINTING, ack the sync late** | **leaked** | 31 ticks with the sprint modifier and no sprint: walks 0.2806/tick (sprint speed), honest walk 0.2159 |

Leaked earlier today and fixed by the netcode agent during this session (re-run green at 21:45): reach 4.5 and killaura
without aim through `UNKNOWN` verdicts (teleport pending, forced entries, pong after an attack: `UNKNOWN` fell back to
Paper's `entity range + 3` check); autoblock; inventory-move; NaN/infinite claims (a NaN correction was sent, then
`Reconciler.bounded` threw `IllegalArgumentException: min is NaN` in every window); NaN rotations taken silently; riding
attackers (now `vehicles: deny`); the 1e-10 creep (the truth sat 8.8e-8 inside a door before `RebaseGuard`/`clearPath`).

## Leaks: root cause and fix

Line numbers are the tree at 21:45 (other agents were editing; method names are stable).

### TickEnd never sent (#28)

`ClientStream.record` (`auth/net/ClientStream.java:226-227`) adds the packet to the window before checking `MAX_ITEMS`,
so the cap only counts a violation: a client that never ends a tick buffers without bound (661 packets in 5.5 s here,
unbounded in general) and is never frozen or kicked, because the freeze only looks at unanswered pings. Nobody moves, so
it is a memory and stall exploit, not a movement one.

Fix: when `window.items >= MAX_ITEMS`, drop the packet and treat it as a protocol violation (kick in enforce); and add
an open-window age limit (no `TickEnd` for `max-unacked-ms` while packets keep coming → frozen, 3x → kicked), the same
rule as unanswered pings.

### Spoofed accept for a server teleport (#30)

`AuthPlayer.onTeleportAccept` (`auth/AuthPlayer.java:627-636`) returns `false` for vanilla teleport ids, so vanilla's
`handleAcceptTeleportPacket` runs and ends with `handlePlayerPositionChange(packet.x(), packet.y(), packet.z(), ...)`
(`ServerGamePacketListenerImpl.java:834`, hooked in patch 1011 line 111): the client's coordinates become the
`ServerPlayer`'s, with `PlayerMoveEvent` and every side effect, until the window's `TickEnd` writes the truth back.
Holding the `TickEnd` holds the spoofed position (10 ticks here; nothing bounds it, see #28). Any server teleport works:
pearls, `/spawn`, plugin setbacks; the accept's position may be anywhere vanilla's own move check allows (about 10 blocks).

Fix: in enforce, consume vanilla accepts too. A patch-1011 method such as `revivalAcceptServerTeleport()` does vanilla's
bookkeeping (`absSnapTo(awaitingPositionFromClient)`, `lastGood*`, `awaitingPositionFromClient = null`) and skips the
`handlePlayerPositionChange` with the packet's coordinates; `checkAccept` already judges the accept as a claim.

### Never accepting a server teleport (#31)

The truth takes the teleport at its pong (`AuthPlayer.apply`, `SELF_POSITION`, line 1475-1486) and keeps simulating, but
`revivalApplyTruth` writes nothing while vanilla waits for the accept (`if (this.hasClientLoaded() &&
!this.updateAwaitingTeleport())`, `ServerGamePacketListenerImpl.java:1583`, patch 1012 line 27), and Paper disabled the
resend (`if (false && ...)`, line 1932). So the client walks on with legal physics while its hitbox stays at the
destination: it attacks from the truth's eye (the judge uses the simulation), others can only hit the frozen body, and
Paper's own range check measures from the body (up to 6 blocks).

Fix: in enforce the teleport is complete at its pong window (§15.3). At that point clear vanilla's awaiting state
(the same patch method as above, called from `apply(SELF_POSITION)`), so `revivalApplyTruth` writes again; if the accept
never follows its pong, count it and kick after the ack deadline like a missing correction accept.

### Accept with a correction id never sent (#32)

`AuthPlayer.checkAccept` (line 1454) treats an accept in the correction band that is not in flight as "a teleport we did
not model" and calls `resync()`, whose `this.world.clear()` (line 1692) drops every persistent sent state, plugin fake
blocks included (HCF combat and claim walls). One forged packet, no kick, and the truth walks through every fake wall
the client still has (5.02 blocks past it here).

Fix: our correction ids are ours alone, so an unknown one is a protocol violation like an unknown pong (kick in enforce,
§15.1). Independently, `resync()` must never clear `WorldView.sent`: those entries are what the client was sent, which a
resync from the server does not change; only `pinned` and `predictions` may go.

### Attack in the window that closes a screen (#62)

The rule exists (`InputRules`: `CONTAINER_CLOSE` sets `closed`, line 99; `ATTACK_AFTER_CLOSE`, line 115) but
`handleContainerClose` is not intercepted (`ServerGamePacketListenerImpl.java:3269`, missing from patch 1011), so the
close is handled at once and never enters a window: 0 grammar violations, the attack lands.

Fix: hook the network entry `handleContainerClose(ServerboundContainerClosePacket)` (the one-argument overload, so
server-initiated closes through the two-argument one stay immediate) with `if (this.player.revivalAuth.intercept(packet))
return;`, list it in `markers.txt`, and replay it in the window's GUI part.

### Sprint modifier kept without sprint (#69, #70)

`SelfEcho.attributes` (`auth/view/SelfEcho.java:100`) decides the `sprinting` modifier from the simulation's sprint state
when the packet is sent. A movement-speed sync sent while sprinting and acked after the client stopped sprinting
re-adds the modifier: `SimAttributes.applyUpdate` (`auth/sim/SimAttributes.java:81`) replaces the modifiers with the
packet's, as the vanilla client does (MC-69459). With `STOP_SPRINTING` the server's own resync removes it a round trip
later (2 ticks here); without it the server never resends, and the client and the truth walk at sprint speed without
sprinting (0.2806 vs 0.2159 per tick, 31 ticks and counting). §15.4 says the truth must not copy MC-69459.

Fix: in `PortSimulation.attributes` (line 183) strip `sprinting` from the `movement_speed` snapshot and re-apply it from the
simulation's own sprint flag, so the truth never has it while not sprinting (the client gets corrected). To spare honest
clients the corrections, also resend `movement_speed` (through `SelfEcho`) whenever the simulation's sprint state changes
while a sync that says the opposite is still unacked.

## Honest clients hurt by the server

### (A) Correction loop after a large divergence

After the cheats in rows 1, 2, 7, 8, 13, 16, 17 and 66 the client, honest again, is corrected every tick forever
(`dpos (0, +0.0784, 0)`, absolute velocity `(0, -0.0784, 0)`, 12 corrections in 12 ticks, no claim compared). Cause:
the accept-residual path in `AuthPlayer.checkAccept` (line 1448) sends `sendCorrection(dx, dy, dz, sim.vx(), sim.vy(),
sim.vz(), false)`: Δ is `truth(T-1) - accept(T)`, but the client applies it one tick later, after it already ran tick T
from the accepted state. Whenever that tick differs from the truth's (here the accepted state was in the air, the truth on
the ground), the client lands off by that difference: 0.0784 inside the floor, where vanilla collision no longer stops it,
so it falls 0.0784 again before the next identical correction. A vanilla client does exactly the same; any correction
that lands it inside a block traps it. Row 9 shows the mild version (1 correction, then claims 2e-4 off forever).

Fix: propagate the residual like the main path (`sim.correction`): re-base the belief on the accept (position, and the
velocity the correction gave), run truth and belief over the ticks until the client applies the next packet (at least 1),
send that Δ; if the propagated belief box overlaps a collision shape, send an absolute position instead of a relative one.

### (B) Honest hit while one's own server teleport is pending

`AuthPlayer.runAttack` only judges when `teleportsPending == 0` (line 1053); otherwise the verdict stays `UNKNOWN`, and
player targets now need `VALID` (line 1072). A pearl and an immediate hit loses the hit. Fix: judge it anyway; the
windows in flight are simulated from the pre-teleport state, which is where the client really is.

### (C) Honest hits after one lag spike or a misplaced pong

Entries force-applied at the ack deadline are applied with `exact = false` (line 799) and events that arrive after their
tick ran (a pong placed after an attack, which is only counted, line 612) call `markInexact` (`MirrorBook.java:185-189,
311`). Exactness only comes back on a spawn (`RemoteMirror.java:167`), so `HitValidator` returns `UNKNOWN` for that target
(`INEXACT_TARGET`, `HitValidator.java:82`) for as long as both stay in range, and since `UNKNOWN` no longer delivers, an
ordinary 250 ms lag spike makes that player unhittable for the rest of the fight. Fixes: re-pair the target on that
viewer (fresh `AddEntity` from `PathWiring`) as soon as it is marked inexact, so the mirror is exact again within a few
ticks; and make a pong after a keybind packet a protocol violation (kick in enforce, like the other pong rules) instead
of applying it late.

## Smaller observations

- Two move claims in one window and a pong after a keybind are counted, never refused or kicked (§15.4 says rejected).
  No gain was found from either.
- A client within `correction-threshold` but not exact (the budget path) is never corrected and never exact again: row
  12's honest phase and the floor creep end with claims 0/12 exact and 0 corrections. Harmless, but `compared - exact`
  stops meaning "something is wrong".
- The truth stops 1.192e-8 short of every wall face (the same in the client: the port is exact). Tolerances in the suite
  are 1e-9 elsewhere and 1e-7 for "touching".
- An accept for a vanilla id that was never sent is only a `teleportAcceptMismatches` count, not a violation (§15.1 says
  flag and resync).
