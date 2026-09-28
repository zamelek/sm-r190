# Buds Pro Control

An Android app that controls Samsung Galaxy Buds Pro (SM-R190) without Galaxy Wearable. Its main feature is a
touch lock that actually stays on.

## Why Galaxy Wearable's lock is not enough

After both earbuds go back into the case and reconnect, firmware R190XXU0AVF1 keeps *reporting* the touchpad as
locked (extended status byte 10 = 1), but it no longer *enforces* the lock. Taps pause and resume videos again until
the lock command `0x90 01` is sent once more. This was confirmed with AVRCP logs on a Mac. Galaxy Wearable never
re-sends it. This app does:

- on every Bluetooth connection of the earbuds (a manifest `ACL_CONNECTED` receiver, so it works even when the app is
  closed);
- with **Background guard** enabled, also on earbud placement changes (in ear / in case), when the second earbud
  joins, and on any touch event that still reaches the earbuds.

The touch beep is played by the firmware and cannot be turned off.

## Features

- Touch lock with automatic re-apply, plus a Quick Settings tile.
- Optional **Background guard**, a `connectedDevice` foreground service. It keeps the link open and shows battery
  levels in its notification.
- Battery for the left and right earbuds and the case, plus where each earbud is (in ear, out of ear, in case).
- Noise control: Off / ANC / Ambient, ANC level, ambient level, voice detect with a timeout.
- Equalizer presets and game mode.
- Touch-and-hold action for each earbud, and double tap on the earbud edge.
- Find my earbuds.

## Requirements

- Android 13+ (built for Android 17, API 37). Tested on an Android 17 emulator; the protocol was verified against real
  earbuds from a Mac.
- The earbuds paired with the phone. Galaxy Wearable must not be running in the same or another profile, because it
  may hold the earbuds' control channel.

## Install

1. Download the APK (from Releases, or build it yourself, see below) and open it on the phone. Allow installs from
   that source.
2. Install it in the same Android profile as the apps you watch videos in.
3. Grant **Nearby devices**, and **Notifications** if you use Background guard.

## Build

Requires JDK 21 and an Android SDK with platform 37 and build-tools 37.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=~/Library/Android/sdk
./gradlew testDebugUnitTest assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

Release signing reads `keystore/keystore.properties`:

```properties
storeFile=release.jks
storePassword=...
keyAlias=budspro
keyPassword=...
```

Without that file the release APK is left unsigned; use `assembleDebug` for a debug-signed build. `keystore/` is
git-ignored. Keep the key, because updating an installed app requires the same signature.

## Protocol notes

Transport is SPP over RFCOMM, UUID `00001101-0000-1000-8000-00805F9B34FB`.

Each frame looks like this:

```
FD | size: uint16 LE (low 10 bits; upper bits are a sequence counter) | msgId | payload | CRC16 LE | DD
```

- `size = len(payload) + 3`.
- The CRC is CRC16-XMODEM (poly `0x1021`, init `0`) over `msgId + payload`.

Example: `fd 04 00 90 01 ca 08 dd` locks the touchpad.

| ID | Direction | Meaning |
|------|---|---|
| 0x90 | → | Lock touchpad, 1 byte: `01` locked, `00` unlocked. This firmware accepts only the 1-byte form. |
| 0x42 | ← | ACK: `[msgId, echoed payload…]` |
| 0x60 | ← | Status: `[rev, batL, batR, coupled, mainConn, placement L<<4\|R, caseBattery]` |
| 0x61 | ← | Extended status, sent on connect. The Buds Pro layout is in `BudsState.withExtended`. |
| 0x2D | ← | Touch event reached the earbuds |
| 0x77 / 0x78 | ←/→ | Noise mode update / set (0 off, 1 ANC, 2 ambient) |
| 0x83 / 0x84 | → | ANC level (0 low, 1 high) / ambient level (0–3) |
| 0x85 / 0x86 | → | Game mode / EQ preset (0–5) |
| 0x7A / 0x7B | → | Voice detect on/off / timeout (0 = 5 s, 1 = 10 s, 2 = 15 s) |
| 0x92 | → | Touch-and-hold action `[left, right]`: 1 voice assistant, 2 noise control, 3 volume, 4 Spotify |
| 0x95 | → | Double tap on the earbud edge |
| 0xA0 / 0xA1 | → | Find my earbuds start / stop |
| 0x28 | →/← | Build info, returns an ASCII build string |

The command IDs and layouts were cross-checked against
[GalaxyBudsClient](https://github.com/timschneeb/GalaxyBudsClient) and the official Galaxy Buds Pro Manager plugin.

## macOS test tools

`tools/buds.swift` is a small RFCOMM client for sending frames to earbuds that are paired with a Mac:

```sh
swiftc -O -o tools/buds tools/buds.swift
./tools/buds 1 2 90:01      # channel 1, listen 2 s, send LOCK_TOUCHPAD 01
./tools/buds 1 2 28:        # empty payload
```

`tools/sdp.swift` and `tools/sdp2.swift` dump the earbuds' SDP records.

## Disclaimer

This project is not affiliated with Samsung. Use it at your own risk.
