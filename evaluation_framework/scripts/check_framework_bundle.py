#!/usr/bin/env python3
"""Verify that a built framework distribution ships every module the pipeline configs ask for.

The framework discovers modules by scanning the directory `modules/` *relative to its working
directory* and keying each jar on its `Main-Class` manifest attribute
(ProcedureArgumentsDeserializer.peekAllModules / addJar in the akiba_framework sources).  A
distribution that is missing a module therefore starts perfectly well and only fails later, once per
stage, with

    ClassNotFoundException: Module not found: org.iotsplab.akiba.process.<Name>

which names neither the jar nor the build step that dropped it.  This turns that into a build-time
error, and writes a plain-text manifest of the shipped jar file names so the container entrypoint can
compare an already existing akiba_home volume against the image (Docker seeds a named volume from the
image only when the volume is empty, so a volume left over from an earlier build shadows the image).

Usage:
  check_framework_bundle.py --framework <dir> --pipelines <dir> [--manifest <file>]
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import zipfile

MAIN_CLASS = re.compile(rb"^Main-Class:[ \t]*([^\s\r\n]+)", re.M)
MAIN_CLASS_JSON = re.compile(r'"mainClassName"\s*:\s*"([^"]+)"')


def jar_main_class(jar: pathlib.Path) -> str | None:
    """The jar's `Main-Class`, or None when it has none / is not a readable zip."""
    try:
        with zipfile.ZipFile(jar) as z:
            manifest = z.read("META-INF/MANIFEST.MF")
    except (zipfile.BadZipFile, KeyError, OSError):
        return None
    m = MAIN_CLASS.search(manifest)
    return m.group(1).decode() if m else None


def pipeline_module_classes(pipelines: pathlib.Path) -> dict[str, list[str]]:
    """{main class: [configs that ask for it]} over every pipeline config."""
    wanted: dict[str, list[str]] = {}
    for cfg in sorted(pipelines.glob("*.json")):
        try:
            raw = cfg.read_text()
        except OSError as e:
            print(f"   ! {cfg.name}: cannot read ({e})")
            continue
        # search the raw text: tasks are nested, and a missing module may be referenced anywhere
        for m in MAIN_CLASS_JSON.finditer(raw):
            wanted.setdefault(m.group(1), []).append(cfg.name)
        json.loads(raw)  # syntax check only; a broken config is worth reporting here
    return wanted


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Check that a framework distribution ships every module the pipeline configs use.")
    ap.add_argument("--framework", required=True, help="built framework dir (contains modules/)")
    ap.add_argument("--pipelines", required=True, help="pipeline config dir (*.json)")
    ap.add_argument("--manifest", help="write the shipped jar file names here, one per line")
    a = ap.parse_args()

    modules = pathlib.Path(a.framework) / "modules"
    jars = sorted(modules.glob("*.jar"))
    if not jars:
        print(f"== no module jars in {modules} — the module build produced nothing")
        return 1

    have: dict[str, str] = {}
    names: list[str] = []
    without_main_class: list[str] = []
    for jar in jars:
        names.append(jar.name)
        main_class = jar_main_class(jar)
        if main_class is None:
            without_main_class.append(jar.name)
        else:
            have[main_class] = jar.name
    print(f"== {len(jars)} module jar(s), {len(have)} usable (Main-Class present)")
    if without_main_class:
        # peekAllModules warns about exactly these and skips them — a jar that lost its manifest
        # attribute is silently ignored by the framework, so list a few instead of all of them
        head = ", ".join(without_main_class[:4])
        more = f", … (+{len(without_main_class) - 4} more)" if len(without_main_class) > 4 else ""
        print(f"== WARNING {len(without_main_class)} jar(s) without Main-Class are skipped by the "
              f"framework: {head}{more}")

    wanted = pipeline_module_classes(pathlib.Path(a.pipelines))
    configs = sorted({c for cs in wanted.values() for c in cs})
    print(f"== {len(wanted)} module class(es) referenced by {len(configs)} pipeline config(s)")

    missing = {c: cs for c, cs in wanted.items() if c not in have}
    for c in sorted(missing):
        print(f"   MISSING {c}   (needed by {', '.join(sorted(missing[c]))})")

    if a.manifest:
        pathlib.Path(a.manifest).write_text("\n".join(sorted(names)) + "\n")
        print(f"== manifest written: {a.manifest} ({len(names)} names)")

    if missing:
        print("== FAILED: this distribution cannot run the pipeline — those stages would fail with")
        print("   'ClassNotFoundException: Module not found: <class>' at startup-looking time")
        return 1
    print("== ok: every module class the pipeline configs use is present")
    return 0


if __name__ == "__main__":
    sys.exit(main())
