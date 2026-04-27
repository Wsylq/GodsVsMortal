package com.example.godsvsmortals;

import com.example.godsvsmortals.enums.EventPhase;
import com.example.godsvsmortals.model.EventState;
import com.example.godsvsmortals.model.GodData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scoreboard.*;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Manages the global sidebar scoreboard displayed to all players.
 *
 * <p>Displays:
 * <ul>
 *   <li>Each god's name, follower count, faith total, betrayal count</li>
 *   <li>Rebellion banner count (0-5)</li>
 *   <li>RAGNAROK ACTIVE indicator</li>
 *   <li>Banishment status with remaining duration</li>
 * </ul>
 *
 * <p>Requirements: 20.1, 20.2, 20.3, 20.4, 20.5, 12.5, 13.2
 */
public class ScoreboardManager implements Listener {

    private final GodsVsMortalsPlugin plugin;
    private final Logger logger;
    private long lastRefreshTimestamp = 0;
    private static final long REFRESH_INTERVAL_MS = 5000; // 5 seconds (Req 20.5)

    public ScoreboardManager(GodsVsMortalsPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * Shows scoreboard to player when they join.
     * Requirement: 20.1
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        showToPlayer(event.getPlayer());
    }

    /**
     * Refreshes the scoreboard for all online players.
     * Rate-limited to once every 5 seconds.
     *
     * <p>Requirement: 20.5
     */
    public void refresh() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshTimestamp < REFRESH_INTERVAL_MS) {
            return; // Rate limit
        }
        lastRefreshTimestamp = now;

        for (Player player : Bukkit.getOnlinePlayers()) {
            showToPlayer(player);
        }
    }

    /**
     * Immediately clears the scoreboard for all online players.
     * Called when the event is stopped.
     */
    public void clearAll() {
        org.bukkit.scoreboard.ScoreboardManager bukkitManager = Bukkit.getScoreboardManager();
        if (bukkitManager == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setScoreboard(bukkitManager.getMainScoreboard());
        }
    }

    /**
     * Shows the scoreboard to a specific player.
     *
     * <p>Requirements: 20.1, 20.2, 20.3, 20.4
     */
    public void showToPlayer(Player player) {
        EventState state = plugin.getEventManager().getState();
        EventPhase phase = state.getCurrentPhase();

        // Clear scoreboard when event is not running
        if (phase == EventPhase.ENDED) {
            org.bukkit.scoreboard.ScoreboardManager bukkitManager = Bukkit.getScoreboardManager();
            if (bukkitManager != null) {
                player.setScoreboard(bukkitManager.getMainScoreboard());
            }
            return;
        }

        // Create or get scoreboard
        org.bukkit.scoreboard.ScoreboardManager bukkitManager = Bukkit.getScoreboardManager();
        if (bukkitManager == null) return;

        Scoreboard scoreboard = bukkitManager.getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective(
                "gvm_event",
                Criteria.DUMMY,
                Component.text("⚔ Gods vs Mortals ⚔", NamedTextColor.GOLD, TextDecoration.BOLD)
        );
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        int score = 15; // Start from top

        // Display RAGNAROK indicator (Req 20.3, 12.5)
        if (phase == EventPhase.RAGNAROK) {
            Score ragnarokScore = objective.getScore("§c§l⚡ RAGNAROK ACTIVE ⚡");
            ragnarokScore.setScore(score--);
            Score spacer1 = objective.getScore("§7" + "─".repeat(20));
            spacer1.setScore(score--);
        }

        // Display rebellion banner count (Req 20.2, 13.2)
        int bannerCount = state.getRebellionBannerCount();
        Score bannerScore = objective.getScore("§e⚑ Rebellion: §f" + bannerCount + "§7/§f5");
        bannerScore.setScore(score--);

        Score spacer2 = objective.getScore("§7" + "─".repeat(20) + " ");
        spacer2.setScore(score--);

        // Display each god's stats (Req 20.1)
        List<UUID> godUUIDs = state.getGodUUIDs();
        if (godUUIDs.isEmpty()) {
            Score noGodsScore = objective.getScore("§7No gods elected yet");
            noGodsScore.setScore(score--);
        } else {
            for (UUID godUUID : godUUIDs) {
                GodData godData = plugin.getFaithEngine().getGodData(godUUID);
                if (godData == null) continue;

                Player god = Bukkit.getPlayer(godUUID);
                String godName = god != null ? god.getName() : "Unknown";

                // God name line
                Score nameScore = objective.getScore("§6⚔ §e" + godName);
                nameScore.setScore(score--);

                // Follower count
                int followerCount = plugin.getFaithEngine().getFollowerCount(godUUID);
                Score followerScore = objective.getScore("  §7Followers: §f" + followerCount);
                followerScore.setScore(score--);

                // Faith total – use live FaithEngine value (#1/#2/#15 fix)
                int faith = plugin.getFaithEngine().getFaith(godUUID);
                Score faithScore = objective.getScore("  §7Faith: §f" + faith);
                faithScore.setScore(score--);

                // Betrayal count against this god
                int betrayalCount = godData.getBetrayalsAgainst();
                Score betrayalScore = objective.getScore("  §7Betrayals: §f" + betrayalCount);
                betrayalScore.setScore(score--);

                // Banishment status (Req 20.4)
                if (godData.isBanished()) {
                    long remainingMs = godData.getBanishmentEndTime() - System.currentTimeMillis();
                    long remainingMinutes = Math.max(0, remainingMs / 60000);
                    Score banishScore = objective.getScore("  §c⚠ Banished: §f" + remainingMinutes + "m");
                    banishScore.setScore(score--);
                }

                // Spacer between gods
                if (godUUIDs.indexOf(godUUID) < godUUIDs.size() - 1) {
                    Score spacer = objective.getScore("§8" + "─".repeat(15) + " " + godUUIDs.indexOf(godUUID));
                    spacer.setScore(score--);
                }
            }
        }

        // Apply scoreboard to player
        player.setScoreboard(scoreboard);
    }
}
