# Repository for "SoK: A Large-Scale Empirical Study of Emulation-Based Dynamic Analysis Research for ARM Cortex-M Firmware"

This repository contains the code and resources covering the paper:

**"SoK: A Large-Scale Empirical Study of Emulation-Based Dynamic Analysis Research for ARM Cortex-M Firmware"**

The materials here are provided to support reproducibility and further research.

---

## Citation

If you use this code or data in your research, please cite our paper:

```bibtex
@inproceedings{large_rehosting_testing,
 title = {SoK: A Large-Scale Empirical Study of Emulation-Based Dynamic Analysis Research for ARM Cortex-M Firmware},
 author = {Li, Hongyuan and Wang, Ke and Zhou, Wei and Guan, Le},
 booktitle = {NDSS},
 year = {2026}
}
```

---

# A Artifact Appendix

## A.1 What is included

Our artifact contains the framework, tool snapshots, evaluation data, and detailed results required to reproduce and inspect our empirical study.

**1. Evaluation Framework.**  
`evaluation_framework/` contains the source code and configurations of our automated pipeline-based evaluation framework, including:

- `framework/` — Akiba 3.1.2, our Ghidra-based batch-analysis framework and analysis modules for invoking the evaluated tools and collecting results.
- `docker/` — Docker configuration for building the complete evaluation environment.
- `pipeline_configs/` — configurations for the four evaluation stages.
- `scripts/` — scripts for running the evaluation workflow.
- `README.md` — the script reference: what every committed driver does, where it runs, and how to
  run a single stage, a single module or a single firmware (`docs/pipeline.md` documents the stages
  and the tables they write).
- `evaluation_samples/` — firmware samples used in the evaluation, provided separately through the reviewer-only Google Drive link (see A.3.5).
- `evaluation_results/` — per-stage results and intermediate outputs.

**2. Evaluated Tools and Configurations.**  
`evaluated_tools_and_configurations/` contains snapshots of the evaluated tools, pinned to the versions used in our study, together with the required build instructions, configurations, and local patches. These include FirmXRay, Firmline, Fuzzware, GDMA, Hoedur, MultiFuzz, FirmRCA, P²IM, μEmu, DICE, AidFuzzer, and AIM. Detailed commit IDs and modifications are documented in the corresponding README files.

**3. Evaluation Results.**  
We provide the complete results and intermediate outputs for all four pipeline stages:

- **Stage 1 — Reconnaissance:** recovered base addresses and entry points, configuration-verification results, and failure categories.
- **Stage 2 — Emulation:** emulation configuration, initialization results, seed admission, and failure categories for each tool-firmware pair.
- **Stage 3 — Security Testing:** fuzzing results, including coverage, deduplicated crashes, and failure categories.
- **Stage 4 — Diagnosis:** FirmRCA diagnostic outputs and corresponding manual-validation results.

**4. Empirical Study Details.**  
We additionally provide detailed evaluation records and representative cases for the problems identified in the paper, supporting the findings and conclusions of our empirical study.

**5. Firmware Dataset.**  
The evaluation uses 4,571 firmware images collected from FirmLine and OTACap. Due to dataset size and copyright restrictions, the firmware binaries are not publicly redistributed in this repository.
`dataset_identification_and_reconstruction/binaries_md5.csv` provides the MD5 hash and source-dataset identifier for each firmware image, enabling researchers with access to the original datasets to reconstruct the benchmark. We also provide recoverable metadata and artifact signatures, including vendor and device/MCU model information where identifiable.



## A.2 Requirements



### A.2.2 Hardware Dependencies

The full evaluation (4,571 firmware samples, three fuzzers, one hour of fuzzing per firmware in three independent runs) is sized for the configuration given in the artifact appendix, on which it completes in roughly two to three weeks:

- CPU: x86-64 with at least 64 cores
- Memory: at least 100 GB of RAM
- Parallelism: at least 32 concurrent fuzzing instances
- Storage: at least 2 TB of free space for the firmware, the intermediate testing files, the trace files, the logs and the fuzzing results

