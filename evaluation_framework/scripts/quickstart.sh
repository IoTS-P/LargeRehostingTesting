#!/usr/bin/env bash
# One-shot run of the whole artifact: initialise the container, provision the six
# evaluated tools, fetch the sample set and run the full pipeline.
#
#   scripts/quickstart.sh                       # quick test: the fixed 20 admitted-firmware samples, 1 h fuzzing
#   scripts/quickstart.sh --full                # every sample, the shipped 1 h fuzz budget
#   scripts/quickstart.sh --fuzz-time 5m        # quick test with a different budget
#   scripts/quickstart.sh --samples 804,3349    # quick test on other ids
#   scripts/quickstart.sh --build               # rebuild the image first
#   scripts/quickstart.sh --keep-state          # do not wipe the pipeline state
#   scripts/quickstart.sh --skip-samples        # do not touch evaluation_samples/
#   scripts/quickstart.sh --list-stages         # show the pipeline stages and exit
#
# Inside the container the same script also works, in place: it imports the fixed 20 images (matched by
# file name, so it does not matter which surrogate ids the instance gave them), runs every stage over
# exactly those files and exports the tables.  Nothing is wiped there, so it is safe to run while results
# already exist.  That mode accepts only --fuzz-time (default 1h); the host-side options above need the
# host.
#
# What it does, in order:
#   1. build the image if it is missing (or always with --build)
#   2. start the container: first boot initialises postgres, creates the akiba instance
#   3. provision the six tools (skipped when they are already provisioned; the
#      provisions live in named volumes, so this is a one-time cost per volume set)
#   4. obtain samples: a tree already present in evaluation_samples/ is used as is; with
#      SAMPLES_URL / --samples-url the corpus is downloaded from Google Drive first
#      (evaluation_framework/scripts/fetch_samples_gdrive.sh)
#   5. import the sample selection: the fixed 20 images that passed the admission tests of all three
#      fuzzing tools (--samples <ids> for a different set, everything with --full)
#   6. run the pipeline in the paper's four stages: Stage 1 Reconnaissance (00b, 01, 02) ->
#      Stage 2 Emulation (02b) -> Stage 3 Security Testing (03/04/05 plus the two related-work
#      baselines, 03b P²IM and 03c µEmu) -> Stage 4 Diagnosis (06/06b)
#   7. export the result tables to evaluation_results/db/ and print a summary
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# quickstart drives the container from outside: it calls `docker exec` and writes host paths under
# $REPO_ROOT.  Inside the container it instead runs the same fixed 20 images in place (see
# run_in_container below); the host options need docker.
IN_CONTAINER=0
in_container && IN_CONTAINER=1
if [ "$IN_CONTAINER" = 0 ] && ! command -v docker >/dev/null 2>&1; then
  c_red "quickstart.sh: not inside the container, and docker is not reachable."
  exit 1
fi

