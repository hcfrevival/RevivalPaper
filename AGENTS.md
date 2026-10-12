# AGENTS.md

Guidance for anyone, human or agent, changing RevivalPaper.

## What this is

RevivalPaper is HCF Revival's server fork of [Leaf](https://github.com/Winds-Studio/Leaf)
(Paper → Purpur → Gale → Leaf), on Minecraft 26.3, Java 25.

Its job is 1.7.10 PvP on a modern server:

- 1.7.10 attack math, knockback, sprint, damage immunity and hit cadence, applied **per hit**
  when the attacker holds a legacy weapon (every sword by default, configurable);
- SportBukkit's 1.7.10 velocity fixes, so knockback doesn't depend on packet order;
- knockback profiles editable in game;
- everything else, including axes, maces, spears, fists and modern validation, stays 26.x.

Every value lives in `revival.yml`, next to `paper-global.yml`, `spigot.yml` and `purpur.yml`.

## Where code goes

| What | Where |
|---|---|
| Combat logic, config, commands | `leaf-server/src/main/java/gg/hcfrevival/revival/**` (plain source, not a patch) |
| Tests | `leaf-server/src/test/java/gg/hcfrevival/revival/**` |
| Hooks into Minecraft code | `leaf-server/minecraft-patches/features/1000-*.patch` and up |
| Hooks into Paper/CraftBukkit code | `leaf-server/paper-patches/features/1000-*.patch` and up |
| Bukkit API additions | `leaf-api/paper-patches/features/1000-*.patch` and up |
| Build additions (test task, jar branding) | `gradle/revival.gradle.kts` |
| Hook list for the CI guard | `scripts/revival/markers.txt` |
| Docs | `docs/revival/` |

Leaf's own patches (`0001`–`0999`), `leaf-server/build.gradle.kts.patch`, `leaf-api/build.gradle.kts.patch`
and Leaf's sources (`org.dreeam.leaf`, `org.purpurmc`, `org.galemc`, ...) are upstream. Don't edit them
unless there is no other way, and if you do, say why in the PR.

## Rules for hooks

1. **Logic lives outside patches.** A patch adds the smallest call into `gg.hcfrevival.revival`
   and nothing else. Formulas, state machines and config parsing go in plain source, where they can be
   unit tested without a server and survive a Minecraft update untouched.
2. **One mechanic per patch, numbered from 1000.** Our patches apply after all of Leaf's, so Leaf
   updates never have to rebase around them.
3. **Mark every hunk.** Multi-line hunks sit between `// Revival start - <mechanic>` and
   `// Revival end - <mechanic>`; a single changed line ends with `// Revival - <mechanic>`.
   Use fully qualified class names in hunks instead of adding imports.
4. **List every hook in `scripts/revival/markers.txt`** in the same PR. CI applies the patches and runs
   `scripts/revival/check-markers.py`, which fails when a listed hook is missing (for example a hunk
   dropped while resolving a conflict) or when a Revival marker exists that isn't listed.
5. **Keep modern validation and the Bukkit event contract.** Attacks still go through Paper's attack
   handling (reach, world border, invalid-target checks), and the usual events still fire with correct
   values: `PrePlayerAttackEntityEvent`, `EntityDamageByEntityEvent`, `EntityKnockbackByEntityEvent`,
   `PlayerVelocityEvent`, `PlayerToggleSprintEvent`. Anticheats and plugins should only see different
   numbers, not different traffic.
6. **Opt in per hit.** A legacy rule applies only when the hit qualifies (legacy weapon, projectile
   toggle). Every other code path keeps vanilla 26.x behaviour.

## revival.yml

- `gg.hcfrevival.revival.config.RevivalConfig` loads it; `ConfigReader` reads typed values and records
  defaults so missing keys are written back.
- Settings are an immutable `RevivalSettings` snapshot. A reload builds a new snapshot and swaps it in
  only if the whole file loaded, so a broken file never half-applies. Read `Revival.settings()` once per
  action rather than caching it across ticks.
- A wrong-typed value falls back to its default and is reported in the console and by
  `/revival reload`. An unparseable file stops startup, and on reload keeps the previous settings.
- Defaults reproduce 1.7.10. Every new constant gets a key; nothing is hard-coded.
- Per-world values resolve `world-settings.<world>.<key>`, then `world-settings.default.<key>`.
- Bump `RevivalConfig.CURRENT_VERSION` and add a migration in `RevivalConfig.read` when a key is
  renamed or its meaning changes.

## Building and testing

```bash
./gradlew applyAllPatches                 # applies Paper, Leaf and our patches
python3 scripts/revival/check-markers.py  # every hook survived patching
./gradlew :leaf-server:runRevivalTests    # every @RevivalTest under gg.hcfrevival.revival
./gradlew createPaperclipJar              # leaf-server/build/libs/leaf-paperclip-26.3*.jar
```

- Tag tests with `@gg.hcfrevival.revival.RevivalTest`. Math and config tests are plain JUnit; tests that
  need registries add Paper's test extensions.
- Give every formula a golden-value test against numbers derived from 1.7.10, with the source line in a
  comment.
- `.github/workflows/revival-ci.yml` runs the four steps above on every PR and on pushes to `ver/26.3`,
  and uploads the jar. CI is the build of record: if you can't build locally, say so in the PR rather than
  claiming it compiles.

## Changing a hook

The applied sources are git repositories: `leaf-server/src/minecraft/java` (Minecraft),
`paper-server` and `paper-api`. After `applyAllPatches`:

1. Edit the applied source.
2. Commit it in that repository, as a new commit for a new mechanic, or as a fixup of the existing
   `RevivalPaper ...` commit. `gradle/patch-editing.gradle.kts` has helper tasks
   (`editMinecraftFeaturePatches`, `commitMinecraftFeatureChanges`, `continueRebasing`, and the Paper and
   API equivalents).
3. Run the matching `rebuild*Patches` task (`./gradlew tasks --all | grep -i rebuild` lists them) and
   commit the regenerated patch files in this repository.

Hand-written patches are fine when you can't apply locally. Copy context lines exactly from the state
Leaf's patches leave behind, then let CI prove they apply.

## Updating Leaf or Minecraft

1. Merge the new Leaf branch into ours.
2. Run `applyAllPatches`. If a `1000+` patch fails, fix the hook against the new code. Keep the hook
   minimal, and move logic into `gg.hcfrevival.revival` instead of growing the patch.
3. Run the marker guard and `runRevivalTests`. A new Minecraft sword without a damage entry, or a dropped
   hook, fails here instead of on a live server.

## Working on this repo

- Branch from `ver/26.3` (for example `revival/phase-1-attack-math`), one PR per phase or fix. Never
  push to `ver/26.3` directly.
- The maintainer reviews and merges. Don't merge your own PR.
- Keep diffs minimal: no reformatting or drive-by cleanup in upstream files.
- Commit messages and PR descriptions say what changed for a player or server owner, then how.

## Performance, threading, compatibility

These carry over from Leaf:

- Combat hooks run on the hot path for every hit and every movement packet. Avoid allocation, boxing,
  streams and capturing lambdas there, and keep early exits for non-legacy hits first.
- Don't move work to another thread or weaken a thread check. Leaf has async entity tracking and
  optional parallel world ticking, so per-player combat state must be owned by the entity's tick thread.
- Preserve vanilla and Paper behaviour, plugin compatibility and persistent formats for everything that
  isn't a legacy hit.
