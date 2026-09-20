CREATE OR REPLACE FUNCTION assert_column_type(
    p_schema text,
    p_table  text,
    p_column text,
    p_type   text)
    RETURNS void
    LANGUAGE plpgsql AS
$$
BEGIN
    PERFORM 1
    FROM pg_attribute a
             JOIN pg_type      t ON t.oid = a.atttypid
             JOIN pg_class     c ON c.oid = a.attrelid
             JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = p_schema
      AND c.relname = p_table
      AND a.attname = p_column
      AND a.attnum  > 0
      AND NOT a.attisdropped
      AND t.typname = p_type;

    IF NOT FOUND THEN
        RAISE EXCEPTION '%.%.% not existed or type is not %',
            p_schema, p_table, p_column, p_type;
    END IF;
END;
$$;

-- Create a table `db_backup_tree` to save backup tree data
CREATE TABLE IF NOT EXISTS db_backup_tree (
    instance_name   TEXT NOT NULL,
    label TEXT NOT NULL,                                                  -- filenames of backup files
    is_expired BOOLEAN NOT NULL DEFAULT FALSE,                            -- whether this backup is expired
    parent_instance TEXT,                                                 -- parent instance (must be the same as instance_name)
    parent TEXT,                                                          -- parent label
    backup_alias    TEXT,                                                 -- alias name string that can be used in configs
    description     TEXT,                                                 -- description of this backup
    backup_type     TEXT CHECK (backup_type IN ('FULL', 'DIFF', 'INCR')), -- backup type
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),                   -- creation time (start timestamp in `pgbackrest info`)
    FOREIGN KEY (parent_instance, parent) REFERENCES db_backup_tree(instance_name, label),
    PRIMARY KEY (instance_name, label)
);

SELECT assert_column_type('public', 'db_backup_tree', 'instance_name', 'text');
SELECT assert_column_type('public', 'db_backup_tree', 'label', 'text');
SELECT assert_column_type('public', 'db_backup_tree', 'is_expired', 'bool');
SELECT assert_column_type('public', 'db_backup_tree', 'parent_instance', 'text');
SELECT assert_column_type('public', 'db_backup_tree', 'parent', 'text');
SELECT assert_column_type('public', 'db_backup_tree', 'backup_alias', 'text');
SELECT assert_column_type('public', 'db_backup_tree', 'description', 'text');
SELECT assert_column_type('public', 'db_backup_tree', 'backup_type', 'text');
SELECT assert_column_type('public', 'db_backup_tree', 'created_at', 'timestamptz');