# AGENTS.md

Notes for AI agents working on this repo. Read this before changing the code or sending anything to the earbuds.

## What this is

A sideloaded Android app (not published on Google Play) that controls Samsung Galaxy Buds Pro (SM-R190). The owner
runs it on GrapheneOS (Android 17 / API 37). Galaxy Wearable lives in a separate profile there. The whole point of the
app is a touch lock that stays enforced. Everything else mirrors Galaxy Wearable. UI and docs are in English.

## Layout

```
app/src/main/java/dev/pk/budspro/
  protocol/Protocol.kt    Msg IDs, frame encode, streaming Parser (CRC-checked, resyncs on garbage)
  protocol/BudsState.kt   State model; parsers for 0x60 status and 0x61 extended status
  BudsSession.kt          One RFCOMM socket (secure, then insecure fallback); blocking read loop
  Buds.kt                 Singleton: holder-counted connection loop, frame handling, re-lock logic, commands
  BluetoothReceiver.kt    Manifest receiver: ACL_CONNECTED -> one-shot lock re-send; BOOT/PACKAGE_REPLACED -> guard
  GuardService.kt         connectedDevice foreground service ("Background guard") holding the link
  LockTileService.kt      Quick Settings tile toggling the lock
  Prefs.kt                SharedPreferences as StateFlows (lockTouch defaults to true, guard, device address)
  MainActivity.kt, ui/MainScreen.kt   Compose Material 3 UI, single screen
app/src/test/.../ProtocolTest.kt      Frame bytes captured from real earbuds; parser; real 0x61 payload
.github/workflows/release.yml         CI: test, lint, build signed APK, publish to GitHub Releases
tools/*.swift             macOS IOBluetooth tools used to reverse-engineer and verify the protocol
```

## Build and test

- Toolchain on the owner's Mac:
  - `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
  - `ANDROID_HOME=~/Library/Android/sdk` (platform 37, build-tools 37, emulator)
  - Gradle wrapper 9.8.0
- Build and lint: `./gradlew testDebugUnitTest assembleRelease lintRelease`. Keep lint at 0 errors.
- AGP 9.4.1 has **built-in Kotlin**. Do not add `org.jetbrains.kotlin.android`. Only the Compose compiler plugin
  (`org.jetbrains.kotlin.plugin.compose` 2.4.20) is applied.
- `minSdk` is 33 on purpose (APIs like `getParcelableExtra(name, Class)`); the only target is Android 17.
- Local build:

  ```sh
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  export ANDROID_HOME=~/Library/Android/sdk
  ./gradlew testDebugUnitTest assembleRelease
  # -> app/build/outputs/apk/release/app-release.apk
  ```

- Release signing reads `keystore/keystore.properties` (paths are relative to `keystore/`):

  ```properties
  storeFile=release.jks
  storePassword=...
  keyAlias=budspro
  keyPassword=...
  ```

  `keystore/` is git-ignored and must never be committed. Updates over an installed build need the same key, so never
  regenerate it. Without the file, `assembleRelease` produces an unsigned APK; use `assembleDebug` for a debug-signed
  build.

### Release pipeline

`.github/workflows/release.yml` builds, tests, lints and publishes a signed APK to GitHub Releases.

- It runs on a pushed tag `v*`, or manually (`workflow_dispatch` with a `tag` input).
- The tag must equal `v` + `versionName` from `app/build.gradle.kts`; otherwise the job fails. To release, bump
  `versionCode` and `versionName`, commit, then `git tag vX.Y && git push origin vX.Y`.
- The asset is named `BudsPro-<versionName>.apk`. The README links to `releases/latest`.
- Repository secrets it needs, recreated from the owner's local `keystore/`:
  - `KEYSTORE_BASE64` (`base64 -i keystore/release.jks`);
  - `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
- The job refuses to publish an unsigned APK when the secrets are missing.

### Emulator smoke test

There is no Bluetooth in the emulator, so it only checks UI, permissions, the service and the tile.

- `avdmanager` from Homebrew does not see the SDK images. The AVD `a17` was created by hand in `~/.android/avd`,
  using image `system-images;android-37.0;google_apis_ps16k;arm64-v8a`.
