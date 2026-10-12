#!/usr/bin/env python3
"""Runs core's own paper-test harnesses (hcfrevival/core) against the RevivalPaper jar.

Usage:
    python3 scripts/revival/core-contract.py --core <core checkout>

Build first:
    ./gradlew createPaperclipJar :leaf-api:jar          (here)
    ./mvnw -pl classes,essentials,events,factions,arena -am package -DskipTests   (in core)
    python3 scripts/revival/live-tests.py               (fills the server runtime cache)

Core's harnesses are written for Paper 26.2: they compile against paper-api 26.2 and launch run/cache's Paper
jar from runtimes under run/<environment>. This script copies each harness into a temporary folder and points
it at RevivalPaper instead: the leaf-api jar, the Paperclip jar, and the runtime that live-tests.py unpacked.
It also points it at a copy of core's environments/ in which the core combat features that RevivalPaper
replaces are switched off, as core's dev.sh does on RevivalPaper (core PR #359). Nothing in the core checkout
is modified.
"""

import argparse
import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
RUNTIME = ROOT / "leaf-server/build/revival-live"
HARNESSES = [
    "essentials/src/paper-test/run.py",
    "classes/src/paper-test/run.py",
    "classes/src/paper-test/run_rogue.py",
    "events/src/paper-test/run.py",
]
# Core features RevivalPaper replaces; core will switch these off itself once it detects the fork (core PR C1)
CORE_COMBAT = ["combat.knockback", "combat.sword-profile", "combat.no-damage-ticks.projectile", "items.attack-speed"]


def disable_core_combat(environments: pathlib.Path) -> None:
    """Sets `enabled: false` under each CORE_COMBAT key; mechanics.yml is plain block YAML with 2-space indents."""
    for mechanics in environments.glob("*/plugins/*/mechanics.yml"):
        lines = mechanics.read_text().splitlines()
        path = []
        for index, line in enumerate(lines):
            stripped = line.lstrip()
            if not stripped or stripped.startswith(("#", "-")) or ":" not in stripped:
                continue
            depth = (len(line) - len(stripped)) // 2
            key = stripped.split(":", 1)[0].strip()
            path = path[:depth] + [key]
            if key == "enabled" and ".".join(path[:-1]) in CORE_COMBAT:
                lines[index] = line[:len(line) - len(stripped)] + "enabled: false"
        mechanics.write_text("\n".join(lines) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--core", type=pathlib.Path, required=True, help="a built hcfrevival/core checkout")
    core = parser.parse_args().core.resolve()

    launchers = sorted((ROOT / "leaf-server/build/libs").glob("leaf-paperclip-*.jar"))
    apis = sorted((ROOT / "leaf-api/build/libs").glob("leaf-api-*.jar"))
    server_jar = next(iter(sorted((RUNTIME / "versions").glob("*/leaf-*.jar"))), None)
    mojang_jar = next(iter(sorted((RUNTIME / "cache").glob("mojang_*.jar"))), None)
    if not launchers or not apis or server_jar is None or mojang_jar is None:
        print("Build the Paperclip and leaf-api jars and run live-tests.py first (see --help)", file=sys.stderr)
        return 1

    work = pathlib.Path(tempfile.mkdtemp(prefix="revival-core-contract-"))
    environments = work / "environments"
    shutil.copytree(core / "environments", environments)
    disable_core_combat(environments)
    # Core's harnesses expect a runtime per environment under run/, plus run/cache/<launcher>
    run = work / "run"
    for environment in ("factions", "arena"):
        runtime = run / environment
        runtime.mkdir(parents=True)
        for shared in ("libraries", "versions", "cache"):
            (runtime / shared).symlink_to(RUNTIME / shared, target_is_directory=True)
    (run / "cache").mkdir()
    launcher = run / "cache" / launchers[-1].name
    shutil.copy(launchers[-1], launcher)

    version = server_jar.parent.name
    replacements = {
        "pathlib.Path(__file__).resolve().parents[3]": f"pathlib.Path({str(core)!r})",
        'pathlib.Path.home() / ".m2/repository/io/papermc/paper/paper-api/26.2.build.115-stable/paper-api-26.2.build.115-stable.jar"': f"pathlib.Path({str(apis[-1])!r})",
        'ROOT / "run/cache/paper-26.2-115.jar"': f"pathlib.Path({str(launcher)!r})",
        'ROOT / "run/': f'pathlib.Path({str(run)!r}) / "',
        'ROOT / "run"': f"pathlib.Path({str(run)!r})",
        'ROOT / f"environments/': f'pathlib.Path({str(work)!r}) / f"environments/',
        '"versions/26.2", "paper-26.2.jar"': f'"versions/{version}", {server_jar.name!r}',
        "versions/26.2/paper-26.2.jar": f"versions/{version}/{server_jar.name}",
        "mojang_26.2.jar": mojang_jar.name,
    }

    failures = []
    for harness in HARNESSES:
        source = (core / harness).read_text()
        for old, new in replacements.items():
            source = source.replace(old, new)
        script = work / harness.replace("/", "_")
        script.write_text(source)
        print(f"== {harness}", flush=True)
        if subprocess.run([sys.executable, str(script)], cwd=work).returncode != 0:
            failures.append(harness)

    print(f"Work folder: {work}")
    if failures:
        print("Core contract failures: " + ", ".join(failures))
        return 1
    print("Core's harnesses pass on RevivalPaper")
    return 0


if __name__ == "__main__":
    sys.exit(main())
