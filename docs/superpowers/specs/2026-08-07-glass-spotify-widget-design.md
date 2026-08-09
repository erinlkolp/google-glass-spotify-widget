# Glass Spotify Widget — Design

**Date:** 2026-08-07
**Status:** Approved, ready for implementation planning
**Target:** Google Glass Explorer Edition, community AOSP 5.1.1 (API 22)

## Summary

A Glass app that controls Spotify playback on the phone over WiFi, with no companion
app on the phone. It appears in the Gesture Launcher as a card labelled "Spotify".
Opening it shows the current track and lets you play, pause, and skip.

The app talks directly to the Spotify Web API over HTTPS. Spotify's servers relay
commands to the phone via Spotify Connect, so Glass never communicates with the phone
directly. No Bluetooth, no pairing, no companion APK.

```
Glass app  ──HTTPS/WiFi──>  Spotify cloud  ──push──>  Spotify app on phone
```

## Goals

- Play, pause, next track, previous track.
- Display the current track title and artist.
- Appear in the Gesture Launcher as "Spotify".
- Require no changes to the Gesture Launcher repo.
- Require no companion app on the phone.

## Non-goals

Deliberately cut. Each is a plausible follow-up, none is in this build.

- Volume control.
- Save / like the current track.
- Seek, scrub, or a progress indicator.
- Album art. It washes out on a see-through optic.
- Playlist or library browsing.
- A persistent now-playing strip on the launcher's home surface.
- Any control of playback on Glass itself. Glass is a remote, never a player.

## Context

### Hardware constraints

Measured on-device 2026-07-30 and 2026-08-04, recorded in RecallNest. This community
AOSP 5.1.1 build is the only open ROM for the device, so these are structural facts,
not a snapshot.

- API 22, OMAP4430, 32-bit ARMv7.
- Display 640x360 at density 240, so 320x180 dp. See-through optic: pure black
  background and pure white text only. Mid-tones and gradients wash out.
- Touchpad is classified by AOSP as a **touchScreen** (kernel sets `INPUT_PROP_DIRECT`,
  no `.idc` file). Touch arrives via ordinary `View.onTouchEvent(MotionEvent)`.
  The Glass GDK is not needed and has no system layer to talk to on this ROM.
- StatusBar window claims the top 38px as touchable, which intercepts downward swipes.
- `ro.build.type=eng`, `ro.secure=0`, SELinux permissive. Anything stored on the device
  is trivially readable.

### Why the Web API, not Bluetooth

The Notifications project established that Glass is **BLE central-only**
(`getBluetoothLeAdvertiser` is NULL, `isPeripheralModeSupported` false). Classic
Bluetooth works, and that project uses RFCOMM with an Android companion app.

For Spotify, the Web API removes the need for any of that. Spotify Connect already
provides remote control as a cloud service. Using it means no RFCOMM socket, no
pairing lifecycle, no companion APK, and no dependence on AVRCP.

### Why the launcher needs no changes

`AppRepository.load()` in the Gesture Launcher issues a generic
`queryIntentActivities` for `ACTION_MAIN` + `CATEGORY_LAUNCHER` and uses
`ResolveInfo.loadLabel()` as the card title. Its `packageWatcher` invalidates the
cache on `ACTION_PACKAGE_ADDED`.

So declaring a launcher activity with `android:label="Spotify"` makes the card appear
on install, with zero launcher modifications. This also keeps the `INTERNET` permission
and the OAuth token out of the HOME app, so a crash in this app cannot take down the
home screen.

## Architecture

One APK, package `dev.erinlkolp.glassspotify`. **No third-party code in the APK.**

The no-dependency rule is deliberate. Gotcha #5 records d8 8.2.2-dev NPE-ing on enums
compiled by JDK 21 javac, and JDK 21 is what is installed here. Every added jar is
another artifact through that dex path. `HttpsURLConnection` and `org.json` are both in
the platform. OkHttp is additionally unattractive because 3.12.x is the last line
supporting API 21 and is long unmaintained.

