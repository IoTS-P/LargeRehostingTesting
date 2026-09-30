# uEmu firmware fuzzing pipeline

Batch knowledge-base extraction and fuzzing.  One **batch** is processed at a time:

```
firmware/<batch>/<fw>/  ──►  result/<batch>/kb/<fw>/  ──►  result/<batch>/fuzz/<fw>/
```

## Dependencies

```bash
pip install pyyaml
export uEmuDIR=/path/to/uEmu          # required
```

`uEmuDIR` must contain `uEmu-helper.py`, the `*-template.{sh,lua}` files, `library.lua`,
`calculate.py` and a built `build/`.

## Source changes

`uEmu-source-changes.patch` records the local changes to uEmu's sources — a **shared-memory key
offset** mechanism, so that several instances running concurrently do not overwrite each other's
shared-memory segments.

It touches three files: `AFL/afl-fuzz.c` and
`s2e/libs2eplugins/src/s2e/Plugins/Fuzzer/AFLFuzzer.{h,cpp}`.  Both sides read the offset from the
`AFL_KEY_OFFSET` environment variable, `key = BASE + (offset % 1000)`.

```bash
cd /path/to/uEmu
patch -p1 < /path/to/uEmu-test/uEmu-source-changes.patch

cd AFL && make && cd .. && make                  # a rebuild is required for it to take effect
strings AFL/afl-fuzz | grep AFL_KEY_OFFSET       # verify
```

> `afl_bin` in `pipeline.yaml` must point at uEmu's customised `afl-fuzz`, never at a system one.

## Directory layout

```
firmware/<batch>/<fw>/<fw>.elf        firmware image
firmware/<batch>/<fw>/<fw>.cfg        configuration (the `cfg` command generates it)
firmware/<batch>/valid_block_range/   bbl files (produced externally; used for coverage)

result/<batch>/
├── manifest.json       parameter snapshot
├── kb_status.csv       fw, kb_file, status, elapsed_s
├── fuzz_status.csv     fw, end_type, duration_min, note
├── results.csv         fw, result, details, duration_min, end_type, queue
├── coverage.csv        fw, total_bbl, covered, pct, note
├── logs/               evidence.jsonl, kb.log, fuzz.log, analyze.log
├── kb/<fw>/            knowledge-base artefacts
├── fuzz/<fw>/          fuzzing artefacts (AFL/, afl.log, fuzz_tb_map.txt, debug_summary.txt)
└── coverage/           intermediate -cov.csv files
```

## Quick start

```bash
export uEmuDIR=/path/to/uEmu

python3 pipeline.py init --batch batch1 --from fuzz-test-1   # migrate firmware (symlinks)
python3 pipeline.py cfg  --batch batch1                      # generate cfg
python3 pipeline.py kb   --batch batch1 --jobs 8             # knowledge-base extraction
python3 pipeline.py fuzz --batch batch1 --jobs 10 \
        --seed testcases/seed_alternating.bin                # fuzzing
python3 pipeline.py analyze  --batch batch1                  # classification
python3 pipeline.py coverage --batch batch1                  # coverage
```

To change the seed, change the batch name:

```bash
python3 pipeline.py fuzz --batch seed00 --seed testcases/seed_zeros.bin
```

## Commands

| Command | Description |
|---|---|
| `init --from <dir> --batch X` | migrate firmware and KBs from an old directory (symlinks; the original data is untouched) |
| `cfg --batch X [--elfs a,b] [--force]` | probe the ROM base/length from the ELF's LOAD segments and generate `<fw>.cfg` |
| `kb --batch X [--jobs N] [--timeout S] [--keep-raw]` | knowledge-base extraction → `result/X/kb/` |
| `fuzz --batch X --seed S [--jobs N] [--timeout S] [--keep-raw]` | fuzzing → `result/X/fuzz/` |
| `analyze --batch X [--elfs a,b]` | classification → `results.csv` + `logs/evidence.jsonl` |
| `coverage --batch X [--bbl-dir D]` | coverage → `coverage.csv` |
| `clean --batch X [--elfs a,b]` | post-hoc cleanup of the large raw files |

Common options: `--batch` (required), `--elfs 3389,3391` (process only the named firmware).

`--keep-raw`: by default each firmware extracts `*_KB.dat`/`fuzz_tb_map.txt`, writes
`debug_summary.txt` and then deletes `s2e-out-*/`/`debug.txt`/`uemu.log`; with this flag everything is
kept (a single `debug.txt` can reach several GB, so use it only when investigating).

## Result classification

The values below are the tool's own literals and are quoted exactly as the CSVs contain them, with an
English gloss in parentheses.

**`result`** — 3 classes

| result | criterion |
|---|---|
| 正常fuzz (fuzzed normally) | `queue > 1` |
| 提前退出 (exited early) | nothing produced, and `end_type = early` |
| 卡死 (stuck) | everything else (it used up the timeout) |

