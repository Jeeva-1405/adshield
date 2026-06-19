# AdShield Patch Pipeline

The workflow at `.github/workflows/build-patches.yml` builds and publishes patched APKs automatically. You never run ReVanced Manager manually.

## How it works

```
GitHub Actions (weekly / on-demand)
  └─ Download ReVanced CLI + patches (latest release)
  └─ Download YouTube / YouTube Music / Spotify APKs from APKPure
  └─ Patch each APK with revanced-cli
  └─ Sign with your keystore (stored as a GitHub secret)
  └─ Build AdShield app (./gradlew assembleRelease)
  └─ Publish everything to GitHub Releases as "vYYYY.MM.DD"
```

The stable download URLs your users hit never change:

```
https://github.com/Jeeva-1405/adshield/releases/latest/download/youtube-revanced.apk
https://github.com/Jeeva-1405/adshield/releases/latest/download/youtube-music-revanced.apk
https://github.com/Jeeva-1405/adshield/releases/latest/download/spotify-revanced.apk
```

## One-time setup (do this once, then forget)

### 1. Generate a signing keystore

Run this **locally** (PowerShell or any terminal with `keytool`):

```powershell
keytool -genkey -v `
  -keystore adshield.keystore `
  -alias adshield `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -storepass adshield123 -keypass adshield123 `
  -dname "CN=AdShield, O=Jeeva"
```

Keep `adshield.keystore` safe — losing it means users must uninstall before updating.

### 2. Convert keystore to base64

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("adshield.keystore")) | Set-Clipboard
```

### 3. Add GitHub secrets

Go to **github.com/Jeeva-1405/adshield → Settings → Secrets and variables → Actions → New repository secret** and add:

| Secret name        | Value                                     |
|--------------------|-------------------------------------------|
| `KEYSTORE_BASE64`  | The base64 string from step 2             |
| `KEYSTORE_PASSWORD`| `adshield123` (or whatever you chose)     |
| `KEY_ALIAS`        | `adshield`                                |
| `KEY_PASSWORD`     | `adshield123` (or whatever you chose)     |

### 4. Trigger the first build

**Actions tab → Build AdShield Patches → Run workflow → Run workflow**

Wait ~10 minutes. A new release appears at github.com/Jeeva-1405/adshield/releases.

## Trigger a manual rebuild

Actions tab → **Build AdShield Patches** → **Run workflow**.

Use this when you want to rebuild immediately (e.g. after a ReVanced patches update).

## Update target app versions

When ReVanced drops support for the current hardcoded version, the patch step logs
`No compatible patches found` and the job summary shows ❌.

Fix:

1. Find the new supported version:
   ```bash
   java -jar revanced-cli.jar list-patches \
     --with-packages revanced-patches.rvp \
     | grep -A3 "com.google.android.youtube"
   ```
2. Update the version constant at the top of `.github/workflows/build-patches.yml`:
   ```yaml
   env:
     YOUTUBE_VERSION: "XX.XX.XX"   # ← new version
   ```
3. Commit and push. The next scheduled run (or manual trigger) uses the new version.

## Add a new app to the pipeline

1. Find the package name and a ReVanced-compatible version.
2. Copy a `Download APK` + `Patch` step block in the workflow and update the variables.
3. Add the new output filename to the `files:` list in **Publish GitHub Release**.
4. Add the download URL to `PatchedApkDownloader.kt` in the Android app.

## Cost

- **Public repo**: unlimited Actions minutes (free tier).
- **Private repo**: ~15 minutes per run × 4 runs/month ≈ 60 min/month (well inside the 2 000 min free tier).

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `Missing GitHub secrets` on first step | Add the four secrets listed in setup step 3 |
| APK download fails (`No APK downloaded`) | Version no longer on APKPure — try one minor version lower, or check APKMirror manually |
| `No compatible patches found` | ReVanced dropped that app version — update the version constant (see above) |
| `SDK component not found` | GitHub runner is missing `platforms;android-36` — the install step should handle this; if not, open an issue |
| Gradle build fails, patched APKs succeed | The patched APKs are still published. Fix the Android build separately |
