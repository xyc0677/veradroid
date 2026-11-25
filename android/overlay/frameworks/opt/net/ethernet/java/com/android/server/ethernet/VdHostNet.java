/*
 * VeraDroid: "hostnet" mode for Kindles whose kernel has no network namespaces (3.0.35: PW2 / PW3 / Oasis 1/2 /
 * Voyage / basic 2014-2016). Android then shares the Kindle's own network stack, so it must not run DHCP or change
 * the interface (that would take the Kindle's Wi-Fi address away). The host writes what the Kindle already has to
 * /dev/kfb/hostnet before Android starts:
 *
 *   iface=wlan0
 *   addr=192.0.2.23/24
 *   gw=192.0.2.1
 *   dns=192.0.2.1
 *   dns=223.5.5.5
 *
 * and passes androidboot.vdhostnet=1 (ro.boot.vdhostnet). EthernetNetworkFactory then tracks only that interface
 * and registers a connected network with exactly these link properties. netd's matching patches turn every
 * interface / route / iptables change into a no-op in this mode (small-patches.py).
 * On every other Kindle the property is absent and nothing here is used.
 */
package com.android.server.ethernet;

import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkUtils;
import android.net.RouteInfo;
import android.os.SystemProperties;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;

final class VdHostNet {
    private static final String TAG = "VdHostNet";
    private static final String FILE = "/dev/kfb/hostnet";

    private VdHostNet() {}

    static boolean enabled() {
        return "1".equals(SystemProperties.get("ro.boot.vdhostnet", "0"));
    }

    /** The Kindle interface to track, or null when hostnet mode is off or the host wrote nothing usable. */
    static String iface() {
        if (!enabled()) return null;
        String[] v = read();
        return v == null ? null : v[0];
    }

    /**
     * Link properties for {@code iface} as the Kindle has them now, or null when the Kindle has no IPv4 address on it
     * (yet). Address and gateway are read from the kernel (the interface itself and /proc/net/route) so they are right
     * even when the host wrote /dev/kfb/hostnet before the Kindle's Wi-Fi was up (first real PW4: the file had only
     * iface + dns, this returned null and the stock code ran DHCP on the Kindle's own wlan0). The file's addr / gw are
     * the fallback, DNS always comes from the file.
     */
    static LinkProperties linkProperties(String iface) {
        return linkProperties(iface, false);
    }

    static LinkProperties linkPropertiesQuiet(String iface) {
        return linkProperties(iface, true);
    }

    private static LinkProperties linkProperties(String iface, boolean quiet) {
        if (!enabled() || iface == null) return null;
        String[] v = read();
        if (v == null || !iface.equals(v[0])) return null;
        String addr = kernelAddr(iface), gw = kernelGateway(iface);
        if (addr == null) addr = v[1];
        if (gw == null) gw = v[2];
        if (addr == null) return null;
        try {
            LinkProperties lp = new LinkProperties();
            lp.setInterfaceName(iface);
            lp.addLinkAddress(new LinkAddress(addr));
            if (gw != null) {
                lp.addRoute(new RouteInfo((IpPrefix) null, NetworkUtils.numericToInetAddress(gw), iface));
            }
            if (v[3] != null) {
                for (String d : v[3].split(",")) {
                    if (!d.isEmpty()) lp.addDnsServer(NetworkUtils.numericToInetAddress(d));
                }
            }
            if (!quiet) Log.i(TAG, "using the Kindle's network as-is: " + lp);
            return lp;
        } catch (IllegalArgumentException e) {
            if (!quiet) Log.e(TAG, "bad " + FILE + ": " + e);
            return null;
        }
    }

    /**
     * hostnet: wait until the Kindle itself has an address on {@code iface} (its own Wi-Fi / DHCP brings it up), then
     * return the link properties. Never null unless the provisioning thread is interrupted (link went down / stopped):
     * in hostnet mode Android must never run DHCP or touch the Kindle's interface.
     */
    static LinkProperties waitForKindle(String iface) {
        int n = 0;
        while (!Thread.currentThread().isInterrupted()) {
            LinkProperties lp = linkProperties(iface);
            if (lp != null) return lp;
            if (n++ % 20 == 0) Log.i(TAG, "waiting for the Kindle to configure " + iface + " (no DHCP from Android)");
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                return null;
            }
        }
        return null;
    }

    /** First IPv4 address/prefix on the interface, or null. */
    private static String kernelAddr(String iface) {
        try {
            java.net.NetworkInterface ni = java.net.NetworkInterface.getByName(iface);
            if (ni == null) return null;
            for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                if (ia.getAddress() instanceof java.net.Inet4Address) {
                    return ia.getAddress().getHostAddress() + "/" + ia.getNetworkPrefixLength();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "kernel address of " + iface + ": " + e);
        }
        return null;
    }

    /** Default gateway on the interface from /proc/net/route (hex, little endian), or null. */
    private static String kernelGateway(String iface) {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/net/route"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length < 3 || !f[0].equals(iface) || !f[1].equals("00000000")) continue;
                long g = Long.parseLong(f[2], 16);
                if (g == 0) continue;
                return (g & 0xff) + "." + ((g >> 8) & 0xff) + "." + ((g >> 16) & 0xff) + "." + ((g >> 24) & 0xff);
            }
        } catch (IOException | NumberFormatException e) {
            Log.w(TAG, "kernel gateway of " + iface + ": " + e);
        }
        return null;
    }

    /** {iface, addr/prefix, gateway, comma-separated dns} */
    private static String[] read() {
        String iface = null, addr = null, gw = null;
        StringBuilder dns = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(FILE))) {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim(), val = line.substring(eq + 1).trim();
                if (val.isEmpty()) continue;
                switch (k) {
                    case "iface": iface = val; break;
                    case "addr": addr = val; break;
                    case "gw": gw = val; break;
                    case "dns": if (dns.length() > 0) dns.append(','); dns.append(val); break;
                    default: break;
                }
            }
        } catch (IOException e) {
            return null;
        }
        if (iface == null) return null;
        return new String[] { iface, addr, gw, dns.length() > 0 ? dns.toString() : null };
    }
}
