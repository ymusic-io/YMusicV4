#!/usr/bin/env bash
# Workspace data dirs: each checkout (main or worktree) runs the desktop dev builds on its own copy of
# the release app's ~/.ymusic, so several branches run side by side without sharing data or sign-in.
#
#   tools/dev-data.sh create            give this checkout its data dir, copied from ~/.ymusic
#   tools/dev-data.sh remove [--keep-data]
#                                       forget this checkout's data dir and delete it (unless --keep-data)
#   tools/dev-data.sh release <checkout> <dir> [--keep-data]
#                                       delete <dir> if <checkout> owns it (dev-worktree.sh remove, once
#                                       the checkout is gone)
#   tools/dev-data.sh list              show the data dirs and their checkouts
#   tools/dev-data.sh prune [-y]        delete the data dirs whose checkout is gone, after confirmation
#
# A checkout's dir is ~/.ymusic-dev/<name>/, <name> being the checkout's folder name (with a -2, -3, …
# suffix when another checkout holds it): `owner` holds the checkout's absolute path, `jvm/` is the
# Kotlin app's data dir and `rust/` the Rust app's. The checkout's gitignored `.ymusic-dev-data` holds
# `name=<name>` and `root=<dir>` lines; the Gradle run task and the Rust app read it.
#
# Both copies are made once, read only on ~/.ymusic, of everything but the instance lock/port, the logs,
# update downloads, the download work dir and the caches that refill themselves (SKIPPED below). Every
# SQLite database is copied with sqlite3 .backup from a read-only connection, a consistent snapshot even
# while the release app writes (on macOS it takes no instance lock, so whether it runs cannot be told),
# then must pass integrity_check. In app.db the per-install telemetry/update rows and the download
# location are dropped, and a cookie jar sealed under the OS user's key gets that key as its secret row
# cookie-key.bin (where both dev apps keep it): read from the macOS Keychain or the Linux Secret
# Service (service io.ymusic.desktop, account cookie-key), never written back.
#
# The library is copied as is: its rows still name the real files, so deleting or renaming a track in
# a workspace deletes or renames the real one.
set -euo pipefail

DATA_ROOT="$HOME/.ymusic-dev"
RELEASE_DIR="$HOME/.ymusic"
DATA_FILE_NAME=.ymusic-dev-data
KEY_SERVICE=io.ymusic.desktop
RELEASE_KEY_ACCOUNT=cookie-key
FALLBACK_KEY_ROW=cookie-key.bin
COOKIE_JAR_ROW=api-cookies.json
SKIPPED="instance.lock instance.port logs.db logs.db-wal logs.db-shm logs update download-work http-cache engine-cache player-cache image-source-cache image-cover-cache image-scaled-cache image-variants"
# Per-install state, not preferences: a copied device id would merge two installs' telemetry. The
# download location is the user's real music folder.
DROPPED_SETTINGS="'telemetry.deviceId','telemetry.lastLoadedEngineJsVer','telemetry.lastLoadedPlayerJsVer','telemetry.nativeBlockReportedVersion','update.lastRunVersionCode','update.autoDownloadFailed','download.location'"
# "YMC1U": a jar sealed under the OS user's key; "YMC1P": under the user's password (no key to copy).
USER_SEAL_HEX=594D433155
# Set to a keychain file to read the release key from it instead of the login keychain (tests).
KEYCHAIN="${DEV_DATA_KEYCHAIN:-}"

LOCK_HELD=0
# Deleted on exit unless create finishes: a half-made copy may hold the sign-in key.
PARTIAL_DIRS=()
COPIED_DATABASES=()

on_exit() {
    local dir
    for dir in ${PARTIAL_DIRS[@]+"${PARTIAL_DIRS[@]}"}; do rm -rf "$dir"; done
    if [ "$LOCK_HELD" = 1 ]; then rmdir "$DATA_ROOT/.lock" 2>/dev/null || true; fi
}
trap on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP

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
    LOCK_HELD=1
}

