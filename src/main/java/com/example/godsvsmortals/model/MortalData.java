package com.example.godsvsmortals.model;

import com.example.godsvsmortals.enums.TitleType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Represents a mortal's persistent data. Persisted to plugins/GodsVsMortals/mortals/<uuid>.yml.
 */
public class MortalData {

    private UUID uuid;
    private String playerName;
    private UUID pledgedGodUUID;
    private int betrayalCount;
    private int betrayalsAgainstCount;
    private boolean isFallenGod;
    private List<TitleType> earnedTitles;
    private TitleType activeTitle;
    private boolean hasRebellionBanner;
    private QuestProgress dailyQuestProgress;
    private boolean loginRewardClaimedToday;
    private long lastPrayTimestamp;
    private BetrayelRitualState betrayalRitual; // null if no ritual in progress

    // -------------------------------------------------------------------------
    // Inner class: BetrayelRitualState
    // -------------------------------------------------------------------------

    /**
     * Tracks the state of an in-progress betrayal ritual.
     */
    public static class BetrayelRitualState {
        private final long startTimeMs;
        private final org.bukkit.Location shrineLocation;

        public BetrayelRitualState(long startTimeMs, org.bukkit.Location shrineLocation) {
            this.startTimeMs = startTimeMs;
            this.shrineLocation = shrineLocation;
        }

        public long getStartTimeMs() { return startTimeMs; }
        public org.bukkit.Location getShrineLocation() { return shrineLocation; }
    }

    public MortalData() {
        this.earnedTitles = new ArrayList<>();
        this.dailyQuestProgress = new QuestProgress();
    }

    public MortalData(UUID uuid, String playerName) {
        this.uuid = uuid;
        this.playerName = playerName;
        this.pledgedGodUUID = null;
        this.betrayalCount = 0;
        this.betrayalsAgainstCount = 0;
        this.isFallenGod = false;
        this.earnedTitles = new ArrayList<>();
        this.activeTitle = null;
        this.hasRebellionBanner = false;
        this.dailyQuestProgress = new QuestProgress();
        this.loginRewardClaimedToday = false;
        this.lastPrayTimestamp = 0L;
        this.betrayalRitual = null;
    }

    // --- Serialization ---

    public void toYaml(org.bukkit.configuration.ConfigurationSection section) {
        section.set("uuid", uuid != null ? uuid.toString() : null);
        section.set("playerName", playerName);
        section.set("pledgedGodUUID", pledgedGodUUID != null ? pledgedGodUUID.toString() : null);
        section.set("betrayalCount", betrayalCount);
        section.set("betrayalsAgainstCount", betrayalsAgainstCount);
        section.set("isFallenGod", isFallenGod);
        List<String> titleNames = new ArrayList<>();
        for (TitleType t : earnedTitles) titleNames.add(t.name());
        section.set("earnedTitles", titleNames);
        section.set("activeTitle", activeTitle != null ? activeTitle.name() : null);
        section.set("hasRebellionBanner", hasRebellionBanner);
        ConfigurationSection questSection = section.createSection("dailyQuestProgress");
        dailyQuestProgress.save(questSection);
        section.set("loginRewardClaimedToday", loginRewardClaimedToday);
        section.set("lastPrayTimestamp", lastPrayTimestamp);
        section.set("betrayalRitualStartMs", betrayalRitual != null ? betrayalRitual.getStartTimeMs() : 0L);
    }

