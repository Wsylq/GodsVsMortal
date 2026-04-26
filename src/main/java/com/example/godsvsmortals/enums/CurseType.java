package com.example.godsvsmortals.enums;

/**
 * Represents the types of curses a god can apply to enemy shrines.
 */
public enum CurseType {
    FIRE(100),
    SILVERFISH(80),
    DEBUFF(120);

    private final int faithCost;

    CurseType(int faithCost) {
        this.faithCost = faithCost;
    }

    public int getFaithCost() {
        return faithCost;
    }
}
