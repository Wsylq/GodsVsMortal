package com.example.godsvsmortals.util;

/**
 * Abstraction over system time to allow deterministic testing of time-dependent logic.
 */
public interface Clock {

    /** Returns the current time in milliseconds (analogous to System.currentTimeMillis()). */
    long currentTimeMillis();

    /** Default implementation backed by the real system clock. */
    static Clock system() {
        return System::currentTimeMillis;
    }
}
