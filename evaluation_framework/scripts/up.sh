#!/usr/bin/env bash
# Start the pipeline container and wait until the akiba database daemon answers.
#
# The probe below is the *host* view: the daemon listens on 31777 inside the container, published as
# 127.0.0.1:41777, and that is what the host-side pipeline scripts use.  So "the daemon started" in
# the container log and "ready" here are two different things, and when this times out the useful
# question is whether the daemon answers *inside* the container — the container's own healthcheck
# asks exactly that — hence both are reported before exiting.  A timeout is not fatal: the container
# keeps running, and the daemon can be re-checked later without restarting anything.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

HOST_URL="http://127.0.0.1:41777/test"
WAIT_SECONDS="${AKIBA_UP_WAIT:-300}"

mkdir -p "$REPO_ROOT/evaluation_samples" "$REPO_ROOT/evaluation_results"
c_blue "==> starting container"
compose up -d

cid="$(compose ps -q "$SERVICE" 2>/dev/null | head -1)"
container_state() {
  [ -n "$cid" ] || { echo "no container"; return; }
  docker inspect --format '{{.State.Status}}{{if .State.Health}} (health: {{.State.Health.Status}}){{end}}' "$cid" 2>/dev/null || echo unknown
}

c_blue "==> waiting for the database daemon on $HOST_URL (up to ${WAIT_SECONDS}s)"
i=0
while [ "$i" -lt "$WAIT_SECONDS" ]; do
  if curl -sf --max-time 3 "$HOST_URL" >/dev/null 2>&1; then
    c_green "database daemon ready after ${i}s"
    compose ps
    exit 0
  fi
  state="$(container_state)"
  case "$state" in
    running*|"") : ;;                    # keep waiting
    *) c_red "container is not running ($state) — it will never answer; last log lines:"
       compose logs --tail=40 "$SERVICE"
       exit 1 ;;
  esac
  if [ "$i" -gt 0 ] && [ $((i % 30)) -eq 0 ]; then
    echo "    still waiting (${i}/${WAIT_SECONDS}s, container: $state)"
  fi
  sleep 2
  i=$((i + 2))
done

c_red "database daemon did not answer on $HOST_URL within ${WAIT_SECONDS}s."
c_blue "==> container state"
compose ps
c_blue "==> published ports"
[ -n "$cid" ] && docker inspect --format '  {{range $p, $c := .NetworkSettings.Ports}}{{$p}} -> {{$c}}
{{end}}' "$cid" 2>/dev/null
c_blue "==> does the daemon answer inside the container? (the container's own healthcheck asks this)"
compose exec -T "$SERVICE" bash -lc 'curl -sf --max-time 3 http://localhost:31777/test >/dev/null && echo "  yes — the daemon is up inside the container"' \
  || c_red "  no — the daemon is not answering on 31777 inside the container either"
c_blue "==> last log lines"
compose logs --tail=40 "$SERVICE"
cat <<'EOF'

The container is left running.  When the daemon answers, re-check without restarting anything:
  curl -sf http://127.0.0.1:41777/test && echo ready
  scripts/up.sh          # same wait, and it prints the state again
A port missing from the list above means docker could not publish 127.0.0.1:41777 (already taken on
this host, or a remote docker daemon whose ports live on another machine).  A daemon that answers
inside but not outside is a host/networking matter, not a pipeline failure.
EOF
exit 1
