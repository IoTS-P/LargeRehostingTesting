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
  # -t and not -i: the framework is a JVM, and without a console its stdout is block-buffered, so a long
  # import prints nothing until it exits.  It reads no stdin, so the TTY is all we need.
  exec docker exec -t "$CONTAINER" bash -lc "bash /opt/rehosting/scripts/import_samples.sh ${LIST_ONLY:+--list}"
fi

mkdir -p "$RESULTS_DIR/logs" "$RESULTS_DIR/generated" 2>/dev/null || true
GEN="$RESULTS_DIR/generated/import_list.json"

# The results mount is the host's evaluation_results/.  A root-owned directory or file in it (an earlier
# root-context run) blocks the write below, which would otherwise surface as a python traceback ending in
#   PermissionError: [Errno 13] Permission denied: '/data/results/generated/import_list.json'
# Say what is wrong and what fixes it.  Probe the directory by creating a file *in* it (appending to a
# directory is not a test) and the list separately, because a writable directory can still hold a file
# this user cannot truncate.
probe_failed=""
touch "$RESULTS_DIR/generated/.write-probe" 2>/dev/null || probe_failed="$RESULTS_DIR/generated"
[ -z "$probe_failed" ] && { : >>"$GEN" 2>/dev/null || probe_failed="$GEN"; }
rm -f "$RESULTS_DIR/generated/.write-probe"
if [ -n "$probe_failed" ]; then
  c_red "cannot write $probe_failed — the results mount is not writable by $(id -un) (uid $(id -u))"
  c_red "  restart the container: it repairs the results tree on startup (entrypoint: fix_results_dir)"
  c_red "  or, on the host:   sudo chown -R $(id -u):$(id -g) evaluation_results/generated"
  exit 1
fi

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

entries=$(python3 -c "import json;print(len(json.load(open('$GEN'))['entries']))" 2>/dev/null || echo '?')
if [ "${entries:-0}" = "0" ]; then
  c_red "evaluation_samples/ is empty — download the sample set first (see README)"
  exit 1
fi

cd "$FRAMEWORK"
IMPORT_LOG="$RESULTS_DIR/logs/00_import.log"
c_blue "==> importing ${entries} firmware file(s) (log: $IMPORT_LOG)"
# A JVM without a console buffers stdout in blocks, so a long import shows nothing until it exits, which
# reads as a hang.  The caller gives this process a TTY (docker exec -t); the tee keeps the log; and because
# a quiet framework still tells you nothing, the elapsed time and the number of files stored so far are
# printed every 20 s.  pipefail is set, so the pipeline reports the framework's status rather than tee's.
import_started=$(date +%s)
./bin/akiba_framework -c /data/pipelines/00_import.json@/main -i "$GEN" 2>&1 | tee -a "$IMPORT_LOG" &
import_pid=$!
# Sleep in short slices so the exit is noticed within a couple of seconds (a single `sleep 20` would make
# every import end with up to 20 s of dead time), while the progress line still appears about every 20 s.
while kill -0 "$import_pid" 2>/dev/null; do
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    sleep 2
    kill -0 "$import_pid" 2>/dev/null || break 2
  done
  printf '    [%4ss] stored in the instance: %s\n' "$(( $(date +%s) - import_started ))" \
    "$(ls /data/akiba/binaries/00_import 2>/dev/null | wc -l)"
done
wait "$import_pid"; rc=$?
printf '    import finished in %ss (exit %s)\n' "$(( $(date +%s) - import_started ))" "$rc"
if [ "$rc" != 0 ]; then
  c_red "import failed (exit $rc) — see $IMPORT_LOG"
  exit 1
fi
c_green "import finished: $(ls /data/akiba/binaries/00_import 2>/dev/null | wc -l) entries stored under /data/akiba/binaries/00_import"
