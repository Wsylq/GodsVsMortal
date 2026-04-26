package com.example.godsvsmortals.enums;

/**
 * Represents the types of blessings a god can bestow on followers.
 */
public enum BlessingType {
    GOLDEN_APPLE(50),
    SUMMON_WOLF(75);

    private final int faithCost;

    BlessingType(int faithCost) {
        this.faithCost = faithCost;
    }

    public int getFaithCost() {
        return faithCost;
    }
}
