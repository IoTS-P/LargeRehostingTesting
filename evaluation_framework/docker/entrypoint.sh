#!/bin/bash
# ---------------------------------------------------------------------------
# Container entrypoint — akiba 3.1.2 all-in-one image
#
# Behaviour intentionally mirroring the reference container of the authors' deployment:
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

# The daemon and this script exec files straight from disk (./resources/initialize_pg_local.sh,
# ./bin/akiba_db_daemon, ./resources/initialize_pg_instance.sh from PGInstances.kt).  /home/akiba is
# a named volume: it can hold a copy that came from an image, a tarball or a checkout created on a
# filesystem that cannot store the executable bit (NTFS/exFAT report everything as 777; git then
# records every script as non-executable), and the container would start, fail on
# `./bin/akiba_db_daemon: Permission denied` and never answer on 31777.  Cheap to repair on every
# start, so repair here instead of relying on how the tree was transferred.  Note this repairs
# *modes for the current owner*: a file owned by root still needs sudo, which akiba has.  The
# entrypoint itself lives in /opt/rehosting (outside every volume) precisely so that an unreadable
# copy of it cannot prevent this repair from running at all.
fix_permissions() {
    sudo chmod 0755 /home/akiba/binaries/*.sh 2>/dev/null || true
    sudo chmod -R a+rX /home/akiba/binaries 2>/dev/null || true
    sudo chmod 0755 /opt/rehosting/entrypoint.sh /opt/rehosting/scripts/*.sh /opt/rehosting/scripts/*.py 2>/dev/null || true
    sudo find /home/akiba/akiba_framework /home/akiba/akiba_db_daemon \
        \( -name '*.sh' -o -path '*/bin/*' -o -path '*/bin' \) -exec chmod 0755 {} + 2>/dev/null || true
    # The same volume can also arrive with root-owned, non-traversable *directories* (a checkout or
    # image built under umask 077), and then the container dies on the first
    # `cd /home/akiba/akiba_db_daemon: Permission denied` — before any stage runs.  Repair the
    # directories this script and the daemon actually need, which is bounded (the two project
    # installs and binaries), rather than the whole home tree with its venvs and caches.
    sudo chmod u+rwx,go+rx /home/akiba 2>/dev/null || true
    sudo chown -R akiba:akiba /home/akiba/binaries /home/akiba/akiba_framework /home/akiba/akiba_db_daemon 2>/dev/null || true
    sudo chmod -R u+rwX,go+rX /home/akiba/binaries /home/akiba/akiba_framework /home/akiba/akiba_db_daemon 2>/dev/null || true
}

# /data/results is a bind mount of the host's evaluation_results/, and a file or directory in it can be
# root-owned - written by an earlier root-context step, or by an image build.  Every write from inside the
# container then fails, and the first casualty is the import list: the pipeline's own scripts die with a
# bare
#   PermissionError: [Errno 13] Permission denied: '/data/results/generated/import_list.json'
# which says nothing about ownership.  Hand the framework's own directories to the container user.
# Probe as akiba, not as root: root can write anywhere, so a naive check never fires.  Only the
# directories the framework writes are examined and only at depth one, so a clean multi-gigabyte results
# tree is not walked on every start.
fix_results_dir() {
    local d
    for d in /data/results /data/results/generated /data/results/logs /data/results/db /data/results/artifacts; do
        [ -d "$d" ] || continue
        if sudo -u akiba test -w "$d" 2>/dev/null \
           && [ -z "$(sudo -u akiba find "$d" -maxdepth 1 \( -type f -o -type d \) ! -writable -print -quit 2>/dev/null)" ]; then
            continue
        fi
        echo ">>> repairing $d (not writable by akiba)"
        sudo chown -R akiba:akiba "$d" 2>/dev/null || true
        sudo chmod -R u+rwX "$d" 2>/dev/null || true
    done
}

# Docker seeds a named volume from the image only when the volume is EMPTY, so an akiba_home volume
# left over from an earlier build keeps that build's /home/akiba and shadows this image.  The framework
# finds its modules by scanning `modules/` relative to its working directory (run_pipeline.sh does
# `cd /home/akiba/akiba_framework`), so such a volume makes every stage fail with
#   ClassNotFoundException: Module not found: org.iotsplab.akiba.process.<Name>
# long after startup looked healthy.  Compare the volume against the manifest this image wrote at
# build time and say so once, with the way out, instead of letting it look like a pipeline bug.
check_framework_bundle() {
    local manifest=/opt/rehosting/framework_manifest.txt vol=/home/akiba/akiba_framework/modules
    [ -r "$manifest" ] || return 0
    [ -d "$vol" ] || return 0
    ls "$vol" 2>/dev/null | sort > /tmp/.volume_jar_names
    if grep -qvxF -f /tmp/.volume_jar_names "$manifest"; then
        local expected have missing
        expected=$(wc -l < "$manifest" | tr -d ' ')
        have=$(wc -l < /tmp/.volume_jar_names | tr -d ' ')
        missing=$(grep -vxF -f /tmp/.volume_jar_names "$manifest" | wc -l | tr -d ' ')
        echo ">>> WARNING: this image ships ${expected} module jars, the akiba_home volume holds ${have}"
        echo "    (${missing} missing).  A volume is seeded from the image only when it is empty, so a"
        echo "    volume from an earlier build shadows this one, and stages then fail with"
        echo "    'ClassNotFoundException: Module not found: org.iotsplab.akiba.process.*'."
        echo "    From the host — results and the database live in other volumes, so those are kept:"
        echo "      docker compose -f evaluation_framework/docker/docker-compose.yml down"
        echo "      docker volume rm largerehosting_akiba_home"
        echo "      evaluation_framework/scripts/up.sh"
    fi
    rm -f /tmp/.volume_jar_names
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

fix_permissions
fix_ownership
fix_results_dir
check_framework_bundle
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