# Runs each argument as a statement, fed on stdin: printf is a builtin, so a key in one never shows in
# a process's argv (ps, /proc).
run_sql() {
    local db=$1
    shift
    printf '%s\n' "$@" | sqlite3 -bail "$db"
}

# 32-bit FNV-1a over the UTF-8 bytes, as 8 lowercase hex digits: the apps' workspace tag.
fnv1a32_hex() {
    local hash=$((0x811c9dc5)) byte
    for byte in $(printf '%s' "$1" | od -An -v -tu1); do
        hash=$((((hash ^ byte) * 0x01000193) & 0xffffffff))
    done
    printf '%08x\n' "$hash"
}

# The hidden desktop entries the workspace's Linux runs write: the JVM app's and the Rust app's.
remove_desktop_entries() {
    local tag apps file
    tag=$(fnv1a32_hex "$1")
    apps="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
    for file in "$apps/io.ymusic.desktop.dev$tag.desktop" "$apps/io.ymusic.YMusic.dev$tag.desktop"; do
        if [ -e "$file" ] || [ -L "$file" ]; then
            rm -f "$file"
            echo "  deleted $file"
        fi
    done
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
            stored=$(run_sql "$db" "SELECT hex(value) FROM secret WHERE name='$FALLBACK_KEY_ROW';")
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

is_sqlite() {
    [ -f "$1" ] && [ ! -L "$1" ] && [ "$(head -c 15 "$1" 2>/dev/null)" = "SQLite format 3" ]
}

# A database's -wal, -shm or -journal: .backup reads through them, so they are not copied.
is_sqlite_sidecar() {
    case "$1" in
        *-wal) is_sqlite "${1%-wal}" ;;
        *-shm) is_sqlite "${1%-shm}" ;;
        *-journal) is_sqlite "${1%-journal}" ;;
        *) return 1 ;;
    esac
}

