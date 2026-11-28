# VYRAL product roadmap

**Last reviewed:** 2026-11-28  
**Product:** VYRAL · **developer:** night · **Android ID:** `app.vyral.night`

This roadmap consolidates the requested work across the conversation. A checked item means it was verified in the current work, not merely found in source. Unchecked work remains open until implemented and tested. Preserve unrelated worktree changes and legal notices.

## Current scope and decisions

- Android mobile and Android TV are first-class targets.
- Desktop targets are Windows and Linux only. macOS desktop is out of scope; Wear OS is to be absent.
- Keep the KMP desktop and Rust desktop implementations distinct. Use Android/KMP as the visual and behavior reference, port verified KMP desktop features into Rust, and retain both until feature parity and install behavior are verified.
- Use the current VYRAL Android Explore experience as the cross-device Explore reference. Keep platform-appropriate layouts; desktop KMP remains the desktop UX reference.
- The actual repository defines architecture. Supplied ZIPs and upstream projects are reference material; do not add parallel players, catalogues, databases, or services.
- No demo songs or fake feature callbacks. Preserve required licenses and notices while removing obsolete user-facing identity references.
- Use the requested Apple Music, Bitchord, NUVX, and Convx references for the specific screen/interaction behaviors requested, reconciled with the later VYRAL Android and KMP standards. Keep VYRAL's own product identity and do not copy whole source files. Use compatible stable dependencies; do not blindly change every version.
- Extension version is fixed at `1.0.0`. Do not reset the apps' existing version/build numbers solely to match an earlier request; review release versioning against the current project configuration.
- iOS and the website/extension should be audited if present in the delivered repository. Do not invent a missing native target or macOS desktop support.

## Status snapshot

