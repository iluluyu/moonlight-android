# Artemis Android

Previously named Moonlight Noir

An open source client for [Apollo](https://github.com/ClassicOldSong/Apollo)/[Sunshine](https://github.com/LizardByte/Sunshine).

Artemis Android will allow you to stream your collection of games from your Windows PC to your Android device,
whether in your own home or over the internet.

Artemis is currently the best fork of Moonlight with loads of optimizations for office usage.

A more seamless experience with virtual display will be Artemis paired with [Apollo](https://github.com/ClassicOldSong/Apollo).

> **This is a personal fork** of Artemis Android, with specific optimizations for **OPPO Pad 4 Pro** trackpad and **Shizuku-based keyboard capture**.

# Fork Features

Built on top of Artemis Android, this fork adds the following enhancements:

1. **Shizuku keyboard capture** — exclusive evdev-level keyboard grab via [Shizuku](https://github.com/RikkaApps/Shizuku), blocking OEM system shortcuts (e.g. OPPO Win→AI) during streaming and ensuring reliable physical key delivery including Esc.
2. **OPPO Pad 4 Pro trackpad optimization** — refined multi-finger gesture detection, stabilized pinch/scroll/drag behavior, and Mac-style trackpad feel tailored for the OPPO Pad 4 Pro's physical trackpad.
3. **Physical trackpad 3-finger gesture support** — three-finger swipe/tap mapped to Windows shortcuts (Alt+Tab, virtual desktop switch), configured through the framework MotionEvent path.
4. **Keyboard capture mode selection** — choose between Shizuku (recommended) or Android Accessibility Service, with real-time active/inactive status display in settings.
5. **Guided Shizuku authorization** — first-time setup guides users through permission granting with clear status feedback and fallback dialogs.
6. **Stream-start Shizuku status notification** — Toast alerts when keyboard capture is not active at stream start.

# Features

If you switch back to the main stream version, you'll be missing the following awesome features which are very unlikely to be added there:

1. Custom virtual buttons with import and export support.
2. [Custom resolutions](https://github.com/moonlight-stream/moonlight-android/pull/1349).
3. Custom bitrates.
4. [Multiple mouse mode switching](https://github.com/moonlight-stream/moonlight-android/pull/1304) (normal mouse, [multi-touch](https://github.com/moonlight-stream/moonlight-android/pull/1364), touchpad, disabled, local cursor mode).
5. Optimized virtual gamepad skins and free joystick.
6. External monitor mode.
7. Joycon D-pad support.
8. Simplified performance information display.
9. [Game back menu](https://github.com/moonlight-stream/moonlight-android/pull/1171).
10. Custom shortcut commands.
11. Easy soft keyboard switching.
12. Portrait mode.
13. Display on top mode, useful for foldable phones.
14. [Virtual touchpad space and sensitivity adjustment](https://github.com/moonlight-stream/moonlight-android/issues/1348#issuecomment-2236344729) for playing right-click view games, such as Warcraft.
15. Force use device's own vibration motor (in case your gamepad's vibration is not effective).
16. Gamepad debugging page to view gamepad vibration and gyroscope information, as well as Android kernel version information.
17. Trackpad tap/scrolling support
18. Natural track pad mode with touch screen
19. Non-QWERTY keyboard layout support
20. Quick Meta key with physical BACK button
21. Frame rate lock fix for some devices
22. Video scale mode: Fit/Fill/Stretch
23. View pan/zoom support
24. Rotate screen in-game
25. Add option to quit app directly
26. Samsung DeX scrolling support
27. Proper click/scroll/right-click for trackpad on generic Android tablet when using local cursor
28. Virtual Display integration with [Apollo](https://github.com/ClassicOldSong/Apollo)
29. Server Command integration with [Apollo](https://github.com/ClassicOldSong/Apollo)
30. Clipboard sync (requires Apollo)
31. SBS 3D for external Displays (Using AI MiDaS v2 Lite)

## Acknowledgements

This fork builds upon [Artemis Android](https://github.com/ClassicOldSong/moonlight-android) (formerly Moonlight Noir) by [ClassicOldSong](https://github.com/ClassicOldSong). All credit for the core Artemis feature set goes to the original author.

## Downloads
* [Download APK directly](https://github.com/iluluyu/moonlight-android/releases)
* [Use Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.limelight.noir%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Filuluyu%2Fmoonlight-android%22%2C%22author%22%3A%22iluluyu%22%2C%22name%22%3A%22Artemis%22%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%5C%22nonRoot%5C%22%2C%5C%22matchGroutToUse%5C%22%3A%5C%22%241%5C%22%2C%5C%22versionExtractionRegEx%5C%22%3A%5C%22v(.%2B)%5C%22%7D%22%7D) (recommended)

## Shizuku Setup (Optional, Recommended)

Shizuku enables exclusive keyboard capture that blocks OEM system shortcuts during streaming. To use it:

1. Install [Shizuku](https://github.com/RikkaApps/Shizuku) from Google Play or GitHub Releases.
2. Start the Shizuku service (via Wireless Debugging or ADB).
3. In Artemis → Settings → Advanced → Keyboard capture, select **Shizuku**.
4. Grant permission when prompted. The status should show "Active".

## Building
* Install Android Studio and the Android NDK
* Run ‘git submodule update --init --recursive’ from within moonlight-android/
* In moonlight-android/, create a file called ‘local.properties’. Add an ‘ndk.dir=’ property to the local.properties file and set it equal to your NDK directory.
* Build the APK using Android Studio or gradle

## Authors

* [Cameron Gutman](https://github.com/cgutman)  
* [Diego Waxemberg](https://github.com/dwaxemberg)  
* [Aaron Neyer](https://github.com/Aaronneyer)  
* [Andrew Hennessy](https://github.com/yetanothername)

Moonlight is the work of students at [Case Western](http://case.edu) and was
started as a project at [MHacks](http://mhacks.org).
