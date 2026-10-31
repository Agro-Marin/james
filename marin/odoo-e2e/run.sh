#!/usr/bin/env bash
# The fork's end-to-end suite against a real Odoo (e2e.py), on the postgres-app the last build packaged.
#
#   marin/odoo-e2e/run.sh <odoo checkout> <odoo python> [test ...]
set -euo pipefail

MARIN=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
REPO=$(git -C "$MARIN" rev-parse --show-toplevel)
. "$MARIN/lane/tools.sh"
export PG_BINDIR
[[ -f $REPO/server/apps/postgres-app/target/james-server-postgres-app.jar ]] || {
    echo "run.sh: package server/apps/postgres-app first" >&2
    exit 2
}
odoo=$(cd "$1" && pwd)
python=$2
shift 2
exec python3 -I -B "$MARIN/odoo-e2e/e2e.py" "$LANE_CACHE/odoo-e2e/$(date -u +%Y%m%dT%H%M%SZ)" "$REPO" "$odoo" "$python" "$@"
