#!/usr/bin/env bash
# One-command worktrees for desktop work.
#
#   tools/dev-worktree.sh new <branch> [base] [--path <dir>]
#       git worktree add under .claude/worktrees/<branch> (the branch is made from base, default dev, or
#       reused when it exists), then `setup` inside it
#   tools/dev-worktree.sh setup
#       idempotent, inside any checkout: links the main checkout's gitignored build inputs
#       (secrets.properties, local.properties, signing/), makes ../ffmpeg-build resolve, copies the built
#       engine bundles, writes .ymusic-dev.env (FFMPEG_BUILD_DIR), then tools/dev-slot.sh claim
#   tools/dev-worktree.sh run kotlin|rust
#       the desktop app of this checkout on its dev slot
#   tools/dev-worktree.sh remove <path-or-branch> [--wipe] [--delete-branch]
#       tools/dev-slot.sh release, then git worktree remove; the branch stays unless --delete-branch
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -P)
ENV_FILE_NAME=.ymusic-dev.env
SLOT_FILE_NAME=.ymusic-dev-slot

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
    "$SCRIPT_DIR/dev-slot.sh" claim
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
    [ -f "$root/$SLOT_FILE_NAME" ] || echo "no dev slot here: the app runs on the default data dir (tools/dev-worktree.sh setup claims one)"
    case "${1:-}" in
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
        *) die "usage: tools/dev-worktree.sh run kotlin|rust" ;;
    esac
}

cmd_remove() {
    local target="" wipe="" delete_branch=0
    while [ $# -gt 0 ]; do
        case "$1" in
            --wipe) wipe=--wipe ;;
            --delete-branch) delete_branch=1 ;;
            *) target=$1 ;;
        esac
        shift
    done
    [ -n "$target" ] || die "usage: tools/dev-worktree.sh remove <path-or-branch> [--wipe] [--delete-branch]"
    local path="" branch="" line current="" current_branch=""
    while IFS= read -r line; do
        case "$line" in
            "worktree "*) current=${line#worktree } current_branch="" ;;
            "branch refs/heads/"*) current_branch=${line#branch refs/heads/} ;;
            "")
                if [ "$current" = "$target" ] || [ "$current_branch" = "$target" ] \
                    || { [ -d "$target" ] && [ "$current" = "$(cd "$target" && pwd -P)" ]; }; then
                    path=$current branch=$current_branch
                fi
                ;;
        esac
    done < <(git worktree list --porcelain; echo)
    [ -n "$path" ] || die "no worktree for $target"
    [ "$path" != "$(main_checkout)" ] || die "$path is the main checkout"
    # What setup added: a branch made before .gitignore knew these would count them as changes.
    local link
    for link in secrets.properties local.properties signing; do
        if [ -L "$path/$link" ]; then rm -f "$path/$link"; fi
    done
    rm -f "$path/$ENV_FILE_NAME"
    local changes
    changes=$(cd "$path" && git status --porcelain -- . ":!$SLOT_FILE_NAME")
    [ -z "$changes" ] || die "$path has changes, commit or discard them first:
$changes"
    if [ -f "$path/$SLOT_FILE_NAME" ]; then
        (cd "$path" && "$SCRIPT_DIR/dev-slot.sh" release $wipe)
    fi
    git worktree remove "$path"
    echo "removed $path"
    if [ "$delete_branch" = 1 ] && [ -n "$branch" ]; then git branch -d "$branch"; fi
}

case "${1:-}" in
    new | setup | run | remove)
        command=$1
        shift
        "cmd_$command" "$@"
        ;;
    *) die "usage: tools/dev-worktree.sh new <branch> [base] [--path <dir>] | setup | run kotlin|rust | remove <path-or-branch> [--wipe] [--delete-branch]" ;;
esac
