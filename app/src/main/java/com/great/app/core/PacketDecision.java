package com.great.app.core;

/** The complete decision vocabulary for the packet engine. Build 1 uses PASS only. */
public enum PacketDecision {
    PASS,
    DROP,
    HOLD,
    DELAY,
    REPLAY
}
