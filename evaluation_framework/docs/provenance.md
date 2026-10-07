# Provenance — where every piece of this repo comes from

The pipeline was developed and evaluated inside a container on the authors' reference server
(user `akiba`, host container hostname `6bae1969f827`), read-only from this repository's point of
view.  That container is the reference for this repository; nothing here was re-invented.  The
server's address and the login material are deliberately not part of this repository — access is
arranged with the authors (see the note on the firmware dataset in the top-level README).

## 1. Tools — upstream commit + overlay

Pins are the commits the reference container had checked out (identical to the
commits the SoK artefact `Hornos3/anon-project` pinned for the same tools).

| Repo path | Upstream | Commit | Local modifications on the server |
|-----------|----------|--------|-----------------------------------|
| `tools/RealworldFirmware` | `MCUSec/RealworldFirmware` | `4133f1fe` | `FirmXRay/src/{core/BaseAddressSolver.java,main/Main.java,util/AddressUtil.java}` modified in the worktree |
| `tools/firmline` | `LittleNewton/firmline` | `38f2ddb8` | `.gitmodules` (binwalk → in-tree), `file_analyses.py` |
| `tools/fuzzware` | `fuzzware-fuzzer/fuzzware` | `e43dfbd3` | `install_local.sh`, `modeling/setup.sh`, plus content changes inside the `emulator` and `pipeline` submodules |
| `tools/hoedur` | `fuzzware-fuzzer/hoedur` | `a021fd06` | `qemu-sys/build.rs`, three `scripts/*.py` |
| `tools/MultiFuzz` | `MultiFuzz/MultiFuzz` | `44d0cc5d` | `hail-fuzz/src/debugging/replay.rs` |
| `tools/FirmRCA` | `NESA-Lab/FirmRCA` | `357958d0` | executable bits on build scripts, regenerated capnp sources |

The FirmXRay entry is the subtree `FirmXRay/` of `tools/RealworldFirmware`, because
that is the FirmXRay the pipeline ran (`configs/1_firmxray.json` on the server points
at `/data/tools/RealworldFirmware/FirmXRay`); the pristine, unmodified variant also
present on the server (`/data/tools/FirmXRay`, used only by
`configs/11_firmxray_original.json`) is not part of this repository.

The working-tree diffs were captured with `git diff` / `git diff --submodule=diff`
and are stored verbatim in `patches/` (one file per tool, one extra file per dirty
nested submodule). `patches/README.md` explains what each hunk does and why.

Commands used to capture them (on the server):

```bash
for t in RealworldFirmware firmline fuzzware hoedur MultiFuzz FirmRCA; do
  git -C /data/tools/$t status --porcelain
  git -C /data/tools/$t diff --submodule=diff
done
```

## 2. Pipeline runner — akiba 3.1.2

* Source: `/data/tools/akiba-source` (a clone of `IoTS-P/Akiba`, working tree
  of commit `1edb6ef`, `version = "3.1.2"`) plus its five submodules
  (`Akiba-Framework`, `Akiba-DB-Daemon`, `Akiba-Modules`, `Akiba-Mod-Utils`,
  `Akiba-Mod-Example`). All of it is flattened into `framework/` here, because the
  module repository is not public: `framework/` **is** the server's working tree
  (modifications included, see below).
* What the server had modified relative to the upstream commit: `Dockerfile`,
  `docker-compose.yml`, `gradlew`, and inside `subprojects/akiba_modules`:
  `build.gradle.kts`, `src/FirmXRayOnFuzzware/FirmXRayOnFuzzware.kt`,
  `src/FuzzwareGateway/kotlin/FuzzwareGateway.kt`, plus five extra modules that
  exist only there (`AidFuzzerAdmissionTest`, `FuzzwareAdmissionTest`,
  `HoedurAdmissionTest`, `MultiFuzzAdmissionTest`, `P2IMGateway`).
* Local fix in `managers/WorkspaceManager.kt`: the `overwriteProject` cleanup ran *after* the
  "fork target already exists" early return, which made the option unreachable — a re-run of a
  fork-mode stage (or any run after an interrupted one) always failed to initialise its
  workspace. The cleanup now runs first, and every `mode: fork` config sets
  `"overwriteProject": true`, so stages can be re-run as often as needed.
