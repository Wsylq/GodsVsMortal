package com.example.godsvsmortals.command;

import com.example.godsvsmortals.ChatSystem;
import com.example.godsvsmortals.GodsVsMortalsPlugin;
import com.example.godsvsmortals.QuestSystem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Handles the /pray command for mortals to send prayers to their god or claim daily login reward.
 * CRIT #4 fix
 */
public class PrayCommand implements CommandExecutor {

    private final GodsVsMortalsPlugin plugin;
    private final ChatSystem chatSystem;
    private final QuestSystem questSystem;

    public PrayCommand(GodsVsMortalsPlugin plugin, ChatSystem chatSystem, QuestSystem questSystem) {
        this.plugin = plugin;
        this.chatSystem = chatSystem;
        this.questSystem = questSystem;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }

        // If no args, try to claim daily login reward (CRIT #4 fix)
        if (args.length == 0) {
            boolean claimed = questSystem.claimLoginReward(player.getUniqueId());
            if (!claimed) {
                player.sendMessage(Component.text(
                        "You have already claimed your daily login reward today.",
                        NamedTextColor.YELLOW));
            }
            return true;
        }

        // Otherwise send prayer message
        String message = String.join(" ", args);
        chatSystem.pray(player, message);

        return true;
    }
}
