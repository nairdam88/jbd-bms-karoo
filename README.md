# JBD BMS for Hammerhead Karoo

A lightweight Karoo extension that shows live JBD / XiaoXiang / Overkill Solar
battery data on your ride screen:

- Battery state of charge
- Pack voltage
- Current
- Power

The app also includes a phone UI for scanning, connecting, diagnostics and
battery details.

## Install

1. Download the latest `app-release.apk` from
   [Releases](https://github.com/nairdam88/jbd-bms-karoo/releases/latest).
2. Install it on the Karoo (Companion App sideload or ADB).
3. Open **JBD BMS**, allow Nearby devices access, and connect to your battery.
4. Add the JBD BMS data fields to a ride profile in Karoo.

## Updates

Karoo reads `manifest.json` from the latest release and offers in-place updates.
Long-press the app icon on the Karoo and choose **Update**.

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleRelease
```

Release signing uses environment variables (`KEYSTORE_FILE`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) or a local
`keystore.properties` file (not committed).

## License

The vendored `karoo-ext` module keeps its original Apache-2.0 license.
