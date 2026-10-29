#!/usr/bin/env bash
# The fork's gate: build, James's own tests (without Docker, and on PostgreSQL), a time-zone matrix and imaptest,
# counted against marin/floors.json. A run lives in $LANE_CACHE/runs/<time>.
#
#   marin/lane.sh                     every stage
#   marin/lane.sh junit pg            only these stages (build, junit, pg, tz, imap)
#   marin/lane.sh --update ...        rewrite the floors from this run, to commit with the change that moved them
set -euo pipefail
shopt -s inherit_errexit

MARIN=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(git -C "$MARIN" rev-parse --show-toplevel)
. "$MARIN/lane/tools.sh"

UPDATE=()
STAGES=()
for arg in "$@"; do
    case $arg in
        --update) UPDATE=(--update) ;;
        build | junit | pg | tz | imap) STAGES+=("$arg") ;;
        *) echo "lane.sh: unknown argument $arg" >&2; exit 2 ;;
    esac
done
[[ ${#STAGES[@]} -gt 0 ]] || STAGES=(build junit pg tz imap)

JUNIT_MODULES=(
    protocols/api protocols/imap protocols/smtp
    mailet/base mailet/standard
    mailbox/store mailbox/memory mailbox/scanning-search mailbox/lucene
    server/protocols/protocols-smtp server/protocols/protocols-imap4 server/mailet/mailets
    server/protocols/jmap-rfc-8621
    server/protocols/jmap-rfc-8621-integration-tests/memory-jmap-rfc-8621-integration-tests
    mpt/impl/imap-mailbox/inmemory
)
PG_MODULES=(
    backends-common/postgres server/data/data-postgres server/data/data-jmap-postgres
    mailbox/postgres mpt/impl/imap-mailbox/postgres
)
TZ_ZONES=(America/Mexico_City America/Monterrey)
TZ_MODULES=(mailbox/store mailbox/memory mailbox/scanning-search mailbox/lucene)
TZ_JMAP=server/protocols/jmap-rfc-8621-integration-tests/memory-jmap-rfc-8621-integration-tests
TZ_JMAP_TESTS='MemoryEmailQueryMethodTest,MemoryEmailQueryMethodNoViewTest'

RUN=$LANE_CACHE/runs/$(date -u +%Y%m%dT%H%M%SZ)
mkdir -p "$RUN"
echo "lane: $(git -C "$REPO" rev-parse --short HEAD)$([[ -z $(git -C "$REPO" status --porcelain) ]] || echo ' (dirty)'), run $RUN"
QUIET=(-Dcheckstyle.skip -Drat.skip -Dmaven.javadoc.skip=true)

mvn_logged() {  # name mvn-args...
    local name=$1
    shift
    echo "lane: $name"
    if ! (cd "$REPO" && "${MVN[@]}" "$@") >"$RUN/$name.log" 2>&1; then
        echo "lane: $name failed; the end of $RUN/$name.log:" >&2
        grep -E '^\[ERROR\]' "$RUN/$name.log" | head -30 >&2
        return 1
    fi
}

tests() {  # stage modules-csv extra-args...: runs the modules' tests and keeps their reports
    local stage=$1 modules=$2 module
    shift 2
    for module in ${modules//,/ }; do rm -rf "$REPO/$module/target/surefire-reports"; done
    mvn_logged "$stage" -fae -pl "$modules" test "${QUIET[@]}" -Dmaven.test.failure.ignore=true \
        -Dsurefire.failIfNoSpecifiedTests=false "$@"
    for module in ${modules//,/ }; do
        if [[ -d $REPO/$module/target/surefire-reports ]]; then
            mkdir -p "$RUN/junit/$stage/$module"
            cp "$REPO/$module/target/surefire-reports"/TEST-*.xml "$RUN/junit/$stage/$module/"
        fi
    done
}

PG_PORT=
stop_all() {
    python3 -I "$MARIN/lane/james.py" stop "$RUN" || true
    [[ -z $PG_PORT ]] || "$PG_BINDIR/pg_ctl" -D "$RUN/pg" -m immediate stop >/dev/null 2>&1 || true
    rm -rf "$RUN/pg"
}
trap stop_all EXIT

start_postgres() {
    PG_PORT=$(python3 -I -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
    "$PG_BINDIR/initdb" -D "$RUN/pg" -U postgres --auth=scram-sha-256 --pwfile=<(echo postgres) -E UTF8 \
        --locale=C >"$RUN/pg-init.log"
    "$PG_BINDIR/pg_ctl" -D "$RUN/pg" -l "$RUN/pg.log" -w start -o "-p $PG_PORT -k $RUN -c listen_addresses=127.0.0.1 \
        -c max_connections=300 -c fsync=off -c synchronous_commit=off -c full_page_writes=off" >/dev/null
    PGPASSWORD=postgres psql -h 127.0.0.1 -p "$PG_PORT" -U postgres -d postgres -q \
        -c "CREATE ROLE james SUPERUSER LOGIN PASSWORD 'secret1'" -c "CREATE DATABASE james_lane OWNER james"
    echo "lane: PostgreSQL $("$PG_BINDIR/postgres" -V | awk '{print $3}') on 127.0.0.1:$PG_PORT"
}

run_imaptest() {
    local james user password port
    james=$(python3 -I "$MARIN/lane/james.py" start "$RUN" "$REPO" "$PG_PORT" james_lane)
    port=$(python3 -I -c 'import json, sys; print(json.loads(sys.argv[1])["imap_port"])' "$james")
    user=$(python3 -I -c 'import json, sys; print(json.loads(sys.argv[1])["user"])' "$james")
    password=$(python3 -I -c 'import json, sys; print(json.loads(sys.argv[1])["password"])' "$james")
    echo "lane: imaptest scripted"
    # imaptest exits 2 when a scripted test fails; count.py judges the result
    (cd "$IMAPTEST_SCRIPTS" && timeout 900 "$IMAPTEST" host=127.0.0.1 port="$port" user="$user" pass="$password" \
        test=. >"$RUN/imaptest-scripted.log" 2>&1) || [[ $? -eq 2 ]]
    echo "lane: imaptest stress"
    timeout 180 "$IMAPTEST" host=127.0.0.1 port="$port" user="$user" pass="$password" \
        mbox="$IMAPTEST_SCRIPTS/default.mbox" clients=10 secs=60 >"$RUN/imaptest-stress.log" 2>&1 || [[ $? -eq 2 ]]
    python3 -I "$MARIN/lane/james.py" stop "$RUN"
}

join() { local IFS=,; echo "$*"; }

for stage in "${STAGES[@]}"; do
    case $stage in
        build)
            mvn_logged build -T 8 -pl "$(join "${JUNIT_MODULES[@]}" "${PG_MODULES[@]}" server/apps/postgres-app)" -am \
                install -DskipTests -Dmaven.javadoc.skip=true
            ;;
        junit) TZ=UTC tests junit "$(join "${JUNIT_MODULES[@]}")" -T 2 ;;
        pg)
            [[ -n $PG_PORT ]] || start_postgres
            TZ=UTC tests pg "$(join "${PG_MODULES[@]}")" -Djames.postgres.external="127.0.0.1:$PG_PORT"
            ;;
        tz)
            for zone in "${TZ_ZONES[@]}"; do
                TZ=$zone tests "tz-${zone//\//_}" "$(join "${TZ_MODULES[@]}")" -T 2
                TZ=$zone tests "tz-${zone//\//_}-jmap" "$TZ_JMAP" -Dtest="$TZ_JMAP_TESTS"
            done
            ;;
        imap)
            [[ -n $PG_PORT ]] || start_postgres
            run_imaptest
            ;;
    esac
done

status=0
python3 -I "$MARIN/lane/excluded.py" "$REPO" || status=1
python3 -I "$MARIN/lane/count.py" "$RUN" "$MARIN/floors.json" "${UPDATE[@]}" || status=1
exit $status
