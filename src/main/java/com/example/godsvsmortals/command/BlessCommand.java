package com.example.godsvsmortals.command;

import com.example.godsvsmortals.ChatSystem;
import com.example.godsvsmortals.GodsVsMortalsPlugin;
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

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class BlessCommand implements CommandExecutor, TabCompleter {

    private final GodsVsMortalsPlugin plugin;
    private final ChatSystem chatSystem;

    public BlessCommand(GodsVsMortalsPlugin plugin, ChatSystem chatSystem) {
        this.plugin = plugin;
        this.chatSystem = chatSystem;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }
        // #40 fix: permission check
        if (!player.hasPermission("godsvsmortals.god")) {
            player.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return true;
        }
        // #40 fix: must be an elected god
        java.util.List<java.util.UUID> electedGods = plugin.getEventManager().getState().getGodUUIDs();
        if (!electedGods.contains(player.getUniqueId())) {
            player.sendMessage(Component.text("You are not an elected god.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /bless <player> <message>", NamedTextColor.RED));
            return true;
        }
        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        chatSystem.bless(player, args[0], message);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && sender instanceof Player god) {
            // Only suggest this god's own followers
            UUID godUUID = god.getUniqueId();
            return Bukkit.getOnlinePlayers().stream()
                    .filter(p -> {
                        var mortal = plugin.getPowerSystem().getMortalData(p.getUniqueId());
                        return mortal != null && godUUID.equals(mortal.getPledgedGodUUID());
                    })
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
