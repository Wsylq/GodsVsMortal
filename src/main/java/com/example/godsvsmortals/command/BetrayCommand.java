package com.example.godsvsmortals.command;

import com.example.godsvsmortals.PowerSystem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Handles the /betray command.
 *
 * <p>Initiates a betrayal ritual for the executing mortal.
 * Delegates all validation and logic to {@link PowerSystem#startBetrayal(java.util.UUID)}.
 *
 * <p>Requirements: 8.1, 8.8
 */
public class BetrayCommand implements CommandExecutor {

    private static final String PERMISSION = "godsvsmortals.play";

    private final PowerSystem powerSystem;

    public BetrayCommand(PowerSystem powerSystem) {
        this.powerSystem = powerSystem;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }

        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage(Component.text("You do not have permission to use this command.", NamedTextColor.RED));
            return true;
        }

        powerSystem.startBetrayal(player.getUniqueId());
        return true;
    }
}
