# evaluation_framework/ — the harness

Everything that turns the pinned tools into one reproducible pipeline lives here. This file is
the script reference: what each committed script does, where it runs, and how to use it — from
the one-shot run down to a single stage, a single module or a single firmware.

```
evaluation_framework/
├── README.md            this file
├── docker/              Dockerfile, docker-compose.yml, entrypoint — builds akiba_for_artifacts:<version>
├── docs/                pipeline.md (stage-by-stage, thresholds), provenance.md (pinned commits)
├── framework/           Akiba 3.1.2 sources (subprojects/), prebuilt module JARs, Gradle files
├── pipeline_configs/    one JSON run config per stage: 00_import, 00b_analyze, 01_firmxray,
│                        02_firmline, 02b_admission, 03_fuzzware, 03b_p2im, 04_hoedur, 05_multifuzz,
│                        06_firmrca, 06b_firmrca_classify, smoke_test
└── scripts/             the drivers (below)
```

## Step by step: what every step does

Execution order, one entry per config in `pipeline_configs/`. `scripts/run_pipeline.sh --only <id>`
runs exactly one of them (including its log and result export); `docs/pipeline.md` documents the
modules behind each step in more detail. The tool is the checkout under `/data/tools/` the module
wraps.

### `00_import` — put the firmware into the database (setup, before Stage 1)

- tool: none — the framework's own importer
- run: `./bin/akiba_framework -c /data/pipelines/00_import.json@/main -i <import-list.json>`, or
  `scripts/import_samples.sh`, which builds that list from `evaluation_samples/`
- writes: `binaries` (path, arch, format, md5, size) · log: `evaluation_results/logs/00_import.log`

### `00b_analyze` — Ghidra pre-analysis (Stage 1 Reconnaissance)

- modules: `ProgramInitialization`, `FunctionFinder`, `StringAdder`
- tool: Ghidra 11.3.2 headless, driven by the framework itself
- run: `scripts/run_pipeline.sh --only 00b`
- writes: `program_initialization_results`, `function_finder_results`, `string_adder_results`, and
  the Ghidra project `/data/akiba/ghidra_projects/00b_analyze` that every later stage forks ·
  log: `logs/00b_00b_analyze.log`
- why first: `FuzzwareGateway` refuses to start when the program has no functions
  (`allFunctions.isEmpty()`), so nothing can fuzz before this step has run

### `01_firmxray` — base address and entry validity (Stage 1)

- module: `FirmXRay`, the enhanced variant of `MCUSec/RealworldFirmware` at
  `/data/tools/RealworldFirmware/FirmXRay`
- run inside the module: `java -cp out:lib/ghidra.jar:lib/json.jar main.Main <firmware> Nordic`
- writes: `firmxray_results` (`base_address`, `entry_valid`, `err_msg`) ·
  log: `logs/01_01_firmxray.log`
- downstream: `base_address` feeds every emulation stage; `entry_valid = 'valid'` is the premise of 02b, and 03-05 select `result = 'PASSED'` from 02b's tables

### `02_firmline` — generic firmware analysis (Stage 1)

- module: `Firmline` → `/data/tools/firmline`, run in its own conda environment
- run: `scripts/run_pipeline.sh --only 02`
- writes: `firmline_results` (`err_msg`) · log: `logs/02_02_firmline.log`
- independent of the base address; its constraint skips images that already come from the FirmLine
  dataset

### `02b_admission` — seed admission (Stage 2 Emulation)

- modules: `FuzzwareGateway` (generates the fuzzware configuration), then
  `FuzzwareAdmissionTest`, `HoedurAdmissionTest`, `MultiFuzzAdmissionTest`
- run inside the modules: `fuzzware pipeline --runtime-config-name <config.yml> -p pipeline`,
  `hoedur-convert-fuzzware-config` followed by the hoedur admission run, and the MultiFuzz
  equivalent