    public static MortalData fromYaml(org.bukkit.configuration.ConfigurationSection section, Logger logger) {
        if (section == null) return null;
        MortalData data = new MortalData();
        String uuidStr = section.getString("uuid");
        if (uuidStr == null) {
            if (logger != null) logger.severe("Missing uuid in MortalData section");
            return null;
        }
        data.uuid = UUID.fromString(uuidStr);
        data.playerName = section.getString("playerName", "Unknown");
        String pledgedStr = section.getString("pledgedGodUUID");
        data.pledgedGodUUID = (pledgedStr != null && !pledgedStr.isEmpty()) ? UUID.fromString(pledgedStr) : null;
        data.betrayalCount = section.getInt("betrayalCount", 0);
        data.betrayalsAgainstCount = section.getInt("betrayalsAgainstCount", 0);
        data.isFallenGod = section.getBoolean("isFallenGod", false);
        List<String> rawTitles = section.getStringList("earnedTitles");
        data.earnedTitles = new ArrayList<>();
        for (String s : rawTitles) {
            try { data.earnedTitles.add(TitleType.valueOf(s)); }
            catch (IllegalArgumentException e) { if (logger != null) logger.severe("Invalid TitleType: " + s); }
        }
        String activeTitleStr = section.getString("activeTitle");
        data.activeTitle = (activeTitleStr != null && !activeTitleStr.isEmpty()) ? TitleType.valueOf(activeTitleStr) : null;
        data.hasRebellionBanner = section.getBoolean("hasRebellionBanner", false);
        ConfigurationSection questSection = section.getConfigurationSection("dailyQuestProgress");
        data.dailyQuestProgress = QuestProgress.load(questSection);
        data.loginRewardClaimedToday = section.getBoolean("loginRewardClaimedToday", false);
        data.lastPrayTimestamp = section.getLong("lastPrayTimestamp", 0L);
        // betrayalRitual: only startTimeMs is persisted; shrine location is re-fetched at runtime
        long ritualStartMs = section.getLong("betrayalRitualStartMs", 0L);
        data.betrayalRitual = (ritualStartMs > 0) ? new BetrayelRitualState(ritualStartMs, null) : null;
        return data;
    }

    public void save(File file) throws IOException {
        YamlConfiguration config = new YamlConfiguration();
        config.set("uuid", uuid.toString());
        config.set("playerName", playerName);
        config.set("pledgedGodUUID", pledgedGodUUID != null ? pledgedGodUUID.toString() : null);
        config.set("betrayalCount", betrayalCount);
        config.set("betrayalsAgainstCount", betrayalsAgainstCount);
        config.set("isFallenGod", isFallenGod);
        List<String> titleNames = new ArrayList<>();
        for (TitleType t : earnedTitles) titleNames.add(t.name());
        config.set("earnedTitles", titleNames);
        config.set("activeTitle", activeTitle != null ? activeTitle.name() : null);
        config.set("hasRebellionBanner", hasRebellionBanner);
        ConfigurationSection questSection = config.createSection("dailyQuestProgress");
        dailyQuestProgress.save(questSection);
        config.set("loginRewardClaimedToday", loginRewardClaimedToday);
        config.set("lastPrayTimestamp", lastPrayTimestamp);
        config.set("betrayalRitualStartMs", betrayalRitual != null ? betrayalRitual.getStartTimeMs() : 0L);
        config.save(file);
    }

    public static MortalData load(File file, Logger logger) {
        if (!file.exists()) return null;
        try {
            FileConfiguration config = YamlConfiguration.loadConfiguration(file);
            MortalData data = new MortalData();
            String uuidStr = config.getString("uuid");
            if (uuidStr == null) {
                logger.severe("Missing uuid in mortal file: " + file.getName());
                return null;
            }
            data.uuid = UUID.fromString(uuidStr);
            data.playerName = config.getString("playerName", "Unknown");
            String pledgedStr = config.getString("pledgedGodUUID");
            data.pledgedGodUUID = (pledgedStr != null && !pledgedStr.isEmpty()) ? UUID.fromString(pledgedStr) : null;
            data.betrayalCount = config.getInt("betrayalCount", 0);
            data.betrayalsAgainstCount = config.getInt("betrayalsAgainstCount", 0);
            data.isFallenGod = config.getBoolean("isFallenGod", false);
            List<String> rawTitles = config.getStringList("earnedTitles");
            data.earnedTitles = new ArrayList<>();
            for (String s : rawTitles) {
                try { data.earnedTitles.add(TitleType.valueOf(s)); }
                catch (IllegalArgumentException e) { logger.severe("Invalid TitleType: " + s); }
            }
            String activeTitleStr = config.getString("activeTitle");
            data.activeTitle = (activeTitleStr != null && !activeTitleStr.isEmpty()) ? TitleType.valueOf(activeTitleStr) : null;
            data.hasRebellionBanner = config.getBoolean("hasRebellionBanner", false);
            ConfigurationSection questSection = config.getConfigurationSection("dailyQuestProgress");
            data.dailyQuestProgress = QuestProgress.load(questSection);
            data.loginRewardClaimedToday = config.getBoolean("loginRewardClaimedToday", false);
            data.lastPrayTimestamp = config.getLong("lastPrayTimestamp", 0L);
            long ritualStartMs = config.getLong("betrayalRitualStartMs", 0L);
            data.betrayalRitual = (ritualStartMs > 0) ? new BetrayelRitualState(ritualStartMs, null) : null;
            return data;
        } catch (Exception e) {
            logger.severe("Failed to load mortal file " + file.getName() + ", initializing to defaults: " + e.getMessage());
            return null;
        }
    }