### Module layout

Two Gradle modules, mirroring the launcher's proven `gesture-core` + `app` split. This
is what makes the JVM testing story real rather than aspirational: everything worth
testing lives in a plain `java-library` that never sees an emulator.

| Module | Type | Contains |
|---|---|---|
| `spotify-core` | `java-library` | `SpotifyClient`, `TokenStore`, `PlaybackState`, `Tls`, error mapping. Pure Java SE — `HttpsURLConnection` and `SSLContext` are both JDK classes. |
| `app` | `com.android.application` | `ControllerActivity`, `NowPlayingView`, gesture translation, manifest |

`spotify-core` takes `org.json` as `compileOnly` plus `testImplementation`. Android
provides `org.json` at runtime, so nothing third-party is packaged into the APK while
tests still get a real implementation.

Because the stock trust store works (see TLS below), `spotify-core` needs nothing
injected from `app` — the small `Tls` helper that pins enabled protocols to TLS 1.2 is
plain `javax.net.ssl` and lives in the core module. The `app` module holds no network
code at all.

```
app ─────────────────────────────────────────┐
  ControllerActivity   UI, gestures, immersive mode
        │
        └── NowPlayingView      rendering only
                                        │
spotify-core ───────────────────────────┼─────┐
  PlayerController   orchestration, optimistic state, polling lifecycle
        │
        ├── SpotifyClient       the only class that touches the network
        │        ├── TokenStore     refresh-token persistence + rotation
        │        └── Tls            SSLSocketFactory pinned to TLS 1.2
        └── PlaybackState       immutable snapshot
```

### Component responsibilities

| Component | Does | Depends on |
|---|---|---|
| `ControllerActivity` | Owns the window, applies immersive flags, translates `MotionEvent` into intents, starts/stops polling with the resume lifecycle | `PlayerController`, `NowPlayingView` |
| `NowPlayingView` | Draws title, artist, play state, and status messages. No logic. | `PlaybackState` |
| `PlayerController` | Applies optimistic state, sequences command-then-refetch, owns the poll timer, maps errors to display strings | `SpotifyClient` |
| `SpotifyClient` | Builds requests, parses JSON, handles 401-refresh-retry. Interface-backed so tests use a fake. | `TokenStore`, `Tls` |
| `TokenStore` | Reads and atomically writes the refresh token; supplies a valid access token | — |
| `Tls` | Supplies an `SSLSocketFactory` restricted to TLS 1.2. Stock trust store. | — |
| `PlaybackState` | Immutable value: title, artist, isPlaying, hasActiveDevice | — |

`SpotifyClient` is the sole network boundary. Everything above it is testable on the
JVM with no device and no network.

### Threading

All network work runs on a single-thread `ExecutorService`. Results post back to the
main thread via a `Handler`. Nothing blocking ever runs on the main thread; a
network-on-main-thread ANR in a foreground Activity is exactly the failure this
structure exists to prevent.

## Spotify Web API usage

Scopes: `user-read-playback-state`, `user-modify-playback-state`.

| Action | Call |
|---|---|
| Read state | `GET /v1/me/player` |
| Play | `PUT /v1/me/player/play` |
| Pause | `PUT /v1/me/player/pause` |
| Next | `POST /v1/me/player/next` |
| Previous | `POST /v1/me/player/previous` |

All carry `Authorization: Bearer <access_token>`. Commands sent without a `device_id`
target whatever device is currently active, which is what we want.

**Premium is required.** Erin's account is Premium (confirmed 2026-08-07). Free-tier
accounts receive `403` on every playback-control endpoint.

### Polling

Poll `GET /v1/me/player` every 3s while the Activity is resumed. Stop entirely in
`onPause`. Ten requests per 30s sits well inside Spotify's rolling-window rate limit,
and the display is not lit when the Activity is not resumed.

