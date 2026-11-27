<!--suppress CheckImageSize -->
# <img width="24" height="24" alt="image" src="/fastlane/metadata/android/en-US/images/icon.png" /> Mighty Calendar Alarm
**Mighty Calendar Alarm** is a HutterSoft fork of [Clock](https://github.com/BlackyHawky/Clock) (AOSP-inspired), focused on alarms from Google Calendar events: profiles matched by event title, fullscreen alarms with snooze, end-of-event alarms, Wi‑Fi conditions and backup. Timer, Stopwatch and Clock are kept; normal alarms were removed.

Application ID: `redpantyhose.claude.mightycalendaralarm` (installs side by side with the upstream Clock app).

# 📑 Table of Contents

- [Download](#-download)
- [Features](#-features)
- [Common Issues](#-common-issues)
- [License](#-license)
- [Credits](#-credits)

# 📥 Download

Fork builds are not yet published on F-Droid. Build from source or use local APKs.

> [!NOTE]  
> **Build variants:**
> - **Release:** Stable versions recommended for everyday use.
> - **Nightly:** Experimental builds with the latest changes.
> - **Debug:** Developer-oriented builds with extra logging and diagnostics.
>
> All variants (Release, Nightly, Debug) can be installed side by side without conflict.
> This fork also installs side by side with upstream `com.best.deskclock`.

# ✨ Features

### • 📅 **Calendar alarms**

* Alarms from Google Calendar events, matched to profiles by a title regex
* Per profile: calendar, ringtone, volume, vibration, offset, escalation and max alert duration
* Three snooze lengths, optional extend-event-on-snooze, and alarms at the end of events
* Optional Wi-Fi condition (ring only near a chosen network)
* Flip or shake to dismiss/postpone, power or volume buttons to snooze/stop

### • 🎨 **Customization**
  * Light, dark, or system theme
  * AMOLED mode for deep blacks
  * Digital or analog clock styles
  * Customizable interface, screensaver, and widgets
  * Dynamic colors for Android 12+

### • 🌍 **World clock**
  * Display home time when abroad
  * View time in multiple cities worldwide

### • ⏱️ **Timer & stopwatch**
  * Built-in timer and stopwatch
  * Share stopwatch results with contacts

### • ⚙️ **Extra features**
  * Quick settings tiles (Android 7+)
  * Backup & restore of calendar profiles and settings (except custom ringtones)
  * Material Design UI
  * Support for [Direct Boot](https://developer.android.com/privacy-and-security/direct-boot)
  * Alarm support on some Snapdragon devices when powered off

> [!NOTE]  
> Some extra features may not work on certain devices:
> - **Direct Boot support**: see the discussion [here](https://github.com/BlackyHawky/Clock/issues/396).
> - **Power‑off alarm on Snapdragon devices**: may fail even if the _"com.qualcomm.qti.poweroffalarm"_ system app is present. See the discussion [here](https://github.com/BlackyHawky/Clock/issues/88).

# 🐞 Common Issues

* Device-specific issues may occur due to limited testing.
* On Android 14+ with HyperOS, the _"Full screen notification"_ permission may be revoked. Possible solution [here](https://github.com/BlackyHawky/Clock/discussions/303#discussioncomment-13407709).
* MIUI users may face problems due to aggressive battery optimizations.
  * Please make sure that battery optimizations are disabled for the app before opening an issue.

# 📜 License

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

Clock is licensed under **GNU General Public License v3.0 (GPLv3)**.
This strong copyleft license requires that any modifications or larger works using Clock must also be distributed under the same license, with complete source code available.

See the [LICENSE](LICENSE) file for full details.

> [!NOTE]  
> Since Clock is based on **AOSP Clock**, which is licensed under **Apache License 2.0**, an additional [Apache 2.0](LICENSE-Apache-2.0) license file is provided in this repository.

# 🏅 Credits
* 🖼️ **App icon**: Soft 3D calendar and clock icon made for this fork
* 💻 Code references and inspiration from:
    * [LineageOS](https://github.com/LineageOS/android_packages_apps_DeskClock)
    * [crDroid Android](https://github.com/crdroidandroid/android_packages_apps_DeskClock)
* 🌍 Translations provided by the community via [Weblate](https://translate.codeberg.org/projects/clock/)
* 🤝 Thanks to all [contributors](https://github.com/BlackyHawky/Clock/graphs/contributors) who help improve Clock
