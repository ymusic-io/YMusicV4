#!/usr/bin/env bash
# Authenticode-signs files in place with the Certum SimplySign cloud certificate (ssign), asking the
# maintainer's phone for every login: a Telegram bot asks for the current SimplySign code and the
# reply logs in. Nothing that can log in unattended is stored anywhere. ssign caches the session
# for 20 minutes under XDG_RUNTIME_DIR, and a later call reuses it while enough of it is left, so
# one code signs a whole release (Inno Setup's SignTool calls included).
#
# usage: certum-sign.sh FILES...
# env:   SSIGN               the ssign executable
#        CERTUM_EMAIL        the SimplySign account
#        TELEGRAM_BOT_TOKEN  the bot that asks; TELEGRAM_CHAT_ID  the one chat whose reply counts
#        XDG_RUNTIME_DIR     where ssign keeps its session
#        SIGN_LABEL          what the request names (default: "a build")
#        CODE_WAIT_MINUTES   how long to wait for a reply (default 30)
set -euo pipefail

[[ $# -gt 0 ]] || { echo "usage: $0 FILES..." >&2; exit 2; }
: "${SSIGN:?}" "${CERTUM_EMAIL:?}" "${TELEGRAM_BOT_TOKEN:?}" "${TELEGRAM_CHAT_ID:?}" "${XDG_RUNTIME_DIR:?}"

max_codes=3
session=$XDG_RUNTIME_DIR/ssign/session.json
api=https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN
run_url=${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-}/actions/runs/${GITHUB_RUN_ID:-}

ssign() { "$SSIGN" --name YMusic --url https://ymusic.io "$@"; }

# ssign drops its cache 2 minutes before expires_at; the margin covers one Inno Setup compile.
session_fresh() {
    local expires_at
    [[ -f $session ]] && expires_at=$(jq -r '.expires_at // 0' "$session") && ((expires_at - $(date +%s) >= 10 * 60))
}

telegram() {
    local method=$1
    shift
    curl -fsS --retry 3 --max-time 70 "$api/$method" "$@"
}

say() { telegram sendMessage --data-urlencode "chat_id=$TELEGRAM_CHAT_ID" --data-urlencode "text=$1" >/dev/null; }

# Sends the prompt, then prints the first six-digit reply from TELEGRAM_CHAT_ID; earlier messages never count.
read_code() {
    local deadline offset response code
    deadline=$(($(date +%s) + ${CODE_WAIT_MINUTES:-30} * 60))
    offset=$(($(telegram getUpdates --data offset=-1 | jq '[.result[].update_id] | max // -1') + 1))
    say "$1"
    while (($(date +%s) < deadline)); do
        if ! response=$(telegram getUpdates --data "offset=$offset" --data timeout=50 \
            --data-urlencode 'allowed_updates=["message"]'); then
            echo "::warning::Telegram getUpdates failed; asking again" >&2
            sleep 5
            continue
        fi
        offset=$(jq --argjson offset "$offset" '[.result[].update_id + 1, $offset] | max' <<<"$response")
        code=$(jq -r --arg chat "$TELEGRAM_CHAT_ID" '[.result[].message // empty
            | select((.chat.id | tostring) == $chat) | (.text // "") | gsub("\\s"; "")
            | select(test("^[0-9]{6}$"))] | last // empty' <<<"$response")
        [[ -n $code ]] && { echo "$code"; return 0; }
    done
    return 1
}

prompt="YMusic ${SIGN_LABEL:-a build}: reply with your SimplySign code to sign the Windows build.
$run_url"
for ((attempt = 1; attempt <= max_codes; attempt++)); do
    session_fresh && break
    if ! code=$(read_code "$prompt"); then
        say "Signing gave up: no code within ${CODE_WAIT_MINUTES:-30} minutes. $run_url"
        echo "certum-sign: no SimplySign code within ${CODE_WAIT_MINUTES:-30} minutes" >&2
        exit 1
    fi
    if output=$(CERTUM_TOKEN=$code ssign "$@" 2>&1); then
        echo "$output"
        [[ -f $session ]] || { echo "certum-sign: ssign logged in but cached no session at $session" >&2; exit 1; }
        say "Code accepted, signing."
        exit 0
    fi
    echo "$output" >&2
    if ! grep -qE '^[[:space:]]*[0-9]+: login[[:space:]]*$' <<<"$output"; then
        say "Signing failed after login, see the run: $run_url"
        exit 1
    fi
    prompt="Certum rejected that code. Send the next one (attempt $((attempt + 1)) of $max_codes)."
done
session_fresh || {
    say "Signing gave up: $max_codes codes were rejected. $run_url"
    echo "certum-sign: $max_codes SimplySign codes rejected" >&2
    exit 1
}
# The cached session signs; ssign still requires a code argument, which it does not use then.
CERTUM_TOKEN=cached ssign "$@"
