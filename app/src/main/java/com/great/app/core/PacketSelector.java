package com.great.app.core;

@FunctionalInterface
public interface PacketSelector {
    boolean matches(PacketContext context);

    static PacketSelector none() { return context -> false; }
    static PacketSelector all() { return context -> true; }
}