# A read-only URI for sqlite3: % ? # are the characters a URI path cannot carry as they are.
read_only_uri() {
    local path=$1
    path=${path//[%]/%25}
    path=${path//[?]/%3f}
    path=${path//[#]/%23}
    printf 'file:%s?mode=ro%s' "$path" "${2:-}"
}

backup_database() {
    local source=$1 target=$2 output
    case "$target" in *"'"*) die "$target: a path with a ' cannot be named to sqlite3's .backup" ;; esac
    if output=$(printf ".backup '%s'\n" "$target" | sqlite3 -bail "$(read_only_uri "$source")" 2>&1); then
        COPIED_DATABASES+=("$target")
        return 0
    fi
    rm -f "$target"
    # A WAL database closed by its writer has no -shm, which a read-only open cannot make; with no WAL
    # content left either, the file alone is the whole database.
    if [ ! -s "$source-wal" ] \
        && output=$(printf ".backup '%s'\n" "$target" | sqlite3 -bail "$(read_only_uri "$source" '&immutable=1')" 2>&1); then
        COPIED_DATABASES+=("$target")
        return 0
    fi
    rm -f "$target"
    die "${source#"$RELEASE_DIR"/} could not be copied: $output"
}

# Copies [source] into [target] (absent), each database through backup_database and the rest as files.
copy_tree() {
    local source=$1 target=$2 entry name
    mkdir -p "$target"
    for entry in "$source"/* "$source"/.[!.]* "$source"/..?*; do
        [ -e "$entry" ] || [ -L "$entry" ] || continue
        name=$(basename "$entry")
        if [ "$source" = "$RELEASE_DIR" ] && is_skipped "$name"; then continue; fi
        if [ -d "$entry" ] && [ ! -L "$entry" ]; then
            if [ -n "$(find "$entry" -type f \( -name '*.db' -o -name '*.sqlite*' \) -print -quit)" ]; then
                copy_tree "$entry" "$target/$name"
            else
                cp -pR "$entry" "$target/"
            fi
        elif is_sqlite "$entry"; then
            backup_database "$entry" "$target/$name"
        elif is_sqlite_sidecar "$entry"; then
            continue
        else
            cp -pR "$entry" "$target/"
        fi
    done
}

# Every copied database must pass integrity_check, and ends as one self-contained file.
verify_databases() {
    local target=$1 db result
    for db in ${COPIED_DATABASES[@]+"${COPIED_DATABASES[@]}"}; do
        result=$(run_sql "$db" "PRAGMA integrity_check;" 2>&1) || die "the copy of ${db#"$target"/} does not open: $result"
        [ "$result" = ok ] || die "the copy of ${db#"$target"/} fails integrity_check: $(printf '%s\n' "$result" | head -5)"
        run_sql "$db" "PRAGMA wal_checkpoint(TRUNCATE);" >/dev/null || die "the copy of ${db#"$target"/} cannot be checkpointed"
        rm -f "$db-wal" "$db-shm"
    done
}

prepare_app_db() {
    local db=$1
    [ -f "$db" ] || return 0
    run_sql "$db" "DELETE FROM setting WHERE key IN ($DROPPED_SETTINGS);"
    local seal
    seal=$(run_sql "$db" "SELECT hex(substr(value, 1, 5)) FROM secret WHERE name='$COOKIE_JAR_ROW';")
    if [ "$seal" = "$USER_SEAL_HEX" ]; then
        local key_hex
        key_hex=$(release_key_hex "$db")
        if [ ${#key_hex} -eq 64 ]; then
            run_sql "$db" "INSERT OR REPLACE INTO secret(name, value) VALUES('$FALLBACK_KEY_ROW', X'$key_hex');"
            echo "  sign-in key copied to app.db ($FALLBACK_KEY_ROW)"
        else
            run_sql "$db" "DELETE FROM secret WHERE name='$COOKIE_JAR_ROW';"
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
        if [ ! -e "$dir" ]; then PARTIAL_DIRS+=("$dir"); fi
        PARTIAL_DIRS+=("$dir/jvm.copying" "$dir/rust.copying")
        rm -rf "$dir/jvm.copying" "$dir/rust.copying"
        mkdir -p "$dir"
        printf '%s\n' "$root" >"$dir/owner"
        if [ -d "$RELEASE_DIR" ]; then
            echo "copying $RELEASE_DIR into $dir"
            copy_tree "$RELEASE_DIR" "$dir/jvm.copying"
            prepare_app_db "$dir/jvm.copying/app.db"
            verify_databases "$dir/jvm.copying"
            echo "  the library still names your real files: deleting or renaming a track in this workspace deletes or renames the real one"
            echo "  the download location is not copied: this workspace asks for its own folder"
        else
            echo "no $RELEASE_DIR: $dir starts empty"
            mkdir -p "$dir/jvm.copying"
        fi
        cp -pR "$dir/jvm.copying" "$dir/rust.copying"
        mv "$dir/jvm.copying" "$dir/jvm"
        mv "$dir/rust.copying" "$dir/rust"
        PARTIAL_DIRS=()
    fi
    printf '%s\n' "$root" >"$dir/owner"
    printf 'name=%s\nroot=%s\n' "$name" "$dir" >"$data_file"
    cat "$data_file"
}

# Deletes [dir] (unless [keep] is 1) and its desktop entries, when [owner] owns it.
release_dir() {
    local owner=$1 dir=$2 keep=$3
    if [ -z "$dir" ] || [ "$(owner_of "$dir")" != "$owner" ]; then
        echo "${dir:-the data dir} belongs to another checkout: left as is"
        return 0
    fi
    remove_desktop_entries "$(basename "$dir")"
    if [ "$keep" = 1 ]; then
        echo "kept $dir (tools/dev-data.sh prune deletes it once the checkout is gone)"
    else
        rm -rf "$dir"
        echo "deleted $dir"
    fi
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
    release_dir "$root" "$dir" "$keep"
}

cmd_release() {
    local owner="" dir="" keep=0
    while [ $# -gt 0 ]; do
        case "$1" in
            --keep-data) keep=1 ;;
            *)
                if [ -z "$owner" ]; then owner=$1; elif [ -z "$dir" ]; then dir=$1; else die "usage: tools/dev-data.sh release <checkout> <dir> [--keep-data]"; fi
                ;;
        esac
        shift
    done
    [ -n "$owner" ] && [ -n "$dir" ] || die "usage: tools/dev-data.sh release <checkout> <dir> [--keep-data]"
    release_dir "$owner" "$dir" "$keep"
}

# present, none (no owner file), unreachable (the closest existing ancestor is a mount root: the
# owner's volume may only be unmounted) or gone.
owner_state() {
    local owner=$1 ancestor
    if [ -z "$owner" ]; then
        echo none
        return 0
    fi
    if [ -d "$owner" ]; then
        echo present
        return 0
    fi
    ancestor=$(dirname "$owner")
    while [ ! -d "$ancestor" ]; do ancestor=$(dirname "$ancestor"); done
    case "$ancestor" in
        / | /Volumes | /media | /mnt | /run/media | "/media/${USER:-}" | "/run/media/${USER:-}") echo unreachable ;;
        *) echo gone ;;
    esac
}

cmd_list() {
    local dir
    for dir in "$DATA_ROOT"/*/; do
        [ -d "$dir" ] || continue
        dir=${dir%/}
        local owner size
        owner=$(owner_of "$dir")
        size=$(du -sh "$dir" 2>/dev/null | cut -f1)
        case "$(owner_state "$owner")" in
            present) echo "$(basename "$dir")  $size  $owner" ;;
            none) echo "$(basename "$dir")  $size  no owner (prune deletes it)" ;;
            unreachable) echo "$(basename "$dir")  $size  unreachable: $owner (its volume is not mounted?)" ;;
            gone) echo "$(basename "$dir")  $size  gone: $owner (prune deletes it)" ;;
        esac
    done
}

