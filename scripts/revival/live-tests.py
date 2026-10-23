#!/usr/bin/env python3
"""Runs RevivalPaper's live-server tests on throwaway servers, once per entity-tracker path.

Build first:
    ./gradlew createPaperclipJar :leaf-server:revivalLiveTestPlugin

Each run gets a fresh temporary server folder (offline mode, flat world, accepted EULA) with the
RevivalLiveTests plugin and a random nonce. The plugin drives two fake players through the real packet
handlers and logs REVIVAL_LIVE_PASS / REVIVAL_LIVE_FAIL lines carrying that nonce; this script fails unless
REVIVAL_LIVE_DONE <nonce> appears and no failure does. The Mojang server jar and libraries Paperclip
unpacks are cached in leaf-server/build/revival-live/ so later runs start offline.

REVIVAL_LIVE_RULES=<rule set> runs both cheat suites on that rule set (classic when unset; classic-capped is the
NAS world's). REVIVAL_LIVE_ONLY=<token>,<token> keeps only the steps whose names hold one of them, and the cheat suite's
own "cheat suite:" steps (for "cheat suite 2", name it).
"""

import os
import pathlib
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
LIBS = ROOT / "leaf-server/build/libs"
CACHE = ROOT / "leaf-server/build/revival-live"
TIMEOUT = 1800  # the sync run carries the auth scenarios and both cheat suites (about 17 min on a laptop, 2026-10-22)
TRACKERS = {"sync tracker": False, "async tracker": True}


def free_port() -> int:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def run(name: str, async_tracker: bool, server_jar: pathlib.Path, plugin: pathlib.Path) -> bool:
    server = pathlib.Path(tempfile.mkdtemp(prefix="revival-live-"))
    for shared in ("cache", "libraries", "versions"):
        (CACHE / shared).mkdir(parents=True, exist_ok=True)
        (server / shared).symlink_to(CACHE / shared, target_is_directory=True)
    (server / "plugins").mkdir()
    shutil.copy(plugin, server / "plugins" / plugin.name)
    (server / "config").mkdir()
    (server / "config/leaf-global.yml").write_text(
        f"async:\n  async-entity-tracker:\n    enabled: {'true' if async_tracker else 'false'}\n")
    # Authoritative-movement playtest instruments: the HUD and the per-correction log run on every live test
    (server / "revival.yml").write_text("authoritative:\n  debug:\n    hud: true\n    log: true\n")
    (server / "eula.txt").write_text("eula=true\n")
    (server / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={free_port()}\nonline-mode=false\n"
        "level-type=minecraft:flat\ngenerator-settings={\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},"
        "{\"block\":\"minecraft:stone\",\"height\":3}],\"biome\":\"minecraft:plains\"}\n"
        "generate-structures=false\nview-distance=2\nsimulation-distance=2\n"
        "spawn-protection=0\nspawn-monsters=false\nspawn-animals=false\nmax-players=4\npvp=true\n"
        "enable-query=false\nenable-rcon=false\n")

    nonce = secrets.token_hex(8)
    log = server / "live-tests.log"
    print(f"[{name}] running live tests in {server}", flush=True)
    with log.open("w") as stream:
        try:
            subprocess.run(
                ["java", f"-Drevival.livetest.nonce={nonce}", f"-Drevival.livetest.rules={os.environ.get('REVIVAL_LIVE_RULES', '')}",
                 f"-Drevival.livetest.only={os.environ.get('REVIVAL_LIVE_ONLY', '')}",
                 "-Dpaper.disableMigrationDelay=true",
                 "-Dterminal.jline=false", "-Dterminal.ansi=false", "-Xms512M", "-Xmx2G",
                 "-jar", str(server_jar), "--nogui"],
                cwd=server, stdin=subprocess.DEVNULL, stdout=stream, stderr=subprocess.STDOUT,
                timeout=TIMEOUT, check=False)
        except subprocess.TimeoutExpired:
            print(f"[{name}] server did not finish within {TIMEOUT}s")

    output = log.read_text(errors="replace")
    (CACHE / "logs").mkdir(parents=True, exist_ok=True)
    shutil.copy(log, CACHE / "logs" / f"{name.replace(' ', '-')}.log")  # for CI to upload
    for line in output.splitlines():
        if f"REVIVAL_LIVE_PASS {nonce}" in line:
            print(f"[{name}] PASS {line.split(nonce, 1)[1].strip()}")
    failed = f"REVIVAL_LIVE_FAIL {nonce}" in output
    done = f"REVIVAL_LIVE_DONE {nonce}" in output
    if failed or not done:
        print(output[-15000:])
        print(f"[{name}] FAILED. Log: {log}")
        return False
    print(f"[{name}] all live tests passed. Log: {log}")
    return True


def main() -> int:
    jars = sorted(LIBS.glob("leaf-paperclip-*.jar"))
    plugin = LIBS / "revival-live-tests.jar"
    if not jars or not plugin.is_file():
        print("Build first: ./gradlew createPaperclipJar :leaf-server:revivalLiveTestPlugin", file=sys.stderr)
        return 1
    results = [run(name, async_tracker, jars[-1], plugin) for name, async_tracker in TRACKERS.items()]
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
