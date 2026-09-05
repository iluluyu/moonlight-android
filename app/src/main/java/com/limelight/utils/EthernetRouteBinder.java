package com.limelight.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import com.limelight.LimeLog;

/**
 * Binds the app process to a wired (Ethernet) network for the duration of a streaming
 * session so stream traffic uses e.g. a campus intranet USB Ethernet link while the
 * rest of the system keeps using the default network (usually Wi-Fi).
 *
 * The binding affects every socket created afterwards - including the native sockets
 * created by moonlight-common-c - because bionic's socket()/connect() are shimmed by
 * libnetd_client to apply the process default network.
 *
 * Concurrent Game instances (e.g. an external display session) share the binding via
 * a reference count; the process is unbound again once the last holder releases it.
 */
public final class EthernetRouteBinder {
    private static final Object bindCountLock = new Object();
    private static int bindCount;

    private EthernetRouteBinder() {
    }

    /**
     * Returns the first network whose transport is Ethernet, or null if none is
     * present. The synchronous lookup is intentional: at stream start we only need
     * to know whether an Ethernet network is connected right now.
     */
    public static Network findEthernetNetwork(ConnectivityManager connMgr) {
        for (Network net : connMgr.getAllNetworks()) {
            NetworkCapabilities netCaps = connMgr.getNetworkCapabilities(net);
            if (netCaps != null && netCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return net;
            }
        }
        return null;
    }

    /**
     * Binds this process to the Ethernet network if one is connected. Returns true
     * when the binding was acquired (and release() must be called later), false when
     * no Ethernet network was present or the binding failed (fall back to default).
     */
    public static boolean bind(Context context) {
        ConnectivityManager connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connMgr == null) {
            LimeLog.info("Ethernet binding: no ConnectivityManager available");
            return false;
        }

        Network ethNet = findEthernetNetwork(connMgr);
        if (ethNet == null) {
            LimeLog.info("Ethernet binding: no Ethernet network present, using default network");
            return false;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!connMgr.bindProcessToNetwork(ethNet)) {
                    LimeLog.warning("Ethernet binding: bindProcessToNetwork() failed");
                    return false;
                }
            }
            else if (!ConnectivityManager.setProcessDefaultNetwork(ethNet)) {
                LimeLog.warning("Ethernet binding: setProcessDefaultNetwork() failed");
                return false;
            }
        } catch (Exception e) {
            // Defensive: some OEM builds may refuse binding to networks they consider unusable
            LimeLog.warning("Ethernet binding: bind threw " + e);
            return false;
        }

        synchronized (bindCountLock) {
            bindCount++;
        }

        LimeLog.info("Ethernet binding: process bound to " + ethNet);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            LimeLog.info("Ethernet binding: getBoundNetworkForProcess() = " + connMgr.getBoundNetworkForProcess());
        }
        return true;
    }

    /**
     * Releases a binding previously acquired via bind(). The process is only unbound
     * once every holder has released it.
     */
    public static void release(Context context) {
        synchronized (bindCountLock) {
            if (bindCount == 0) {
                return;
            }
            if (--bindCount > 0) {
                LimeLog.info("Ethernet binding: still held by another streaming session");
                return;
            }
        }

        ConnectivityManager connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connMgr == null) {
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                connMgr.bindProcessToNetwork(null);
            }
            else {
                ConnectivityManager.setProcessDefaultNetwork(null);
            }
            LimeLog.info("Ethernet binding: process unbound, default network restored");
        } catch (Exception e) {
            LimeLog.warning("Ethernet binding: unbind threw " + e);
        }
    }
}