Smaller machines are sufficient for functional validation. The artifact itself is modest: the container image is 7.9 GB, and the sample set distributed through Google Drive occupies 1.6 GB in the pipeline's stripped form.
- The sample set published on Google Drive is the 4,571 `ARM:LE:32:v8T` raw-binary firmware images this evaluation uses (stages 1–3). It is distributed in the offset-stripped form and occupies 1.6 GB on disk; `evaluation_samples/restore_original.py` expands it to the 27.7 GB (25.8 GiB) of original firmware. Only 334 of the images are stored stripped (they restore to 26.6 GB); the other 4,237 are already in their original form and account for 1.1 GB. This is the evaluation subset, not the 47 GB / 19,011-image library on the reference server, which additionally holds other architectures and formats.
- For small-scale smoke testing, the five sample ARM firmware files selected by `scripts/quickstart.sh` require only 231 KB.

### A.2.3 Software Dependencies

- A Linux host (tested on Ubuntu 24.04 LTS), Docker 24+ and Docker Compose v2. Git 2.40+ and Python 3.10+ are used by the host-side setup scripts. Network access to GitHub and Docker Hub is needed for the first image build.
- The remaining dependencies — Ghidra, JDK, PostgreSQL, the Python environments, Rust and the tool-specific libraries — are installed inside the provided Docker environment (see A.8).

### A.2.4 Benchmarks

- **Firmware dataset:** 4,571 firmware images collected from multiple sources (FirmLine publicly available, OTACap under NDA, vendor OTA payloads). The `dataset_identification_and_reconstruction/binaries_md5.csv` file provides MD5 checksums and source-dataset identifiers for every image.
- **Stage selection:** The main pipeline constraint selects 4,571 ARM:LE:32:v8T raw-binary firmware images (`WHERE arch = 'ARM:LE:32:v8T' AND format = 'Raw Binary'`).

## A.3 Setup

### A.3.1 Cloning the repository

```bash
git clone https://github.com/IoTS-P/LargeRehostingTesting
cd LargeRehostingTesting
```

### A.3.2 Submodule initialization and patches

```bash
evaluation_framework/scripts/setup.sh --with-nested     # check out all submodules at pinned commits
evaluation_framework/scripts/apply_patches.sh            # apply the captured overlays
```

### A.3.3 Building the container

```bash
evaluation_framework/scripts/build.sh                    # build akiba_allinone:3.1.2 (~15 min)
```

### A.3.4 Starting the container

```bash
evaluation_framework/scripts/up.sh                       # start the container, wait for the daemon
```

### A.3.5 Obtaining firmware samples

The evaluated firmware sample set is hosted in a reviewer-only Google Drive folder. Its URL is
given in the artifact appendix and is deliberately not reproduced here — the firmware binaries are
not redistributed through this repository (A.6).

The folder contains the whole `evaluation_samples/` tree — `firmware/` (all 4,571 corpus images, `<id>.bin`), `offsets/` (the per-image segment mappings), `original_md5.csv`, `offset_dump.tsv`, `stripped_ids.txt`, `restore_original.py` and `scripts/`. Download it and extract the contents into `evaluation_samples/` (that directory is bind-mounted to `/data/samples` inside the container).

The helper script does the same download non-interactively and can also pull a single file:

```bash
evaluation_framework/scripts/fetch_samples_gdrive.sh <google-drive-url-or-id>
```

It accepts a folder link, a file link, `open?id=…` links or a bare id, extracts archives in place, and lands everything in `evaluation_samples/`. The images are stored in the pipeline's own stripped form (see `evaluation_samples/README.md`); `restore_original.py` rebuilds the originals byte-for-byte from `offsets/` when needed.

> **For internal use only:** an `evaluation_framework/scripts/fetch_samples_from_server.sh` script is also provided for users with access to the reference server. It is not part of the public artifact workflow, and it expects `SSHPASS` to be exported in the environment.

### A.3.6 Importing samples

```bash
evaluation_framework/scripts/import_samples.sh           # import evaluation_samples/ into the akiba instance
```

### A.3.7 One-shot run

```bash
evaluation_framework/scripts/quickstart.sh           # 5 hard-coded samples, 2 min fuzzing per tool
evaluation_framework/scripts/quickstart.sh --full    # every sample, shipped 1 h fuzz budget
```

