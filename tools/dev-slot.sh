#!/usr/bin/env bash
# Dev data slots: each checkout (main or worktree) gets its own numbered data folder, so desktop dev
# builds of several branches run side by side without sharing data, sign-in or OS identity.
#
#   tools/dev-slot.sh claim            claim a slot for this checkout, seeded from ~/.ymusic
#   tools/dev-slot.sh release [--wipe] give this checkout's slot back; --wipe also deletes its data
#   tools/dev-slot.sh list             show the slots and their owners
#
# Slot N is ~/.ymusic-dev/N/: `owner` holds the owning checkout's absolute path, `jvm/` is the Kotlin
# app's data dir and `rust/` the Rust app's. The checkout's gitignored `.ymusic-dev-slot` holds
# `slot=N` and `root=<slot dir>` lines; the Gradle run task and the Rust run helpers read it.
#
# A new slot is seeded once from the release app's ~/.ymusic (read only): app.db (settings and the
# sealed cookie jar, minus per-install telemetry/update rows) and window.properties. A sign-in key
# held outside app.db is copied too: on macOS into the slot's own Keychain item (service
# io.ymusic.desktop, account cookie-key.dev<N>), and as app.db's cookie-key.bin row for the Rust
# debug build; on Linux from the Secret Service into app.db's cookie-key.bin row of both apps.
set -euo pipefail

SLOTS_ROOT="$HOME/.ymusic-dev"
RELEASE_DIR="$HOME/.ymusic"
MAX_SLOTS=9
SLOT_FILE_NAME=.ymusic-dev-slot
KEY_SERVICE=io.ymusic.desktop
RELEASE_KEY_ACCOUNT=cookie-key
FALLBACK_KEY_ROW=cookie-key.bin
COOKIE_JAR_ROW=api-cookies.json
# Per-install state, not preferences: a copied device id would merge two installs' telemetry.
SEED_DROPPED_SETTINGS="'telemetry.deviceId','telemetry.lastLoadedEngineJsVer','telemetry.lastLoadedPlayerJsVer','telemetry.nativeBlockReportedVersion','update.lastRunVersionCode','update.autoDownloadFailed'"
# "YMC1U": a jar sealed under the OS user's key; "YMC1P": under the user's password (no key to copy).
USER_SEAL_HEX=594D433155
# Set to a keychain file to keep every Keychain read and write off the login keychain (tests).
KEYCHAIN="${DEV_SLOT_KEYCHAIN:-}"

die() {
    echo "dev-slot: $*" >&2
    exit 1
}

checkout_root() {
    local top
    top=$(git rev-parse --show-toplevel) || die "not inside a git checkout"
    (cd "$top" && pwd -P)
}

slot_key_account() {
    echo "$RELEASE_KEY_ACCOUNT.dev$1"
}

keychain_args() {
    if [ -n "$KEYCHAIN" ]; then echo "$KEYCHAIN"; fi
}

lock_slots() {
    mkdir -p "$SLOTS_ROOT"
    local tries=0
    until mkdir "$SLOTS_ROOT/.lock" 2>/dev/null; do
        tries=$((tries + 1))
        [ "$tries" -lt 50 ] || die "$SLOTS_ROOT/.lock is held; remove it if no claim is running"
        sleep 0.2
    done
    trap 'rmdir "$SLOTS_ROOT/.lock" 2>/dev/null || true' EXIT
}

slot_owner() {
    cat "$SLOTS_ROOT/$1/owner" 2>/dev/null || true
}

# Hex of the release app's sign-in key, or nothing when it cannot be read.
release_key_hex() {
    local db=$1
    case "$(uname -s)" in
        Darwin)
            local out
            # shellcheck disable=SC2046
            out=$(security find-generic-password -s "$KEY_SERVICE" -a "$RELEASE_KEY_ACCOUNT" -w $(keychain_args) 2>/dev/null) || return 0
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

# Makes the copied jar in [db] openable by [app] in slot [n] under the key [key_hex].
install_key() {
    local app=$1 n=$2 key_hex=$3 db=$4
    if [ "$app" = jvm ] && [ "$(uname -s)" = Darwin ]; then
        # shellcheck disable=SC2046
        security add-generic-password -U -s "$KEY_SERVICE" -a "$(slot_key_account "$n")" \
            -l "YMusic dev slot $n sign-in key" -X "$key_hex" $(keychain_args) \
            || die "could not write the Keychain item $KEY_SERVICE/$(slot_key_account "$n")"
        echo "    sign-in key copied to the Keychain item $KEY_SERVICE/$(slot_key_account "$n")"
    else
        sqlite3 "$db" "INSERT OR REPLACE INTO secret(name, value) VALUES('$FALLBACK_KEY_ROW', X'$key_hex');"
        echo "    sign-in key copied to app.db ($FALLBACK_KEY_ROW)"
    fi
}

