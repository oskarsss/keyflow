# Third-party credits

Keyflow uses two small Kotlin adaptations from [sandeepsj/piano-falling-tiles](https://github.com/sandeepsj/piano-falling-tiles), inspected at commit `3f1fa5e862ac7ca6a583103e3a094e42e493cc94`:

- `engine/TileScheduler.kt` → `TileScheduler.kt`: audio-clock position calculation, adapted to four lanes and the app's header.
- `rendering/ParticleSystem.kt` → `HitParticles.kt`: fixed particle slots, adapted to Android Canvas and screen density.

The game, Android interface, audio charts, and original demo music were created for this project, apart from the adaptations described here. [jatinsachdevame/Piano-Tiles](https://github.com/jatinsachdevame/Piano-Tiles) was reviewed for its lightweight native approach and persistent high scores; no code or audio was copied from it. [atillaturkmen/piano-tiles](https://github.com/atillaturkmen/piano-tiles) was referenced for adjustable speed as a gameplay idea; no code or assets were incorporated from that repository.

Gesture design was informed by the [Piano Tiles 2 sliding-tile gameplay video](https://www.youtube.com/watch?v=7zIRPjfoTiQ). Its diagonal path concept was used as a reference for original Kotlin hold/slide mechanics. No video, artwork, branding, or music from that reference is shipped.

## YTDLnis downloader integration — GPL-3.0

Copyright Denis Çerri and contributors. The Keyflow application is distributed under GPL-3.0; the full text is in `LICENSE` and the APK's `assets/downloader/GPL-3.0.txt`.

Source inspected and adapted: [deniscerri/ytdlnis](https://github.com/deniscerri/ytdlnis/tree/afec4a6eb562c3e7e4b98b2a48f371712f101380), revision `afec4a6eb562c3e7e4b98b2a48f371712f101380` (2026-10-13).

- `core/models/YTDLRequest.kt` and `YTDLOptions.kt` are reused with package changes and attribution headers.
- `core/RuntimeManager.kt`, `core/packages/PackageBase.kt`, and `core/ZipUtils.kt` inform `downloader/NativeRuntime.kt`: native process environments, Python/FFmpeg runtime unpacking with UNIX symlinks, JS runtime selection, and process-tree cancellation. Keyflow changes include app-private paths, bounded UTF-8 streams, timeouts, checksum-verified extractor updates, and removal of YTDLnis UI dependencies.
- Native Python/FFmpeg/QuickJS components are extracted without modification from official [YTDLnis v1.9.0 release APKs](https://github.com/deniscerri/ytdlnis/releases/tag/v1.9.0). Only the runtime libraries are included; no YTDLnis app interface or branding is shipped. YTDLnis remains an upstream credit, not the name of this derivative application.

The pinned official APK digests are:

| CPU | SHA-256 |
| --- | --- |
| arm64-v8a | `aa7b78d52575c886e4639ab319c7cd6cbada7543a31c4e523b4237cebb0b43e7` |
| x86_64 | `ce58f841f57d76d2c16e60299dd6350aeb1ac8e2fc44eb7b4531024f2f3739e8` |

`tools/prepare_android_runtimes.py` downloads and verifies these APKs before extracting `libpython.so`, `libpython.zip.so`, `libffmpeg.so`, `libffmpeg.zip.so`, `libffprobe.so`, `libqjs.so`, and `libtermux.so` for each CPU.

## Runtime components and corresponding sources

- **Python runtime/package wrappers:** [deniscerri/ytdlnis-packages](https://github.com/deniscerri/ytdlnis-packages), GPL-3.0 package integration. [Python build script](https://github.com/deniscerri/ytdlnis-packages/blob/main/scripts/build_python.sh), based on [Termux packages](https://github.com/termux/termux-packages). CPython and bundled Python package license files remain inside the unmodified `libpython.zip.so` archive, including `usr/lib/python3.14/LICENSE.txt` and package `dist-info/licenses/` notices.
- **FFmpeg Android wrapper/bundle:** [junkfood02/youtubedl-android 0.17.2](https://github.com/junkfood02/youtubedl-android/tree/0.17.2), based on [yausername/youtubedl-android](https://github.com/yausername/youtubedl-android), GPL-3.0 Android integration. [Upstream FFmpeg build instructions](https://github.com/junkfood02/youtubedl-android/blob/0.17.2/BUILD_FFMPEG.md), [FFmpeg source](https://ffmpeg.org/download.html), and [Termux build recipes](https://github.com/termux/termux-packages/tree/master/packages/ffmpeg). The original native bundle is preserved; no native components have been changed.
- **QuickJS:** Copyright Fabrice Bellard and Charlie Gordon, MIT. [QuickJS source](https://bellard.org/quickjs/) and its full MIT license in `assets/downloader/licenses/QuickJS.txt`.
- **yt-dlp:** [yt-dlp official nightly 2026.10.10.233141](https://github.com/yt-dlp/yt-dlp-nightly-builds/releases/tag/2026.10.10.233141), Unlicense with bundled dependencies under their own licenses. Bundled executable SHA-256: `f40b6a9e4c5786360953911cab5d2f87507626b8fb7bfee3f8f1c2e5cf341fae`. The executable retains its embedded license/dependency notices; the upstream license is also included in the APK.
- **Apache Commons Compress 1.27.1** and transitive Commons IO/Lang/Codec: Apache-2.0. [Commons Compress source](https://github.com/apache/commons-compress/tree/rel/commons-compress-1.27.1); license and upstream notices are included in `assets/downloader/licenses/`.

The private release provides Keyflow's complete source and build scripts, while these upstream links identify the corresponding unchanged component sources and build recipes. Downloaded playlist music/artwork is user cache content and is not part of source or release assets.

## MIT license — piano-falling-tiles

Copyright (c) 2025 sandeepsj

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
