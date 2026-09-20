# Repository for "LargeRehostingTesting: A Reproducible Evaluation Testbed for Firmware Re-Hosting Tools"

This repository contains the code and resources covering the paper:

**"LargeRehostingTesting: A Reproducible Evaluation Testbed for Firmware Re-Hosting Tools"**

The materials here are provided to support reproducibility and further research.

---

## Citation

If you use this code or data in your research, please cite our paper:

```bibtex
@inproceedings{large_rehosting_testing,
 author = {},
 title = {{LargeRehostingTesting}: A Reproducible Evaluation Testbed for Firmware Re-Hosting Tools},
 booktitle = {},
 year = {},
 address = {},
 publisher = {}
}
```

---

# A Artifact Appendix

## A.1 Abstract

This artifact provides a fully automated, containerized evaluation pipeline for six firmware re-hosting and analysis tools (FirmXRay, Firmline, Fuzzware, Hoedur, MultiFuzz, and FirmRCA) orchestrated by the Akiba batch-analysis framework, together with pinned snapshots of five further tools from related work (P²IM, μEmu, DICE-DMA-Emulation, AidFuzzer, and AIM) provided for reference. It includes:

- (1) the complete source code of our evaluation framework;
- (2) snapshots of all evaluated tools, including tool versions, build instructions, patches, and per-tool configurations;
- (3) full evaluation results and intermediate outputs for four pipeline stages;
- (4) details of the empirical study, including additional cases supporting the findings in Section V;
- (5) firmware dataset reconstruction methods with MD5 hashes, source-dataset identifiers, and mappings to retrieve the original firmware from their respective sources.

## A.2 Description & Requirements

### A.2.1 What is Included

**Evaluation Framework.** The source code of our automated pipeline-based firmware evaluation framework, including:

- `evaluation_framework/framework/` — Source code of the Akiba 3.1.2 batch-analysis framework: the core framework (`akiba_framework`), database daemon (`akiba_db_daemon`), the analysis modules (`akiba_modules`), utilities (`akiba_mod_utils`), and an example module (`akiba_mod_example`). Akiba is a Ghidra-based workflow engine that imports firmware binaries, opens Ghidra projects, and runs analysis modules — each module shells out to one of the six evaluated tools and writes results to a database table.
- `evaluation_framework/docker/` — Container definition (Dockerfile, docker-compose.yml, entrypoint) that builds an all-in-one image (`akiba_allinone:3.1.2`) with Ubuntu 24.04, JDK 21, PostgreSQL 16, Ghidra 12.0.4 (plus 11.3.2 for Firmline), the Akiba framework and its analysis modules compiled from source (37 module JARs; the reference build ships 40 — `CortexEmulator`, `P2IMGateway` and `P2IMRunner` are excluded by the project's own build script or do not compile against the Ghidra 12.0.4 SDK), Miniconda with Python 3.10 and 3.11 environments, the Rust toolchain, Zsh with virtualenvwrapper, and OpenSSH.
- `evaluation_framework/pipeline_configs/` — Akiba run configurations (one JSON per pipeline stage).
- `evaluation_framework/scripts/` — Driver scripts for every step of the evaluation workflow.
- `evaluation_samples/` — the firmware sample set analysed in the evaluation, hosted on Google Drive (see A.3.5); `evaluation_results/` — the published per-stage results (`stage_1.csv` … `stage_4.csv`) plus the working result tree the container writes (`db/`, `logs/`, `generated/`).

**Evaluated Tools and Configurations.** Snapshots of all evaluated tools, including tool versions, build instructions, patches, and per-tool configurations, subject to their respective licenses:

- `evaluated_tools_and_configurations/tools/` — Git submodule snapshots pinned to the validated commits.
- `evaluated_tools_and_configurations/patches/` — Local modifications for each tool (documented in `evaluated_tools_and_configurations/patches/README.md`).

| Tool | Upstream Repository | Pinned Commit | Local Modifications |
|------|-------------------|---------------|---------------------|
| FirmXRay | MCUSec/RealworldFirmware (FirmXRay/) | `4133f1fe` | `Main.java`, `BaseAddressSolver.java`, `AddressUtil.java` (enhanced variant) |
| Firmline | LittleNewton/firmline | `38f2ddb8` | `file_analyses.py` (watchdog thread), `.gitmodules` (vendored binwalk) |
| Fuzzware | fuzzware-fuzzer/fuzzware | `e43dfbd3` | Python 3.10/3.8, `setuptools<58`, `--no-build-isolation`, `_exit→exit`, unconditional `AFL_SKIP_CPUFREQ` (three patch files: top level, emulator, pipeline) |
| Fuzzware (GDMA) | fuzzware-fuzzer/fuzzware, branch `DMA` (`gdma/`) | `f5979d0` | `install_local.sh` (venv `fuzzware_gdma`), `modeling/setup.sh` (Python 3.10) |
| Hoedur | fuzzware-fuzzer/hoedur | `a021fd06` | `build.rs` (local QEMU), `scripts/` (absolute paths) |
| MultiFuzz | MultiFuzz/MultiFuzz | `44d0cc5d` | `replay.rs` (configurable TRACE_PATH) |
| FirmRCA | NESA-Lab/FirmRCA | `357958d0` | executable autogen.sh, regenerated capnp files |
| P²IM | RiS3-Lab/p2im | `0e64506a` | none (pristine upstream snapshot) |
| μEmu | MCUSec/uEmu | `c82fc0a3` | none (pristine upstream snapshot) |
| DICE | RiS3-Lab/DICE-DMA-Emulation | `2b3b8c8b` | none — DICE brings its own `DICE-Patches/` for P²IM and the MIPS emulator |
| AidFuzzer | wjqsec/aidfuzzer | `c00d62c4` | none (pristine upstream snapshot) |
| AIM | bofeng17/AIM-Interrupt-Modeling | `f32b6124` | none (pristine upstream snapshot) |

The first six rows are the tools driven by the automated pipeline; `gdma/` is the DMA-branch variant of Fuzzware used for the DMA-emulation experiments, whose own overlay keeps the two fuzzware installs apart. P²IM, μEmu, DICE-DMA-Emulation, AidFuzzer and AIM are reference snapshots of related work, pinned at the same fidelity for building and inspecting; they are not exercised by a dedicated stage of the pipeline provided here, and snapshotting them required no local modification (`evaluated_tools_and_configurations/patches/` holds an overlay only for the six pipeline tools and `gdma`).

### A.2.2 Hardware Dependencies

- A machine with an x86-64 CPU and at least 32 GB of memory is recommended. The container image is 7.9 GB; on top of that the pipeline writes the imported firmware, its per-firmware Ghidra/fuzzware projects and the result database, so 64 GB of free storage is a reasonable floor for the verification runs and 200 GB for a campaign over all 4,571 images.
- The sample set published on Google Drive is the 4,571 `ARM:LE:32:v8T` raw-binary firmware images this evaluation uses (stages 1–3). It is distributed in the offset-stripped form and occupies 1.6 GB on disk; `evaluation_samples/restore_original.py` expands it to the 27.7 GB (25.8 GiB) of original firmware. Only 334 of the images are stored stripped (they restore to 26.6 GB); the other 4,237 are already in their original form and account for 1.1 GB. This is the evaluation subset, not the 47 GB / 19,011-image library on the reference server, which additionally holds other architectures and formats.
- For small-scale smoke testing, the five sample ARM firmware files selected by `scripts/quickstart.sh` require only 231 KB.

### A.2.3 Software Dependencies

- A Linux environment (tested on Ubuntu 24.04 LTS). Docker 24+ with docker compose v2 is required for the containerized workflow.
- For host-side operations (outside the container): Python 3.10+, Git 2.40+, and network access to GitHub and Docker Hub for the first build.

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

The evaluated firmware sample set is hosted on Google Drive:

> **Sample set download URL: `<to be filled in>`**

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

`quickstart.sh` builds the image if it is missing, starts the container (first boot initialises PostgreSQL and creates the `akiba-instance`), provisions whichever of the six tools are still missing, fetches the sample set when `--samples-url` is given (otherwise it uses the empirical-study images that ship with the artifact), imports the selection and then runs the pipeline — `00b` pre-analysis → `01` FirmXRay → `03` Fuzzware → `04` Hoedur → `05` MultiFuzz → `06`/`06b` FirmRCA → `02` Firmline — and finally exports and summarises the result tables.

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

The artifact automates the full evaluation pipeline:

```bash
evaluation_framework/scripts/run_pipeline.sh               # all stages in dependency order
```

A full campaign fuzzes each firmware for one hour per fuzzer, which is far more than a reader checking the artifact needs. `--fuzz-time` shortens that budget for all three fuzzing stages by rewriting the budget keys into `evaluation_results/generated/pipelines/` — the shipped configurations are never modified:

```bash
evaluation_framework/scripts/run_pipeline.sh --fuzz-time 2m
```

### Verification run on the empirical-study samples

The 23 firmware images behind §V of the paper ship inside `empirical_study/samples_in_empirical_study.zip` (2.7 MB, the original images; each md5 and size matches `dataset_identification_and_reconstruction/binaries_md5.csv`). They are the fastest way to exercise every evaluated tool end to end:

```bash
cd evaluation_samples && unzip -o ../empirical_study/samples_in_empirical_study.zip -d empirical_study_samples
cd ..

# the 23-entry list ships as evaluation_results/generated/import_list_verify.json;
# regenerate it from the extracted images if needed:
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

Each stage writes its database table (exported to `evaluation_results/db/<table>.csv`) and its logs (`evaluation_results/logs/<stage>.log`). The pipeline produces four stages of results:

### Stage 1 — FirmXRay base-address recognition

*Recovered base addresses and entry points, configuration-verification results, and problem categories for failed verification.*

- Module: `org.iotsplab.akiba.process.FirmXRay`
- Output: `firmxray_results` (base_address, entry_valid)
- Config: `evaluation_framework/pipeline_configs/01_firmxray.json`
- The enhanced FirmXRay variant returns -1 for base addresses it cannot determine, producing the "failed" cases that are categorized.

### Stage 2 — Tool initialization and seed generation

*For each tool-firmware combination: the initialization results, the used seeds, and problem categories for unsuccessful cases.*

- Modules: `FuzzwareGateway` + (tool-specific fuzzer module)
- Configs: `evaluation_framework/pipeline_configs/03_fuzzware.json`, `evaluation_framework/pipeline_configs/04_hoedur.json`, `evaluation_framework/pipeline_configs/05_multifuzz.json`
- Output tables: `firmxray_on_fuzzware_results`, `hoedur_fuzz_results`, `multifuzz_results`
- The gateway module prepares per-firmware fuzzware projects; unreliable firmwares where FirmXRay did not return a base address are skipped.

### Stage 3 — Fuzzing results

*For each tool-firmware combination: the fuzzing results, including coverage, deduplicated crash counts, and problem categories for unsuccessful cases.*

- Output tables: `firmxray_on_fuzzware_replay_results` (view `firmxray_fuzzware_replay_crashes`), `hoedur_statistics_results`, `multifuzz_results`
- Coverage computed by `FuzzwareStat` module (`firmxray_on_fuzzware_stat_results`)

### Stage 4 — FirmRCA diagnostics and manual verification

*Diagnostic outputs from FirmRCA and the corresponding manual verification results.*

- Pass 1 (classifiedMode: false): For crash inputs with basic_block_cov >= 0.1, runs backward taint analysis → `firmrca_results`
- Pass 2 (classifiedMode: true): Classification over selected inputs → `firmrca_classified_results`

### Exporting results

```bash
evaluation_framework/scripts/export_results.sh                     # database tables → evaluation_results/db/*.csv
evaluation_framework/scripts/export_results.sh --with-artifacts    # + per-firmware work trees
```

The `evaluation_results/stage_*.csv` files contain the paper's published results.

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
| FirmXRay | `make` (javac, needs ghidra.jar) | JDK 21, Ghidra 12.0.4 SDK |
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
- SDK: Ghidra 12.0.4 (ghidra.jar) + Ghidra 11.3.2 (headless for Firmline)
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
- FirmRCA results (Stage 4) depend on specific crash inputs from fuzzing stages; different crash sets produce different FirmRCA outputs. The provided `empirical_study/firmrca_analysis/` captures one run for reproducibility.