- ✅ Cloudflare Listen Together Worker deployed at `https://vyral.syrrnight.workers.dev`; live party creation/join, bearer and VYRAL WebSocket authentication, synchronized playback, ping/pong, and durable state were verified.
- ✅ Go source, Go modules, tests, and its VM deployment files were removed after the Cloudflare live test. The independent party probe remains and defaults to the Worker.
- 🟡 Rust verification: `cargo fmt --all -- --check`, `cargo check --workspace --all-targets`, `cargo clippy --workspace --all-targets`, and `cargo test -p vyral-rust --all-targets` passed. Rust tests: 323 passed, 0 failed, 31 ignored; Clippy reports existing warnings. Windows release built at `C:\Users\abbas\AppData\Local\Temp\vyral-rust-release-check\release\vyral-rust.exe` (40,216,064 bytes). The `vyral-core` Linux cross-check passed; full Linux app check is blocked by missing Linux pkg-config/sysroot and GTK development libraries on this Windows host.
- 🟡 Open-issue follow-up: Rust now classifies YouTube “not a bot” challenges separately from age/account gates and still reaches signed-in recovery or enabled alternate sources; 2 focused tests pass. This does not add Rust PoToken support or verify live playback. Spotify issue #480's current Android/KMP/Rust export uses Web Player credentials and private write APIs; supported OAuth client registration/scopes are not configured.
- 🟡 File hygiene: a recursive project-owned scan found 44 `.md`/`.markdown`/`.txt` files; none was clearly disposable after checking build inputs, licenses, policy docs, and active technical notes. Removed the commented-out Home title and two obsolete icon comments; kept source and legal attributions, including upstream copyright notices.
- ✅ Gradle root/wrapper recovery is in place. Android and TV debug APKs assembled, and the Android mobile APK launched in the emulator; no TV emulator is available for runtime verification.
- 🟡 Local device-to-device: Android mobile and TV use the real host/join panel, pinned HTTPS pairing, host approval, and separate playback-control/send/receive settings. TV Kotlin compile passed, but there is no TV emulator here. Android instrumentation verifies TLS pairing, the permission gate against the real Media3 service, and full-duplex selected-file transfer with byte-for-byte checks (1 focused test passed).
- 🟡 Rust device-to-device: Windows release and Rust workspace checks pass. A pinned-TLS host/client test now resumes a download while sending a selected file back concurrently, proves progress overlap, and checks both file contents; focused test passed, and the full Rust suite is 323 passed, 0 failed, 31 ignored. Test files stay in a temporary directory; production receives remain in configured Downloads/Received.
- 🟡 Mixed runtime and hardware: the earlier Android-emulator ↔ Windows-Rust tunnel test verified a sequential file transfer in both directions with SHA-256 `3fa20276d8a4131431490ed129d0b05fafa4e9c82d4339b5cf635512103d6615`; a remote `next` was accepted into Android queue but no track was loaded, so audible cross-runtime control is unverified. Ordinary Wi-Fi simultaneous mixed-runtime transfer, TV runtime, Linux GUI/runtime, and physical-device networking remain open. Sync settings were restored off, test audio recycled, and the ADB forward removed. Cloudflare remains playback coordination only.
- 🟡 `:app:testDevDebugUnitTest` ran 1,354 tests and reported 25 failures plus one skipped test. The failures were triaged as stale test expectations and localization/path gaps; do not report the suite as passing or repeat the broad suite without addressing those findings.
- 🟡 §2: live JioSaavn and Radio Javan search endpoints both returned HTTP 200 and results for a generic `test` query on 2026-11-28; JioSaavn also returned 20 results when asked for `n=20`. Android combined-source search previously hid provider failures as empty results; JioSaavn now propagates request/parse errors, honors the source's requested result limit (bounded 1–50), and all-enabled-provider failures show the affected source names. Android/TV production Kotlin compilation, focused recognition tests (4/4), and both debug APK assemblies passed. On 2026-11-28, the Android emulator's in-app `Ebi` search showed interleaved results from JioSaavn and Radio Javan with both source toggles enabled. Selecting Radio Javan's `Gheseh Eshgh — Ebi` row reached Media3 `PLAYING` and advanced; the corresponding live Radio Javan stream lookup returned a valid HTTPS media URL (track ID `9341`, duration 250.462s). Selecting JioSaavn's `EBI — Kolja Goldstein, Djaga Djaga` row produced a JioSaavn `getStreamUrl()` log and Media3 `PLAYING` with the result's metadata. This verifies search-to-player handoff on Android mobile; audible output, source-byte attribution for the Radio Javan session, paging, disabled-source search, error UI, TV, and desktop remain unverified. The emulator source toggles were restored off after the check. The roadmap item stays open for the remaining checks.
- 🟡 §7 song-detection: The entry now follows Nuvx's placement and interaction pattern: a recognition action in root-level app chrome by the account/profile control, opening a centered frosted consent flow with capture-source choices and state-specific content. Android mobile, Android TV, KMP desktop, and Rust desktop use platform-adapted root chrome; the duplicate Search-landing cards were removed. Android/TV builds and focused recognition/locale tests passed; the Android emulator visually verified the Home action, consent overlay, and outside/back dismissal without granting capture permission (`%TEMP%\vyral-recognition-modal-verified.png`). KMP desktop compile and its focused Search UI test passed. Rust Windows formatting, all-target check, Clippy, and workspace tests passed after the placement change (323 passed, 31 ignored; Clippy exits 0 with existing warnings); the Windows release build passed earlier. TV and desktop visual/runtime placement, Rust Linux loopback runtime, and capture permission flows remain unverified. No sync code was changed. Live microphone/system-audio matching and local-library scan/index parity remain open.
- 🟡 §7 local-music detection UX: Added an optional “Music on this device” step to Android mobile onboarding and an equivalent D-pad-friendly Android TV setup step, with skip/later access, explicit media permission, and foreground permission-aware MediaStore observation with debounced refresh on mobile/TV. The Android emulator verified the access screen, granted-permission state, root-chrome recognition placement, and the real empty Local Music state; the emulator contained no local audio, so no demo files were added and discovery of a real track or a MediaStore change event was not exercised. Existing KMP and Rust folder scanners/library surfaces were retained; local scan/rescan, metadata matching, permission revocation, large-library behavior, and nearby/system-audio end-to-end matching remain open.
- 🟡 Repository hygiene: a full project-owned source/comment and `.md`/`.txt` inventory found three redundant narration comments, which were removed. The explicitly temporary SF vector exporter with a stale absolute output path was deleted after verifying its generated glyph assets are present. No documentation or text assets were safe to delete: remaining files provide legal attribution/licenses, build or extension inputs, packaging guidance, or active technical documentation. Restored the requested root README, Code of Conduct, Contributing guide, and Security policy. `:sharedUi:jvmTest` passes on JDK 21; the system-default JDK 17 cannot load the SF catalog's Java 21 classes. Ignored build caches and vendor content were not treated as project cleanup targets.
- 🟡 Cold-start baseline: on the running x86_64 Android emulator, `adb shell am start -W -n app.vyral.night/.MainActivity` after force-stop returned `Status: timeout`, `WaitTime: 10864ms`; a screenshot taken just after showed the Home skeleton. This is a launch-timeout observation, not a precise first-frame benchmark. Startup profiling and root-cause work remain open.
- 🟡 The web client uses the VYRAL socket protocol, but its production origin is unknown and the Worker allowlist is empty. Browser connectivity still needs an exact deployed origin configured and tested. After dependency installation, web/extension typecheck passed, all 171 web tests passed, and the combined web/extension build passed; the app bundle reported large-chunk and ineffective-dynamic-import warnings.
- ⬜ The remaining feature requests below have not been verified as complete by this work.

