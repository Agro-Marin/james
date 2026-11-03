#!/usr/bin/env bash
# testssl.sh against marin/conf's TLS ports (tls_check.py), on the postgres-app the last build packaged.
#
#   marin/tls-check/run.sh
set -euo pipefail

MARIN=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
REPO=$(git -C "$MARIN" rev-parse --show-toplevel)
. "$MARIN/lane/tools.sh"
export PG_BINDIR
exec python3 -I -B "$MARIN/tls-check/tls_check.py" "$LANE_CACHE/tls-check/$(date -u +%Y%m%dT%H%M%SZ)" "$REPO" "$(testssl)"
