package com.example.godsvsmortals.command;

import com.example.godsvsmortals.GodsVsMortalsPlugin;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class SacrificeCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "godsvsmortals.play";

    private final GodsVsMortalsPlugin plugin;
    private final PowerSystem powerSystem;

    public SacrificeCommand(GodsVsMortalsPlugin plugin, PowerSystem powerSystem) {
        this.plugin = plugin;
        this.powerSystem = powerSystem;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
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

        UUID targetGodUUID = resolveGodUUID(player, args[0]);
        if (targetGodUUID == null) {
            player.sendMessage(Component.text("God not found: " + args[0], NamedTextColor.RED));
            return true;
        }
        powerSystem.processSacrifice(player.getUniqueId(), targetGodUUID);
        return true;
    }

    private UUID resolveGodUUID(Player sender, String name) {
        // #26 fix: only return UUIDs that are in the elected gods list
        List<UUID> godUUIDs = plugin.getEventManager().getState().getGodUUIDs();
        Player online = sender.getServer().getPlayer(name);
        if (online != null && godUUIDs.contains(online.getUniqueId())) return online.getUniqueId();
        @SuppressWarnings("deprecation")
        var offline = sender.getServer().getOfflinePlayer(name);
        if (offline.hasPlayedBefore() && godUUIDs.contains(offline.getUniqueId())) return offline.getUniqueId();
        return null;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            // Only suggest actual elected gods, excluding the sender themselves
            Set<UUID> godUUIDs = Set.copyOf(plugin.getEventManager().getState().getGodUUIDs());
            UUID senderUUID = sender instanceof Player sp ? sp.getUniqueId() : null;
            return Bukkit.getOnlinePlayers().stream()
                    .filter(p -> godUUIDs.contains(p.getUniqueId()))
                    .filter(p -> !p.getUniqueId().equals(senderUUID))
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