- Start it: `$ANDROID_HOME/emulator/emulator -avd a17 -no-window -no-audio -no-snapshot &`
- Useful adb commands:
  - `cmd statusbar add-tile|click-tile dev.pk.budspro/.LockTileService`
  - `dumpsys activity services dev.pk.budspro`
  - `dumpsys notification --noredact`
- The release build is not debuggable, so `run-as` does not work. Use a debug build to inspect prefs.

### Real-hardware checks from the Mac

The earbuds must be connected to the Mac, not the phone.

- Build the client: `swiftc -O -o tools/buds tools/buds.swift`. The binaries are git-ignored.
- Run it: `./tools/buds <rfcommChannel=1> <listenSeconds> [hexId:hexPayload ...]`. It prints every TX/RX frame.
  Examples: `./tools/buds 1 1 90:01` sends LOCK_TOUCHPAD 01; `./tools/buds 1 1 28:` sends an empty payload.
- `tools/sdp.swift` and `tools/sdp2.swift` dump the earbuds' SDP records.
- Ground truth for "did a tap reach the Mac as a media command" is the AVRCP log:
  `/usr/bin/log stream --predicate 'process == "bluetoothd" AND eventMessage CONTAINS "AVRCP"'`, then grep
  `Received AVRCP`. Use `/usr/bin/log`, because `log` is a zsh builtin.
- Spoken reports from a person tapping the earbuds were sometimes inconsistent. Trust the logs.
- **Don't change the owner's earbud settings as a side effect.** Read the 0x61 status first. Only send values that
  are already set, or restore them afterwards. `0xA0` (find my earbuds) is very loud. Holding the touchpad during tests
  toggles ANC.

## Protocol facts (verified on firmware R190XXU0AVF1, extended-status revision 10)

- Transport: SPP over RFCOMM, UUID `00001101-0000-1000-8000-00805F9B34FB`.
- Frame layout: `FD | uint16 LE header | id | payload | CRC16-XMODEM LE over id+payload | DD`.
  - Header: low 10 bits are `len(payload)+3`; the upper bits are a sequence counter. Send 0 there and mask it on
    receive.
  - CRC16-XMODEM is poly `0x1021`, init `0`. It is little-endian on the wire (checked against received frames).
  - Example: `fd 04 00 90 01 ca 08 dd` locks the touchpad.
- Messages used by the app (→ phone to earbuds, ← earbuds to phone):

  | ID | Dir | Meaning |
  |---|---|---|
  | 0x90 | → | Lock touchpad, 1 byte: `01` locked, `00` unlocked |
  | 0x42 | ← | ACK: `[msgId, echoed payload…]` |
  | 0x60 | ← | Status: `[rev, batL, batR, coupled, mainConn, placement L<<4\|R, caseBattery]` |
  | 0x61 | ← | Extended status (offsets below) |
  | 0x2D | ← | Touch event reached the earbuds |
  | 0x91 | ← | TOUCH_UPDATED: `[locked]` |
  | 0x77 / 0x78 | ← / → | Noise mode update / set (0 off, 1 ANC, 2 ambient) |
  | 0x83 / 0x84 | → | ANC level (0 low, 1 high) / ambient level (0–3) |
  | 0x85 / 0x86 | → | Game mode / EQ preset (0–5) |
  | 0x7A / 0x7B | → | Voice detect on/off / timeout (0 = 5 s, 1 = 10 s, 2 = 15 s) |
  | 0x92 | → | Touch-and-hold action `[left, right]`: 1 voice assistant, 2 noise control, 3 volume, 4 Spotify |
  | 0x95 | → | Double tap on the earbud edge |
  | 0xA0 / 0xA1 | → | Find my earbuds start / stop |
  | 0x28 | → / ← | Build info |
- **Lock: `0x90` with exactly one byte** (`01` locked / `00` unlocked). This matches the official plugin.
  GalaxyBudsClient's 5- and 7-byte "advanced touch lock" formats do **not** work on this firmware; this was disproved
  with AVRCP logs. Don't reintroduce them.
- **Firmware bug the app exists for:** after *both* earbuds were in the case and reconnected, 0x61 byte 10 still says
  locked, yet taps work again. Re-sending `0x90 01` restores the lock. One earbud in the case does not trigger it.
