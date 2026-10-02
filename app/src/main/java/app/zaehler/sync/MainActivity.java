package app.zaehler.sync;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * Gegenstelle fuer die Zaehler-App auf der Uhr.
 *
 * Solange diese App im Vordergrund ist, bietet das Handy per Bluetooth LE den Dienst FFE0 an.
 * Die Uhr verbindet sich beim Sync, liest die Einstellungen aus FFE1 und schreibt die
 * Zaehlerstaende nach FFE2. Beide Werte sind kurzer Text unter 20 Byte, weil laengere Werte
 * mit der Uhr nie getestet wurden.
 */
public class MainActivity extends Activity {

    private static UUID shortUuid(String hex) {
        return UUID.fromString("0000" + hex + "-0000-1000-8000-00805f9b34fb");
    }

    private static final UUID SERVICE_UUID = shortUuid("ffe0");
    /** Die Uhr liest hier die Einstellungen: "RRGGBB,RRGGBB" (Farbe oben, Farbe unten). */
    private static final UUID CONFIG_UUID = shortUuid("ffe1");
    /** Die Uhr schreibt hier die Zaehlerstaende: "oben,unten" als Dezimalzahlen. */
    private static final UUID STATE_UUID = shortUuid("ffe2");

    private static final int REQUEST_PERMISSIONS = 1;
    private static final int MAX_HISTORY = 50;
    private static final String PREFS = "zaehler";

    private static final int[] PALETTE = {
            0x00E0A0, 0xFFA030, 0xFFFFFF, 0x4DA3FF, 0xFF5A5A, 0xFFE14D, 0xC77DFF, 0x7CFF6B
    };

    private static final int BG = 0xFF0E1210;
    private static final int SURFACE = 0xFF171C19;
    private static final int TEXT = 0xFFE8EEEA;
    private static final int MUTED = 0xFF97A39C;

    private SharedPreferences prefs;
    private BluetoothManager bluetoothManager;
    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private boolean advertising = false;
    private boolean permissionAsked = false;

    private int colorTop;
    private int colorBottom;

    private TextView statusView;
    private TextView topValue;
    private TextView bottomValue;
    private TextView lastSyncView;
    private LinearLayout topSwatches;
    private LinearLayout bottomSwatches;
    private LinearLayout historyBox;

