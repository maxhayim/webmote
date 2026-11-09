package io.github.maxhayim.webmote;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The remote over Bluetooth Low Energy, for a phone that isn't on the same Wi-Fi (a car with no hotspot, a TV on
 * another network). The phone opens Webmote's page on GitHub Pages in Chrome, which connects here with Web Bluetooth
 * (the TV's own page is plain HTTP, where browsers allow no Bluetooth). The API is the same as over HTTP, with the same
 * pairing and keys, minus app icons.
 *
 * <p>Messages are JSON, cut into pieces that fit a Bluetooth packet: each write or notification starts with a byte that
 * is 1 on a message's last piece, 0 before. The phone writes requests to {@link #RX} and gets answers as notifications
 * on {@link #TX}: {@code {"i":1,"m":"GET","p":"/api/status","b":{…}}} gets {@code {"i":1,"s":200,"b":{…}}}. Its first
 * request, {@code "p":"/auth"} with {@code "k"}: its key, signs the connection in; until then, only pairing answers.
 */
final class Ble {
    private static final String TAG = "Webmote";
    static final UUID SERVICE = UUID.fromString("4e7a0001-8f1d-4b6e-a3c2-5765626d6f74");
    static final UUID RX = UUID.fromString("4e7a0002-8f1d-4b6e-a3c2-5765626d6f74");
    static final UUID TX = UUID.fromString("4e7a0003-8f1d-4b6e-a3c2-5765626d6f74");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    /** Longest message taken from a phone; requests are small. */
    private static final int MAX_REQUEST = 16 * 1024;

    private final RemoteService service;
    private final Server server;
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final Map<String, Link> links = new HashMap<>();
    private BluetoothGattServer gatt;
    private BluetoothGattCharacteristic tx;
    private BluetoothLeAdvertiser advertiser;
    private boolean receiving;
    private volatile String state = "off";

    /** One connected phone. */
    private static final class Link {
        final BluetoothDevice device;
        final ByteArrayOutputStream in = new ByteArrayOutputStream();
        final ArrayDeque<byte[]> out = new ArrayDeque<>();
        int mtu = 23;
        boolean paired, sending, notify;

        Link(BluetoothDevice device) {
            this.device = device;
        }
    }

    Ble(RemoteService service, Server server) {
        this.service = service;
        this.server = server;
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean("bluetooth", false);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("bluetooth", on).apply();
        RemoteService svc = RemoteService.running;
        if (svc != null) svc.bluetoothChanged();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("webmote", Context.MODE_PRIVATE);
    }

    /** The permissions Bluetooth needs that are asked for on screen (Android 12 and newer); none before. */
    static String[] permissions() {
        if (Build.VERSION.SDK_INT < 31) return new String[0];
        return new String[] {Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT};
    }

    static boolean permitted(Context c) {
        for (String p : permissions()) {
            if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    /**
     * Where it stands, for Webmote's screen and the status: "off" (switched off), "on" (advertising), "no permission",
     * "bluetooth off" (the device's Bluetooth is off), "unsupported" (no Bluetooth an app can advertise on: many car
     * head units keep theirs for calls and music), or "failed".
     */
    String state() {
        return state;
    }

    /** What the device's Bluetooth allows, before Webmote's setting: for the screen to say so ahead of switching on. */
    static String capability(Context c) {
        BluetoothManager m = c.getSystemService(BluetoothManager.class);
        BluetoothAdapter a = m == null ? null : m.getAdapter();
        if (a == null || !c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return "unsupported";
        if (!permitted(c)) return "no permission";
        if (!a.isEnabled()) return "bluetooth off";
        return a.isMultipleAdvertisementSupported() ? "supported" : "unsupported";
    }

    private final BroadcastReceiver adapterState = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int s = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (s == BluetoothAdapter.STATE_ON || s == BluetoothAdapter.STATE_OFF) {
                stop(false);
                start();
            }
        }
    };

    /** Starts advertising when switched on and the device allows it; otherwise notes why not. */
    @SuppressLint("MissingPermission") // permitted() checks them
    synchronized void start() {
        if (!receiving) {
            service.registerReceiver(adapterState, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
            receiving = true;
        }
        if (!enabled(service)) {
            state = "off";
            return;
        }
        String cap = capability(service);
        if (!cap.equals("supported")) {
            state = cap;
            return;
        }
        if (gatt != null) return;
        BluetoothManager m = service.getSystemService(BluetoothManager.class);
        BluetoothAdapter a = m.getAdapter();
        advertiser = a.getBluetoothLeAdvertiser();
        gatt = m.openGattServer(service, callback);
        if (advertiser == null || gatt == null) {
            state = "unsupported";
            stop(false);
            return;
        }
        BluetoothGattService s = new BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic rx = new BluetoothGattCharacteristic(RX,
                BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE);
        tx = new BluetoothGattCharacteristic(TX,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ);
        tx.addDescriptor(new BluetoothGattDescriptor(CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        s.addCharacteristic(rx);
        s.addCharacteristic(tx);
        gatt.addService(s);
        advertise(true);
    }

    @SuppressLint("MissingPermission")
    private void advertise(boolean withName) {
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .setTimeout(0)
                .build();
        AdvertiseData data = new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(SERVICE)).build();
        // The device's Bluetooth name, for the phone's list; a long name doesn't fit, and then it goes without.
        AdvertiseData scan = new AdvertiseData.Builder().setIncludeDeviceName(withName).build();
        advertiser.startAdvertising(settings, data, scan, withName ? named : unnamed);
    }

    private final AdvertiseCallback named = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings s) {
            state = "on";
        }

        @Override
        public void onStartFailure(int error) {
            if (error == ADVERTISE_FAILED_DATA_TOO_LARGE && advertiser != null) {
                advertise(false);
            } else {
                Log.w(TAG, "Bluetooth advertising failed: " + error);
                state = error == ADVERTISE_FAILED_FEATURE_UNSUPPORTED ? "unsupported" : "failed";
            }
        }
    };

    private final AdvertiseCallback unnamed = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings s) {
            state = "on";
        }

        @Override
        public void onStartFailure(int error) {
            Log.w(TAG, "Bluetooth advertising failed: " + error);
            state = error == ADVERTISE_FAILED_FEATURE_UNSUPPORTED ? "unsupported" : "failed";
        }
    };

    /** Stops advertising and drops every phone; {@code all}: for good (the service is going). */
    @SuppressLint("MissingPermission")
    synchronized void stop(boolean all) {
        try {
            if (advertiser != null && permitted(service)) {
                advertiser.stopAdvertising(named);
                advertiser.stopAdvertising(unnamed);
            }
        } catch (RuntimeException ignored) {
            // Bluetooth already off.
        }
        advertiser = null;
        if (gatt != null) {
            try {
                gatt.close();
            } catch (RuntimeException ignored) {
                // Same.
            }
            gatt = null;
        }
        synchronized (links) {
            links.clear();
        }
        if (!state.equals("unsupported") && !state.equals("no permission")) state = "off";
        if (all) {
            if (receiving) {
                service.unregisterReceiver(adapterState);
                receiving = false;
            }
            work.shutdownNow();
        }
    }

    private Link link(BluetoothDevice d) {
        synchronized (links) {
            Link l = links.get(d.getAddress());
            if (l == null) {
                l = new Link(d);
                links.put(d.getAddress(), l);
            }
            return l;
        }
    }

    private final BluetoothGattServerCallback callback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice d, int status, int newState) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                synchronized (links) {
                    links.remove(d.getAddress());
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothDevice d, int mtu) {
            link(d).mtu = mtu;
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onCharacteristicWriteRequest(BluetoothDevice d, int requestId, BluetoothGattCharacteristic c,
                                                 boolean prepared, boolean responseNeeded, int offset, byte[] value) {
            boolean ok = RX.equals(c.getUuid()) && !prepared && value != null && value.length > 0;
            BluetoothGattServer g = gatt;
            if (responseNeeded && g != null) {
                g.sendResponse(d, requestId, ok ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_FAILURE, 0, null);
            }
            if (!ok) return;
            Link l = link(d);
            byte[] message = null;
            synchronized (l) {
                if (l.in.size() + value.length > MAX_REQUEST) {
                    l.in.reset();
                    return;
                }
                l.in.write(value, 1, value.length - 1);
                if (value[0] == 1) {
                    message = l.in.toByteArray();
                    l.in.reset();
                }
            }
            if (message != null) {
                byte[] m = message;
                work.execute(() -> answer(l, new String(m, StandardCharsets.UTF_8)));
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onDescriptorWriteRequest(BluetoothDevice d, int requestId, BluetoothGattDescriptor desc,
                                             boolean prepared, boolean responseNeeded, int offset, byte[] value) {
            if (CCCD.equals(desc.getUuid()) && value != null && value.length > 0) link(d).notify = value[0] != 0;
            BluetoothGattServer g = gatt;
            if (responseNeeded && g != null) g.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onDescriptorReadRequest(BluetoothDevice d, int requestId, int offset, BluetoothGattDescriptor desc) {
            BluetoothGattServer g = gatt;
            if (g == null) return;
            byte[] v = link(d).notify ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
            g.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, v);
        }

        @Override
        public void onNotificationSent(BluetoothDevice d, int status) {
            Link l = link(d);
            synchronized (l) {
                l.sending = false;
            }
            sendNext(l);
        }
    };

    /** Runs one request from a phone and sends the answer back. */
    private void answer(Link l, String text) {
        int id = 0;
        int status;
        String body;
        try {
            JSONObject req = new JSONObject(text);
            id = req.optInt("i");
            String path = req.optString("p");
            if (path.equals("/auth")) {
                l.paired = server.paired(req.optString("k", null));
                status = l.paired ? 200 : 401;
                body = l.paired ? "{\"ok\":true}" : "{\"error\":\"Pair this device again.\"}";
            } else if (path.equals("/api/pair/show")) {
                service.showPin();
                status = 200;
                body = "{\"ok\":true}";
            } else {
                JSONObject b = req.optJSONObject("b");
                Server.Reply r = server.call(req.optString("m", "GET"), path, b == null ? null : b.toString(), l.paired);
                if (path.equals("/api/pair") && r.status == 200) l.paired = true;
                status = r.status;
                body = r.json;
            }
        } catch (JSONException | RuntimeException e) {
            status = 400;
            body = "{\"error\":\"Bad request.\"}";
        }
        byte[] msg = ("{\"i\":" + id + ",\"s\":" + status + ",\"b\":" + body + "}").getBytes(StandardCharsets.UTF_8);
        synchronized (l) {
            l.out.addAll(pieces(msg, l.mtu));
        }
        sendNext(l);
    }

    /** {@code msg} in notifications that fit the link's MTU: a byte, 1 on the last piece, then up to MTU − 4 bytes. */
    static List<byte[]> pieces(byte[] msg, int mtu) {
        List<byte[]> out = new ArrayList<>();
        int size = Math.max(23, mtu) - 4, at = 0;
        do {
            int n = Math.min(size, msg.length - at);
            byte[] piece = new byte[n + 1];
            piece[0] = (byte) (at + n >= msg.length ? 1 : 0);
            System.arraycopy(msg, at, piece, 1, n);
            out.add(piece);
            at += n;
        } while (at < msg.length);
        return out;
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation") // the old notify call, for Android 12 and older
    private void sendNext(Link l) {
        byte[] piece;
        synchronized (l) {
            if (l.sending || l.out.isEmpty()) return;
            piece = l.out.poll();
            l.sending = true;
        }
        BluetoothGattServer g = gatt;
        BluetoothGattCharacteristic c = tx;
        if (g == null || c == null) return;
        boolean sent;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                sent = g.notifyCharacteristicChanged(l.device, c, false, piece) == BluetoothStatusCodes.SUCCESS;
            } else {
                c.setValue(piece);
                sent = g.notifyCharacteristicChanged(l.device, c, false);
            }
        } catch (RuntimeException e) {
            sent = false;
        }
        if (!sent) {
            synchronized (l) {
                l.sending = false;
                l.out.clear();
            }
        }
    }
}
