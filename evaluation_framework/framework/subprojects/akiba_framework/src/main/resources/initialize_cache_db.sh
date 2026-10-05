#!/usr/bin/env bash
set -e

# -----------------------------
# initialize_cache_db.sh: Initialize a local cache database, for storing data that failed to send to database
# Note: This database will not open any port, and can only be connected through the socket
# This instance will occupy the subdirectory
#
# Exports:
# - INSTANCE_ROOT: Root directory of the instance
#
# The total directory structure should be:
# INSTANCE_ROOT
#   |- .s.PGSQL.5432 (socket file)
#   |- cache_instance (instance directory)
# -----------------------------

REQUIRED_VERSION=12
echo "== PostgreSQL instance initialization started =="

if ! command -v psql >/dev/null 2>&1; then
    echo "PostgreSQL is not installed. Please install it via apt first."
    exit 1
fi

PG_VERSION_RAW=$(psql --version | awk '{print $3}')
PG_MAJOR_VERSION=${PG_VERSION_RAW%%.*}

if [[ "$PG_MAJOR_VERSION" -lt "$REQUIRED_VERSION" ]]; then
    echo "PostgreSQL version $PG_VERSION_RAW detected."
    echo "Version must be >= $REQUIRED_VERSION."
    exit 1
fi

echo "PostgreSQL version $PG_VERSION_RAW OK."

# -----------------------------
# 1. Check variables
# -----------------------------
INSTANCE_ROOT=${INSTANCE_ROOT:?}

# -----------------------------
# 2. Initialize data directory
# -----------------------------
PGDATA="$INSTANCE_ROOT/cache_instance"
if [[ -e "$PGDATA" ]]; then
  echo "ERROR: $PGDATA already exists." >&2
  exit 1
fi

mkdir -p "$PGDATA"
chmod 777 "$INSTANCE_ROOT"
chmod 700 "$PGDATA"
/usr/lib/postgresql/"$PG_MAJOR_VERSION"/bin/initdb -D "$PGDATA" --auth-host=md5 --auth-local=peer 2>&1

echo "[2] Initialize data directory: done"

# -----------------------------
# 3. Set listen to no port and socket directory
# -----------------------------
INSTANCE_CONF="$PGDATA/postgresql.conf"
sudo sed -Ei '
s|^#?(listen_addresses)\s*=.*|\1 = '\'\''|;
s|^#?(port)\s*=.*|#\1 = 0|;
s|^#?(unix_socket_directories)\s*=.*|\1 = '\'"$INSTANCE_ROOT"\''|;
' "$INSTANCE_CONF"

echo "[3] Set conf: done"

# -----------------------------
# 4. Open the instance
# -----------------------------
sudo chown -R postgres:postgres "$PGDATA"
sudo -i -u postgres /usr/lib/postgresql/"$PG_MAJOR_VERSION"/bin/pg_ctl -D "$PGDATA" -w -l "$PGDATA/postmaster.log" start

echo "[4] Open the instance: done"

# -----------------------------
# 5. Create superuser
# -----------------------------
USER_NAME=akiba
HBA_CONF="$PGDATA/pg_hba.conf"
sudo sed -i "1ilocal all $USER_NAME trust" "$HBA_CONF"
psql -h "$INSTANCE_ROOT" -d postgres -c "CREATE USER $USER_NAME SUPERUSER LOGIN;"

echo "[5] Create superuser: done"

# -----------------------------
# 6. Create database named as superuser and grant to superuser
# -----------------------------
INSTANCE_NAME="cache"
createdb -h "$INSTANCE_ROOT" -O "$USER_NAME" "$INSTANCE_NAME"

echo "[6] Create database: done"