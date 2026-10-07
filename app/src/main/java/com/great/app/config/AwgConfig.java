package com.great.app.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Parsed, immutable .conf representation. Unknown AmneziaWG fields are preserved for the backend. */
public final class AwgConfig {
    private final Map<String, String> interfaceValues;
    private final Map<String, String> peerValues;

    AwgConfig(Map<String, String> interfaceValues, Map<String, String> peerValues) {
        this.interfaceValues = Collections.unmodifiableMap(new LinkedHashMap<>(interfaceValues));
        this.peerValues = Collections.unmodifiableMap(new LinkedHashMap<>(peerValues));
    }

    public Map<String, String> interfaceValues() { return interfaceValues; }
    public Map<String, String> peerValues() { return peerValues; }
    public String interfaceValue(String key) { return interfaceValues.get(key); }
    public String peerValue(String key) { return peerValues.get(key); }

    @Override public String toString() { return "AwgConfig[redacted]"; }
}