* Excluded from the copy (rebuilt at image build time): `lib/ghidra.jar` (227 MB,
  built by Ghidra's `support/buildGhidraJar`), every `build/` directory and the
  distribution zips.
* The deployed runtime on the server (`/home/akiba/akiba_framework` with 40
  `amod-*.jar` modules, `/home/akiba/akiba_db_daemon`) is reproduced by the image
  build; the 43 analysis modules in `framework/subprojects/akiba_modules/src` are
  compiled by `:akiba_modules:moduleJar-ALL`.

## 3. Container definition

`docker/Dockerfile` follows the 3.1.2 line
(`/data/tools/akiba-source/Dockerfile`, `ARG VERSION=3.1.2`,
`ubuntu:24.04`, `openjdk-21-jdk`, `postgresql-16`, `pgbackrest`) and adds what the
reference container had installed interactively on top of it:

* `/usr/bin/python3.10` for fuzzware's `mkvirtualenv -p /usr/bin/python3.10`
  (the server has both system python 3.10 and `/data/tools/anaconda3` with a
  `firmline` env; here both come from Miniconda: envs `py310` and `firmline`),
* zsh + virtualenvwrapper with `~/.zshrc` sources, because the fuzzware-family
  modules run their commands through `cmdPrefix: ["/bin/zsh", "-ic"]` and
  `cmdPredo: "source ~/.zshrc && source …/virtualenvwrapper.sh"`,
* rustup for hoedur / MultiFuzz, `redis-server`, `sqlite3`, `gcc-arm-none-eabi`,
  build toolchain for the tools' native components,
* Ghidra 11.3.2 for both roles -- akiba's `ghidra.jar` and Firmline's
  `ghidraHome` (matching `/data/hongyuan/ghidra_11.3.2_PUBLIC` on the server).
  The pin is load-bearing: a newer SDK (12.0.4) changes stage 01's `entry_valid`
  verdicts for 15 of the 34 fixtures, see §5,
* sshd, so the container can be driven like the server (host port 41778).

Differences worth knowing: the server's `docker-compose.yml` binds the host
directory `/data/hongyuan/hongyuan-24` into the container; here the six tool
directories are bind-mounted individually and the *products* live in named
volumes. The entrypoint is a modified copy of
`evaluation_framework/framework/dockerfile_needed/entrypoint.sh` (persistent DB password
location, ownership fixes for fresh volumes, sshd) and differs further in the two runtime
fixes described in the README (§A.8): the `$HOME/.akiba` symlink onto the volume and the
`postgres` ownership of each instance data directory.

## 4. Stage configurations

`pipelines/*.json` are the server's proven configs with these changes:

| Our config | Server config | Changes |
|------------|---------------|---------|
| `00_import.json` | `dockerfile_needed/config_example.json` | import root `/data/samples` |
| `01_firmxray.json` | `configs/1_firmxray.json` | `firmxrayRoot` → `/data/tools/FirmXRay` (the enhanced variant, which 1_firmxray.json already used via `RealworldFirmware/FirmXRay`), constraint `format = 'Raw Binary'` instead of the `arch = 'ARM:LE:32:v8T'`-only filter |
| `02_firmline.json` | `configs/12_firmline.json` | `ghidraHome` → `/opt/ghidra/ghidra_11.3.2_PUBLIC`, conda paths → `/opt/conda` |
| `03_fuzzware.json` | `configs/config_fuzzware_on_gdma_2.json` (fuzzing premise) + `config_fw_only.json` (project layout) | constraint `id IN (SELECT id FROM fuzzware_admission_checks_v2 WHERE result = 'PASSED')` instead of the hard-coded 27-id list `config_fw_only.json` carries (that list is one per-run subset of the same set); `venv` → `fuzzware_gdma`; project root renamed |
| `04_hoedur.json` | `configs/config_hoedur_on_firmxray.json` | constraint `id IN (SELECT id FROM hoedur_admission_checks_v2 WHERE result = 'PASSED')`, i.e. the server's admission gate (its admission configs are `configs/config_*_admission.json`) |
| `05_multifuzz.json` | `configs/config_multifuzz_allinone.json` | constraint `id IN (SELECT id FROM multifuzz_admission_checks_v2 WHERE result = 'PASSED')`; `venv` → `fuzzware_gdma` (the gateway the server's MultiFuzz config uses) |
| `06_firmrca.json` | `configs/3_firmxray_on_firmrca.json` | crash source view `firmxray_fuzzware_replay_crashes` (this repo) instead of `firmxray_on_gdma_replay_crashes`; `classifiedMode: false` for the first pass; `dbImports` reduced to `firmxray_results.base_address` |
| `06b_firmrca_classify.json` | derived from the same server config | second pass with `classifiedMode: true` and `dbImports` including `firmrca_classified_replays.paths` |

The server also carries GDMA (DMA-modelling) variants of the fuzzware configs
(`configs/config_fw_on_gdma*.json`, image `fuzzware:fuzzware-hoedur-eval`) and the
admission-test configs. They are *not* part of this repository — if they are
needed, the same pattern applies: one config per stage, path-substituted.

## 5. Module build (findings)

`scripts/build_akiba_modules.py` builds the analysis modules from source and is what
the image uses. Three observations worth keeping:

* `moduleJar-ALL` cannot work on a clean tree: `akiba_modules/build.gradle.kts`
  resolves each module's inter-module dependencies to
  `build/libs/amod-<name>-<version>.jar` **while creating the task** (it reads them
  with `JarInputStream`), so a module can only be built once its dependencies exist.
  Hence the topological batching in the driver.
* Seeding the reference build's JARs does not help either: they were compiled with a
  newer Kotlin (metadata 2.3.0) and the 2.1.20 compiler declared by these sources
  refuses them — `Module was compiled with an incompatible version of Kotlin. The
  binary version of its metadata is 2.3.0, expected version is 2.1.0`.
* `CortexEmulator` and `EnhancedFunctionFinder` are excluded by the build script
  itself (`deprecatedModules` / `underDevelopmentModules`) — no task is registered,
  the driver skips them. `P2IMGateway` / `P2IMRunner` fail to compile against the
  Ghidra 12.0.4 SDK (`Unresolved reference 'definedStrings'`, i.e.
  `ghidra.program.util.DefinedDataIterator.definedStrings` is absent in that
  release), so the driver treats them as optional (warning, not failure) and the
  stage installs the JARs that ship in `framework/prebuilt-modules/` instead — the
  two now run as stage `03b_p2im` (see `docs/pipeline.md`).  That stage is a related-work baseline: it
  is fed the corpus **directly**, and the outcome it is meant to record is that the dataset does not
  run on P²IM at all.
* How the reference ran P²IM — two configurations, both without the admission premise:
  `config_gen_elf_for_firmxray.json` (`ConvertFirmToELF` only, forked from the analysed-base project,
  selecting binaries with `base_address IS NOT NULL` and sampling every 45th by size up to 100) and then
  `config_p2im.json` (`P2IMGateway` → `P2IMRunner`, forked from that `convert_firm_to_elf` project,
  `WHERE id IN (SELECT id FROM convert_elf_results WHERE elf_path IS NOT NULL)`,
  `dbImports: convert_elf_results.elf_path`, `p2imRoot: /data/hongyuan/p2im`,
  `P2IMGateway.projectRoot: p2im_on_real_fw`, `P2IMRunner.pythonPath` pointing at its own conda env
  `p2im`, `timeoutSeconds: 3600` with a task `timeout: 4000`, writing `p2im_fuzzing_results_v2`).  The
  artifact runs both halves in one config (`03b_p2im.json`), keeps the module's own table name
  `p2im_fuzzing_results` instead of that experiment suffix, and points `p2imRoot` at the container's
  `/data/tools/p2im`.
* What the reference measured: `p2im_on_real_fw/` holds 2,468 firmware work trees, and its run log
  (8.25 MB) contains 2,468 `P2IM Runner Finished!` lines — **all of them `Crashes: 0, Hangs: 0`**, after
  spending the full 3,600 s budget each, with 4–56 basic blocks of coverage.  Every firmware P²IM's QEMU
  would start (board guessed as `NUCLEO-F103RB`/`STM32F103RB` on the sample inspected) therefore yields
  nothing on this corpus: that is the evidence the paper cites, not a defect of the harness.  P²IM's
* The interpreter, which is subtler than it looks.  P2IM's `fuzz.py` imports the standard library
  only, but at *API* level it needs a Python older than 3.12: it calls `configparser.SafeConfigParser`
  (removed in 3.12), and its helper `me.py` is reached through its own `#!/usr/bin/env python3` shebang.
  The reference's `p2im` conda env (Python 3.8.20) satisfied both, and `P2IMRunner` reproduces the
  effect by deriving the child's `PATH` from `dirname(pythonPath)`.  Pinning `P2IMRunner.pythonPath` to
  `/usr/bin/python3.8` therefore does *not* work: `python3` on that path is still 3.12, `me.py` dies
  silently, no `0/peripheral_model.json` is written, the fuzzed QEMU exits while loading the model, and
  AFL's `Fork server handshake failed` reaches the module as "P2IM aborted early. This usually
  indicates an unsupported board/MCU configuration." — a message that points nowhere near the cause.
  The artifact gives 3.8 a bin directory of its own (`~/p2im-py38/bin`, created by
  `scripts/setup_tools.sh` and by the runtime image) and points `P2IMRunner.pythonPath` at it.
* The stage is two passes here, as it was on the reference, and for two measured reasons: the ELF pass
  must run with `threads: 1` (`ConvertFirmToELF` unpacks ELFBuilder to a fixed `/tmp/ELFBuilder`, so
  concurrent tasks fail with `Text file busy (FileNotFound)`), and the fuzzing pass's `dbImports` are
  resolved at run start, so `convert_elf_results.elf_path` has to be in the database already (a merged
  config fails the second task with `Key convert_elf_results.elf_path is missing`).  With the split, the
  container produces 33 `parsed_elfs`, 33 work trees and 33 `p2im_fuzzing_results` rows on the
  34-firmware fixture set - 32 `FAILED_EARLY_ABORT`, 1 `SUCCESS_TIMEOUT`, all with 0 crashes and 0 hangs -
  which is the reference's corpus-wide result (`Crashes: 0, Hangs: 0`) in miniature.  A failed firmware
  does **not** abort the run: all 33 firmware were attempted after the first failure.
* How µEmu was run — `uEmu-test` is a harness around the µEmu snapshot (MCUSec/uEmu): `pipeline.py` runs cfg → kb → fuzz → analyze → coverage, one batch per
  seed, and classifies each firmware (正常fuzz / 提前退出 / 卡死).  The reference deployment never
  carried µEmu — no config, no module, nothing under `/data` — so unlike P²IM there is no module or JAR
  variant to reconcile; the artifact drives the harness directly (`scripts/run_uemu_test.sh`, stage
  id `03c`).  The harness is **vendored** into this repository (a verbatim copy of
  Orantree957/uEmu-test at `b8f8500e4830fe525ed089d58040e233a96bdfcc`, which is a private
  repository, so a submodule pointer could not be fetched by a reviewer) while the µEmu snapshot it
  builds against stays a submodule (`MCUSec/uEmu`).  Comparing the vendored tree against the
  upstream commit blob by blob: 968 entries on both sides, 966 identical, and the only two
  differences are `README.md` and `pipeline.yaml`, whose Chinese documentation was translated to
  English.  All code, firmware, configs, logs and CSVs are byte-identical; a `.gitattributes` entry
  (`-text -diff`) keeps the copy from being end-of-line normalised, which otherwise rewrites the
  reference's CRLF CSVs.
* What the container reproduces, and where it stops — on two corpus ELFs the harness derives 539-byte
  configs (`rom = 0x08000000,0x20000`, `ram = 0x20000000,0x50000`, `vtor = 0x08000000`) against the
  reference's 544-byte ones, and renders `launch-uEmu.sh` (2,569 B), `uEmu-config.lua` and `library.lua`
  per firmware.  `kb` then reports 无KB: the snapshot is source only — no `build/`, no patched
  `AFL/afl-fuzz` — and building it needs S2E plus KVM, which a container cannot provide.  The
  firmware-level verdict therefore comes from the harness's own record: in the smoke batch shipped with
  it, KB extraction succeeds (`有KB`, 3.8–9.7 s) and all three firmwares end `提前退出`
  (`end_type = early`, `fuzzer:dry-run种子crash`) with the evidence line
  `Test case 'id:000000,orig:seed_alternating.bin' results in a crash`.
* Three interpreter traps, the same class the P²IM stage documents (a tool upstream ran on one specific
  host): µEmu's `uEmu-helper.py` calls `configparser.SafeConfigParser`, removed in Python 3.12 while the
  container's default `python3` is 3.14, so the harness must run on ≤ 3.11; it needs Jinja2 as well as
  the PyYAML its README lists; and `pipeline.py` invokes that helper as a bare `python3`, so the chosen
  interpreter has to sit on PATH as `python3` — the same shape as the P²IM `p2im-py38` bin directory.
* Two host facts the reference simply had and a container does not.  Its kernel ran `core_pattern=core`,
  whereas a container inherits the host's (this host: `|/usr/share/apport/apport -p%p ...`), which makes
  AFL 2.06b refuse to start — so `docker/docker-compose.yml` and the Dockerfile export AFL's own escape
  hatch, `AFL_I_DONT_CARE_ABOUT_MISSING_CRASHES=1` and `AFL_SKIP_CPUFREQ=1`.  And the staged QEMU needs
  the X11/GL client libraries (`libx11-6`, `libxext6`, `libxrender1`, `libxi6`, `libxkbcommon0`,
  `libgl1`, `libegl1`, `libpixman-1-0`); the image carries them and `scripts/setup_tools.sh` installs
  them when it finds a container built without them.

Result of a full run on the build host: **36 `amod-*.jar`** — every module the
pipeline stages in `pipelines/` reference — against the reference deployment's 40
(the difference being the P2IM pair, `CortexEmulator`, `EnhancedFunctionFinder` and
the two example modules). With `03b_p2im` the pipeline needs the P2IM pair as well;
those two come from `framework/prebuilt-modules/`.

### Module JARs that read a file out of themselves

Two modules extract a file from their own JAR at runtime: `ConvertFirmToELF` unpacks the C++
`ELFBuilder` binary (`ELFBuilder/cmake-build-debug/ELFBuilder`) and `FirmRCA` unpacks
`generateDataset.py`.  The Jar task takes the first one from
`build/resources/ConvertFirmToELF/ELFBuilder/cmake-build-debug/ELFBuilder`, i.e. from
`framework/build/`, which is git-ignored; without it the module dies inside `extractFileInJar`
with `ELFBuilder/cmake-build-debug/ELFBuilder not found in
modules/amod-ConvertFirmToELF-1.2.jar`, which `ProcedureManager` reports as "latter tasks skipped"
with no hint about the cause.

The compiled helper therefore ships **in this repository**, at the path the module's own resource
packaging picks up: `subprojects/akiba_modules/src/ConvertFirmToELF/resources/ELFBuilder/cmake-build-debug/ELFBuilder`
— a statically linked x86-64 ELF (3,450,632 bytes, sha256
`ebd031b70ace29cb35b93bcaabd3f7af66efdff21ef37c59462bb59ebf52e86b`), taken from the reference JAR
`amod-ConvertFirmToELF-1.2.jar`, where it had been built with CMake/ninja (`main.cpp`,
`ELFLoader.cpp` and a `CMakeLists.txt` sit next to it in that resources tree).  Static linking means
it does not depend on the container's glibc.  The directory is named in the module's own
`.gitignore` (`cmake-build-debug`) because upstream builds the helper locally, so the file is added
with `git add -f`; the Jar task then packages it through the ordinary resource set — no build step has
to copy it anywhere.  Verified: the rebuilt `amod-ConvertFirmToELF-1.2.jar` contains the entry
`ELFBuilder/cmake-build-debug/ELFBuilder` (3,450,632 bytes), the same path the reference JAR has.
`FirmRCA`'s `generateDataset.py` is a tracked module resource
(`src/FirmRCA/resources/`) and is packaged by the rebuild without any staging.  The install step
still prefers the reference JARs when they are present (on the reference machine, or for
byte-comparison), but no pipeline stage depends on them any more.

### Note on directory names

The paths in this section (`/data/hongyuan/...`) are the **reference server's**
paths, quoted as they were — they are provenance, not the layout of this
repository's container.  Here the same content lives at `/data/tools/<tool>` for the
tool checkouts and `/data/akiba` for the akiba work data
(`binaries`, `ghidra_projects`), see `docker/docker-compose.yml`.
