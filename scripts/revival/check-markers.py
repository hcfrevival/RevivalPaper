#!/usr/bin/env python3
"""Checks that every RevivalPaper hook is present in the applied sources.

Run after `./gradlew applyAllPatches`. A patch that no longer applies already fails the build; this
catches the quieter failure, where a hunk is dropped while resolving a conflict and the regenerated
patch simply no longer contains it. It also checks that every `// Revival start` has a matching
`// Revival end`, and that no Revival marker exists without a line in markers.txt.
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
MARKERS = pathlib.Path(__file__).with_name("markers.txt")
APPLIED_ROOTS = ["leaf-server/src/minecraft/java", "paper-server/src", "paper-api/src"]
MARKER = re.compile(r"//\s*Revival( start| end)?\s*-")


def main() -> int:
    errors = []
    expected = {}
    for number, line in enumerate(MARKERS.read_text(encoding="utf-8").splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        path, sep, text = line.partition(" | ")
        if not sep or not text.strip():
            errors.append(f"markers.txt:{number}: expected '<file> | <text>'")
            continue
        expected.setdefault(path.strip(), []).append(text.strip())

    for path, texts in sorted(expected.items()):
        file = ROOT / path
        if not file.is_file():
            errors.append(f"{path}: file is missing (were the patches applied?)")
            continue
        source = file.read_text(encoding="utf-8")
        for text in texts:
            if text not in source:
                errors.append(f"{path}: missing hook '{text}'")

    for applied_root in APPLIED_ROOTS:
        base = ROOT / applied_root
        if not base.is_dir():
            errors.append(f"{applied_root}: directory is missing (were the patches applied?)")
            continue
        for file in base.rglob("*.java"):
            source = file.read_text(encoding="utf-8", errors="replace")
            if "Revival" not in source:
                continue
            relative = file.relative_to(ROOT).as_posix()
            starts = len(re.findall(r"//\s*Revival start\s*-", source))
            ends = len(re.findall(r"//\s*Revival end\b", source))
            if starts != ends:
                errors.append(f"{relative}: {starts} '// Revival start' but {ends} '// Revival end'")
            listed = expected.get(relative, [])
            for number, line in enumerate(source.splitlines(), 1):
                if MARKER.search(line) and "Revival end" not in line and not any(text in line for text in listed):
                    errors.append(f"{relative}:{number}: Revival marker is not listed in scripts/revival/markers.txt: {line.strip()}")

    for error in errors:
        print(f"error: {error}", file=sys.stderr)
    if errors:
        print(f"{len(errors)} problem(s) with RevivalPaper hooks", file=sys.stderr)
        return 1
    print(f"All {sum(len(texts) for texts in expected.values())} RevivalPaper hooks are present")
    return 0


if __name__ == "__main__":
    sys.exit(main())