`quickstart.sh` builds the image if it is missing, starts the container (first boot initialises PostgreSQL and creates the `akiba-instance`), provisions whichever of the six tools are still missing, fetches the sample set when `--samples-url` is given (otherwise it uses the empirical-study images that ship with the artifact), imports the selection and then runs the pipeline — `00b` pre-analysis → `01` FirmXRay → `02b` admission → `03` Fuzzware → `04` Hoedur → `05` MultiFuzz → `06`/`06b` FirmRCA → `02` Firmline — and finally exports and summarises the result tables. Stage `02b` runs the admission tests of fuzzware, hoedur and MultiFuzz over the pre-analysis project and records, per firmware, whether the seeds are admissible (tables `fuzzware_admission_results`, `hoedur_admission_results`, `multifuzz_admission_results`); it is the stage that separates "cannot be fuzzed" from "fuzzed badly" (paper §V).

The tool provisions live in named volumes (`akiba_home`, `akiba_local`, `akiba_conda`), so later runs skip them; `quickstart.sh` wipes only the pipeline state (database, Ghidra/fuzzware projects) by default, which is what makes a repeated test reproducible. Pass `--keep-state` to resume instead, `--samples 804,3349` to test other ids, or `--list-stages` to see the stage table.

With the default 2-minute budget the run is a smoke test rather than a measurement. The five
images produce real tool output — FirmXRay 5 rows, Firmline 5 rows, Fuzzware 4 rows (122 s
fuzzing each, 0.031–0.149 basic-block coverage), Hoedur 4 rows, MultiFuzz 4 rows (0.127–0.162
coverage, 1–2 crashes each) and 173 replayed crash records — but three outcomes are expected
and are not defects:

- image 804 reports `err_msg = failed` from FirmXRay (a genuine analysis failure, identical to
  the published stage-1 result);
- stages 06/06b exit with `Empty query results`: the replay crashes of this small sample set
  reach at most ≈0.097 basic-block coverage, below the 0.1 reporting threshold, so the
  root-cause stage has nothing to select. Use a longer budget (`--fuzz-time 30m`) to populate
  them;
- Hoedur's per-sample wall time can far exceed the budget when a sample produces many crashes
  (one image needed 1,320 s of a 120 s budget in the reference run and returned empty
  statistics).

## A.4 Evaluation Workflow

The artifact automates the full evaluation pipeline, which follows the paper's four stages — **Stage 1 Reconnaissance** (`00b` pre-analysis, `01` FirmXRay, `02` Firmline), **Stage 2 Emulation** (`02b` admission, plus the configuration generation and modelling the fuzzware gateway performs for `03`–`05`), **Stage 3 Security Testing** (the fuzzing tasks of `03`–`05`) and **Stage 4 Diagnosis** (`06`/`06b` FirmRCA). Stage `02b` is the explicit split between the first two:

```bash
evaluation_framework/scripts/run_pipeline.sh               # all stages in dependency order
```

A full campaign fuzzes each firmware for one hour per fuzzer, which is far more than a reader checking the artifact needs. `--fuzz-time` shortens that budget for all three fuzzing stages by rewriting the budget keys into `evaluation_results/generated/pipelines/` — the shipped configurations are never modified:

```bash
evaluation_framework/scripts/run_pipeline.sh --fuzz-time 2m
```

### Verification run on the empirical-study samples

The 23 firmware images behind §V of the paper ship in `evaluation_samples/empirical_study_samples/` (original images; each md5 and size matches `dataset_identification_and_reconstruction/binaries_md5.csv`, and `evaluation_samples/scripts/verify_corpus.py` re-checks them against `evaluation_samples/original_md5.csv`). They are the fastest way to exercise every evaluated tool end to end:

```bash
# the 23 images are already in place; their import list ships as
# evaluation_results/generated/import_list_verify.json — regenerate it if needed:
python3 -c "import json,pathlib; p=pathlib.Path('evaluation_samples/empirical_study_samples'); \
print(json.dumps({'entries':[{'path':f'empirical_study_samples/{f.name}'} for f in sorted(p.glob('*.bin'))]},indent=2))" \
  > evaluation_results/generated/import_list_verify.json

# import only those images (paths are relative to /data/samples); a hand-written list is
# required because import_samples.sh imports everything under evaluation_samples/
docker exec -i largerehosting_akiba bash -lc 'cd /home/akiba/akiba_framework && \
  ./bin/akiba_framework -c /data/pipelines/00_import.json@/main \
                        -i /data/results/generated/import_list_verify.json'

evaluation_framework/scripts/run_pipeline.sh --fuzz-time 2m   # 01 → 03 → 04 → 05 → 06 → 06b → 02
```