## Roadmap

### 1. Repository, identity, and project structure

- [ ] Re-establish a buildable repository layout with mobile, TV, KMP desktop, Rust desktop, web, shared, backend, native, and build tooling in their agreed locations.
- [ ] Keep platform apps under `app/` (`mobile`, `tv`, `desktop/kmp`, `desktop/rust`, and web/iOS where present) and consolidate shared UI/logic, backend, native code, and common build support under a clearly named core area, without moving files until build references are mapped.
- [ ] Restore or create the required Gradle settings/build entry point without overwriting the existing app modules or unrelated work.
- [ ] Audit every supplied Nyvo bundle and imported component against the actual VYRAL repository; keep only compatible functionality and remove duplicate/unused copies.
- [ ] Triage applicable open issues in `syrrnight/VYRAL`; reproduce each relevant bug on its named platform and check shared behavior on the other supported platforms, even when an issue names only Windows. Issue [#485](https://github.com/syrrnight/VYRAL/issues/485)'s local-first favorites routing is present across Rust desktop, KMP desktop, and Android; focused tests passed (Rust routing + persistence, KMP 2/2, Android 4/4).
- [ ] Issue [#461](https://github.com/syrrnight/VYRAL/issues/461) reports YouTube `LOGIN_REQUIRED`/anti-bot playback failures. Rust's VISIONOS and IOS_PRODUCER requests still have no PoToken path, while Android/KMP do. Rust now distinguishes bot checks from age/account gates, keeps signed-in recovery, and lets configured alternate sources run instead of telling a signed-out user to sign in for a bot challenge; the 2 focused classifier tests and `cargo fmt --all -- --check` pass. Live playback and source fallback remain unverified, so this issue stays open; assess a client-appropriate token path next.
- [ ] Issue [#480](https://github.com/syrrnight/VYRAL/issues/480) only provides a generic “Unable to add to Spotify” screenshot. Current Android/KMP exports use a Web Player token for Web API search and private Pathfinder/spclient write endpoints; Rust uses the same unsupported token/write model, and the extension has no write feature. Replace export writes with user-authorized Spotify OAuth and documented endpoints, then test each actual client path; current code has no Spotify OAuth client ID, scopes, or redirect configuration.
- [ ] Audit branding, package names, manifests, assets, URLs, installers, scripts, comments, and generated output for obsolete product/developer identities. Preserve legally required upstream attribution.
- [ ] Audit Indian/Hindi-specific assets and content; remove unrelated material where safe while retaining JioSaavn.
- [ ] Confirm Android mobile ID `app.vyral.night`, TV install identity, extension identity/version `1.0.0`, and separate KMP/Rust install paths/data directories.
- [ ] Keep Wear OS support out. Confirm whether a native iOS target exists and report it accurately.

### 2. Catalogue sources, search, discovery, and playback

- [ ] Make all three requested music sources real and independently switchable: YouTube Music through the existing InnerTubeX client, JioSaavn, and Radio Javan.
- [ ] Repair source-specific search; specifically verify JioSaavn and Radio Javan search, paging, result mapping, playback handoff, errors, and disabled-source behavior.
- [ ] Update InnerTubeX and NewPipe Extractor to compatible current releases after reviewing release notes and build constraints.
- [ ] Only after the three requested sources work, assess whether Lidarr provides a useful supported fallback/library integration; do not misrepresent it as a stream catalogue or add a separate catalogue backend.
- [ ] Add source priority/health and quality preferences only where they affect real requests and playback.
- [ ] Use InnerTubeX recommendations and mood/genre data for Explore. Keep the Android VYRAL Explore structure on Android TV, KMP, Rust, and web where appropriate.
- [ ] Pursue close screen/feature parity from the current VYRAL Android/KMP standards while retaining native interaction conventions and responsive desktop layouts.
- [ ] Remove the unwanted commute/energy-style cards inside mood/genre destinations; put useful recommendation shelves on Home and genres/categories in Search.
- [ ] Ensure Home includes the requested account/profile area, recommendations, and the established VYRAL Android layout.
- [ ] Implement real radio replenishment, discovery/personal stations, podcasts, queue behavior, and popups using existing clients and player state.
- [ ] Verify playback, background audio, queue, autoplay, lyrics, quality selection, offline playback, and error/retry behavior without placeholder tracks.

### 3. Screen and interaction parity

- [ ] Audit and complete Home, Explore, Search, Library, Radio, Settings, Accounts, Now Playing, lyrics, replay, downloads, and Listen Together on each supported target.
- [ ] Match Library card presentation and Replay cards to the supplied VYRAL/Android references; check the previously flagged Replay card details.
- [ ] Add album, artist, and playlist detail pages; discographies, related artists/releases, credits, listening history, Mixed for You, context menus, and playlist editing.
- [ ] Make navigation/back behavior, loading/error/empty/offline states, keyboard/focus, touch targets, and screen-size adaptation coherent per platform.
- [ ] Keep the Android navigation surface glassy with the requested haze/refraction treatment, but make each visual setting affect a real feature and provide a reduced-effects option.
- [ ] Add the requested miscellaneous setting to hide/show Radio navigation and verify persistence and actual navigation behavior.
- [ ] Make onboarding options genuinely toggleable in app settings after onboarding; keep the requested colorful live background where performance and accessibility settings allow it.
- [ ] Apply the requested Bitchord-inspired Home/account profile, Radio behavior, player motion, and update/pop-up interaction patterns, while using the current VYRAL Android screen as the actual parity reference.
- [ ] Use the requested Figcraft workflow/CLI for interface design where it is installed and usable; capture screenshots during emulator/window visual review so screen and motion comparisons are reviewable.

### 4. Icons, design system, animation, and splash

- [ ] Use the specified `SF_Symbol_Catalog` assets and its compatible Compose vector companion where supported; use matching layered vector assets in Rust and the other clients. Audit every icon, including Settings, menus, dialogs, widgets, and media controls.
- [ ] Preserve vector layers/paths and attribution. Do not use emoji, screenshots, or generic placeholder icons.
- [ ] Centralize icon rendering, names, accessibility labels, and motion defaults so screens do not silently fall back to unrelated icon packs.
- [ ] Implement per-layer spring motion for interactive icons, including play/pause, favorite, shuffle, repeat, download, search, settings, library, lyrics, Listen Together, and volume. A whole-icon scale alone does not satisfy this request.
- [ ] Add the requested playlist/detail shared transition, elastic navigation indicator, symbol replacement, layered bounce, and scroll-triggered per-layer motion.
- [ ] Respect reduced-motion, performance, and haptic settings; test rapid repeated interactions and every settings surface.
- [ ] Match the requested spring timing/overshoot and Convx-style per-part transitions where appropriate; confirm scrolling can trigger the layer-by-layer motion.
- [ ] Audit SFStudio/catalog availability and resolve missing symbols with layered, appropriately licensed vectors.
- [ ] Apply the supplied font family/style consistently after verifying the local font files and platform licensing.
- [ ] Add Persian (`fa`) localization on supported targets, follow the system locale by default, preserve the user override, render RTL correctly, and verify fallback behavior.
- [ ] Use the current VYRAL monochrome transparent mark for adaptive splash/app icons; implement the requested ink/feather reveal, optional haptic feedback, and pencil-on-paper sound without retaining obsolete product branding.

### 5. Audio engine, analysis, and video synchronization

- [ ] Audit and integrate the supplied bit-perfect, native analyzer, audio, overscroll, and video-sync references into existing VYRAL playback infrastructure only where compatible.
- [ ] Verify bit-perfect output, resampling/format changes, device routing, USB DAC handling, equalizer/DSP controls, diagnostics, and unsupported-device fallbacks.
- [ ] Complete native beat/vocal analysis and AutoMix behavior; verify queue transitions and avoid claiming analysis where no model/result exists.
- [ ] Keep music-video and audio playback synchronized and test seeks, source changes, and reconnects.
- [ ] Complete lyrics provider selection, timed lyrics, translation, word animation, and offline/error states.

### 6. Smart playlists and downloadable models

- [ ] Build smart playlists with a small open-source language model that can interpret user intent; evaluate size, license, device compatibility, and privacy before choosing it.
- [ ] Add the requested Vibe flow: describe the desired sound for one or more artists, optionally skip artist restrictions, or explicitly exclude selected artists.
- [ ] Make model download controls actually download, validate, store, update, and remove model files separately from the application package.
- [ ] Prefer official model repositories/releases; if unavailable, create and publish an appropriately licensed VYRAL model artifact only with the required publication authorization.
- [ ] Apply the same model catalog and download behavior on Android mobile, TV, KMP desktop, and Rust desktop; show progress, checksum/version, storage, offline, and failure states.

### 7. Listening, local media, and song recognition

- [x] Place song recognition in root-level app chrome on Android, TV, KMP desktop, and Rust desktop, following Nuvx's placement/interaction pattern and exposing nearby audio/current-device playback only where supported; keep Search landing free of a duplicate action.
- [ ] Verify end-to-end microphone/system-audio matching on each platform and scan/index permitted local audio consistently into the library. Android mobile/TV now offer optional local-music access during setup and automatically refresh the permitted local listing while foregrounded; real-track discovery and change-event verification still remain open.
- [ ] Verify duplicate detection, metadata/artwork matching, rescans, removable storage, permissions revoked, and large-library performance.

### 8. Listen Together and device sync

- [ ] Finish app-side Cloudflare integration: Android/TV/KMP/Rust defaults, health checks, join/create/reconnect, host controls, queue, autoplay replenishment, invitations, and error recovery.
- [ ] Configure the web client only after the actual production origin is known; add that exact origin to Worker CORS and test browser-to-Worker flows.
- [x] Implement Android mobile device-to-device host/join, encrypted pairing, host-controlled playback permission, and file-transfer controls; Android instrumentation verifies pairing against the real Media3 service and permission denial/allow behavior.
- [ ] Verify Android TV host/join, remote-control, and D-pad transfer behavior on a TV emulator or device; the TV build compiles and uses the same sync surface, but runtime is unavailable here.
- [x] Implement Rust Windows host/join, remote playback authorization, and file-transfer controls; pinned-TLS tests and the Windows release build pass.
- [ ] Verify mixed Android↔Rust playback control while a real source track is active, full-duplex transfer between those different implementations over ordinary Wi-Fi, and Rust Linux GUI/runtime behavior. Existing mixed-runtime transfer used an ADB tunnel and was sequential.
- [x] Implement QR/manual LAN pairing across clients: Android mobile scans QR invites; Android and Rust hosts display them; Rust desktop accepts pasted invite JSON; TV joins with D-pad-friendly address/fingerprint entry. Android and Rust payload-validation tests pass. Camera-scan and TV runtime still need device verification; no app-link fallback is configured.
- [x] Implement full-duplex transfer of user-selected local audio while playback-control messages remain independent. Android emulator instrumentation and Rust TLS host/client integration tests each verify simultaneous progress and byte-identical files.
- [x] Keep transfers limited to user-selected local files, require host approval, show progress, support cancellation/resume, and verify content hashes in the Android/Rust paths; the focused tests exercise these flows.
- [ ] Verify storage-full behavior, duplicate handling, interrupted ordinary-Wi-Fi links, Android TV transfers on device, mixed-runtime full-duplex transfer, and Linux desktop runtime.
- [ ] Add per-category toggles for remote playback control, queue sharing, playlists/library, settings, history, and file transfer so users choose what is shared. Enforce each permission on both the UI and protocol side.
- [ ] Reuse existing LAN/Wi-Fi and Bluetooth paths where they are already supported; add encrypted transport, reconnect/conflict handling, and clear fallback behavior. Internet transfer across separate networks needs a verified peer transport/relay; do not claim it works through the current Cloudflare party Worker.
- [ ] Keep Cloudflare Listen Together for playback coordination and invitations only until a separately verified binary-transfer transport is implemented; it is not a file server.
- [ ] Test host/join, host playback controls, both transfer directions concurrently, large/partial files, cancellation/resume, hash mismatch, storage-full, disconnections, app restart, QR expiry, permission toggles, and mixed Android/TV/Rust Windows/Linux peers.

### 9. Spotify account and playlist interoperability

- [ ] Define the requested “all of it” as user-authorized playlist import/export and ongoing playlist synchronization for connected accounts across supported clients.
- [ ] Implement account linking using supported Spotify authorization and scopes; never collect or reuse passwords or bypass account authorization.
- [ ] Support creating/exporting VYRAL playlists to Spotify and importing Spotify playlists into VYRAL, with track matching, duplicates, unavailable songs, ordering, edits, failures, and unlink/revoke handling.
- [ ] Keep account state secure and consistent across platforms; document any Spotify API/app-registration credentials or service requirements.

### 10. Add-ons and useful customization

- [ ] Complete the add-on directory/management surface so users can discover, install, enable/disable, update, and remove add-ons; support authoring/running only within a defined permission and sandbox model.
- [ ] Bring useful customization controls across Android, TV, KMP, Rust, and web: source toggles, hidden navigation destinations, onboarding preferences, motion/reduced motion, model management, and platform-available appearance effects.
- [ ] Every toggle must bind to a real feature and persist; remove misleading or unsupported settings.

### 11. Replay, downloads, system integrations, and account flows

- [ ] Build monthly/annual Replay from the existing listening store: top songs/artists/albums/genres, time, streaks, animated stories, and shareable posters.
- [ ] Complete download management, tagging, embedded lyrics, offline playback, storage controls, and delete/retry flows.
- [ ] Complete Android notifications/lock screen, Android Auto, widgets, Quick Settings, Chromecast, supported deep links, and account/session restoration; keep Wear OS excluded.
- [ ] Add appropriate Windows/Linux desktop notifications, shortcuts, tray/window behavior, packaging, uninstall, and data-directory safety for both KMP and Rust.
- [ ] Audit iOS native lifecycle/system integration if an iOS target is supplied or present; do not claim an iOS build otherwise.

### 12. Rust desktop parity and cross-platform distribution

- [ ] Produce a KMP/Rust feature matrix and use KMP plus Android as the verified UI/UX and behavior baseline.
- [ ] Port every working KMP desktop capability into Rust where the platform supports it, including the requested sources, search, explore, radio, player, icons/motion, Persian/RTL, sync, models, and settings.
- [ ] Keep both implementations independently buildable and branded VYRAL. Prevent their installers, app data, single-instance locks, deep links, and updates from colliding.
- [ ] Support Windows and Linux only for desktop; create clearly distinguishable artifacts for both implementations and supported architectures. Do not create macOS desktop artifacts.
- [ ] Measure startup, memory, CPU, package size, and runtime behavior on comparable Windows/Linux builds; publish only measured results.

### 13. Web, extension, and project documentation

- [ ] Redesign the existing VYRAL website with responsive product, download, install, feature, FAQ, and extension sections; use real screenshots and existing releases only.
- [ ] Show Android, TV, iOS if present, KMP desktop, Rust desktop, and extension downloads distinctly; do not fabricate links or feature claims.
- [ ] Prepare the extension as VYRAL version `1.0.0`, audit permissions/security, and verify the packaged build.
- [ ] Maintain a polished root README, Code of Conduct, GPL-3 license, Contributing guide, Security policy, and consolidated third-party notices as requested.

### 14. Security, performance, and final verification

- [ ] Audit credentials, OAuth/token storage, network/TLS, permissions, WebView/IPC, add-on execution, file handling, model downloads, telemetry, and logging.
- [ ] Measure cold/warm/hot startup and runtime resource use; defer nonessential initialization and report actual methodology/results only.
- [ ] Build/test Android mobile and TV, shared KMP, KMP desktop, Rust desktop, web, and extension where the necessary project roots and native environments exist.
- [ ] Test real source connectivity and playback, local detection, QR/data sync, settings persistence, reduced motion, Persian/RTL, installation collision behavior, offline/error recovery, and accessibility.
- [ ] Install and launch Android mobile/TV emulator builds and the Windows KMP/Rust desktop builds where build tooling and targets are available.
- [ ] Run a final identity, forbidden-reference, Indian/Hindi-content, dependency, file-hygiene, and diff sweep while preserving legal notices and unrelated user changes.
- [ ] Record external blockers (actual web origin, API credentials, signing credentials, unavailable hardware/OS) instead of marking untested work complete.

## Known external/environment blockers

- Gradle wrapper/root recovery has been completed; current environment verification is Windows-only. Linux runtime/package behavior and Android TV runtime behavior remain unverified without Linux and TV targets.
- The web deployment origin is not configured, so the Worker intentionally has no browser-origin allowlist entries yet.
- Spotify export needs a maintainer-registered OAuth client ID and exact redirect URI(s); the connected Spotify account must grant the least required scopes. Use Authorization Code with PKCE for mobile/desktop clients. Spotify's current Development Mode also requires the app owner to have Premium and allowlists at most five users; wider public distribution depends on Spotify approval for Extended Quota Mode. See [PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow), [playlist create/add](https://developer.spotify.com/documentation/web-api/reference/create-playlist), [save library items](https://developer.spotify.com/documentation/web-api/reference/save-library-items), and [quota modes](https://developer.spotify.com/documentation/web-api/concepts/quota-modes). Production signing credentials must also come from the maintainer.
- Hardware-specific audio, microphone, USB DAC, Android TV, and cross-OS Windows/Linux verification require the corresponding devices/runners.
- Go code/deployment files are gone. Automatic approval review blocked deletion of their now-empty local directory shells; those directories contain no files and are not represented in Git.
