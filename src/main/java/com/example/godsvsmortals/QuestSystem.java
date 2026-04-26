package com.example.godsvsmortals;

import com.example.godsvsmortals.enums.EventPhase;
import com.example.godsvsmortals.enums.QuestActionType;
import com.example.godsvsmortals.model.MortalData;
import com.example.godsvsmortals.model.QuestProgress;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Manages daily quests and the daily login reward for mortals.
 *
 * <p>Three daily quests are available starting on Day 2:
 * <ol>
 *   <li>Pray at 3 distinct shrines ({@link QuestActionType#SHRINE_PRAY})</li>
 *   <li>Sacrifice a diamond at own shrine ({@link QuestActionType#DIAMOND_SACRIFICE})</li>
 *   <li>Convert a non-believer ({@link QuestActionType#NONBELIEVER_CONVERTED})</li>
 * </ol>
 *
 * <p>Completing each quest grants a configurable faith reward to the mortal's pledged god
 * plus a small item reward. Completing all three grants a bonus reward and a server broadcast.
 *
 * <p>Quest progress resets once per in-game day (triggered by {@link EventManager}).
 * The daily login reward is granted on the first {@code /pray} of each in-game day.
 *
 * <p>All progress is persisted to {@code mortals/<uuid>.yml}.
 *
 * <p>Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 22.1, 22.2, 22.3
 */
public class QuestSystem {

    private final GodsVsMortalsPlugin plugin;
    private final Logger logger;

    /** In-memory cache of mortal data keyed by UUID. */
    private final Map<UUID, MortalData> mortalCache = new HashMap<>();

    /** Current in-game day number (incremented by EventManager on day transitions). */
    private int currentDayNumber = 1;

    public QuestSystem(GodsVsMortalsPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the current quest progress for a mortal.
     * Loads from disk if not cached.
     *
     * <p>Requirement: 11.4
     */
    public QuestProgress getProgress(UUID mortalUUID) {
        return getMortalData(mortalUUID).getDailyQuestProgress();
    }

    /**
     * Records a quest-relevant action for a mortal and grants rewards if a quest is completed.
     *
     * <p>Quests are only available on Day 2 and later (Requirement 11.1).
     * The {@code context} parameter carries additional data:
     * <ul>
     *   <li>{@link QuestActionType#SHRINE_PRAY}: context is the shrine ID (String)</li>
     *   <li>{@link QuestActionType#DIAMOND_SACRIFICE}: context is ignored</li>
     *   <li>{@link QuestActionType#NONBELIEVER_CONVERTED}: context is ignored</li>
     * </ul>
     *
     * <p>Requirements: 11.1, 11.2, 11.5
     */
    public void recordAction(UUID mortalUUID, QuestActionType action, Object context) {
        // Quests only available Day 2+
        EventPhase phase = plugin.getEventManager().getCurrentPhase();
        if (phase == EventPhase.VOTING || phase == EventPhase.DAY1 || phase == EventPhase.ENDED) {
            return;
        }

        MortalData data = getMortalData(mortalUUID);
        QuestProgress progress = data.getDailyQuestProgress();

        // Ensure progress is for the current day
        if (progress.getDayNumber() != currentDayNumber) {
            resetProgressForMortal(data);
            progress = data.getDailyQuestProgress();
        }

        boolean wasComplete = isAllComplete(progress);
        boolean questCompleted = false;

        switch (action) {
            case SHRINE_PRAY -> {
                if (progress.getShrinesPrayed() < 3 && context instanceof String shrineId) {
                    if (!progress.getPrayedShrineIds().contains(shrineId)) {
                        progress.getPrayedShrineIds().add(shrineId);
                        progress.setShrinesPrayed(progress.getShrinesPrayed() + 1);
                        if (progress.getShrinesPrayed() == 3) {
                            questCompleted = true;
                        }
                    }
                }
            }
            case DIAMOND_SACRIFICE -> {
                if (!progress.isDiamondSacrificed()) {
                    progress.setDiamondSacrificed(true);
                    questCompleted = true;
                }
            }
            case NONBELIEVER_CONVERTED -> {
                if (!progress.isNonBelieverConverted()) {
                    progress.setNonBelieverConverted(true);
                    questCompleted = true;
                }
            }
        }

        if (questCompleted) {
            grantQuestReward(mortalUUID, data, action);
        }

        // Check all-three completion bonus
        if (!wasComplete && isAllComplete(progress)) {
            grantBonusReward(mortalUUID, data);
        }

        saveMortalData(data);
    }

    /**
     * Resets all quest progress for the new in-game day.
     * Called by {@link EventManager} on day transitions.
     *
     * <p>Requirement: 11.3
     * <p>HIGH #12 fix: Don't double-increment currentDayNumber
     */
    public void resetDailyQuests() {
        // Don't increment here - EventManager already set the day number
        // Reset cached mortals
        for (MortalData data : mortalCache.values()) {
            resetProgressForMortal(data);
            data.setLoginRewardClaimedToday(false);
            saveMortalData(data);
        }
        logger.info("QuestSystem: daily quests reset for day " + currentDayNumber + ".");
    }

    /**
     * Grants the daily login reward if not yet claimed today.
     * Called when a player uses {@code /pray} for the first time on a given in-game day.
     *
     * @return true if the reward was granted; false if already claimed today
     *
     * <p>Requirements: 22.1, 22.2, 22.3
     */
    public boolean claimLoginReward(UUID playerUUID) {
        MortalData data = getMortalData(playerUUID);

        // Ensure the claim state is for the current day
        QuestProgress progress = data.getDailyQuestProgress();
        if (progress.getDayNumber() != currentDayNumber) {
            resetProgressForMortal(data);
            data.setLoginRewardClaimedToday(false);
        }

        if (data.isLoginRewardClaimedToday()) {
            return false;
        }

        data.setLoginRewardClaimedToday(true);
        grantLoginReward(playerUUID);
        saveMortalData(data);
        return true;
    }

    /**
     * Sets the current day number. Called by EventManager when the day advances.
     */
    public void setCurrentDayNumber(int dayNumber) {
        this.currentDayNumber = dayNumber;
    }

    /**
     * Returns the current day number.
     */
    public int getCurrentDayNumber() {
        return currentDayNumber;
    }

    /**
     * Registers or updates a mortal's data in the cache (used for testing and wiring).
     */
    public void registerMortal(MortalData data) {
        mortalCache.put(data.getUuid(), data);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private boolean isAllComplete(QuestProgress progress) {
        return progress.getShrinesPrayed() >= 3
                && progress.isDiamondSacrificed()
                && progress.isNonBelieverConverted();
    }

    private void resetProgressForMortal(MortalData data) {
        QuestProgress fresh = new QuestProgress();
        fresh.setDayNumber(currentDayNumber);
        data.setDailyQuestProgress(fresh);
    }

    /**
     * Grants the individual quest completion reward: faith to the pledged god + item to mortal.
     *
     * <p>Requirement: 11.2
     */
    private void grantQuestReward(UUID mortalUUID, MortalData data, QuestActionType action) {
        int faithReward = plugin.getConfig().getInt("quests.daily-reward-faith", 5);

        // Add faith to the pledged god
        UUID godUUID = data.getPledgedGodUUID();
        if (godUUID != null) {
            plugin.getFaithEngine().addFaith(godUUID, faithReward);
        }

        // Grant item reward to the player if online
        Player player = Bukkit.getPlayer(mortalUUID);
        if (player != null && player.isOnline()) {
            ItemStack reward = getQuestItemReward();
            player.getInventory().addItem(reward);
            player.sendMessage(Component.text(
                    "✦ Quest complete: " + formatQuestName(action) + "! +" + faithReward + " faith for your god.",
                    NamedTextColor.GREEN));
        }

        logger.info("QuestSystem: mortal " + mortalUUID + " completed quest " + action.name());
    }

    /**
     * Grants the all-three-quests bonus reward and broadcasts to the server.
     *
     * <p>Requirement: 11.5
     */
    private void grantBonusReward(UUID mortalUUID, MortalData data) {
        Player player = Bukkit.getPlayer(mortalUUID);
        String name = data.getPlayerName();

        // Bonus: double the faith reward as a bonus
        int bonusFaith = plugin.getConfig().getInt("quests.daily-reward-faith", 5) * 2;
        UUID godUUID = data.getPledgedGodUUID();
        if (godUUID != null) {
            plugin.getFaithEngine().addFaith(godUUID, bonusFaith);
        }

        if (player != null && player.isOnline()) {
            ItemStack bonus = getQuestItemReward();
            bonus.setAmount(bonus.getAmount() * 2);
            player.getInventory().addItem(bonus);
            player.sendMessage(Component.text(
                    "✦ You completed ALL daily quests! Bonus reward granted!", NamedTextColor.GOLD));
        }

        // Server-wide broadcast
        Bukkit.getServer().broadcast(Component.text(
                "⚡ " + name + " has completed all daily quests!", NamedTextColor.GOLD));

        logger.info("QuestSystem: mortal " + mortalUUID + " completed all daily quests.");
    }

    /**
     * Grants the daily login reward to the player.
     *
     * <p>Requirements: 22.1, 22.2
     */
    private void grantLoginReward(UUID playerUUID) {
        Player player = Bukkit.getPlayer(playerUUID);
        List<String> rewardEntries = plugin.getConfig().getStringList("quests.login-reward");

        if (player != null && player.isOnline()) {
            for (String entry : rewardEntries) {
                ItemStack item = parseItemEntry(entry);
                if (item != null) {
                    player.getInventory().addItem(item);
                }
            }
            player.sendMessage(Component.text(
                    "✦ Daily login reward claimed!", NamedTextColor.AQUA));
        }

        logger.info("QuestSystem: login reward granted to " + playerUUID);
    }

    /**
     * Parses an item entry in the format "MATERIAL:AMOUNT" (e.g. "BREAD:3").
     * LOW #43 fix: clamp amount to material's max stack size.
     */
    ItemStack parseItemEntry(String entry) {
        if (entry == null || entry.isBlank()) return null;
        String[] parts = entry.split(":");
        try {
            Material mat = Material.valueOf(parts[0].toUpperCase());
            int amount = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
            // LOW #43 fix: clamp to max stack size
            amount = Math.max(1, Math.min(amount, mat.getMaxStackSize()));
            return new ItemStack(mat, amount);
        } catch (IllegalArgumentException e) {
            logger.warning("QuestSystem: invalid item entry '" + entry + "': " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns a small item reward for individual quest completion (bread × 1 by default).
     */
    private ItemStack getQuestItemReward() {
        List<String> entries = plugin.getConfig().getStringList("quests.login-reward");
        if (!entries.isEmpty()) {
            ItemStack item = parseItemEntry(entries.get(0));
            if (item != null) {
                item.setAmount(1);
                return item;
            }
        }
        return new ItemStack(Material.BREAD, 1);
    }

    private String formatQuestName(QuestActionType action) {
        return switch (action) {
            case SHRINE_PRAY -> "Pray at 3 Shrines";
            case DIAMOND_SACRIFICE -> "Diamond Sacrifice";
            case NONBELIEVER_CONVERTED -> "Convert a Non-Believer";
        };
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    /**
     * Returns the MortalData for the given UUID, loading from disk if not cached.
     * MED #23 fix: resolve real player name instead of UUID string.
     */
    public MortalData getMortalData(UUID uuid) {
        if (mortalCache.containsKey(uuid)) {
            return mortalCache.get(uuid);
        }

        File mortalsDir = new File(plugin.getDataFolder(), "mortals");
        File file = new File(mortalsDir, uuid.toString() + ".yml");
        MortalData data = MortalData.load(file, logger);
        if (data == null) {
            // MED #23 fix: resolve real name from Bukkit
            String playerName = uuid.toString();
            org.bukkit.OfflinePlayer op = plugin.getServer().getOfflinePlayer(uuid);
            if (op.getName() != null) playerName = op.getName();
            data = new MortalData(uuid, playerName);
            data.getDailyQuestProgress().setDayNumber(currentDayNumber);
        }
        mortalCache.put(uuid, data);
        return data;
    }

    /**
     * Persists a mortal's data to disk.
     * MED #24 fix: save synchronously on main thread to avoid race conditions.
     * The file I/O is fast enough for individual saves; bulk saves use saveAllMortals.
     */
    private void saveMortalData(MortalData data) {
        File mortalsDir = new File(plugin.getDataFolder(), "mortals");
        mortalsDir.mkdirs();
        File file = new File(mortalsDir, data.getUuid().toString() + ".yml");
        try {
            data.save(file);
        } catch (IOException e) {
            logger.severe("QuestSystem: failed to save mortal data for " + data.getUuid() + ": " + e.getMessage());
        }
    }

    /**
     * Saves all mortal data to disk synchronously.
     * Called during plugin shutdown.
     */
    public void saveAllMortals() {
        File mortalsDir = new File(plugin.getDataFolder(), "mortals");
        mortalsDir.mkdirs();
        for (MortalData data : mortalCache.values()) {
            File file = new File(mortalsDir, data.getUuid().toString() + ".yml");
            try {
                data.save(file);
            } catch (IOException e) {
                logger.severe("QuestSystem: failed to save mortal data for " + data.getUuid() + ": " + e.getMessage());
            }
        }
        logger.info("QuestSystem: saved " + mortalCache.size() + " mortal(s).");
    }
}
