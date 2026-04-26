package com.example.godsvsmortals.model;

import org.bukkit.configuration.ConfigurationSection;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tracks a mortal's daily quest progress. Embedded in MortalData.
 */
public class QuestProgress {

    private int shrinesPrayed;
    private Set<String> prayedShrineIds;
    private boolean diamondSacrificed;
    private boolean nonBelieverConverted;
    private int dayNumber;

    public QuestProgress() {
        this.shrinesPrayed = 0;
        this.prayedShrineIds = new HashSet<>();
        this.diamondSacrificed = false;
        this.nonBelieverConverted = false;
        this.dayNumber = 0;
    }

    public QuestProgress(int shrinesPrayed, Set<String> prayedShrineIds,
                         boolean diamondSacrificed, boolean nonBelieverConverted, int dayNumber) {
        this.shrinesPrayed = shrinesPrayed;
        this.prayedShrineIds = new HashSet<>(prayedShrineIds);
        this.diamondSacrificed = diamondSacrificed;
        this.nonBelieverConverted = nonBelieverConverted;
        this.dayNumber = dayNumber;
    }

    // --- Serialization ---

    public void save(ConfigurationSection section) {
        section.set("shrinesPrayed", shrinesPrayed);
        section.set("prayedShrineIds", List.copyOf(prayedShrineIds));
        section.set("diamondSacrificed", diamondSacrificed);
        section.set("nonBelieverConverted", nonBelieverConverted);
        section.set("dayNumber", dayNumber);
    }

    public static QuestProgress load(ConfigurationSection section) {
        if (section == null) return new QuestProgress();
        int shrinesPrayed = section.getInt("shrinesPrayed", 0);
        List<?> rawIds = section.getList("prayedShrineIds");
        Set<String> prayedShrineIds = new HashSet<>();
        if (rawIds != null) {
            for (Object o : rawIds) {
                if (o instanceof String s) prayedShrineIds.add(s);
            }
        }
        boolean diamondSacrificed = section.getBoolean("diamondSacrificed", false);
        boolean nonBelieverConverted = section.getBoolean("nonBelieverConverted", false);
        int dayNumber = section.getInt("dayNumber", 0);
        return new QuestProgress(shrinesPrayed, prayedShrineIds, diamondSacrificed, nonBelieverConverted, dayNumber);
    }

    // --- Getters and Setters ---

    public int getShrinesPrayed() { return shrinesPrayed; }
    public void setShrinesPrayed(int shrinesPrayed) { this.shrinesPrayed = shrinesPrayed; }

    public Set<String> getPrayedShrineIds() { return prayedShrineIds; }
    public void setPrayedShrineIds(Set<String> prayedShrineIds) { this.prayedShrineIds = prayedShrineIds; }

    public boolean isDiamondSacrificed() { return diamondSacrificed; }
    public void setDiamondSacrificed(boolean diamondSacrificed) { this.diamondSacrificed = diamondSacrificed; }

    public boolean isNonBelieverConverted() { return nonBelieverConverted; }
    public void setNonBelieverConverted(boolean nonBelieverConverted) { this.nonBelieverConverted = nonBelieverConverted; }

    public int getDayNumber() { return dayNumber; }
    public void setDayNumber(int dayNumber) { this.dayNumber = dayNumber; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof QuestProgress other)) return false;
        return shrinesPrayed == other.shrinesPrayed
                && diamondSacrificed == other.diamondSacrificed
                && nonBelieverConverted == other.nonBelieverConverted
                && dayNumber == other.dayNumber
                && prayedShrineIds.equals(other.prayedShrineIds);
    }

    @Override
    public int hashCode() {
        int result = shrinesPrayed;
        result = 31 * result + prayedShrineIds.hashCode();
        result = 31 * result + (diamondSacrificed ? 1 : 0);
        result = 31 * result + (nonBelieverConverted ? 1 : 0);
        result = 31 * result + dayNumber;
        return result;
    }
}
