package com.example.godsvsmortals;

import com.example.godsvsmortals.model.MortalData;
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
     *
     * @param mortal the mortal sending the prayer
     * @param message the prayer message
     * @return true if the message was sent; false if rejected
     *
     * <p>Requirements: 19.1, 19.3, 19.6
     */
    public boolean pray(Player mortal, String message) {
        UUID mortalUUID = mortal.getUniqueId();
        MortalData data = plugin.getQuestSystem().getMortalData(mortalUUID);

        // Check if mortal has a pledged god (Req 19.3)
        UUID godUUID = data.getPledgedGodUUID();
        if (godUUID == null) {
            mortal.sendMessage(Component.text(
                    "✦ You must dedicate a shrine to a god before you can pray.",
                    NamedTextColor.RED));
            return false;
        }

        // Deliver message to god if online (Req 19.1, 19.6)
        Player god = Bukkit.getPlayer(godUUID);
        if (god != null && god.isOnline()) {
            Component prayerMessage = Component.text()
                    .append(Component.text("[Prayer] ", NamedTextColor.GOLD, TextDecoration.ITALIC))
                    .append(Component.text(mortal.getName() + ": ", NamedTextColor.YELLOW))
                    .append(Component.text(message, NamedTextColor.WHITE))
                    .build();
            god.sendMessage(prayerMessage);
        }

        // Confirm to mortal
        mortal.sendMessage(Component.text(
                "✦ Your prayer has been sent to your god.",
                NamedTextColor.AQUA));

        logger.info("ChatSystem: " + mortal.getName() + " prayed to god " + godUUID);
        return true;
    }

    /**
     * Sends a /bless message from a god to a follower.
     *
     * @param god the god sending the blessing message
     * @param targetName the name of the target mortal
     * @param message the blessing message
     * @return true if the message was sent; false if rejected
     *
     * <p>Requirements: 19.2, 19.4
     */
    public boolean bless(Player god, String targetName, String message) {
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
