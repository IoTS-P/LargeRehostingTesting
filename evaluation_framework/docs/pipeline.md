# The pipeline, stage by stage

The paper evaluates every tool inside one pipeline of four stages. The artifact keeps that
vocabulary, and each configuration below is labelled with the stage it serves:

| paper stage | configurations | what it does |
|---|---|---|
| **Stage 1 — Reconnaissance** | `00b_analyze`, `01_firmxray`, `02_firmline` | recover what is needed to start an emulation: Ghidra pre-analysis (functions, strings), the base address and entry validity (FirmXRay), and a generic firmware analysis (Firmline) |
| **Stage 2 — Emulation** | `02b_admission` (plus the `FuzzwareGateway` task inside `03`–`05`) | turn the recovered base address into an emulation configuration and decide whether the fuzzer admits the firmware at all |
| **Stage 3 — Security Testing** | the fuzzing tasks of `03_fuzzware` (FirmXRayOnFuzzware), `03b_p2im` (ConvertFirmToELF → P2IMGateway → P2IMRunner; P²IM as the related-work baseline, run directly), `04_hoedur`, `05_multifuzz` (+ crash replay and statistics) | fuzz each firmware, replay the crashes, collect coverage |
| **Stage 4 — Diagnosis** | `06_firmrca`, `06b_firmrca_classify` | root-cause analysis of the crash inputs and the classification pass |

`00_import` is not a stage — it puts the firmware into the database before Stage 1 starts, and
`smoke_test` is a container self-check.

akiba runs *modules* over the firmware binaries that were imported into its
database. A run config selects the binaries (`sqlSource.constraint`), opens a
Ghidra project, imports a few columns from earlier stages (`dbImports`) and then
executes a list of module classes (`tasks`), each writing one table
(`tableName`). Every module is a small adapter that shells out to the tool it
wraps, so the tool's own output directories end up under
`/data/akiba/binaries/<projectRoot>/<id>/`.

## Stage 01 — FirmXRay (Stage 1 Reconnaissance: base address)

* module: `org.iotsplab.akiba.process.FirmXRay`
* runs: `cd /data/tools/FirmXRay && java -cp out:lib/ghidra.jar:lib/json.jar main.Main <firmware> Nordic`
* writes: `firmxray_results` (`base_address BIGINT`, `entry_valid TEXT`)

The base address is the pivot of the whole pipeline: fuzzware, hoedur and
MultiFuzz all rebase the firmware before emulating it, and they all read this
table (`dbImports: ["firmxray_results.base_address"]`). Firmwares for which
FirmXRay fails get `err_msg = 'failed'`. The base address is what the emulation stages
rebase with; `entry_valid` is the premise of the admission stage (02b), and 02b's verdict
is the premise of the fuzzing stages (03-05) - the same chain the reference server runs.

## Stage 02b — Admission (Stage 2 Emulation: seed admission)

* modules: `FuzzwareGateway`, then `FuzzwareAdmissionTest`, `HoedurAdmissionTest`,
  `MultiFuzzAdmissionTest`
* runs: the gateway generates `config.yml` for each firmware (the same call stage 03
  makes), then each admission test asks its fuzzer whether the initial seeds are
  admissible — `fuzzware pipeline --runtime-config-name <config> -p pipeline`,
  `hoedur-convert-fuzzware-config` + the hoedur admission run, and the MultiFuzz
  equivalent
* writes: `fuzzware_admission_checks_v2`, `hoedur_admission_checks_v2`,
  `multifuzz_admission_checks_v2` (`result TEXT`, `detail TEXT`) — the table names the
  reference server uses

`result` is `PASSED`, `FAILED_*` or `NOT_RUN`/`RUNTIME_ERROR`; `detail` carries the
per-seed `[ADMISSION]` lines the fuzzer printed. This is the stage that decides how
many samples of a corpus are fuzzable at all, so run it before the fuzzing stages.
Admission is the gate of the fuzzing stages, as on the reference server: 02b requires
FirmXRay's `entry_valid = 'valid'`, and `03_fuzzware.json`, `04_hoedur.json` and
`05_multifuzz.json` require the verdict of their own admission table. `03b_p2im.json` is the exception:
P²IM is the paper's related-work baseline and is fed the corpus **directly**, with no admission premise:

