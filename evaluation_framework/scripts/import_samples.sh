#!/usr/bin/env bash
# Import every firmware file under evaluation_samples/ into the akiba instance.
#
#   scripts/import_samples.sh          # host: runs inside the container
#   scripts/import_samples.sh --list   # only show what would be imported
#
# The import list is generated from the mounted sample directory, so dropping new
# files into evaluation_samples/ and re-running is all that is needed.  Files already in the
# database are skipped by checksum (framework behaviour).
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

LIST_ONLY=0
[ "${1:-}" = "--list" ] && LIST_ONLY=1

if ! in_container; then
  require_container
  exec docker exec -i "$CONTAINER" bash -lc "bash /opt/rehosting/scripts/import_samples.sh ${LIST_ONLY:+--list}"
fi

mkdir -p "$RESULTS_DIR/logs" "$RESULTS_DIR/generated"
GEN="$RESULTS_DIR/generated/import_list.json"

python3 - "$SAMPLES_DIR" "$GEN" <<'PY'
import json, pathlib, sys

root = pathlib.Path(sys.argv[1])
out = pathlib.Path(sys.argv[2])
files = sorted(p for p in root.rglob('*') if p.is_file()
               and not p.name.startswith('.')
               and p.suffix.lower() not in ('.md', '.txt', '.json'))
entries = [{"path": str(p.relative_to(root))} for p in files]
out.write_text(json.dumps({"entries": entries}, indent=2))
print(f"[import] {len(entries)} firmware file(s) found under {root}")
for e in entries[:20]:
    print("         ", e["path"])
if len(entries) > 20:
    print(f"         ... and {len(entries) - 20} more")
PY

if [ "$LIST_ONLY" = 1 ]; then
  exit 0
fi

if [ "$(python3 -c "import json;print(len(json.load(open('$GEN'))['entries']))")" = "0" ]; then
  c_red "evaluation_samples/ is empty — download the sample set first (see README)"
  exit 1
fi

cd "$FRAMEWORK"
c_blue "==> importing (log: $RESULTS_DIR/logs/00_import.log)"
./bin/akiba_framework -c /data/pipelines/00_import.json@/main -i "$GEN" 2>&1 | tee -a "$RESULTS_DIR/logs/00_import.log"
c_green "import finished"