Step-by-step documentation — the tool each step drives, the command that starts it, the table it
writes and the log it leaves — lives in **`evaluation_framework/README.md`, section "Step by step"**.
The mapping to the paper's stages, its experiments, and the canonical result files:

| paper stage | experiment | steps (configs) | result tables | canonical results |
|---|---|---|---|---|
| Stage 1 — Reconnaissance | E1: recovery and verification of base addresses and entry points | `00_import`, `00b_analyze`, `01_firmxray`, `02_firmline` | `firmxray_results`, `firmline_results` | `evaluation_results/stage_1.csv` |
| Stage 2 — Emulation | E2: whether firmware passing reconnaissance can be initialized by the emulation tools | `02b_admission`, plus the `FuzzwareGateway` task of `03`–`05` | `fuzzware_admission_results`, `hoedur_admission_results`, `multifuzz_admission_results` | `evaluation_results/stage_2.csv` |
| Stage 3 — Security Testing | E3: fuzzing applicability and effectiveness (coverage, crashes, hangs) | fuzzing tasks of `03_fuzzware`, `04_hoedur`, `05_multifuzz`, plus crash replay and statistics | `firmxray_on_fuzzware_results`, `firmxray_fuzzware_replay_crashes`, `hoedur_fuzz_results`, `hoedur_statistics_results`, `multifuzz_results` | `evaluation_results/stage_3.csv` |
| Stage 4 — Diagnosis | E4: post-fuzzing diagnosis with FirmRCA and manual validation | `06_firmrca`, `06b_firmrca_classify` | `firmrca_results`, `firmrca_classified_results` | `evaluation_results/stage_4.csv` |

Expected effort, taken from the artifact appendix and included in the single pipeline run: E1 ≈ 10
human-minutes + 10 compute-hours, E2 ≈ 10 human-minutes + 7 compute-hours, E3 ≈ 20 human-minutes +
tool-dependent multi-day compute time, E4 ≈ 10–30 human-minutes + 10–15 compute-hours. With the
recommended hardware the three one-hour runs per firmware are sized as follows:

| Tool | Samples | Workers | Est. time |
|---|---|---|---|
| Fuzzware | 1,471 | 16 | 79 h (3.3 d) |
| GDMA | 1,462 | 16 | 79 h (3.3 d) |
| Hoedur | 1,485 | 24 | 56 h (2.3 d) |
| MultiFuzz | 1,499 | 48 | 32 h (1.3 d) |

i.e. ≈17,751 fuzzing instance-hours across the four campaigns. The canonical results show that
1,580 of the 4,571 firmware samples (34.5%) can be successfully fuzzed by at least one evaluated
tool, with an average code coverage of ≈10%; the artifact also reports the corresponding crash and
hang results. Exact coverage and crash counts vary between reruns because fuzzing is
nondeterministic — the supplied `stage_*.csv` files are the canonical comparison.

Results are exported with:

```bash
evaluation_framework/scripts/export_results.sh                     # tables and views -> evaluation_results/db/*.csv
evaluation_framework/scripts/export_results.sh --with-artifacts    # + per-firmware work trees
```

## A.5 Empirical Study Details

*The detailed evaluation results and additional cases for each problem to support the findings and conclusions discussed in Section V.*

The study material is a single document, `empirical_study/README.md`.  It defines the twelve root-cause categories P1–P12 observed when applying base-address inference and emulation-based fuzzing to the corpus — each with a representative case and its evidence chain (crash record, disassembly, database entry) — and consolidates the case-level audit: the 100-case verdict distribution (no genuine firmware defect survives the reachability audit), the classification of the 33-case FirmRCA sample, the DMA-caused-crash experiment on the GDMA model, and the Firmline architecture-comparison finding.  Firmware identifiers in that document refer to the dataset index described in A.6; the images themselves are part of the separately distributed sample set (A.3.5).

## A.6 Firmware Dataset Access and Licensing

*FirmLine firmware is publicly available, whereas the OTACap dataset was released to us by its original authors under a non-disclosure agreement.  To obtain the firmware, please contact us and we will point you to the respective data owners; this artifact does not redistribute firmware.*

`dataset_identification_and_reconstruction/` identifies the benchmark without containing it:

