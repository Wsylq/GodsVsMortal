package com.example.godsvsmortals.command;

import com.example.godsvsmortals.ChatSystem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Handles the /pray command for mortals to send messages to their god.
 */
public class PrayCommand implements CommandExecutor {

    private final ChatSystem chatSystem;

    public PrayCommand(ChatSystem chatSystem) {
        this.chatSystem = chatSystem;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(Component.text("Usage: /pray <message>", NamedTextColor.RED));
            return true;
        }

        // Join all args into a single message
        String message = String.join(" ", args);
        chatSystem.pray(player, message);

        return true;
    }
}
