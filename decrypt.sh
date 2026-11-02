#!/usr/bin/env bash
# Restore the build secrets sealed by encrypt.sh from secrets.enc into this checkout.
#
#   ./decrypt.sh            prompts for the passphrase; refuses to overwrite existing files
#   ./decrypt.sh -f         overwrite them
#
# SECRETS_PASSPHRASE, when set, is used instead of the prompt (CI: a GitHub Actions secret).
#
# Nothing is written until the decrypted payload matches the SHA-256 it carries.
set -euo pipefail
cd "$(dirname "$0")"

IN=secrets.enc
ITER=1000000
force=false
[[ ${1:-} == -f ]] && force=true

[[ -f $IN ]] || { echo "missing $IN" >&2; exit 1; }
if [[ -n ${SECRETS_PASSPHRASE:-} ]]; then
  passphrase=$SECRETS_PASSPHRASE
else
  read -rsp "Passphrase: " passphrase; echo
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# stdin, not fd:N: Git for Windows' native openssl.exe cannot read an inherited descriptor.
printf '%s\n' "$passphrase" |
  openssl enc -d -aes-256-cbc -pbkdf2 -iter "$ITER" -md sha512 -pass stdin \
    -in "$IN" -out "$work/plain" 2>/dev/null ||
  { echo "wrong passphrase or damaged $IN" >&2; exit 1; }

expected=$(head -n 1 "$work/plain")
tail -n +2 "$work/plain" > "$work/payload.tar"
actual=$(openssl dgst -sha256 -r "$work/payload.tar" | cut -d' ' -f1)
[[ $expected == "$actual" ]] || { echo "wrong passphrase or damaged $IN" >&2; exit 1; }

files=()
while IFS= read -r f; do files+=("$f"); done < <(tar -tf "$work/payload.tar")
if ! $force; then
  for f in "${files[@]}"; do
    [[ -f $f ]] && { echo "$f exists — rerun with -f to overwrite" >&2; exit 1; }
  done
fi

umask 077
tar -xf "$work/payload.tar" --no-same-owner
echo "restored ${files[*]}"
