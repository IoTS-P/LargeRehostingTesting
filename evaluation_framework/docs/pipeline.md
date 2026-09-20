# The pipeline, stage by stage

akiba runs *modules* over the firmware binaries that were imported into its
database. A run config selects the binaries (`sqlSource.constraint`), opens a
Ghidra project, imports a few columns from earlier stages (`dbImports`) and then
executes a list of module classes (`tasks`), each writing one table
(`tableName`). Every module is a small adapter that shells out to the tool it
wraps, so the tool's own output directories end up under
`/data/akiba/binaries/<projectRoot>/<id>/`.

## Stage 01 — FirmXRay (base address)

* module: `org.iotsplab.akiba.process.FirmXRay`
* runs: `cd /data/tools/FirmXRay && java -cp out:lib/ghidra.jar:lib/json.jar main.Main <firmware> Nordic`
* writes: `firmxray_results` (`base_address BIGINT`, `entry_valid TEXT`)

The base address is the pivot of the whole pipeline: fuzzware, hoedur and
MultiFuzz all rebase the firmware before emulating it, and they all read this
table (`dbImports: ["firmxray_results.base_address"]`). Firmwares for which
FirmXRay fails get `err_msg = 'failed'` and are excluded from the later stages by
the `base_address IS NOT NULL` constraint.

## Stage 03 — Fuzzware

`FuzzwareGateway` prepares a project per firmware under
`akiba_data/binaries/fuzzware_projects/<id>/`: it copies the (rebased) firmware,
generates `config.yml` and calls the fuzzware pipeline inside the `fuzzware`
virtualenv. Then:

* `FirmXRayOnFuzzware` — the fuzzing run (`maxTimeout` per firmware) →
  `firmxray_on_fuzzware_results` (execs, coverage, crash counts);
* `FirmXRayOnFuzzwareReplay` — replays every crash input found
  (`fuzzware replay …`) → `firmxray_on_fuzzware_replay_results`
  (`crash_replay_results` JSONB: pc, lr, coverage) and the view
  `firmxray_fuzzware_replay_crashes`;
* `FuzzwareStat` — coverage statistics → `firmxray_on_fuzzware_stat_results`.

## Stage 04 — Hoedur

`HoedurFuzz` converts the fuzzware config to a hoedur config
(`cargo run --bin hoedur-convert-fuzzware-config`), fuzzes
(`hoedur-arm`), and `HoedurStatistics` computes coverage
(`hoedur-coverage-list`, `hoedur-eval-executions`) →
`hoedur_fuzz_results`, `hoedur_statistics_results`. Both use
`workon fuzzware` because hoedur's helper scripts come from the fuzzware venv.

## Stage 05 — MultiFuzz

`MultiFuzz` generates/loads the firmware config (ghidra submodule), fuzzes with
`cargo run --release -- <workdir>` (env `WORKDIR`, `RUN_FOR`, `COVERAGE_MODE=blocks`)
and replays every crash with `REPLAY=<input>` / `TRACE_PATH=<out>` (this is why
`patches/multifuzz.patch` makes the trace path configurable) →
`multifuzz_results` + `replay_data`.

## Stages 06 / 06b — FirmRCA

FirmRCA works on the crash inputs a fuzzer produced, not on the firmware as a
whole, so it consumes the replay view:

* pass 1 (`06_firmrca.json`, `classifiedMode: false`): for every firmware that has
  a crash entry in `firmxray_fuzzware_replay_crashes` with
  `basic_block_cov >= 0.1` (one input per `(id, pc, lr)`), build a FirmRCA dataset
  and run the backward taint analysis → `firmrca_results`, plus the views
  `firmrca_classified_results` / `firmrca_classified_replays`;
* pass 2 (`06b_firmrca_classify.json`, `classifiedMode: true`): runs the
  classification over the inputs selected in pass 1 (input list comes from the
  database through `dbImports: firmrca_classified_replays.paths`).

Both passes use the venv in `tools/FirmRCA/FirmRCA-fuzzware` (FirmRCA needs its own
fuzzware emulator fork, `tools/FirmRCA/fuzzware-emulator`).

## Stage 02 — Firmline

`Firmline` copies the firmware next to its own directory and runs
`python pipeline.py <firmware>` with `GHIDRA_HOME=/opt/ghidra/ghidra_11.3.2_PUBLIC`,
the `firmline` conda env, `bgrep` on `PATH` and `LD_LIBRARY_PATH=/usr/local/lib`;
results go to `firmline_results` (plus Firmline's own `reverse/` outputs). The
module refuses to run if python is not 3.10/3.11, or if `fwdb.db`, `bgrep` or
`sqlite3` are missing — `scripts/setup_tools.sh --check` reports exactly that.

---

## Adding firmware

```bash
cp my-firmwares/*.bin samples/          # any nesting is fine
scripts/import_samples.sh               # generates the import list, imports, skips duplicates
scripts/run_pipeline.sh --only 01       # base addresses first
scripts/status.sh                       # how many files, what is provisioned
```

Files smaller than the framework's minimum size, directories and dot-files are
ignored; duplicates are skipped by MD5, so re-running the import is safe.

## Inspecting and re-running results

```bash
# what is in the database right now:
scripts/shell.sh
psql -h 127.0.0.1 -p $(python3 -c "import json;print(json.load(open('/akiba/.akiba/instances.json'))['akiba-instance']['port'])") \
     -U akiba -d akiba-instance -c '\dt'

# re-export everything (CSV per table and view):
scripts/export_results.sh --with-artifacts

# resume an interrupted stage:
scripts/run_pipeline.sh --restore 03_fuzzware
```

Every stage can run on its own — the constraint in `sqlSource` decides which
firmwares it touches, so a stage can be repeated on a subset by editing a copy of
the config in `pipelines/`.
