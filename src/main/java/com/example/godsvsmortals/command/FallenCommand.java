package com.example.godsvsmortals.command;

import com.example.godsvsmortals.PowerSystem;
import com.example.godsvsmortals.model.MortalData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class FallenCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "godsvsmortals.fallen";

    private final PowerSystem powerSystem;

    public FallenCommand(PowerSystem powerSystem) {
        this.powerSystem = powerSystem;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }
        MortalData mortalData = powerSystem.getMortalData(player.getUniqueId());
        if (mortalData == null || !mortalData.isFallenGod()) {
            player.sendMessage(Component.text("You are not the Fallen God.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(Component.text("Usage: /fallen steal", NamedTextColor.YELLOW));
            return true;
        }
        if (args[0].equalsIgnoreCase("steal")) {
            powerSystem.executeFallenSteal(player.getUniqueId());
        } else {
            player.sendMessage(Component.text("Usage: /fallen steal", NamedTextColor.YELLOW));
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && sender instanceof Player player) {
            MortalData mortalData = powerSystem.getMortalData(player.getUniqueId());
            if (mortalData != null && mortalData.isFallenGod()) {
                return List.of("steal").stream()
                        .filter(s -> s.startsWith(args[0].toLowerCase()))
                        .toList();
            }
        }
        return List.of();
    }
}
