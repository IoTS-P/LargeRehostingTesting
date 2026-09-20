#!/usr/bin/env bash

set -euo pipefail

REQUIRED_VERSION=12

echo "== PostgreSQL initialization started =="

# -----------------------------
# 1. Check if PostgreSQL exists
# -----------------------------
if ! command -v psql >/dev/null 2>&1; then
    echo "PostgreSQL is not installed. Please install it via apt first."
    exit 1
fi

# -----------------------------
# 2. Check if PostgreSQL version is supported
# -----------------------------
PG_VERSION_RAW=$(psql --version | awk '{print $3}')
PG_MAJOR_VERSION=${PG_VERSION_RAW%%.*}

if [[ "$PG_MAJOR_VERSION" -lt "$REQUIRED_VERSION" ]]; then
    echo "PostgreSQL version $PG_VERSION_RAW detected."
    echo "Version must be >= $REQUIRED_VERSION."
    exit 1
fi

echo "PostgreSQL version $PG_VERSION_RAW OK."

# -----------------------------
# 3. Check if user exists
# -----------------------------
USER=akiba
USER_EXISTS=$(sudo -u postgres psql -tAc \
    "SELECT 1 FROM pg_roles WHERE rolname='${USER}';")

if [[ "$USER_EXISTS" != "1" ]]; then
    echo "Role '${USER}' does not exist. Creating..."
    sudo -u postgres psql <<EOF
CREATE ROLE ${USER}
    LOGIN
    PASSWORD 'akiba';
EOF
    echo "User '${USER}' created."
else
    echo "User '${USER}' already exists."
fi

# -----------------------------
# 4. Check and grant privileges
# -----------------------------
read -r HAS_REPLICATION HAS_CREATEDB <<<"$(
sudo -u postgres psql -tAc "
SELECT rolreplication, rolcreatedb
FROM pg_roles
WHERE rolname='${USER}';
")"

NEED_ALTER=false
ALTER_SQL="ALTER ROLE ${USER}"

if [[ "$HAS_REPLICATION" != "t" ]]; then
    ALTER_SQL+=" REPLICATION"
    NEED_ALTER=true
fi

if [[ "$HAS_CREATEDB" != "t" ]]; then
    ALTER_SQL+=" CREATEDB"
    NEED_ALTER=true
fi

if [[ "$NEED_ALTER" == true ]]; then
    ALTER_SQL+=";"
    sudo -u postgres psql -c "$ALTER_SQL"
    echo "Privileges updated for user '${USER}'."
else
    echo "User '${USER}' already has required privileges."
fi

# Alter PostgreSQL config files to only listen to localhost
PG_CONF_DIR="/etc/postgresql/${PG_MAJOR_VERSION}/main"
CONF_FILE="${PG_CONF_DIR}/postgresql.conf"
if sudo test -f "$CONF_FILE"; then
    sudo sed -Ei 's/^#(listen_addresses)\s*=.*/\1 = localhost/;' "$CONF_FILE"
else
    echo "PostgreSQL config file not found: $CONF_FILE"
    exit 1
fi

echo "== PostgreSQL initialization completed =="

# -----------------------------
# 5. Create database and set its owner
# -----------------------------
DB_NAME=akiba_backup

DB_EXISTS=$(sudo -u postgres psql -tAc \
    "SELECT 1 FROM pg_database WHERE datname='${DB_NAME}';")

if [[ "$DB_EXISTS" != "1" ]]; then
    echo "Database '${DB_NAME}' does not exist. Creating..."

    sudo -u postgres psql <<EOF
CREATE DATABASE ${DB_NAME}
    OWNER ${USER};
EOF

    echo "Database '${DB_NAME}' created with owner '${USER}'."
else
    CURRENT_OWNER=$(sudo -u postgres psql -tAc \
        "SELECT pg_catalog.pg_get_userbyid(datdba)
         FROM pg_database
         WHERE datname='${DB_NAME}';")

    if [[ "$CURRENT_OWNER" != "USER" ]]; then
        echo "Database '${DB_NAME}' exists but owner is '${CURRENT_OWNER}', fixing..."

        sudo -u postgres psql -c \
            "ALTER DATABASE ${DB_NAME} OWNER TO ${USER};"

        echo "Database owner updated to '${USER}'."
    else
        echo "Database '${DB_NAME}' already exists with correct owner."
    fi
fi

sudo -u postgres psql <<EOF
GRANT CONNECT, CREATE, TEMP ON DATABASE ${DB_NAME} TO ${USER};

\\c ${DB_NAME}

ALTER SCHEMA public OWNER TO ${USER};
GRANT USAGE, CREATE ON SCHEMA public TO ${USER};

GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO ${USER};
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO ${USER};

ALTER DEFAULT PRIVILEGES IN SCHEMA public
GRANT ALL ON TABLES TO ${USER};

ALTER DEFAULT PRIVILEGES IN SCHEMA public
GRANT ALL ON SEQUENCES TO ${USER};
EOF

# -----------------------------
# 6. Run initialization SQL file
# -----------------------------
DB_INIT_SQL="./resources/backup_db_init.sql"

if [[ ! -f "$DB_INIT_SQL" ]]; then
    echo "SQL file not found: $DB_INIT_SQL"
    exit 1
fi

echo "Executing SQL file '$DB_INIT_SQL' on database '$DB_NAME'..."

sudo -u postgres psql \
    --dbname="$DB_NAME" \
    --set=ON_ERROR_STOP=on \
    --file="$DB_INIT_SQL"

echo "SQL initialization completed successfully."