package com.example.godsvsmortals.command;

import com.example.godsvsmortals.EventManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Handles the /gvm admin command.
 *
 * <p>Sub-commands:
 * <ul>
 *   <li>/gvm start – begins the event (requires op / godsvsmortals.admin)</li>
 *   <li>/gvm stop  – immediately ends the event (requires op / godsvsmortals.admin)</li>
 * </ul>
 *
 * Requirements: 1.1, 1.2
 */
public class GvmCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "godsvsmortals.admin";

    private final EventManager eventManager;

    public GvmCommand(EventManager eventManager) {
        this.eventManager = eventManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {

        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(Component.text("You do not have permission to use this command.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "start" -> {
                com.example.godsvsmortals.enums.EventPhase before = eventManager.getCurrentPhase();
                eventManager.startEvent();
                if (eventManager.getCurrentPhase() != before) {
                    sender.sendMessage(Component.text("Event started.", NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text("Event is already running.", NamedTextColor.RED));
                }
            }
            case "stop" -> {
                eventManager.stopEvent();
                sender.sendMessage(Component.text("Event stopped and state reset.", NamedTextColor.YELLOW));
            }
            default -> sendUsage(sender);
        }

        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("Usage: /gvm <start|stop>", NamedTextColor.YELLOW));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                 @NotNull Command command,
                                                 @NotNull String label,
                                                 @NotNull String[] args) {
        if (args.length == 1) {
            com.example.godsvsmortals.enums.EventPhase phase = eventManager.getCurrentPhase();
            boolean running = phase != com.example.godsvsmortals.enums.EventPhase.ENDED;
            List<String> options = running ? List.of("stop") : List.of("start");
            return options.stream()
                    .filter(o -> o.startsWith(args[0].toLowerCase()))
                    .collect(java.util.stream.Collectors.toList());
        }
        return List.of();
    }
}
