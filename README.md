<div align="center">

<br/>
<br/>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="core/brand/mark/nuvx_mark_white.png">
  <img src="core/brand/mark/nuvx_mark_black.png" alt="nuvx logo" width="140" />
</picture>

# nuvx

### Music in full color

**An open-source music player for Android, iOS, desktop, TV, the car, your wrist and the web.**

<br/>

[![Latest release](https://img.shields.io/github/v/release/lylicht/nuvx?style=for-the-badge&labelColor=0d1117&color=355f8a)](https://github.com/lylicht/nuvx/releases/latest)
[![GPL-3.0 license](https://img.shields.io/badge/license-GPL--3.0-4b5563?style=for-the-badge&labelColor=0d1117)](LICENSE)
[![GitHub downloads](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fnuvx-listen.lylicht.workers.dev%2Fapi%2Fstats%2Fdownloads&query=%24.downloads&style=for-the-badge&labelColor=0d1117&label=GitHub%20downloads&color=6b4f99)](https://github.com/lylicht/nuvx/releases)
[![Online clients](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fnuvx-listen.lylicht.workers.dev%2Fapi%2Fstats%2Flive&query=%24.online&style=for-the-badge&labelColor=0d1117&label=Online%20clients&color=397458)](https://nuvx-listen.lylicht.workers.dev/api/stats/live)
[![Listening Together](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fnuvx-listen.lylicht.workers.dev%2Fapi%2Fstats%2Flive&query=%24.listening&style=for-the-badge&labelColor=0d1117&label=Listening%20Together&color=87563c)](https://nuvx-listen.lylicht.workers.dev/api/stats/live)

<br/>

[**Download**](#download) · [**Features**](#features) · [**Build from source**](#build-from-source) · [**Contribute**](#contributing) · [**Support**](#support) · [**License**](#license)

[Web player](https://web.nuvx.lol) · [Discord](https://discord.gg/h4Wsu824Uf) · [Website](https://www.nuvx.lol)

</div>

> [!IMPORTANT]
> **Independent third-party app.** nuvx is not affiliated with, endorsed by or connected to Google, YouTube or Spotify. Use it in accordance with the terms of the services you access.
---

<div align="center">

<img src="docs/nuvx-banner-now-playing.png" alt="nuvx Now Playing — Music in full color" width="100%" />

</div>

## Features

**Everything you need for your music, across your screens.** nuvx combines a customizable player, synced lyrics, local library tools and connected playback in a single open-source project.

### 🎧 Music, your way

- **Search, discover and play** tracks on YouTube Music, explore new releases and moods, and take control of your queue.
- **Choose your sound:** lossless and Dolby Atmos when supported by the source, separate Wi-Fi and mobile quality settings, equalizer and loudness normalization.
- **Listen offline** with downloads, storage limits and a free-space guard.
- **Keep playback going** with fallback sources and Android bit-perfect USB output for compatible DACs.

### ✨ An immersive listening experience

- **Synced lyrics** with line and word timing from supported providers.
- **Community Canvases** with moving artwork on Now Playing. [Explore the project](core/canvas/README.md).
- **Replay recaps** to see what you listened to over the month, year or all time.
- **Made for each screen:** a corner-snapping desktop mini player, TV remote navigation, a car head-unit app and a Wear OS companion.

### 🔗 Better together

- **Listen Together:** share an invite, queue tracks and keep playback in sync with friends.
- **Local Wi-Fi Sync:** sync playlists and playback between your approved devices over an encrypted local connection.
- **Remote control:** play music on one device and control it from another.
- **Integrations:** Discord Rich Presence, Last.fm scrobbling and desktop playlist transfer from Spotify.

### 🗂️ Your library stays yours

- **Local-only playlists** let you organize music without writing changes to YouTube Music.
- **Playlist history and Undo** make it easier to recover an earlier version.
- **Portable backups** work across Android and desktop, with M3U and CSV import/export.
- **Privacy controls and diagnostics** show what leaves your device while avoiding personal data in copied reports.
- **Restricted network support** with external Tor/Orbot or VLESS/REALITY clients. [Setup guide](docs/CONNECTING_FROM_RESTRICTED_NETWORKS.md).

> **Platform availability:** Not every feature is on every device yet. For details, see the [iOS parity notes](app/ios/docs/ANDROID_PARITY.md).

---

<div align="center">

<h1><a id="download"></a>Download</h1>

Everything is on the [**latest release**](https://github.com/lylicht/nuvx/releases/latest) page.

</div>

| Device | Requirements | What to get |
| --- | --- | --- |
| **Android phone or tablet** | Android 8.0+ | Phone APK · Android Auto playback comes with it |
| **nuvx Auto** | Compatible Android head unit | Auto APK |
| **Android TV or Google TV** | Android 8.0+ | TV APK |
| **Wear OS** | Wear OS 3+ and a paired phone running nuvx | Wear APK |
| **iPhone or iPad** | iOS/iPadOS 18+; signing required | Unsigned universal IPA · [install and update guide](app/ios/README.md) |
| **Windows** | Windows 10+, x64 or ARM64 | Universal setup `.exe` |
| **macOS** | macOS 15+, Apple silicon or Intel | Universal `.dmg` |
| **Linux** | x86-64 or ARM64 | Universal `.tar.gz` |
| **Web** | Chrome, Edge, Brave or Firefox on desktop | [Web player](https://web.nuvx.lol) with the companion extension · [setup](https://web.nuvx.lol/guide) |

Phone, TV and Auto APKs include armeabi-v7a, arm64-v8a and x86_64. Cast and Wear features need Google Play Services. Sideloading an APK needs "Install unknown apps" for the app you download it with.

<details>
<summary><b>Install and update on iPhone or iPad</b></summary>

1. Download `nuvx-<version>-universal.ipa` from a release.
2. Sign and install it with a sideloading tool such as Sideloadly, AltStore Classic or SideStore; an unsigned IPA cannot be opened from Files or Safari. See the [iOS install guide](app/ios/README.md).
3. nuvx checks for a new release on launch (**Settings → Version & Updates → Check for Updates**). Install the new IPA over the existing app with the same tool and Apple Account to keep your data.
4. Free Apple Account profiles expire after 7 days; re-sign and reinstall before then. [Apple's personal-team limits](https://developer.apple.com/help/account/basics/about-your-developer-account/)

</details>

<details>
<summary><b>Download troubleshooting</b></summary>

Batch downloads resolve YouTube stream URLs one at a time to avoid request bursts. If YouTube refuses a track for a moment, wait and retry. In Iran or on another filtered network, see [Connecting from restricted networks](docs/CONNECTING_FROM_RESTRICTED_NETWORKS.md) and [Network resilience](docs/NETWORK_RESILIENCE.md); nuvx works with external proxy/VPN clients but is not a VPN itself.

</details>

---

<div align="center">

<h1><a id="build-from-source"></a>Build from source</h1>

</div>

Android builds use Kotlin and Compose. Install JDK 25 and Android SDK Platform 37 (with the NDK), then set `ANDROID_HOME` or add `sdk.dir` to `local.properties`. The phone and TV apps also build the shared Rust core, so install [Rust](https://rustup.rs), its Android targets and cargo-ndk:

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked
```

```bash
./gradlew :app:assembleDevDebug     # phone and tablet
./gradlew :tv:assembleProdTvDebug   # Android TV and Google TV
./gradlew :tv:assembleProdAutoDebug # standalone nuvx Auto head-unit app
./gradlew :wearos:assembleDevDebug  # watch companion
```

On Windows, use `gradlew.bat`. For signing release builds, see [keystore.properties.example](keystore.properties.example).

| Project | Source and setup |
| --- | --- |
| Android apps | [Phone and Android Auto media support](app/android/mobile) · [TV and nuvx Auto](app/android/tv/README.md) · [Wear OS](app/android/wearos/README.md) |
| Desktop player | [Rust and egui](app/desktop/README.md) |
| Web player and extension | [React, TypeScript and WXT](app/web/README.md) |
| Shared code | [Rust core](core/rust/nuvx-core) · [Kotlin](core/shared) · [Compose UI](core/sharedUi) · [Native audio](core/native) |
| Discord bot | [Bot setup](core/discord-bot/README.md) |
| Listen Together server | [Cloudflare Worker](core/worker/README.md) |
| Project assets | [Canvases](core/canvas/README.md) · [Website](core/website) · [Brand](core/brand/README.md) · [Third-party libraries](core/third_party/README.md) |

---

<div align="center">

<h1><a id="contributing"></a>Contributing</h1>

Found a bug or have an idea? [Open an issue](https://github.com/lylicht/nuvx/issues) or join the [Discord](https://discord.gg/h4Wsu824Uf). Please read the guide and code of conduct before opening a pull request.

[**Contributing Guide**](CONTRIBUTING.md) · [**Code of Conduct**](CODE_OF_CONDUCT.md) · [**Contributors**](CONTRIBUTORS.md) · [**Security**](SECURITY.md)

### Thanks to all contributors ❤

<a href="https://github.com/lylicht/nuvx/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=lylicht/nuvx" alt="nuvx contributors" />
</a>

</div>

---

<div align="center">

<h1><a id="support"></a>Support</h1>

nuvx is free. If it has earned a spot in your rotation, you can support its development here:

<a href="https://ko-fi.com/lylicht" target="_blank" rel="noopener noreferrer"><img src="https://cdn.jsdelivr.net/gh/JMcrafter26/badges@main/src/assets/donate/kofi-singular-alt/cozy.svg" alt="Support nuvx on Ko-fi" height="55"/></a>
<a href="https://www.patreon.com/lylicht" target="_blank" rel="noopener noreferrer"><img src="https://img.shields.io/badge/Patreon-support-f96854?style=for-the-badge&logo=patreon&logoColor=white&labelColor=0d1117" alt="Support nuvx on Patreon" height="40"/></a>

</div>

<details>
<summary><b>How the counters work</b></summary>

- **GitHub downloads** totals installers and app packages across public GitHub releases; updates and repeat downloads count too. Web visits and extension downloads are separate. [Download count](https://nuvx-listen.lylicht.workers.dev/api/stats/downloads)
- **Online clients** counts apps and browsers that recently checked in to the official Listen Together server, deduplicated by a random client ID.
- **Listening Together** counts connected devices in parties with playback running on the official server. Paused parties are excluded; one person can use more than one device.

GitHub and badge caches can delay updates. [Current server counts](https://nuvx-listen.lylicht.workers.dev/api/stats/live)

</details>

---

<div align="center">

<h1><a id="disclaimer"></a>Disclaimer & legal notice</h1>

</div>

nuvx is an independent, community-driven third-party player. It is **not** associated with Google LLC, YouTube, YouTube Music or Spotify.

* **No media hosting:** nuvx does not host, upload or store copyrighted music. It plays local files on your device or streams from services you use, with your own account where one is needed.
* **Your responsibility:** you are responsible for using nuvx in line with your local laws and the terms of the services you connect.
* **Copyleft:** nuvx is free software under the GPL-3.0. Anyone may share or sell copies, but every distribution must come with the corresponding source under the same license.

---

<div align="center">

<h1><a id="license"></a>License & attribution</h1>

</div>

nuvx uses [GPL-3.0](LICENSE) by default. Files with a different SPDX identifier keep that file-level license; their scopes and attributions are listed in the [license map](docs/licenses/LICENSES.md), with full AGPL and LGPL texts linked there. Bundled third-party code keeps its own licenses. The [DST decoder](app/android/mobile/src/main/cpp/dsd/dst), for example, is under LGPL-2.1-or-later. The Android bit-perfect output also contains Mornye-adapted code under LGPL-3.0-only; see the [license map](docs/licenses/LICENSES.md) and [the attribution](core/native/third_party/README.md). Android APKs include the project license texts and native notices under `assets/licenses/`; Android dependency notices remain under `META-INF/`. The web app serves the Kira MIT notice under `/licenses/`. Website search indexing is described in [docs/WEBSITE_SEARCH_INDEXING.md](docs/WEBSITE_SEARCH_INDEXING.md).

### Original project

**nuvx is forked from [BitChord](https://github.com/kushagrasinghx/BitChord).** Thanks to BitChord’s original creators and contributors for the foundation on which nuvx builds.
