#!/usr/bin/env bash
set -e

# -----------------------------
# initialize_pg_instance.sh: Initialize a new PostgreSQL instance on local environment
#
# Exports:
# - INSTANCE_NAME: Name of the instance
# - INSTANCE_ROOT: Root directory of all instances
# - USER_NAME: Name of the superuser
# - PORT: Port number
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
INSTANCE_NAME=${INSTANCE_NAME:?}
INSTANCE_ROOT=${INSTANCE_ROOT:?}
USER_NAME=${USER_NAME:?}
PORT=${PORT:?}
PGDATA="$INSTANCE_ROOT/$INSTANCE_NAME"

echo "[1] Check variables: done"

# -----------------------------
# 2. Initialize data directory
# -----------------------------
if [[ -e "$PGDATA" ]]; then
  echo "ERROR: $PGDATA already exists." >&2
  exit 1
fi

mkdir -p "$PGDATA"
chmod 700 "$PGDATA"
/usr/lib/postgresql/"$PG_MAJOR_VERSION"/bin/initdb -D "$PGDATA" --auth-host=md5 --auth-local=peer 2>&1

echo "[2] Initialize data directory: done"

# -----------------------------
# 3. Only listen on localhost, specify the port
# -----------------------------
cat >> "$PGDATA/postgresql.conf" <<EOF
listen_addresses = '127.0.0.1'
port = $PORT
EOF

echo "[3] Set port: done"

# -----------------------------
# 4. Allow to use local TCP connections
# -----------------------------
echo "host all all 127.0.0.1/32 md5" >> "$PGDATA/pg_hba.conf"

echo "[4] Set local TCP connections: done"

# -----------------------------
# 5. Open the instance
# -----------------------------
sudo chown -R postgres:postgres "$PGDATA"
sudo -i -u postgres /usr/lib/postgresql/"$PG_MAJOR_VERSION"/bin/pg_ctl -D "$PGDATA" -w -l "$PGDATA/postmaster.log" start

echo "[5] Open the instance: done"

# -----------------------------
# 6. Create superuser
# -----------------------------
HBA_CONF="$PGDATA/pg_hba.conf"
# Add trust authentication for local connections BEFORE existing rules
sudo sed -i "1ilocal all $USER_NAME trust" "$HBA_CONF"
sudo sed -i "2ihost all $USER_NAME 127.0.0.1/32 trust" "$HBA_CONF"
sudo sed -i "3ilocal all postgres trust" "$HBA_CONF"
sudo sed -i "4ihost all postgres 127.0.0.1/32 trust" "$HBA_CONF"
# Check if user exists before creating
psql -p "$PORT" -d postgres -c "DO \$\$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$USER_NAME') THEN CREATE USER $USER_NAME SUPERUSER LOGIN; END IF; END \$\$;"

echo "[6] Create superuser: done"

# -----------------------------
# 7. Change /etc/pgbackrest.conf and create stanza
# -----------------------------
BACKUP_ROOT=${BACKUP_ROOT:?}
BACKUP_PATH="$BACKUP_ROOT/$INSTANCE_NAME"
mkdir -p "$BACKUP_PATH"
chmod 777 "$BACKUP_PATH"
BACKREST_CONF_PATH="$BACKUP_ROOT/$INSTANCE_NAME/pgbackrest.conf"
cat >> "$BACKREST_CONF_PATH" <<EOF
[${INSTANCE_NAME}]
repo1-path    = $BACKUP_PATH
repo1-retention-full = 10
pg1-user      = $USER_NAME
pg1-path      = $PGDATA
pg1-port      = $PORT
EOF
sudo -i -u postgres /usr/lib/postgresql/"$PG_MAJOR_VERSION"/bin/pg_ctl -D "$PGDATA" -w -l "$PGDATA/postmaster.log" restart
sudo -u postgres pgbackrest --config="$BACKREST_CONF_PATH" --stanza="$INSTANCE_NAME" stanza-create

# -----------------------------
# 8. Change postgresql.conf to support backups
# -----------------------------
INSTANCE_CONF="$PGDATA/postgresql.conf"
sudo sed -Ei '
s/^#?(wal_level)\s*=.*/\1 = replica/;
s/^#?(archive_mode)\s*=.*/\1 = on/;
s|^#?(archive_command)\s*=.*|\1 = '\''pgbackrest --config='"$BACKUP_PATH"'/pgbackrest.conf --stanza='"$INSTANCE_NAME"'_base archive-push '"$PGDATA"'/%p'\''|;
' "$INSTANCE_CONF"

# -----------------------------
# 9. Create database named as superuser and grant to superuser
# -----------------------------
createdb -p "$PORT" -O "$USER_NAME" "$INSTANCE_NAME"

echo "[7] Create database: done"

# -----------------------------
# 10. Run initialization SQL file
# -----------------------------
DB_INIT_SQL="./resources/database_init.sql"

if [[ ! -f "$DB_INIT_SQL" ]]; then
    echo "SQL file not found: $DB_INIT_SQL"
    exit 1
fi

echo "Executing SQL file '$DB_INIT_SQL' on database '$INSTANCE_NAME'..."

psql -p "$PORT" \
    --dbname="$INSTANCE_NAME" \
    --set=ON_ERROR_STOP=on \
    --file="$DB_INIT_SQL"

echo "[8] SQL initialization completed successfully."

# -----------------------------
# 11. Stop the instance
# -----------------------------

sudo -i -u postgres /usr/lib/postgresql/"$PG_MAJOR_VERSION"/bin/pg_ctl -D "$PGDATA" -m fast -w stop
echo "[9] Stop the instance: done"

echo "Instance $INSTANCE_NAME ready at 127.0.0.1:$PORT, superuser=$USER_NAME, db=$INSTANCE_NAME"
