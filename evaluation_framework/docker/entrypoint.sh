#!/bin/bash
# ---------------------------------------------------------------------------
# Container entrypoint — akiba 3.1.2 all-in-one image
#
# Behaviour intentionally mirroring the reference container on 222.20.126.138:
#   1. first start: start postgres, create the local cluster, create the
#      `akiba-instance` instance (user akiba / password akiba), touch the init flag
#   2. then exec the db daemon (or whatever command docker-compose passes)
#
# Differences from the upstream 3.1.2 script, all of them about persistence:
#   * the generated database password lives in /akiba (a named volume) instead of
#     ~/.akiba, so rebuilding the image does not orphan an existing instance
#   * /akiba, /data and the postgres data dir are chowned on start because Docker
#     creates fresh named volumes as root
#   * sshd is started so the container can be driven exactly like the server
# ---------------------------------------------------------------------------
set -e

INIT_FLAG="/akiba/.init"
DAEMON_DIR="/home/akiba/akiba_db_daemon"
FRAMEWORK_DIR="/home/akiba/akiba_framework"
CONFIG="${DAEMON_DIR}/resources/config.json"
PID_FILE="/tmp/akiba_db_daemon.pid"
PASSWORD_FILE="/akiba/.akiba/db_password"

fix_ownership() {
    sudo mkdir -p /akiba/backups /akiba/instances /akiba/.akiba \
                 /data/akiba /data/samples /data/results
    # akiba 3.1.2 is inconsistent about the instance map: PGInstances.initialize()
    # reads File(config.instanceMapFile) (= /akiba/.akiba/instances.json here, i.e.
    # inside the named volume), while DatabaseDaemon.kt hard-codes the *write* to
    # ${user.home}/.akiba/instances.json.  Point $HOME/.akiba at the volume so both
    # paths are the same file and the map survives a container rebuild; otherwise the
    # daemon starts with an empty map and every login fails with
    # "Instance akiba-instance not found" ("Failed to login to database: null").
    if [ ! -L /home/akiba/.akiba ]; then
        sudo rm -rf /home/akiba/.akiba
        sudo ln -s /akiba/.akiba /home/akiba/.akiba
    fi
    sudo chown -R akiba:akiba /akiba /data 2>/dev/null || true
    sudo chown -h akiba:akiba /home/akiba/.akiba 2>/dev/null || true
    # The instance *root* has to stay writable by akiba (the daemon mkdir's
    # <root>/<name> there), but each instance's data directory must belong to
    # postgres: PGInstances.startInstance() runs `sudo -i -u postgres pg_ctl -D
    # /akiba/instances/<name>`.  A blanket `chown -R akiba /akiba` (which this
    # entrypoint used to do) leaves those data dirs akiba-owned/0700, and then every
    # /instance/connect fails with "Instance <name> failed to start" -> 500 ->
    # "Failed to login to database: null".
    for d in /akiba/instances/*/; do
        [ -d "$d" ] && sudo chown -R postgres:postgres "$d" 2>/dev/null || true
    done
    sudo chown -R postgres:postgres /var/lib/postgresql 2>/dev/null || true
}

start_ssh() {
    sudo mkdir -p /run/sshd
    sudo /usr/sbin/sshd || echo ">>> sshd already running (or failed to start)"
}

wait_for_service() {
    local url=$1 max_attempts=60 attempt=1
    echo ">>> Waiting for service ready: ${url}"
    while [ $attempt -le $max_attempts ]; do
        if curl -s --max-time 2 "${url}" > /dev/null 2>&1; then
            echo ">>> Service ready (${attempt}s)"
            return 0
        fi
        [ $((attempt % 10)) -eq 0 ] && echo "    Waiting... (${attempt}/${max_attempts})"
        sleep 1
        attempt=$((attempt + 1))
    done
    echo ">>> Error: service not ready until timeout"
    return 1
}

cleanup() {
    if [ -f "$PID_FILE" ]; then
        local pid
        pid=$(cat "$PID_FILE")
        if kill -0 "$pid" 2>/dev/null; then
            echo ">>> Stopping akiba_db_daemon... (PID: $pid)"
            kill "$pid" || true
            wait "$pid" 2>/dev/null || true
        fi
        rm -f "$PID_FILE"
    fi
}
trap cleanup EXIT

fix_ownership
start_ssh

if [ ! -f "$INIT_FLAG" ]; then
    echo ">>> Startup for the first time, initializing..."

    sudo service postgresql start
    cd "$DAEMON_DIR"
    ./resources/initialize_pg_local.sh

    echo ">>> Running temporary akiba_db_daemon..."
    nohup ./bin/akiba_db_daemon -c "$CONFIG" > /tmp/daemon.boot.log 2>&1 &
    echo $! > "$PID_FILE"

    if ! wait_for_service "http://localhost:31777/test"; then
        echo ">>> Service failed to start:"
        cat /tmp/daemon.boot.log || true
        exit 1
    fi

    echo ">>> Creating PostgreSQL instance 'akiba-instance' (user akiba / password akiba)..."
    cd "$FRAMEWORK_DIR"
    if ! ./bin/akiba_framework instance-create -n akiba-instance -u akiba -P akiba; then
        echo ">>> Initialization failed"
        exit 1
    fi

    cleanup
    touch "$INIT_FLAG"
    echo ">>> Initialization finished (flag: $INIT_FLAG, db password: $PASSWORD_FILE)"
else
    echo ">>> Initialization already done"
    sudo service postgresql start
fi

echo ">>> Starting main service (akiba database daemon): $*"
cd "$DAEMON_DIR"
exec "$@"
