package com.example.godsvsmortals;

import com.example.godsvsmortals.enums.TemplateTier;
import com.example.godsvsmortals.model.Shrine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Detects shrine construction and temple upgrades via block events.
 *
 * <p>Shrine pattern: 3×3 base of STONE_BRICKS with a GOLD_BLOCK in the center.
 * Temple tiers are detected by the surrounding blocks (IRON_BLOCK, GOLD_BLOCK, DIAMOND_BLOCK).
 *
 * <p>Requirements: 3.1–3.9, 4.1–4.5
 */
public class ShrineDetector implements Listener {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /** The 8 relative offsets (dx, dz) of the surrounding 3×3 blocks (excluding center). */
    private static final int[][] SURROUND_OFFSETS = {
            {-1, -1}, {0, -1}, {1, -1},
            {-1,  0},           {1,  0},
            {-1,  1}, {0,  1}, {1,  1}
    };

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final GodsVsMortalsPlugin plugin;
    private final Logger logger;

    /** ownerUUID → active Shrine */
    private final Map<UUID, Shrine> shrinesByOwner = new HashMap<>();

    /** core Location key → active Shrine */
    private final Map<String, Shrine> shrinesByLocation = new HashMap<>();

    /** shrineId → active BukkitTask for particle spawning */
    private final Map<String, BukkitTask> particleTasks = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public ShrineDetector(GodsVsMortalsPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        loadAllShrines();
    }

    /**
     * Package-private constructor for testing: skips loading shrines from disk.
     */
    ShrineDetector(GodsVsMortalsPlugin plugin, boolean skipLoad) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        if (!skipLoad) {
            loadAllShrines();
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the active shrine owned by the given player, or null.
     * Requirement 3.5
     */
    public Shrine getShrineByOwner(UUID ownerUUID) {
        Shrine s = shrinesByOwner.get(ownerUUID);
        return (s != null && s.isActive()) ? s : null;
    }

    /**
     * Returns the active shrine whose core block is at the given location, or null.
     */
    public Shrine getShrineAtLocation(Location coreLoc) {
        Shrine s = shrinesByLocation.get(locationKey(coreLoc));
        return (s != null && s.isActive()) ? s : null;
    }

    /**
     * Registers a new shrine. Enforces one-shrine-per-mortal (Req 3.6).
     * If the owner already has an active shrine, the registration is rejected.
     */
    public void registerShrine(Shrine shrine) {
        UUID owner = shrine.getOwnerUUID();
        if (shrinesByOwner.containsKey(owner) && shrinesByOwner.get(owner).isActive()) {
            logger.warning("registerShrine() called but owner " + owner + " already has an active shrine.");
            return;
        }
        shrine.setActive(true);
        shrinesByOwner.put(owner, shrine);
        shrinesByLocation.put(locationKey(shrine.getCoreLocation()), shrine);
        saveShrine(shrine);
        startParticleTask(shrine);
    }

    /**
     * Deactivates and removes a shrine. Removes particles and notifies the owner (Req 3.8, 3.9).
     * MED #26 fix: delete the YAML file of destroyed shrines.
     */
    public void destroyShrine(Shrine shrine, UUID destroyerUUID) {
        shrine.setActive(false);
        shrinesByOwner.remove(shrine.getOwnerUUID());
        shrinesByLocation.remove(locationKey(shrine.getCoreLocation()));
        stopParticleTask(shrine.getId());

        // MED #26 fix: delete the shrine file instead of saving with active=false
        File shrinesDir = new File(plugin.getDataFolder(), "shrines");
        File shrineFile = new File(shrinesDir, shrine.getId() + ".yml");
        if (shrineFile.exists()) {
            shrineFile.delete();
        }

        // Log destroyer (Req 3.9)
        logger.info("Shrine " + shrine.getId() + " destroyed by " + destroyerUUID
                + " (owner=" + shrine.getOwnerUUID() + ")");

        // Notify owner if online (Req 3.8), or queue notification if offline (Req 21.1)
        Player owner = plugin.getServer().getPlayer(shrine.getOwnerUUID());
        String destroyerName = resolvePlayerName(destroyerUUID);
        String message = "☠ Your shrine has been destroyed by " + destroyerName + "!";

        if (owner != null && owner.isOnline()) {
            owner.sendMessage(Component.text(message, NamedTextColor.RED));
        } else {
            plugin.getNotificationSystem().queue(shrine.getOwnerUUID(), message);
        }
    }

    // -------------------------------------------------------------------------
    // Bukkit Event Listeners
    // -------------------------------------------------------------------------

    /**
     * Handles block placement. Checks if the placed block completes a shrine pattern
     * or upgrades an existing shrine's temple tier.
     * HIGH #16 fix: check all 8 surrounding positions too, not just the gold block.
     * HIGH #17 fix: check temple upgrade BEFORE new shrine detection for gold blocks.
     * (Req 3.1, 3.2, 4.1)
     */
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();
        Player player = event.getPlayer();

        // HIGH #17 fix: check temple upgrade first for gold blocks near existing shrines
        if (placed.getType() == Material.GOLD_BLOCK || placed.getType() == Material.IRON_BLOCK
                || placed.getType() == Material.DIAMOND_BLOCK) {
            // If this block is adjacent to an existing shrine core, it's an upgrade attempt
            boolean isUpgrade = isAdjacentToExistingShrine(placed);
            if (isUpgrade) {
                checkTempleUpgrade(placed);
                return;
            }
        }

        // HIGH #16 fix: check shrine completion for gold block OR any of the 8 surrounding stone bricks
        if (placed.getType() == Material.GOLD_BLOCK) {
            if (isValidShrinePattern(placed.getLocation())) {
                handleShrineCompletion(player, placed.getLocation());
                return;
            }
        } else if (placed.getType() == Material.STONE_BRICKS) {
            // Check if any adjacent gold block now forms a complete shrine
            for (int[] offset : SURROUND_OFFSETS) {
                Location candidateCenter = placed.getLocation().clone().add(-offset[0], 0, -offset[1]);
                if (candidateCenter.getBlock().getType() == Material.GOLD_BLOCK
                        && isValidShrinePattern(candidateCenter)) {
                    handleShrineCompletion(player, candidateCenter);
                    return;
                }
            }
        }

        // Check if this placement upgrades a nearby shrine's temple tier
        checkTempleUpgrade(placed);
    }

