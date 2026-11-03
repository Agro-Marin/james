#!/usr/bin/env bash
# Builds the deployable James: the postgres-app jar, its libraries, and its configuration (the app's sample
# configuration, sample-configuration-single, then marin/conf) in one tarball named after the commit. The
# configuration takes every site value and secret from ${env:...}: agromarin-infra's mail role supplies them.
#
#   marin/release.sh <output dir>       -> <output dir>/james-<commit>.tar.gz
set -euo pipefail

MARIN=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(git -C "$MARIN" rev-parse --show-toplevel)
OUT=$(mkdir -p "$1" && cd "$1" && pwd)
[[ -z $(git -C "$REPO" status --porcelain) ]] || { echo "release.sh: the tree has changes; commit them first" >&2; exit 2; }
COMMIT=$(git -C "$REPO" rev-parse --short=10 HEAD)
. "$MARIN/lane/tools.sh"

APP=$REPO/server/apps/postgres-app
(cd "$REPO" && "${MVN[@]}" -q -pl server/apps/postgres-app -am install -DskipTests -Dmaven.javadoc.skip=true)
STAGE=$(mktemp -d "$LANE_CACHE/release.XXXXXX")
trap 'rm -rf "$STAGE"' EXIT
ROOT=$STAGE/james-$COMMIT
mkdir -p "$ROOT/conf"
cp "$APP/target/james-server-postgres-app.jar" "$ROOT/"
cp -r "$APP/target/james-server-postgres-app.lib" "$ROOT/"
cp "$APP"/sample-configuration/* "$APP"/sample-configuration-single/* "$ROOT/conf/"
find "$MARIN/conf" -maxdepth 1 -type f ! -name README.md -exec cp {} "$ROOT/conf/" \;
echo "$COMMIT" >"$ROOT/COMMIT"
tar -czf "$OUT/james-$COMMIT.tar.gz" -C "$STAGE" "james-$COMMIT"
echo "$OUT/james-$COMMIT.tar.gz"
