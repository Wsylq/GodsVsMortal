package com.example.godsvsmortals.model;

import com.example.godsvsmortals.enums.EventPhase;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Represents the global event state. Persisted to plugins/GodsVsMortals/state.yml.
 */
public class EventState {

    private EventPhase currentPhase;
    private long phaseStartTimestamp;
    private long lastTickTimestamp;
    private List<UUID> godUUIDs;
    private int rebellionBannerCount;
    private boolean ragnarokActive;
    private UUID fallenGodUUID;

    public EventState() {
        this.currentPhase = EventPhase.ENDED;
        this.phaseStartTimestamp = 0L;
        this.lastTickTimestamp = 0L;
        this.godUUIDs = new ArrayList<>();
        this.rebellionBannerCount = 0;
        this.ragnarokActive = false;
        this.fallenGodUUID = null;
    }

    public EventState(EventPhase currentPhase, long phaseStartTimestamp, long lastTickTimestamp,
                      List<UUID> godUUIDs, int rebellionBannerCount, boolean ragnarokActive,
                      UUID fallenGodUUID) {
        this.currentPhase = currentPhase;
        this.phaseStartTimestamp = phaseStartTimestamp;
        this.lastTickTimestamp = lastTickTimestamp;
        this.godUUIDs = new ArrayList<>(godUUIDs);
        this.rebellionBannerCount = rebellionBannerCount;
        this.ragnarokActive = ragnarokActive;
        this.fallenGodUUID = fallenGodUUID;
    }

    // --- Serialization ---

    public void toYaml(org.bukkit.configuration.ConfigurationSection section) {
        section.set("currentPhase", currentPhase.name());
        section.set("phaseStartTimestamp", phaseStartTimestamp);
        section.set("lastTickTimestamp", lastTickTimestamp);
        List<String> godUUIDStrings = new ArrayList<>();
        for (UUID uuid : godUUIDs) godUUIDStrings.add(uuid.toString());
        section.set("godUUIDs", godUUIDStrings);
        section.set("rebellionBannerCount", rebellionBannerCount);
        section.set("ragnarokActive", ragnarokActive);
        section.set("fallenGodUUID", fallenGodUUID != null ? fallenGodUUID.toString() : null);
    }

    public static EventState fromYaml(org.bukkit.configuration.ConfigurationSection section, Logger logger) {
        if (section == null) return new EventState();
        EventPhase phase;
        try {
            phase = EventPhase.valueOf(section.getString("currentPhase", "ENDED"));
        } catch (IllegalArgumentException e) {
            if (logger != null) logger.severe("Invalid EventPhase in section, defaulting to ENDED");
            phase = EventPhase.ENDED;
        }
        long phaseStart = section.getLong("phaseStartTimestamp", 0L);
        long lastTick = section.getLong("lastTickTimestamp", 0L);
        List<String> rawUUIDs = section.getStringList("godUUIDs");
        List<UUID> godUUIDs = new ArrayList<>();
        for (String s : rawUUIDs) {
            try { godUUIDs.add(UUID.fromString(s)); }
            catch (IllegalArgumentException e) { if (logger != null) logger.severe("Invalid UUID in godUUIDs: " + s); }
        }
        int bannerCount = section.getInt("rebellionBannerCount", 0);
        boolean ragnarok = section.getBoolean("ragnarokActive", false);
        String fallenStr = section.getString("fallenGodUUID");
        UUID fallenGodUUID = null;
        if (fallenStr != null && !fallenStr.isEmpty()) {
            try { fallenGodUUID = UUID.fromString(fallenStr); }
            catch (IllegalArgumentException e) { if (logger != null) logger.severe("Invalid fallenGodUUID: " + fallenStr); }
        }
        return new EventState(phase, phaseStart, lastTick, godUUIDs, bannerCount, ragnarok, fallenGodUUID);
    }

