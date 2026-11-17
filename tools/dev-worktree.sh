#!/usr/bin/env bash
# One-command worktrees for desktop work.
#
#   tools/dev-worktree.sh new <branch> [base] [--path <dir>]
#       git worktree add under .claude/worktrees/<branch> (the branch is made from base, default dev, or
#       reused when it exists), then `setup` inside it
#   tools/dev-worktree.sh setup
#       idempotent, inside any checkout: links the main checkout's gitignored build inputs
#       (secrets.properties, local.properties, signing/), makes ../ffmpeg-build resolve, copies the built
#       engine bundles, writes .ymusic-dev.env (FFMPEG_BUILD_DIR), then tools/dev-data.sh create (the
#       checkout's own copy of ~/.ymusic)
#   tools/dev-worktree.sh run kotlin|rust
#       the desktop app of this checkout on its data dir; refuses without one (the Kotlin app's default
#       is the real ~/.ymusic) unless YMUSIC_DATA_DIR names a dir
#   tools/dev-worktree.sh remove <path-or-branch> [--keep-data] [--delete-branch]
#       git worktree remove, then deletes the worktree's data dir (unless --keep-data); a worktree with
#       changes, or one git refuses to remove, keeps everything. The branch stays unless --delete-branch
#   tools/dev-worktree.sh list | prune [-y]
#       the data dirs and their checkouts; prune deletes those whose checkout is gone, after confirmation
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -P)
ENV_FILE_NAME=.ymusic-dev.env
DATA_FILE_NAME=.ymusic-dev-data

die() {
    echo "dev-worktree: $*" >&2
    exit 1
}

main_checkout() {
    git worktree list --porcelain | sed -n '1s/^worktree //p'
}

checkout_root() {
    local top
    top=$(git rev-parse --show-toplevel) || die "not inside a git checkout"
    (cd "$top" && pwd -P)
}

link_if_missing() {
    local source=$1 link=$2
    if [ -e "$link" ] || [ -L "$link" ]; then return 0; fi
    if [ ! -e "$source" ]; then
        echo "  no $source to link"
        return 0
    fi
    ln -s "$source" "$link"
    echo "  linked $link -> $source"
}

cmd_setup() {
    local root main
    root=$(checkout_root)
    main=$(cd "$(main_checkout)" && pwd -P)
    echo "setting up $root"
    if [ "$root" != "$main" ]; then
        link_if_missing "$main/secrets.properties" "$root/secrets.properties"
        link_if_missing "$main/local.properties" "$root/local.properties"
        link_if_missing "$main/signing" "$root/signing"
        link_if_missing "$(dirname "$main")/ffmpeg-build" "$(dirname "$root")/ffmpeg-build"
        local dist=engine-js/build/dist bundle
        for bundle in engine.js player.js engine.version; do
            if [ ! -e "$root/$dist/$bundle" ] && [ -f "$main/$dist/$bundle" ]; then
                mkdir -p "$root/$dist"
                cp "$main/$dist/$bundle" "$root/$dist/$bundle"
                echo "  copied $dist/$bundle"
            fi
        done
    fi
    local ffmpeg_build="$(dirname "$root")/ffmpeg-build"
    if [ -d "$ffmpeg_build" ]; then
        printf 'export FFMPEG_BUILD_DIR=%q\n' "$(cd "$ffmpeg_build" && pwd -P)" >"$root/$ENV_FILE_NAME"
        echo "  wrote $ENV_FILE_NAME: $(cat "$root/$ENV_FILE_NAME")"
    else
        echo "  no ffmpeg-build at $ffmpeg_build: build it (ffmpeg-build/scripts/build-desktop.sh) or set FFMPEG_BUILD_DIR"
    fi
    "$SCRIPT_DIR/dev-data.sh" create
}

cmd_new() {
    local branch="" base=dev path=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --path)
                [ $# -ge 2 ] || die "--path needs a directory"
                path=$2
                shift 2
                ;;
            *)
                if [ -z "$branch" ]; then branch=$1; else base=$1; fi
                shift
                ;;
        esac
    done
    [ -n "$branch" ] || die "usage: tools/dev-worktree.sh new <branch> [base] [--path <dir>]"
    local main
    main=$(main_checkout)
    if [ -z "$path" ]; then
        path="$main/.claude/worktrees/$(printf '%s' "$branch" | tr -c 'A-Za-z0-9._-' '-')"
    fi
    if git show-ref --verify --quiet "refs/heads/$branch"; then
        git worktree add "$path" "$branch"
    else
        git worktree add -b "$branch" "$path" "$base"
    fi
    (cd "$path" && "$SCRIPT_DIR/dev-worktree.sh" setup)
}

