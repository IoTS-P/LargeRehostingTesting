# Provenance — where every piece of this repo comes from

The pipeline was developed and evaluated inside a container on
`222.20.126.138` (user `akiba`, host container hostname `6bae1969f827`). That
container is the reference for this repository; nothing here was re-invented.

Access used while building this repo (read-only on the server):

```bash
sshpass -e ssh -p 31779 akiba@222.20.126.138
```

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
* Ghidra 12.0.4 (ghidra.jar, used by akiba) **and** Ghidra 11.3.2 (used by
  Firmline as `ghidraHome`, matching `/data/tools/ghidra_11.3.2_PUBLIC`),
* sshd, so the container can be driven like the server (host port 31779).

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
  release); they belong to the P2IM tool, not to the six evaluated here, so the
  driver treats them as optional (warning, not failure).

Result of a full run on the build host: **36 `amod-*.jar`** — every module the
pipeline stages in `pipelines/` reference — against the reference deployment's 40
(the difference being the P2IM pair, `CortexEmulator`, `EnhancedFunctionFinder` and
the two example modules).

### Note on directory names

The paths in this section (`/data/hongyuan/...`) are the **reference server's**
paths, quoted as they were — they are provenance, not the layout of this
repository's container.  Here the same content lives at `/data/tools/<tool>` for the
tool checkouts and `/data/akiba` for the akiba work data
(`binaries`, `ghidra_projects`), see `docker/docker-compose.yml`.
