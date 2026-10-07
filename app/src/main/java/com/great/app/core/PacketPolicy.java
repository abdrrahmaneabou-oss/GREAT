package com.great.app.core;

/** A policy decides what should happen; it does not perform I/O. */
@FunctionalInterface
public interface PacketPolicy {
    PacketDecision decide(PacketEnvelope packet, EngineSnapshot state);
}
