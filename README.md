# Zähler Sync

Android-App als Gegenstelle für die Zähler-App auf der Amazfit Active 2.

Solange die App geöffnet ist, bietet das Handy per Bluetooth LE den Dienst `FFE0` an.
Beim Sync liest die Uhr die Einstellungen aus `FFE1` (`RRGGBB,RRGGBB`, Farbe oben und unten)
und schreibt die Zählerstände als `oben,unten` nach `FFE2`.

## Bauen

- **GitHub:** Jeder Push auf `main` baut die APK. Sie liegt danach unter „Releases" als `ZaehlerSync.apk`.
- **Android Studio:** Projekt öffnen, „Run" oder `./gradlew assembleDebug`. Die APK liegt in `app/build/outputs/apk/debug/`.

Der Schlüssel in `keystore/debug.keystore` ist ein Wegwerf-Schlüssel nur für diese private App.
Er sorgt dafür, dass sich neue Builds über die alte Version installieren lassen.
