# Buds Pro Control

A small Android app for **Samsung Galaxy Buds Pro (SM-R190)** that works without Galaxy Wearable. It started with one
goal: a touch lock that **really stays on**. Adjusting an earbud in your ear should no longer pause or resume YouTube.

## Why you might want it

Galaxy Wearable has a "Lock touch controls" switch, but on current Buds Pro firmware it quietly stops working. After
you put both earbuds back in the case and take them out again, taps work again, even though Wearable still shows the
lock as on.

This app keeps the lock working. It turns the lock back on by itself:

- every time the earbuds connect to your phone, even if the app is closed;
- when you put an earbud in or take it out, when the second earbud joins, and whenever a tap still gets through (with
  **Background guard** on).

## Features

- **Touch lock that stays on.** Also available as a **Quick Settings tile** ("Buds touch lock") for one-tap toggling.
- **Background guard** (optional). It keeps a light connection to the earbuds so the lock is re-applied right away.
  A silent notification shows the lock state and battery levels.
- **Battery**: left, right and case, plus where each earbud is (in ear, out of ear, in the case).
- **Noise control**: Off, Active noise cancelling or Ambient sound.
  - ANC level: low or high.
  - Ambient sound level.
  - Voice detect: switches to ambient sound when you start talking, then goes back after 5, 10 or 15 s.
- **Sound**: equalizer presets (Normal, Bass boost, Soft, Dynamic, Clear, Treble boost) and **Game mode** for lower
  latency.
- **Touch controls**:
  - what touch-and-hold does on each earbud (noise control, voice assistant, volume, Spotify);
  - double tap on the earbud edge.
- **Find my earbuds**: makes the earbuds beep loudly.
- Material You design that follows your wallpaper colors. No ads, no accounts, no internet permission.

## Requirements

- Android 13 or newer. The app is built for Android 17 and works on GrapheneOS.
- Galaxy Buds Pro paired with the phone in Bluetooth settings.
- Galaxy Wearable should **not** run at the same time, in any profile. It may hold the earbuds' control channel,
  and then this app can't connect. The app shows a hint when that seems to be the case.

## Install

1. Download the latest `BudsPro-<version>.apk` from [Releases](../../releases/latest).
2. Open it on your phone and allow installs from that source when asked.
3. Install it in the same Android profile as the apps you watch videos in.
4. Open the app and allow **Nearby devices**. If you turn on Background guard, allow **Notifications** too.

To update, install the newer APK over the old one. Your settings are kept.

## Using it

1. Open the app. It finds your Buds Pro among paired devices; if you have several, pick them from the list.
2. **Lock touch controls** is on by default. Leave it on.
3. Optionally turn on **Background guard** for the most reliable lock.
4. Optionally add the **Buds touch lock** tile to Quick Settings.

While the lock is on, the earbuds still beep when touched. The earbud firmware makes that sound and it can't be
turned off, but the tap does nothing.

## Known limitations

- Only Galaxy Buds Pro (SM-R190) is supported. Other Galaxy Buds models use different commands.
- Firmware updates, voice wake-up and seamless connection settings are not available. Use Galaxy Wearable for those,
  then close it.

## Disclaimer

This project is not affiliated with Samsung. Use it at your own risk.

Technical details, build instructions and the earbud protocol are documented in [AGENTS.md](AGENTS.md).