cmd_run() {
    local root
    root=$(checkout_root)
    case "${1:-}" in
        kotlin | rust) ;;
        *) die "usage: tools/dev-worktree.sh run kotlin|rust" ;;
    esac
    if [ ! -f "$root/$DATA_FILE_NAME" ] && [ -z "${YMUSIC_DATA_DIR:-}" ]; then
        die "$root has no workspace data dir, so the app would run on its default one (the Kotlin app's is your real ~/.ymusic): run tools/dev-worktree.sh setup first, or set YMUSIC_DATA_DIR"
    fi
    case "$1" in
        kotlin)
            cd "$root"
            exec ./gradlew :desktopApp:run
            ;;
        rust)
            if [ -f "$root/$ENV_FILE_NAME" ]; then
                # shellcheck disable=SC1090
                . "$root/$ENV_FILE_NAME"
            fi
            cd "$root/desktopRust"
            # rustup puts cargo in ~/.cargo/bin, which a non-login shell's PATH lacks.
            command -v cargo >/dev/null 2>&1 || PATH="${CARGO_HOME:-$HOME/.cargo}/bin:$PATH"
            command -v cargo >/dev/null 2>&1 || die "cargo not found (install Rust via rustup)"
            # findshlibs overflows its arithmetic on macOS, which panics a debug build.
            exec cargo run -p ymusic --config 'profile.dev.package.findshlibs.overflow-checks=false'
            ;;
    esac
}

# Setup's own files in a checkout: links into the main checkout, the env file and the data file.
SETUP_LINKS="secrets.properties local.properties signing"

cmd_remove() {
    local target="" keep_data="" delete_branch=0
    while [ $# -gt 0 ]; do
        case "$1" in
            --keep-data) keep_data=--keep-data ;;
            --delete-branch) delete_branch=1 ;;
            *) target=$1 ;;
        esac
        shift
    done
    [ -n "$target" ] || die "usage: tools/dev-worktree.sh remove <path-or-branch> [--keep-data] [--delete-branch]"
    local path="" branch="" locked="" line current="" current_branch="" current_locked=""
    while IFS= read -r line; do
        case "$line" in
            "worktree "*) current=${line#worktree } current_branch="" current_locked="" ;;
            "branch refs/heads/"*) current_branch=${line#branch refs/heads/} ;;
            locked*) current_locked=1 ;;
            "")
                if [ "$current" = "$target" ] || [ "$current_branch" = "$target" ] \
                    || { [ -d "$target" ] && [ "$current" = "$(cd "$target" && pwd -P)" ]; }; then
                    path=$current branch=$current_branch locked=$current_locked
                fi
                ;;
        esac
    done < <(git worktree list --porcelain; echo)
    [ -n "$path" ] || die "no worktree for $target"
    [ "$path" != "$(main_checkout)" ] || die "$path is the main checkout"
    [ -z "$locked" ] || die "$path is locked (git worktree unlock it first): nothing removed"
    local excludes=(":!$DATA_FILE_NAME" ":!$ENV_FILE_NAME") link
    for link in $SETUP_LINKS; do excludes+=(":!$link"); done
    local changes
    changes=$(cd "$path" && git status --porcelain -- . "${excludes[@]}")
    [ -z "$changes" ] || die "$path has changes, commit or discard them first (nothing removed):
$changes"
    local data_dir=""
    if [ -f "$path/$DATA_FILE_NAME" ]; then data_dir=$(sed -n 's/^root=//p' "$path/$DATA_FILE_NAME"); fi
    # Set aside, not deleted: a branch whose .gitignore predates them sees them as untracked, which git
    # worktree remove refuses; if it refuses anyway they go back.
    local aside name set_aside=()
    aside=$(mktemp -d "${TMPDIR:-/tmp}/dev-worktree-remove.XXXXXX")
    for name in $SETUP_LINKS; do
        if [ -L "$path/$name" ]; then
            mv "$path/$name" "$aside/$name"
            set_aside+=("$name")
        fi
    done
    for name in "$ENV_FILE_NAME" "$DATA_FILE_NAME"; do
        if [ -f "$path/$name" ] && [ ! -L "$path/$name" ]; then
            mv "$path/$name" "$aside/$name"
            set_aside+=("$name")
        fi
    done
    if ! git worktree remove "$path"; then
        for name in ${set_aside[@]+"${set_aside[@]}"}; do
            mv "$aside/$name" "$path/$name" || die "git worktree remove refused $path; its setup files stay in $aside"
        done
        rmdir "$aside"
        die "git worktree remove refused $path: it and its data dir are left as they were"
    fi
    rm -rf "$aside"
    echo "removed $path"
    if [ -n "$data_dir" ]; then "$SCRIPT_DIR/dev-data.sh" release "$path" "$data_dir" $keep_data; fi
    if [ "$delete_branch" = 1 ] && [ -n "$branch" ]; then git branch -d "$branch"; fi
}

case "${1:-}" in
    new | setup | run | remove)
        command=$1
        shift
        "cmd_$command" "$@"
        ;;
    list | prune) "$SCRIPT_DIR/dev-data.sh" "$@" ;;
    *) die "usage: tools/dev-worktree.sh new <branch> [base] [--path <dir>] | setup | run kotlin|rust | remove <path-or-branch> [--keep-data] [--delete-branch] | list | prune [-y]" ;;
esac
