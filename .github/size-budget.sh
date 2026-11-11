#!/usr/bin/env bash
# Fails when the given files together are bigger than the budget, in bytes.
# Usage: bash .github/size-budget.sh <budget bytes> <file>...
#
# Each budget in build.yml is the size measured on 11 November 2026 plus a tenth, so the
# installers can shrink but can't quietly grow. To raise one, change it in build.yml in the same
# commit as the reason.
set -euo pipefail
budget=$1
shift
total=0
for f in "$@"; do
  if [ ! -f "$f" ]; then
    echo "::error::No file $f"
    exit 1
  fi
  size=$(wc -c < "$f" | tr -d ' ')
  echo "$size bytes  $f"
  total=$((total + size))
done
echo "$total bytes in all; budget $budget"
if [ "$total" -gt "$budget" ]; then
  echo "::error::These files are $total bytes, over the budget of $budget bytes"
  exit 1
fi
