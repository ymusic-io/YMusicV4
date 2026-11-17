#!/usr/bin/env bash
# Workspace data dirs: each checkout (main or worktree) runs the desktop dev builds on its own copy of
# the release app's ~/.ymusic, so several branches run side by side without sharing data or sign-in.
#
#   tools/dev-data.sh create            give this checkout its data dir, copied from ~/.ymusic
#   tools/dev-data.sh remove [--keep-data]
#                                       forget this checkout's data dir and delete it (unless --keep-data)
#   tools/dev-data.sh list              show the data dirs and their checkouts
#   tools/dev-data.sh prune             delete the data dirs whose checkout is gone
#
# A checkout's dir is ~/.ymusic-dev/<name>/, <name> being the checkout's folder name (with a -2, -3, …
# suffix when another checkout holds it): `owner` holds the checkout's absolute path, `jvm/` is the
# Kotlin app's data dir and `rust/` the Rust app's. The checkout's gitignored `.ymusic-dev-data` holds
# `name=<name>` and `root=<dir>` lines; the Gradle run task and the Rust app read it.
#
# Both copies are made once, read only on ~/.ymusic, of everything but the instance lock/port, the logs,
# update downloads and the caches that refill themselves (SKIPPED below). Every SQLite database is
# copied with its WAL and checkpointed in the copy. In app.db the per-install telemetry/update rows are
# dropped, and a cookie jar sealed under the OS user's key gets that key as its secret row
# cookie-key.bin (where both dev apps keep it): read from the macOS Keychain or the Linux Secret
# Service (service io.ymusic.desktop, account cookie-key), never written back.
set -euo pipefail

DATA_ROOT="$HOME/.ymusic-dev"
RELEASE_DIR="$HOME/.ymusic"
DATA_FILE_NAME=.ymusic-dev-data
KEY_SERVICE=io.ymusic.desktop
RELEASE_KEY_ACCOUNT=cookie-key
FALLBACK_KEY_ROW=cookie-key.bin
COOKIE_JAR_ROW=api-cookies.json
SKIPPED="instance.lock instance.port logs.db logs.db-wal logs.db-shm logs update http-cache engine-cache player-cache image-source-cache image-cover-cache image-scaled-cache image-variants"
# Per-install state, not preferences: a copied device id would merge two installs' telemetry.
DROPPED_SETTINGS="'telemetry.deviceId','telemetry.lastLoadedEngineJsVer','telemetry.lastLoadedPlayerJsVer','telemetry.nativeBlockReportedVersion','update.lastRunVersionCode','update.autoDownloadFailed'"
# "YMC1U": a jar sealed under the OS user's key; "YMC1P": under the user's password (no key to copy).
USER_SEAL_HEX=594D433155
# Set to a keychain file to read the release key from it instead of the login keychain (tests).
KEYCHAIN="${DEV_DATA_KEYCHAIN:-}"

die() {
    echo "dev-data: $*" >&2
    exit 1
}

checkout_root() {
    local top
    top=$(git rev-parse --show-toplevel) || die "not inside a git checkout"
    (cd "$top" && pwd -P)
}

owner_of() {
    cat "$1/owner" 2>/dev/null || true
}

data_file_field() {
    sed -n "s/^$2=//p" "$1"
}

lock_root() {
    mkdir -p "$DATA_ROOT"
    local tries=0
    until mkdir "$DATA_ROOT/.lock" 2>/dev/null; do
        tries=$((tries + 1))
        [ "$tries" -lt 50 ] || die "$DATA_ROOT/.lock is held; remove it if no create is running"
        sleep 0.2
    done
    trap 'rmdir "$DATA_ROOT/.lock" 2>/dev/null || true' EXIT
}

