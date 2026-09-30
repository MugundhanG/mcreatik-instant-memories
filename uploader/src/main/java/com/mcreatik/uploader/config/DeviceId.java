package com.mcreatik.uploader.config;

import java.net.InetAddress;
import java.util.UUID;

/** A stable, non-personal identifier for this installation ("hostname-1a2b3c4d"). */
public final class DeviceId {

    private DeviceId() {
    }

    public static String generate() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "device";
        }
        host = host.replaceAll("[^A-Za-z0-9-]", "").toLowerCase();
        if (host.length() > 40) {
            host = host.substring(0, 40);
        }
        return (host.isEmpty() ? "device" : host) + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