- **`binaries_md5.csv`** (19,011 rows) — one row per image: `id` (the filename in the corpus is `<id>.bin`), `original_path`, MD5 `checksum`, and the `arch`/`format`/`compiler_spec`/`size` metadata.  The evaluated subset is selected with `WHERE arch = 'ARM:LE:32:v8T' AND format = 'Raw Binary'` — 4,571 images.

- **Source composition:**

  | Source | Count | Access |
  |--------|-------|--------|
  | FirmLine | 14,873 | Publicly available |
  | OTACap-derived | 4,138 | Original authors' data under NDA — contact us |
  | **Total** | **19,011** | |

- **`dataset_identification_and_reconstruction/Dataset.csv`** provides the identified metadata: hash, vendor name, OS, device/MCU model and source dataset identifier.
- The container image (`akiba_allinone:3.1.2`) ships no firmware: images stay in the host-mounted `evaluation_samples/` directory and the database is initialised empty by the entrypoint.

## A.7 Tool Building and Runtime Dependencies

Each tool in `evaluated_tools_and_configurations/tools/` has standalone build instructions (in its own README). The container automates builds via `evaluation_framework/scripts/setup_tools.sh`:

| Tool | Build Method | Runtime Dependencies |
|------|-------------|---------------------|
| FirmXRay | `make` (javac, needs ghidra.jar) | JDK 21, Ghidra 11.3.2 SDK |
| Firmline | pip + bgrep make + radare2 install | Conda env (Python 3.11), Ghidra 11.3.2 headless |
| Fuzzware | install_local.sh (virtualenvwrapper) | Virtualenvs `fuzzware` + `fuzzware-modeling`, Redis |
| Fuzzware (GDMA) | install_local.sh (virtualenvwrapper) | Virtualenvs `fuzzware_gdma` + `fuzzware-modeling`, Redis |
| Hoedur | cargo build --release | Rust toolchain, QEMU 7.1.0, shared library |
| MultiFuzz | cargo build --release | Ghidra submodule for config generation |
| FirmRCA | venv + capstone make + capnproto/pomp | Virtualenv, capstone system library |
| P²IM | `make -C afl/`; QEMU either from `qemu/precompiled_bin/` or built from source (`docs/build_qemu.md`) | AFL, QEMU (TriforceAFL-based), ARM cross toolchain to prepare firmware |
| μEmu | Vagrant VM (`vagrant up`) or source build (`make -f $uEmuDIR/Makefile && make install` in `$uEmuDIR/build`, ≈60 min on 4 cores) | S2E 2.0 package set (see `vagrant-bootstrap.sh`), QEMU/KVM, `ptracearm.h` installed into `/usr/include/x86_64-linux-gnu/asm` |
| DICE | apply `DICE-Patches/DICE-P2IM.patch` to the bundled `p2im` and rebuild QEMU, and `DICE-Patches/DICE-MIPS-EMULATOR.patch` to `mips-emulator`; precompiled binaries under `DICE-precompiled/` | P²IM's QEMU build prerequisites, `mipsel-softmmu` target for the MIPS build |
| AidFuzzer | upstream prebuilt image (`docker pull wjqsec555/aidfuzz`), or build the in-tree `framework/` against the vendored `qemu-7.2.0/`; run `framework/bin/iofuzz fuzz <config.yml> ./simulator -corpus <dir>` | QEMU 7.2.0 (vendored), x86-64 Linux; the authors publish their evaluation dataset separately |
| AIM | python virtualenv + `pip install angr==9.2.40 pandas monkeyhex IPython pygraphviz`, plus the two documented angr source edits (tested against angr 8.20.7.27) | Python 3.8, angr, pandas, monkeyhex, IPython, pygraphviz |

The last five rows are standalone builds from each tool's own instructions; `evaluation_framework/scripts/setup_tools.sh` provisions the six pipeline tools (`ALL_TOOLS=(firmxray firmline fuzzware hoedur multifuzz firmrca)`) together with the `gdma` variant on request (`setup_tools.sh gdma`) and does not touch the five related-work snapshots.

The module JARs are compiled by `evaluation_framework/scripts/build_akiba_modules.py` during the Docker build (see `evaluation_framework/docs/provenance.md` §5).  The image ends up with 37 module JARs — `akiba_modules`'s 36 plus `amod-AkibaUtils-1.0.jar` from `akiba_mod_utils` (the Dockerfile builds `:akiba_mod_utils:moduleJar-AkibaUtils` and copies both `build/libs` directories into `akiba_framework/modules/`).  `AkibaUtils` is a *dependency* of the other modules: without it every module aborts with `Module not found: org.iotsplab.akiba.module.AkibaUtils`.

