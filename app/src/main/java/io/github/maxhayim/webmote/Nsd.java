package io.github.maxhayim.webmote;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Announces the remote on the home network as {@code _webmote._tcp} (mDNS), so Home Assistant finds the TV by itself
 * and offers to add it. The record carries the TV's name, its id and kind, and no key: pairing still takes the PIN.
 * An announcement made while the TV slept with Wi-Fi off never goes out once Wi-Fi is back (seen on Android 14), so
 * it's made again each time the TV gets a network. Android also drops an announcement without a word when the name is
 * still taken, even by its own earlier announcement that Android failed to clear, so one that isn't confirmed soon is
 * made again, a few times, as "Name (2)" and so on. Home Assistant names the TV from its status, not from this.
 */
final class Nsd {
    static final String TYPE = "_webmote._tcp";
    private static final String TAG = "Webmote";
    private static final long CHECK_MS = 15_000;
    private static final int MAX_TRIES = 5;

    private final NsdManager manager;
    private final ConnectivityManager connectivity;
    private NsdServiceInfo info;
    private String name;
    private NsdManager.RegistrationListener listener;
    private ConnectivityManager.NetworkCallback networks;
    private boolean again; // announce again once the old announcement is gone
    private boolean stopped;
    private boolean confirmed;
    private int tries;
    private final Handler handler = new Handler(Looper.getMainLooper());

    Nsd(Context c) {
        manager = c.getSystemService(NsdManager.class);
        connectivity = c.getSystemService(ConnectivityManager.class);
    }

    synchronized void start(String name, int port, String id, String kind) {
        if (manager == null || info != null || port <= 0 || stopped) return;
        this.name = name;
        info = new NsdServiceInfo();
        info.setServiceName(name);
        info.setServiceType(TYPE);
        info.setPort(port);
        info.setAttribute("id", id);
        info.setAttribute("kind", kind);
        info.setAttribute("version", BuildConfig.VERSION_NAME);
        register();
        if (connectivity == null) return;
        networks = new ConnectivityManager.NetworkCallback() {
            private boolean first = true;

            @Override
            public void onAvailable(Network n) {
                // The first call is the network the TV already has, which the announcement above goes out on.
                if (first) {
                    first = false;
                } else {
                    synchronized (Nsd.this) {
                        tries = 0;
                    }
                    announceAgain();
                }
            }
        };
        try {
            connectivity.registerDefaultNetworkCallback(networks);
        } catch (RuntimeException e) {
            networks = null;
        }
    }

    private synchronized void register() {
        if (stopped || listener != null) return;
        NsdManager.RegistrationListener l = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo s) {
                Log.i(TAG, "announced as " + s.getServiceName());
                synchronized (Nsd.this) {
                    if (listener == this) {
                        confirmed = true;
                        tries = 0;
                    }
                }
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo s, int error) {
                Log.w(TAG, "can't announce: " + error);
                gone(this);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo s) {
                gone(this);
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo s, int error) {
                gone(this);
            }
        };
        listener = l;
        confirmed = false;
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, l);
        } catch (RuntimeException e) {
            Log.w(TAG, "can't announce", e);
            listener = null;
        }
        handler.postDelayed(() -> check(l), CHECK_MS << Math.min(tries, 4));
    }

    /** Some time after announcing: if it never went out (or failed), try again, up to a few times. */
    private synchronized void check(NsdManager.RegistrationListener l) {
        if (stopped || confirmed || (listener != null && listener != l) || tries >= MAX_TRIES) return;
        tries++;
        info.setServiceName(name + " (" + (tries + 1) + ")");
        Log.i(TAG, "announcing again as " + info.getServiceName());
        announceAgain();
    }

    /** A network came up: take the announcement down and, once it's down, make it again (so the name stays ours). */
    private synchronized void announceAgain() {
        if (stopped || info == null) return;
        if (listener == null) {
            register();
            return;
        }
        again = true;
        try {
            manager.unregisterService(listener);
        } catch (RuntimeException e) {
            listener = null;
            again = false;
            register();
        }
    }

    private synchronized void gone(NsdManager.RegistrationListener l) {
        if (listener != l) return;
        listener = null;
        if (again) {
            again = false;
            register();
        }
    }

    synchronized void stop() {
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        if (networks != null) {
            try {
                connectivity.unregisterNetworkCallback(networks);
            } catch (RuntimeException ignored) {
                // Never registered.
            }
            networks = null;
        }
        if (manager == null || listener == null) return;
        try {
            manager.unregisterService(listener);
        } catch (RuntimeException ignored) {
            // Never registered.
        }
        listener = null;
    }
}
