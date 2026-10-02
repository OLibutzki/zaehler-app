# Zähler

Zwei Zähler auf der Amazfit Active 2 (Round), gesteuert über die beiden Seitentasten, mit Bluetooth-Sync zum Handy.

- `uhr/` enthält die Zepp-OS-App für die Uhr.
- Der Rest des Repositorys ist die Android-App „Zähler Sync" als Gegenstelle.

## Android-App

Solange die App geöffnet ist, bietet das Handy per Bluetooth LE den Dienst `FFE0` an.
Beim Sync liest die Uhr die Einstellungen aus `FFE1` (`RRGGBB,RRGGBB`, Farbe oben und unten)
und schreibt die Zählerstände als `oben,unten` nach `FFE2`.

### Bauen

- **GitHub:** Jeder Push auf `main` baut die APK. Sie liegt danach unter „Releases" als `ZaehlerSync.apk`.
- **Android Studio:** Projekt öffnen, „Run" oder `./gradlew assembleDebug`. Die APK liegt in `app/build/outputs/apk/debug/`.

Der Schlüssel in `keystore/debug.keystore` ist ein Wegwerf-Schlüssel nur für diese private App.
Er sorgt dafür, dass sich neue Builds über die alte Version installieren lassen.

## Uhren-App

Zepp-OS-App für die Amazfit Active 2 (Round), installiert über Gadgetbridge.

- Obere Taste zählt den oberen, untere Taste den unteren Zähler. Die Stände überleben das Schließen der App.
- „Sync" überträgt die Stände per Bluetooth an die Android-App und übernimmt die dort gewählten Farben.
- „Licht" hält den Bildschirm dauerhaft an, „Reset" setzt beide Zähler nach Bestätigung zurück.

### Bauen

```bash
npm i -g @zeppos/zeus-cli
cd uhr
zeus build --js2bin false
```

Die Installationsdatei liegt danach in `uhr/dist/` als `.zab` und lässt sich auf dem Handy mit Gadgetbridge öffnen.
`zeus build` lädt beim ersten Aufruf eine Geräteliste von Zepp herunter.

`uhr/lib/easy-ble.js` ist die Bibliothek Easy BLE von silver-zepp (MIT-Lizenz) mit einer lokalen Fehlerkorrektur.