- writes: `fuzzware_admission_checks_v2`, `hoedur_admission_checks_v2`,
  `multifuzz_admission_checks_v2` (`result`, `detail`; the reference server's table names)
  · log: `logs/02b_02b_admission.log`
- cost: seconds, never a fuzzing run — the patched pipeline runs the admission test while parsing
  its configuration and then exits (1 on a failing seed, 0 when every seed is consumed normally).
  Measured on the smoke fixture: 6.7 s (Fuzzware), 2.6 s (Hoedur), 2.6 s (MultiFuzz), all three
  `PASSED` with per-seed `detail`; the reference server averages 17.7 s / 19.5 s / 42.2 s over its
  2,468 rows (maximum 387 s). The module also captures the fuzzer's own per-seed lines, which is
  what fills `detail`. Hoedur's task drives the **debug** build (`target/debug/hoedur-arm`) and
  looks the firmware up under its **original file name**, so `setup_tools.sh` builds both cargo
  profiles and `HoedurAdmissionTest` mirrors the name the configuration references

### `03_fuzzware` — Fuzzware fuzzing (Stage 3 Security Testing)

- modules: `FuzzwareGateway` (one project + `config.yml` per firmware, rebased to
  `firmxray_results.base_address`), `FirmXRayOnFuzzware` (the fuzzing run, `maxTimeout` per
  firmware), `FirmXRayOnFuzzwareReplay` (replays every crash input), `FuzzwareStat` (coverage)
- writes: `firmxray_on_fuzzware_results`, `firmxray_on_fuzzware_replay_results`,
  `firmxray_on_fuzzware_stat_results`, and the view `firmxray_fuzzware_replay_crashes` ·
  log: `logs/03_03_fuzzware.log`
- per-firmware work tree: `/data/akiba/binaries/fuzzware_projects/<id>/`

### `03b_p2im` — P²IM fuzzing (Stage 3 Security Testing)

- modules: `ConvertFirmToELF` (rebuilds the firmware as an ELF under `/data/akiba/binaries/parsed_elfs/`
  from `firmxray_results.base_address`; table `convert_elf_results`), `P2IMGateway` (guesses the board
  and MCU from strings in the binary — STM32F429I-Discovery, Arduino-Due, FRDM-K64F, else
  NUCLEO-F103RB — and writes `fuzz.cfg` under `p2im_on_real_fw/<id>/`), `P2IMRunner` (runs
  `model_instantiation/fuzz.py`: peripheral-model instantiation with P²IM's own QEMU, then AFL
  fuzzing, then a basic-block coverage count)
- writes: `p2im_fuzzing_results` (`mcu_used`, `crashes_found`, `hangs_found`, `bbl_coverage`,
  `execution_status`) · log: `logs/03b_03b_p2im.log`
- selection: the corpus **directly** — `WHERE id IN (SELECT id FROM firmxray_results WHERE
  base_address IS NOT NULL)`, the reference's own predicate: P²IM is the paper's related-work baseline,
  so this step deliberately has **no Stage 2 admission premise**, and it asks only that there be a base
  address to rebuild the ELF from.  The per-firmware budget is `P2IMRunner.timeoutSeconds` (the task
  timeout sits above it, as on the reference, so the budget is what ends the run)
- expected outcome: the corpus does not run on P²IM, and the step exists to record that.  On the
  reference, all 2,468 eligible firmware (every image FirmXRay gives a base address) spent the full
  one-hour budget and reported `Crashes: 0, Hangs: 0` with only 4-56 basic blocks - that record, not
  crashes, is what the paper cites.  The same two shapes appear here and both land a row: a firmware
  whose seeds P²IM's model accepts runs its budget (`Timeout reached (Normal behavior for fuzzing)` ->
  `execution_status = SUCCESS_TIMEOUT`), while one it cannot instantiate ends on AFL's own
  `PROGRAM ABORT` (`All test cases time out` / `Test case ... results in a crash`) ->
  `FAILED_EARLY_ABORT`.  On the container's 34-firmware fixture set that yields 33 rows (per-firmware
  budget 60 s here, 3600 as shipped): 32 `FAILED_EARLY_ABORT` and 1 `SUCCESS_TIMEOUT`, every one of them
  with `crashes_found = 0, hangs_found = 0` and 0-185 basic blocks - the corpus-wide zero in miniature,
  and the numbers the paper reports at corpus scale.  A firmware that suddenly yields crashes under P²IM
  is the surprising case, not the goal.