```sql
WHERE id IN (SELECT id FROM fuzzware_admission_checks_v2  WHERE result = 'PASSED')
WHERE id IN (SELECT id FROM hoedur_admission_checks_v2    WHERE result = 'PASSED')
WHERE id IN (SELECT id FROM multifuzz_admission_checks_v2 WHERE result = 'PASSED')
```

A firmware whose fuzzer rejects the initial seeds is therefore never fuzzed.

**Where the verdicts come from.** The pre-check is part of the tools: the nine
`evaluated_tools_and_configurations/patches/admission-*.patch` files make fuzzware, gdma,
hoedur and MultiFuzz run the initial seeds before fuzzing and print one
`[ADMISSION] Seed … PASSED/FAILED …` line each, which the `*AdmissionTest` modules parse into
`result`/`detail` (`scripts/apply_patches.sh` applies them). fuzzware and gdma are pure
Python, so the container's venv picks the change up through the `fuzzware_pipeline` symlink;
hoedur and MultiFuzz are Rust and have to be rebuilt (`cargo build --release`) after applying.

**Relationship to the reference server.** The server runs one admission config per tool
(`configs/config_{fuzzware,hoedur,multifuzz,aidfuzzer,fuzzware_gdma}_admission.json`) writing
into `<tool>_admission_checks_v2` for the samples whose `entry_valid` is `valid`; this stage
mirrors that, only combined into one config. Measured there over 2,468 samples per tool:
fuzzware 17.7 s on average (4.3 s min, 387 s max), hoedur 19.5 s, multifuzz 42.2 s.

**Runtime.** The patched pipeline performs the admission test directly after parsing its
configuration and then *exits*: `sys.exit(1)` when a seed fails, `sys.exit(0)` when they all pass
(`"All initial seeds passed admission test. Proceeding to Session 0."`). This stage is therefore a
check, never a fuzzing run — no timeout is needed and the reference configs carry none. Measured:
6.8 s for the fixture in this repository, 17.7 s on average over the reference server's 2,468
rows (the 387 s maximum is a seed that hangs until its own physical time limit). Note also that the container's tool mounts can go stale
after the host drive is re-mounted: if `/data/tools/<tool>` shows `d?????????`,
`docker restart largerehosting_akiba` restores them (both `run_pipeline.sh` and the stage will
otherwise fail with an unreadable-tool error).
## Stage 03 — Fuzzware (FuzzwareGateway = Stage 2 Emulation; fuzzing/replay/statistics = Stage 3 Security Testing)

`FuzzwareGateway` prepares a project per firmware under
`akiba_data/binaries/fuzzware_projects/<id>/`: it copies the (rebased) firmware,
generates `config.yml` and calls the fuzzware pipeline inside the `fuzzware_gdma`
virtualenv - the DMA-capable build the reference server fuzzes with (`venv:
"fuzzware_gdma"` in its `config_fuzzware_on_gdma_2.json`). Then:

* `FirmXRayOnFuzzware` — the fuzzing run (`maxTimeout` per firmware) →
  `firmxray_on_fuzzware_results` (execs, coverage, crash counts);
* `FirmXRayOnFuzzwareReplay` — replays every crash input found
  (`fuzzware replay …`) → `firmxray_on_fuzzware_replay_results`
  (`crash_replay_results` JSONB: pc, lr, coverage) and the view
  `firmxray_fuzzware_replay_crashes`;
* `FuzzwareStat` — coverage statistics → `firmxray_on_fuzzware_stat_results`.

## Stage 03b — P²IM (Stage 3 Security Testing)

`03b_p2im.json` runs the P²IM step of Stage 3. It is the only stage that needs an ELF, so it chains
three modules per firmware: `ConvertFirmToELF` (rebuilds the firmware as an ELF in
`/data/akiba/binaries/parsed_elfs/<id>.elf`, using `firmxray_results.base_address` as the load
address; table `convert_elf_results`), `P2IMGateway` (guesses board/MCU and writes the `fuzz.cfg`
P²IM's scripts read, table-less by design) and `P2IMRunner` (`fuzz.py`, i.e. peripheral-model
instantiation with P²IM's own QEMU, AFL fuzzing, then a coverage count; table
`p2im_fuzzing_results`).

