<div align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" width="120" alt="Horus app icon" />
  <br/>
  <img src="docs/assets/horus-eye.png" width="96" alt="Eye of Horus" />

  # Horus

  **Take back your attention.** An open source Android app that blocks distracting apps, reels and shorts, tracks your screen time, and protects your settings from your own weak moments.

  Powered by **mhmdahmd**
</div>

---

## What it does

Horus watches over how you use your phone and helps you stay on what matters.

**Block and control**
- App and website blocking, with schedules and usage limits per group.
- Short-form blocker for Instagram Reels and YouTube Shorts, and a live scroll counter.
- UI hider: remove specific parts of an app (for example the YouTube home feed) while keeping the rest.
- Tamper protection that makes it hard to uninstall Horus or change its settings in a weak moment.

**Insights**
- App and website usage stats, focus stats, and a live session timer.

**Mindful tools**
- Focus mode, scheduled Do Not Disturb, per-app grayscale, and home screen widgets.
- Unlock mechanisms for blocked apps: strict, timed, dynamic, QR or barcode scan, sentence retyping, unlock limits, NFC and adaptive math.

**For developers**
- An exported API lets other apps start focus modes, change settings and read usage. See [docs/api.md](docs/api.md).

## Install

1. Open the [debug release](https://github.com/mood-ahmad/curbox-scrcpy-app/releases/tag/debug-latest).
2. Download `curbox-latest-debug.apk`.
3. Install it. Android asks you to allow installs from your browser or file manager the first time.
4. Open Horus and follow the setup. Later builds install over the previous one.

Every build is versioned `1.2.<build number>` and the build number rises with each CI run, so a newer APK always shows a higher version.

## How pairing works

Horus can run privileged actions on its own through Android's Wireless debugging, without a separate Shizuku app. Pairing follows one fixed order:

1. Tap **Auto Pairing**. Horus first sends you to Accessibility settings to enable the Horus service.
2. When the permission is on, Horus brings you back to the app.
3. Horus opens Developer options, scrolls to **Wireless debugging**, turns it on if needed and opens **Pair device with pairing code**.
4. Horus reads the pairing code from the screen and completes pairing.
5. When pairing succeeds, Horus returns you to the app.

If a step is already done, Horus skips to the next one. It never changes the order. Details: [docs/pairing.md](docs/pairing.md).

## How protection works

Tamper protection blocks the screens used to uninstall Horus or turn off its permissions. Keep-alive protection keeps the service running. Removing the uninstall protection takes a signed request, so it cannot be turned off by accident. Details: [docs/protection.md](docs/protection.md).

## Project layout

| Path | What is in it |
| --- | --- |
| `app/` | The Android app (Kotlin). |
| `apitester/` | A sample client for the exported API. |
| `docs/` | Guides: [API](docs/api.md), [pairing](docs/pairing.md), [protection](docs/protection.md). |
| `fastlane/` | Store listing text, icon and changelogs. |
| `.github/workflows/` | CI, debug build and release workflows. |
| `CONTRIBUTING.md`, `agents.md` | Contributor and code-style guides. |

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) first. Fork the repo, make a branch, keep the change small, and open a pull request.

## Credits and license

Horus is a fork of **Curbox** by [nethical6](https://github.com/nethical6) ([curbox-app/curbox-android](https://github.com/curbox-app/curbox-android)). The original project's thanks go to Digipauws, Usage Direct, ShizuTools and MPAndroidChart. The Horus changes, branding and Direct ADB pairing are by **mhmdahmd**.

Horus is free software under the [GNU GPL v3 or later](LICENSE). The upstream copyright and license are kept as required.
