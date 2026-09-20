#!/usr/bin/env bash
# One-shot run of the whole artifact: initialise the container, provision the six
# evaluated tools, fetch the sample set and run the full pipeline.
#
#   scripts/quickstart.sh                       # quick test: 5 hard-coded samples, 2 min fuzzing
#   scripts/quickstart.sh --full                # every sample, the shipped 1 h fuzz budget
#   scripts/quickstart.sh --fuzz-time 5m        # quick test with a different budget
#   scripts/quickstart.sh --samples 804,3349    # quick test on other ids
#   scripts/quickstart.sh --build               # rebuild the image first
#   scripts/quickstart.sh --keep-state          # do not wipe the pipeline state
#   scripts/quickstart.sh --skip-samples        # do not touch evaluation_samples/
#   scripts/quickstart.sh --list-stages         # show the pipeline stages and exit
#
# What it does, in order:
#   1. build the image if it is missing (or always with --build)
#   2. start the container: first boot initialises postgres, creates the akiba instance
#   3. provision the six tools (skipped when they are already provisioned; the
#      provisions live in named volumes, so this is a one-time cost per volume set)
#   4. obtain samples: the 23 empirical-study images that ship in empirical_study/ are
#      always used; when SAMPLES_URL / --samples-url is given, the full sample set is
#      downloaded from Google Drive first (evaluation_framework/scripts/fetch_samples_gdrive.sh)
#   5. import the sample selection (5 ids by default, everything with --full)
#   6. run the pipeline: 00b pre-analysis -> 01 FirmXRay -> 03/04/05 fuzzers ->
#      06/06b FirmRCA -> 02 Firmline
#   7. export the result tables to evaluation_results/db/ and print a summary
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# ---------------------------------------------------------------- the quick-test sample selection
# Small ids from the empirical study (md5-verified against
# dataset_identification_and_reconstruction/binaries_md5.csv).  They cover the cases the
# paper discusses: 804 (A-group NPE), 1179, 224, 227 (vulnerability cases) and 3349
# (the FirmRCA limitation case).  Keep them small — they are imported and fuzzed per run.
DEFAULT_SAMPLES=(804 1179 224 227 3349)
STAGE_ORDER="00b,01,03,04,05,06,06b,02"
QUICK_FUZZ_TIME="2m"

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
    -h|--help)       sed -n '2,25p' "$0"; exit 0 ;;
    *) die "unknown option $1" ;;
  esac
done
[ "$FULL" = 1 ] && { FUZZ_TIME="" ; }              # --full keeps the shipped one-hour budget
[ -z "$FUZZ_TIME" ] && [ "$FULL" = 0 ] && FUZZ_TIME="$QUICK_FUZZ_TIME"

step()  { echo; c_blue "=== $* ==="; }
ok()    { c_green "    ok: $*"; }
have_tool() { docker exec "$CONTAINER" test -e "$1" 2>/dev/null; }

# ---------------------------------------------------------------- 1. image
step "1/7 image"
if [ "$BUILD" = 1 ] || ! docker image inspect akiba_allinone:3.1.2 >/dev/null 2>&1; then
  bash "$(dirname "${BASH_SOURCE[0]}")/build.sh" || die "image build failed"
else
  ok "akiba_allinone:3.1.2 present (use --build to rebuild)"
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
  docker exec "$CONTAINER" bash -lc 'command -v r2 >/dev/null'    || missing+=(radare2)
  if [ ${#missing[@]} -eq 0 ]; then
    ok "all six tools already provisioned"
  else
    c_blue "    provisioning: ${missing[*]} (long: radare2/conda/venvs are in the image and volumes already)"
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
mkdir -p "$GEN_DIR"
if [ "$FULL" = 1 ]; then
  LIST="$GEN_DIR/import_list.json"
  python3 - "$REPO_ROOT/evaluation_samples" "$LIST" <<'PY'
import json, pathlib, sys
root, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
files = sorted(p for p in root.rglob('*.bin') if p.is_file())
out.write_text(json.dumps({"entries": [{"path": str(p.relative_to(root))} for p in files]}, indent=2) + "\n")
print(f"    {len(files)} sample(s) selected (full corpus)")
PY
else
  LIST="$GEN_DIR/import_list_quick.json"
  [ -n "$SAMPLES_ARG" ] && DEFAULT_SAMPLES=($SAMPLES_ARG)
  python3 - "$REPO_ROOT/evaluation_samples" "$LIST" "${DEFAULT_SAMPLES[@]}" <<'PY'
import json, pathlib, sys
root, out, ids = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3:]
entries = []
for i in ids:
    for cand in (root / 'empirical_study_samples' / f'{i}.bin', root / 'firmware' / f'{i}.bin'):
        if cand.exists():
            entries.append({"path": str(cand.relative_to(root))}); break
    else:
        sys.exit(f"sample {i} not found under {root}")
out.write_text(json.dumps({"entries": entries}, indent=2) + "\n")
print(f"    {len(entries)} sample(s) selected: {' '.join(ids)}")
PY
fi
CONTAINER_LIST="/data/results/generated/$(basename "$LIST")"
step "importing"
docker exec -i "$CONTAINER" bash -lc "cd $FRAMEWORK && ./bin/akiba_framework \
  -c /data/pipelines/00_import.json@/main -i $CONTAINER_LIST" 2>&1 | tail -3
bash "$(dirname "${BASH_SOURCE[0]}")/export_results.sh" >/dev/null 2>&1 || true
printf '    binaries in database: %s\n' "$([ -f "$REPO_ROOT/evaluation_results/db/binaries.csv" ] && echo $(( $(wc -l < "$REPO_ROOT/evaluation_results/db/binaries.csv") - 1 )))"

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
         multifuzz_results firmrca_results firmline_results; do
  f="$REPO_ROOT/evaluation_results/db/$t.csv"
  if [ -f "$f" ]; then printf '    %-40s %5s rows\n' "$t" "$(( $(wc -l < "$f") - 1 ))"
  else printf '    %-40s (no table)\n' "$t"; fi
done
echo
c_green "results in evaluation_results/db/*.csv, logs in evaluation_results/logs/"
[ "$rc" = 0 ] || c_red "the pipeline reported failures — check the stage logs above"
exit $rc