    // --- Getters and Setters ---

    public UUID getUuid() { return uuid; }
    public void setUuid(UUID uuid) { this.uuid = uuid; }

    public String getPlayerName() { return playerName; }
    public void setPlayerName(String playerName) { this.playerName = playerName; }

    public UUID getPledgedGodUUID() { return pledgedGodUUID; }
    public void setPledgedGodUUID(UUID pledgedGodUUID) { this.pledgedGodUUID = pledgedGodUUID; }

    public int getBetrayalCount() { return betrayalCount; }
    public void setBetrayalCount(int betrayalCount) { this.betrayalCount = betrayalCount; }

    public int getBetrayalsAgainstCount() { return betrayalsAgainstCount; }
    public void setBetrayalsAgainstCount(int betrayalsAgainstCount) { this.betrayalsAgainstCount = betrayalsAgainstCount; }

    public boolean isFallenGod() { return isFallenGod; }
    public void setFallenGod(boolean fallenGod) { isFallenGod = fallenGod; }

    public List<TitleType> getEarnedTitles() { return earnedTitles; }
    public void setEarnedTitles(List<TitleType> earnedTitles) { this.earnedTitles = new ArrayList<>(earnedTitles); }

    public TitleType getActiveTitle() { return activeTitle; }
    public void setActiveTitle(TitleType activeTitle) { this.activeTitle = activeTitle; }

    public boolean isHasRebellionBanner() { return hasRebellionBanner; }
    public void setHasRebellionBanner(boolean hasRebellionBanner) { this.hasRebellionBanner = hasRebellionBanner; }

    public QuestProgress getDailyQuestProgress() { return dailyQuestProgress; }
    public void setDailyQuestProgress(QuestProgress dailyQuestProgress) { this.dailyQuestProgress = dailyQuestProgress; }

    public boolean isLoginRewardClaimedToday() { return loginRewardClaimedToday; }
    public void setLoginRewardClaimedToday(boolean loginRewardClaimedToday) { this.loginRewardClaimedToday = loginRewardClaimedToday; }

    public long getLastPrayTimestamp() { return lastPrayTimestamp; }
    public void setLastPrayTimestamp(long lastPrayTimestamp) { this.lastPrayTimestamp = lastPrayTimestamp; }

    public BetrayelRitualState getBetrayelRitualState() { return betrayalRitual; }
    public void setBetrayelRitualState(BetrayelRitualState betrayalRitual) { this.betrayalRitual = betrayalRitual; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MortalData other)) return false;
        return betrayalCount == other.betrayalCount
                && betrayalsAgainstCount == other.betrayalsAgainstCount
                && isFallenGod == other.isFallenGod
                && hasRebellionBanner == other.hasRebellionBanner
                && loginRewardClaimedToday == other.loginRewardClaimedToday
                && lastPrayTimestamp == other.lastPrayTimestamp
                && Objects.equals(uuid, other.uuid)
                && Objects.equals(playerName, other.playerName)
                && Objects.equals(pledgedGodUUID, other.pledgedGodUUID)
                && earnedTitles.equals(other.earnedTitles)
                && activeTitle == other.activeTitle
                && Objects.equals(dailyQuestProgress, other.dailyQuestProgress)
                && Objects.equals(
                        betrayalRitual != null ? betrayalRitual.getStartTimeMs() : null,
                        other.betrayalRitual != null ? other.betrayalRitual.getStartTimeMs() : null);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid, playerName, pledgedGodUUID, betrayalCount, betrayalsAgainstCount,
                isFallenGod, earnedTitles, activeTitle, hasRebellionBanner, dailyQuestProgress,
                loginRewardClaimedToday, lastPrayTimestamp,
                betrayalRitual != null ? betrayalRitual.getStartTimeMs() : null);
    }
}
