#!/bin/sh
# Makes site/fonts/MonaSans-*-ext.woff2: Latin Extended-A and the German and French quotation marks.
# The base files (Latin-1 and common punctuation) stay as they are; styles.css loads these only when
# a page uses a character from them (unicode-range), so the English page never downloads them.
# Needs Python with fonttools and brotli. Source fonts: the app's own Mona Sans (SIL OFL 1.1).
# Same options as the base files (their sizes match exactly with --layout-features=*).
set -e
cd "$(dirname "$0")/.."
PY="${PYTHON:-python}"
for w in Regular Medium SemiBold; do
  lower=$(printf %s "$w" | tr 'A-Z' 'a-z')
  "$PY" -m fontTools.subset "app/shared/src/commonMain/composeResources/font/monasans_$lower.ttf" \
    --unicodes="U+0100-017F,U+201A,U+201E,U+2039,U+203A" --layout-features='*' --flavor=woff2 \
    --output-file="site/fonts/MonaSans-$w-ext.woff2"
done