    // ---------------------------------------------------------------- Lebenszyklus

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        colorTop = prefs.getInt("colorTop", PALETTE[0]);
        colorBottom = prefs.getInt("colorBottom", PALETTE[1]);
        bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        buildUi();
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        startBluetooth();
    }

    @Override
    protected void onStop() {
        stopBluetooth();
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            startBluetooth();
        }
    }

    // ---------------------------------------------------------------- Bluetooth

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private void startBluetooth() {
        if (advertising) {
            return;
        }
        if (!hasPermissions()) {
            if (!permissionAsked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissionAsked = true;
                requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_ADVERTISE,
                        Manifest.permission.BLUETOOTH_CONNECT
                }, REQUEST_PERMISSIONS);
            }
            setStatus("Die Berechtigung „Geräte in der Nähe“ fehlt. Erlaube sie in den App-Einstellungen.", true);
            return;
        }
        BluetoothAdapter adapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();
        if (adapter == null) {
            setStatus("Dieses Gerät hat kein Bluetooth.", true);
            return;
        }
        if (!adapter.isEnabled()) {
            setStatus("Bluetooth ist ausgeschaltet. Schalte es ein und öffne die App erneut.", true);
            return;
        }
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            setStatus("Dieses Gerät kann sich nicht per Bluetooth LE anbieten.", true);
            return;
        }

        try {
            gattServer = bluetoothManager.openGattServer(this, gattCallback);
            if (gattServer == null) {
                setStatus("Der Bluetooth-Dienst ließ sich nicht starten.", true);
                return;
            }
            BluetoothGattService service = new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            service.addCharacteristic(new BluetoothGattCharacteristic(
                    CONFIG_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ));
            service.addCharacteristic(new BluetoothGattCharacteristic(
                    STATE_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE));
            gattServer.addService(service);

            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                    .build();
            advertiser.startAdvertising(settings, data, advertiseCallback);
            advertising = true;
            setStatus("Bereit. Tippe auf der Uhr auf „Sync“.", false);
        } catch (SecurityException e) {
            setStatus("Die Berechtigung „Geräte in der Nähe“ fehlt. Erlaube sie in den App-Einstellungen.", true);
        }
    }

    @SuppressLint("MissingPermission")
    private void stopBluetooth() {
        try {
            if (advertiser != null && advertising) {
                advertiser.stopAdvertising(advertiseCallback);
            }
            if (gattServer != null) {
                gattServer.close();
            }
        } catch (SecurityException ignored) {
            // Berechtigung wurde zwischenzeitlich entzogen; es gibt nichts mehr zu stoppen.
        }
        advertising = false;
        gattServer = null;
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartFailure(int errorCode) {
            advertising = false;
            runOnUiThread(() -> setStatus("Das Handy konnte sich nicht per Bluetooth anbieten (Fehler " + errorCode + ").", true));
        }
    };

    private byte[] configBytes() {
        String text = String.format(Locale.ROOT, "%06X,%06X", colorTop & 0xFFFFFF, colorBottom & 0xFFFFFF);
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                runOnUiThread(() -> setStatus("Uhr verbunden …", false));
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            BluetoothGattServer server = gattServer;
            if (server == null) {
                return;
            }
            try {
                if (CONFIG_UUID.equals(characteristic.getUuid())) {
                    byte[] all = configBytes();
                    if (offset > all.length) {
                        server.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
                    } else {
                        server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                                Arrays.copyOfRange(all, offset, all.length));
                    }
                } else {
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null);
                }
            } catch (SecurityException ignored) {
                // Ohne Berechtigung kann nicht geantwortet werden; die Uhr meldet dann einen Lesefehler.
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            BluetoothGattServer server = gattServer;
            long[] reading = STATE_UUID.equals(characteristic.getUuid()) && !preparedWrite && offset == 0
                    ? parseState(value) : null;
            if (responseNeeded && server != null) {
                try {
                    server.sendResponse(device, requestId,
                            reading != null ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_FAILURE,
                            offset, null);
                } catch (SecurityException ignored) {
                    // siehe oben
                }
            }
            if (reading != null) {
                runOnUiThread(() -> onReading(reading[0], reading[1]));
            }
        }
    };

    /** Liest "oben,unten". Gibt null zurueck, wenn der Text nicht passt. */
    static long[] parseState(byte[] value) {
        if (value == null || value.length == 0 || value.length > 40) {
            return null;
        }
        String text = new String(value, StandardCharsets.US_ASCII).trim();
        String[] parts = text.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            long top = Long.parseLong(parts[0].trim());
            long bottom = Long.parseLong(parts[1].trim());
            if (top < 0 || bottom < 0) {
                return null;
            }
            return new long[]{top, bottom};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- Daten

    private JSONArray history() {
        try {
            return new JSONArray(prefs.getString("history", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void onReading(long top, long bottom) {
        try {
            JSONArray old = history();
            JSONArray next = new JSONArray();
            JSONObject entry = new JSONObject();
            entry.put("t", System.currentTimeMillis());
            entry.put("top", top);
            entry.put("bottom", bottom);
            next.put(entry);
            for (int i = 0; i < old.length() && next.length() < MAX_HISTORY; i++) {
                next.put(old.getJSONObject(i));
            }
            prefs.edit().putString("history", next.toString()).apply();
            setStatus("Stand übernommen: oben " + top + ", unten " + bottom + ".", false);
        } catch (Exception e) {
            setStatus("Der Stand kam an, ließ sich aber nicht speichern.", true);
        }
        render();
    }

    private void chooseColor(boolean top, int color) {
        if (top) {
            colorTop = color;
        } else {
            colorBottom = color;
        }
        prefs.edit().putInt("colorTop", colorTop).putInt("colorBottom", colorBottom).apply();
        render();
        setStatus("Farbe gespeichert. Die Uhr übernimmt sie beim nächsten Sync.", false);
    }

    // ---------------------------------------------------------------- Oberflaeche

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    private TextView text(String value, float sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private LinearLayout.LayoutParams block(int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMarginDp);
        return params;
    }

    private void buildUi() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(page, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        page.addView(text("Zähler Sync", 26, TEXT, true));

        statusView = text("", 15, MUTED, false);
        page.addView(statusView, block(6));

        // Letzter Stand, aufgebaut wie die Anzeige der Uhr
        LinearLayout dial = new LinearLayout(this);
        dial.setOrientation(LinearLayout.VERTICAL);
        dial.setGravity(Gravity.CENTER_HORIZONTAL);
        dial.setPadding(dp(16), dp(18), dp(16), dp(18));
        GradientDrawable dialBg = new GradientDrawable();
        dialBg.setColor(0xFF050706);
        dialBg.setCornerRadius(dp(24));
        dialBg.setStroke(dp(3), 0xFF343C37);
        dial.setBackground(dialBg);

        TextView topLabel = text("oben", 14, MUTED, false);
        topLabel.setGravity(Gravity.CENTER);
        dial.addView(topLabel);
        topValue = text("–", 64, colorTop | 0xFF000000, true);
        topValue.setGravity(Gravity.CENTER);
        dial.addView(topValue);
        View rule = new View(this);
        rule.setBackgroundColor(0xFF2C332F);
        LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(dp(160), dp(2));
        ruleParams.topMargin = dp(4);
        ruleParams.bottomMargin = dp(4);
        dial.addView(rule, ruleParams);
        bottomValue = text("–", 64, colorBottom | 0xFF000000, true);
        bottomValue.setGravity(Gravity.CENTER);
        dial.addView(bottomValue);
        TextView bottomLabel = text("unten", 14, MUTED, false);
        bottomLabel.setGravity(Gravity.CENTER);
        dial.addView(bottomLabel);
        page.addView(dial, block(18));

        lastSyncView = text("", 15, MUTED, false);
        lastSyncView.setGravity(Gravity.CENTER);
        page.addView(lastSyncView, block(10));

        // Farben
        page.addView(text("FARBE OBEN", 13, MUTED, true), block(26));
        topSwatches = new LinearLayout(this);
        topSwatches.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(topSwatches, block(8));

        page.addView(text("FARBE UNTEN", 13, MUTED, true), block(18));
        bottomSwatches = new LinearLayout(this);
        bottomSwatches.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(bottomSwatches, block(8));

        // Verlauf
        page.addView(text("VERLAUF", 13, MUTED, true), block(26));
        historyBox = new LinearLayout(this);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        page.addView(historyBox, block(6));

        setContentView(scroll);
    }

    private void fillSwatches(LinearLayout row, boolean top) {
        row.removeAllViews();
        int selected = (top ? colorTop : colorBottom) & 0xFFFFFF;
        for (int i = 0; i < PALETTE.length; i++) {
            final int color = PALETTE[i];
            View swatch = new View(this);
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(color | 0xFF000000);
            if (color == selected) {
                shape.setStroke(dp(3), TEXT);
            } else {
                shape.setStroke(dp(1), 0xFF3A433E);
            }
            swatch.setBackground(shape);
            swatch.setContentDescription((top ? "Farbe oben " : "Farbe unten ") + (i + 1)
                    + (color == selected ? ", ausgewählt" : ""));
            swatch.setOnClickListener(v -> chooseColor(top, color));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(36), 1f);
            params.setMarginEnd(i == PALETTE.length - 1 ? 0 : dp(6));
            row.addView(swatch, params);
        }
    }

    private void render() {
        JSONArray items = history();
        SimpleDateFormat format = new SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.GERMANY);

        topValue.setTextColor(colorTop | 0xFF000000);
        bottomValue.setTextColor(colorBottom | 0xFF000000);
        fillSwatches(topSwatches, true);
        fillSwatches(bottomSwatches, false);

        historyBox.removeAllViews();
        if (items.length() == 0) {
            topValue.setText("–");
            bottomValue.setText("–");
            lastSyncView.setText("Noch kein Stand übernommen");
            historyBox.addView(text("Hier erscheint jeder Sync mit Datum und der Veränderung zum vorigen.", 15, MUTED, false));
            return;
        }

        try {
            JSONObject latest = items.getJSONObject(0);
            topValue.setText(String.valueOf(latest.getLong("top")));
            bottomValue.setText(String.valueOf(latest.getLong("bottom")));
            lastSyncView.setText("Stand vom " + format.format(new Date(latest.getLong("t"))) + " Uhr");

            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                JSONObject older = i + 1 < items.length() ? items.getJSONObject(i + 1) : null;

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(10), dp(12), dp(10));
                GradientDrawable rowBg = new GradientDrawable();
                rowBg.setColor(SURFACE);
                rowBg.setCornerRadius(dp(10));
                row.setBackground(rowBg);

                TextView when = text(format.format(new Date(item.getLong("t"))), 15, TEXT, false);
                row.addView(when, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                row.addView(valueCell(item.getLong("top"), older == null ? null : item.getLong("top") - older.getLong("top"), colorTop));
                row.addView(valueCell(item.getLong("bottom"), older == null ? null : item.getLong("bottom") - older.getLong("bottom"), colorBottom));

                historyBox.addView(row, block(i == 0 ? 0 : 6));
            }
        } catch (Exception e) {
            historyBox.addView(text("Der Verlauf ließ sich nicht lesen.", 15, MUTED, false));
        }
    }

    private View valueCell(long value, Long delta, int color) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.END);
        TextView number = text(String.valueOf(value), 22, color | 0xFF000000, true);
        number.setGravity(Gravity.END);
        cell.addView(number);
        if (delta != null) {
            String sign = delta > 0 ? "+" + delta : delta < 0 ? "−" + Math.abs(delta) : "±0";
            TextView change = text(sign, 12, MUTED, false);
            change.setGravity(Gravity.END);
            cell.addView(change);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT);
        cell.setLayoutParams(params);
        return cell;
    }

    private void setStatus(String message, boolean problem) {
        statusView.setText(message);
        statusView.setTextColor(problem ? 0xFFFF8A80 : MUTED);
    }
}