# ---------------------------------------------------------------- in-container flow
# One restricted copy of a stage config: the constraint selects the fixed images by *file name*
# (split_part, no regex and no surrogate ids), and the fuzzing budget is rewritten where the stage has one.
restrict_config() {   # $1 source config, $2 destination, $3 fuzz budget (empty = leave the budget alone)
  python3 - "$1" "$2" "${3:-}" "${DEFAULT_SAMPLES[@]}" <<'PY'
import json, re, sys
src, dst, spec, ids = sys.argv[1], sys.argv[2], sys.argv[3], [i for i in sys.argv[4:] if i]
cfg = json.load(open(src))
cfg["main"]["sqlSource"]["constraint"] = (
    "WHERE split_part(original_path, '/', -1) IN (%s)" % ", ".join("'%s.bin'" % i for i in ids))
if spec:
    m = re.fullmatch(r"(\d+)\s*([smhd]?)", spec.strip())
    if not m:
        sys.exit(f"cannot parse --fuzz-time '{spec}' (use e.g. 300s, 10m, 2h)")
    n, unit = int(m.group(1)), m.group(2) or "s"
    secs = n * {"s": 1, "m": 60, "h": 3600, "d": 86400}[unit]
    budget = {
        "FirmXRayOnFuzzware": ("maxTimeout", "%02d:%02d:%02d:%02d" % (
            secs // 86400, secs % 86400 // 3600, secs % 3600 // 60, secs % 60)),
        "HoedurFuzz": ("maxTimeoutMinutes", f"{max(1, secs // 60)}m"),
        "MultiFuzz": ("runFor", f"{secs}s"),
        "P2IMRunner": ("timeoutSeconds", secs),
    }
    for task, (field, value) in budget.items():
        if task in cfg and cfg[task].get(field) is not None:
            cfg[task][field] = value
json.dump(cfg, open(dst, "w"), indent=2)
open(dst, "a").write("\n")
PY
}

run_in_container() {   # $1 fuzzing budget
  local fuzz_time="$1"
  local gen="$RESULTS_DIR/generated" log="$RESULTS_DIR/logs"
  mkdir -p "$gen/pipelines" "$log" || return 1
  local n=${#DEFAULT_SAMPLES[@]} rc_all=0 t0 s id cfg budget
  c_blue "=== quickstart (inside the container): the fixed $n images, fuzzing budget $fuzz_time ==="

  # 1. the import list, resolved against the sample tree mounted here
  python3 - "$gen/import_list_quick.json" "$AKIBA_ARCH" "$SAMPLES_DIR" "${DEFAULT_SAMPLES[@]}" <<'PY'
import json, pathlib, sys
out, arch, root, ids = pathlib.Path(sys.argv[1]), sys.argv[2], pathlib.Path(sys.argv[3]), sys.argv[4:]
entries = []
for i in ids:
    for cand in (root / "empirical_study_samples" / f"{i}.bin", root / "firmware" / f"{i}.bin"):
        if cand.is_file():
            entries.append({"path": str(cand.relative_to(root)), "arch": arch}); break
    else:
        sys.exit(f"sample {i} not found under {root} - the corpus must be mounted at {root}")
json.dump({"entries": entries}, open(out, "w"), indent=2); open(out, "a").write("\n")
print(f"    {len(entries)} sample(s): {' '.join(ids)}")
PY
  [ $? -eq 0 ] || { c_red "could not write the import list"; return 1; }

  # 2. import: skips the files the instance already holds, by checksum
  cd "$FRAMEWORK" || return 1
  t0=$(date +%s)
  c_blue "==> importing $n image(s) (log: $log/00_import.log)"
  ./bin/akiba_framework -c /data/pipelines/00_import.json@/main -i "$gen/import_list_quick.json" 2>&1 \
    | tee -a "$log/00_import.log"
  if [ "${PIPESTATUS[0]}" -ne 0 ]; then
    c_red "import failed — see $log/00_import.log"; return 1
  fi
  c_green "    import done in $(( $(date +%s) - t0 ))s"

  # 3. the stages, in the pipeline order, each on its own restricted copy
  local stages=( "00b|00b_analyze.json" "01|01_firmxray.json" "02b|02b_admission.json"
                 "03|03_fuzzware.json" "03b|03b_p2im_elf.json" "03b|03b_p2im.json"
                 "03c|run_uemu_test.sh" "04|04_hoedur.json" "05|05_multifuzz.json"
                 "06|06_firmrca.json" "06b|06b_firmrca_classify.json" "02|02_firmline.json" )
  for s in "${stages[@]}"; do
    IFS='|' read -r id cfg <<<"$s"
    if [ "$cfg" = "run_uemu_test.sh" ]; then
      c_blue "==> [$id] µEmu (direct run, over the parsed ELFs of the selection)"
      bash "$SCRIPT_DIR/run_uemu_test.sh" --fuzz-time "$fuzz_time" 2>&1 | tee -a "$log/03c_uemu_test.log" \
        || { c_red "[$id] FAILED (log: $log/03c_uemu_test.log)"; rc_all=1; }
      continue
    fi
    [ -f "/data/pipelines/$cfg" ] || { c_red "missing /data/pipelines/$cfg"; rc_all=1; continue; }
    budget=""
    case "$cfg" in 03_fuzzware.json|04_hoedur.json|05_multifuzz.json|03b_p2im.json) budget="$fuzz_time" ;; esac
    if ! restrict_config "/data/pipelines/$cfg" "$gen/pipelines/$cfg" "$budget"; then
      c_red "[$id] could not prepare the restricted config"; rc_all=1; continue
    fi
    c_blue "==> [$id] $cfg (the fixed $n only${budget:+, budget $budget})"
    ./bin/akiba_framework -c "$gen/pipelines/$cfg@/main" 2>&1 \
      | tee -a "$log/${id}_$(basename "$cfg" .json).log" \
      || { c_red "[$id] FAILED (log: $log/${id}_$(basename "$cfg" .json).log)"; rc_all=1; }
  done

  # 4. export, exactly as the host flow does
  c_blue "==> exporting to $RESULTS_DIR/db"
  bash "$SCRIPT_DIR/export_results.sh" || rc_all=1
  [ "$rc_all" -eq 0 ] && c_green "quickstart (in container) finished" \
                      || c_red "one or more stages reported failures — see $log"
  return "$rc_all"
}

# ---------------------------------------------------------------- the quick-test sample selection
# The quick run uses a fixed set of QUICK_SAMPLES ids (DEFAULT_SAMPLES below), so every clone evaluates the
# same images and two quick runs are directly comparable.  Those ids were drawn at random from the firmware
# that passed the admission tests of all three fuzzing tools when the selection was measured, which is what
# makes every stage of the four-stage pipeline actually run on every selected image - an image that admission
# rejects is skipped by the three fuzzing stages.  Pass --samples <ids> to evaluate a different set.
QUICK_SAMPLES=20
# The quick run used to draw its samples at random; it now uses this fixed set.  Each id passed the admission
# tests of all three fuzzing tools when the selection was measured (the full passing set is kept in
# evaluation_framework/data/admission_triple_pass_ids.txt), which is what makes all three fuzzing stages
# actually run: an image that admission rejects is skipped by them.
DEFAULT_SAMPLES=(209 274 1330 15756 2669 3412 2974 12299 3480 6625 2651 7883 16868 1748 11115 11990 2442 509 3141 2863)
STAGE_ORDER="00b,01,02b,03,03b,03c,04,05,06,06b,02"
QUICK_FUZZ_TIME="1h"

FULL=0; BUILD=0; FRESH=1; DO_SAMPLES=1; SKIP_PROVISION=0
SAMPLES_ARG=""; FUZZ_TIME=""; SAMPLES_URL=""
while [ $# -gt 0 ]; do
  case "$1" in
    --full)          FULL=1; shift ;;
    --build)         BUILD=1; shift ;;
    --keep-state)    FRESH=0; shift ;;
    --skip-samples)  DO_SAMPLES=0; shift ;;
    --skip-provision) SKIP_PROVISION=1; shift ;;
    --samples)       SAMPLES_ARG="${2//,/ }"; shift 2 ;;
    --fuzz-time)     FUZZ_TIME="$2"; shift 2 ;;
    --samples-url)   SAMPLES_URL="$2"; shift 2 ;;
    --list-stages)   bash "$(dirname "${BASH_SOURCE[0]}")/run_pipeline.sh" --list; exit 0 ;;
    -h|--help)       sed -n '2,/^set -uo pipefail/p' "$0" | sed '$d'; exit 0 ;;
    *) die "unknown option $1" ;;
  esac