On a command: flip the UI optimistically for immediate feedback, issue the call, then
refetch after ~400ms and reconcile. Round-trip latency through the Spotify cloud is
roughly 200-500ms, which is why the optimistic flip matters.

### The dead-app limitation

If Spotify on the phone has been force-killed, `GET /v1/me/player` returns
`204 No Content` and there is no device to command. The Web API cannot wake a dead
app. Backgrounded is fine; killed is not. This surfaces as `No active device`.

## Authentication

**Authorization Code with PKCE.** Implicit Grant is deprecated by Spotify. PKCE means
no client secret ships in the APK, which matters because `ro.secure=0` makes on-device
storage readable by anything.

### One-time bootstrap

There is no keyboard on Glass, so authorization happens once on the laptop. A helper
script:

1. Generates a `code_verifier` and `code_challenge`.
2. Opens the browser to `accounts.spotify.com/authorize` with
   `redirect_uri=http://127.0.0.1:8888/callback`.
3. Catches the redirect with a one-shot local HTTP server.
4. Exchanges the code for an access token and refresh token.
5. Writes the refresh token to a file for `adb push` to the device.

### Refresh-token rotation is a footgun

**Spotify's PKCE flow rotates the refresh token on every refresh.** Each
`grant_type=refresh_token` call returns a *new* refresh token and invalidates the one
used. If a rotated token is ever written non-atomically, or the process dies between
using it and persisting it, the app is permanently locked out and the laptop bootstrap
must be repeated.

Therefore `TokenStore`:

- Persists the rotated token by **write-temp-then-`rename`** on the same filesystem.
  `rename` is atomic, and this keeps `TokenStore` in `spotify-core` as plain Java SE
  with no `SharedPreferences` dependency. A partially written file can never be
  observed.
- Persists the new refresh token *before* the access token that arrived with it is used
  for any request.
- Treats a persist failure as **fatal and surfaced**, never swallowed.

Access tokens live 3600s. On `401`, refresh once and retry the original request exactly
once.

## TLS — RESOLVED, measured on hardware 2026-08-09

This was the project's top risk. **It is closed, and the answer is favourable.**

A dex probe was run on the real Glass via `app_process32` (the same technique used for
the 2026-08-04 BLE measurement). Results:

```
protocol = TLSv1.2
cipher   = TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
provider = com.android.org.conscrypt.OpenSSLSocketFactoryImpl

chain    common.spotify.com  (expires 2027-02-20)
      ←  DigiCert Global G2 TLS RSA SHA256 2020 CA1  (expires 2031-03-29)
      ←  DigiCert Global Root G2

GET https://api.spotify.com/v1/me        → HTTP 401, well-formed JSON error body
GET https://accounts.spotify.com/api/token → HTTP 405 (POST-only endpoint)
```

Both hosts complete the handshake against the **stock system trust store**, with no
intervention. Spotify chains to **DigiCert Global Root G2**, issued 2013, which
predates this ROM's 2015 store and is therefore already present. The 401 is the ideal
outcome: TLS completed, the request reached Spotify, and only the token is missing.

### Consequences for the design

- **`TlsFactory` is removed from the design.** No PEM bundle in `res/raw`, no
  `CertificateFactory` → `KeyStore` → `TrustManagerFactory` wiring, no `openssl
  s_client` regeneration script, no CA-rotation maintenance.
- Use plain `HttpsURLConnection` throughout.
- `spotify-core` no longer needs an injected `SSLSocketFactory` from the `app` module,
  which removes that seam entirely.

### One retained hardening measure

The probe showed the client advertises `SSLv3, TLSv1, TLSv1.1, TLSv1.2` — a 2015
default. Spotify's servers will not negotiate the weak ones, so real-world risk is low,
but a ~15-line `SSLSocketFactory` wrapper calling `setEnabledProtocols` to TLS 1.2 only
closes the question for a fraction of the code the trust-anchor work would have cost.
Keep it.

