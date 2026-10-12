# GrimAC checklist for RevivalPaper

The live tests (`scripts/revival/live-tests.py`) prove what the server sends. They can't prove how our GrimAC fork reads
it, because that needs a real client. Run this checklist on a test server with the fork jar, the GrimAC fork and a real
26.x client before Arena moves to RevivalPaper. Use two accounts, and watch `/grim alerts` and the Grim log for
each row.

| # | Area | What RevivalPaper changes | How to test | Pass when |
|---|---|---|---|---|
| 1 | Velocity (`KnockbackHandler`) | Legacy hits send one velocity packet with the 1.7 knock (Phase 3). Projectile hits send theirs from the tracker. | 50 sword hits standing, sprinting and w-tapping; 20 rod, snowball and Punch-bow hits. | No velocity flags. The victim's movement matches the knock. |
| 2 | Sword blocking (`NoSlow`, prediction) | The client sees a `blocks_attacks` component on swords that the server's copy doesn't have (Phase 9). | Hold right click with a sword while walking, sprinting, strafing and jumping. Block-hit: block, release, hit, repeat. | No NoSlow or prediction setbacks while blocking. Movement slows to 0.2 like a shield. |
| 3 | Sprint (`SprintA`–`SprintG`) | Legacy hits never clear the attacker's sprint flag (Phase 4). Blocking re-arms the sprint knock. | Sprint-hit combos with and without w-taps; sprint, block, then sprint again. | No sprint flags. The client stays sprinting after hits, with no latch-off. |
| 4 | W-tap (`WTapA`, experimental) | 1.7 play rewards w-tapping, so legitimate players w-tap far more often. | A legitimate player w-taps by hand for 2 minutes of sword PvP. | No WTapA alerts for hand w-taps. Otherwise tune its thresholds before cutover. |
| 5 | Attack speed (`Autoclicker*`, `KillAura*`, `MultiInteractB`) | While a sword is held, attack speed is maxed (`hcfrevival:legacy_no_cooldown`, Phase 6), so there's no cooldown bar and clicks aren't throttled. | Click at 10–16 CPS with a sword, then switch to an axe and click. | No autoclicker or killaura flags at legitimate CPS. The axe cooldown comes back at once after the swap. |
| 6 | Rod (`KnockbackHandler`, prediction) | A hooked player isn't pulled on their screen: reeling changes only their server-side 1.7 motion (Phase 8). A rod hit knocks like a 0-damage hit. | Rod a player, reel them in, and rod-into-sword combos. | No velocity or prediction flags from reeling. The rod knock is accepted like a sword knock. |
| 7 | Reach and hitboxes (`Reach`, `Hitboxes`) | Unchanged: attacks still go through Paper's attack-range check. | Normal sword PvP at 2.8–3.0 blocks. | Same alert rate as on Purpur. |

Record results in the cutover plan (phase 11), with the Grim version and config used.
