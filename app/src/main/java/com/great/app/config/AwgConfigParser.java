package com.great.app.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Clean-room parser: validates the minimum contract and preserves AWG extension fields verbatim. */
public final class AwgConfigParser {
    public static final int MAX_CONFIG_BYTES = 64 * 1024;

    public AwgConfig parse(byte[] raw) throws IOException {
        if (raw == null || raw.length == 0 || raw.length > MAX_CONFIG_BYTES) throw new IOException("Invalid configuration size");
        Map<String, String> iface = new LinkedHashMap<>();
        Map<String, String> peer = new LinkedHashMap<>();
        Map<String, String> current = null;
        boolean seenInterface = false, seenPeer = false;

        try (BufferedReader reader = new BufferedReader(new StringReader(new String(raw, StandardCharsets.UTF_8)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
                if ("[Interface]".equalsIgnoreCase(line)) {
                    if (seenInterface || seenPeer) throw new IOException("Duplicate or misplaced Interface section");
                    seenInterface = true; current = iface; continue;
                }
                if ("[Peer]".equalsIgnoreCase(line)) {
                    if (!seenInterface || seenPeer) throw new IOException("Exactly one Peer is required");
                    seenPeer = true; current = peer; continue;
                }
                if (current == null) throw new IOException("Setting outside a section");
                int equals = line.indexOf('=');
                if (equals <= 0) throw new IOException("Malformed configuration line");
                String key = line.substring(0, equals).trim();
                String value = line.substring(equals + 1).trim();
                if (!key.matches("[A-Za-z][A-Za-z0-9_]*") || value.isEmpty()) throw new IOException("Invalid setting");
                if (current.put(key, value) != null) throw new IOException("Duplicate setting: " + key);
            }
        }

        if (!seenInterface || !seenPeer) throw new IOException("Interface and Peer are required");
        require(iface, "PrivateKey"); require(iface, "Address");
        require(peer, "PublicKey"); require(peer, "Endpoint"); require(peer, "AllowedIPs");
        validateBase64Key(iface.get("PrivateKey")); validateBase64Key(peer.get("PublicKey"));
        if (peer.containsKey("PresharedKey")) validateBase64Key(peer.get("PresharedKey"));
        validateEndpoint(peer.get("Endpoint"));
        return new AwgConfig(iface, peer);
    }

    private static void require(Map<String, String> values, String key) throws IOException {
        if (!values.containsKey(key)) throw new IOException("Missing required setting: " + key);
    }

    private static void validateBase64Key(String value) throws IOException {
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length != 32) throw new IOException("Invalid key length");
            java.util.Arrays.fill(bytes, (byte) 0);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid key encoding");
        }
    }

    private static void validateEndpoint(String value) throws IOException {
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) throw new IOException("Invalid endpoint");
        try {
            int port = Integer.parseInt(value.substring(colon + 1));
            if (port < 1 || port > 65535) throw new IOException("Invalid endpoint port");
        } catch (NumberFormatException e) {
            throw new IOException("Invalid endpoint port");
        }
    }
}
