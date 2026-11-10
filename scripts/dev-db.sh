#!/usr/bin/env bash
# Local PostgreSQL 18 cluster for development and tests (no Docker on this machine).
# Listens on 127.0.0.1:55432 only, trust auth for local connections, data in .local/pgdata.
set -euo pipefail
PG_BIN="${PG_BIN:-/c/Program Files/PostgreSQL/18/bin}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DATA="$ROOT/.local/pgdata"
PORT="${BREWFLOW_DB_PORT:-55432}"
case "${1:-start}" in
  start)
    if [ ! -f "$DATA/PG_VERSION" ]; then
      "$PG_BIN/initdb" -D "$DATA" -U postgres -A trust -E UTF8 --locale=C >/dev/null
    fi
    "$PG_BIN/pg_ctl" -D "$DATA" -o "-p $PORT -c listen_addresses=127.0.0.1" -l "$ROOT/.local/pg.log" -w start || true
    for db in brewflow brewflow_test; do
      "$PG_BIN/psql" -h 127.0.0.1 -p "$PORT" -U postgres -tAc "select 1 from pg_database where datname='$db'" | grep -q 1 \
        || "$PG_BIN/createdb" -h 127.0.0.1 -p "$PORT" -U postgres "$db"
    done
    echo "PostgreSQL 18 on 127.0.0.1:$PORT (databases brewflow, brewflow_test)";;
  stop) "$PG_BIN/pg_ctl" -D "$DATA" -w stop;;
  status) "$PG_BIN/pg_ctl" -D "$DATA" status;;
  *) echo "usage: $0 start|stop|status"; exit 2;;
esac
