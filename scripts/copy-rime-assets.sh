#!/bin/sh
# Copies the rime wasm engine and luna_pinyin schema data from a local
# my_rime distribution (https://github.com/LibreService/my_rime) into the
# app assets. AGPL-3.0 sources; see THIRD_PARTY.md.
#
# The Emscripten --preload-file pack (rime.data) stores its file table
# inside the generated rime.js, not in the data file; we extract it into a
# manifest asset that RimeDataPack reads at runtime.
set -e

DIST="${1:-../my-rime-dist}"
ASSETS="$(dirname "$0")/../app/src/main/assets/rime"

if [ ! -f "$DIST/rime.wasm" ]; then
    echo "my_rime dist not found at $DIST (pass the path as the first argument)" >&2
    exit 1
fi

mkdir -p "$ASSETS/luna-pinyin"
cp "$DIST/rime.wasm" "$ASSETS/rime.wasm"
cp "$DIST/rime.data" "$ASSETS/rime.data"
# Everything the browser fetches for the "luna-pinyin" target, except its
# package.json manifest used only by the my_rime web UI.
find "$DIST/ime/luna-pinyin" -maxdepth 1 -type f ! -name package.json -exec cp {} "$ASSETS/luna-pinyin/" \;

python3 - "$DIST/rime.js" "$ASSETS/rime-data-files.txt" <<'PY'
import re, sys

src = open(sys.argv[1]).read()
i = src.find('loadPackage({files:')
if i < 0:
    sys.exit('file table not found in rime.js')
j = src.find('{', i)
depth = 0
in_str = False
esc = False
end = j
for pos in range(j, len(src)):
    c = src[pos]
    if esc:
        esc = False
        continue
    if c == '\\':
        esc = True
        continue
    if c == '"':
        in_str = not in_str
        continue
    if in_str:
        continue
    if c == '{':
        depth += 1
    elif c == '}':
        depth -= 1
        if depth == 0:
            end = pos + 1
            break
files = re.findall(r'\{filename:"([^"]+)",start:(\d+),end:(\d+)\}', src[j:end])
with open(sys.argv[2], 'w') as out:
    for name, start, end_at in files:
        out.write('%s %s %s\n' % (start, end_at, name))
print('manifest: %d files' % len(files))
PY

echo "copied rime assets into $ASSETS"