### Still not negotiable

No `ALLOW_ALL_HOSTNAME_VERIFIER`. No trust-everything `X509TrustManager`. The
connection carries a token that controls the account. Since the stock store works,
there is now no scenario in which either would even be tempting.

### Watch item

The leaf expires 2027-02-20 and the intermediate 2031-03-29; DigiCert Global Root G2 is
valid to 2038. If Spotify ever migrates to a root issued after 2015, the handshake will
start failing and the bundled-anchor approach becomes necessary after all. The
`Secure connection failed` error string is retained specifically so that day is
diagnosable at a glance.

## UI

320x180 dp, pure black background, pure white text. Theme
`Theme.Holo.NoActionBar.Fullscreen`, matching the launcher.

```
┌──────────────────────────────┐
│                              │
│  Black Hole Sun              │   title
│  Soundgarden                 │   artist
│                              │
│  ▶ playing                   │   state
│                              │
└──────────────────────────────┘
```

Status messages replace the state line when something needs saying.

### Gestures

Ordinary `View.onTouchEvent(MotionEvent)`. All single-finger, which avoids gotcha #1
entirely — the framework's multitouch collapse only affects two-finger gestures.

| Gesture | Physical direction | Action |
|---|---|---|
| Tap | — | Play / pause |
| Swipe forward | Toward the front of the head, i.e. increasing X | Next track |
| Swipe back | Toward the ear, i.e. decreasing X | Previous track |
| Swipe down | Toward the ground, i.e. increasing Y | Exit |

Directions are stated physically because the touchpad's native surface is 1366x187
rescaled onto 640x360, so "left" and "right" are ambiguous between the pad and the
display. The launcher's `GestureOrientation.DEFAULT` already encodes this mapping and
is the reference if there is ever doubt.

### Immersive mode is load-bearing

The StatusBar claims the top 38px, and the touchpad's 187 native vertical units map
onto 360px, so the top of the pad falls inside that region. Without immersive mode,
swipe-down-to-exit opens the notification shade instead.

Set `SYSTEM_UI_FLAG_FULLSCREEN | HIDE_NAVIGATION | IMMERSIVE_STICKY | LAYOUT_STABLE`
in `onCreate` **and** re-apply in `onWindowFocusChanged`, exactly as
`LauncherActivity.applyImmersiveMode()` does. `LOW_PROFILE` does not work for this — it
only dims nav icons.

## Error handling

Every failure maps to one short line on the prism. Detail goes to logcat. A stack trace
is useless on a see-through display read while walking.

| Condition | Prism shows | Behavior |
|---|---|---|
| `401` | *(silent)* | Refresh token, retry once |
| Refresh rejected | `Re-authorize on laptop` | Terminal; repeat bootstrap |
| `403` | `Not permitted` | Premium or restriction violation |
| `404` no active device | `No active device` | Start Spotify on phone |
| `204` | `Nothing playing` | Idle; keep polling |
| `429` | *(silent)* | Honor `Retry-After`, back off polling |
| `SSLHandshakeException` | `Secure connection failed` | Deliberately distinguishable |
| `UnknownHostException` | `No network` | Glass is off WiFi |
| Token persist failure | `Token save failed` | Loud and fatal |

## Known limitation: WiFi coverage

This is the direct cost of dropping the companion app. **Raised and explicitly accepted
by Erin on 2026-08-07.**

The app works only when Glass has internet. At home or the office, that is the normal
WiFi network. Away from known networks, Glass joins the phone's hotspot, which Erin is
happy to enable when out. The Bluetooth route would need no network but would need the
companion app, which is the thing being avoided.

Design consequences:

- Glass must have the home network **and** the phone's hotspot saved, so it roams
  between them without intervention.
- Hotspot costs battery on both devices. Glass's battery is the smaller constraint, and
  the 3s poll runs only while the controller is actually on screen, which keeps this to
  the duration of active use rather than all day.
