# Classic 1.7 under authority: audit and fixes

2026-10-22. The user's direction: Neue is backseated; classic 1.7 RevivalPaper PvP under authority comes first, "solid
as hell". The NAS world runs rule set `classic-capped` (classic plus the 12 CPS cap and the 0.15 s click buffer).

An audit read the merged tree through four lenses: the 66-row 1.7 issue checklist (`pvp-issues.md`), one classic
exchange end to end, the cheats that matter in classic HCF, and the fights no rig plays. A skeptic checked each gap
against the code before it counted. 14 work items survived, plus 4 rows that `pvp-issues.md` already tracks. Seven
claims were refuted.

## Work items

| # | Pri | Item | Status | What changed | Proof |
|---|---|---|---|---|---|
| 1 | P0 | The attack eye followed any claim within 0.5 per axis of the truth: 0.5-0.7 blocks of free reach, and hits through doors and panes | fixed | The eye offset is only for a gap the server caused: entries forced at their deadline, a refused window, a denied break, a revert snap (`AuthPlayer.gapCaused`). It also needs every claim since the client was last on the truth to land within `attack-eye-forecast-tolerance` (0.03) of the belief, and no block between the truth and the claim (`clearPath`). A gap the claim made up casts from the truth and counts as `eye offsets refused` in `/revival auth stats`. | cheat rows: "claims 0.49 toward a target 3.3 from the true eye", "creeps 0.025 a tick ...", "claims 0.49 through a glass pane it touches" |
| 2 | P1 | Under enforce the sprint ledger still listened to STOP_SPRINTING: a spoofed STOP gave every hit the armed sprint knock with no w-tap | fixed | The ledger has a simulation-owned mode (`SprintLedger.simulated`). Under enforce sprint packets move nothing, refused windows included. The simulation starts and stops it from the change between tick ends (`sendIsSprintingIfNeeded`), not from the state after the tick's keybinds. A held sprint key's re-sprint after a hit is no change (the 1.7 held level). A double-tap player's hit leaves it stopped. | unit `SprintLedgerTest.underAuthorityStopPacketsMoveNothing`; cheat rows "spoofed STOP_SPRINTING in a click's window", "STOP, then START with a click (SuperKB)", control "an honest w-tap re-arms it" |
| 3 | P1 | Use packets were a second rotation and hand channel: silent auto-pot, silent rod and pearl aim, off-hand pots behind a sword, one-tick pot swaps | fixed | `UseItem` is replayed with the tick's rotation under enforce (`use-rotation` counted, never dropped). An off-hand use, use-on or interact is dropped when the main hand holds an item that starts using (`offhand-after-main`). A `SetCarriedItem` to another slot after the window's first attack or use is dropped, and the server's slot is sent back (`slot-after-action`, pvp-issues IN-5). | unit `InputRulesTest` (three rules); cheat rows "a pot thrown at the feet under level claims, the sword back in the same tick", "an off-hand pot thrown while a sword is in the main hand", control "... with an empty main hand" |
| 4 | P1 | The piston and shulker-box band adopted any 0.6-per-axis claim within 14 blocks of any acked BlockEvent: self-made claim-wall climbs and free speed | fixed | A push is matched only while the truth's box is in what the event moves: a piston's lane (`block-range` long, plus one block up for what stands on it) or a box's lid side. Only the part of the claim along the push is adopted, with its sign, at most 0.51 a tick and the push's travel in all (a piston 1.02, a lid 0.6). The rest of the claim is judged like any gap. | unit `ExternalMoversTest` (lane, axis, sign, travel); cheat rows "a piston 10 blocks off toggled, the claims rise 0.55 a tick", "in the lane, the claims rise ...", control "pushed along its lane, the claims adopted" |
| 5 | P2 | A UseItem and its RELEASE in one window: a zero-cost block-hit that re-armed the sprint knock | fixed | Grammar rule `release-without-use`: a release only comes from a window that started using (`Minecraft.handleKeybinds`, the only client sender, checked in the 26.3 source). Under authority the block re-arm comes from the simulation, after a tick that really ran with a legacy block up, once per block. | unit `InputRulesTest.aReleaseOnlyComesFromAWindowThatStartedUsing`; cheat row "a use and its release in one window", control "an honest block-hit, the release a tick later, re-arms it" |
| 6 | P2 | 26.3 keeps an existing sprint through a sword block and a sneak (x0.2 or x0.3 of sprint speed, plus the +0.2 sprint-jump boost), where 1.7 dropped sprint | needs a decision | Parity call for the user: (a) stop sprint as a server-meant write when a legacy block starts (1.7 behaviour, needs a real-client check of the 1.21.2+ sprint latch); (b) keep 26.3's behaviour, document it in BL-1, and tune `use-speed-multiplier`. | |
| 7 | P2 | Paper's range check on server positions ran after a VALID lag-compensated hit, and dropped chase hits on fast targets | fixed | A replay the judge called VALID on a player, and Neue's landing replay, skip Paper's range check for that target only (patch 1018 `Lag-compensated reach`, `AuthPlayer.judgedReach`). The world-border, self and invalid-entity checks stay. | live row "an honest hit at 300 ms of latency on a target running straight away with Speed IV" |
| 8 | P2 | Jump-reset velocity: a client can jump in exactly the window that applies each knock | measured | `/revival auth stats` counts knocks applied on the ground and those whose own window pressed jump (a rising edge). The `jump-reset` anomaly waits for a threshold calibrated on human and macro models; the hold-to-land variant is KB-2's ack deadline. | |
| 9 | P2 | Golden apples and drinkable potions were never eaten or drunk under enforce in any test | covered (live) | Honest rows in the cheat suite: a golden apple eaten while walking, a Speed potion drunk and then a sprint; no correction, every claim exact, the use ends on both sides. The real-client rig still has no eating scenario. | cheat suite controls "honest: a golden apple eaten under enforce", "honest: a Speed potion drunk under enforce, then a sprint" |
| 10 | P2 | The real-client agent can't press hotbar keys, so rod, pot and pearl swaps are never checked against a real client | open | coverage | |
| 11 | P3 | Rod, pot or pearl to sword swaps: the first hits skip 1.7's x0.6 attacker slowdown and sprint stop until the no-cooldown modifier reaches the client | open | exactness | |
| 12 | P3 | The double-click anomaly fired on honest butterfly clickers in classic | fixed | `stats.double-click-ratio` defaults to 1.0 (only a client doubling every attack tick); config v6 moves a file's old 0.3 up. The ratio stays a statistic in `/revival auth inspect`. | unit `RevivalConfigTest.version5DoubleClickAlertStopsFlaggingButterflyClickers` |
| 13 | P3 | No honest mid-fight pot refill through the inventory screen is tested under enforce | open | coverage (client-compat R1) | |
| 14 | P3 | `pvp-issues.md` and the projectile docs are stale | partly | This file; HR-4, SP-1, SP-3, MV-5 and IN-5 point here. A full refresh of the ranked table is still to do. | |

## Already tracked

- Off-crosshair placing and digging (ghost hand through walls): pvp-issues WD-4.
- Crouching and swimming shrink the judged hitbox to 1.5 and 0.6: pvp-issues HR-8.
- Packet crits, keep-sprint and the cancelled-placement block glitch have no cheat rows: MV-3, SP-3, WD-1. SP-3 now has its rows (item 2).
- Classic autoclicking is bounded at 20/s, and randomized or hit-select clickers are undetectable: IN-1, IN-3, HR-6. The NAS world's `classic-capped` caps at 12 with the buffer.

## Refuted

- Lunar with HCFR's Apollo combat options is judged by the vanilla grammar (client profiles follow Apollo, §15.8).
- ViaVersion and Geyser players skip authority by default (`ineligible: allow` is the documented choice).
- Beyond 24 blocks the 26.3 stutter is back (K2's range is configurable, VI-1 tracks it).
- The damage window reopens after 9 or 11 server ticks from a queued window.
- Knock direction comes from server positions at different real times.
- The classic lens's classification, and the proof rows for cheats authority closes by design.
- The rod combo is never played end to end under enforce.