done
[ "$FULL" = 1 ] && { FUZZ_TIME="" ; }              # --full keeps the shipped one-hour budget
[ -z "$FUZZ_TIME" ] && [ "$FULL" = 0 ] && FUZZ_TIME="$QUICK_FUZZ_TIME"

# Inside the container there is no image to build and no state to wipe, so only the fuzzing budget applies.
if [ "$IN_CONTAINER" = 1 ]; then
  unsupported=""
  [ "$FULL" = 1 ]           && unsupported="$unsupported --full"
  [ "$BUILD" = 1 ]          && unsupported="$unsupported --build"
  [ "$FRESH" = 0 ]          && unsupported="$unsupported --keep-state"
  [ "$DO_SAMPLES" = 0 ]     && unsupported="$unsupported --skip-samples"
  [ "$SKIP_PROVISION" = 1 ] && unsupported="$unsupported --skip-provision"
  [ -n "$SAMPLES_ARG" ]     && unsupported="$unsupported --samples"
  [ -n "$SAMPLES_URL" ]     && unsupported="$unsupported --samples-url"
  if [ -n "$unsupported" ]; then
    c_red "inside the container quickstart.sh accepts only --fuzz-time (got:$unsupported)"
    exit 1
  fi
  run_in_container "${FUZZ_TIME:-$QUICK_FUZZ_TIME}"
  exit $?
