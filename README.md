# <p align="center">![icon](app/src/main/res/drawable/parsec_logo.png) ![OpenParsec](app/src/main/res/drawable/openparsec_wordmark.png)</p>

[![GitHub stars](https://img.shields.io/github/stars/nomadsgalaxy/OpenParsec?style=flat-square)](https://github.com/nomadsgalaxy/OpenParsec/stargazers)
[![GitHub forks](https://img.shields.io/github/forks/nomadsgalaxy/OpenParsec?style=flat-square)](https://github.com/nomadsgalaxy/OpenParsec/network/members)
[![GitHub issues](https://img.shields.io/github/issues/nomadsgalaxy/OpenParsec?style=flat-square)](https://github.com/nomadsgalaxy/OpenParsec/issues)
[![GitHub pull requests](https://img.shields.io/github/issues-pr/nomadsgalaxy/OpenParsec?style=flat-square)](https://github.com/nomadsgalaxy/OpenParsec/pulls)
[![GitHub license](https://img.shields.io/github/license/nomadsgalaxy/OpenParsec?style=flat-square)](https://github.com/nomadsgalaxy/OpenParsec/blob/main/LICENSE)
[![GitHub contributors](https://img.shields.io/github/contributors/nomadsgalaxy/OpenParsec?style=flat-square)](https://github.com/nomadsgalaxy/OpenParsec/graphs/contributors)
[![Latest release](https://img.shields.io/github/v/release/nomadsgalaxy/OpenParsec?style=flat-square)](https://github.com/nomadsgalaxy/OpenParsec/releases/latest)

An unofficial Android port of [hugeBlack/OpenParsec][upstream], the open-source
Parsec remote-desktop client. Connects to a Parsec host PC with the same
account-based flow as the official Parsec client.

> Originally an iOS/SwiftUI project by [hugeBlack][upstream-author].
> Android port (Java, Material 3) by [NomadsGalaxy](https://github.com/nomadsgalaxy).

[upstream]: https://github.com/hugeBlack/OpenParsec
[upstream-author]: https://github.com/hugeBlack

For my other projects, check out [my website][website] and [my discord][discord]

[website]: https://nomadsgalaxy.com

[discord]: https://discord.gg/pqCVFkahUt

---

## Download

Grab the latest signed APK from [Releases](../../releases/latest) and side-load it.
You may need to enable *Install unknown apps* for your browser or file manager.

The app has a built-in update checker that polls this repository's
`releases/latest` once every 24 hours and offers a one-tap download when a
newer tag is available. It can be skipped per-version from the prompt.

## Features

- **Touchpad and direct-touch input modes** with a draggable cursor
- **Pill-shaped on-screen mouse-button row** (L / M / R) with press-and-hold
  semantics for click-and-drag and middle-button scroll. The row is freely
  draggable; position persists across sessions
- **IME accessory bar** above the soft keyboard with modifier keys
  (Ctrl / Alt / ⊞ / Shift), arrows, Home/End, PgUp/PgDn, Ins/Del, Esc, Tab,
  F1–F12. Long-press modifiers to lock for chording
- **Foldable-aware layout** — when the keyboard opens, the desktop view
  absorbs the IME into existing letterbox space first so the GL surface and
  soft keyboard share screen real estate. Auto-resets touch + cursor state
  across fold/unfold to recover from dropped gestures
- **Display-cutout aware** placement so overlays steer clear of camera
  punch-outs
- **Material 3 dynamic theming** (system / light / dark)
- **4-finger tap** anywhere in-session resets the FAB and mouse-button row
  to their default positions — a safety hatch if you drag them offscreen
- **Ctrl+Alt+Del** buried in the in-session FAB menu so it's never fired by
  accident (requires `host_ctrl_alt_del=1` on the host's Parsec config)

## Build from source

```bash
git clone https://github.com/nomadsgalaxy/OpenParsec.git
cd OpenParsec
./gradlew assembleRelease
```

The signed APK lands at `app/build/outputs/apk/release/app-release.apk`.

Requirements:

- Android SDK with API 34
- NDK `26.3.11579264` (gradle pulls it automatically)
- Java 17

## Project layout

```
app/                       Android module
  src/main/java/.../       Activities, IME, FAB, settings
  src/main/cpp/            JNI shim around the Parsec SDK
  src/main/res/            Material 3 resources, themes, drawables
sdk/                       Parsec SDK headers
app/libs/                  Pre-built Parsec SDK native libraries per ABI
```

## License

This port retains the upstream OpenParsec [LICENSE](LICENSE). Parsec SDK
binaries are redistributed under their original Parsec terms.

## Credits

- [hugeBlack](https://github.com/hugeBlack) — original OpenParsec iOS client
  that this port mirrors design-wise
- [Parsec](https://parsec.app) — the underlying remote-desktop SDK

The iOS source from before the Android rewrite is preserved on the
[`ios-legacy`](../../tree/ios-legacy) branch.
