#!/usr/bin/env bash
# Start the pipeline container and wait until the akiba database daemon answers.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

mkdir -p "$REPO_ROOT/evaluation_samples" "$REPO_ROOT/evaluation_results"
c_blue "==> starting container"
compose up -d

c_blue "==> waiting for the database daemon on 127.0.0.1:41777"
for i in $(seq 1 90); do
  if curl -sf --max-time 3 http://127.0.0.1:41777/test >/dev/null 2>&1; then
    c_green "database daemon ready after ${i}s"
    compose ps
    exit 0
  fi
  sleep 2
done

c_red "database daemon did not become ready in time; last log lines:"
compose logs --tail=40 "$SERVICE"
exit 1
