# AdShield — Project Context for Claude Code

## What this project is
A single Android app that blocks ads in four target apps: **Chrome, YouTube, YouTube Music, Spotify**. Distributed via GitHub Releases as a signed APK. No Play Store, no login, no telemetry, no backend.

## Author
Jeeva — final-year ECE student, building this as a placement portfolio project.

## Architecture
Two engines behind one Jetpack Compose UI:

1. **DNS Engine** — Local `VpnService` that intercepts DNS queries and drops requests to ad domains. Used for Chrome and any third-party app using AdMob/Unity/AppLovin.
2. **Patch Engine** — Wraps the open-source `revanced-patcher` library to download, patch, sign, and install modified versions of YouTube, YT Music, and Spotify.

```
AdShield
├── DNS Engine  (VpnService)        → Chrome
└── Patch Engine (revanced-patcher) → YouTube, YT Music, Spotify
```

## Tech stack (non-negotiable)
- **Language:** Kotlin only
- **UI:** Jetpack Compose + Material 3
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 34
- **Architecture:** MVVM with ViewModel + StateFlow
- **DI:** Manual constructor injection (no Hilt — keep it light)
- **Async:** Coroutines + Flow
- **Persistence:** Room for stats + DataStore for settings
- **Networking:** OkHttp
- **Build:** Gradle Kotlin DSL (`build.gradle.kts`)

## Package structure
```
com.jeeva.adshield/
├── ui/
│   ├── home/           ← main dashboard (HomeScreen, HomeViewModel)
│   ├── theme/          ← Material 3 theme (Color, Type, Theme)
│   └── components/     ← reusable Composables
├── core/
│   ├── detector/       ← installed-app detection
│   ├── dns/            ← VpnService + packet parsing
│   └── patcher/        ← revanced-patcher integration
├── data/
│   ├── db/             ← Room entities, DAOs
│   ├── prefs/          ← DataStore
│   └── blocklist/      ← blocklist fetch + parse
├── service/            ← foreground services
└── util/               ← extensions, helpers
```

## Target apps (hardcoded constants — see TargetApps.kt)
| App | Package | Engine |
|---|---|---|
| Chrome | `com.android.chrome` | DNS |
| YouTube | `com.google.android.youtube` | Patch |
| YouTube Music | `com.google.android.apps.youtube.music` | Patch |
| Spotify | `com.spotify.music` | Patch |

## Phase plan — DO NOT skip ahead
| Phase | Status | Deliverable |
|---|---|---|
| 0 | ✅ Done | Gradle project, Compose scaffold, base theme |
| 1 | ✅ Done | Target-app detection + dashboard UI with 4 cards |
| 2 | Pending | DNS engine (VpnService) for Chrome |
| 3 | Pending | Patch engine — wraps revanced-patcher for YT/YTM/Spotify |
| 4 | Pending | Stats, in-app auto-updater, polish, README |

## Coding conventions
- Composables: `PascalCase`, suffixed by purpose (`HomeScreen`, `AppCard`)
- ViewModels: `*ViewModel`, expose `StateFlow<UiState>`
- Sealed classes for UI state (`Loading`, `Ready(...)`, `Error(...)`)
- No `!!` unwraps — use `requireNotNull` or proper null handling
- All long-running work in `viewModelScope` or `IO` dispatcher
- Comments only where logic is non-obvious — code should self-document
- Every public function gets a one-line KDoc comment

## Distribution
- License: **GPL-3.0** (required — we link against revanced-patcher which is GPL-3)
- Signed with a local keystore (NOT committed to repo — in `.gitignore`)
- Built via `./gradlew assembleRelease`
- Published to GitHub Releases manually
- In-app updater reads `https://api.github.com/repos/jeeva-1405/adshield/releases/latest`

## What is OUT of scope
- Login / accounts / cloud sync / telemetry
- Play Store submission (policy violation)
- iOS
- Per-app firewalling beyond the 4 targets
- Writing our own ReVanced patches (we use community ones)

## Reference implementations (do NOT copy verbatim)
- DNS66: github.com/julian-klode/dns66 (DNS engine reference)
- Blokada v5: github.com/blokadaorg/blokada (architecture reference)
- ReVanced Manager: github.com/ReVanced/revanced-manager (patch engine reference)
