#!/usr/bin/env bash
# Fetches and builds the lane's tools into $LANE_CACHE, once. Sourced by lane.sh; exports JAVA_HOME, MVN, IMAPTEST, PG_BINDIR.
set -euo pipefail
shopt -s inherit_errexit

LANE_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
. "$LANE_DIR/toolchain.env"
LANE_CACHE=${LANE_CACHE:-$HOME/.cache/james-lane}
DL=$LANE_CACHE/dl
LOGS=$LANE_CACHE/logs
mkdir -p "$DL" "$LOGS"

logged() {  # name command... : runs it with its output in $LOGS/name.log, shown only if it fails
    local name=$1
    shift
    if ! "$@" >"$LOGS/$name.log" 2>&1; then
        echo "tools.sh: $name failed; the end of $LOGS/$name.log:" >&2
        tail -30 "$LOGS/$name.log" >&2
        return 1
    fi
}

fetch() {  # url algorithm digest -> path of the verified file
    local url=$1 algorithm=$2 digest=$3 file
    file=$DL/$(basename "${url%%\?*}")
    file=${file//%2B/+}
    if [[ ! -f $file ]] || ! echo "$digest  $file" | "${algorithm}sum" -c --status; then
        curl -fsSL -o "$file.part" "$url"
        if ! echo "$digest  $file.part" | "${algorithm}sum" -c --status; then
            echo "tools.sh: $url does not match its pinned $algorithm" >&2
            rm -f "$file.part"
            return 1
        fi
        mv "$file.part" "$file"
    fi
    echo "$file"
}

jdk() {
    local home=$LANE_CACHE/jdk-$JDK_VERSION
    if [[ ! -x $home/bin/java ]]; then
        tar -xzf "$(fetch "$JDK_URL" sha256 "$JDK_SHA256")" -C "$LANE_CACHE"
    fi
    echo "$home"
}

maven() {
    local home=$LANE_CACHE/apache-maven-$MAVEN_VERSION
    if [[ ! -x $home/bin/mvn ]]; then
        tar -xzf "$(fetch "$MAVEN_URL" sha512 "$MAVEN_SHA512")" -C "$LANE_CACHE"
    fi
    echo "$home"
}

autotools() {
    local prefix=$LANE_CACHE/autotools url sha build dir
    if [[ ! -x $prefix/bin/flex ]]; then
        build=$LANE_CACHE/autotools-build
        rm -rf "$build" "$prefix" && mkdir -p "$build" "$prefix/share/aclocal"
        for tool in M4 AUTOCONF AUTOMAKE LIBTOOL BISON FLEX; do
            url=${tool}_URL sha=${tool}_SHA256
            tar -xf "$(fetch "${!url}" sha256 "${!sha}")" -C "$build"
            dir=$(basename "${!url}")
            # Their gnulib predates GCC 15's C23 default
            logged "build-${tool,,}" bash -c 'cd "$1" && export PATH=$2/bin:$PATH CFLAGS="-O2 -std=gnu17" \
                && ./configure --prefix="$2" && make -j"$(nproc)" install' _ "$build/${dir%.tar.*}" "$prefix"
        done
        tar -xJf "$(fetch "$GETTEXT_URL" sha256 "$GETTEXT_SHA256")" -C "$prefix/share/aclocal" --strip-components=3 \
            --wildcards '*/gettext-runtime/gnulib-m4/iconv.m4' '*/gettext-runtime/gnulib-m4/lib-*.m4' \
            '*/gettext-runtime/gnulib-m4/host-cpu-c-abi.m4'
        rm -rf "$build"
    fi
    echo "$prefix"
}

checkout() {  # repo commit dir
    rm -rf "$3"
    git clone -q --filter=blob:none --no-checkout "$1" "$3"
    git -C "$3" -c advice.detachedHead=false checkout -q "$2"
}

dovecot() {
    local src=$LANE_CACHE/dovecot-$DOVECOT_COMMIT tools
    if [[ ! -f $src/dovecot-config ]]; then
        tools=$(autotools)
        checkout "$DOVECOT_REPO" "$DOVECOT_COMMIT" "$src"
        logged build-dovecot bash -c 'cd "$1" && export PATH=$2/bin:$PATH && autoreconf -vif \
            && ./configure --without-lz4 --without-zstd && make -j"$(nproc)"' _ "$src" "$tools"
    fi
    echo "$src"
}

imaptest() {
    local src=$LANE_CACHE/imaptest-$IMAPTEST_COMMIT tools core
    if [[ ! -x $src/src/imaptest ]]; then
        tools=$(autotools)
        core=$(dovecot)
        checkout "$IMAPTEST_REPO" "$IMAPTEST_COMMIT" "$src"
        logged build-imaptest bash -c 'cd "$1" && export PATH=$2/bin:$PATH && autoreconf -vif \
            && ./configure --with-dovecot="$3" && make -j"$(nproc)"' _ "$src" "$tools" "$core"
    fi
    echo "$src"
}

postgres_bindir() {
    local bindir=${PG_BINDIR:-/usr/lib/postgresql/$PG_MAJOR/bin}
    if [[ ! -x $bindir/initdb ]]; then
        echo "tools.sh: no PostgreSQL $PG_MAJOR initdb in $bindir; set PG_BINDIR" >&2
        return 1
    fi
    echo "$bindir"
}

JAVA_HOME=$(jdk)
MAVEN_HOME=$(maven)
IMAPTEST_SRC=$(imaptest)
IMAPTEST=$IMAPTEST_SRC/src/imaptest
IMAPTEST_SCRIPTS=$IMAPTEST_SRC/src/tests
PG_BINDIR=$(postgres_bindir)
export JAVA_HOME PATH=$JAVA_HOME/bin:$MAVEN_HOME/bin:$PATH MAVEN_OPTS=${MAVEN_OPTS:--Xmx4g}
MVN=(mvn -B -Dmaven.repo.local="$LANE_CACHE/m2")
