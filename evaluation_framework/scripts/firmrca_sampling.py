#!/usr/bin/env python3
"""FirmRCA sampling pass — standalone replacement for stages 06/06b at the 0.1 threshold.

The stock FirmRCA module (framework/subprojects/akiba_modules/src/FirmRCA) was written to lift the
**cov > 0.2** crashes out of the replay set and run FirmRCA on those only; the paper's 0.1 selection is
an order of magnitude larger than that path was built for, so the artifact performs the same two steps
here, directly and outside the module:

  1. select one crash input per (firmware id, pc, lr) with ``basic_block_cov >= --threshold``
  2. per input, inside the container:
        generateDataset.py  (fuzzware_harness trace -> instlist.reverse + memac.bin)
        reversenolog        (FirmRCA) -> "Current Instruction at N with score S is 0xADDR : ..." lines

Inputs are read from the pipeline's own export ``evaluation_results/db/firmxray_fuzzware_replay_crashes.csv``
(so no database or credentials are needed); results are written to
``evaluation_results/db/firmrca_sampling.csv`` in the shape of the reference server's
``firmxray_on_gdma_firmrca_results`` rows (input_id_map / firmrca_results).

Usage (from the repository root, host side; drives the container with docker exec):

    python3 evaluation_framework/scripts/firmrca_sampling.py --ids 29 --max-inputs 1 --dry-run
    python3 evaluation_framework/scripts/firmrca_sampling.py --threshold 0.1 --max-inputs 20
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import re
import shlex
import subprocess
import sys
import time
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DEFAULT_CRASHES = REPO / "evaluation_results/db/firmxray_fuzzware_replay_crashes.csv"
DEFAULT_OUT = REPO / "evaluation_results/db/firmrca_sampling.csv"
DEFAULT_FIRMXRAY = REPO / "evaluation_results/db/firmxray_results.csv"
DEFAULT_BINARIES = REPO / "evaluation_results/db/binaries.csv"
JAR = REPO / "evaluation_framework/framework/prebuilt-modules/amod-FirmRCA-1.0.jar"

# Paths inside the container.
PROJECTS = "/data/akiba/binaries/fuzzware_projects"   # per-firmware fuzzware project (pipeline/ inside)
FIRMWARE_IN = "/data/akiba/binaries"                  # imported firmware images
FRCA_ROOT = "/data/tools/FirmRCA"
# The dataset step needs FirmRCA's *own* fuzzware harness fork (it emits `instlist` and accepts
# --trace-out/--state-out; the upstream/GDMA harnesses do not), so both steps run in the
# FirmRCA venv — the same one the stock module activates via firmRCAPythonVenvRoot.
VENV = "/data/tools/FirmRCA/FirmRCA-fuzzware"

SCORE_RE = re.compile(r"^Current Instruction at [0-9]+ with score ([0-9]+) is 0x([0-9a-f]+) :")


def sh(cmd: list[str], timeout: int = 3600) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def dexec(container: str, script: str, timeout: int = 3600) -> subprocess.CompletedProcess:
    return sh(["docker", "exec", container, "bash", "-lc", script], timeout=timeout)


def load_inputs(args) -> list[dict]:
    rows: list[dict] = []
    with DEFAULT_CRASHES.open(newline="") as fh:
        for row in csv.DictReader(fh):
            cov = row.get("basic_block_cov") or ""
            if not cov:
                continue
            try:
                if float(cov) < args.threshold:
                    continue
            except ValueError:
                continue
            if args.ids and row["id"] not in args.ids:
                continue
            rows.append({
                "id": row["id"],
                "path": (row.get("path") or "").strip('"'),
                "pc": row.get("pc", ""),
                "lr": row.get("lr", ""),
                "cov": float(cov),
            })
    # one input per (id, pc, lr), highest coverage first
    rows.sort(key=lambda r: -r["cov"])
    seen: set[tuple[str, str, str]] = set()
    uniq: list[dict] = []
    for r in rows:
        key = (r["id"], r["pc"], r["lr"])
        if key in seen:
            continue
        seen.add(key)
        uniq.append(r)
    return uniq[: args.max_inputs] if args.max_inputs else uniq


def base_addresses() -> dict[str, str]:
    out: dict[str, str] = {}
    if DEFAULT_FIRMXRAY.exists():
        with DEFAULT_FIRMXRAY.open(newline="") as fh:
            for row in csv.DictReader(fh):
                if row.get("base_address"):
                    out[row["id"]] = row["base_address"]
    return out


def prepare(container: str, outdir: Path) -> None:
    """Extract the module's own generateDataset.py from the shipped jar (host side)."""
    outdir.mkdir(parents=True, exist_ok=True)
    target = outdir / "generateDataset.py"
    if target.exists():
        return
    with zipfile.ZipFile(JAR) as zf:
        target.write_bytes(zf.read("generateDataset.py"))


