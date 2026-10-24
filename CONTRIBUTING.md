# Contributing

See README.md for phone setup and development commands. This fork is a direct-control bridge: do not reintroduce model providers, embedded credentials, autonomous task loops, or duplicate relays.

Run `npm ci --ignore-scripts` and `npm test` for the relay. With JDK 17 and Android SDK 34, run `./gradlew assembleDebug lintDebug`. Test screenshots, taps, text entry and disconnect behavior on an Android 11 device before calling an APK device-verified.
