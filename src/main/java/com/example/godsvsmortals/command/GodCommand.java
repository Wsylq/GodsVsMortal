package com.example.godsvsmortals.command;

import com.example.godsvsmortals.PowerSystem;
import com.example.godsvsmortals.enums.BlessingType;
import com.example.godsvsmortals.enums.CurseType;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Handles the /god command for god powers.
 *
 * <p>Sub-commands:
 * <ul>
 *   <li>/god bless &lt;player&gt; &lt;power&gt;   – apply a blessing (Req 6.1)</li>
 *   <li>/god curse &lt;shrine_id&gt; &lt;type&gt;  – apply a curse (Req 9.1)</li>
 *   <li>/god rival &lt;god_name&gt;               – declare a rival (Req 10.1)</li>
 *   <li>/god unrival &lt;god_name&gt;             – remove a rival (Req 10.5)</li>
 *   <li>/god truce &lt;god_name&gt;               – propose a truce (Req 23.1)</li>
 *   <li>/god truce accept                        – accept a truce (Req 23.2)</li>
 * </ul>
 */
public class GodCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "godsvsmortals.god";

    private final PowerSystem powerSystem;

    public GodCommand(PowerSystem powerSystem) {
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
            sendUsage(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "bless" -> handleBless(player, args);
            case "curse" -> handleCurse(player, args);
            case "rival" -> handleRival(player, args);
            case "unrival" -> handleUnrival(player, args);
            case "truce" -> handleTruce(player, args);
            case "avatar" -> handleAvatar(player);
            default -> sendUsage(player);
        }

        return true;
    }

    // -------------------------------------------------------------------------
    // Sub-command handlers
    // -------------------------------------------------------------------------

    private void handleBless(Player god, String[] args) {
        if (args.length < 3) {
            god.sendMessage(Component.text("Usage: /god bless <player> <power>", NamedTextColor.YELLOW));
            return;
        }

        String targetName = args[1];
        String powerArg = args[2].toUpperCase();

        BlessingType type;
        try {
            type = BlessingType.valueOf(powerArg);
        } catch (IllegalArgumentException e) {
            god.sendMessage(Component.text("Unknown blessing type: " + args[2] + ". Valid: golden_apple, summon_wolf", NamedTextColor.RED));
            return;
        }

        Player target = god.getServer().getPlayer(targetName);
        if (target == null) {
            god.sendMessage(Component.text("Player not found: " + targetName, NamedTextColor.RED));
            return;
        }

        powerSystem.applyBlessing(god.getUniqueId(), target.getUniqueId(), type);
    }

    private void handleCurse(Player god, String[] args) {
        if (args.length < 3) {
            god.sendMessage(Component.text("Usage: /god curse <shrine_id> <curse_type>", NamedTextColor.YELLOW));
            return;
        }

        String shrineId = args[1];
        String curseArg = args[2].toUpperCase();

        CurseType type;
        try {
            type = CurseType.valueOf(curseArg);
        } catch (IllegalArgumentException e) {
            god.sendMessage(Component.text("Unknown curse type: " + args[2] + ". Valid: fire, silverfish, debuff", NamedTextColor.RED));
            return;
        }

        powerSystem.applyCurse(god.getUniqueId(), shrineId, type);
    }

    private void handleRival(Player god, String[] args) {
        if (args.length < 2) {
            god.sendMessage(Component.text("Usage: /god rival <god_name>", NamedTextColor.YELLOW));
            return;
        }

        String rivalName = args[1];
        Player rival = god.getServer().getPlayer(rivalName);
        if (rival == null) {
            god.sendMessage(Component.text("Player not found: " + rivalName, NamedTextColor.RED));
            return;
        }

        if (rival.getUniqueId().equals(god.getUniqueId())) {
            god.sendMessage(Component.text("You cannot declare yourself as a rival.", NamedTextColor.RED));
            return;
        }

        powerSystem.declareRivalry(god.getUniqueId(), rival.getUniqueId());
    }

    private void handleUnrival(Player god, String[] args) {
        if (args.length < 2) {
            god.sendMessage(Component.text("Usage: /god unrival <god_name>", NamedTextColor.YELLOW));
            return;
        }

        String rivalName = args[1];
        Player rival = god.getServer().getPlayer(rivalName);
        if (rival == null) {
            // Try offline player lookup by name
            UUID rivalUUID = resolveOfflineUUID(god, rivalName);
            if (rivalUUID == null) return;
            powerSystem.removeRivalry(god.getUniqueId(), rivalUUID);
            god.sendMessage(Component.text("Rivalry with " + rivalName + " removed.", NamedTextColor.GREEN));
            return;
        }

        powerSystem.removeRivalry(god.getUniqueId(), rival.getUniqueId());
        god.sendMessage(Component.text("Rivalry with " + rival.getName() + " removed.", NamedTextColor.GREEN));
    }

    private void handleTruce(Player god, String[] args) {
        if (args.length < 2) {
            god.sendMessage(Component.text("Usage: /god truce <god_name> | /god truce accept", NamedTextColor.YELLOW));
            return;
        }

        if (args[1].equalsIgnoreCase("accept")) {
            powerSystem.acceptTruce(god.getUniqueId());
            return;
        }

        String targetName = args[1];
        Player target = god.getServer().getPlayer(targetName);
        if (target == null) {
            god.sendMessage(Component.text("Player not found: " + targetName, NamedTextColor.RED));
            return;
        }

        if (target.getUniqueId().equals(god.getUniqueId())) {
            god.sendMessage(Component.text("You cannot propose a truce with yourself.", NamedTextColor.RED));
            return;
        }

        powerSystem.proposeTruce(god.getUniqueId(), target.getUniqueId());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UUID resolveOfflineUUID(Player sender, String name) {
        @SuppressWarnings("deprecation")
        var offlinePlayer = sender.getServer().getOfflinePlayer(name);
        if (!offlinePlayer.hasPlayedBefore()) {
            sender.sendMessage(Component.text("Player not found: " + name, NamedTextColor.RED));
            return null;
        }
        return offlinePlayer.getUniqueId();
    }

    private void sendUsage(Player player) {
        player.sendMessage(Component.text(
                "Usage: /god <bless|curse|rival|unrival|truce|avatar> [args...]", NamedTextColor.YELLOW));
    }

    private void handleAvatar(Player god) {
        boolean result = powerSystem.activateAvatarMode(god.getUniqueId());
        if (!result) {
            // Error messages are sent inside activateAvatarMode
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                 @NotNull Command command,
                                                 @NotNull String label,
                                                 @NotNull String[] args) {
        if (args.length == 1) {
            return Arrays.asList("bless", "curse", "rival", "unrival", "truce", "avatar");
        }

        if (args.length == 2) {
            String subCmd = args[0].toLowerCase();
            if (subCmd.equals("bless") || subCmd.equals("rival") || subCmd.equals("unrival")) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if (subCmd.equals("truce")) {
                List<String> options = new ArrayList<>();
                options.add("accept");
                options.addAll(Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList()));
                return options;
            }
            if (subCmd.equals("curse")) {
                return List.of(); // shrine IDs are UUIDs, no tab completion
            }
        }

        if (args.length == 3) {
            String subCmd = args[0].toLowerCase();
            if (subCmd.equals("bless")) {
                return Arrays.stream(BlessingType.values())
                        .map(Enum::name)
                        .map(String::toLowerCase)
                        .filter(name -> name.startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if (subCmd.equals("curse")) {
                return Arrays.stream(CurseType.values())
                        .map(Enum::name)
                        .map(String::toLowerCase)
                        .filter(name -> name.startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        return List.of();
    }
}
