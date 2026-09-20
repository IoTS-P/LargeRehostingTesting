#!/usr/bin/env python3
"""Build every akiba module JAR from source, in dependency order.

Why this exists: `akiba_modules/build.gradle.kts` resolves each module's inter-module
dependencies to *files* (`build/libs/amod-<name>-<version>.jar`) while it creates the
module's Jar task — it even reads the dependency JARs with `JarInputStream` to record
`META-INF/module-deps`.  In a clean tree those JARs do not exist yet, so
`moduleJar-ALL` (and any task whose module has dependencies) fails with
"Could not create task …: No such file or directory" before compiling anything.

Pre-seeding the JARs from another build does not work either: the reference build was
made with a newer Kotlin, and its JARs carry Kotlin metadata 2.3.0, which the
2.1.20 compiler declared by this project's sources refuses ("binary version of its
metadata is 2.3.0, expected version is 2.1.0").

So: parse the module list and the dependency graph out of build.gradle.kts, build the
modules in topological *batches* (one Gradle invocation per batch, all modules of the
batch in parallel), and verify after each batch that the expected JARs appeared.

Usage (inside the akiba_modules project directory, i.e. where ./gradlew lives):
    build_akiba_modules.py [--jobs N] [--dry-run] [--modules A,B,C]
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

GRADLE_TASK = ":akiba_modules:moduleJar-"


def parse_build_file(build_file: Path):
    """Return (ordered module list, dependency map, modules the build script itself excludes)."""
    text = build_file.read_text(encoding="utf-8", errors="replace")

    modules = re.findall(
        r'ModuleMetadata\(\s*moduleName\s*=\s*"([^"]+)".*?version\s*=\s*"([^"]+)"',
        text,
        flags=re.S,
    )
    if not modules:
        sys.exit(f"could not parse any ModuleMetadata out of {build_file}")

    deps: dict[str, list[str]] = {}
    for owner, dep_list in re.findall(
        r'\(lc\["([^"]+)"\]!!\)\(moduleDependency\(listOf\(([^)]*)\)\)\)', text
    ):
        deps[owner] = re.findall(r'"([^"]+)"', dep_list)

    # the build script registers no moduleJar-<name> task for these, so asking Gradle
    # for them fails with "task not found"
    excluded: dict[str, str] = {}
    for var, why in (("underDevelopmentModules", "under development"),
                     ("deprecatedModules", "deprecated")):
        m = re.search(rf"val {var}(?::\s*List<String>)?\s*=\s*listOf\(([^)]*)\)", text, flags=re.S)
        for name in re.findall(r'"([^"]+)"', m.group(1)) if m else []:
            excluded[name] = why

    known = {name for name, _ in modules}
    for owner, dl in deps.items():
        unknown = [d for d in dl if d not in known]
        if unknown:
            sys.exit(f"module {owner} depends on unknown module(s): {unknown}")
    return modules, deps, excluded


def batches(modules, deps):
    """Group modules so that every batch only depends on previously built batches."""
    order = [name for name, _ in modules]
    done: set[str] = set()
    remaining = list(order)
    out = []
    while remaining:
        ready = [m for m in remaining if all(d in done for d in deps.get(m, []))]
        if not ready:
            sys.exit(f"dependency cycle among modules: {remaining}")
        out.append(ready)
        done.update(ready)
        remaining = [m for m in remaining if m not in done]
    return out


def run(cmd, cwd, log):
    print(f"    $ {' '.join(cmd)}", flush=True)
    with open(log, "ab") as fh:
        proc = subprocess.run(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        fh.write(b"".join(proc.stdout.splitlines(keepends=True)))
    tail = proc.stdout.decode("utf-8", "replace").splitlines()[-25:]
    for line in tail:
        print("      " + line)
    return proc.returncode


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--project", default=".", help="akiba_modules project dir (contains build.gradle.kts and gradlew's parent)")
    ap.add_argument("--gradle-root", default=None, help="directory that holds ./gradlew (defaults to --project)")
    ap.add_argument("--jobs", default="8", help="Gradle --max-workers")
    ap.add_argument("--log", default="/tmp/akiba_modules_build.log")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--gradle", default="./gradlew",
                    help="gradle launcher (the image installs /opt/gradle-8.8/bin/gradle because the wrapper's distribution download is unreliable here)")
    ap.add_argument("--modules", default=None, help="comma separated subset to build")
    ap.add_argument("--optional", default="P2IMGateway,P2IMRunner",
                    help="modules whose failure is only a warning (not needed by the pipeline stages)")
    args = ap.parse_args()

    project = Path(args.project).resolve()
    root = Path(args.gradle_root).resolve() if args.gradle_root else project
    build_file = project / "build.gradle.kts"
    modules, deps, excluded = parse_build_file(build_file)
    versions = dict(modules)
    optional = {m for m in args.optional.split(",") if m}
    keep = set(args.modules.split(",")) if args.modules else None

    todo = [n for n, _ in modules if keep is None or n in keep]
    # transitive closure so that dependencies of the selection are built too
    if keep is not None:
        frontier = list(todo)
        while frontier:
            for d in deps.get(frontier.pop(), []):
                if d not in todo:
                    todo.append(d)
                    frontier.append(d)

    for name, why in excluded.items():
        if name in todo:
            print(f"   skip {name}: {why} (no moduleJar task is registered for it)")
    todo = [n for n in todo if n not in excluded]

    plan = [[m for m in batch if m in todo] for batch in batches(modules, deps)]
    plan = [b for b in plan if b]

    print(f"== {len(todo)} module(s) in {len(plan)} batch(es), max-workers={args.jobs}")
    for i, batch in enumerate(plan, 1):
        print(f"   batch {i}: {' '.join(batch)}")
    if args.dry_run:
        return 0

    libs = project / "build/libs"
    libs.mkdir(parents=True, exist_ok=True)
    failed: list[str] = []

    for i, batch in enumerate(plan, 1):
        print(f"== batch {i}/{len(plan)}: {len(batch)} module(s)", flush=True)
        cmd = [args.gradle, "--no-daemon", "--console=plain", f"--max-workers={args.jobs}"]
        cmd += [GRADLE_TASK + name for name in batch]
        rc = run(cmd, root, args.log)
        missing = [n for n in batch if not (libs / f"amod-{n}-{versions[n]}.jar").exists()]
        if rc != 0 and missing:
            # retry the batch one module at a time — a single bad module should not
            # hide the modules that build fine
            print(f"   batch failed (rc={rc}), retrying module by module", flush=True)
            for name in missing:
                rc1 = run([args.gradle, "--no-daemon", "--console=plain",
                           f"--max-workers={args.jobs}", GRADLE_TASK + name], root, args.log)
                if rc1 != 0 or not (libs / f"amod-{name}-{versions[name]}.jar").exists():
                    if name in optional:
                        print(f"   WARN optional module {name} did not build "
                              f"(not needed by the pipeline stages)")
                    else:
                        failed.append(name)
        for name in batch:
            jar = libs / f"amod-{name}-{versions[name]}.jar"
            print(f"   {'ok  ' if jar.exists() else 'MISS'} amod-{name}-{versions[name]}.jar")

    built = sorted(p.name for p in libs.glob("amod-*.jar"))
    print(f"== {len(built)} module JAR(s) in {libs}")
    if failed:
        print(f"== FAILED modules: {', '.join(failed)}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