- The touch beep still plays while locked. The firmware does it and it cannot be disabled.
- The earbuds send 0x61 unsolicited right after the RFCOMM connection opens. 0x42 ACK payload is
  `[msgId, echoed payload]`.
- 0x61 byte offsets for Buds Pro:

  | Byte | Field |
  |---|---|
  | 2, 3 | battery L/R |
  | 4 | coupled |
  | 6 | placement (L high nibble, R low nibble; 1 in ear, 2 out, 3 in case, 0 disconnected) |
  | 7 | case battery (>100 means N/A) |
  | 8 | game mode |
  | 9 | EQ |
  | 10 | touch lock |
  | 11 | touch-and-hold action (L/R nibbles) |
  | 12 | noise mode |
  | 23 | ambient level |
  | 24 | ANC level |
  | 25 | auto switch |
  | 26 | voice detect |
  | 27 | voice detect timeout |
  | 31 | edge double tap (rev ≥ 7) |

- `0x28` returns `[side, 0, ASCII build string]`, e.g. `2022.06.29/03.33.50/SWHD7408`. The `R190XXU0AVF1` style
  version would need the version-decoding logic from GalaxyBudsClient's `DebugGetAllData`/`0x26`, which is not
  implemented.
- Reference sources used earlier:
  - [GalaxyBudsClient](https://github.com/timschneeb/GalaxyBudsClient) (`Message/Decoder/ExtendedStatusUpdateDecoder.cs`, enums, encoders).
  - A jadx decompile of the official Galaxy Buds Pro Manager 6.0.26031351 (`com.samsung.accessory.atticmgr`, obtained
    through the Galaxy Store stub API). Its `v5/r.java` shows the 1-byte lock.

## Design decisions and Android constraints

- `Buds` keeps the link open only while there is a holder: `ui` (screen started), `guard` (service) or `oneshot`
  (receiver or tile). It releases on `onStop` so the socket does not linger.
- Re-lock triggers, all gated on `prefs.lockTouch`:
  - on connect, and again 2.5 s later;
  - on a 0x60 placement or coupled change, immediately and 2 s later;
  - on 0x61 reporting unlocked;
  - on 0x91 TOUCH_UPDATED reporting unlocked;
  - on any 0x2D touch event (debounced 700 ms).
  Re-sending `0x90 01` is harmless.
- The app's switch is the source of truth. Unlocking from Galaxy Wearable gets overridden while the lock is enabled.
- `ACTION_ACL_CONNECTED` is exempt from implicit-broadcast limits, so the manifest receiver works when the app is
  dead. It uses `goAsync()` with a ~25 s budget: RFCOMM may not be ready right after ACL, so it retries every 3 s.
- Starting a foreground service from the background is generally blocked, so starting the guard from ACL
  intentionally fails silently. It is allowed from `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`; that path was verified
  on the emulator.
- `GuardService.onStartCommand` must call `startForeground` before any `stopSelf`, otherwise the app crashes with
  `ForegroundServiceDidNotStartInTimeException`.
- In guard-only mode, with no A2DP/HEADSET link, the loop waits for a wake-up instead of paging the earbuds, to save
  battery.
- If Galaxy Wearable runs in any profile it may own the RFCOMM channel, and connections fail. The UI shows a hint
  after 2 failures.

## Not verified / not implemented

- End-to-end on the owner's phone with the real earbuds (only Mac + emulator so far).
- Whether 0x2D is emitted while the lock is enforced.
- Whether both earbuds in an *open* case (no ACL drop) also trigger the bug. The guard covers it via placement
  changes.
- Not implemented:
  - 0xAF seamless connection (payload is inverted: send `!enabled`);
  - 0x79 touch-and-hold noise cycle;
  - voice wake-up;
  - firmware update;
  - `R190XXU0AVF1`-style version string.

## Conventions

- Kotlin + Jetpack Compose Material 3, one screen, no DI, minimal dependencies. Use coroutines/StateFlow; blocking
  socket I/O goes on `Dispatchers.IO`.
- Match the existing style: short KDoc on non-obvious classes, sparse comments that explain *why*.
- Add a unit test with captured bytes whenever you touch framing or a parser.
- `README.md` is for end users: features, install, usage. Keep build, protocol and design notes in this file.
