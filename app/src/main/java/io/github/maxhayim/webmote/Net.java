package io.github.maxhayim.webmote;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;

/**
 * This device's address for phones to open. At home that's the Wi-Fi's. In a car the active network may be mobile
 * data (which a phone can't reach) or none at all while the head unit runs its own hotspot, so the hotspot's or the
 * Wi-Fi's address comes first, from any interface.
 */
final class Net {
    private Net() {
    }

    static String address(Context c) {
        String active = null;
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        Network n = cm == null ? null : cm.getActiveNetwork();
        LinkProperties p = n == null ? null : cm.getLinkProperties(n);
        if (p != null) {
            for (LinkAddress a : p.getLinkAddresses()) {
                if (a.getAddress() instanceof Inet4Address && a.getAddress().isSiteLocalAddress()) {
                    active = a.getAddress().getHostAddress();
                    break;
                }
            }
        }
        if (active != null) return active;
        String wifi = null, other = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    if (isWifi(ni.getName())) {
                        if (wifi == null) wifi = a.getHostAddress();
                    } else if (other == null) {
                        other = a.getHostAddress();
                    }
                }
            }
        } catch (SocketException | NullPointerException e) {
            // No interfaces to list.
        }
        return wifi != null ? wifi : other;
    }

    /** Wi-Fi and hotspot interface names: wlan0, wlan1, ap0, swlan0, softap0. */
    static boolean isWifi(String name) {
        return name != null && (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap"));
    }
}