- `UnknownHostException` handling matters more than it would on a permanently connected
  device: `No network` is an expected state here, not an exceptional one, and must not
  look like a crash.

## Testing

### Off-device (JVM, no hardware)

- JSON parsing against fixtures captured from real API responses.
- Token refresh state machine, including rotation persistence and persist-failure.
- The error-mapping table above.
- Gesture classification from recorded touch traces.

`SpotifyClient` sits behind an interface, so a fake HTTP layer drives all of the above
without a network.

### What automated tests cannot certify

Per gotcha #3, `adb shell input tap/swipe` injects *below* the window manager and
bypasses touchable regions entirely. On the launcher project, 40 automated tests passed
green while both the multitouch bug and the status-bar-steals-swipes bug were live on
real hardware. `adb shell input` also cannot inject multitouch at all.

**The gesture layer and immersive-mode behavior require real-finger testing by Erin.**
The implementation plan must carry this as an explicit human step. No test suite covers
it.

### On-device sequence

1. ~~TLS spike~~ — **done 2026-08-09, passed.** See the TLS section.
2. Token bootstrap and first authenticated call.
3. End-to-end against real playback on the phone.

When testing failure paths, clear `/data/dalvik-cache` — ART will serve a stale dex and
mask a bad build (gotcha #6).

## Build setup

Mirror the launcher, which is a known-good configuration on this toolchain:
AGP 8.7.0, `compileSdk = 34`, `minSdk = 22`, `targetSdk = 22`, Java 8 compatibility,
`android.useAndroidX=false`, JUnit 4.13.2. Kotlin DSL build scripts.

Toolchain notes, from the recorded gotchas and verified 2026-08-07:

- Host JDK is **OpenJDK 21.0.11**, and it is a full JDK with `lib/ct.sym`. Gotcha #4
  was about Ubuntu's *JRE* package lacking `ct.sym`; that does not apply here.
- Consequently `options.release.set(8)` **does** work for the `java-library` module, and
  the launcher already relies on it in `gesture-core/build.gradle.kts`. The Android
  module uses `compileOptions { sourceCompatibility / targetCompatibility }` instead,
  because AGP emits `-source`/`-target` rather than `--release`. Follow that split.
- JDK 21 javac is exactly the trigger for gotcha #5, where d8 8.2.2-dev NPEs on any
  enum because javac records a nameless `MethodParameters` entry for implicit
  enum-constructor params. **Avoid enums in shipped code**; use `static final int`
  constants. This dodges the jar-stripping workaround entirely.
- Use the system Android SDK, as the launcher moved to in commit `3d01d3c`.
- Device shell lacks `head`, `which`, `pidof`, `sed`. Pipe to the host instead
  (gotcha #8).

### Devices

Both attached over adb as of 2026-08-07:

| Role | Serial | Product |
|---|---|---|
| Glass | `0123456789ABCDEF` | `aosp_glass_1` |
| Phone | `VS9967edd915b` | LG V30, `joan_vzw` |

## Phone independence

A replacement Android phone is on order, expected around late August 2026.

**This design requires no work when it arrives.** Because control is mediated by the
Spotify cloud rather than a direct Glass-to-phone link, the phone is interchangeable:
install Spotify, log into the same account, and the new handset becomes the active
Connect device. There is no pairing to redo, no companion APK to reinstall, and no
device-specific code anywhere in this project.

This is a meaningful contrast with the Notifications project, which uses classic
Bluetooth RFCOMM plus an Android companion app and *will* need bring-up work on the new
phone. Worth keeping in mind when sequencing the two projects, but it is not a
dependency in either direction.

## Open questions

None. Resolved during design:

- Premium confirmed available.
- Form factor: launcher card, not embedded panel.
- Structure: single APK, launcher untouched.
- Controls: transport and now-playing display only.
- **TLS against the 2015 trust store: measured working 2026-08-09.** `TlsFactory` and
  the bundled PEM anchors are out of scope.
