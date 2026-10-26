#!/usr/bin/env bash
# Transparent encryption of Kinotrek's closed sources in git (the same idea as git-crypt/transcrypt).
#
# Files marked "filter=kinocrypt" in .gitattributes are stored in the repository as AES-256 ciphertext;
# in a working copy that has the key they are plain text, so editing, building and committing work as
# usual. Without the key a clone only has the ciphertext: the code cannot be read or built.
#
# The key lives only in .kinotrek/key (never committed). Keep a copy somewhere safe: without it the
# encrypted files on GitHub can never be read again, even by you.
#
#   tools/kinocrypt.sh init      # once: create a key (if none) and set up git in this clone
#   tools/kinocrypt.sh unlock    # in a fresh clone, after putting the key into .kinotrek/key
#   tools/kinocrypt.sh status    # which files are encrypted in the repository
#
# git itself calls "clean" (plain → cipher, on commit), "smudge" (cipher → plain, on checkout)
# and "textconv" (for readable diffs).
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
KEY="$ROOT/.kinotrek/key"
ITER=100000

# The same text always encrypts to the same bytes (salt from an HMAC of the content), so git does not
# see unchanged files as modified.
encrypt() {
  local salt
  salt="$(openssl dgst -sha256 -mac HMAC -macopt "key:$(cat "$KEY")" "$1" | sed 's/.*= //' | cut -c1-16)"
  # OpenSSL 3 leaves out the "Salted__<salt>" header when the salt is given, so it is written here:
  # the result is the standard format that "openssl enc -d" reads back.
  printf 'Salted__'
  printf "$(printf '%s' "$salt" | sed 's/../\\x&/g')"
  openssl enc -aes-256-cbc -pbkdf2 -iter "$ITER" -md sha256 -S "$salt" -pass "file:$KEY" -in "$1"
}

decrypt() {
  openssl enc -d -aes-256-cbc -pbkdf2 -iter "$ITER" -md sha256 -pass "file:$KEY" -in "$1"
}

is_cipher() { [ "$(head -c 8 "$1")" = "Salted__" ]; }

setup_git() {
  git config filter.kinocrypt.clean 'bash tools/kinocrypt.sh clean'
  git config filter.kinocrypt.smudge 'bash tools/kinocrypt.sh smudge'
  git config filter.kinocrypt.required true
  git config diff.kinocrypt.textconv 'bash tools/kinocrypt.sh textconv'
}

case "${1:-}" in
  clean)
    tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT
    cat > "$tmp"
    if is_cipher "$tmp"; then cat "$tmp"; exit 0; fi
    [ -s "$KEY" ] || { echo "kinocrypt: no key in $KEY — refusing to commit plain text" >&2; exit 1; }
    encrypt "$tmp"
    ;;
  smudge)
    tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT
    cat > "$tmp"
    # Without the key the working copy keeps the ciphertext.
    if [ -s "$KEY" ] && is_cipher "$tmp"; then decrypt "$tmp" || cat "$tmp"; else cat "$tmp"; fi
    ;;
  textconv)
    if [ -s "$KEY" ] && is_cipher "$2"; then decrypt "$2"; else cat "$2"; fi
    ;;
  init)
    mkdir -p "$ROOT/.kinotrek"
    if [ ! -s "$KEY" ]; then
      openssl rand -base64 48 | tr -d '\n' > "$KEY"
      echo "New key: $KEY — copy it somewhere safe now (password manager, USB stick)."
    fi
    setup_git
    echo "kinocrypt is set up in this clone."
    ;;
  unlock)
    [ -s "$KEY" ] || { echo "Put the key into $KEY first." >&2; exit 1; }
    setup_git
    # Check the encrypted files out again, this time through the smudge filter.
    git ls-files | git check-attr --stdin filter | awk -F': ' '$3=="kinocrypt"{print $1}' > "$ROOT/.kinotrek/files"
    while read -r f; do rm -f "$ROOT/$f"; done < "$ROOT/.kinotrek/files"
    while read -r f; do git checkout -- "$f"; done < "$ROOT/.kinotrek/files"
    echo "Unlocked: the sources are plain text in this working copy."
    ;;
  status)
    git ls-files | git check-attr --stdin filter | awk -F': ' '$3=="kinocrypt"{print $1}' | while read -r f; do
      if [ "$(git cat-file -p "HEAD:$f" 2>/dev/null | head -c 8)" = "Salted__" ]; then echo "encrypted  $f"; else echo "PLAIN      $f"; fi
    done
    ;;
  *)
    sed -n '2,16p' "$0"
    exit 1
    ;;
esac