- two passes, not one: `03b_p2im_elf.json` (`ConvertFirmToELF` only, `threads: 1`) and then
  `03b_p2im.json` (`P2IMGateway` -> `P2IMRunner`, forked from the first pass's Ghidra project).  The
  split is a requirement, not a style choice: `ConvertFirmToELF` unpacks its ELFBuilder helper to a
  fixed `/tmp/ELFBuilder`, so concurrent tasks collide on it (`Text file busy`) - the reference ran its
  ELF pass with `threads: 1` for the same reason - and the fuzzing pass's `dbImports`
  (`convert_elf_results.elf_path`) are resolved when a run starts, so those rows must already exist.
  Both passes share the `03b` stage id, so `--only 03b` runs them in order.
- environment: P²IM's `fuzz.py`/`me.py` need Python < 3.12 (`configparser.SafeConfigParser`, removed in
  3.12), and `me.py` is entered through `#!/usr/bin/env python3` while `P2IMRunner` builds the child's
  `PATH` from `dirname(pythonPath)` — so pinning `/usr/bin/python3.8` is not enough: `python3` on that
  path still resolves to 3.12, `me.py` dies before writing `0/peripheral_model.json`, the fuzzed QEMU
  exits loading the model and AFL reports `Fork server handshake failed`.  `P2IMRunner.pythonPath`
  therefore points at `~/p2im-py38/bin/python3`, the artifact's equivalent of the reference's `p2im`
  conda env.  AFL additionally needs `AFL_I_DONT_CARE_ABOUT_MISSING_CRASHES=1` / `AFL_SKIP_CPUFREQ=1`
  when the kernel's `core_pattern` is a pipe.
- provisioning: `scripts/setup_tools.sh p2im` compiles AFL from the snapshot's sources and stages the
  snapshot's pre-compiled GNU ARM Eclipse QEMU at the path `fuzz.cfg` expects, exposing only its
  non-glibc libraries (`qemu/src/qemu.git/gnuarmeclipse-softmmu/libs` + `patchelf --set-rpath`); the
  X11/GL client libraries that QEMU links against come from the image, and `setup_tools.sh` installs
  them itself (via sudo/root) when a container was created without them, as it also creates
  `~/p2im-py38/bin` and verifies the interpreter and the AFL environment

### `03c` — µEmu (Stage 3 Security Testing, related work, run directly)

- what it runs: `scripts/run_uemu_test.sh`, which drives the `uEmu-test` harness
  (`pipeline.py`: cfg → kb → fuzz → analyze → coverage) over the ELF corpus the 03b ELF pass produced.
  There is no akiba module and no database table here — the harness is a self-contained CLI and the
  reference ran it directly — so the stage is a script, wired into `run_pipeline.sh` under the `03c`
  id.  Log: `logs/03c_uemu_test.log` · results: `results/uemu_test/*.csv`.
- input: the same `parsed_elfs` the P²IM pass reads, fed to µEmu directly with no admission premise.
  The harness derives each `<fw>.cfg` from the ELF's LOAD segments, which needs no µEmu at all.
