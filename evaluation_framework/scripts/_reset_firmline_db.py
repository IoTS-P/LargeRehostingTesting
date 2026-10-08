#!/usr/bin/env python3
"""Make firmline's result database usable, without destroying its schema.

Two facts about the checkout:

* `fwdb.db` is **not tracked by the firmline repository** — it ships inside the authors'
  release archive.  `pipeline.py` only calls `create_engine()` and never
  `Base.metadata.create_all()`, so a checkout without that file (a fresh clone, or the
  bind-mounted tree after a cleanup) fails with
  `sqlite3.OperationalError: no such table: samples`.
* rows left behind by an interrupted run point at processed copies that no longer exist,
  and `process_duplicate` then aborts with
  `FileNotFoundError: processed-firmware/blobs/<name>_tmpforFirmline`, poisoning every
  later attempt on the same image.

This helper creates the schema from the tool's own models (`db.py`) when it is missing and
clears the content either way.  It runs inside the container with the firmline env:

    docker exec <container> /opt/conda/envs/firmline/bin/python3 \
        /opt/rehosting/scripts/_reset_firmline_db.py
"""
import pathlib, shutil, sqlite3, sys

TOOL = pathlib.Path("/data/tools/firmline")
DB = TOOL / "fwdb.db"
PROCESSED = TOOL / "processed-firmware"

sys.path.insert(0, str(TOOL))
try:
    from db import Base                                   # the tool's own models
except Exception as exc:                                  # pragma: no cover
    sys.exit(f"cannot import {TOOL}/db.py: {exc}")
from sqlalchemy import create_engine

engine = create_engine(f"sqlite:///{DB}")
Base.metadata.create_all(engine)                          # idempotent

con = sqlite3.connect(DB)
tables = [r[0] for r in con.execute("select name from sqlite_master where type='table'")]
cleared = {}
for t in tables:
    n = con.execute(f"select count(*) from {t}").fetchone()[0]
    if n:
        con.execute(f"delete from {t}")
        cleared[t] = n
con.commit()
con.close()
if PROCESSED.exists():
    shutil.rmtree(PROCESSED)
print("schema:", sorted(tables))
print("cleared rows:", cleared or "(nothing to clear)")
