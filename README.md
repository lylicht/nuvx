# VYRAL

VYRAL is an open-source music application for Android phones and TV, Windows and Linux desktops, and the web. Desktop has separate Kotlin Multiplatform and Rust implementations. The project is actively developed; feature availability and verification vary by platform. See [ROADMAP.md](ROADMAP.md) for current status.

Maintainer and VYRAL project credit: [@syrrnight](https://github.com/syrrnight) (`night`). Android application ID: `app.vyral.night`.

## Requirements

- JDK 21 or newer for the KMP desktop and shared UI tests.
- Android SDK for Android phone and TV builds.
- Rust stable for the Rust desktop.
- Node.js 22 or newer and pnpm `12.11.2` for the web app and extension.

## Project layout

- `app/mobile` — Android phone application
- `app/tv` — Android TV application
- `app/desktop/kmp` — Kotlin Multiplatform desktop application
- `app/desktop/rust` — Rust desktop application
- `app/web` — web client and browser extension (extension version `1.0.0`)
- `core` — shared code, native components, and the Cloudflare Worker

## Build and test

Run Android and TV tasks from the repository root with the Android SDK configured:

```powershell
.\gradlew.bat :app:assembleProdDebug :tv:assembleProdDebug
```

Run the KMP desktop tests from the repository root:

```powershell
.\gradlew.bat :desktopKmpApp:test :desktopKmpUi:jvmTest
```

Build and test the Rust desktop application from `app/desktop/rust`:

```powershell
cargo fmt --all -- --check
cargo test --workspace
cargo build --workspace --release
```

Build and check the web client and extension from `app/web` with Node.js and pnpm installed:

```powershell
pnpm install --frozen-lockfile
pnpm typecheck
pnpm test
pnpm build
```

Platform-specific packaging notes are in the relevant desktop directories. Release packages are not linked here; use only artifacts published in this repository's Releases.

## Licensing

VYRAL is distributed under GNU GPL version 3; see [LICENSE](LICENSE). Third-party code, assets, and upstream attribution are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and alongside their components.

VYRAL is forked from [BitChord](https://github.com/kushagrasinghx/BitChord); see the notices above for attribution and licensing details.

