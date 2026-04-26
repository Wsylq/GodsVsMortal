package com.example.godsvsmortals.model;

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
 * Represents a god's persistent data. Persisted to plugins/GodsVsMortals/gods/<uuid>.yml.
 */
public class GodData {

    private UUID uuid;
    private String playerName;
    private int faithTotal;
    private int blessingCount;
    private int curseCount;
    private String uniquePower;
    private boolean avatarModeUsed;
    private long avatarExpiry; // LOW #65 fix: persist avatar expiry across restarts
    private boolean banished;
    private long banishmentExpiry;
    private int sacrificePoints;
    private int betrayalsAgainst;
    private List<UUID> rivals;
    private UUID trucePartner;
    private long truceExpiry;

    public GodData() {
        this.rivals = new ArrayList<>();
    }

    public GodData(UUID uuid, String playerName) {
        this.uuid = uuid;
        this.playerName = playerName;
        this.faithTotal = 0;
        this.blessingCount = 0;
        this.curseCount = 0;
        this.uniquePower = null;
        this.avatarModeUsed = false;
        this.banished = false;
        this.banishmentExpiry = 0L;
        this.sacrificePoints = 0;
        this.betrayalsAgainst = 0;
        this.rivals = new ArrayList<>();
        this.trucePartner = null;
        this.truceExpiry = 0L;
    }

    // --- Serialization ---

    public void toYaml(org.bukkit.configuration.ConfigurationSection section) {
        section.set("uuid", uuid != null ? uuid.toString() : null);
        section.set("playerName", playerName);
        section.set("faithTotal", faithTotal);
        section.set("blessingCount", blessingCount);
        section.set("curseCount", curseCount);
        section.set("uniquePower", uniquePower);
        section.set("avatarModeUsed", avatarModeUsed);
        section.set("avatarExpiry", avatarExpiry);
        section.set("banished", banished);
        section.set("banishmentExpiry", banishmentExpiry);
        section.set("sacrificePoints", sacrificePoints);
        section.set("betrayalsAgainst", betrayalsAgainst);
        List<String> rivalStrings = new ArrayList<>();
        for (UUID r : rivals) rivalStrings.add(r.toString());
        section.set("rivals", rivalStrings);
        section.set("trucePartner", trucePartner != null ? trucePartner.toString() : null);
        section.set("truceExpiry", truceExpiry);
    }

    public static GodData fromYaml(org.bukkit.configuration.ConfigurationSection section, Logger logger) {
        if (section == null) return null;
        GodData data = new GodData();
        String uuidStr = section.getString("uuid");
        if (uuidStr == null) {
            if (logger != null) logger.severe("Missing uuid in GodData section");
            return null;
        }
        data.uuid = UUID.fromString(uuidStr);
        data.playerName = section.getString("playerName", "Unknown");
        data.faithTotal = section.getInt("faithTotal", 0);
        data.blessingCount = section.getInt("blessingCount", 0);
        data.curseCount = section.getInt("curseCount", 0);
        data.uniquePower = section.getString("uniquePower");
        data.avatarModeUsed = section.getBoolean("avatarModeUsed", false);
        data.avatarExpiry = section.getLong("avatarExpiry", 0L);
        data.banished = section.getBoolean("banished", false);
        data.banishmentExpiry = section.getLong("banishmentExpiry", 0L);
        data.sacrificePoints = section.getInt("sacrificePoints", 0);
        data.betrayalsAgainst = section.getInt("betrayalsAgainst", 0);
        List<String> rawRivals = section.getStringList("rivals");
        data.rivals = new ArrayList<>();
        for (String s : rawRivals) {
            try { data.rivals.add(UUID.fromString(s)); }
            catch (IllegalArgumentException e) { if (logger != null) logger.severe("Invalid rival UUID: " + s); }
        }
        String truceStr = section.getString("trucePartner");
        data.trucePartner = (truceStr != null && !truceStr.isEmpty()) ? UUID.fromString(truceStr) : null;
        data.truceExpiry = section.getLong("truceExpiry", 0L);
        return data;
    }

    public void save(File file) throws IOException {
        YamlConfiguration config = new YamlConfiguration();
        config.set("uuid", uuid.toString());
        config.set("playerName", playerName);
        config.set("faithTotal", faithTotal);
        config.set("blessingCount", blessingCount);
        config.set("curseCount", curseCount);
        config.set("uniquePower", uniquePower);
        config.set("avatarModeUsed", avatarModeUsed);
        config.set("avatarExpiry", avatarExpiry);
        config.set("banished", banished);
        config.set("banishmentExpiry", banishmentExpiry);
        config.set("sacrificePoints", sacrificePoints);
        config.set("betrayalsAgainst", betrayalsAgainst);
        List<String> rivalStrings = new ArrayList<>();
        for (UUID r : rivals) rivalStrings.add(r.toString());
        config.set("rivals", rivalStrings);
        config.set("trucePartner", trucePartner != null ? trucePartner.toString() : null);
        config.set("truceExpiry", truceExpiry);
        config.save(file);
    }

