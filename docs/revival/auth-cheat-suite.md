# Authoritative cheat suite

Status: 2026-10-22, branch `revival/authoritative`. Code:
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

## Results (tree at 02:20 on 2026-10-22, run `fd36e162`)

82 of 82 scenarios pass: every cheat blocked, every honest control unaffected (sync-tracker run; the async run has no
cheat suite, authority refuses there). The three `honest:` joins at the top guard the load-test regression of
2026-10-22 (a truth that fell through the floor of a crowd joining together). The latency controls follow the rewind-cap
policy of 2026-10-22 (ping made good up to `rtt-ceiling-ms` 250, plus the client's own display delay, jitter and tick
waits): honest hits land at 50, 150 and 250 ms, and at a steady 300 ms on a target sprinting across the line of sight
(rewind 445 ms, cap 513 ms). Past roughly 350-400 ms of steady ping a hit is judged at the target's truth at the capped
age, so a high-ping shooter leads moving players. Outcomes as printed by the suite:

Re-run at 02:31 (run `d5d4fdfb`, netcode round: `rttBase` fast window, tick-bundle list reuse, `mirror-history-ticks`
16): 82 of 82 again. #71 now switches its 300 ms on 2.5 s before the target moves (16 s before): `rttBase` is 346 ms by
then (the long window alone was still at the old ping), and no entry is forced after that; its row below is from this run.

