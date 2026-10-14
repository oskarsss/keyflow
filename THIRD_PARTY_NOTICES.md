# Third-party credits

Keyflow uses two small Kotlin adaptations from [sandeepsj/piano-falling-tiles](https://github.com/sandeepsj/piano-falling-tiles), inspected at commit `3f1fa5e862ac7ca6a583103e3a094e42e493cc94`:

- `engine/TileScheduler.kt` → `TileScheduler.kt`: audio-clock position calculation, adapted to four lanes and the app's header.
- `rendering/ParticleSystem.kt` → `HitParticles.kt`: fixed particle slots, adapted to Android Canvas and screen density.

The remaining game, Android interface, playlist service, charts, and original demo music were created for this project. [jatinsachdevame/Piano-Tiles](https://github.com/jatinsachdevame/Piano-Tiles) was reviewed for its lightweight native approach and persistent high scores; no code or audio was copied from it. [atillaturkmen/piano-tiles](https://github.com/atillaturkmen/piano-tiles) was referenced for adjustable speed as a gameplay idea; no GPL code or assets were incorporated.

Gesture design was informed by the [Piano Tiles 2 sliding-tile gameplay video](https://www.youtube.com/watch?v=7zIRPjfoTiQ). Its diagonal path concept was used as a reference for original Kotlin hold/slide mechanics. No video, artwork, branding, or music from that reference is shipped.

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
