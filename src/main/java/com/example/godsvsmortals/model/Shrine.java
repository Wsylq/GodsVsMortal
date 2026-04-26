package com.example.godsvsmortals.model;

import com.example.godsvsmortals.enums.TemplateTier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Represents a shrine structure in the world. Persisted to plugins/GodsVsMortals/shrines/<id>.yml.
 */
public class Shrine {

    private String id;
    private UUID ownerUUID;
    private UUID dedicatedGodUUID;
    private Location coreLocation;
    private TemplateTier tier;
    private boolean active;
    private long createdTimestamp;
    private long lastFaithTickTimestamp;

    public Shrine() {}

    public Shrine(String id, UUID ownerUUID, UUID dedicatedGodUUID, Location coreLocation,
                  TemplateTier tier, boolean active, long createdTimestamp, long lastFaithTickTimestamp) {
        this.id = id;
        this.ownerUUID = ownerUUID;
        this.dedicatedGodUUID = dedicatedGodUUID;
        this.coreLocation = coreLocation;
        this.tier = tier;
        this.active = active;
        this.createdTimestamp = createdTimestamp;
        this.lastFaithTickTimestamp = lastFaithTickTimestamp;
    }

    // --- Serialization ---

    public void toYaml(ConfigurationSection section) {
        section.set("id", id);
        section.set("ownerUUID", ownerUUID != null ? ownerUUID.toString() : null);
        section.set("dedicatedGodUUID", dedicatedGodUUID != null ? dedicatedGodUUID.toString() : null);
        if (coreLocation != null) {
            section.set("coreLocation.world", coreLocation.getWorld() != null ? coreLocation.getWorld().getName() : null);
            section.set("coreLocation.x", coreLocation.getX());
            section.set("coreLocation.y", coreLocation.getY());
            section.set("coreLocation.z", coreLocation.getZ());
        }
        section.set("tier", tier != null ? tier.name() : TemplateTier.BASIC.name());
        section.set("active", active);
        section.set("createdTimestamp", createdTimestamp);
        section.set("lastFaithTickTimestamp", lastFaithTickTimestamp);
    }

    public static Shrine fromYaml(ConfigurationSection section) {
        if (section == null) return null;
        Shrine shrine = new Shrine();
        shrine.id = section.getString("id");
        String ownerStr = section.getString("ownerUUID");
        shrine.ownerUUID = (ownerStr != null && !ownerStr.isEmpty()) ? UUID.fromString(ownerStr) : null;
        String godStr = section.getString("dedicatedGodUUID");
        shrine.dedicatedGodUUID = (godStr != null && !godStr.isEmpty()) ? UUID.fromString(godStr) : null;
        ConfigurationSection locSection = section.getConfigurationSection("coreLocation");
        if (locSection != null) {
            String worldName = locSection.getString("world");
            World world = (worldName != null) ? Bukkit.getWorld(worldName) : null;
            double x = locSection.getDouble("x");
            double y = locSection.getDouble("y");
            double z = locSection.getDouble("z");
            shrine.coreLocation = new Location(world, x, y, z);
        }
        String tierStr = section.getString("tier", TemplateTier.BASIC.name());
        try {
            shrine.tier = TemplateTier.valueOf(tierStr);
        } catch (IllegalArgumentException e) {
            shrine.tier = TemplateTier.BASIC;
        }
        shrine.active = section.getBoolean("active", true);
        shrine.createdTimestamp = section.getLong("createdTimestamp", 0L);
        shrine.lastFaithTickTimestamp = section.getLong("lastFaithTickTimestamp", 0L);
        return shrine;
    }

    public void save(File file) throws IOException {
        YamlConfiguration config = new YamlConfiguration();
        toYaml(config.createSection("shrine"));
        config.save(file);
    }

    public static Shrine load(File file, Logger logger) {
        if (!file.exists()) return null;
        try {
            FileConfiguration config = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection section = config.getConfigurationSection("shrine");
            Shrine shrine = fromYaml(section);
            if (shrine == null || shrine.id == null) {
                logger.severe("Missing shrine data in file: " + file.getName());
                return null;
            }
            return shrine;
        } catch (Exception e) {
            logger.severe("Failed to load shrine file " + file.getName() + ", initializing to defaults: " + e.getMessage());
            return null;
        }
    }

    // --- Getters and Setters ---

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public UUID getOwnerUUID() { return ownerUUID; }
    public void setOwnerUUID(UUID ownerUUID) { this.ownerUUID = ownerUUID; }

    public UUID getDedicatedGodUUID() { return dedicatedGodUUID; }
    public void setDedicatedGodUUID(UUID dedicatedGodUUID) { this.dedicatedGodUUID = dedicatedGodUUID; }

    public Location getCoreLocation() { return coreLocation; }
    public void setCoreLocation(Location coreLocation) { this.coreLocation = coreLocation; }

    public TemplateTier getTier() { return tier; }
    public void setTier(TemplateTier tier) { this.tier = tier; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public long getCreatedTimestamp() { return createdTimestamp; }
    public void setCreatedTimestamp(long createdTimestamp) { this.createdTimestamp = createdTimestamp; }

    public long getLastFaithTickTimestamp() { return lastFaithTickTimestamp; }
    public void setLastFaithTickTimestamp(long lastFaithTickTimestamp) { this.lastFaithTickTimestamp = lastFaithTickTimestamp; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Shrine other)) return false;
        return active == other.active
                && createdTimestamp == other.createdTimestamp
                && lastFaithTickTimestamp == other.lastFaithTickTimestamp
                && Objects.equals(id, other.id)
                && Objects.equals(ownerUUID, other.ownerUUID)
                && Objects.equals(dedicatedGodUUID, other.dedicatedGodUUID)
                && tier == other.tier;
        // Note: Location equality intentionally excludes world reference for serialization tests
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerUUID, dedicatedGodUUID, tier, active, createdTimestamp, lastFaithTickTimestamp);
    }
}
