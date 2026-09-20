#!/usr/bin/env bash
# Self-contained smoke test: import the example ELF that ships inside the image and
# run a built-in analysis module (Entropy) against it.  Proves the container is
# functional (Ghidra headless analysis + module loading + database round trip)
# without any real tool being provisioned and without firmware in evaluation_samples/.
#
#   scripts/smoke_test.sh
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

if ! in_container; then
  require_container
  exec docker exec -i "$CONTAINER" bash -lc "bash /opt/rehosting/scripts/smoke_test.sh"
fi

cd "$FRAMEWORK"
c_blue "==> importing the example ELF (log: $RESULTS_DIR/logs/smoke_import.log)"
./bin/akiba_framework -c /home/akiba/binaries/config_example.json \
                      -i /home/akiba/binaries/import_example.json 2>&1 \
  | tee "$RESULTS_DIR/logs/smoke_import.log" | tail -5

c_blue "==> running the built-in Entropy module"
./bin/akiba_framework -c /data/pipelines/smoke_test.json@/main 2>&1 \
  | tee "$RESULTS_DIR/logs/smoke_module.log" | tail -15

rc=${PIPESTATUS[0]}
bash "$(dirname "${BASH_SOURCE[0]}")/export_results.sh" >/dev/null 2>&1 || true

# The module writes one row per analysed binary into example_table; a header-only
# file means the module never got to work (e.g. the binary itself was skipped).
if [ -s "$RESULTS_DIR/db/example_table.csv" ] && [ "$(wc -l < "$RESULTS_DIR/db/example_table.csv")" -gt 1 ]; then
  c_green "smoke test passed — example_table.csv written:"
  sed 's/,/\t/g' "$RESULTS_DIR/db/example_table.csv" | head -5
else
  c_red "smoke test failed — example_table.csv is missing or empty (see $RESULTS_DIR/logs/smoke_module.log)"
  exit 1
fi
exit $rc