```sql
WHERE id IN (SELECT id FROM firmxray_results WHERE base_address IS NOT NULL)
```

P²IM is the paper's **related-work baseline**, and this step exists to document that the dataset does
not run on it: it is fed the corpus directly (no admission premise, no per-firmware eligibility
verdict), because restricting it to firmware that already emulates under fuzzware would defeat the
comparison. The predicate is the reference's own — it only asks that there be a base address to rebuild
the ELF from.

On the reference, all 2,468 eligible firmware (every image FirmXRay gives a base address) spent the full
one-hour budget and reported `Crashes: 0, Hangs: 0` with only 4-56 basic blocks - that record, not
crashes, is what the paper cites; a firmware that suddenly emulates under P2IM would be the surprising
Both shapes appear here and both land a row: a firmware whose seeds P2IM's model accepts runs its
budget (`Timeout reached (Normal behavior for fuzzing)` -> `execution_status = SUCCESS_TIMEOUT`), while
one it cannot instantiate ends on AFL's own `PROGRAM ABORT` (`All test cases time out` / `Test case ...
results in a crash`) -> `FAILED_EARLY_ABORT`.  On the container's 34-firmware fixture set (60 s budget
for the verification runs, 3600 as shipped) that is 33 rows: 32 `FAILED_EARLY_ABORT` and 1
`SUCCESS_TIMEOUT`, every one with `crashes_found = 0, hangs_found = 0` and 0-185 basic blocks.
(ConvertFirmToELF only, sampled `rn % 45 = 0 LIMIT 100`) and then `config_p2im.json` (P2IMGateway ->
P2IMRunner over `convert_elf_results WHERE elf_path IS NOT NULL`, which is where the 2,468 came from);
this artifact runs both in one config, and keeps the module's own table name `p2im_fuzzing_results`
rather than the reference's experiment suffix.

The per-firmware budget is `P2IMRunner.timeoutSeconds` (default 3600, also settable with `--fuzz-time`).

Three environment facts make the difference between "P2IM runs and reports nothing" and an indirect
failure that surfaces as an unsupported board/MCU.  `scripts/setup_tools.sh`, `docker/docker-compose.yml`
and the runtime image handle all three:

* **Interpreter.** `fuzz.py` and its helper `me.py` need Python < 3.12 (`configparser.SafeConfigParser`),
  and `me.py` runs through `#!/usr/bin/env python3` while `P2IMRunner` derives the child's `PATH` from
  `dirname(pythonPath)`.  `P2IMRunner.pythonPath` therefore points at `~/p2im-py38/bin/python3`, the
  artifact's equivalent of the reference's `p2im` conda env (3.8.20); pinning `/usr/bin/python3.8` is not
  enough, because `python3` then resolves to 3.12 and `me.py` dies before writing
  `0/peripheral_model.json`.
* **AFL and `core_pattern`.** AFL 2.06b aborts when the kernel's `core_pattern` begins with a pipe (a
  container inherits the host's, e.g. apport), so the compose file and the image export
  `AFL_I_DONT_CARE_ABOUT_MISSING_CRASHES=1` and `AFL_SKIP_CPUFREQ=1`.
* **QEMU's client libraries.** The staged QEMU needs `libx11-6` and its X11/GL siblings; the image
  carries them and the provisioner installs them when a container was created without them.
Provisioning is `scripts/setup_tools.sh p2im`.

## Stage 03c — µEmu (Stage 3 Security Testing)

`scripts/run_uemu_test.sh` runs the µEmu step of Stage 3.  Unlike every other stage it is not a
framework config: `uEmu-test` is a self-contained CLI (`pipeline.py`: cfg → kb → fuzz → analyze →
coverage) and the reference ran it directly, so the stage is a script that `run_pipeline.sh` invokes
under the `03c` id, and it writes no database table — its output is CSV under `results/uemu_test/`.
The harness itself is vendored into this repository rather than referenced as a submodule (upstream
is private); see the stage's section in `README.md`.
It works on the same ELFs the P²IM stage's first pass produced, fed in directly with no admission
premise: µEmu is the paper's second related-work baseline and the step documents that this corpus does
not run under it.