# Hex of the release app's sign-in key, or nothing when it cannot be read.
release_key_hex() {
    local db=$1
    case "$(uname -s)" in
        Darwin)
            local out
            echo "  macOS may ask to let 'security' read the YMusic sign-in key: click Allow, NOT Always Allow (that adds 'security' to the key's access list for good, so any process could then read the key through it)" >&2
            local keychain=()
            if [ -n "$KEYCHAIN" ]; then keychain=("$KEYCHAIN"); fi
            out=$(security find-generic-password -s "$KEY_SERVICE" -a "$RELEASE_KEY_ACCOUNT" -w ${keychain[@]+"${keychain[@]}"} 2>/dev/null) || return 0
            # `security -w` prints a password that is not text as hex.
            if printf '%s' "$out" | grep -Eq '^[0-9a-fA-F]{64}$'; then
                printf '%s' "$out"
            else
                printf '%s' "$out" | od -An -tx1 | tr -d ' \n'
            fi
            ;;
        Linux)
            local stored
            stored=$(sqlite3 "$db" "SELECT hex(value) FROM secret WHERE name='$FALLBACK_KEY_ROW';")
            if [ -n "$stored" ]; then
                printf '%s' "$stored"
                return 0
            fi
            command -v secret-tool >/dev/null || return 0
            local encoded
            encoded=$(secret-tool lookup service "$KEY_SERVICE" account "$RELEASE_KEY_ACCOUNT" 2>/dev/null) || return 0
            printf '%s' "$encoded" | base64 -d | od -An -tx1 | tr -d ' \n'
            ;;
    esac
}

is_skipped() {
    local name=$1 skipped
    for skipped in $SKIPPED; do
        if [ "$name" = "$skipped" ]; then return 0; fi
    done
    return 1
}

