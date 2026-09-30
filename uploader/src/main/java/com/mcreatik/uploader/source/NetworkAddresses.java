package com.mcreatik.uploader.source;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** This laptop's local-network IPv4 addresses: what goes into the camera's "FTP server address". */
public final class NetworkAddresses {

    private NetworkAddresses() {
    }

    /** Private-network addresses first; if there are none (unusual networks), any other usable IPv4 address. */
    public static List<String> lan() {
        List<String> addresses = new ArrayList<>();
        List<String> others = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual() || isLikelyVirtual(nic.getName())) {
                    continue;
                }
                for (var address : Collections.list(nic.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLinkLocalAddress()) {
                        continue;
                    }
                    (address.isSiteLocalAddress() ? addresses : others).add(address.getHostAddress());
                }
            }
        } catch (SocketException ignored) {
            // no network information available
        }
        return addresses.isEmpty() ? others : addresses;
    }

    private static boolean isLikelyVirtual(String name) {
        String n = name.toLowerCase();
        return n.startsWith("docker") || n.startsWith("veth") || n.startsWith("vmnet") || n.startsWith("vbox")
                || n.startsWith("br-") || n.startsWith("utun") || n.startsWith("tailscale");
    }
}
