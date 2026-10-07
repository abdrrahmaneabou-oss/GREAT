package com.great.app.core;

@FunctionalInterface
public interface PacketClassifier {
    TrafficClass classify(PacketMetadata metadata);
}
