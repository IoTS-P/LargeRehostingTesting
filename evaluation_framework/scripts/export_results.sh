#!/usr/bin/env bash
# Dump everything the pipeline produced.
#
#   scripts/export_results.sh                 # database tables + views -> results/db/*.csv
#   scripts/export_results.sh --with-artifacts  # also mirror the per-firmware project trees
#
# The akiba database lives in a PostgreSQL instance owned by the db daemon; the
# instance name -> port mapping is written by the daemon to /akiba/.akiba/instances.json
# on first start, so the port is discovered at run time instead of hard-coded.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

WITH_ARTIFACTS=0
[ "${1:-}" = "--with-artifacts" ] && WITH_ARTIFACTS=1

if ! in_container; then
  require_container
  exec docker exec -i "$CONTAINER" bash -lc "bash /opt/rehosting/scripts/export_results.sh ${WITH_ARTIFACTS:+--with-artifacts}"
fi

mkdir -p "$RESULTS_DIR/db" "$RESULTS_DIR/logs"

MAP=/akiba/.akiba/instances.json
[ -f "$MAP" ] || die "instance map $MAP not found — is the container initialised?"

PORT=$(python3 - "$MAP" "$DB_INSTANCE" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))
name = sys.argv[2]
if name not in m:
    sys.exit(f"instance {name} not found in {sys.argv[1]}: {list(m)}")
print(m[name]["port"])
PY
) || die "could not determine the port of instance $DB_INSTANCE"

OWNER=$(python3 - "$MAP" "$DB_INSTANCE" <<'PY'
import json, sys
print(json.load(open(sys.argv[1]))[sys.argv[2]]["owner"])
PY
)

c_blue "==> exporting instance '$DB_INSTANCE' (port $PORT, owner $OWNER) to $RESULTS_DIR/db"

export PGPASSWORD="${AKIBA_DB_PASSWORD:-akiba}"
PSQL=(psql -h 127.0.0.1 -p "$PORT" -U "$OWNER" -d "$DB_INSTANCE" -v ON_ERROR_STOP=1 -q -t -A)

# every base table and view of the public schema
OBJECTS=$("${PSQL[@]}" -c "SELECT table_name || ' ' || table_type FROM information_schema.tables WHERE table_schema='public' ORDER BY table_type, table_name" 2>/dev/null) \
  || die "cannot connect to the akiba instance database on port $PORT"

# Each result table is exported as <name>_stage_<N>.csv, so a file name says which stage of the
# paper it belongs to.  The prefix is the tool (or the module that produced the table); the
# *_admission_checks_v2 tables are the seed-level detail behind *_admission_results.
declare -A FILE_FOR=(
  # stage 1 - reconnaissance
  [firmxray_results]="firmxray_stage_1.csv"
  [firmline_results]="firmline_stage_1.csv"
  [convert_elf_results]="convert_elf_stage_1.csv"
  # stage 2 - emulation admission
  [fuzzware_admission_results]="fuzzware_admission_stage_2.csv"
  [hoedur_admission_results]="hoedur_admission_stage_2.csv"
  [multifuzz_admission_results]="multifuzz_admission_stage_2.csv"
  [fuzzware_admission_checks_v2]="fuzzware_admission_seeds_stage_2.csv"
  [hoedur_admission_checks_v2]="hoedur_admission_seeds_stage_2.csv"
  [multifuzz_admission_checks_v2]="multifuzz_admission_seeds_stage_2.csv"
  [p2im_fuzzing_results]="p2im_stage_2.csv"
  # stage 3 - security testing
  [firmxray_on_fuzzware_results]="fuzzware_stage_3.csv"
  [firmxray_on_fuzzware_gdma_results]="gdma_stage_3.csv"
  [firmxray_on_fuzzware_gdma_replay_results]="gdma_replay_stage_3.csv"
  [firmxray_on_fuzzware_gdma_stat_results]="gdma_statistics_stage_3.csv"
  [firmxray_on_fuzzware_stat_results]="fuzzware_statistics_stage_3.csv"
  [firmxray_on_fuzzware_replay_results]="fuzzware_replay_stage_3.csv"
  [firmxray_fuzzware_replay_crashes]="fuzzware_replay_crashes_stage_3.csv"
  [hoedur_fuzz_results]="hoedur_stage_3.csv"
  [hoedur_statistics_results]="hoedur_statistics_stage_3.csv"
  [hoedur_replay_data]="hoedur_replay_stage_3.csv"
  [multifuzz_results]="multifuzz_stage_3.csv"
  # stage 4 - diagnosis
  [firmrca_results]="firmrca_stage_4.csv"
  [firmrca_classified_results]="firmrca_classified_stage_4.csv"
  [firmrca_sampling]="firmrca_sampling_stage_4.csv"
)

