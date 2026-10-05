#!/usr/bin/env bash
# Report the state of the container, the sample set and the collected results.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

echo "=== container ================================================"
if container_running; then
  compose ps
  echo
  echo "-- health:"
  curl -sf --max-time 3 http://127.0.0.1:41777/test && echo " database daemon OK" || echo " database daemon NOT reachable"
else
  c_red "container '$CONTAINER' is not running"
fi

echo
echo "=== samples =================================================="
find "$REPO_ROOT/evaluation_samples" -type f ! -name '.gitkeep' | wc -l | sed 's/^/firmware files: /'
du -sh "$REPO_ROOT/evaluation_samples" 2>/dev/null

echo
echo "=== results =================================================="
if [ -d "$REPO_ROOT/evaluation_results" ]; then
  find "$REPO_ROOT/evaluation_results" -maxdepth 2 -type d | sed "s|$REPO_ROOT/||" | head -25
  echo "-- csv/json files:"
  find "$REPO_ROOT/evaluation_results" -type f \( -name '*.csv' -o -name '*.json' \) | wc -l
  du -sh "$REPO_ROOT/evaluation_results" 2>/dev/null
fi

echo
echo "=== tool state ==============================================="
if container_running; then
  inside "for t in firmline fuzzware hoedur MultiFuzz FirmRCA FirmXRay; do \
            printf '%-10s ' \$t; \
            case \$t in \
              fuzzware) [ -d /home/akiba/.virtualenvs/fuzzware ] && echo 'venv ok' || echo 'not provisioned';; \
              hoedur|MultiFuzz) [ -x /home/akiba/.cargo/bin/cargo ] && echo 'cargo ok' || echo 'no cargo';; \
              FirmRCA) [ -x /data/tools/FirmRCA/FirmRCA-fuzzware/bin/python3 ] && echo 'venv ok' || echo 'not provisioned';; \
              firmline) [ -e /data/tools/firmline/fwdb.db ] && echo 'db ok' || echo 'not provisioned';; \
              FirmXRay) [ -d /data/tools/FirmXRay/out ] && echo 'built' || echo 'not built';; \
            esac; done" 2>/dev/null
fi
