package com.example.godsvsmortals;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * Queues and delivers notifications for offline players.
 *
 * <p>Requirements: 21.1–21.5
 */
public class NotificationSystem implements Listener {

    private final GodsVsMortalsPlugin plugin;
    private final Logger logger;

    /** playerUUID → list of pending messages */
    private final Map<UUID, List<String>> queue = new HashMap<>();

    public NotificationSystem(GodsVsMortalsPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        loadQueue();
    }

    /**
     * Queues a notification for a player (Req 21.1, 21.2).
     * #18 fix: save async instead of blocking the main thread on every queue call.
     */
    public void queue(UUID playerUUID, String message) {
        queue.computeIfAbsent(playerUUID, k -> new ArrayList<>()).add(message);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::saveQueue);
    }

    /**
     * Delivers and clears all queued notifications for a player (Req 21.3, 21.5).
     * HIGH #21 fix: check if player is online BEFORE removing from queue.
     */
    public void deliverAll(UUID playerUUID) {
        List<String> messages = queue.get(playerUUID);
        if (messages == null || messages.isEmpty()) return;

        Player player = plugin.getServer().getPlayer(playerUUID);
        if (player == null || !player.isOnline()) {
            // HIGH #21 fix: don't remove from queue if player is offline
            return;
        }

        // Player is online, deliver and remove
        queue.remove(playerUUID);

        player.sendMessage(Component.text("--- Notifications while you were away ---", NamedTextColor.GOLD));
        for (String msg : messages) {
            player.sendMessage(Component.text(msg, NamedTextColor.YELLOW));
        }
        saveQueue();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        deliverAll(event.getPlayer().getUniqueId());
    }

    /**
     * Persists the notification queue to disk (Req 21.4).
     */
    public void saveQueue() {
        File file = new File(plugin.getDataFolder(), "notifications.yml");
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<UUID, List<String>> entry : queue.entrySet()) {
            config.set(entry.getKey().toString(), entry.getValue());
        }
        try {
            config.save(file);
        } catch (IOException e) {
            logger.severe("NotificationSystem: failed to save queue: " + e.getMessage());
        }
    }

    /**
     * Loads the notification queue from disk (Req 21.4).
     */
    public void loadQueue() {
        File file = new File(plugin.getDataFolder(), "notifications.yml");
        if (!file.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        for (String key : config.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                List<String> messages = config.getStringList(key);
                if (!messages.isEmpty()) {
                    queue.put(uuid, new ArrayList<>(messages));
                }
            } catch (IllegalArgumentException e) {
                logger.severe("NotificationSystem: invalid UUID key in queue: " + key);
            }
        }
    }
}
