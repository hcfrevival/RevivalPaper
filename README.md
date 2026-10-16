# RevivalPaper

RevivalPaper is HCF Revival's Minecraft server: a fork of [Leaf](https://github.com/Winds-Studio/Leaf)
(Paper → Purpur → Gale → Leaf) that plays **1.7.10 PvP on a modern 26.x server**.

A hit with a legacy weapon (every sword, by default) uses 1.7.10 damage, knockback, sprint, armor and hit
cadence. Everything else (axes, maces, spears, fists, mobs hitting players, movement validation and the
plugin API) stays vanilla 26.x. All of it is configured in one file, `revival.yml`.

| | |
|---|---|
| Minecraft | 26.2 |
| Java | 25 |
| Branch | `ver/26.2` |
| Upstream | Leaf `ver/26.2` |
| Downloads | [Releases](https://github.com/hcfrevival/RevivalPaper/releases) (`revivalpaper-26.2-<commit>.jar`) |
| Brand | `hcfrevival:revivalpaper` (`Bukkit.getName()` is `RevivalPaper`) |

> [!NOTE]
> `ver/26.3` (Minecraft 26.3) stops at the Phase 10 merge and isn't maintained: 26.3 was dropped because of the
> client bug MC-311974. New work goes to `ver/26.2`.

## Contents

- [What RevivalPaper changes](#what-revivalpaper-changes)
- [Configuration](#configuration)
- [Commands and permissions](#commands-and-permissions)
- [Plugin API](#plugin-api)
- [Project setup](#project-setup)
- [Releases](#releases)
- [Repository layout](#repository-layout)
- [Contributing](#contributing)
- [Credits and license](#credits-and-license)

## What RevivalPaper changes

Each mechanic is one patch numbered from `1000`, so our patches apply after all of Leaf's. The patches only
call into plain Java under `gg.hcfrevival.revival`, where the logic is unit tested.

| Mechanic | What a player sees | Patch |
|---|---|---|
| Config | `revival.yml` loads at startup next to `paper-global.yml` and `purpur.yml`; `/revival` is registered. | `1000-RevivalPaper-config` |
| Attack damage | 1.7.10 sword damage (wood/gold 5, stone/copper 6, iron 7, diamond 8, netherite 9, the player's base 1 included), 1.7 Sharpness (+1.25 per level), Strength and Weakness, 1.5× falling crits, no sweep. | `1001-Legacy-attack-damage` |
| Knockback engine | A legacy hit knocks like 1.7.10 from the world's knockback profile, base and extra knock in one event. | `1002-Knockback-engine` |
| Consistent knockback | SportBukkit's 1.7.10 velocity fix: the victim is knocked from their 1.7 server-side motion and gets exactly one velocity packet per hit, so knockback doesn't depend on packet order. In water and lava that motion follows 1.7.10's liquid rules, so hits lift swimmers instead of pushing them down. | `1003-Consistent-knockback` |
| Sprint | The sprint knock arms once per sprint start, so w-tapping matters as it did in 1.7. Legacy hits never clear the attacker's sprint flag (modern clients would latch sprint off). | `1004-Sprint` |
| Damage immunity and defence | 1.7.10 armor (4% per point, no toughness) and Protection (rolled once per hit) inside CraftBukkit's damage modifiers. A sprint hit that overflows the victim's damage window still adds its extra knock to their server motion but sends no packet, as 1.7.10 did. | `1005-Damage-immunity-and-defence` |
| Hit registration | No attack cooldown bar while a legacy weapon is held (a transient attack-speed modifier); optional cap on attacks per tick. | `1006-Hit-registration` |
| Live profiles | Knockback profiles edited in game, per-world and per-player profiles, and the plugin events below. | `1007-Live-profiles` |
| Projectiles | 1.7 rod, bow, snowball and egg hits: 0-damage hits land on players, knock comes from the shooter with Punch folded in, rods hit on impact, cast and fall like 1.7 and reel with the 1.7 pull. | `1008-Projectiles` |
| Sword blocking | Right click with a sword blocks like 1.7: a blocked hit takes (damage + 1) × 0.5 and full knockback. Clients see the sword as "edible" with the block animation, so the block starts instantly and block-hitting stays fluid; the server's own item is untouched. | `1009-Sword-blocking` |

Paper-side hooks are in `leaf-server/paper-patches/features/1000-RevivalPaper-config.patch` (the
`--revival-settings` startup option, `/reload` support, branding that still passes Leaf brand checks) and
`1001-Consistent-knockback.patch`.

Every hit still goes through Paper's attack handling (reach, world border, invalid targets), and the usual
events fire with correct values: `PrePlayerAttackEntityEvent`, `EntityDamageByEntityEvent`,
`EntityKnockbackByEntityEvent`, `PlayerVelocityEvent`, `PlayerToggleSprintEvent`. Plugins and anticheats see
different numbers, not different traffic.

Leaf's own options are unchanged. [`docs/revival/leaf-defaults.md`](docs/revival/leaf-defaults.md) lists the
Leaf defaults that differ from Purpur and which ones matter for a PvP server.

## Configuration

`revival.yml` sits in the server folder next to `paper-global.yml`, `spigot.yml` and `purpur.yml`. A different
path can be passed with `--revival-settings <file>`. The server writes the file with every default on first
start, and writes any missing key back on later starts.

- `/revival reload` (or `/reload`) applies changes. A file that fails to parse is refused and the previous
  settings stay live; a value with the wrong type falls back to its default and is reported in the console
  and in the command output. A file that can't be parsed at startup stops the server.
- Values under `world-settings.default` apply to every world. To change one world, add
  `world-settings.<world name>` with only the keys that differ.
- Defaults reproduce 1.7.10.

### `world-settings.<world>.legacy-combat`

| Key | Default | What it does |
|---|---|---|
| `weapons` | `["#minecraft:swords"]` | Items (`minecraft:stick`) and item tags (`#minecraft:swords`) that make a hit legacy. `[]` turns legacy combat off in that world. |
| `weapon-damage` | wood 5, gold 5, stone 6, copper 6, iron 7, diamond 8, netherite 9 | Total 1.7 damage per weapon, the player's base 1 included. A weapon needs an entry here to be legacy. |
| `strength-multiplier-per-level` | `1.3` | Strength multiplies damage by `1 + this × level`. |
| `weakness-per-level` | `0.5` | Weakness subtracts `this × level` before Strength. |
| `sharpness-per-level` | `1.25` | Extra damage per Sharpness level. |
| `critical-multiplier` | `1.5` | Damage multiplier for a falling hit. |
| `sweep-attack` | `false` | Whether a legacy hit may sweep. |
| `knockback-profile` | `legacy-1.7` | The knockback profile this world uses (see below). |
| `legacy-armor` | `true` | 4% reduction per armor point, ignoring toughness. |
| `legacy-enchant-protection` | `true` | 1.7.10 Protection instead of 26.x's. |
| `randomize-protection` | `true` | Roll each hit's Protection between half and all of it, as 1.7.10 did. |
| `hide-cooldown-indicator` | `true` | No cooldown bar while a legacy weapon is held. |
| `max-attacks-per-tick` | `0` | Legacy attacks a player may land per tick; `0` is unlimited, as in 1.7.10. |

### `world-settings.<world>.legacy-combat.projectiles`

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | Every projectile toggle at once. |
| `knockback-profile` | `inherit` | Profile for projectile knock; `inherit` uses the shooter's melee profile. |
| `knockback-origin` | `shooter` | Knock away from the `shooter` (1.7.10) or along the `projectile`'s flight (26.x). |
| `shooters` / `victims` | `all` | `players` limits legacy projectiles to ones players shot / hits on players. |
| `zero-damage-hits` | `true` | 0-damage hits (rod, snowball, egg) land on players: knock, flash and open the damage window. |
| `arrow.enabled`, `arrow.launchers` | `true`, `["minecraft:bow"]` | Which launchers fire legacy arrows. |
| `arrow.punch-per-level`, `arrow.punch-vertical` | `0.6`, `0.1` | Horizontal push per Punch level and upward push from Punch. |
| `snowball.enabled`, `egg.enabled` | `true` | |
| `fishing-rod.enabled` | `true` | |
| `fishing-rod.hit-on-impact` | `true` | The hook deals a 0-damage hit to what it lands on. |
| `fishing-rod.hook-only-if-hit` | `true` | The hook only attaches when that hit landed. |
| `fishing-rod.reel-pull-players` | `shadow` | `shadow` changes only the hooked player's 1.7 server motion (no visible pull); `vanilla` is 26.x's pull. |
| `fishing-rod.reel-pull-other` | `legacy` | 1.7.10 (`legacy`) or 26.x (`vanilla`) pull on hooked mobs. |
| `fishing-rod.reel-durability` | `3` | Rod durability used by reeling in a hooked entity. |
| `fishing-rod.cast-speed`, `fishing-rod.gravity` | `1.5`, `0.04` | 1.7 cast speed and bobber gravity. |

### `sword-blocking`

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | Right click blocks with a legacy weapon. |
| `client-decoration` | `true` | Send clients the "edible" sword so they show and predict the block. |
| `damage-offset`, `damage-factor` | `1.0`, `0.5` | A blocked hit takes `(damage + offset) × factor`. |
| `ignore-armor-bypassing-damage` | `true` | Blocking doesn't reduce damage that bypasses armor. |
| `use-speed-multiplier` | `0.2` | Movement speed while blocking (0 to 1). |
| `rearm-sprint-on-block` | `true` | Blocking re-arms the sprint knock, as releasing W would. |

### Knockback profiles

A world picks a profile with `legacy-combat.knockback-profile`. These presets are built in:

`legacy-1.7` (default), `legacy-1.8`, `kohi`, `minehq`, `badlion`, `mmc`, `lunar`, `velt`, `hcfr`, `hcfr-projectile`

Profiles under `knockback-profiles.<name>` in `revival.yml` override a preset of the same name key by key; any
other name starts from `legacy-1.7`. Only profiles that differ from their preset are written to the file, so
preset fixes keep reaching servers that never edited them.

| Key | What it does |
|---|---|
| `friction`, `friction-vertical` | The victim's motion is divided by these before the knock. |
| `horizontal`, `vertical` | Base push away from the attacker and upward. |
| `vertical-cap` | Upward motion after the base knock is capped here. |
| `extra-horizontal`, `extra-vertical` | Extra push per level (Knockback enchant, plus sprint). |
| `attacker-slowdown` | The attacker's horizontal motion is multiplied by this on an extra-knock hit. |
| `velocity-clamp` | Each axis is clamped to ± this, the most a 1.7 velocity packet could carry. |
| `resistance-mode` | `none`, `roll` (1.7: skip the knock on a roll) or `scale` (26.x). |
| `motion-model` | `sportbukkit` (every hit lands the same), `vanilla-1.7` (combo hits compound, as in 1.7.10) or `none` (26.x motion). |
| `ground-prediction-distance`, `ground-prediction-ping-offset` | Treat a nearly landed victim as landed when their ping says they already are; `0` turns it off. |
| `sprint-armed-level` | Sprint part of the extra-knock level on the first hit after a sprint start. |
| `sprint-held-level` | Sprint part on later hits while sprint is held; `0` in 1.7.10, which needed a w-tap. |
| `sprint-arm-requires-ground` | A sprint start only arms the next hit when the attacker was on the ground. |

The presets other than `legacy-1.7`, `legacy-1.8` and `hcfr*` come from archived server configs as curated by
[Mental](https://github.com/owengregson/Mental) (MIT); their authenticity can't be verified.

`knockback-autosave` (default `false`) saves every `/revival kb` edit to `revival.yml` straight away. With it
off, edits are live but are lost on restart until you run `/revival kb save`.

## Commands and permissions

Everything is under `/revival`, op-only by default.

| Command | Permission | What it does |
|---|---|---|
| `/revival reload` | `revival.command.reload` | Re-read `revival.yml`. |
| `/revival version` | `revival.command.version` | Show the build and `revival.yml` config version. |
| `/revival kb list` | `revival.command.knockback` | List profiles, whether presets were edited, and which worlds use them. |
| `/revival kb show <profile>` | | Every value of a profile. |
| `/revival kb set <profile> <key> <value>` | | Change one value, live. |
| `/revival kb create <name> [from]` | | New profile, copied from `from` (default `legacy-1.7`). |
| `/revival kb delete <profile>` | | Delete a custom profile. |
| `/revival kb reset <preset>` | | Undo edits to a built-in preset. |
| `/revival kb use <profile> [world <world> \| player <player>]` | | Assign a profile to the default, a world, or one player (`none` clears a player's). |
| `/revival kb save` | | Write the live profiles to `revival.yml`. |
| `/revival kb debug` | | Toggle a chat line for every legacy knock you deal or take. |

`revival.command` grants all three.

## Plugin API

The API ships in `leaf-api` under `gg.hcfrevival.revival.api`. Check for the fork with
`ServerBuildInfo.buildInfo().brandId()` (`hcfrevival:revivalpaper`) before calling it.

- `RevivalCombat.legacyCombat(World)`: whether legacy combat is on in a world.
- `RevivalCombat.knockbackProfiles()`: every profile name.
- `RevivalCombat.getKnockbackProfile(Player)` / `setKnockbackProfile(Player, String)`: per-player profiles, for
  example per arena ladder. `null` clears it.
- `RevivalKnockbackEvent`: after a legacy hit knocked its victim, with the motion they were given. Paper's
  `EntityKnockbackEvent` fires first and can still change or cancel the knock.
- `SprintArmEvent` / `SprintConsumeEvent`: a sprint start armed the next sprint knock / a hit used it up.

The API isn't published to a Maven repository. Build it with `./gradlew :leaf-api:jar` and depend on the jar,
or compile against Paper's API and reach the fork through reflection.

## Project setup

You need **JDK 25**, **Git** (with `user.name` and `user.email` set; patching makes commits), **Python 3** for
the test scripts, and network access to PaperMC's Maven and Mojang (the build downloads the vanilla server).

```bash
git clone https://github.com/hcfrevival/RevivalPaper.git
cd RevivalPaper
git checkout ver/26.2

./gradlew applyAllPatches                 # applies Paper, Purpur, Gale, Leaf and our patches
python3 scripts/revival/check-markers.py  # every RevivalPaper hook survived patching
./gradlew :leaf-server:runRevivalTests    # unit tests for gg.hcfrevival.revival
./gradlew createPaperclipJar              # leaf-server/build/libs/leaf-paperclip-26.2*.jar
```

Run the jar like any Paper server: `java -jar leaf-paperclip-26.2-*.jar --nogui`.

Optional checks:

```bash
# Live-server tests: two fake players fight on a throwaway server, with the sync and the async entity tracker
./gradlew :leaf-server:revivalLiveTestPlugin && python3 scripts/revival/live-tests.py

# Core contract tests: hcfrevival/core's own harnesses run on this jar (needs a built core checkout)
./gradlew :leaf-api:jar
python3 scripts/revival/core-contract.py --core <path to core>
```

After `applyAllPatches`, the patched sources are git repositories you can open in an IDE:
`leaf-server/src/minecraft/java` (Minecraft), `paper-server` and `paper-api`. To change a hook, edit there, commit
in that repository, then run the matching `rebuild*Patches` task and commit the regenerated patch.
[`AGENTS.md`](AGENTS.md) has the full workflow.

[`docs/revival/grimac-checklist.md`](docs/revival/grimac-checklist.md) lists the anticheat checks that need a
real client before a server moves to a new build.

### CI

[`.github/workflows/revival-ci.yml`](.github/workflows/revival-ci.yml) runs on every pull request and push to
`ver/26.2`: apply patches, marker check, unit tests, Paperclip jar, live-server tests, and core contract tests
when the `CORE_READ_TOKEN` secret is set (reported, not blocking). It uploads `revivalpaper-26.2.jar` as an
artifact. CI is the build of record. Leaf's own workflows only run on `Winds-Studio/Leaf`.

## Releases

Each release is a GitHub pre-release tagged `26.2-<short commit>` holding `revivalpaper-26.2-<short commit>.jar`
from a green CI run on `ver/26.2`, with its sha256 in the notes. Servers pin a release by URL and sha256, so a
new build reaches them only when that pin is bumped.

## Repository layout

| Path | What's there |
|---|---|
| `leaf-server/src/main/java/gg/hcfrevival/revival/` | Combat logic, `revival.yml`, `/revival` |
| `leaf-server/src/test/java/gg/hcfrevival/revival/` | Unit tests (`@RevivalTest`) |
| `leaf-server/src/revivalLiveTest/` | Live-server test plugin |
| `leaf-server/minecraft-patches/features/1000-*` | Hooks into Minecraft |
| `leaf-server/paper-patches/features/1000-*` | Hooks into Paper and CraftBukkit |
| `leaf-api/src/main/java/gg/hcfrevival/revival/api/` | Plugin API and events |
| `gradle/revival.gradle.kts` | Test tasks and jar branding |
| `scripts/revival/` | Marker list and check, live-test and core contract runners |
| `docs/revival/` | Leaf defaults review, GrimAC checklist |

Everything else (patches `0001`–`0999`, `org.dreeam.leaf`, `org.purpurmc`, `org.galemc`, ...) is upstream Leaf.

## Contributing

Read [`AGENTS.md`](AGENTS.md) first. In short:

- Branch from `ver/26.2`, one PR per mechanic or fix. Never push to `ver/26.2` directly; the maintainer
  reviews and merges.
- Logic goes in `gg.hcfrevival.revival`; a patch adds the smallest call into it.
- Mark every hunk with `// Revival start/end - <mechanic>` or `// Revival - <mechanic>` and list it in
  `scripts/revival/markers.txt`.
- Every new constant gets a `revival.yml` key, and every formula gets a golden-value test against 1.7.10.
- Leave upstream files alone unless there's no other way, and say why in the PR.

To update Leaf, merge the new Leaf branch, fix any `1000+` patch that no longer applies, and run the marker
check and tests. Keep our `README.md` when Leaf's changes.

## Credits and license

RevivalPaper is built on [Leaf](https://github.com/Winds-Studio/Leaf), [Gale](https://github.com/Dreeam-qwq/Gale),
[Purpur](https://github.com/PurpurMC/Purpur) and [Paper](https://github.com/PaperMC/Paper). The 1.7.10 velocity
model follows SportBukkit. Licensing follows upstream; see [`LICENSE.md`](LICENSE.md) and [`licenses/`](licenses).