- expected outcome: the corpus does not run under µEmu, and the step exists to record that.  On a stock
  container the record runs to a specific point: `cfg` succeeds (for two firmwares,
  `rom = 0x08000000,0x20000`, `ram = 0x20000000,0x50000`, `vtor = 0x08000000` — a 539-byte file with
  the same five sections as the reference's 544-byte ones), the helper renders `launch-uEmu.sh`
  (2,569 B), `uEmu-config.lua` and `library.lua` per firmware, and `kb` then reports 无KB because the
  µEmu snapshot is source only: no `build/`, no patched `AFL/afl-fuzz`.  Building that stack needs S2E
  (and KVM for the guest), which an artifact container cannot carry.
- the dataset verdict itself comes from the harness's own run record on a µEmu host — the smoke batch
  shipped with it classifies every firmware `提前退出` (`end_type = early`) with
  `fuzzer:dry-run种子crash` and the evidence line ``Test case 'id:000000,orig:seed_alternating.bin'
  results in a crash``: KB extraction succeeds (`有KB`, 3.8–9.7 s), then the AFL dry run on the seed
  ends the firmware before fuzzing starts.  That is the same failure mode the P²IM stage produces on
  this corpus, which is the point of running both.
- vendored, not a submodule: `uEmu-test` is checked into this repository as a verbatim copy of
  Orantree957/uEmu-test at commit `b8f8500e` — the upstream repository is private, so a submodule
  pointer could not be fetched by a reviewer — and the µEmu snapshot it builds against stays a
  submodule (`MCUSec/uEmu`).  966 of the 968 files are byte-identical to upstream; only `README.md`
  and `pipeline.yaml` differ, because their Chinese documentation was translated to English
  (the tool's own messages, the CSVs and every source file are untouched).
- environment: the harness needs Python ≤ 3.11 (`pipeline.py` and µEmu's `uEmu-helper.py` call
  `configparser.SafeConfigParser`, removed in 3.12 — the container's default `python3` is 3.14),
  PyYAML *and* Jinja2 (`uEmu-helper.py` renders the launch templates; its README lists only PyYAML),
  and `pipeline.py` invokes that helper as a bare `python3`, so the chosen interpreter must also be
  exposed as `python3` on PATH.  `scripts/run_uemu_test.sh` picks the first qualifying interpreter and
  does exactly that; `setup_tools.sh uemu` installs the two modules into it.

### `04_hoedur` — Hoedur fuzzing (Stage 3)

- modules: `FuzzwareGateway`, `HoedurFuzz`, `HoedurStatistics`
- writes: `hoedur_fuzz_results` (`actual_fuzz_time`), `hoedur_statistics_results` (crash, timeout
  and exit counts, per-run coverage, executions) · log: `logs/04_04_hoedur.log`
- note: Hoedur treats executions beyond three million basic blocks as a timeout, and its wall time
  can exceed the budget on crash-heavy firmware

### `05_multifuzz` — MultiFuzz fuzzing (Stage 3)

- modules: `FuzzwareGateway`, `MultiFuzz`
- writes: `multifuzz_results` (`coverage`, `crash_count`, `hang_count`, `replay_data`) ·
  log: `logs/05_05_multifuzz.log`

### `06_firmrca` / `06b_firmrca_classify` — root-cause analysis (Stage 4 Diagnosis)

- module: `FirmRCA` → `/data/tools/FirmRCA`; pass 1 selects the replayed crash inputs with
  `basic_block_cov >= 0.1`, pass 2 (`classifiedMode: true`) classifies what pass 1 selected
- writes: `firmrca_results`, `firmrca_classified_results` (view `firmrca_classified_replays`) ·
  logs: `logs/06_06_firmrca.log`, `logs/06b_06b_firmrca_classify.log`
- when no crash input reaches the 0.1 threshold the stage stops with
  `Empty query results, quit immediately` — a valid outcome, not a failure
- **reference only.** The stock module was written to lift the **cov > 0.2** crashes and run
  FirmRCA on those; the paper's 0.1 selection is an order of magnitude larger than that path was
  built for, and its classify pass expects an input table (`firmrca_classified_replays`, `(id, paths)`)
  that the authors fill outside these configs. The artifact therefore performs the 0.1 sampling with
  `scripts/firmrca_sampling.py`, which drives the same two steps — dataset generation through
  FirmRCA's **own** fuzzware harness, then `reversenolog` — and writes
  `evaluation_results/db/firmrca_sampling.csv`
- the module itself does run end to end once its own environment is provisioned
  (`scripts/setup_tools.sh firmrca`): pass 1 generated the dataset and ran `reversenolog` for all
  30 inputs of one firmware (`Successfully generated dataset` / `Successfully ran FirmRCA`) with no
  dataset failures, and for a firmware where reversing finds no root cause it reports the same
  outcome as `firmrca_sampling.py`. What keeps it reference-only is cost, not capability: on the
  0.1 set one firmware selects 268 inputs at ~78 s each (~2.3 s for a short-trace firmware)

### `smoke_test` — container self-check

- module: `Entropy` over the example ELF that ships inside the image ·
  run: `scripts/smoke_test.sh` · writes: `example_table`

## Run everything (one command)

```bash
scripts/quickstart.sh                  # 5 samples, 2 min fuzzing per tool: build if needed,
                                       # start the container, provision tools, import, run all
                                       # stages, export the tables, print a summary
scripts/quickstart.sh --full           # every sample, the shipped 1 h fuzz budget
scripts/quickstart.sh --samples 804,3349 --fuzz-time 5m
scripts/quickstart.sh --keep-state     # do not wipe the pipeline state first
scripts/quickstart.sh --skip-provision # tools are already built
scripts/quickstart.sh --samples-url <google-drive-url>   # fetch the sample set first
```

`scripts/pipeline.sh` is the older, lower-level version of the same idea (build → up → provision →
import → stages → export) with `--no-build`, `--no-tools`, `--only`, `--export-only`,
`--restore`; `quickstart.sh` is what the artifact appendix tells readers to run.

## Run one step

Any single stage, in the container, with its log and an incremental export:

```bash
scripts/run_pipeline.sh --only 02b             # just the admission stage
scripts/run_pipeline.sh --only 01,03           # several stages
scripts/run_pipeline.sh --skip 02,06b          # everything except these
scripts/run_pipeline.sh --fuzz-time 10m        # shorter fuzzing budget for 03/04/05
scripts/run_pipeline.sh --restore 01_firmxray  # resume an interrupted task
```

`--fuzz-time` writes shortened copies into `evaluation_results/generated/pipelines/` and runs
those; the shipped configs are never modified.

A single config, without the wrapper (the same thing `--only` does, useful when you edited a
config and want to see the raw module output):

```bash
scripts/shell.sh    # or: docker exec -it akiba_for_artifacts bash
cd /home/akiba/akiba_framework
./bin/akiba_framework -c /data/pipelines/03_fuzzware.json@/main
```

`pipeline_configs/` is mounted read-only at `/data/pipelines`, so a config you edit in the
repository is immediately visible in the container — no rebuild. To run *one module* of a stage,
copy the stage config, delete the other `tasks` entries and run that file the same way; to run a
stage on *one firmware*, tighten its `sqlSource.constraint` (e.g. `WHERE id = 3349`) and mention
the same rows in `metadata.constraint`.

Single procedures, when a full run is not what you want:

```bash
scripts/import_samples.sh                 # import everything under evaluation_samples/
scripts/import_samples.sh --list          #   ... show what would be imported
scripts/export_results.sh                 # re-export every table and view to results/db/*.csv
scripts/export_results.sh --with-artifacts   #   ... also mirror the per-firmware project trees
scripts/smoke_test.sh                     # container-only check: built-in ELF + Entropy module
```

## Script reference

| script | runs on | what it does | example |
|---|---|---|---|
| `quickstart.sh` | host | one-shot: image if missing, container up, per-tool provision probe, samples, import, all stages, export, summary | `scripts/quickstart.sh --full` |
| `pipeline.sh` | host | older full-run wrapper: build → up → provision → import → stages → export | `scripts/pipeline.sh --only 01,03` |
| `build.sh` | host | builds the image `akiba_for_artifacts:<version>`; `PROVISION_TOOLS=1` also builds the six tools (hours) | `PROVISION_TOOLS=1 scripts/build.sh` |
| `up.sh` | host | starts the container and waits until the database daemon answers | `scripts/up.sh` |
| `down.sh` | host | stops/removes the container; volumes are kept unless `--wipe` | `scripts/down.sh --wipe` |
| `setup.sh` | host | checks out the tool submodules at the pinned commits, optional nested submodules, applies `patches/` | `scripts/setup.sh --with-nested` |
| `apply_patches.sh` | host | applies/checks/reverts the captured tool overlays; idempotent, never leaves a partial state | `scripts/apply_patches.sh --check` |
| `setup_tools.sh` | container | provisions the six evaluated tools into `/data/tools` (survives rebuilds); logs per tool | `scripts/setup_tools.sh --check` / `scripts/setup_tools.sh gdma hoedur` |
| `rebuild_framework.sh` | host | rebuilds framework/db-daemon/module JARs from the mounted sources and reinstalls them. It uses `/opt/gradle-8.8` when that path exists and otherwise `./gradlew`; either way Maven Central has to be reachable, and the wrapper fallback additionally needs the Gradle distribution (which the wrapper downloads on first use) | `scripts/rebuild_framework.sh --modules-only` |
| `build_akiba_modules.py` | container | builds every module JAR from source in dependency order (used by the image build too) | `python3 build_akiba_modules.py` |
| `firmrca_sampling.py` | host | standalone FirmRCA pass: one crash input per (firmware, pc, lr) with `basic_block_cov >= --threshold` (default 0.1), `--per-firmware N` cap per firmware, taken from the exported crash table, then the dataset step (FirmRCA's own fuzzware harness → `instlist.reverse`) and `reversenolog` per input; writes `evaluation_results/db/firmrca_sampling.csv` in the shape of the reference run's `firmxray_on_gdma_firmrca_results`. Takes the place of stages 06/06b, whose stock module only lifts the cov>0.2 crashes | `scripts/firmrca_sampling.py --ids 30,34 --per-firmware 1` |
| `run_pipeline.sh` | host or container | the stage runner: `--list`, `--only`, `--skip`, `--restore`, `--fuzz-time`; exports after every stage; refuses to start when the `/data` bind mounts are stale | `scripts/run_pipeline.sh --only 02b` |
| `import_samples.sh` | container | imports every firmware under `/data/samples` into the akiba instance (`--list` to preview) | `scripts/import_samples.sh --list` |
| `fetch_samples_gdrive.sh` | host | downloads the published sample set from a Drive link/folder/file id into `evaluation_samples/` | `scripts/fetch_samples_gdrive.sh <url> --dry-run` |
| `fetch_samples_from_server.sh` | host | **internal**: mirrors the corpus from the reference server in its own layout (stage-01 selection, or `--all` for the 19,011-image library) | `scripts/fetch_samples_from_server.sh --all` |
| `fetch_reference_modules.sh` | host | **optional**: fetches the reference build's module JARs — for diffing a locally built module JAR, not for the build (their Kotlin metadata 2.3.0 is newer than the compiler this project uses) | `scripts/fetch_reference_modules.sh` |
| `export_results.sh` | host or container | dumps every table and view to `results/db/*.csv` (`--with-artifacts` mirrors the project trees) | `scripts/export_results.sh` |
| `status.sh` | host | prints the state of the container, the sample set and the collected results | `scripts/status.sh` |
| `shell.sh` | host | opens a shell inside the container as the `akiba` user | `scripts/shell.sh` |
| `smoke_test.sh` | container | self-contained check: imports the ELF that ships in the image and runs the built-in Entropy module — no tool provisioned, no samples needed | `scripts/smoke_test.sh` |
| `lib.sh` | sourced | shared helpers: paths, colours, docker wrappers, stage selector (`wanted`), `die` | `. scripts/lib.sh` |

Every script also works from inside the container (the same files are mounted read-only at
`/opt/rehosting/scripts`, and `lib.sh` only shells out to docker when it has to). Host-side
scripts must be started from the repository root, because they resolve mounts relative to
`evaluation_framework/`.

Files named `_*.sh` / `_*.py` in `scripts/` are one-off scaffolding used while the artifact was
assembled (patch regeneration, path rewrites, corpus bookkeeping). They are deliberately **not**
committed — `.gitignore` excludes them — so the script list above is exactly what a clone holds.

## Where the output goes

| what | where |
|---|---|
| per-stage console log | `evaluation_results/logs/<stage>_<config>.log`, `pipeline_timeline.txt` |
| provision log per tool | `evaluation_results/logs/setup_<tool>.log` |
| exported tables (one CSV per table/view) | `evaluation_results/db/*.csv` |
| shortened configs for `--fuzz-time` | `evaluation_results/generated/pipelines/` |
| import lists | `evaluation_results/generated/import_list*.json` |
| per-firmware project trees (with `--with-artifacts`) | `evaluation_results/artifacts/{fuzzware,hoedur,multifuzz}_projects/<id>/` |
| `evaluation_samples/` (firmware) | mounted read-only at `/data/samples` |
