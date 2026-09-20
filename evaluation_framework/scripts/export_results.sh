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

n=0
while read -r name kind; do
  [ -n "$name" ] || continue
  out="$RESULTS_DIR/db/${name}.csv"
  if "${PSQL[@]}" -c "\\copy (SELECT * FROM \"$name\") TO '$out' WITH (FORMAT csv, HEADER true)" 2>/dev/null; then
    rows=$(( $(wc -l < "$out") - 1 ))
    printf '%-42s %-10s %6s rows\n' "$name" "$kind" "$rows"
    n=$((n + 1))
  else
    c_red "failed to export $name"
  fi
done <<< "$OBJECTS"

echo "$n object(s) exported to $RESULTS_DIR/db"

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
