package com.example.godsvsmortals.command;

import com.example.godsvsmortals.GodsVsMortalsPlugin;
import com.example.godsvsmortals.model.Shrine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Handles /shrine dedicate command for dedicating a shrine to a god.
 * CRIT #3 fix
 */
public class ShrineCommand implements CommandExecutor, TabCompleter {

    private final GodsVsMortalsPlugin plugin;

    public ShrineCommand(GodsVsMortalsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }

        if (args.length < 2 || !args[0].equalsIgnoreCase("dedicate")) {
            player.sendMessage(Component.text("Usage: /shrine dedicate <god_name>", NamedTextColor.RED));
            return true;
        }

        // Get player's shrine
        Shrine shrine = plugin.getShrineDetector().getShrineByOwner(player.getUniqueId());
        if (shrine == null) {
            player.sendMessage(Component.text("You don't have a shrine to dedicate!", NamedTextColor.RED));
            return true;
        }

        // Check if already dedicated
        if (shrine.getDedicatedGodUUID() != null) {
            player.sendMessage(Component.text("Your shrine is already dedicated to a god!", NamedTextColor.RED));
            return true;
        }

        // Find the god by name
        String godName = args[1];
        List<UUID> godUUIDs = plugin.getEventManager().getState().getGodUUIDs();
        UUID targetGodUUID = null;

        for (UUID godUUID : godUUIDs) {
            org.bukkit.entity.Player god = plugin.getServer().getPlayer(godUUID);
            if (god != null && god.getName().equalsIgnoreCase(godName)) {
                targetGodUUID = godUUID;
                break;
            }
            // Also check offline players
            org.bukkit.OfflinePlayer offlineGod = plugin.getServer().getOfflinePlayer(godUUID);
            if (offlineGod.getName() != null && offlineGod.getName().equalsIgnoreCase(godName)) {
                targetGodUUID = godUUID;
                break;
            }
        }

        if (targetGodUUID == null) {
            player.sendMessage(Component.text("'" + godName + "' is not a god!", NamedTextColor.RED));
            return true;
        }

        // Dedicate the shrine
        shrine.setDedicatedGodUUID(targetGodUUID);
        // #3 fix: persist shrine to disk
        plugin.getShrineDetector().saveShrinePublic(shrine);
        
        // Update mortal's pledged god
        var mortalData = plugin.getQuestSystem().getMortalData(player.getUniqueId());
        mortalData.setPledgedGodUUID(targetGodUUID);
        // #3 fix: persist mortal data to disk
        plugin.getQuestSystem().saveMortalData(mortalData);
        // #16 fix: sync to PowerSystem's mortalRegistry
        plugin.getPowerSystem().registerMortal(mortalData);

        player.sendMessage(Component.text(
                "✦ Your shrine has been dedicated to " + godName + "!",
                NamedTextColor.GOLD));

        plugin.getLogger().info("Shrine " + shrine.getId() + " dedicated to god " + targetGodUUID);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                  @NotNull String label, @NotNull String[] args) {
        List<String> suggestions = new ArrayList<>();

        if (args.length == 1) {
            suggestions.add("dedicate");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("dedicate")) {
            // Suggest god names
            List<UUID> godUUIDs = plugin.getEventManager().getState().getGodUUIDs();
            for (UUID godUUID : godUUIDs) {
                org.bukkit.entity.Player god = plugin.getServer().getPlayer(godUUID);
                if (god != null) {
                    suggestions.add(god.getName());
                } else {
                    org.bukkit.OfflinePlayer offlineGod = plugin.getServer().getOfflinePlayer(godUUID);
                    if (offlineGod.getName() != null) {
                        suggestions.add(offlineGod.getName());
                    }
                }
            }
        }

        return suggestions;
    }
}