fi

step()  { echo; c_blue "=== $* ==="; }
ok()    { c_green "    ok: $*"; }
have_tool() { docker exec "$CONTAINER" test -e "$1" 2>/dev/null; }

# ---------------------------------------------------------------- 1. image
step "1/7 image"
if [ "$BUILD" = 1 ] || ! docker image inspect akiba_for_artifacts:3.1.2 >/dev/null 2>&1; then
  bash "$(dirname "${BASH_SOURCE[0]}")/build.sh" || die "image build failed"
else
  ok "akiba_for_artifacts:3.1.2 present (use --build to rebuild)"
fi

# ---------------------------------------------------------------- 2. container
step "2/7 container"
if [ "$FRESH" = 1 ]; then
  compose down >/dev/null 2>&1 || true
  # only the pipeline state is dropped: the tool provisions (home/local/conda volumes)
  # and the sample corpus stay, so a re-run is quick
  docker volume rm -f largerehosting_akiba_state largerehosting_akiba_data >/dev/null 2>&1 || true
  c_blue "    pipeline state wiped (database, ghidra/fuzzware projects); use --keep-state to keep it"
fi
bash "$(dirname "${BASH_SOURCE[0]}")/up.sh" || die "container did not come up"
ok "container running: $(docker ps --format '{{.Names}}' | grep -x "$CONTAINER")"

if [ "$FRESH" = 1 ]; then
  # Firmline keeps its own result database (fwdb.db) plus processed copies inside its
  # checkout.  Those survive the pipeline wipe, and a row whose processed file is gone
  # makes the next run abort with
  #   FileNotFoundError: processed-firmware/blobs/<name>_tmpforFirmline
  # (firmline treats the file as a duplicate and compares it against the missing copy).
  # With the pipeline state wiped, drop that tool state as well; --keep-state keeps both.
  # ... but the *file* must stay: it carries the schema firmline expects (see
  # _reset_firmline_db.py).  Clear the rows and the processed copies instead.
  docker exec "$CONTAINER" python3 /opt/rehosting/scripts/_reset_firmline_db.py \
    && c_blue "    firmline tool state reset (rows cleared, schema kept)"
fi

# ---------------------------------------------------------------- 3. tools
step "3/7 tool provisioning"
if [ "$SKIP_PROVISION" = 1 ]; then
  ok "skipped (--skip-provision)"