cmd_prune() {
    local yes=0
    case "${1:-}" in
        -y | --yes) yes=1 ;;
        "") ;;
        *) die "prune takes only -y" ;;
    esac
    lock_root
    local dir owner doomed=()
    for dir in "$DATA_ROOT"/*/; do
        [ -d "$dir" ] || continue
        dir=${dir%/}
        owner=$(owner_of "$dir")
        case "$(owner_state "$owner")" in
            present) ;;
            unreachable) echo "skipped $dir: $owner is unreachable (its volume is not mounted?)" ;;
            *) doomed+=("$dir") ;;
        esac
    done
    if [ ${#doomed[@]} -eq 0 ]; then
        echo "nothing to prune"
        return 0
    fi
    echo "data dirs whose checkout is gone:"
    for dir in "${doomed[@]}"; do
        owner=$(owner_of "$dir")
        echo "  $dir  (${owner:-no owner})"
    done
    if [ "$yes" != 1 ]; then
        [ -t 0 ] || die "rerun with -y to delete them"
        local answer
        read -r -p "delete them? [y/N] " answer || answer=""
        case "$answer" in
            y | Y | yes) ;;
            *)
                echo "left as is"
                return 0
                ;;
        esac
    fi
    for dir in "${doomed[@]}"; do
        remove_desktop_entries "$(basename "$dir")"
        rm -rf "$dir"
        echo "deleted $dir"
    done
}

case "${1:-}" in
    create) cmd_create ;;
    remove | release | prune)
        command=$1
        shift
        "cmd_$command" "$@"
        ;;
    list) cmd_list ;;
    *) die "usage: tools/dev-data.sh create | remove [--keep-data] | release <checkout> <dir> [--keep-data] | list | prune [-y]" ;;
esac
