package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.junit.Test;

public class EthernetRouteBinderTest {
    private static NetworkCapabilities capsFor(boolean ethernet) {
        NetworkCapabilities netCaps = mock(NetworkCapabilities.class);
        when(netCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)).thenReturn(ethernet);
        return netCaps;
    }

    @Test
    public void picksEthernetAmongOtherNetworks() {
        Network wifi = mock(Network.class);
        Network cellular = mock(Network.class);
        Network ethernet = mock(Network.class);

        // Create all stubs before the enclosing when() calls to avoid nested stubbing
        NetworkCapabilities wifiCaps = capsFor(false);
        NetworkCapabilities cellularCaps = capsFor(false);
        NetworkCapabilities ethernetCaps = capsFor(true);

        ConnectivityManager connMgr = mock(ConnectivityManager.class);
        when(connMgr.getAllNetworks()).thenReturn(new Network[]{wifi, cellular, ethernet});
        when(connMgr.getNetworkCapabilities(wifi)).thenReturn(wifiCaps);
        when(connMgr.getNetworkCapabilities(cellular)).thenReturn(cellularCaps);
        when(connMgr.getNetworkCapabilities(ethernet)).thenReturn(ethernetCaps);

        assertEquals(ethernet, EthernetRouteBinder.findEthernetNetwork(connMgr));
    }

    @Test
    public void returnsNullWhenNoEthernetPresent() {
        Network wifi = mock(Network.class);

        NetworkCapabilities wifiCaps = capsFor(false);

        ConnectivityManager connMgr = mock(ConnectivityManager.class);
        when(connMgr.getAllNetworks()).thenReturn(new Network[]{wifi});
        when(connMgr.getNetworkCapabilities(wifi)).thenReturn(wifiCaps);

        assertNull(EthernetRouteBinder.findEthernetNetwork(connMgr));
    }

    @Test
    public void skipsNetworksWithoutCapabilities() {
        Network vpn = mock(Network.class);
        Network ethernet = mock(Network.class);

        NetworkCapabilities ethernetCaps = capsFor(true);

        // The first network reports null capabilities and must not break the scan
        ConnectivityManager connMgr = mock(ConnectivityManager.class);
        when(connMgr.getAllNetworks()).thenReturn(new Network[]{vpn, ethernet});
        when(connMgr.getNetworkCapabilities(vpn)).thenReturn(null);
        when(connMgr.getNetworkCapabilities(ethernet)).thenReturn(ethernetCaps);

        assertEquals(ethernet, EthernetRouteBinder.findEthernetNetwork(connMgr));
    }

    @Test
    public void returnsNullWhenNoNetworksExist() {
        ConnectivityManager connMgr = mock(ConnectivityManager.class);
        when(connMgr.getAllNetworks()).thenReturn(new Network[0]);

        assertNull(EthernetRouteBinder.findEthernetNetwork(connMgr));
    }
}
