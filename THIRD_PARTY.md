# Third-party components

## librime / my_rime / rime-data (AGPL-3.0)

The Pinyin input mode embeds the following AGPL-3.0 licensed components from
[LibreService/my_rime](https://github.com/LibreService/my_rime) (built on
[rime/librime](https://github.com/rime/librime) and the Rime schema/data
ecosystem, including [opencc](https://github.com/BYVoid/OpenCC) dictionaries
and the 朙月拼音·語句流/luna_pinyin_fluency schema):

- `app/src/main/assets/rime/rime.wasm` — librime compiled to WebAssembly
- `app/src/main/assets/rime/rime.data` — Emscripten preload pack (opencc
  dictionaries, prebuilt `default.yaml`, lua scripts)
- `app/src/main/assets/rime/luna-pinyin/*` — prebuilt luna_pinyin schema
  artifacts (prism/table/reverse bins and schema yamls)
- `app/libs/rime-machine.jar` — Java bytecode compiled from the rime.wasm
  above by endive's build-time compiler (same AGPL-3.0 source)

Source code and exact build instructions for these artifacts:
<https://github.com/LibreService/my_rime> (AGPL-3.0). Corresponding source of
this project's modifications is available at
<https://github.com/dongyuwei/Hallelujah-Android>.

## Endive (Apache-2.0)

The WebAssembly runtime executing rime.wasm on the JVM/ART:
[bytecodealliance/endive](https://github.com/bytecodealliance/endive)
(`run.endive:runtime`, `run.endive:wasm`, Apache-2.0).

## fcitx5-android (LGPL-2.1-or-later)

The keyboard's Pixel Dark theme (color palette, borderless 4dp-rounded key
shape, row structure) is ported from
[fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)'s
ThemePreset.PixelDark and Material layout (LGPL-2.1-or-later). Special-key
icons use Material Symbols (Apache-2.0).

## Other components

- Gson (Apache-2.0) — JSON parsing of the rime engine protocol
- AndroidX RecyclerView (Apache-2.0) — candidate strip
- SQLite dictionaries and other data files: see README.md