Re-run at 06:06 (run `9ccd8340`, stall robustness batch: paced ack deadlines, forced entries kept until they land, the
tick clock repaying a stall's burst, `timer-queue-cap-ms` 400, solid-entity colliders): 83 of 83. New: "stall: 1.0 s with
a server teleport sent into it" (honest: no kick, no violation, 0 corrections, 22 forced, the burst drained, client and
truth at the destination). Changed on purpose: "timer: blink" may run the bank and then the bank's worth the bank
forfeited (20 of 24 at once, was 10: an honest stall's burst must drain, torture F4); "timer: 30 tps" counts the windows
that moved (83, within the budget of 91; refused windows run but move nothing, and 25 were refused past the 400 ms cap).
"anti-knockback: delay pongs 9 ticks" lands at +5 with 0 corrections now (none is sent while the forced knock is out).

| # | Cheat | Outcome | Evidence |
|---|---|---|---|
| 1 | honest: 5 players join 2 ticks apart on a floor, then walk | honest | Crowd1 not kicked; Crowd1 corrections 0; Crowd1 exact 28/28; Crowd1 walked (z +4.3134); Crowd1 stands on the floor (y -60.0000); Crowd2 not kicked; Crowd2 corrections 0; Crowd2 exact 28/28; Crowd2 walked (z +4.3134); Crowd2 stands on the floor (y -60.0000);... |
| 2 | honest: a player joins into chunks still being sent, then walks | honest | Crowd6 not kicked; Crowd6 corrections 0; Crowd6 exact 28/28; Crowd6 walked (z +4.3134); Crowd6 stands on the floor (y -60.0000) |
| 3 | honest: 3 bots that report loaded once their chunk is sent, before it arrives, join, then walk (the load-test regression) | honest | Crowd7 not kicked; Crowd7 corrections 0; Crowd7 exact 28/28; Crowd7 walked (z +4.3134); Crowd7 stands on the floor (y -60.0000); Crowd8 not kicked; Crowd8 corrections 0; Crowd8 exact 28/28; Crowd8 walked (z +4.3134); Crowd8 stands on the floor (y -60.0000);... |
| 4 | fly: claims rising 0.25/tick | blocked | claimed up to +7.5000; truth y max +0.0000; ServerPlayer y max +0.0000; corrections 12 (accepted 12); honest again: 0 corrections, 12/12 claims exact |
| 5 | fly: jump then hover | blocked | claims held at +1.0 or more for 38 ticks; truth y max +1.2522 (a jump); truth landed (y +0.0000); ServerPlayer y max +1.2522; corrections 12; honest again: 0 corrections, 12/12 claims exact |
| 6 | speed: claims 1.5x the walk | blocked | truth is the honest path after 25 ticks (moved 5.1369, reference 5.1369, gap 0.0000); claims ran up to 1.6179 ahead of the truth; ServerPlayer z max +5.1369; corrections 9; honest again: 0 corrections, 12/12 claims exact |
| 7 | step: 1.0 block step-up | blocked | truth is the honest path after 30 ticks (moved 2.2000, reference 2.2000, gap 0.0000); claimed standing on the block (+1.0000); truth max y +0.0000, max z -1.192e-08 past the face; ServerPlayer y max +0.0000; corrections 6; honest again: 0 corrections, 12/12... |
| 8 | phase: through a wall | blocked | truth is the honest path after 30 ticks (moved 2.2000, reference 2.2000, gap 0.0000); claimed 5.0000 past the face; truth z max -1.192e-08 past touching; ServerPlayer z max -1.192e-08 past touching; corrections 12; honest again: 0 corrections, 12/12 claims ... |
| 9 | phase: through a closed door | blocked | truth is the honest path after 30 ticks (moved 2.2000, reference 2.2000, gap 0.0000); claimed 5.0000 past the face; truth z max -1.192e-08 past touching; ServerPlayer z max -1.192e-08 past touching; corrections 12; honest again: 0 corrections, 12/12 claims ... |
| 10 | vclip: claims 6 blocks down through the floor | blocked | claimed y -6.0000; truth y min 0.0000; ServerPlayer y min 0.0000; corrections 6; honest again: 0 corrections, 12/12 claims exact |
| 11 | spider: claims climbing a plain wall | blocked | truth is the honest path after 25 ticks (moved 0.2000, reference 0.2000, gap 0.0000); claimed +5.0000; truth y max +0.0000; ServerPlayer y max +0.0000; corrections 9; honest again: 0 corrections, 12/12 claims exact |
| 12 | jesus: claims walking on water | blocked | claims crossed the water to z +6.6385; truth stood on the water surface for 0 ticks; truth sank to -1.8302, ServerPlayer to -1.8302; corrections 12; honest again: 0 corrections, 12/12 claims exact |
| 13 | no-fall: claims onGround through a 12-block fall | blocked | fall damage 9.0, an honest fall took 9.0 (server fall distance 10.8065 vs 10.8065); onGround lies counted: 18; truth landed (y +0.0000); honest again: 0 corrections, 12/12 claims exact |
| 14 | no-slow: sneaking at walking speed | blocked | truth is the honest path after 25 ticks (moved 1.6922, reference 1.6922, gap 0.0000); claims ran up to 2.0848 ahead of the truth; corrections 8; honest again: 0 corrections, 12/12 claims exact |
| 15 | no-slow: blocking with a sword at walking speed | blocked | truth is the honest path after 25 ticks (moved 1.0274, reference 1.0274, gap 0.0000); the server player is blocking; claims ran up to 2.3827 ahead of the truth; corrections 9; honest again: 0 corrections, 12/12 claims exact |
| 16 | anti-knockback: pongs normally, claims ignore the knock | blocked | truth knocked 3.2022 back, +1.2523 up; ServerPlayer knocked 3.2022; the client was corrected: 3 corrections, 3 accepted, forced 0; claim now 0.0000 from the truth; honest again: 0 corrections, 12/12 claims exact |
| 17 | anti-knockback: withhold every pong | blocked | unacked entries force-applied: 28 (deadline 5 windows); the knock reached the truth without an ack (z +2.1730); frozen after 1749 ms without a pong; windows refused while frozen: 6; still frozen after 5003 ms, not kicked (); the knock stayed on the truth (z... |
| 18 | stall: 4.5 s with nothing in or out, walking | honest | not kicked (); frozen during the stall; running again; recovered with 1 correction(s); honest again: 0 corrections, 12/12 claims exact |
| 19 | stall: 12.0 s with nothing in or out, standing | honest | not kicked (); frozen during the stall; running again; recovered with 0 correction(s); honest again: 0 corrections, 12/12 claims exact |
| 20 | anti-knockback: delay pongs 3 ticks to choose when the knock lands | bounded (lag within the deadline) | the knock landed at window +3, the client's choice +3, within the deadline 5 (forced 0); corrections 0; honest again: 0 corrections, 12/12 claims exact |
| 21 | anti-knockback: delay pongs 9 ticks to choose when the knock lands | blocked | the knock landed at window +5, not the client's +9 (deadline 5, forced 58); forced entries 58; corrections 2; honest again: 0 corrections, 12/12 claims exact |
| 22 | anti-knockback: batch two knocks into one drain past the deadline | blocked | the first knock reached the truth on its own (window 22); the second one after it (window 24); forced entries 13; honest again: 0 corrections, 12/12 claims exact |
| 23 | pongs: answered out of order | blocked | kicked: 'Invalid transaction replies'; transaction violations 1, stats 1 |
| 24 | pongs: unknown id in our band | blocked | kicked: 'Invalid transaction replies'; transaction violations 1, stats 1 |
| 25 | pongs: future id | blocked | kicked: 'Invalid transaction replies'; transaction violations 1, stats 1 |
| 26 | pongs: duplicate of an acked id | blocked | not kicked ; no violation (transactions 0, stats 0, duplicates 1); honest again: 0 corrections, 12/12 claims exact |
| 27 | pongs: foreign ids | blocked | not kicked ; no violation (transactions 0, stats 0, duplicates 0); honest again: 0 corrections, 12/12 claims exact |
| 28 | pongs: pong placed after a keybind packet | blocked | grammar violation counted: 1; counted, not kicked ; the knock reached the truth (z +2.9502); applied at its pong, not forced (0); honest again: 0 corrections, 12/12 claims exact |
| 29 | grammar: two PlayerInput packets in one tick | blocked | counted, not kicked ; grammar violation counted: 1; the last input before the move is the tick's: no jump, no correction (0); honest again: 0 corrections, 12/12 claims exact |
| 30 | timer: 22 tps for 4 s | blocked | windows run 83 of 88 sent in 4000 ms (real-time budget 91); the extra ticks waited or were refused: queued 5, refused 0; the truth walked 17.6567, no further than 83 ticks of walking (17.6567); honest again: 0 corrections, 12/12 claims exact |
| 31 | timer: 30 tps for 4 s | blocked | windows run 90 of 120 sent in 4000 ms (real-time budget 91); the extra ticks waited or were refused: queued 30, refused 7; past timer-queue-cap-ms windows are refused: 7; the truth walked 17.6567, no further than 83 ticks of walking (17.6567); honest again:... |
| 32 | timer: blink (1.2 s without packets, then the burst) | blocked | during the blink the ServerPlayer stayed (z +0.0000); the burst ran 10 of 24 windows at once (bank 10); then at most 2 per server tick; the truth walked 6.0003 for 29 windows; refused 0, queued 15; honest again: 0 corrections, 12/12 claims exact |
| 33 | timer: 15 tps for 3 s, then a 10-tick burst | blocked | the ack clock let 3 of 10 run at once (cap 6, bank 10); honest again: 0 corrections, 12/12 claims exact |
| 34 | timer: TickEnd never sent | blocked | nothing moved: windows run 0, truth z +0.0000; grammar violations 1; the open window holds 4 packets (cap 512); kicked, frozen false |
| 35 | timer: two move claims in one window | blocked | grammar violation counted: 1; neither claim was taken (truth z +0.0000); kicked; kicked, no honest phase |
| 36 | teleport: accept a server teleport with spoofed coordinates | blocked | ServerPlayer y max +0.0000 (0 ticks at the spoofed height); the accept was flagged: mismatches 1; the truth took the teleport (z 0.0000, y 0.0000); the ServerPlayer is back on the truth; honest again: 0 corrections, 12/12 claims exact |
| 37 | teleport: never accept a server teleport | blocked | the truth took the teleport and walked on (z +4.0576 past it); the ServerPlayer follows the truth (it is 0.0000 away, at z +4.0576); counted, not kicked ; counted after the ack deadline: {a server teleport was never accepted=>1}; honest again: 0 corrections... |
| 38 | teleport: accept an id never sent (correction band) to drop fake walls | blocked | control: the fake wall stops truth and client alike (z -1.192e-08, corrections 0); the truth still stops at the fake wall (z max -1.192e-08 past it; sent states 10 -> 10); the accept counted: violations 1; kicked, no honest phase |
| 39 | teleport: accept a vanilla id never sent | blocked | nothing moved; flagged: violations 1, accept mismatches 0; kicked, no honest phase |
| 40 | non-finite: NaN position claim | blocked | truth and ServerPlayer stay finite; non-finite position packets sent to the client: 0; windows handled without an exception (0 thrown); rejected: kicked 'Invalid move player packet received'; corrections 0; kicked, no honest phase |
| 41 | non-finite: infinite position claim | blocked | truth and ServerPlayer stay finite; non-finite position packets sent to the client: 0; windows handled without an exception (0 thrown); rejected: kicked 'Invalid transaction replies'; corrections 0; kicked, no honest phase |
| 42 | non-finite: NaN rotation claim | blocked | truth and ServerPlayer stay finite; non-finite position packets sent to the client: 0; windows handled without an exception (0 thrown); rejected: kicked 'Invalid move player packet received'; corrections 0; kicked, no honest phase |
| 43 | non-finite: NaN accept for a server teleport | blocked | ServerPlayer finite; kicked: 'Invalid transaction replies' |
| 44 | non-finite: NaN accept for our correction | blocked | truth and ServerPlayer finite; the NaN accept counted: violations 1; kicked or the correction is not left in flight (corrections 1, reconciler violations 0, kicked true); kicked, no honest phase |
| 45 | creep: 1e-7 per tick into a closed door (rebase-epsilon 0) | blocked | touching the face (-1.192e-08); after 100 creeping claims the truth sat 0.0000 inside, then reached -1.192e-08 past the face; claims exact 23/120, corrections while creeping 4, in all 5; honest again: 0 corrections, 12/12 claims exact |
| 46 | creep: 1e-7 per tick into a closed fence gate (rebase-epsilon 0) | blocked | touching the face (-1.192e-08); after 60 creeping claims the truth sat 0.0000 inside, then reached -1.192e-08 past the face; claims exact 23/82, corrections while creeping 2, in all 3; honest again: 0 corrections, 12/12 claims exact |
| 47 | creep: 0.9e-10 per tick into a closed door (rebase-epsilon 1e-10, its max) | blocked | touching the face (-1.192e-08); after 1300 creeping claims the truth sat 9.900e-10 inside, then reached -1.093e-08 past the face; claims exact 35/1263, corrections while creeping 61, in all 62; honest again: 0 corrections, 12/12 claims exact |
| 48 | creep: 0.9e-10 per tick down into the floor (rebase-epsilon 1e-10) | blocked | gravity sets the truth back on the floor every tick (min 0.0000); exact 1/96, corrections 4; honest again: 0 corrections, 12/12 claims exact |
| 49 | overlap: claims 0.05 inside a wall | blocked | claimed 0.0500 inside; truth z max -1.192e-08 past the face; corrections 5; honest again: 0 corrections, 12/12 claims exact |
| 50 | combat: honest hit at 2.5 (control) | control | an honest hit lands: landed 1 of 1 (judged valid 1, invalid 0, over cap 0) |
| 51 | combat: reach 3.5 | blocked | refused: landed 0 of 1 (judged valid 0, invalid 1, over cap 0) |
| 52 | combat: reach 4.5 | blocked | refused: landed 0 of 1 (judged valid 0, invalid 1, over cap 0) |
| 53 | combat: reach 6.0 | blocked | refused: landed 0 of 1 (judged valid 0, invalid 1, over cap 0) |
| 54 | combat: through a wall at 2.5 | blocked | refused: landed 0 of 1 (judged valid 0, invalid 1, over cap 0) |
| 55 | combat: killaura without aim (target 2 blocks to the side) | blocked | refused: landed 0 of 1 (judged valid 0, invalid 1, over cap 0) |
| 56 | combat: 5 attacks in one window (autoclicker) | blocked | landed 1 of 5 (judged valid 5, invalid 0, over cap 4) |
| 57 | combat: honest hit at 2.5 after the cheats (control) | control | an honest hit lands: landed 1 of 1 (judged valid 1, invalid 0, over cap 0) |
| 58 | combat: reach 4.5 while a server teleport is pending | blocked | refused: landed 0 (teleport pending true, judged valid 0, invalid 1) |
| 59 | combat: honest hit at 2.5 while a server teleport is pending (control) | control | an honest hit lands: landed 1 (teleport pending true, judged valid 1, invalid 0) |
| 60 | combat: backtrack past the rewind cap (pongs held 500 ms) | blocked | refused: landed 0 (forced 12, judged valid 0, invalid 1) |
| 61 | combat: one lag spike past the ack deadline -> then killaura without aim | blocked | refused: landed 0 of 1 (judged valid 0, invalid 1, over cap 0) |
| 62 | combat: one lag spike past the ack deadline -> then reach 4.5 | blocked | refused: landed 0 of 1 (judged valid 0, invalid 1, over cap 0) |
| 63 | combat: one lag spike past the ack deadline -> then an honest hit at 2.5 (control) | control | an honest hit lands: landed 1 of 1 (judged valid 1, invalid 0, over cap 0) |
| 64 | combat: a pong placed after an attack in its window | blocked | counted, not kicked ; counted: {a pong after the tick's keybinds=>1}; the attack before the pong landed 1 (judged at its place) |
| 65 | combat: a pong placed after an attack in its window -> then killaura without aim | blocked | refused: landed 0 of 1 (judged valid 0, invalid 1, over cap 0) |
| 66 | combat: a pong placed after an attack in its window -> then reach 4.5 | blocked | refused: landed 0 of 1 (judged valid 0, invalid 1, over cap 0) |
| 67 | combat: a pong placed after an attack in its window -> then an honest hit at 2.5 (control) | control | an honest hit lands: landed 1 of 1 (judged valid 1, invalid 0, over cap 0) |
| 68 | combat: an honest hit at 50 ms of latency (control) | control | an honest hit lands: landed 1 of 1 (judged valid 1, invalid 0, over cap 0) |
| 69 | combat: an honest hit at 150 ms of latency (control) | control | an honest hit lands: landed 1 of 1 (judged valid 1, invalid 0, over cap 0) |
| 70 | combat: an honest hit at 250 ms of latency (control) | control | an honest hit lands: landed 1 of 1 (judged valid 1, invalid 0, over cap 0) |
| 71 | combat: an honest hit at 300 ms of latency on a target sprinting across | control | an honest hit lands: landed 1 (judged valid 1, invalid 0; rewind 455 ms, cap 514 ms, display 106 ms, rtt base 345 ms, verdict VALID); nothing forced once the round trip settled (0 entries) |
| 72 | combat: fake lag (pongs on time, the victim's movement shown 10 ticks late) | blocked | refused: landed 0 (judged valid 0, invalid 1, verdict INVALID MISSED) |
| 73 | combat: attack while blocking (autoblock) | blocked | refused: landed 0 while blocking true (judged valid 0) |
| 74 | combat: attack in the window that closes a screen | blocked | refused: landed 0 (judged valid 0, grammar violations 0) |
| 75 | inventory-move: container click while walking | blocked | refused: clicks handled 0 with the tick's keys holding forward (grammar violations 0) |
| 76 | vehicles: mount under enforce (authoritative.vehicles: deny) | blocked | a forced mount is refused (passenger false) |
| 77 | combat: attack from an exempt state (riding) | blocked | riding (authoritative.vehicles: vanilla) is exempt (11 ticks); refused: landed 0 (judged valid 0) |
| 78 | world: place a block without holding one, stand on it | blocked | no prediction admitted (0) and no block placed; the truth never stood on it (y max +0.0000); the truth walked through the spot (z +4.9893); corrections 6; honest again: 0 corrections, 12/12 claims exact |
| 79 | world: spam placements with block acks held | blocked | first placement made; diagnostics: hand 63 minecraft:stone, windows 22, refused 0, queued 0, grammar 0, predictions refused 0, loaded true, in range true, pos (24.5, -60.0, -7.5), unobstructed true, entities there [], awaiting teleport null; the first block... |
| 80 | world: break a protected block, walk through before the revert | blocked | the refused break is no prediction (0), the block stands; the truth stops at it (z -1.192e-08); corrections 7; honest again: 0 corrections, 12/12 claims exact |
| 81 | self-echo: STOP_SPRINTING before acking the sprint attribute sync | blocked | ticks the truth kept the sprint modifier while not sprinting: 0 (speed attribute now 0.1000); walking 0.2159/tick, honest walk 0.2159; combat reads the simulated sprint (false, truth false); self attribute syncs carrying the sprint modifier: 2; ServerPlayer... |
| 82 | self-echo: sprint dropped without STOP_SPRINTING, attribute sync acked late | blocked | ticks the truth kept the sprint modifier while not sprinting: 0 (speed attribute now 0.1000); walking 0.2159/tick, honest walk 0.2159; combat reads the simulated sprint (false, truth false); self attribute syncs carrying the sprint modifier: 2; ServerPlayer... |

82 of 82 blocked.

## Rows added by the netcode, 2026-10-22 (88 of 88 with them)

Run on the netcode batch after the merge (the NAS block-glitch fix, review round 2, cheat suite 2's H1/H2/L2), private
live runner, sync tracker: 88 of 88 blocked, every honest phase clean. The suite's honest attacks now send their `Punch`
(a vanilla client always does), and its client model starts an item use with the hands its view holds (H2).

| Row | Verdict | Evidence |
|---|---|---|
| world: hold attack on a protected honey block underfoot, then walk off (honest) | blocked | the block stands; the truth never left its top; the client broke it 40 times and its own restore put it back 39 times; 0 corrections while it bobbed (40 claims held as its own hole, 40 restores modelled); 1 correction walking off; honest again 0 |
| world: break a protected wall block and walk into the gap (honest) | blocked | the block stands, the truth stops at it, 0 corrections |
| world: dig a protected glass pane and walk into the gap (honest) | blocked | the same through `STOP_DESTROY_BLOCK`, 0 corrections |
| combat: hit a target whose link stopped 2 s ago, where it stopped (review X2-01) | control | landed, VALID at 2050 ms past a 202 ms cap |
| teleport: a server teleport that reaches the client 1.2 s late, walking (review X2-03) | honest | the server player walks on 2.6 blocks from the forced teleport; the late landing rolls nothing back; accept matched, no violation |
| (after the summary) players who quit under authority are released (review PT2-02) | pass | 0 held |

## Since the 21:45 run (51 of 70)

Fixed by the netcode, each with its scenario above:

- **TickEnd never sent**: a window past 512 packets, 4096 drain replies or 65536 released entries overflows (a kick in
  enforce, authority off for the session in observe); an open window counts toward the freeze.
- **Spoofed / never / forged accepts**: server teleports complete at their pong and vanilla never takes an accept's
  coordinates; an accept for an id vanilla never issued, or a correction id never sent, is a protocol violation at once
  (§15.8); a teleport never accepted is counted, not kicked (the truth applied it: no gain). A resync never clears the
  sent block states.
- **Attack in the window that closes a screen**: dropped (vanilla's `missTime = 10000` drops those clicks).
- **Sprint modifier kept without sprint**: the truth's sprint comes from the simulation (§15.5), and a speed sync in
  flight is followed by a fresh one when the sprint changes before its ack.
- **Honest clients hurt (A, B, C)**: corrections forecast to the apply tick and go absolute when they keep missing or
  after refused ticks; honest hits while one's own teleport is pending or after a lag spike are judged on re-paired,
  exact mirrors; the rewind cap is per attacker (HR-2).
- **Stalls (NW-2)** freeze and recover with one correction; **ordering-only violations** (a pong or accept after the
  keybinds, a second input or sprint command) are counted and kick only past `client-profiles.protocol-kick-rate`.

## Smaller observations

- A client within `correction-threshold` but not exact is corrected after `correction-persist-ticks` (20) in a row.
- The truth stops 1.192e-8 short of every wall face (the same in the client: the port is exact). Tolerances in the suite
  are 1e-9 elsewhere and 1e-7 for "touching".
- Joins: a client that reports itself loaded before it holds its chunk (bots, not vanilla) is exempt `chunk-unknown`
  while it claims ground over a chunk our record lacks, so the truth never falls through a floor the client stands on.