else
  missing=()
  have_tool /data/tools/RealworldFirmware/FirmXRay/out/main/Main.class || missing+=(firmxray)
  have_tool /home/akiba/.virtualenvs/fuzzware/bin/fuzzware        || missing+=(fuzzware)
  have_tool /home/akiba/.virtualenvs/fuzzware_gdma/bin/fuzzware   || missing+=(gdma)
  have_tool /data/tools/hoedur/target/release/libqemu-system-arm.release.so || missing+=(hoedur)
  have_tool /data/tools/MultiFuzz/target/release/multifuzz        || missing+=(multifuzz)
  have_tool /data/tools/FirmRCA/src/src/reversenolog              || missing+=(firmrca)
  have_tool /data/tools/p2im/afl/afl-fuzz                         || missing+=(p2im)
  have_tool /data/tools/uEmu-test/pipeline.py                     || missing+=(uemu)
  docker exec "$CONTAINER" bash -lc 'command -v r2 >/dev/null'    || missing+=(radare2)
  if [ ${#missing[@]} -eq 0 ]; then
    ok "all evaluated and related-work tools already provisioned"
  else
    c_blue "    provisioning: ${missing[*]} (long: radare2/conda/venvs are in the image and volumes already)"
    # setup_tools.sh builds the tools inside the container, but it needs their sources on disk first, and
    # checking those out is a host-side job: on a fresh clone each of these directories is just an empty
    # gitlink, so provisioning failed on the first thing it touched.  Run the checkout here instead of
    # sending the user back to the README; setup.sh is idempotent, so a populated tree costs only seconds.
    # (Testing a few trees rather than the tools/ directory itself: uEmu-test is vendored into the clone and
    # would make that directory look non-empty on a fresh checkout.)
    unchecked=""
    for t in fuzzware hoedur MultiFuzz p2im FirmRCA firmline; do
      [ -n "$(ls -A "$REPO_ROOT/evaluated_tools_and_configurations/tools/$t" 2>/dev/null)" ] || unchecked="$unchecked $t"
    done
    if [ -n "$unchecked" ]; then
      c_blue "    tool trees not checked out yet:$unchecked — running setup.sh --with-nested (host side)"
      bash "$(dirname "${BASH_SOURCE[0]}")/setup.sh" --with-nested || die "tool checkout failed"
    fi
    bash "$(dirname "${BASH_SOURCE[0]}")/setup_tools.sh" || die "tool provisioning failed"
    ok "tools provisioned"
  fi
fi

# ---------------------------------------------------------------- 4. samples
step "4/7 samples"
if [ "$DO_SAMPLES" = 1 ]; then
  if [ -n "$SAMPLES_URL" ]; then
    bash "$(dirname "${BASH_SOURCE[0]}")/fetch_samples_gdrive.sh" "$SAMPLES_URL" \
      || c_red "    Google Drive download failed — continuing with the bundled samples"
  else
    c_blue "    no --samples-url given: using the 23 empirical-study images that ship with the artifact"
    c_blue "    (for the full corpus: scripts/quickstart.sh --samples-url <google-drive-url>)"
  fi
  # The repository ships no firmware: the corpus comes from Google Drive (see
  # evaluation_samples/README.md).  A sample tree that is already on disk — downloaded
  # once with --samples-url, or restored locally — is used as is.
  STUDY_DIR="$REPO_ROOT/evaluation_samples/empirical_study_samples"
  if [ -d "$STUDY_DIR" ] && [ -n "$(ls -A "$STUDY_DIR" 2>/dev/null)" ]; then
    ok "sample tree present ($(ls "$STUDY_DIR"/*.bin 2>/dev/null | wc -l) files in empirical_study_samples/)"
  elif [ -n "$SAMPLES_URL" ]; then
    ok "samples taken from the Google Drive download"
  else
    c_red "    no samples on disk: run once with --samples-url <google-drive-url> (see evaluation_samples/README.md)"
  fi
fi

# ---------------------------------------------------------------- 5. import list
step "5/7 import selection"
GEN_DIR="$REPO_ROOT/evaluation_results/generated"
mkdir -p "$GEN_DIR" 2>/dev/null || true
# Same failure mode as import_samples.sh: a root-owned directory or list file here (an earlier root-context
# run) turns into a python PermissionError traceback three seconds later.  Probe the directory by creating
# a file in it, and the list separately: a writable directory can still hold a file this user cannot
# truncate.
probe_failed=""
touch "$GEN_DIR/.write-probe" 2>/dev/null || probe_failed="$GEN_DIR"
[ -z "$probe_failed" ] && { : >>"$GEN_DIR/import_list.json" 2>/dev/null || probe_failed="$GEN_DIR/import_list.json"; }
rm -f "$GEN_DIR/.write-probe"
if [ -n "$probe_failed" ]; then
  c_red "cannot write $probe_failed — evaluation_results/ is not writable by $(id -un)"
  c_red "  either restart the container (it repairs the results tree at startup), or:"
  c_red "    sudo chown -R $(id -u):$(id -g) evaluation_results"
  exit 1
fi
if [ "$FULL" = 1 ]; then
  LIST="$GEN_DIR/import_list.json"
  python3 - "$AKIBA_ARCH" "$REPO_ROOT/evaluation_samples" "$LIST" <<'PY'
import json, pathlib, sys
arch, root, out = sys.argv[1], pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3])
files = sorted(p for p in root.rglob('*.bin') if p.is_file())
out.write_text(json.dumps({"entries": [{"path": str(p.relative_to(root)), "arch": arch} for p in files]}, indent=2) + "\n")
print(f"    {len(files)} sample(s) selected (full corpus, arch {arch})")
PY
else
  LIST="$GEN_DIR/import_list_quick.json"
  # The default is the fixed set; --samples overrides it.  Both take the same path, which resolves each id
  # against the empirical-study copies first and the corpus second, and names the id when it is not there.
  [ -n "$SAMPLES_ARG" ] || SAMPLES_ARG="${DEFAULT_SAMPLES[*]}"
  DEFAULT_SAMPLES_IDS=($SAMPLES_ARG)
  python3 - "$AKIBA_ARCH" "$REPO_ROOT/evaluation_samples" "$LIST" "${DEFAULT_SAMPLES_IDS[@]}" <<'PY'