seed_app_db() {
    local target=$1 app=$2 n=$3
    if ! command -v sqlite3 >/dev/null; then
        echo "    sqlite3 not found: settings and sign-in not seeded"
        return 0
    fi
    local staged="$target/app.db.seeding"
    rm -f "$staged" "$staged-wal" "$staged-shm"
    # Plain file copies: nothing ever opens, locks or writes the release app's files.
    cp "$RELEASE_DIR/app.db" "$staged"
    if [ -f "$RELEASE_DIR/app.db-wal" ]; then cp "$RELEASE_DIR/app.db-wal" "$staged-wal"; fi
    chmod 600 "$staged"
    sqlite3 "$staged" "DELETE FROM setting WHERE key IN ($SEED_DROPPED_SETTINGS);"
    local seal
    seal=$(sqlite3 "$staged" "SELECT hex(substr(value, 1, 5)) FROM secret WHERE name='$COOKIE_JAR_ROW';")
    if [ "$seal" = "$USER_SEAL_HEX" ]; then
        local key_hex
        key_hex=$(release_key_hex "$staged")
        if [ ${#key_hex} -eq 64 ]; then
            install_key "$app" "$n" "$key_hex" "$staged"
        else
            sqlite3 "$staged" "DELETE FROM secret WHERE name='$COOKIE_JAR_ROW';"
            echo "    the release sign-in key is unreadable: settings seeded, the slot starts signed out"
        fi
    elif [ -z "$seal" ]; then
        echo "    ~/.ymusic is signed out: settings seeded"
    else
        echo "    the sign-in is password-protected: it asks for the same password"
    fi
    # One self-contained file to move: the apps switch it back to WAL when they open it.
    sqlite3 "$staged" "PRAGMA journal_mode=DELETE;" >/dev/null
    rm -f "$staged-shm"
    mv "$staged" "$target/app.db"
    echo "    seeded app.db"
}

seed() {
    local target=$1 app=$2 n=$3
    echo "  $app/:"
    if [ -e "$target/app.db" ]; then
        echo "    has data already, not seeded"
        return 0
    fi
    if [ -f "$RELEASE_DIR/app.db" ]; then
        seed_app_db "$target" "$app" "$n"
    else
        echo "    no $RELEASE_DIR/app.db: starts empty"
    fi
    if [ -f "$RELEASE_DIR/window.properties" ] && [ ! -e "$target/window.properties" ]; then
        cp "$RELEASE_DIR/window.properties" "$target/window.properties"
        echo "    seeded window.properties"
    fi
}

cmd_claim() {
    local root slot_file
    root=$(checkout_root)
    slot_file="$root/$SLOT_FILE_NAME"
    if [ -f "$slot_file" ]; then
        cat "$slot_file"
        return 0
    fi
    lock_slots
    local n chosen=""
    for n in $(seq 1 "$MAX_SLOTS"); do
        local owner
        owner=$(slot_owner "$n")
        if [ "$owner" = "$root" ]; then
            chosen=$n
            break
        fi
        if [ -n "$owner" ] && [ -d "$owner" ]; then continue; fi
        if [ -n "$owner" ]; then
            echo "slot $n: owner $owner is gone, wiping it"
            rm -rf "${SLOTS_ROOT:?}/$n"
        fi
        chosen=$n
        break
    done
    [ -n "$chosen" ] || die "all $MAX_SLOTS slots are owned by existing checkouts (tools/dev-slot.sh list)"
    local dir="$SLOTS_ROOT/$chosen"
    mkdir -p "$dir/jvm" "$dir/rust"
    printf '%s\n' "$root" >"$dir/owner"
    echo "claimed slot $chosen for $root"
    seed "$dir/jvm" jvm "$chosen"
    seed "$dir/rust" rust "$chosen"
    printf 'slot=%s\nroot=%s\n' "$chosen" "$dir" >"$slot_file"
    cat "$slot_file"
}

cmd_release() {
    local wipe=0
    case "${1:-}" in
        --wipe) wipe=1 ;;
        "") ;;
        *) die "release takes only --wipe" ;;
    esac
    local root slot_file
    root=$(checkout_root)
    slot_file="$root/$SLOT_FILE_NAME"
    [ -f "$slot_file" ] || die "$root holds no slot"
    local n
    n=$(sed -n 's/^slot=//p' "$slot_file")
    [ -n "$n" ] || die "$slot_file names no slot"
    lock_slots
    local dir="$SLOTS_ROOT/$n"
    if [ "$(slot_owner "$n")" = "$root" ]; then rm -f "$dir/owner"; fi
    rm -f "$slot_file"
    if [ "$wipe" = 1 ]; then
        rm -rf "${SLOTS_ROOT:?}/$n"
        if [ "$(uname -s)" = Darwin ]; then
            # shellcheck disable=SC2046
            if security delete-generic-password -s "$KEY_SERVICE" -a "$(slot_key_account "$n")" $(keychain_args) >/dev/null 2>&1; then
                echo "deleted the Keychain item $KEY_SERVICE/$(slot_key_account "$n")"
            fi
        fi
        echo "released slot $n and wiped $dir"
    else
        echo "released slot $n; its data stays in $dir for the next claim (--wipe deletes it)"
    fi
}

cmd_list() {
    local n
    for n in $(seq 1 "$MAX_SLOTS"); do
        local dir="$SLOTS_ROOT/$n" owner
        [ -d "$dir" ] || continue
        owner=$(slot_owner "$n")
        if [ -z "$owner" ]; then
            echo "$n  free (data kept)"
        elif [ -d "$owner" ]; then
            echo "$n  $owner"
        else
            echo "$n  stale: $owner is gone"
        fi
    done
}

case "${1:-}" in
    claim) cmd_claim ;;
    release)
        shift
        cmd_release "$@"
        ;;
    list) cmd_list ;;
    *) die "usage: tools/dev-slot.sh claim | release [--wipe] | list" ;;
esac
