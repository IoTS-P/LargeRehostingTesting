#!/usr/bin/env bash
# Provision the six evaluated tools *inside* the container.
#
#   scripts/setup_tools.sh                 # everything that is missing
#   scripts/setup_tools.sh --check         # only report what is provisioned
#   scripts/setup_tools.sh gdma hoedur    # only the named tools
#
# Every tool is provisioned into its bind-mounted directory under /data/tools,
# so the (long) builds survive a container rebuild.  Logs: results/logs/setup_<tool>.log.
# The steps are the ones documented by each tool plus the adjustments observed in
# the reference container; where a step is fragile it prints exactly what to check.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

TOOLS_ROOT=/data/tools
SETUP_LOG_DIR="$RESULTS_DIR/logs"

if ! in_container; then
  require_container
  exec docker exec -i "$CONTAINER" bash -lc "bash /opt/rehosting/scripts/setup_tools.sh $*"
fi

ALL_TOOLS=(firmxray firmline fuzzware hoedur multifuzz firmrca)
CHECK_ONLY=0
REQUESTED=()
for arg in "$@"; do
  case "$arg" in
    --check) CHECK_ONLY=1 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) REQUESTED+=("$arg") ;;
  esac
done
[ ${#REQUESTED[@]} -eq 0 ] && REQUESTED=("${ALL_TOOLS[@]}")

mkdir -p "$SETUP_LOG_DIR"
export WORKON_HOME=/home/akiba/.virtualenvs
export VIRTUALENVWRAPPER_PYTHON=/usr/bin/python3

# ---------------------------------------------------------------- helpers
run_logged() { # name cmd...
  local name="$1"; shift
  local log="$SETUP_LOG_DIR/setup_${name}.log"
  c_blue "    \$ $*"
  if [ "$CHECK_ONLY" = 1 ]; then return 0; fi
  if "$@" >>"$log" 2>&1; then
    return 0
  else
    c_red "    step failed — see $log"
    tail -15 "$log" | sed 's/^/      /'
    return 1
  fi
}

have() { command -v "$1" >/dev/null 2>&1; }

# virtualenvwrapper: fuzzware's and gdma's install_local.sh abort unless
# `which virtualenvwrapper.sh` succeeds, and they call mkvirtualenv right after — so the
# wrapper has to be on PATH *and* its python module importable.  The packaged script is
# 0644, i.e. a plain symlink still fails `which` (it checks the execute bit), hence the
# executable copy.  Sets VW_SH.
VW_SH=/usr/share/virtualenvwrapper/virtualenvwrapper.sh
prepare_virtualenvwrapper() {
  [ -f "$VW_SH" ] || { c_red "    $VW_SH missing (virtualenvwrapper is not installed in the image)"; return 1; }
  if ! have virtualenvwrapper.sh; then
    run_logged virtualenvwrapper sudo install -m 755 "$VW_SH" /usr/local/bin/virtualenvwrapper.sh || return 1
  fi
  "$VIRTUALENVWRAPPER_PYTHON" -c "import virtualenvwrapper" 2>/dev/null \
    || run_logged virtualenvwrapper sudo -H "$VIRTUALENVWRAPPER_PYTHON" -m pip install virtualenvwrapper
  have virtualenvwrapper.sh || { c_red "    virtualenvwrapper.sh is still not on PATH"; return 1; }
}

# ---------------------------------------------------------------- FirmXRay
provision_firmxray() {
  local root="$TOOLS_ROOT/RealworldFirmware/FirmXRay"
  local marker="$root/out/main/Main.class"
  if [ -e "$marker" ] && [ "$CHECK_ONLY" = 1 ]; then c_green "firmxray: built"; return 0; fi
  c_blue "==> FirmXRay (java static analysis, needs the ghidra.jar SDK)"
  [ -d "$root" ] || { c_red "    $root missing (submodule not checked out)"; return 1; }
  [ "$CHECK_ONLY" = 1 ] && { c_blue "    would symlink /opt/ghidra-jar/ghidra.jar and run make"; return 0; }

  # the pipeline calls `java -cp out:lib/ghidra.jar:lib/json.jar main.Main`
  # (the SDK jar must be world-readable: javac runs as akiba, the jar ships root:root 0640)
  sudo chmod a+r /opt/ghidra-jar/ghidra.jar 2>/dev/null || true
  ln -sf /opt/ghidra-jar/ghidra.jar "$root/lib/ghidra.jar"
  run_logged firmxray make -C "$root" clean || true
  run_logged firmxray make -C "$root" || return 1
  [ -e "$marker" ] && c_green "firmxray: ok" || { c_red "firmxray: $marker missing"; return 1; }
}

# ---------------------------------------------------------------- Firmline
provision_firmline() {
  local root="$TOOLS_ROOT/firmline"
  c_blue "==> Firmline (python pipeline + radare2/bgrep/binwalk + ghidra headless)"
  [ -d "$root" ] || { c_red "    $root missing"; return 1; }
  [ "$CHECK_ONLY" = 1 ] && { c_blue "    check: $root/fwdb.db, bgrep, radare2, conda env firmline"; return 0; }

  # 1. python dependencies (the module requires python 3.10 or 3.11).
  # requirements.txt pins the local, patched binwalk (binwalk==2.3.3+a555eb1) which is
  # not on PyPI — it is installed from the vendored source in step 5, so drop that pin here.
  # The conda environment is root-owned, hence sudo.
  grep -v '^binwalk==' "$root/requirements.txt" > /tmp/firmline_requirements.txt
  run_logged firmline sudo /opt/conda/envs/firmline/bin/pip install -r /tmp/firmline_requirements.txt || return 1
  run_logged firmline sudo /opt/conda/envs/firmline/bin/pip install psutil || true

  # 2. nested submodules (binwalk, bgrep, cpu_rec, radare2)
  for sub in binwalk bgrep cpu_rec radare2; do
    [ -e "$root/$sub/.git" ] || c_red "    $root/$sub is not checked out — run scripts/setup.sh --with-nested on the host"
  done

  # 3. bgrep — the Firmline module hard-codes /usr/local/opt/bgrep/usr/bin/bgrep
  if [ ! -x /usr/local/opt/bgrep/usr/bin/bgrep ]; then
    run_logged firmline sudo make -C "$root/bgrep" install PREFIX=/usr/local/opt/bgrep \
      || c_red "    bgrep install failed — it must end up at /usr/local/opt/bgrep/usr/bin/bgrep"
  fi

  # 4. radare2 — the image builds it from upstream (Dockerfile stage 1b), so this is
  #    normally a no-op.  The vendored tree is only a fallback: it must be built with its
  #    own capstone fork (a system capstone makes anal_arm_cs.c fail on ARM64_INS_ADRP).
  if ! have r2 && [ ! -x /usr/local/bin/radare2 ]; then
    c_blue "    radare2 missing from the image — building the vendored copy as a fallback"
    [ -d "$root/radare2/shlr/capstone" ] || run_logged firmline make -C "$root/radare2/shlr" capstone-sync \
      || c_red "    radare2 capstone-sync failed (needs network)"
    run_logged firmline bash -c "cd '$root/radare2' && sudo sys/install.sh" || c_red "    radare2 install failed"
  fi
  if have r2 || [ -x /usr/local/bin/radare2 ]; then
    c_green "    radare2: $("r2" -v 2>/dev/null | head -1)"
  else
    c_red "    radare2 still missing — the Firmline module needs it"
  fi

  # 5. binwalk (legacy python2 setup.py; the patched .gitmodules points it at the vendored copy)
  run_logged firmline bash -c "cd '$root/binwalk' && sudo /opt/conda/envs/firmline/bin/python setup.py install" \
    || c_red "    binwalk install failed (needs distutils; use 'pip install .' if setup.py is unusable)"

  # 6. database + config
  [ -f "$root/fwdb.db" ] || run_logged firmline bash -c "cd '$root' && sqlite3 fwdb.db < schema.sql"
  if [ ! -f "$root/config.ini" ] && [ -f "$root/config.ini.bak_full" ]; then
    cp "$root/config.ini.bak_full" "$root/config.ini"
    c_blue "    config.ini created from config.ini.bak_full"
  fi
  # The result database is not tracked by firmline's repository (the authors ship it in
  # their release archive) and pipeline.py never creates it, so ensure the schema exists —
  # `schema.sql` when the checkout carries it, otherwise the tool's own models — and clear
  # the rows, because a record whose processed copy is gone poisons every later run.
  run_logged firmline /opt/conda/envs/firmline/bin/python3 /opt/rehosting/scripts/_reset_firmline_db.py \
    || c_red "    could not prepare firmline's fwdb.db"
  [ -f "$root/config.ini" ] && c_green "firmline: ok" || c_red "firmline: config.ini missing"
}

# ---------------------------------------------------------------- Fuzzware (vanilla, main branch)
provision_fuzzware() {
  local root="$TOOLS_ROOT/fuzzware"
  c_blue "==> Fuzzware (creates the virtualenvs 'fuzzware' and 'fuzzware-modeling')"
  [ -d "$root" ] || { c_red "    $root missing"; return 1; }
  if [ -d "$WORKON_HOME/fuzzware" ] && [ "$CHECK_ONLY" = 1 ]; then c_green "fuzzware: venv present"; return 0; fi
  [ "$CHECK_ONLY" = 1 ] && { c_blue "    would run install_local.sh (needs network)"; return 0; }

  export WORKON_HOME VIRTUALENVWRAPPER_PYTHON
  # install_local.sh / modeling/setup.sh are patched (python 3.10 + 3.8, setuptools<58,
  # --no-build-isolation), see patches/README.md.  install_local.sh aborts unless
  # `which virtualenvwrapper.sh` succeeds, and it calls mkvirtualenv afterwards, so both
  # the script and the python module have to be in place (the reference container had them
  # from its own install; here the provisioning runs non-interactively).
  prepare_virtualenvwrapper
  run_logged fuzzware bash -c "source '$VW_SH' && cd '$root' && ./install_local.sh" || return 1
  [ -d "$WORKON_HOME/fuzzware" ] && [ -d "$WORKON_HOME/fuzzware-modeling" ] \
    && c_green "fuzzware: ok (venvs fuzzware, fuzzware-modeling)" \
    || c_red "fuzzware: venvs missing, check the log"
  link_venv_package fuzzware "$root"
  ensure_fuzzware_python_deps
  link_tool_clis
}

# ---------------------------------------------------------------- GDMA (fuzzware, DMA branch)
provision_gdma() {
  local root="$TOOLS_ROOT/gdma"
  c_blue "==> GDMA (fuzzware on the DMA branch; creates the virtualenvs 'fuzzware_gdma' and 'fuzzware-modeling')"
  [ -d "$root" ] || { c_red "    $root missing"; return 1; }
  if [ -d "$WORKON_HOME/fuzzware_gdma" ] && [ "$CHECK_ONLY" = 1 ]; then c_green "gdma: venv present"; return 0; fi
  [ "$CHECK_ONLY" = 1 ] && { c_blue "    would run install_local.sh (needs network)"; return 0; }

  export WORKON_HOME VIRTUALENVWRAPPER_PYTHON
  # install_local.sh is patched (venv name fuzzware_gdma, python 3.10), see patches/README.md;
  # it has the same `which virtualenvwrapper.sh` check as fuzzware
  prepare_virtualenvwrapper
  run_logged gdma bash -c "source '$VW_SH' && cd '$root' && ./install_local.sh" || return 1
  [ -d "$WORKON_HOME/fuzzware_gdma" ] && [ -d "$WORKON_HOME/fuzzware-modeling" ] \
    && c_green "gdma: ok (venvs fuzzware_gdma, fuzzware-modeling)" \
    || c_red "gdma: venvs missing, check the log"
  link_venv_package fuzzware_gdma "$root"
  link_tool_clis
}

# ---------------------------------------------------------------- Hoedur
provision_hoedur() {
  local root="$TOOLS_ROOT/hoedur"
  c_blue "==> Hoedur (rust + qemu 7.1.0 build; the akiba module drives cargo run --bin …)"
  [ -d "$root" ] || { c_red "    $root missing"; return 1; }
  [ "$CHECK_ONLY" = 1 ] && { c_blue "    check: $root/target/release, qemu-7.1.0.tar.xz"; return 0; }

  # the patched qemu-sys/build.rs expects the tarball next to the crate and rewrites the
  # built library with patchelf
  have patchelf || run_logged hoedur sudo apt-get install -y patchelf || return 1
  if [ ! -f "$root/qemu-sys/qemu-7.1.0.tar.xz" ]; then
    c_blue "    fetching qemu-7.1.0.tar.xz (121 MB)"
    run_logged hoedur curl -fL --retry 3 -o "$root/qemu-sys/qemu-7.1.0.tar.xz" \
      https://download.qemu.org/qemu-7.1.0.tar.xz || return 1
  fi
  run_logged hoedur bash -c "cd '$root' && /home/akiba/.cargo/bin/cargo build --release" || return 1
  run_logged hoedur sudo cp "$root/target/release/libqemu-system-arm.release.so" /usr/lib/ || true
  link_hoedur_bins
  c_green "hoedur: built ($(ls "$root/target/release" 2>/dev/null | grep -c '^hoedur') hoedur binaries)"
}

# ---------------------------------------------------------------- MultiFuzz
provision_multifuzz() {
  local root="$TOOLS_ROOT/MultiFuzz"
  c_blue "==> MultiFuzz (rust; the akiba module runs 'cargo run --release -- <workdir>')"
  [ -d "$root" ] || { c_red "    $root missing"; return 1; }
  [ "$CHECK_ONLY" = 1 ] && { c_blue "    check: $root/target/release"; return 0; }
  [ -e "$root/ghidra/.git" ] || c_red "    ghidra submodule missing — run scripts/setup.sh --with-nested on the host"
  run_logged multifuzz bash -c "cd '$root' && /home/akiba/.cargo/bin/cargo build --release" || return 1
  c_green "multifuzz: ok"
}

# ---------------------------------------------------------------- FirmRCA
provision_firmrca() {
  local root="$TOOLS_ROOT/FirmRCA"
  c_blue "==> FirmRCA (capstone, capnproto, pomp; own venv for its fuzzware fork)"
  [ -d "$root" ] || { c_red "    $root missing"; return 1; }
  [ "$CHECK_ONLY" = 1 ] && { c_blue "    check: $root/FirmRCA-fuzzware/bin/activate, src/src/*, libcapnproto.so"; return 0; }

  # 1. python venv used by the module (firmRCAPythonVenvRoot)
  if [ ! -f "$root/FirmRCA-fuzzware/bin/activate" ]; then
    run_logged firmrca python3 -m venv "$root/FirmRCA-fuzzware" || return 1
    run_logged firmrca "$root/FirmRCA-fuzzware/bin/pip" install --upgrade pip setuptools wheel
    run_logged firmrca "$root/FirmRCA-fuzzware/bin/pip" install -r "$root/requirements.txt"
    run_logged firmrca "$root/FirmRCA-fuzzware/bin/pip" install matplotlib pandas pyyaml openpyxl
    # FirmRCA drives the fuzzware *emulator* from this repository, not the upstream one
    run_logged firmrca bash -c "cd '$root/fuzzware-emulator' && '$root/FirmRCA-fuzzware/bin/pip' install -e ." \
      || c_red "    installing the in-tree fuzzware-emulator failed — see README of FirmRCA"
  fi

  # 2. capstone as a system library.  FirmRCA's sources use `insn->detail->writeback`,
  #    which the headers of Ubuntu's capstone 4.0.2 do not expose (they keep the flag in
  #    the per-architecture struct); the README pins upstream capstone to
  #    622059530f172b1570a424e3f7ef5fda8c00dab0 and builds it with ./make.sh.
  #    The checkout lives under $HOME/build (a writable place: /opt is root-owned and the
  #    tool's own directory must not gain an untracked submodule-like tree).
  CAPSTONE_COMMIT=622059530f172b1570a424e3f7ef5fda8c00dab0
  CAPSTONE_DIR="$HOME/build/capstone"
  CAPSTONE_STAMP=/usr/local/.capstone-pinned
  if [ ! -e "$CAPSTONE_STAMP" ]; then
    # the distro dev package has to go *first*: apt deletes files by package list, so
    # removing it after our install would delete the freshly installed headers
    run_logged firmrca sudo apt-get remove -y libcapstone-dev || true
    if [ ! -d "$CAPSTONE_DIR/.git" ]; then
      run_logged firmrca git clone https://github.com/capstone-engine/capstone.git "$CAPSTONE_DIR" \
        || { c_red "    cloning capstone failed (network?)"; return 1; }
    fi
    run_logged firmrca git -C "$CAPSTONE_DIR" reset --hard "$CAPSTONE_COMMIT" || return 1
    run_logged firmrca bash -c "cd '$CAPSTONE_DIR' && ./make.sh" || { c_red "    capstone build failed"; return 1; }
    run_logged firmrca bash -c "cd '$CAPSTONE_DIR' && sudo ./make.sh install" || { c_red "    capstone install failed"; return 1; }
    # make.sh installs into /usr (PREFIX default), not /usr/local
    if [ ! -f /usr/include/capstone/capstone.h ]; then
      c_red "    capstone install left no /usr/include/capstone/capstone.h"
      return 1
    fi
    sudo touch "$CAPSTONE_STAMP"
  fi

  # 3. capnproto + c-capnproto + the trace library
  # README step 3: gcc the trace library and drop it into src/lib.  There is no
  # autogen.sh in test_c_capnproto (it is a hand-written C library, the generated
  # bintrace.capnp.c/.h are committed), so only the compile step is needed.
  run_logged firmrca bash -c "cd '$root/test_c_capnproto' && gcc *.c -I./ -shared -fPIC -o libcapnproto.so && sudo cp -f libcapnproto.so '$root/src/lib/'" \
    || c_red "    capnproto trace library build failed (gcc/glibc headers?)"
  run_logged firmrca cp "$root/test_c_capnproto/libcapnproto.so" "$root/src/lib/" || true

  # 4. pompplusplus (backward taint analysis).  The akiba module calls
  #    <root>/src/src/reversenolog (see FirmRCA.kt), which is what the default
  #    bin_PROGRAMS list of src/src/Makefile.am builds; there is no `pomp`/`firmrca`
  #    binary in this version, so reversenolog is the marker.
  if [ ! -x "$root/src/src/reversenolog" ]; then
    # `all-am` in src/Makefile.am lists a `README` that the checkout does not ship, so
    # make stops with "No rule to make target 'README', needed by 'all-am'"
    [ -f "$root/src/README" ] || touch "$root/src/README"
    run_logged firmrca bash -c "cd '$root/src' && ./autogen.sh && ./configure && make" \
      || c_red "    FirmRCA build failed — see FirmRCA README step 4 (autogen/configure need LF endings)"
  fi
  [ -x "$root/src/src/reversenolog" ] || { c_red "    $root/src/src/reversenolog missing after the build"; return 1; }
  c_green "firmrca: provisioned (verify with: scripts/status.sh)"
}

# ---------------------------------------------------------------- CLI exposure
# The akiba modules spawn *bare* command names in a non-interactive shell
# ("fuzzware pipeline …", "fuzzware cov -p …", "fuzzware replay …"), and the venv
# bin directories are not on that shell's PATH.  The process then exits in ~0 ms
# with "command not found" and the stage silently reports an empty result:
# fuzzware's actual_fuzz_time stays 0 and no <workdir>/pipeline project appears
# (FuzzwareGateway.getCovInfo then throws "Project directory … not found"), while
# hoedur's statistics task throws "No corpus file found".
# Linking every console script of the two fuzzware virtualenvs into /usr/local/bin
# fixes that for all shells: /usr/local/bin is on the default PATH and lives in a
# volume, so the links survive image rebuilds.  The DMA virtualenv exposes the same
# console-script names, so its CLI is linked under the distinct name fuzzware_gdma.
# The fuzzware pipeline is installed *non-editably* (this pip has no
# --no-use-pep517 and the PEP-660 backend needs setuptools>=64), so the installed
# copy has none of the sibling checkout directories that fuzzware_pipeline resolves
# relative to its own file: the generic seed corpus is looked up as
# <pkg>/../data/base_inputs and the AFL++ toolchain as <pkg>/../../emulator/afl.
# Neither exists in that install, so `fuzzware pipeline` aborts with
# "Could not find any base inputs directory." or
# "No such file or directory: .../emulator/afl/afl-cmin" — after the tool has
# spent minutes emulating — and the stage stores an empty result.
# Replacing the installed package directory with a symlink into the checkout gives
# every one of those relative paths its intended target (and keeps the code live,
# so patches in the checkout take effect without reinstalling).
link_venv_package() {
  local venv="$1" root="$2" SP
  SP=$(ls -d "$WORKON_HOME/$venv"/lib/python*/site-packages 2>/dev/null | head -1)
  [ -n "$SP" ] || { c_red "    $venv: site-packages not found"; return 1; }
  [ -d "$root/pipeline/fuzzware_pipeline" ] || { c_red "    $root/pipeline/fuzzware_pipeline missing"; return 1; }
  sudo rm -rf "$SP/fuzzware_pipeline"
  sudo ln -sfn "$root/pipeline/fuzzware_pipeline" "$SP/fuzzware_pipeline"
  c_green "    linked site-packages/fuzzware_pipeline -> $root/pipeline/fuzzware_pipeline"
}

# The akiba modules drive hoedur through scripts/fuzz.py, which spawns the fuzzer by its
# bare name ("hoedur-arm").  The binaries are built into <hoedur>/target/release, which is
# not on PATH.  Without the links the fuzz task dies instantly with
# FileNotFoundError: 'hoedur-arm' (written to <workdir>/console.txt) and the statistics
# task then fails with "No corpus file found".
link_hoedur_bins() {
  local rel="$TOOLS_ROOT/hoedur/target/release" n=0 b
  [ -d "$rel" ] || return 0
  for b in "$rel"/hoedur-*; do
    [ -x "$b" ] || continue
    sudo ln -sf "$b" "/usr/local/bin/$(basename "$b")" && n=$((n+1))
  done
  [ "$n" -gt 0 ] && c_green "    linked $n hoedur binary(ies) into /usr/local/bin"
}

# The akiba modules drive hoedur through scripts/fuzz.py *inside the fuzzware
# virtualenv*, whose dependencies do not include psutil: without it the fuzz stage
# dies with ModuleNotFoundError and the statistics task then fails with
# "No corpus file found".
ensure_fuzzware_python_deps() {
  [ -x "$WORKON_HOME/fuzzware/bin/pip" ] || return 0
  run_logged fuzzware "$WORKON_HOME/fuzzware/bin/pip" install --no-input -q psutil PyYAML || true
}

link_tool_clis() {
  local v bin exe b linked=0
  for v in fuzzware fuzzware_gdma; do
    bin="$WORKON_HOME/$v/bin"
    [ -d "$bin" ] || continue
    for exe in "$bin"/*; do
      [ -x "$exe" ] && [ ! -d "$exe" ] || continue
      b=$(basename "$exe")
      case "$b" in python*|pip*|wheel|easy_install*|activate*|pydoc*|virtualenv*) continue ;; esac
      if [ "$v" = fuzzware_gdma ] && [ "$b" = fuzzware ]; then b=fuzzware_gdma; fi
      sudo ln -sf "$exe" "/usr/local/bin/$b" && linked=$((linked+1))
    done
  done
  [ "$linked" -gt 0 ] && c_green "    linked $linked CLI(s) from the venvs into /usr/local/bin"
}

# ---------------------------------------------------------------- driver
rc=0
for tool in "${REQUESTED[@]}"; do
  case "$tool" in
    firmxray)  provision_firmxray  || rc=1 ;;
    firmline)  provision_firmline  || rc=1 ;;
    fuzzware)  provision_fuzzware  || rc=1 ;;
    gdma)      provision_gdma      || rc=1 ;;
    hoedur)    provision_hoedur    || rc=1 ;;
    multifuzz) provision_multifuzz || rc=1 ;;
    firmrca)   provision_firmrca   || rc=1 ;;
    *) die "unknown tool '$tool' (choose from: ${ALL_TOOLS[*]})" ;;
  esac
done
exit $rc