    /** Returns true if the placed block is adjacent to an existing active shrine core. */
    private boolean isAdjacentToExistingShrine(Block placed) {
        int bx = placed.getX(), by = placed.getY(), bz = placed.getZ();
        World world = placed.getWorld();
        for (int[] offset : SURROUND_OFFSETS) {
            Location candidateCore = new Location(world, bx + offset[0], by, bz + offset[1]);
            if (getShrineAtLocation(candidateCore) != null) return true;
        }
        return false;
    }

    /**
     * Handles block breaking. Detects core block removal and temple downgrade. (Req 3.8, 4.5)
     */
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block broken = event.getBlock();
        Player breaker = event.getPlayer();

        // Check if the broken block is a shrine core (gold block at center)
        Shrine shrine = getShrineAtLocation(broken.getLocation());
        if (shrine != null) {
            destroyShrine(shrine, breaker.getUniqueId());
            return;
        }

        // Check if the broken block is a temple upgrade block surrounding a shrine
        checkTempleDowngrade(broken);
    }

    // -------------------------------------------------------------------------
    // Shrine Pattern Validation
    // -------------------------------------------------------------------------

    /**
     * Returns true if the 3×3 area centered on {@code center} matches the shrine pattern:
     * 8 surrounding blocks = STONE_BRICKS, center = GOLD_BLOCK. (Req 3.1, 3.2)
     */
    public boolean isValidShrinePattern(Location center) {
        World world = center.getWorld();
        if (world == null) return false;

        // Center must be GOLD_BLOCK
        Block centerBlock = world.getBlockAt(center);
        if (centerBlock.getType() != Material.GOLD_BLOCK) return false;

        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        // All 8 surrounding blocks must be STONE_BRICKS
        for (int[] offset : SURROUND_OFFSETS) {
            Block b = world.getBlockAt(cx + offset[0], cy, cz + offset[1]);
            if (b.getType() != Material.STONE_BRICKS) return false;
        }
        return true;
    }

    /**
     * Pure pattern validation using a block-lookup function (for testing without a live World).
     * The function receives int[]{x, y, z} and returns the Material at that position.
     * Package-private for testing.
     */
    static boolean isValidShrinePatternPure(int cx, int cy, int cz,
                                             java.util.function.Function<int[], Material> blockLookup) {
        if (blockLookup.apply(new int[]{cx, cy, cz}) != Material.GOLD_BLOCK) return false;
        for (int[] offset : SURROUND_OFFSETS) {
            if (blockLookup.apply(new int[]{cx + offset[0], cy, cz + offset[1]}) != Material.STONE_BRICKS) {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Shrine Completion
    // -------------------------------------------------------------------------

    private void handleShrineCompletion(Player player, Location coreLoc) {
        UUID ownerUUID = player.getUniqueId();

        // One-shrine-per-mortal enforcement (Req 3.6, 3.7)
        if (getShrineByOwner(ownerUUID) != null) {
            player.sendMessage(Component.text(
                    "You already have an active shrine! Destroy it before building a new one.",
                    NamedTextColor.RED));
            return;
        }

        // Create shrine with BASIC tier, no god yet
        String shrineId = UUID.randomUUID().toString();
        Shrine shrine = new Shrine(
                shrineId,
                ownerUUID,
                null,           // dedicatedGodUUID set after god selection
                coreLoc,
                TemplateTier.BASIC,
                false,          // not active until dedicated
                System.currentTimeMillis(),
                System.currentTimeMillis()
        );

        // Prompt player to choose a god via chat menu (Req 3.4)
        promptGodSelection(player, shrine);
    }

    /**
     * Sends a chat menu to the player asking them to choose a god to dedicate the shrine to.
     * For now, lists the elected gods from EventState. The player responds by clicking
     * a chat component (or typing a number). This is a simplified text-based menu.
     */
    private void promptGodSelection(Player player, Shrine shrine) {
        List<UUID> godUUIDs = plugin.getEventManager().getState().getGodUUIDs();

        player.sendMessage(Component.text(
                "✦ Shrine structure detected! Choose a god to dedicate your shrine to:",
                NamedTextColor.GOLD));

        if (godUUIDs == null || godUUIDs.isEmpty()) {
            player.sendMessage(Component.text(
                    "  No gods have been elected yet. Your shrine will be registered without a dedication.",
                    NamedTextColor.YELLOW));
            // Register without a god for now
            registerShrine(shrine);
            return;
        }

        for (int i = 0; i < godUUIDs.size(); i++) {
            UUID godUUID = godUUIDs.get(i);
            String godName = resolvePlayerName(godUUID);
            player.sendMessage(Component.text(
                    "  [" + (i + 1) + "] " + godName, NamedTextColor.AQUA));
        }

        player.sendMessage(Component.text(
                "Type /shrine dedicate <number> to choose. (e.g. /shrine dedicate 1)",
                NamedTextColor.GRAY));

        // Store pending shrine for the player to confirm via command
        // For this implementation, we auto-register with the first god if only one exists,
        // otherwise we store it as pending. A full chat-click implementation would use
        // ClickEvent on the component, but that requires a command handler.
        // We register immediately with null god and let the player use /shrine dedicate later.
        registerShrine(shrine);
        player.sendMessage(Component.text(
                "Your shrine has been registered! Use /shrine dedicate <number> to choose your god.",
                NamedTextColor.GREEN));
    }

    // -------------------------------------------------------------------------
    // Temple Tier Detection and Upgrade/Downgrade
    // -------------------------------------------------------------------------

    /**
     * Checks if the placed block upgrades a nearby shrine's temple tier.
     * MED #32 fix: also check Y±1 for blocks placed on slopes.
     * (Req 4.1, 4.2, 4.3, 4.4)
     */
    private void checkTempleUpgrade(Block placed) {
        Material type = placed.getType();
        if (type != Material.IRON_BLOCK && type != Material.GOLD_BLOCK && type != Material.DIAMOND_BLOCK) {
            return;
        }

        int bx = placed.getX();
        int by = placed.getY();
        int bz = placed.getZ();
        World world = placed.getWorld();

        // MED #32 fix: check Y, Y-1, Y+1 to handle shrines on slopes
        for (int dy = -1; dy <= 1; dy++) {
            for (int[] offset : SURROUND_OFFSETS) {
                Location candidateCore = new Location(world, bx + offset[0], by + dy, bz + offset[1]);
                Shrine shrine = getShrineAtLocation(candidateCore);
                if (shrine != null) {
                    TemplateTier newTier = detectTier(shrine.getCoreLocation());
                    if (newTier != shrine.getTier()) {
                        shrine.setTier(newTier);
                        saveShrine(shrine);
                        updateParticleTask(shrine);
                        notifyTierChange(shrine, newTier);
                    }
                    return;
                }
            }
        }
    }

    /**
     * Checks if the broken block downgrades a nearby shrine's temple tier. (Req 4.5)
     */
    private void checkTempleDowngrade(Block broken) {
        Material type = broken.getType();
        if (type != Material.IRON_BLOCK && type != Material.GOLD_BLOCK && type != Material.DIAMOND_BLOCK) {
            return;
        }

        int bx = broken.getX();
        int by = broken.getY();
        int bz = broken.getZ();
        World world = broken.getWorld();

        for (int[] offset : SURROUND_OFFSETS) {
            Location candidateCore = new Location(world, bx + offset[0], by, bz + offset[1]);
            Shrine shrine = getShrineAtLocation(candidateCore);
            if (shrine != null) {
                // Re-detect tier after the block is removed (it will be air at this point)
                TemplateTier newTier = detectTierExcluding(shrine.getCoreLocation(), broken.getLocation());
                if (newTier != shrine.getTier()) {
                    shrine.setTier(newTier);
                    saveShrine(shrine);
                    updateParticleTask(shrine);
                    notifyTierChange(shrine, newTier);
                }
                return;
            }
        }
    }

    /**
     * Detects the temple tier by examining the 8 surrounding blocks of the shrine core.
     * Returns the highest tier for which ALL 8 surrounding blocks match. (Req 4.2)
     */
    public TemplateTier detectTier(Location coreLoc) {
        return detectTierExcluding(coreLoc, null);
    }

    /**
     * Detects the temple tier, optionally treating {@code excludedLoc} as AIR
     * (used during block-break events before the block is actually removed).
     */
    private TemplateTier detectTierExcluding(Location coreLoc, Location excludedLoc) {
        World world = coreLoc.getWorld();
        if (world == null) return TemplateTier.BASIC;

        int cx = coreLoc.getBlockX();
        int cy = coreLoc.getBlockY();
        int cz = coreLoc.getBlockZ();

        String excludedKey = excludedLoc != null ? locationKey(excludedLoc) : null;

        int ironCount = 0, goldCount = 0, diamondCount = 0;

        for (int[] offset : SURROUND_OFFSETS) {
            Location loc = new Location(world, cx + offset[0], cy, cz + offset[1]);
            Material mat;
            if (excludedKey != null && locationKey(loc).equals(excludedKey)) {
                mat = Material.AIR;
            } else {
                mat = world.getBlockAt(loc).getType();
            }
            if (mat == Material.IRON_BLOCK) ironCount++;
            else if (mat == Material.GOLD_BLOCK) goldCount++;
            else if (mat == Material.DIAMOND_BLOCK) diamondCount++;
        }

        // All 8 surrounding blocks must match for a tier upgrade
        if (diamondCount == 8) return TemplateTier.DIAMOND;
        if (goldCount == 8) return TemplateTier.GOLD;
        if (ironCount == 8) return TemplateTier.IRON;
        return TemplateTier.BASIC;
    }

    private void notifyTierChange(Shrine shrine, TemplateTier newTier) {
        Player owner = plugin.getServer().getPlayer(shrine.getOwnerUUID());
        if (owner != null && owner.isOnline()) {
            owner.sendMessage(Component.text(
                    "✦ Your shrine has been upgraded to " + newTier.name() + " tier!",
                    NamedTextColor.GOLD));
        }
        logger.info("Shrine " + shrine.getId() + " tier changed to " + newTier);
    }

    // -------------------------------------------------------------------------
    // Particle Effects
    // -------------------------------------------------------------------------

    /**
     * Starts a repeating BukkitTask that spawns VILLAGER_HAPPY particles above the shrine core.
     * Requirement 3.3
     */
    private void startParticleTask(Shrine shrine) {
        stopParticleTask(shrine.getId());

        Location coreLoc = shrine.getCoreLocation();
        if (coreLoc == null || coreLoc.getWorld() == null) return;
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!shrine.isActive()) {
                stopParticleTask(shrine.getId());
                return;
            }
            spawnParticles(shrine);
        }, 0L, 40L); // every 2 seconds (40 ticks)

        particleTasks.put(shrine.getId(), task);
    }

    private void spawnParticles(Shrine shrine) {
        Location coreLoc = shrine.getCoreLocation();
        if (coreLoc == null || coreLoc.getWorld() == null) return;

        // Spawn above the core block
        Location particleLoc = coreLoc.clone().add(0.5, 1.5, 0.5);
        Particle particle = getTierParticle(shrine.getTier());
        coreLoc.getWorld().spawnParticle(particle, particleLoc, 10, 0.3, 0.3, 0.3, 0.01);
    }

    /**
     * Returns the particle type for the given tier. (Req 4.4)
     */
    private Particle getTierParticle(TemplateTier tier) {
        return switch (tier) {
            case DIAMOND -> Particle.END_ROD;
            case GOLD -> Particle.FLAME;
            case IRON -> Particle.SMOKE;
            default -> Particle.HAPPY_VILLAGER;
        };
    }

    private void stopParticleTask(String shrineId) {
        BukkitTask task = particleTasks.remove(shrineId);
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
    }

    private void updateParticleTask(Shrine shrine) {
        // Restart particle task to pick up new tier particle
        stopParticleTask(shrine.getId());
        if (shrine.isActive()) {
            startParticleTask(shrine);
        }
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private void loadAllShrines() {
        File shrinesDir = new File(plugin.getDataFolder(), "shrines");
        if (!shrinesDir.exists()) return;

        File[] files = shrinesDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;

        for (File file : files) {
            Shrine shrine = Shrine.load(file, logger);
            if (shrine != null && shrine.isActive()) {
                shrinesByOwner.put(shrine.getOwnerUUID(), shrine);
                if (shrine.getCoreLocation() != null) {
                    shrinesByLocation.put(locationKey(shrine.getCoreLocation()), shrine);
                }
                startParticleTask(shrine);
            }
        }
        logger.info("Loaded " + shrinesByOwner.size() + " active shrine(s).");
    }

    private void saveShrine(Shrine shrine) {
        File shrinesDir = new File(plugin.getDataFolder(), "shrines");
        shrinesDir.mkdirs();
        File file = new File(shrinesDir, shrine.getId() + ".yml");
        try {
            shrine.save(file);
        } catch (IOException e) {
            logger.severe("Failed to save shrine " + shrine.getId() + ": " + e.getMessage());
        }
    }

    /**
     * Saves all active shrines to disk synchronously.
     * Called during plugin shutdown.
     */
    public void saveAllShrines() {
        File shrinesDir = new File(plugin.getDataFolder(), "shrines");
        shrinesDir.mkdirs();
        for (Shrine shrine : shrinesByOwner.values()) {
            File file = new File(shrinesDir, shrine.getId() + ".yml");
            try {
                shrine.save(file);
            } catch (IOException e) {
                logger.severe("ShrineDetector: failed to save shrine " + shrine.getId() + ": " + e.getMessage());
            }
        }
        logger.info("ShrineDetector: saved " + shrinesByOwner.size() + " shrine(s).");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String locationKey(Location loc) {
        if (loc == null) return "null";
        String worldName = loc.getWorld() != null ? loc.getWorld().getName() : "null";
        return worldName + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    private String resolvePlayerName(UUID uuid) {
        if (uuid == null) return "Unknown";
        var player = plugin.getServer().getPlayer(uuid);
        if (player != null) return player.getName();
        var offlinePlayer = plugin.getServer().getOfflinePlayer(uuid);
        String name = offlinePlayer.getName();
        return name != null ? name : uuid.toString();
    }

    // -------------------------------------------------------------------------
    // Package-private accessors for testing
    // -------------------------------------------------------------------------

    /** Returns a read-only view of the owner→shrine map (for tests). */
    Map<UUID, Shrine> getShrinesByOwner() {
        return Collections.unmodifiableMap(shrinesByOwner);
    }

    /** Returns a read-only view of the location→shrine map (for tests). */
    Map<String, Shrine> getShrinesByLocation() {
        return Collections.unmodifiableMap(shrinesByLocation);
    }
}