    public static GodData load(File file, Logger logger) {
        if (!file.exists()) return null;
        try {
            FileConfiguration config = YamlConfiguration.loadConfiguration(file);
            GodData data = new GodData();
            String uuidStr = config.getString("uuid");
            if (uuidStr == null) {
                logger.severe("Missing uuid in god file: " + file.getName());
                return null;
            }
            data.uuid = UUID.fromString(uuidStr);
            data.playerName = config.getString("playerName", "Unknown");
            data.faithTotal = config.getInt("faithTotal", 0);
            data.blessingCount = config.getInt("blessingCount", 0);
            data.curseCount = config.getInt("curseCount", 0);
            data.uniquePower = config.getString("uniquePower");
            data.avatarModeUsed = config.getBoolean("avatarModeUsed", false);
            data.avatarExpiry = config.getLong("avatarExpiry", 0L);
            data.banished = config.getBoolean("banished", false);
            data.banishmentExpiry = config.getLong("banishmentExpiry", 0L);
            data.sacrificePoints = config.getInt("sacrificePoints", 0);
            data.betrayalsAgainst = config.getInt("betrayalsAgainst", 0);
            List<String> rawRivals = config.getStringList("rivals");
            data.rivals = new ArrayList<>();
            for (String s : rawRivals) {
                try { data.rivals.add(UUID.fromString(s)); }
                catch (IllegalArgumentException e) { logger.severe("Invalid rival UUID: " + s); }
            }
            String truceStr = config.getString("trucePartner");
            data.trucePartner = (truceStr != null && !truceStr.isEmpty()) ? UUID.fromString(truceStr) : null;
            data.truceExpiry = config.getLong("truceExpiry", 0L);
            return data;
        } catch (Exception e) {
            logger.severe("Failed to load god file " + file.getName() + ", initializing to defaults: " + e.getMessage());
            return null;
        }
    }

    // --- Getters and Setters ---

    public UUID getUuid() { return uuid; }
    public void setUuid(UUID uuid) { this.uuid = uuid; }

    public String getPlayerName() { return playerName; }
    public void setPlayerName(String playerName) { this.playerName = playerName; }

    public int getFaithTotal() { return faithTotal; }
    public void setFaithTotal(int faithTotal) { this.faithTotal = faithTotal; }

    public int getBlessingCount() { return blessingCount; }
    public void setBlessingCount(int blessingCount) { this.blessingCount = blessingCount; }

    public int getCurseCount() { return curseCount; }
    public void setCurseCount(int curseCount) { this.curseCount = curseCount; }

    public String getUniquePower() { return uniquePower; }
    public void setUniquePower(String uniquePower) { this.uniquePower = uniquePower; }

    public boolean isAvatarModeUsed() { return avatarModeUsed; }
    public void setAvatarModeUsed(boolean avatarModeUsed) { this.avatarModeUsed = avatarModeUsed; }
    public long getAvatarExpiry() { return avatarExpiry; }
    public void setAvatarExpiry(long avatarExpiry) { this.avatarExpiry = avatarExpiry; }

    public boolean isBanished() { return banished; }
    public void setBanished(boolean banished) { this.banished = banished; }

    public long getBanishmentExpiry() { return banishmentExpiry; }
    public void setBanishmentExpiry(long banishmentExpiry) { this.banishmentExpiry = banishmentExpiry; }

    public int getSacrificePoints() { return sacrificePoints; }
    public void setSacrificePoints(int sacrificePoints) { this.sacrificePoints = sacrificePoints; }

    public int getBetrayalsAgainst() { return betrayalsAgainst; }
    public void setBetrayalsAgainst(int betrayalsAgainst) { this.betrayalsAgainst = betrayalsAgainst; }

    public int getFaith() { return faithTotal; }
    public long getBanishmentEndTime() { return banishmentExpiry; }

    public List<UUID> getRivals() { return rivals; }
    public void setRivals(List<UUID> rivals) { this.rivals = new ArrayList<>(rivals); }

    public UUID getTrucePartner() { return trucePartner; }
    public void setTrucePartner(UUID trucePartner) { this.trucePartner = trucePartner; }

    public long getTruceExpiry() { return truceExpiry; }
    public void setTruceExpiry(long truceExpiry) { this.truceExpiry = truceExpiry; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GodData other)) return false;
        return faithTotal == other.faithTotal
                && blessingCount == other.blessingCount
                && curseCount == other.curseCount
                && avatarModeUsed == other.avatarModeUsed
                && banished == other.banished
                && banishmentExpiry == other.banishmentExpiry
                && sacrificePoints == other.sacrificePoints
                && betrayalsAgainst == other.betrayalsAgainst
                && truceExpiry == other.truceExpiry
                && Objects.equals(uuid, other.uuid)
                && Objects.equals(playerName, other.playerName)
                && Objects.equals(uniquePower, other.uniquePower)
                && rivals.equals(other.rivals)
                && Objects.equals(trucePartner, other.trucePartner);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid, playerName, faithTotal, blessingCount, curseCount,
                uniquePower, avatarModeUsed, banished, banishmentExpiry, sacrificePoints,
                betrayalsAgainst, rivals, trucePartner, truceExpiry);
    }
}
