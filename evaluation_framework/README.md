# evaluation_framework/ — the harness

Everything that turns the pinned tools into one reproducible pipeline lives here. This file is
the script reference: what each committed script does, where it runs, and how to use it — from
the one-shot run down to a single stage, a single module or a single firmware.

```
evaluation_framework/
├── README.md            this file
├── docker/              Dockerfile, docker-compose.yml, entrypoint — builds akiba_allinone:<version>
├── docs/                pipeline.md (stage-by-stage, thresholds), provenance.md (pinned commits)
├── framework/           Akiba 3.1.2 sources (subprojects/), prebuilt module JARs, Gradle files
├── pipeline_configs/    one JSON run config per stage: 00_import, 00b_analyze, 01_firmxray,
│                        02_firmline, 02b_admission, 03_fuzzware, 04_hoedur, 05_multifuzz,
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
- tool: Ghidra 12.0.4 headless, driven by the framework itself
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
- downstream: `base_address IS NOT NULL` is the constraint of every later stage

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
- writes: `fuzzware_admission_results`, `hoedur_admission_results`, `multifuzz_admission_results`
  (`result`, `detail`) · log: `logs/02b_02b_admission.log`
- cost: one `fuzzware pipeline` per firmware, i.e. roughly a fuzzing run

### `03_fuzzware` — Fuzzware fuzzing (Stage 3 Security Testing)

- modules: `FuzzwareGateway` (one project + `config.yml` per firmware, rebased to
  `firmxray_results.base_address`), `FirmXRayOnFuzzware` (the fuzzing run, `maxTimeout` per
  firmware), `FirmXRayOnFuzzwareReplay` (replays every crash input), `FuzzwareStat` (coverage)
- writes: `firmxray_on_fuzzware_results`, `firmxray_on_fuzzware_replay_results`,
  `firmxray_on_fuzzware_stat_results`, and the view `firmxray_fuzzware_replay_crashes` ·
  log: `logs/03_03_fuzzware.log`
- per-firmware work tree: `/data/akiba/binaries/fuzzware_projects/<id>/`

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
scripts/shell.sh    # or: docker exec -it largerehosting_akiba bash
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
| `build.sh` | host | builds the image `akiba_allinone:<version>`; `PROVISION_TOOLS=1` also builds the six tools (hours) | `PROVISION_TOOLS=1 scripts/build.sh` |
| `up.sh` | host | starts the container and waits until the database daemon answers | `scripts/up.sh` |
| `down.sh` | host | stops/removes the container; volumes are kept unless `--wipe` | `scripts/down.sh --wipe` |
| `setup.sh` | host | checks out the tool submodules at the pinned commits, optional nested submodules, applies `patches/` | `scripts/setup.sh --with-nested` |
| `apply_patches.sh` | host | applies/checks/reverts the captured tool overlays; idempotent, never leaves a partial state | `scripts/apply_patches.sh --check` |
| `setup_tools.sh` | container | provisions the six evaluated tools into `/data/tools` (survives rebuilds); logs per tool | `scripts/setup_tools.sh --check` / `scripts/setup_tools.sh gdma hoedur` |
| `rebuild_framework.sh` | host | rebuilds framework/db-daemon/module JARs from the mounted sources and reinstalls them (needs network) | `scripts/rebuild_framework.sh --modules-only` |
| `build_akiba_modules.py` | container | builds every module JAR from source in dependency order (used by the image build too) | `python3 build_akiba_modules.py` |
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