# Tables that carry no result worth publishing: pure-analysis modules that only record that they ran
# (function_finder), framework bookkeeping (db_backup_tree, example_table, results, processed_binaries)
# The replay view is a real result and is exported.  They are still created by the
# pipeline; only the export skips them.
declare -A SKIP=(
  [function_finder_results]=1
  [program_initialization_results]=1   # module bookkeeping only, no result columns
  [string_adder_results]=1             # same
  [db_backup_tree]=1
  [example_table]=1
  [results]=1
  [processed_binaries]=1
  [firmrca_classified_replays]=1   # helper view the second FirmRCA pass selects from
)

n=0
skipped=0
PRODUCED=()
while read -r name kind; do
  [ -n "$name" ] || continue
  if [ -n "${SKIP[$name]:-}" ]; then
    skipped=$((skipped + 1))
    continue
  fi
  # the import tables (binaries, using_binaries) belong to no stage and keep their own name
  out="$RESULTS_DIR/db/${FILE_FOR[$name]:-${name}.csv}"
  if "${PSQL[@]}" -c "\\copy (SELECT * FROM \"$name\") TO '$out' WITH (FORMAT csv, HEADER true)" 2>/dev/null; then
    rows=$(( $(wc -l < "$out") - 1 ))
    PRODUCED+=("$(basename "$out")")
    printf '%-38s %-10s %6s rows  -> %s\n' "$name" "$kind" "$rows" "$(basename "$out")"
    n=$((n + 1))
  else
    c_red "failed to export $name"
  fi
done <<< "$OBJECTS"

# drop CSVs from earlier runs that this export did not produce (renamed or now-skipped tables)
stale=0
produced_list=" ${PRODUCED[*]} "
while IFS= read -r f; do
  b="$(basename "$f")"
  case "$produced_list" in
    *" $b "*) ;;
    *) rm -f "$f"; stale=$((stale + 1)) ;;
  esac
done < <(find "$RESULTS_DIR/db" -maxdepth 1 -name '*.csv' -type f)

echo "$n object(s) exported to $RESULTS_DIR/db ($skipped skipped: no result to publish, $stale stale file(s) removed)"

# summary file: what was exported, from which instance, and the run timestamps
{
  echo "exported_at: $(date -Is)"
  echo "instance:    $DB_INSTANCE (port $PORT, owner $OWNER)"
  echo "samples:     $(find "$SAMPLES_DIR" -type f ! -name '.gitkeep' | wc -l) file(s)"
  echo "objects:"
  ls -1 "$RESULTS_DIR/db" | sed 's/^/  /'
} > "$RESULTS_DIR/export_manifest.txt"

if [ "$WITH_ARTIFACTS" = 1 ]; then
  c_blue "==> mirroring per-firmware project trees (crash inputs are kept, memac dumps are not)"
  for project in fuzzware_projects hoedur_projects multifuzz_projects; do
    src="/data/akiba/binaries/$project"
    [ -d "$src" ] || continue
    dst="$RESULTS_DIR/artifacts/$project"
    mkdir -p "$dst"
    timeout 600 rsync -a --exclude='*.bin' --exclude='*.memac*' --exclude='*.so' "$src/" "$dst/" \
      && echo "  $project -> $dst ($(du -sh "$dst" 2>/dev/null | cut -f1))" \
      || c_red "  rsync of $project failed or timed out"
  done
fi

c_green "export finished"
