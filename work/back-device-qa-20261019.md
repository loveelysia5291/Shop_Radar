# Back navigation device QA — 2026-10-19

Device: Black Shark SHARK_PAR_H0, Android 12. Not the originally reported Android 15 device.
Installed with `adb install -r`, no uninstall/data clear: `1.28-debug-back-20261019`, version code 29.
APK SHA-256: `d495a2717fac3b8c9ce510cd687879f1a395e3e31ad2b0d0a2dee9b87bd29fd0`.

| Test | Observed result | Evidence in work/ |
| --- | --- | --- |
| Subscription, injected left-edge swipe | Search page restored, callback logged | back-new-sub-before.png, back-new-left.png |
| Auction, injected right-edge swipe | Search page restored, callback logged | back-new-auction-before.png, back-new-right.png |
| Shop new-items page, KEYCODE_BACK | Subscription page restored, callback logged | back-new-items-before.png, back-new-items-after.png |
| Card options opened | Popup rendered; return not tested | back-menu-before.png |

Callback log times (device-local): 00:44:01.459, 00:44:34.857, 00:45:21.179, tag RadarBack, message activity_back_received.

The user requested Release packaging and ADB disconnection during QA. ADB server stopped. No further device commands issued. Modal close, keyboard behavior, resume, search-results return, and Android 15 remain unverified. These are Debug device results, not Release device validation.
