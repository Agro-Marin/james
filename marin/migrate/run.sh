#!/usr/bin/env bash
# imap_copy.py's test (test_copy.py), on the postgres-app the last build packaged.
#
#   marin/migrate/run.sh
set -euo pipefail

MARIN=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
REPO=$(git -C "$MARIN" rev-parse --show-toplevel)
. "$MARIN/lane/tools.sh"
export PG_BINDIR
exec python3 -I -B "$MARIN/migrate/test_copy.py" "$LANE_CACHE/migrate/$(date -u +%Y%m%dT%H%M%SZ)" "$REPO"