**`end_type`** — 3 values: `early` — exited on its own without using the budget / `clean` — ran the
full budget and finished normally / `killed` — ran the full budget, was unresponsive for 30 s and was
killed.

**`details`** — finer classification, prefixed by origin (`fuzzer:` = the AFL side, `uEmu:` = the
emulator side)

```
正常fuzz    正常fuzzing | (发现N个crash) | (仅AFL证据)

fuzzer:     dry-run种子crash | dry-run种子超时 | dry-run中止(其他原因) | AFL被信号终止(SIGKILL)

uEmu:崩溃    越界访问 | 超时 | HardFault | 写只读ROM | fuzz-state-killed
            无效内存访问 | QEMU段错误 | invalid-pc

uEmu:启动即退出 | uEmu:未进入learning(未见任何外设) | uEmu:初始化hang | uEmu:未完成KB提取

uEmu:未命中输入点            learning 完成但固件没读到 fuzzer 数据
            (+dead-loop拖住 / long-loop拖住 / hang拖住)

uEmu:命中输入点但           状态切换被拒 | 被dead-loop拖住 | 被long-loop拖住 | 被hang拖住 | 未产出
uEmu:learning↔fuzzing震荡(fuzzing零产出)    fuzzing 零产出（paths_found=0 且 NP≈execs）

其他        证据缺失(无debug_summary) | 未分类
```

> Process model: `learning completes → switch to fuzzing (AR) → hit the input point (FK) → AFL
> produces output (queue > 1)`; `details` is named after **the step at which it stopped**.

The evidence behind a classification is not in `results.csv` but in `logs/evidence.jsonl`:

```json
{"fw": "3", "detail": "fuzzer:dry-run种子crash",
 "evidence": "Test case 'id:000000,orig:seed_alternating.bin' results in a crash"}
```

## debug_summary.txt

The evidence digest (v2) written before `debug.txt` is cleaned up; it is the input to the
classification.

```
counts  KB / AR / FK / DL / LL / HANG / NP / KF / INV / KP / mode_switch
lines   kb_last_ln / ar_last_ln / fk_last_ln / dl_first_ln / dl_last_ln
        hang_first_ln / hang_last_ln / ms_last_ln
raw     concrete_mode / kf_line / kp_line / termination
dist    kill_reason: <reason> = N      hang_pc: <address> = N
```

`LL` (long loop) and the differently-cased `Dead Loop (single tb)` are new in v2: the sources
(`InvalidStatesDetection.cpp`) carry four loop-termination messages and the old awk covered only one.

## Coverage

> **Note**: `valid_block_range/<fw>_bbl.txt` must be generated **externally and placed into the batch
> directory**; the pipeline does not produce it.  Firmware without a bbl file is recorded as
> `无 bbl 文件` (no bbl file) in `coverage.csv`, which does not affect any earlier step.

`coverage.csv` semantics: `total_bbl` = the number of lines in `<fw>_bbl.txt`, `covered` = the number
of distinct start addresses in the `-cov.csv` that `calculate.py` writes, `pct` = covered / total.

## Notes

- No resume support: every run starts from a clean slate.
- KBs are bound to their batch, with no global cache; the fuzz stage picks up the KB of the same batch.
- Concurrency: see `parallel.{config,kb,fuzz}` in `pipeline.yaml`; configuration generation uses its
  own temporary directory and takes no locks.
- `uEmu-helper.py` prepends `AFL/` to absolute paths (rendering `AFL//abs/path`) and calls a bare
  `afl-fuzz`; `pipeline.py` rewrites both after generating the scripts.

---

## Provenance

This directory is a **verbatim copy** of `https://github.com/Orantree957/uEmu-test` at commit
`b8f8500e4830fe525ed089d58040e233a96bdfcc` (branch `main`), vendored into this artifact so that the
repository stays self-contained — the upstream repository is private, so a submodule pointer to it
could not be fetched by a reviewer.  All code, firmware images, configs and results are unchanged.

Only documentation was translated from Chinese into English: this README and the comments in
`pipeline.yaml`.  The tool's own runtime messages — including the `result` and `details` values quoted
above, and its console/CSV output — are left exactly as they are, because the artifact cites them
verbatim.

Two dependencies beyond the `pip install pyyaml` above are needed to get it running (measured in the
artifact's container): `uEmu-helper.py` also imports **Jinja2**, and both `pipeline.py` and the helper
call `configparser.SafeConfigParser`, which Python 3.12 removed — so the harness must run on Python
≤ 3.11.  `pipeline.py` invokes the helper as a bare `python3`, so that interpreter must be the one
`python3` resolves to on PATH as well.  `scripts/run_uemu_test.sh` in this artifact does both.
