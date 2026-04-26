package com.example.godsvsmortals;

import com.example.godsvsmortals.enums.TitleType;
import com.example.godsvsmortals.model.GodData;
import com.example.godsvsmortals.model.MortalData;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/**
 * Manages player titles earned during the event.
 *
 * <p>Titles are assigned based on player actions:
 * <ul>
 *   <li>"The Loyal" - never betrayed a god</li>
 *   <li>"The Turncloak" - completed at least one betrayal</li>
 *   <li>"Godslayer" - landed killing blow on a god during Ragnarok</li>
 *   <li>"The Merciful" - god with most blessings given</li>
 *   <li>"The Wrathful" - god with most curses applied</li>
 * </ul>
 *
 * <p>Requirements: 18.1, 18.2, 18.3, 18.4, 18.5, 18.6, 18.7
 */
public class TitleSystem implements Listener {

    private final GodsVsMortalsPlugin plugin;
    private final Logger logger;

    public TitleSystem(GodsVsMortalsPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * Assigns a specific title to a player immediately (e.g., Godslayer on kill).
     *
     * <p>Requirement: 18.3, 18.6, 18.7
     */
    public void assignTitle(UUID playerUUID, TitleType title) {
        MortalData data = plugin.getQuestSystem().getMortalData(playerUUID);

        // Add to earned titles if not already present
        if (!data.getEarnedTitles().contains(title)) {
            data.getEarnedTitles().add(title);
        }

        // Set as active title (most recently earned) (Req 18.7)
        data.setActiveTitle(title);

        logger.info("TitleSystem: assigned title " + title.name() + " to player " + playerUUID);

        // Notify player if online
        Player player = plugin.getServer().getPlayer(playerUUID);
        if (player != null && player.isOnline()) {
            player.sendMessage(Component.text(
                    "✦ You have earned the title: " + formatTitle(title),
                    NamedTextColor.GOLD));
        }
    }

    /**
     * Returns the active title prefix for a player, or empty string if none.
     *
     * <p>Requirement: 18.6
     */
    public String getActiveTitle(UUID playerUUID) {
        MortalData data = plugin.getQuestSystem().getMortalData(playerUUID);
        TitleType activeTitle = data.getActiveTitle();
        return activeTitle != null ? formatTitle(activeTitle) : "";
    }

    /**
     * Evaluates and assigns all end-of-event titles.
     * Called by EventManager when the event ends.
     *
     * <p>Requirements: 18.1, 18.2, 18.4, 18.5
     */
    public void assignEndOfEventTitles() {
        // Assign "The Loyal" and "The Turncloak" to mortals (Req 18.1, 18.2)
        for (MortalData data : getAllMortals()) {
            if (data.getBetrayalCount() == 0) {
                assignTitle(data.getUuid(), TitleType.THE_LOYAL);
            } else if (data.getBetrayalCount() > 0) {
                assignTitle(data.getUuid(), TitleType.THE_TURNCLOAK);
            }
        }

        // Assign "The Merciful" and "The Wrathful" to gods (Req 18.4, 18.5)
        List<GodData> gods = getAllGods();
        if (!gods.isEmpty()) {
            // Find god with most blessings
            GodData mostMerciful = gods.stream()
                    .max(Comparator.comparingInt(GodData::getBlessingCount))
                    .orElse(null);
            if (mostMerciful != null && mostMerciful.getBlessingCount() > 0) {
                assignTitle(mostMerciful.getUuid(), TitleType.THE_MERCIFUL);
            }

            // Find god with most curses
            GodData mostWrathful = gods.stream()
                    .max(Comparator.comparingInt(GodData::getCurseCount))
                    .orElse(null);
            if (mostWrathful != null && mostWrathful.getCurseCount() > 0) {
                assignTitle(mostWrathful.getUuid(), TitleType.THE_WRATHFUL);
            }
        }

        logger.info("TitleSystem: end-of-event titles assigned.");
    }

    /**
     * Listens for god kills during Ragnarok and assigns "Godslayer" title.
     * HIGH #18 fix: only award during RAGNAROK phase.
     *
     * <p>Requirement: 18.3
     */
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        // HIGH #18 fix: only during Ragnarok
        if (plugin.getEventManager().getCurrentPhase() != com.example.godsvsmortals.enums.EventPhase.RAGNAROK) return;

        // Check if victim is a god
        UUID victimUUID = victim.getUniqueId();
        List<UUID> godUUIDs = plugin.getEventManager().getState().getGodUUIDs();
        if (!godUUIDs.contains(victimUUID)) return;

        // Check if killer is a player
        Player killer = victim.getKiller();
        if (killer == null) return;

        // Assign Godslayer title (Req 18.3)
        assignTitle(killer.getUniqueId(), TitleType.GODSLAYER);
    }

    /**
     * Displays active title as chat prefix.
     *
     * <p>Requirement: 18.6
     */
    @EventHandler
    public void onAsyncChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String title = getActiveTitle(player.getUniqueId());

        if (!title.isEmpty()) {
            // Modify the chat renderer to prepend the title
            Component titlePrefix = Component.text("[" + title + "] ", NamedTextColor.GOLD);
            
            event.renderer((source, sourceDisplayName, message, viewer) -> {
                return Component.text()
                        .append(titlePrefix)
                        .append(sourceDisplayName)
                        .append(Component.text(": "))
                        .append(message)
                        .build();
            });
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String formatTitle(TitleType title) {
        return switch (title) {
            case THE_LOYAL -> "The Loyal";
            case THE_TURNCLOAK -> "The Turncloak";
            case GODSLAYER -> "Godslayer";
            case THE_MERCIFUL -> "The Merciful";
            case THE_WRATHFUL -> "The Wrathful";
        };
    }

    private List<MortalData> getAllMortals() {
        // CRIT #5 fix: Load all mortal files from disk
        List<MortalData> mortals = new ArrayList<>();
        File mortalsDir = new File(plugin.getDataFolder(), "mortals");
        if (!mortalsDir.exists()) return mortals;

        File[] files = mortalsDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return mortals;

        for (File file : files) {
            MortalData data = MortalData.load(file, logger);
            if (data != null) {
                mortals.add(data);
            }
        }
        return mortals;
    }

    private List<GodData> getAllGods() {
        List<GodData> gods = new ArrayList<>();
        for (UUID godUUID : plugin.getEventManager().getState().getGodUUIDs()) {
            GodData data = plugin.getFaithEngine().getGodData(godUUID);
            if (data != null) {
                gods.add(data);
            }
        }
        return gods;
    }
}