import json, pathlib, sys
arch, root, out, ids = sys.argv[1], pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3]), sys.argv[4:]
entries = []
for i in ids:
    for cand in (root / 'empirical_study_samples' / f'{i}.bin', root / 'firmware' / f'{i}.bin'):
        if cand.exists():
            entries.append({"path": str(cand.relative_to(root)), "arch": arch}); break
    else:
        sys.exit(f"sample {i} not found under {root} - the quick run needs the corpus "
                 f"(see evaluation_samples/README.md)")
out.write_text(json.dumps({"entries": entries}, indent=2) + "\n")
print(f"    {len(entries)} sample(s) selected: {' '.join(ids)} (arch {arch})")
PY
fi
CONTAINER_LIST="/data/results/generated/$(basename "$LIST")"
step "importing"
[ -s "$LIST" ] || { c_red "    the import list was not written: $LIST"; exit 1; }
entries=$(python3 -c "import json;print(len(json.load(open('$LIST'))['entries']))" 2>/dev/null || echo '?')
IMPORT_LOG="$REPO_ROOT/evaluation_results/logs/00_import.log"
mkdir -p "$(dirname "$IMPORT_LOG")" 2>/dev/null || true
c_blue "    ${entries} firmware file(s) -> the akiba instance (log: $IMPORT_LOG)"
# The framework is a JVM: without a console it buffers stdout in blocks, so a long import prints nothing at
# all until it exits, which reads as a hang.  `docker exec -t` gives it a TTY, the tee keeps the log, and
# because a quiet framework still tells you nothing the elapsed time and the number of files stored so far
# are printed every 20 s.  pipefail is set, so the pipeline reports the framework's status, not tee's.
import_started=$(date +%s)
docker exec -t "$CONTAINER" bash -lc "cd $FRAMEWORK && ./bin/akiba_framework \
  -c /data/pipelines/00_import.json@/main -i $CONTAINER_LIST" 2>&1 | tee -a "$IMPORT_LOG" &