def binaries_index() -> dict[str, str]:
    """id -> the path the firmware was imported from (its basename names the project copy)."""
    out: dict[str, str] = {}
    if DEFAULT_BINARIES.exists():
        with DEFAULT_BINARIES.open(newline="") as fh:
            for row in csv.DictReader(fh):
                out[row["id"]] = row.get("original_path", "")
    return out


def firmware_path(container: str, fw_id: str, original_path: str) -> str | None:
    """The image held by the firmware's fuzzware project (what the module hands to reversenolog)."""
    name = os.path.basename(original_path or "")
    if name:
        cand = f"{PROJECTS}/{fw_id}/{name}"
        if dexec(container, f"test -f {shlex.quote(cand)} && echo ok").stdout.strip() == "ok":
            return cand
    cp = dexec(container, f"ls -p {PROJECTS}/{shlex.quote(fw_id)} 2>/dev/null | grep -v / | head -1")
    found = cp.stdout.strip()
    return f"{PROJECTS}/{fw_id}/{found}" if found else None


def config_for(container: str, fw_id: str, crash_path: str) -> str | None:
    """Crash paths are project-relative: <group>/fuzzers/... -> <project>/pipeline/<group>/config.yml."""
    group = crash_path.split("/", 1)[0]
    cand = f"{PROJECTS}/{fw_id}/pipeline/{group}/config.yml"
    cp = dexec(container, f"test -f {cand} && echo ok")
    return cand if cp.stdout.strip() == "ok" else None