## A.8 Container Image

The container (`akiba_allinone:3.1.2`) is built with:

```bash
evaluation_framework/scripts/build.sh
```

Key characteristics:
- Base: `ubuntu:24.04`
- Runtime: JDK 21 (headless), PostgreSQL 16, pgbackrest
- SDK: Ghidra 11.3.2 (ghidra.jar; the same release runs headless for Firmline)
- Orchestration: Akiba framework 3.1.2 (database daemon port 31777, framework CLI)
- Python: Miniconda with `py310` (Python 3.10) and `firmline` (Python 3.11) environments
- Languages: Rust (latest stable), Zsh + virtualenvwrapper
- Services: OpenSSH (host port 31779), Redis
- Volume structure (container):
  ```
  /data
    ├── tools/          twelve tool checkouts (bind-mounted from host; the six
    │                   pipeline tools, the DMA variant of Fuzzware, five
    │                   related-work snapshots)
    ├── akiba/          binaries, ghidra projects (named volume)
    ├── samples/        firmware sample set (bind-mounted)
    ├── results/        pipeline outputs (bind-mounted)
    └── pipelines/      run configs (read-only bind)
  ```
- Initialization: PostgreSQL initialization, akiba-instance creation, daemon start.

The image must remain `3.1.2`; the Akiba 4.x framework is not a drop-in replacement (changed module API and framework layout).

**Runtime fixes baked into `entrypoint.sh`** (both are Akiba 3.1.2 quirks, found by running the container, and both are commented next to the code):

1. `$HOME/.akiba` is a symlink to the volume-mounted `/akiba/.akiba`.  The daemon *reads* its instance map from `config.instanceMapFile` but *writes* it to `${user.home}/.akiba/instances.json`; pointing the config at the volume without this link makes the two paths diverge, so the instance silently disappears between the first-boot daemon and the main daemon and every login fails with `400 Instance akiba-instance not found` / `Failed to login to database: null`.
2. `/akiba/instances/<name>` (the PostgreSQL data directory) must stay owned by `postgres` while `/akiba/instances` itself stays owned by `akiba`: the daemon creates the instance directory as `akiba`, and `pg_ctl` is then run as `postgres`.  A blanket `chown -R akiba:akiba /akiba` leaves those data directories akiba-owned/`0700`, and `/instance/connect` then answers `500` (`Instance akiba-instance failed to start`).

Verify the container end to end with `evaluation_framework/scripts/smoke_test.sh` — it imports the example ELF shipped in the image and runs the built-in `Entropy` module against it, i.e. it exercises Ghidra headless analysis, module loading and the database round trip without needing any provisioned tool or firmware sample:

```
$ evaluation_framework/scripts/smoke_test.sh
smoke test passed — example_table.csv written:
id  start_timestamp                 finish_timestamp                execute_time  err_msg  entropy
1   2026-09-20 05:40:08.167201+00   2026-09-20 05:40:08.206867+00   00:00:00.039666        1.5698932616815757
```

Pipeline stage configs are passed to the framework with a JSON pointer — `./bin/akiba_framework -c /data/pipelines/<stage>.json@/main` — because each config file carries a top-level `metadata` block (description, start command) next to the actual configuration under `main`.  Without `@/main` the loader deserialises the whole document into its `Configs` class and aborts with `UnrecognizedPropertyException: Unrecognized field "metadata"`.  The flat example configs (`/home/akiba/binaries/config_example.json`, `config_run_example.json`) have no wrapper and are passed as-is.

## A.9 Notes on Discrepancies

- The published evaluation results (`evaluation_results/stage_*.csv`) are the canonical outputs. Re-running on the same corpus should reproduce them qualitatively (same base addresses, order-of-magnitude crash counts, problem categories), but exact values may differ due to timing-dependent fuzzing and Ghidra version differences.
- FirmRCA results (Stage 4 — Diagnosis) depend on specific crash inputs from fuzzing stages; different crash sets produce different FirmRCA outputs. The provided `empirical_study/firmrca_analysis/` captures one run for reproducibility.
