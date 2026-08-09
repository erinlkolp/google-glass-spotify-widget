# Glass Spotify Widget

Controls Spotify playback on the phone from Google Glass, over WiFi.

The Glass talks to the Spotify Web API directly; Spotify Connect relays commands to the
phone cloud-side. There is no companion app and no Bluetooth, so replacing the phone
requires no work here — install Spotify, log in, done.

Requires Spotify Premium. The Web API returns 403 on every playback-control endpoint for
Free accounts.

```
Glass app  ──HTTPS/WiFi──>  Spotify cloud  ──push──>  Spotify app on phone
```

## Controls

| Gesture | Action |
|---|---|
| Tap | Play / pause |
| Swipe forward | Next track |
| Swipe backward | Previous track |
| Swipe down | Exit |

The prism shows the track title, artist, and play state. Anything else — no active
device, no network, an expired authorization — appears as a single line of white text.

## Build

This project shares `gesture-core` with the Gesture Launcher rather than copying it, so
that repo must be checked out beside this one:

    ~/workspace/google-glass-gesture-launcher/
    ~/workspace/google-glass-spotify-widget/

`gesture-core` holds the touchpad anisotropy maths and the empirically-tuned gesture
thresholds. Those are safety-critical — a wrong threshold once caused a downward swipe to
register as a tap — so they keep a single home. Gradle fails with an explicit message if
the sibling repo is missing.

    ./gradlew test                    # 48 JVM tests, no device needed
    ./gradlew :app:assembleDebug
    adb -s 0123456789ABCDEF install -r app/build/outputs/apk/debug/app-debug.apk

The app appears in the Gesture Launcher as a card reading **Spotify**. The launcher needs
no modification: its `AppRepository` discovers any activity registering `ACTION_MAIN` +
`CATEGORY_LAUNCHER` and titles the card from `ResolveInfo.loadLabel()`.

## First run

Glass has no keyboard, so Spotify authorization happens once on the laptop. See
[`tools/README.md`](tools/README.md).

    python3 tools/bootstrap_token.py <client-id>
    adb -s 0123456789ABCDEF push tools/refresh_token.txt /data/local/tmp/spotify_bootstrap_token

Then launch Spotify on the Glass. It imports the token into private storage, fingerprints
it so a leftover pushed copy can never be re-imported over a rotated token, and deletes
the pushed file if permissions allow.

Delete `tools/refresh_token.txt` afterwards — Spotify rotates the refresh token on every
refresh, so the laptop copy goes stale immediately.

## Architecture

| Module | Type | Contains |
|---|---|---|
| `spotify-core` | `java-library` | All network, token, and orchestration logic. No Android types, so all 48 tests run on the JVM in milliseconds. |
| `app` | Android | `ControllerActivity`, `NowPlayingView`. No network code at all. |

`HttpTransport` is the single network seam. Everything above it is tested against a fake;
only `UrlHttpTransport` performs real I/O.

There are no third-party dependencies in the APK. `org.json` is `compileOnly` — Android
provides it at runtime.

## Notes for future work

- **TLS.** The stock trust store on this AOSP 5.1.1 build reaches Spotify fine — measured
  2026-08-09, chain terminating at DigiCert Global Root G2 (issued 2013, so present in the
  2015 store). No bundled trust anchors are needed. See [`spike/`](spike/). The leaf
  expires 2027-02-20; if Spotify ever migrates to a root issued after 2015 the handshake
  will start failing, and `Secure connection failed` is kept distinguishable so that day
  is diagnosable at a glance.
- **API level.** `java.time` and `java.nio.file` are API 26+. Both compile cleanly against
  the JDK and then throw `NoClassDefFoundError` on this API 22 device, which no JVM test
  can catch. Use `long` millis and `FileInputStream`.
- **Enums** are fine in Gradle builds — AGP bundles its own R8. The d8 enum NPE only
  affects the standalone `d8` binary used for hand-dexed probes like `spike/`.
- **Gestures cannot be tested from adb.** `adb shell input tap/swipe` injects below the
  window manager and bypasses touchable regions entirely. It will report success while
  real bugs are live. Touch changes need a human wearing the device.

## Design

- Spec: [`docs/superpowers/specs/2026-08-07-glass-spotify-widget-design.md`](docs/superpowers/specs/2026-08-07-glass-spotify-widget-design.md)
- Plan: [`docs/superpowers/plans/2026-08-09-glass-spotify-widget.md`](docs/superpowers/plans/2026-08-09-glass-spotify-widget.md)