    public void save(File file) throws IOException {
        YamlConfiguration config = new YamlConfiguration();
        config.set("currentPhase", currentPhase.name());
        config.set("phaseStartTimestamp", phaseStartTimestamp);
        config.set("lastTickTimestamp", lastTickTimestamp);
        List<String> godUUIDStrings = new ArrayList<>();
        for (UUID uuid : godUUIDs) {
            godUUIDStrings.add(uuid.toString());
        }
        config.set("godUUIDs", godUUIDStrings);
        config.set("rebellionBannerCount", rebellionBannerCount);
        config.set("ragnarokActive", ragnarokActive);
        config.set("fallenGodUUID", fallenGodUUID != null ? fallenGodUUID.toString() : null);
        config.save(file);
    }

    public static EventState load(File file, Logger logger) {
        if (!file.exists()) return new EventState();
        try {
            FileConfiguration config = YamlConfiguration.loadConfiguration(file);
            EventPhase phase;
            try {
                phase = EventPhase.valueOf(config.getString("currentPhase", "ENDED"));
            } catch (IllegalArgumentException e) {
                logger.severe("Invalid EventPhase in state.yml, defaulting to ENDED");
                phase = EventPhase.ENDED;
            }
            long phaseStart = config.getLong("phaseStartTimestamp", 0L);
            long lastTick = config.getLong("lastTickTimestamp", 0L);
            List<String> rawUUIDs = config.getStringList("godUUIDs");
            List<UUID> godUUIDs = new ArrayList<>();
            for (String s : rawUUIDs) {
                try {
                    godUUIDs.add(UUID.fromString(s));
                } catch (IllegalArgumentException e) {
                    logger.severe("Invalid UUID in state.yml godUUIDs: " + s);
                }
            }
            int bannerCount = config.getInt("rebellionBannerCount", 0);
            boolean ragnarok = config.getBoolean("ragnarokActive", false);
            String fallenStr = config.getString("fallenGodUUID");
            UUID fallenGodUUID = null;
            if (fallenStr != null && !fallenStr.isEmpty()) {
                try {
                    fallenGodUUID = UUID.fromString(fallenStr);
                } catch (IllegalArgumentException e) {
                    logger.severe("Invalid fallenGodUUID in state.yml: " + fallenStr);
                }
            }
            return new EventState(phase, phaseStart, lastTick, godUUIDs, bannerCount, ragnarok, fallenGodUUID);
        } catch (Exception e) {
            logger.severe("Failed to load state.yml, initializing to defaults: " + e.getMessage());
            return new EventState();
        }
    }

    // --- Getters and Setters ---

    public EventPhase getCurrentPhase() { return currentPhase; }
    public void setCurrentPhase(EventPhase currentPhase) { this.currentPhase = currentPhase; }

    public long getPhaseStartTimestamp() { return phaseStartTimestamp; }
    public void setPhaseStartTimestamp(long phaseStartTimestamp) { this.phaseStartTimestamp = phaseStartTimestamp; }

    public long getLastTickTimestamp() { return lastTickTimestamp; }
    public void setLastTickTimestamp(long lastTickTimestamp) { this.lastTickTimestamp = lastTickTimestamp; }

    public List<UUID> getGodUUIDs() { return godUUIDs; }
    public void setGodUUIDs(List<UUID> godUUIDs) { this.godUUIDs = new ArrayList<>(godUUIDs); }

    public int getRebellionBannerCount() { return rebellionBannerCount; }
    public void setRebellionBannerCount(int rebellionBannerCount) { this.rebellionBannerCount = rebellionBannerCount; }

    public boolean isRagnarokActive() { return ragnarokActive; }
    public void setRagnarokActive(boolean ragnarokActive) { this.ragnarokActive = ragnarokActive; }

    public UUID getFallenGodUUID() { return fallenGodUUID; }
    public void setFallenGodUUID(UUID fallenGodUUID) { this.fallenGodUUID = fallenGodUUID; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EventState other)) return false;
        return phaseStartTimestamp == other.phaseStartTimestamp
                && lastTickTimestamp == other.lastTickTimestamp
                && rebellionBannerCount == other.rebellionBannerCount
                && ragnarokActive == other.ragnarokActive
                && currentPhase == other.currentPhase
                && godUUIDs.equals(other.godUUIDs)
                && java.util.Objects.equals(fallenGodUUID, other.fallenGodUUID);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(currentPhase, phaseStartTimestamp, lastTickTimestamp,
                godUUIDs, rebellionBannerCount, ragnarokActive, fallenGodUUID);
    }
}
