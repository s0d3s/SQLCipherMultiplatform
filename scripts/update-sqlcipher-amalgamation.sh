#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UPSTREAM_DIR="$ROOT_DIR/third_party/sqlcipher/upstream"
OUT_DIR="$ROOT_DIR/third_party/sqlcipher"
REF="${1:-}"

if [[ "$REF" == "-h" || "$REF" == "--help" ]]; then
  cat <<USAGE
Usage:
  bash scripts/update-sqlcipher-amalgamation.sh [<sqlcipher-ref>]

Examples:
  bash scripts/update-sqlcipher-amalgamation.sh
  bash scripts/update-sqlcipher-amalgamation.sh v4.13.0
USAGE
  exit 0
fi

if ! command -v git >/dev/null 2>&1; then
  echo "error: git is required" >&2
  exit 1
fi

if [ ! -d "$UPSTREAM_DIR" ] || [ ! -e "$UPSTREAM_DIR/.git" ]; then
  echo "error: SQLCipher upstream submodule is missing." >&2
  echo "run: git submodule update --init --recursive third_party/sqlcipher/upstream" >&2
  exit 1
fi

if ! command -v make >/dev/null 2>&1; then
  echo "error: 'make' is required to regenerate sqlite3.c/sqlite3.h" >&2
  exit 1
fi

if [ -n "$REF" ]; then
  echo "[sqlcipher] checking out ref: $REF"
  git -C "$UPSTREAM_DIR" fetch --tags --prune
  git -C "$UPSTREAM_DIR" checkout "$REF"
fi

echo "[sqlcipher] generating amalgamation from $UPSTREAM_DIR"
make -C "$UPSTREAM_DIR" sqlite3.c

cp "$UPSTREAM_DIR/sqlite3.c" "$OUT_DIR/sqlite3.c"
cp "$UPSTREAM_DIR/sqlite3.h" "$OUT_DIR/sqlite3.h"

COMMIT="$(git -C "$UPSTREAM_DIR" rev-parse HEAD)"
DESCRIBE="$(git -C "$UPSTREAM_DIR" describe --tags --always --dirty 2>/dev/null || echo "$COMMIT")"
GENERATED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

cat > "$OUT_DIR/AMALGAMATION_INFO.txt" <<INFO
SQLCipher upstream commit: $COMMIT
SQLCipher upstream describe: $DESCRIBE
Generated at (UTC): $GENERATED_AT
Generator: scripts/update-sqlcipher-amalgamation.sh
Command: make -C third_party/sqlcipher/upstream sqlite3.c
INFO

echo "[sqlcipher] updated:"
echo "  - $OUT_DIR/sqlite3.c"
echo "  - $OUT_DIR/sqlite3.h"
echo "  - $OUT_DIR/AMALGAMATION_INFO.txt"