# Copies ~/.ymusic into [target] (absent), leaving the caches out and every database consistent.
copy_release_data() {
    local target=$1 entry
    mkdir -p "$target"
    for entry in "$RELEASE_DIR"/* "$RELEASE_DIR"/.[!.]*; do
        [ -e "$entry" ] || continue
        if is_skipped "$(basename "$entry")"; then continue; fi
        cp -pR "$entry" "$target/"
    done
    # A copied -shm is the source's live index, not this copy's: SQLite rebuilds it from the WAL.
    find "$target" -type f -name '*.db-shm' -exec rm -f {} +
}

# Folds every database's WAL into the database, so each is one self-contained file.
checkpoint_databases() {
    local target=$1 db
    while IFS= read -r db; do
        sqlite3 "$db" "PRAGMA wal_checkpoint(TRUNCATE);" >/dev/null || die "the copy of ${db#"$target"/} does not open"
        rm -f "$db-wal" "$db-shm"
    done < <(find "$target" -type f -name '*.db')
}

prepare_app_db() {
    local db=$1
    [ -f "$db" ] || return 0
    sqlite3 "$db" "DELETE FROM setting WHERE key IN ($DROPPED_SETTINGS);"
    local seal
    seal=$(sqlite3 "$db" "SELECT hex(substr(value, 1, 5)) FROM secret WHERE name='$COOKIE_JAR_ROW';")
    if [ "$seal" = "$USER_SEAL_HEX" ]; then
        local key_hex
        key_hex=$(release_key_hex "$db")
        if [ ${#key_hex} -eq 64 ]; then
            sqlite3 "$db" "INSERT OR REPLACE INTO secret(name, value) VALUES('$FALLBACK_KEY_ROW', X'$key_hex');"
            echo "  sign-in key copied to app.db ($FALLBACK_KEY_ROW)"
        else
            sqlite3 "$db" "DELETE FROM secret WHERE name='$COOKIE_JAR_ROW';"
            echo "  the release sign-in key is unreadable: the copy starts signed out"
        fi
    elif [ -z "$seal" ]; then
        echo "  ~/.ymusic is signed out"
    else
        echo "  the sign-in is password-protected: it asks for the same password"
    fi
}

cmd_create() {
    local root data_file
    root=$(checkout_root)
    data_file="$root/$DATA_FILE_NAME"
    if [ -f "$data_file" ]; then
        cat "$data_file"
        return 0
    fi
    if [ -d "$RELEASE_DIR" ] && ! command -v sqlite3 >/dev/null; then
        die "copying $RELEASE_DIR needs the sqlite3 command line tool"
    fi
    lock_root
    local base name dir suffix=1
    base=$(printf '%s' "$(basename "$root")" | tr -c 'A-Za-z0-9._-' '-')
    name=$base
    while [ -e "$DATA_ROOT/$name" ] && [ "$(owner_of "$DATA_ROOT/$name")" != "$root" ]; do
        suffix=$((suffix + 1))
        name="$base-$suffix"
    done
    dir="$DATA_ROOT/$name"
    if [ -d "$dir/jvm" ] && [ -d "$dir/rust" ]; then
        echo "reusing $dir"
    else
        rm -rf "$dir/jvm.copying" "$dir/rust.copying"
        mkdir -p "$dir"
        printf '%s\n' "$root" >"$dir/owner"
        if [ -d "$RELEASE_DIR" ]; then
            echo "copying $RELEASE_DIR into $dir"
            copy_release_data "$dir/jvm.copying"
            prepare_app_db "$dir/jvm.copying/app.db"
            checkpoint_databases "$dir/jvm.copying"
        else
            echo "no $RELEASE_DIR: $dir starts empty"
            mkdir -p "$dir/jvm.copying"
        fi
        cp -pR "$dir/jvm.copying" "$dir/rust.copying"
        mv "$dir/jvm.copying" "$dir/jvm"
        mv "$dir/rust.copying" "$dir/rust"
    fi
    printf '%s\n' "$root" >"$dir/owner"
    printf 'name=%s\nroot=%s\n' "$name" "$dir" >"$data_file"
    cat "$data_file"
}

cmd_remove() {
    local keep=0
    case "${1:-}" in
        --keep-data) keep=1 ;;
        "") ;;
        *) die "remove takes only --keep-data" ;;
    esac
    local root data_file
    root=$(checkout_root)
    data_file="$root/$DATA_FILE_NAME"
    [ -f "$data_file" ] || die "$root has no data dir"
    local dir
    dir=$(data_file_field "$data_file" root)
    rm -f "$data_file"
    if [ "$keep" = 1 ]; then
        echo "kept $dir (tools/dev-data.sh prune deletes it once the checkout is gone)"
    elif [ -n "$dir" ] && [ "$(owner_of "$dir")" = "$root" ]; then
        rm -rf "$dir"
        echo "deleted $dir"
    else
        echo "$dir belongs to another checkout: left as is"
    fi
}

cmd_list() {
    local dir
    for dir in "$DATA_ROOT"/*/; do
        [ -d "$dir" ] || continue
        dir=${dir%/}
        local owner size
        owner=$(owner_of "$dir")
        size=$(du -sh "$dir" 2>/dev/null | cut -f1)
        if [ -z "$owner" ]; then
            echo "$(basename "$dir")  $size  no owner (prune deletes it)"
        elif [ -d "$owner" ]; then
            echo "$(basename "$dir")  $size  $owner"
        else
            echo "$(basename "$dir")  $size  gone: $owner (prune deletes it)"
        fi
    done
}

cmd_prune() {
    lock_root
    local dir
    for dir in "$DATA_ROOT"/*/; do
        [ -d "$dir" ] || continue
        dir=${dir%/}
        local owner
        owner=$(owner_of "$dir")
        if [ -n "$owner" ] && [ -d "$owner" ]; then continue; fi
        rm -rf "$dir"
        echo "deleted $dir"
    done
}

case "${1:-}" in
    create) cmd_create ;;
    remove)
        shift
        cmd_remove "$@"
        ;;
    list) cmd_list ;;
    prune) cmd_prune ;;
    *) die "usage: tools/dev-data.sh create | remove [--keep-data] | list | prune" ;;
esac
