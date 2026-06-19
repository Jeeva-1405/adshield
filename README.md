# AdShield

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

An Android app that blocks ads in Chrome, YouTube, YouTube Music, and Spotify — no root required.

---

## One-click setup

Tap **Block All Ads** on the home screen. AdShield walks you through everything automatically:

1. **Chrome** — enables a local DNS VPN that drops requests to ad domains (100k+ blocked domains via Steven Black's hosts list)
2. **YouTube / YouTube Music / Spotify** — downloads and installs pre-patched APKs from your GitHub release

The only things you approve are Android's system dialogs (VPN consent + install confirmation). You never have to think about which card to tap.

Individual app cards remain available for manual control at any time.

## How it works

| App | Method |
|---|---|
| Chrome | Local VpnService intercepts DNS · returns NXDOMAIN for ad domains · forwards everything else to 8.8.8.8 |
| YouTube | Pre-patched APK downloaded from GitHub Releases + installed via PackageInstaller |
| YouTube Music | Same as YouTube |
| Spotify | Same as YouTube |

## Automated patch pipeline

[![Build AdShield Patches](https://github.com/Jeeva-1405/adshield/actions/workflows/build-patches.yml/badge.svg)](https://github.com/Jeeva-1405/adshield/actions/workflows/build-patches.yml)

Patched APKs are built and published automatically by GitHub Actions — no ReVanced Manager, no manual uploads.

**Flow:** GitHub Actions → downloads APKs from APKPure → patches with ReVanced CLI → signs → publishes to GitHub Releases.

**Schedule:** every Sunday midnight UTC, or trigger manually via the Actions tab.

**Stable download URLs (always point to the latest build):**
```
https://github.com/Jeeva-1405/adshield/releases/latest/download/youtube-revanced.apk
https://github.com/Jeeva-1405/adshield/releases/latest/download/youtube-music-revanced.apk
https://github.com/Jeeva-1405/adshield/releases/latest/download/spotify-revanced.apk
```

See [PATCHES.md](PATCHES.md) for setup instructions, how to update app versions, and troubleshooting.

## Requirements

- Android 8.0 (API 26) or higher
- Apps to be patched must already be installed

## Build

```bash
./gradlew assembleDebug      # debug APK
./gradlew assembleRelease    # signed release APK
```

## License

GPL-3.0
