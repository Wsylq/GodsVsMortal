package com.example.godsvsmortals.command;

import com.example.godsvsmortals.PowerSystem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Handles the /sacrifice command for Mass Sacrifice mechanic.
 *
 * <p>Usage: /sacrifice &lt;god_name&gt;
 *
 * <p>Deducts 5 HP from the mortal and adds 1 sacrifice point toward banishing the named god.
 * Rejects if mortal HP ≤ 6.
 *
 * Requirements: 16.1, 16.2, 16.3, 16.4, 16.5, 16.6
 */
public class SacrificeCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "godsvsmortals.play";

    private final PowerSystem powerSystem;

    public SacrificeCommand(PowerSystem powerSystem) {
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

        if (args.length == 0) {
            player.sendMessage(Component.text("Usage: /sacrifice <god_name>", NamedTextColor.YELLOW));
            return true;
        }

        String godName = args[0];

        // Resolve god UUID by name
        UUID targetGodUUID = resolveGodUUID(player, godName);
        if (targetGodUUID == null) {
            player.sendMessage(Component.text("God not found: " + godName, NamedTextColor.RED));
            return true;
        }

        powerSystem.processSacrifice(player.getUniqueId(), targetGodUUID);
        return true;
    }

    /**
     * Resolves a god UUID by player name (online or offline).
     */
    private UUID resolveGodUUID(Player sender, String name) {
        // Try online player first
        Player online = sender.getServer().getPlayer(name);
        if (online != null) return online.getUniqueId();

        // Try offline player
        @SuppressWarnings("deprecation")
        var offline = sender.getServer().getOfflinePlayer(name);
        if (offline.hasPlayedBefore()) return offline.getUniqueId();

        return null;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                 @NotNull Command command,
                                                 @NotNull String label,
                                                 @NotNull String[] args) {
        if (args.length == 1) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
