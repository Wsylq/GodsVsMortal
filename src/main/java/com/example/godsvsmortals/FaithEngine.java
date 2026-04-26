package com.example.godsvsmortals;

import com.example.godsvsmortals.enums.TemplateTier;
import com.example.godsvsmortals.model.GodData;
import com.example.godsvsmortals.model.Shrine;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Manages faith generation, distribution, and persistence for gods.
 *
 * <p>Faith is generated every 60 seconds from all active shrines. Each shrine
 * contributes (base_rate × tier_multiplier) faith to its dedicated god, subject
 * to the configured faith cap.
 *
 * <p>On server restart, catch-up faith is calculated from the last tick timestamp
 * stored in EventState, capped at 4 hours of accumulation.
 *
 * <p>Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 24.1, 24.2, 24.3
 */
public class FaithEngine {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final long MILLIS_PER_MINUTE = 60_000L;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final GodsVsMortalsPlugin plugin;
    private final Logger logger;

    /** In-memory faith totals: godUUID → current faith */
    private final Map<UUID, Integer> faithMap = new HashMap<>();

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public FaithEngine(GodsVsMortalsPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Distributes faith from all active shrines to their dedicated gods.
     * Called every 60 seconds by the repeating BukkitTask.
     *
     * <p>Each shrine contributes floor(base_rate × tier_multiplier) faith.
     * The faith cap is enforced per god after summing all shrine contributions.
     *
     * <p>Requirements: 5.1, 5.2, 5.3, 5.6
     */
    public void distributeFaith() {
        int baseRate = plugin.getConfig().getInt("faith.base-rate-per-minute", 1);
        int faithCap = plugin.getConfig().getInt("event.faith-cap", 10000);

        Collection<Shrine> activeShrines = getActiveShrines();

        // Accumulate faith per god
        Map<UUID, Integer> faithToAdd = new HashMap<>();
        for (Shrine shrine : activeShrines) {
            UUID godUUID = shrine.getDedicatedGodUUID();
            if (godUUID == null) continue;

            double multiplier = getTierMultiplier(shrine.getTier());
            int faithAmount = (int) Math.floor(baseRate * multiplier);
            faithToAdd.merge(godUUID, faithAmount, Integer::sum);
        }

        // Apply to each god, respecting the cap
        for (Map.Entry<UUID, Integer> entry : faithToAdd.entrySet()) {
            UUID godUUID = entry.getKey();
            int amount = entry.getValue();
            int current = getFaith(godUUID);
            int newTotal = Math.min(current + amount, faithCap);
            faithMap.put(godUUID, newTotal);
            persistGodFaith(godUUID, newTotal);
        }

        // Update lastTickTimestamp in EventState
        plugin.getEventManager().getState().setLastTickTimestamp(System.currentTimeMillis());
    }

    /**
     * Returns the current faith total for a god.
     * Returns 0 if the god has no recorded faith.
     *
     * <p>Requirement: 5.4
     */
    public int getFaith(UUID godUUID) {
        return faithMap.getOrDefault(godUUID, 0);
    }

    /**
     * Adds faith to a god, respecting the configured faith cap.
     *
     * <p>Requirement: 5.6
     */
    public void addFaith(UUID godUUID, int amount) {
        if (amount <= 0) return;
        int faithCap = plugin.getConfig().getInt("event.faith-cap", 10000);
        int current = getFaith(godUUID);
        int newTotal = Math.min(current + amount, faithCap);
        faithMap.put(godUUID, newTotal);
        persistGodFaith(godUUID, newTotal);
    }

    /**
     * Deducts faith from a god.
     *
     * @return true if the deduction succeeded; false if the god has insufficient faith
     */
    public boolean deductFaith(UUID godUUID, int amount) {
        if (amount <= 0) return true;
        int current = getFaith(godUUID);
        if (current < amount) return false;
        int newTotal = current - amount;
        faithMap.put(godUUID, newTotal);
        persistGodFaith(godUUID, newTotal);
        return true;
    }

    /**
     * Calculates the catch-up faith for a shrine after server downtime.
     *
     * <p>Formula: floor(base_rate × tier_multiplier × min(downtime_minutes, max_catchup_minutes))
     * where max_catchup_minutes = offline-catchup-max-hours × 60.
     *
     * <p>Requirements: 24.2, 24.3
     *
     * @param shrine           the shrine to calculate catch-up for
     * @param downtimeDuration downtime in milliseconds
     * @return the catch-up faith amount (non-negative integer)
     */
    public int calculateCatchUpFaith(Shrine shrine, long downtimeDuration) {
        if (downtimeDuration <= 0) return 0;

        int baseRate = plugin.getConfig().getInt("faith.base-rate-per-minute", 1);
        int maxCatchupHours = plugin.getConfig().getInt("event.offline-catchup-max-hours", 4);
        long maxCatchupMillis = maxCatchupHours * 60L * MILLIS_PER_MINUTE;

        long effectiveDowntime = Math.min(downtimeDuration, maxCatchupMillis);
        double downtimeMinutes = effectiveDowntime / (double) MILLIS_PER_MINUTE;
        double multiplier = getTierMultiplier(shrine.getTier());

        return (int) Math.floor(baseRate * multiplier * downtimeMinutes);
    }

    /**
     * Applies catch-up faith to all active shrines based on the server downtime.
     * Called once on server start after loading state.
     *
     * <p>Requirements: 24.1, 24.2, 24.3
     *
     * @param lastTickTimestamp the epoch ms of the last faith tick before shutdown
     */
    public void applyCatchUpFaith(long lastTickTimestamp) {
        if (lastTickTimestamp <= 0) return;

        long now = System.currentTimeMillis();
        long downtimeDuration = now - lastTickTimestamp;
        if (downtimeDuration <= 0) return;

        int faithCap = plugin.getConfig().getInt("event.faith-cap", 10000);
        Collection<Shrine> activeShrines = getActiveShrines();

        for (Shrine shrine : activeShrines) {
            UUID godUUID = shrine.getDedicatedGodUUID();
            if (godUUID == null) continue;

            int catchUp = calculateCatchUpFaith(shrine, downtimeDuration);
            if (catchUp <= 0) continue;

            int current = getFaith(godUUID);
            int newTotal = Math.min(current + catchUp, faithCap);
            faithMap.put(godUUID, newTotal);
            persistGodFaith(godUUID, newTotal);
        }

        logger.info("FaithEngine: applied catch-up faith for " + downtimeDuration / 1000 + "s downtime.");
    }

    /**
     * Loads all god faith totals from disk into the in-memory map.
     * Should be called during plugin startup.
     */
    public void loadAllFaith() {
        File godsDir = new File(plugin.getDataFolder(), "gods");
        if (!godsDir.exists()) return;

        File[] files = godsDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;

        for (File file : files) {
            GodData data = GodData.load(file, logger);
            if (data != null && data.getUuid() != null) {
                faithMap.put(data.getUuid(), data.getFaithTotal());
            }
        }
        logger.info("FaithEngine: loaded faith for " + faithMap.size() + " god(s).");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the tier multiplier for the given shrine tier.
     * BASIC=1.0, IRON=1.5, GOLD=2.0, DIAMOND=3.0 (from config, with defaults).
     */
    double getTierMultiplier(TemplateTier tier) {
        return switch (tier) {
            case IRON -> plugin.getConfig().getDouble("faith.multipliers.iron", 1.5);
            case GOLD -> plugin.getConfig().getDouble("faith.multipliers.gold", 2.0);
            case DIAMOND -> plugin.getConfig().getDouble("faith.multipliers.diamond", 3.0);
            default -> 1.0;
        };
    }

    /**
     * Returns all currently active shrines from the ShrineDetector.
     */
    private Collection<Shrine> getActiveShrines() {
        return plugin.getShrineDetector().getShrinesByOwner().values();
    }

    /**
     * Persists a god's faith total to their YAML file asynchronously.
     * MED #25 fix: use async I/O instead of blocking main thread every minute.
     */
    private void persistGodFaith(UUID godUUID, int faithTotal) {
        File godsDir = new File(plugin.getDataFolder(), "gods");
        godsDir.mkdirs();
        File file = new File(godsDir, godUUID.toString() + ".yml");

        // Capture values for async thread
        final int faithSnapshot = faithTotal;
        final UUID uuidSnapshot = godUUID;

        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            GodData data = GodData.load(file, logger);
            if (data == null) {
                data = new GodData(uuidSnapshot, uuidSnapshot.toString());
            }
            data.setFaithTotal(faithSnapshot);
            try {
                data.save(file);
            } catch (IOException e) {
                logger.severe("FaithEngine: failed to persist faith for god " + uuidSnapshot + ": " + e.getMessage());
            }
        });
    }

    /**
     * Saves all god faith totals to disk synchronously.
     * Called during plugin shutdown.
     */
    public void saveAllFaith() {
        for (Map.Entry<UUID, Integer> entry : faithMap.entrySet()) {
            persistGodFaith(entry.getKey(), entry.getValue());
        }
        logger.info("FaithEngine: saved faith for " + faithMap.size() + " god(s).");
    }

    /**
     * Returns the GodData for a given god UUID, loading from disk if necessary.
     * Returns null if the god data file doesn't exist.
     */
    public GodData getGodData(UUID godUUID) {
        File godsDir = new File(plugin.getDataFolder(), "gods");
        File file = new File(godsDir, godUUID.toString() + ".yml");
        return GodData.load(file, logger);
    }

    /**
     * Returns the number of followers for a given god.
     * Counts all active shrines dedicated to this god.
     *
     * <p>Requirement: 20.1
     */
    public int getFollowerCount(UUID godUUID) {
        int count = 0;
        for (Shrine shrine : getActiveShrines()) {
            if (godUUID.equals(shrine.getDedicatedGodUUID())) {
                count++;
            }
        }
        return count;
    }
}
