package com.example.godsvsmortals;

import com.example.godsvsmortals.model.MortalData;
import com.example.godsvsmortals.model.Shrine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Manages private communication between gods and their followers via /pray and /bless commands.
 *
 * <p>Requirements: 19.1, 19.2, 19.3, 19.4, 19.5, 19.6
 */
public class ChatSystem {

    private final GodsVsMortalsPlugin plugin;
    private final Logger logger;

    public ChatSystem(GodsVsMortalsPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * Sends a /pray message from a mortal to their god.
     * MED #29 fix: reject if event is not running.
     * MED #46 fix: warn if god is offline instead of silently discarding.
     */
    public boolean pray(Player mortal, String message) {
        // MED #29 fix: phase check
        com.example.godsvsmortals.enums.EventPhase phase = plugin.getEventManager().getCurrentPhase();
        if (phase == com.example.godsvsmortals.enums.EventPhase.ENDED
                || phase == com.example.godsvsmortals.enums.EventPhase.VOTING) {
            mortal.sendMessage(Component.text("The event is not currently running.", NamedTextColor.RED));
            return false;
        }

        UUID mortalUUID = mortal.getUniqueId();
        var data = plugin.getQuestSystem().getMortalData(mortalUUID);

        UUID godUUID = data.getPledgedGodUUID();
        if (godUUID == null) {
            mortal.sendMessage(Component.text(
                    "✦ You must dedicate a shrine to a god before you can pray.",
                    NamedTextColor.RED));
            return false;
        }

        Player god = plugin.getServer().getPlayer(godUUID);
        if (god != null && god.isOnline()) {
            Component prayerMessage = Component.text()
                    .append(Component.text("[Prayer] ", NamedTextColor.GOLD, net.kyori.adventure.text.format.TextDecoration.ITALIC))
                    .append(Component.text(mortal.getName() + ": ", NamedTextColor.YELLOW))
                    .append(Component.text(message, NamedTextColor.WHITE))
                    .build();
            god.sendMessage(prayerMessage);
            mortal.sendMessage(Component.text("✦ Your prayer has been sent to your god.", NamedTextColor.AQUA));
        } else {
            // MED #46 fix: queue for offline god and warn mortal
            plugin.getNotificationSystem().queue(godUUID, "[Prayer from " + mortal.getName() + "] " + message);
            mortal.sendMessage(Component.text(
                    "✦ Your god is offline. Your prayer has been queued for delivery.",
                    NamedTextColor.YELLOW));
        }

        // LOW #57 fix: record SHRINE_PRAY quest action if mortal is at their shrine
        Shrine shrine = plugin.getShrineDetector().getShrineByOwner(mortal.getUniqueId());
        if (shrine != null && shrine.getDedicatedGodUUID() != null) {
            plugin.getQuestSystem().recordAction(mortal.getUniqueId(),
                    com.example.godsvsmortals.enums.QuestActionType.SHRINE_PRAY, shrine.getId());
        }

        logger.info("ChatSystem: " + mortal.getName() + " prayed to god " + godUUID);
        return true;
    }

    /**
     * Sends a /bless message from a god to a follower.
     * MED #29 fix: reject if event is not running.
     */
    public boolean bless(Player god, String targetName, String message) {
        // MED #29 fix: phase check
        com.example.godsvsmortals.enums.EventPhase phase = plugin.getEventManager().getCurrentPhase();
        if (phase == com.example.godsvsmortals.enums.EventPhase.ENDED
                || phase == com.example.godsvsmortals.enums.EventPhase.VOTING) {
            god.sendMessage(Component.text("The event is not currently running.", NamedTextColor.RED));
            return false;
        }

        UUID godUUID = god.getUniqueId();

        // Find target player
        Player target = Bukkit.getPlayer(targetName);
        if (target == null || !target.isOnline()) {
            god.sendMessage(Component.text(
                    "✦ Player '" + targetName + "' is not online.",
                    NamedTextColor.RED));
            return false;
        }

        // Check if target is a follower of this god (Req 19.4)
        UUID targetUUID = target.getUniqueId();
        MortalData targetData = plugin.getQuestSystem().getMortalData(targetUUID);
        if (!godUUID.equals(targetData.getPledgedGodUUID())) {
            god.sendMessage(Component.text(
                    "✦ " + targetName + " is not your follower.",
                    NamedTextColor.RED));
            return false;
        }

        // Deliver message to target (Req 19.2)
        Component blessingMessage = Component.text()
                .append(Component.text("[Divine Blessing] ", NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text("Your god says: ", NamedTextColor.YELLOW))
                .append(Component.text(message, NamedTextColor.WHITE))
                .build();
        target.sendMessage(blessingMessage);

        // Confirm to god
        god.sendMessage(Component.text(
                "✦ Your blessing has been delivered to " + targetName + ".",
                NamedTextColor.AQUA));

        logger.info("ChatSystem: god " + god.getName() + " blessed follower " + targetName);
        return true;
    }
}
