#!/usr/bin/env bash
# The restore drill (drill.py), on the postgres-app the last build packaged.
#
#   marin/restore-drill/run.sh
set -euo pipefail

MARIN=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
REPO=$(git -C "$MARIN" rev-parse --show-toplevel)
. "$MARIN/lane/tools.sh"
[[ -f $REPO/server/apps/postgres-app/target/james-server-postgres-app.jar ]] || {
    echo "run.sh: package server/apps/postgres-app first" >&2
    exit 2
}
export PG_BINDIR
exec python3 -I -B "$MARIN/restore-drill/drill.py" "$LANE_CACHE/restore-drill/$(date -u +%Y%m%dT%H%M%SZ)" "$REPO" \
    "$(unpacked pgbackrest "$PGBACKREST_URL" "$PGBACKREST_SHA256")" "$(unpacked restic "$RESTIC_URL" "$RESTIC_SHA256")"