import_pid=$!
# Sleep in short slices so the exit is noticed within a couple of seconds (a single `sleep 20` would make
# every import end with up to 20 s of dead time), while the progress line still appears about every 20 s.
while kill -0 "$import_pid" 2>/dev/null; do
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    sleep 2
    kill -0 "$import_pid" 2>/dev/null || break 2
  done
  printf '    [%4ss] stored in the instance: %s\n' "$(( $(date +%s) - import_started ))" \
    "$(docker exec "$CONTAINER" bash -lc 'ls /data/akiba/binaries/00_import 2>/dev/null | wc -l' 2>/dev/null || echo '?')"
done
wait "$import_pid"; rc=$?
printf '    import finished in %ss (exit %s)\n' "$(( $(date +%s) - import_started ))" "$rc"
if [ "$rc" != 0 ]; then
  c_red "    import failed (exit $rc) — can the container read $CONTAINER_LIST? (log: $IMPORT_LOG)"
  exit 1
fi
bash "$(dirname "${BASH_SOURCE[0]}")/export_results.sh" >/dev/null 2>&1 || true
binaries=$([ -f "$REPO_ROOT/evaluation_results/db/binaries.csv" ] && echo $(( $(wc -l < "$REPO_ROOT/evaluation_results/db/binaries.csv") - 1 )) || echo 0)
printf '    binaries in database: %s\n' "$binaries"
[ "${binaries:-0}" -gt 0 ] || { c_red "    the database holds no binaries after the import — stopping here"; exit 1; }

# ---------------------------------------------------------------- 6. pipeline
step "6/7 pipeline (stages $STAGE_ORDER)"
if [ -n "$FUZZ_TIME" ]; then
  c_blue "    fuzzing budget: $FUZZ_TIME per firmware per fuzzer (quick test)"
  bash "$(dirname "${BASH_SOURCE[0]}")/run_pipeline.sh" --only "$STAGE_ORDER" --fuzz-time "$FUZZ_TIME"
else
  c_blue "    fuzzing budget: the shipped 1 h per firmware per fuzzer (full run)"
  bash "$(dirname "${BASH_SOURCE[0]}")/run_pipeline.sh" --only "$STAGE_ORDER"
fi
rc=$?

# ---------------------------------------------------------------- 7. results
step "7/7 results"
for t in binaries firmxray_results firmxray_on_fuzzware_results \
         firmxray_on_fuzzware_replay_results firmxray_fuzzware_replay_crashes \
         firmxray_on_fuzzware_stat_results hoedur_fuzz_results hoedur_statistics_results \
         multifuzz_results p2im_fuzzing_results firmrca_results firmline_results; do
  f="$REPO_ROOT/evaluation_results/db/$t.csv"
  if [ -f "$f" ]; then printf '    %-40s %5s rows\n' "$t" "$(( $(wc -l < "$f") - 1 ))"
  else printf '    %-40s (no table)\n' "$t"; fi
done
# 03c writes no database table: the uEmu-test harness classifies in its own CSVs
if [ -f "$REPO_ROOT/evaluation_results/uemu_test/results.csv" ]; then
  printf '    %-40s %5s rows (CSV: 正常fuzz/提前退出/卡死)\n' "uemu_test/results.csv" \
    "$(( $(wc -l < "$REPO_ROOT/evaluation_results/uemu_test/results.csv") - 1 ))"
else printf '    %-40s (not run)\n' "uemu_test/results.csv"; fi
echo
c_green "results in evaluation_results/db/*.csv, logs in evaluation_results/logs/"
[ "$rc" = 0 ] || c_red "the pipeline reported failures — check the stage logs above"
exit $rc