def run_one(container: str, args, row: dict, base: str, original_path: str) -> dict:
    fw_id, crash_rel = row["id"], row["path"]
    stage = f"{PROJECTS}/{fw_id}/pipeline"
    workdir = f"{PROJECTS}/{fw_id}/firmrca_sampling/{row['pc']}_{row['lr']}_{abs(hash(crash_rel)) % 10000:04d}"
    input_path = f"{stage}/{crash_rel}"
    fw_path = firmware_path(container, fw_id, original_path)
    cfg = config_for(container, fw_id, crash_rel)
    result = {"id": fw_id, "path": crash_rel, "pc": row["pc"], "lr": row["lr"],
              "basic_block_cov": row["cov"], "base_address": base,
              "err_msg": "", "firmrca_results": "", "execute_time": 0.0}
    if not fw_path or not cfg:
        result["err_msg"] = f"input not resolvable (config={cfg}, firmware={fw_path})"
        return result
    if args.dry_run:
        result["err_msg"] = f"dry-run: {cfg} {input_path} 0x{int(base):x}"
        return result

    started = time.time()
    # NB: generateDataset.py shells out to `fuzzware_harness`, so the venv must be *activated* —
    # otherwise PATH resolves /usr/local/bin/fuzzware_harness, whose shebang points at the GDMA venv,
    # which rejects --trace-out/--state-out and silently produces no instlist.
    gen = dexec(container, f"rm -rf {workdir}; mkdir -p {workdir}; cp {args.gen_py_in_container} {workdir}/generateDataset.py; "
                           f"cd {workdir} && source {args.venv}/bin/activate && "
                           f"python generateDataset.py {workdir} {cfg} {input_path} 2>&1 | tail -30",
                timeout=args.dataset_timeout)
    instlist = f"{workdir}/instlist.reverse"
    cp = dexec(container, f"wc -l < {instlist} 2>/dev/null || echo 0")
    try:
        lines = int(cp.stdout.strip() or 0)
    except ValueError:
        lines = 0
    if lines == 0:
        result["err_msg"] = "dataset generation failed: " + gen.stdout.strip()[-300:]
        return result

    cmd = (f"source {args.venv}/bin/activate && export LD_LIBRARY_PATH={args.firmrca_root}/src/lib && "
           f"timeout {args.timeout} {args.firmrca_root}/src/src/reversenolog "
           f"{workdir}/state-out.txt {fw_path} {instlist} {workdir}/memac.bin "
           f"0x{int(base):x} {lines} {lines} 2>&1")
    frca = dexec(container, cmd, timeout=args.timeout + 120)
    scores, addrs, finished = [], [], False
    for line in frca.stdout.splitlines():
        if line.startswith("Finish reverse execution"):
            finished = True
        m = SCORE_RE.match(line)
        if m:
            scores.append(int(m.group(1)))
            addrs.append(int(m.group(2), 16))
    result["execute_time"] = round(time.time() - started, 2)
    if not scores:
        result["err_msg"] = "analysis failed" if finished else "timeout"
        result["firmrca_results"] = json.dumps({"raw_tail": frca.stdout.strip()[-300:]})
        return result
    result["firmrca_results"] = json.dumps({
        "1": {"errMsg": None, "failed": False, "scores": scores, "results": addrs}})
    return result


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--container", default=os.environ.get("AKIBA_CONTAINER", "largerehosting_akiba"))
    ap.add_argument("--threshold", type=float, default=0.1,
                    help="basic_block_cov cut-off for the sampling pass (default 0.1)")
    ap.add_argument("--ids", nargs="*", help="restrict to these firmware ids")
    ap.add_argument("--max-inputs", type=int, default=0, help="stop after N inputs (0 = all)")
    ap.add_argument("--timeout", type=int, default=600, help="per-input FirmRCA timeout (s)")
    ap.add_argument("--dataset-timeout", type=int, default=900, help="per-input dataset timeout (s)")
    ap.add_argument("--firmrca-root", default=FRCA_ROOT)
    ap.add_argument("--venv", default=VENV)
    ap.add_argument("--gen-py-in-container", default="/tmp/akiba_generateDataset.py")
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    ap.add_argument("--dry-run", action="store_true", help="resolve inputs and print the commands only")
    args = ap.parse_args()

    cache = REPO / ".hermes-cache"
    prepare(args.container, cache)
    cp = sh(["docker", "cp", str(cache / "generateDataset.py"), f"{args.container}:{args.gen_py_in_container}"])
    if cp.returncode != 0:
        print(f"failed to copy generateDataset.py into the container: {cp.stderr.strip()}", file=sys.stderr)
        return 2

    rows = load_inputs(args)
    bases = base_addresses()
    bins = binaries_index()
    print(f"selected {len(rows)} input(s) at cov >= {args.threshold}")
    results = []
    for i, row in enumerate(rows, 1):
        base = bases.get(row["id"])
        if not base:
            print(f"[{i}/{len(rows)}] id {row['id']}: no base_address, skipped")
            continue
        res = run_one(args.container, args, row, base, bins.get(row["id"], ""))
        results.append(res)
        status = res["err_msg"] or f"ok ({len(json.loads(res['firmrca_results'])['1']['scores'])} scores)"
        print(f"[{i}/{len(rows)}] id {row['id']} cov {row['cov']:.4f} -> {status}")
        if args.dry_run:
            continue
    if results:
        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        with out.open("w", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=list(results[0].keys()))
            w.writeheader()
            w.writerows(results)
        print(f"wrote {len(results)} row(s) -> {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
