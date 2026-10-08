#!/usr/bin/env python3
"""Repair stale submodule gitdir / core.worktree pointers after a repo tree moved.

A git submodule checked out with `git submodule add` keeps its real git directory
inside the superproject -- for a submodule at `<path>` that is
`<repo>/.git/modules/<path>`, and for a nested submodule additionally
`.../modules/<name>` below its parent's git directory.  The worktree holds a
one-line `.git` file pointing at that git directory, and the git directory's
config holds a `core.worktree` pointing back.  Both are relative paths, so
copying or renaming the enclosing tree leaves them dangling: every git command
that walks into the submodules fails with "fatal: not a git repository: ..." or
"fatal: cannot chdir to ...".

This script recomputes both pointers for every submodule of `repo` (nested
submodules at any depth) from where they actually live now.

Usage:
    ./_fix_submodule_gitdirs.py [repo]            # default: repo containing this script
    ./_fix_submodule_gitdirs.py [repo] --check    # report only, change nothing
"""
from __future__ import annotations

import os
import pathlib
import subprocess
import sys


def find_repo(start: pathlib.Path) -> pathlib.Path:
    p = start.resolve()
    for cand in [p, *p.parents]:
        if (cand / ".git").exists():
            return cand
    raise SystemExit(f"no git repository at or above {start}")


def gitdir_for(modules: pathlib.Path, rel: pathlib.PurePath) -> pathlib.Path | None:
    """Locate the git directory of the submodule checked out at `rel`."""
    parts = rel.parts
    for k in range(len(parts), 0, -1):
        head = modules.joinpath(*parts[:k])
        if not head.is_dir():
            continue
        gd = head
        for name in parts[k:]:
            gd = gd / "modules" / name
        if gd.is_dir():
            return gd
    return None


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    check = "--check" in sys.argv
    repo = find_repo(pathlib.Path(args[0]) if args else pathlib.Path(__file__).parent)
    modules = repo / ".git" / "modules"
    if not modules.is_dir():
        print(f"{repo}: no .git/modules -- nothing to repair")
        return 0

    changed = 0
    for gitfile in sorted(repo.rglob(".git")):
        if gitfile.is_dir():
            continue                                  # the superproject itself
        worktree = gitfile.parent
        try:
            rel = worktree.relative_to(repo)
        except ValueError:
            continue
        gitdir = gitdir_for(modules, rel)
        if gitdir is None:
            # the heuristic can miss deeply nested layouts -- ask git itself
            probe = subprocess.run(["git", "-C", str(worktree), "rev-parse", "--absolute-git-dir"],
                                   capture_output=True, text=True)
            if probe.returncode != 0:
                print(f"  {rel}: no git directory found -- dangling pointer, needs "
                      f"`git submodule update --init` in its parent")
            continue

        want = os.path.relpath(gitdir, worktree)
        text = gitfile.read_text()
        current = text.split("gitdir:", 1)[1].strip() if "gitdir:" in text else text.strip()
        if current != want:
            print(f"  {rel}: .git {current} -> {want}")
            if not check:
                gitfile.write_text(f"gitdir: {want}\n")
            changed += 1

        cfg = gitdir / "config"
        got_wt = subprocess.run(["git", "config", "--file", str(cfg), "--get", "core.worktree"],
                                capture_output=True, text=True).stdout.strip()
        if got_wt:                                    # unset means git derives it correctly
            want_wt = os.path.relpath(worktree, gitdir)
            if got_wt != want_wt:
                print(f"  {rel}: core.worktree {got_wt} -> {want_wt}")
                if not check:
                    subprocess.run(["git", "config", "--file", str(cfg), "core.worktree", want_wt],
                                   check=True)
                changed += 1

    print(f"{'would fix' if check else 'fixed'} {changed} pointer(s) under {repo}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
