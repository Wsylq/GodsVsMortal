package com.example.godsvsmortals.command;

import com.example.godsvsmortals.GodsVsMortalsPlugin;
import com.example.godsvsmortals.VoteSystem;
import com.example.godsvsmortals.enums.EventPhase;
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

public class VoteCommand implements CommandExecutor, TabCompleter {

    private final VoteSystem voteSystem;
    private final GodsVsMortalsPlugin plugin;

    public VoteCommand(GodsVsMortalsPlugin plugin, VoteSystem voteSystem) {
        this.plugin = plugin;
        this.voteSystem = voteSystem;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can vote.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(Component.text("Usage: /vote <player>", NamedTextColor.YELLOW));
            return true;
        }
        voteSystem.castVote(player, args[0]);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            EventPhase phase = plugin.getEventManager().getCurrentPhase();
            // Only show candidates during VOTING phase; exclude already-elected gods
            Set<UUID> godUUIDs = Set.copyOf(plugin.getEventManager().getState().getGodUUIDs());
            return Bukkit.getOnlinePlayers().stream()
                    .filter(p -> phase == EventPhase.VOTING)
                    .filter(p -> !godUUIDs.contains(p.getUniqueId()))
                    .filter(p -> !(sender instanceof Player sp) || !p.getUniqueId().equals(sp.getUniqueId()))
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