The harness keeps a batch per seed (`firmware/<batch>/<fw>/{<fw>.elf,<fw>.cfg}`), so the stage runs the
`artifact` batch with `testcases/seed_alternating.bin`.  `cfg` derives each firmware's memory layout
from the ELF's LOAD segments and needs no µEmu; `kb` (knowledge-base extraction) and `fuzz` (AFL dry run
plus µEmu fuzzing) need µEmu built with S2E — `$uEmuDIR/build`, libs2e, the patched `AFL/afl-fuzz` —
and KVM for the guest, none of which the container carries.  So `kb` is attempted once with a short
probe budget, the tool's own message is what the run records, and `analyze`/`coverage` classify the
result (`results.csv`: 正常fuzz / 提前退出 / 卡死).  The dataset-level verdict comes from the harness's
own run on a µEmu host; see `README.md` (stage `03c`) and `provenance.md` for that record and for the
three interpreter traps this stage shares with the P²IM one.

## Stage 04 — Hoedur (Stage 3 Security Testing)

`HoedurFuzz` converts the fuzzware config to a hoedur config
(`cargo run --bin hoedur-convert-fuzzware-config`), fuzzes
(`hoedur-arm`), and `HoedurStatistics` computes coverage
(`hoedur-coverage-list`, `hoedur-eval-executions`) →
`hoedur_fuzz_results`, `hoedur_statistics_results`. Both use
`workon fuzzware` because hoedur's helper scripts come from the fuzzware venv.

## Stage 05 — MultiFuzz (Stage 3 Security Testing)

`MultiFuzz` generates/loads the firmware config (ghidra submodule), fuzzes with
`cargo run --release -- <workdir>` (env `WORKDIR`, `RUN_FOR`, `COVERAGE_MODE=blocks`)
and replays every crash with `REPLAY=<input>` / `TRACE_PATH=<out>` (this is why
`patches/multifuzz.patch` makes the trace path configurable) →
`multifuzz_results` + `replay_data`.

## Stages 06 / 06b — FirmRCA (Stage 4 Diagnosis)

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

## Stage 02 — Firmline (Stage 1 Reconnaissance: generic firmware analysis)

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

### 02b admission - verified locally

The three admission tasks ran against the smoke fixture (`id=5`, `3349.bin`) and produced one row
per tool in seconds, never a fuzzing run:

| tool | table | time | result |
|---|---|---|---|
| Fuzzware | `fuzzware_admission_checks_v2` | 6.7 s | `PASSED` (3 seeds, `NORMAL_FULL_CONSUMPTION`) |
| Hoedur | `hoedur_admission_checks_v2` | 2.6 s | `PASSED` (3 seeds, `NORMAL_FULL_CONSUMPTION`) |
| MultiFuzz | `multifuzz_admission_checks_v2` | 2.6 s | `PASSED` (3 seeds, `NORMAL_FULL_CONSUMPTION`) |

This matches the reference server, whose `*_admission_checks_v2` rows average 17.7 s (Fuzzware),
19.5 s (Hoedur) and 42.2 s (MultiFuzz) over 2,468 rows.  The stage stays short by construction: the
patched `fuzzware pipeline` performs the admission test while parsing its configuration and then
exits (`sys.exit(0)` when every seed is consumed normally, `sys.exit(1)` otherwise), so no timeout
wrapper is needed or wanted.

Two environment facts the tasks depend on:

- Hoedur's admission module invokes `<hoedur>/target/debug/hoedur-arm`, i.e. the **debug** build;
  `setup_tools.sh` therefore builds both profiles.
- The generated hoedur configuration addresses the firmware by the name it has in the fuzzware
  project - its **original file name** (`3349.bin`), as recorded in the database.  `HoedurAdmissionTest`
  places the firmware in its project directory under that name as well as under the name akiba
  imported it with (`5.bin`), so the memory-map lookup cannot miss.
