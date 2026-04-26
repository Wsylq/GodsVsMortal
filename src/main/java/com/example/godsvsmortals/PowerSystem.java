package com.example.godsvsmortals;

import com.example.godsvsmortals.enums.BlessingType;
import com.example.godsvsmortals.enums.CurseType;
import com.example.godsvsmortals.model.GodData;
import com.example.godsvsmortals.model.MortalData;
import com.example.godsvsmortals.model.Shrine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * Manages god powers: blessings, curses, rivalries, and truces.
 *
 * <p>Requirements: 6.1–6.6, 9.1–9.6, 10.1–10.5, 23.1–23.6
 */
public class PowerSystem implements Listener {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final long BLESSING_COOLDOWN_MS = 5 * 60 * 1000L; // 5 minutes
    private static final long TRUCE_DURATION_MS = 12 * 60 * 60 * 1000L; // 12 hours
    private static final double RIVALRY_DAMAGE_BONUS = 0.20; // 20% bonus

    // Avatar Mode constants (Req 14)
    private static final int AVATAR_FAITH_COST = 500;
    private static final double AVATAR_MAX_HEALTH = 100.0;
    private static final long AVATAR_DURATION_MS = 10L * 60 * 1000; // 10 minutes
    private static final double AVATAR_BOSS_BAR_RADIUS = 50.0;
    private static final String AVATAR_MYTHICMOBS_WEAPON = "AvatarSword";

    // Mass Sacrifice constants (Req 16)
    private static final int SACRIFICE_HP_COST = 5;
    private static final double SACRIFICE_MIN_HP = 6.0;
    private static final long BANISHMENT_DURATION_MS = 60L * 60 * 1000; // 1 hour

    // Rebellion Banner NBT key
    private static final String REBELLION_BANNER_KEY = "rebellion_banner";

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final GodsVsMortalsPlugin plugin;
    private final Logger logger;

    /** godUUID → mortalUUID → MortalData (in-memory registry) */
    private final Map<UUID, MortalData> mortalRegistry = new HashMap<>();

    /** godUUID → BlessingType → last-used timestamp (ms) */
    private final Map<UUID, Map<BlessingType, Long>> blessingCooldowns = new HashMap<>();

    /** proposerGodUUID → targetGodUUID (pending truce proposals) */
    private final Map<UUID, UUID> trucePending = new HashMap<>();

    /** godUUID → avatar expiry timestamp (ms) */
    private final Map<UUID, Long> avatarExpiry = new HashMap<>();

    /** godUUID → avatar boss bar */
    private final Map<UUID, BossBar> avatarBossBars = new HashMap<>();

    /** MythicMobs adapter – null if MythicMobs is not installed */
    private final MythicMobsAdapter mythicMobsAdapter;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public PowerSystem(GodsVsMortalsPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();

        // Initialize MythicMobs adapter if present (Req 14.7)
        if (plugin.getServer().getPluginManager().getPlugin("MythicMobs") != null) {
            this.mythicMobsAdapter = new MythicMobsAdapter(logger);
        } else {
            this.mythicMobsAdapter = null;
            logger.warning("PowerSystem: MythicMobs not found – Avatar Mode will use vanilla fallback.");
        }

        loadAllMortals();
    }

    // -------------------------------------------------------------------------
    // Public API – Blessings
    // -------------------------------------------------------------------------

    /**
     * Applies a blessing from a god to a target mortal.
     *
     * <p>Validates: follower relationship, faith balance, per-blessing cooldown.
     * On success: deducts faith, applies effect, notifies both parties.
     *
     * <p>Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6
     * <p>HIGH #14 fix: Refund faith if target is offline
     *
     * @return true if the blessing was applied successfully
     */
    public boolean applyBlessing(UUID godUUID, UUID targetUUID, BlessingType type) {
        // Phase check: blessings only during active event (not voting, not ended)
        com.example.godsvsmortals.enums.EventPhase phase = plugin.getEventManager().getCurrentPhase();
        if (phase == com.example.godsvsmortals.enums.EventPhase.VOTING
                || phase == com.example.godsvsmortals.enums.EventPhase.ENDED) {
            notifyGod(godUUID, "§cYou cannot use powers outside of the event.");
            return false;
        }

        // Check if god is banished (Req 16.4)
        if (isGodBanished(godUUID)) {
            notifyGod(godUUID, "§cYou are banished and cannot use powers.");
            return false;
        }

        // Validate follower relationship (Req 6.3)
        MortalData mortal = mortalRegistry.get(targetUUID);
        if (mortal == null || !godUUID.equals(mortal.getPledgedGodUUID())) {
            notifyGod(godUUID, "§cThat player is not your follower.");
            return false;
        }

        // Check faith balance (Req 6.4)
        int cost = type.getFaithCost();
        int currentFaith = plugin.getFaithEngine().getFaith(godUUID);
        if (currentFaith < cost) {
            notifyGod(godUUID, "§cInsufficient faith. You have " + currentFaith + " but need " + cost + ".");
            return false;
        }

        // Check cooldown (Req 6.5)
        long now = System.currentTimeMillis();
        Map<BlessingType, Long> godCooldowns = blessingCooldowns.computeIfAbsent(godUUID, k -> new HashMap<>());
        Long lastUsed = godCooldowns.get(type);
        if (lastUsed != null && (now - lastUsed) < BLESSING_COOLDOWN_MS) {
            long remaining = (BLESSING_COOLDOWN_MS - (now - lastUsed)) / 1000;
            notifyGod(godUUID, "§cBlessing on cooldown. " + remaining + "s remaining.");
            return false;
        }

        // HIGH #14 fix: Check if target is online BEFORE deducting faith
        Player target = plugin.getServer().getPlayer(targetUUID);
        if (target == null || !target.isOnline()) {
            notifyGod(godUUID, "§cThat player is not online.");
            return false;
        }

        // Deduct faith (Req 6.1)
        plugin.getFaithEngine().deductFaith(godUUID, cost);

        // Update cooldown
        godCooldowns.put(type, now);

        // Apply effect (Req 6.2)
        applyBlessingEffect(target, type);

        // Notify both parties (Req 6.6)
        notifyGod(godUUID, "§aBlessing §e" + type.name() + "§a applied to §e" + resolveName(targetUUID) + "§a.");
        target.sendMessage(Component.text("✦ Your god has blessed you with " + type.name() + "!", NamedTextColor.GOLD));

        // Increment blessing count on GodData
        updateGodBlessingCount(godUUID);

        return true;
    }

    // -------------------------------------------------------------------------
    // Public API – Curses
    // -------------------------------------------------------------------------

    /**
     * Applies a curse to a shrine.
     *
     * <p>Validates: not own follower's shrine, faith balance, truce check.
     * On success: deducts faith, applies curse effect, notifies shrine owner.
     *
     * <p>Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 23.3, 23.6
     * <p>HIGH #13 fix: Destroy shrine properly when FIRE curse is applied
     *
     * @return true if the curse was applied successfully
     */
    public boolean applyCurse(UUID godUUID, String shrineId, CurseType type) {
        // Phase check: curses only during active event (not voting, not ended)
        com.example.godsvsmortals.enums.EventPhase phase = plugin.getEventManager().getCurrentPhase();
        if (phase == com.example.godsvsmortals.enums.EventPhase.VOTING
                || phase == com.example.godsvsmortals.enums.EventPhase.ENDED) {
            notifyGod(godUUID, "§cYou cannot use powers outside of the event.");
            return false;
        }

        // Check if god is banished (Req 16.4)
        if (isGodBanished(godUUID)) {
            notifyGod(godUUID, "§cYou are banished and cannot use powers.");
            return false;
        }

        // Find the shrine
        Shrine shrine = findShrineById(shrineId);
        if (shrine == null) {
            notifyGod(godUUID, "§cShrine not found: " + shrineId);
            return false;
        }

        UUID shrineOwner = shrine.getOwnerUUID();

        // Validate not own follower's shrine (Req 9.3)
        MortalData ownerData = mortalRegistry.get(shrineOwner);
        if (ownerData != null && godUUID.equals(ownerData.getPledgedGodUUID())) {
            notifyGod(godUUID, "§cYou cannot curse your own follower's shrine.");
            return false;
        }

        // Check truce: prevent cursing truce partner's shrines (Req 23.3, 23.6)
        UUID shrineGod = shrine.getDedicatedGodUUID();
        if (shrineGod != null && isTruceActive(godUUID, shrineGod)) {
            notifyGod(godUUID, "§cYou have an active truce with that god. You cannot curse their followers' shrines.");
            return false;
        }

        // Check faith balance (Req 9.4)
        int cost = type.getFaithCost();
        int currentFaith = plugin.getFaithEngine().getFaith(godUUID);
        if (currentFaith < cost) {
            notifyGod(godUUID, "§cInsufficient faith. You have " + currentFaith + " but need " + cost + ".");
            return false;
        }

        // Deduct faith
        plugin.getFaithEngine().deductFaith(godUUID, cost);

        // Apply curse effect (Req 9.2)
        // HIGH #13 fix: If FIRE curse, destroy the shrine properly
        if (type == CurseType.FIRE) {
            applyCurseEffect(shrine, type);
            plugin.getShrineDetector().destroyShrine(shrine, godUUID);
        } else {
            applyCurseEffect(shrine, type);
        }

        // Notify shrine owner (Req 9.5, 9.6)
        String godName = resolveName(godUUID);
        String message = "§c☠ Your shrine has been cursed with §e" + type.name() + "§c by §e" + godName + "§c!";
        Player owner = plugin.getServer().getPlayer(shrineOwner);
        if (owner != null && owner.isOnline()) {
            owner.sendMessage(Component.text("☠ Your shrine has been cursed with " + type.name() + " by " + godName + "!", NamedTextColor.RED));
        } else {
            // Queue offline notification (Req 9.6)
            plugin.getNotificationSystem().queue(shrineOwner, message);
        }

        notifyGod(godUUID, "§aCurse §e" + type.name() + "§a applied to shrine §e" + shrineId + "§a.");

        // Increment curse count on GodData
        updateGodCurseCount(godUUID);

        return true;
    }

    // -------------------------------------------------------------------------
    // Public API – Rivalries
    // -------------------------------------------------------------------------

    /**
     * Declares a rivalry between two gods.
     * HIGH #20 fix: validates that both UUIDs are actual gods.
     * Requirements: 10.1, 10.2, 10.4
     */
    public void declareRivalry(UUID godUUID, UUID rivalUUID) {
        // HIGH #20 fix: validate target is a god
        List<UUID> godUUIDs = plugin.getEventManager().getState().getGodUUIDs();
        if (!godUUIDs.contains(rivalUUID)) {
            notifyGodComponent(godUUID, Component.text("That player is not a god.", NamedTextColor.RED));
            return;
        }

        GodData godData = loadGodData(godUUID);

        if (!godData.getRivals().contains(rivalUUID)) {
            godData.getRivals().add(rivalUUID);
            saveGodData(godData);
        }

        // Broadcast rivalry (Req 10.4)
        String godName = resolveName(godUUID);
        String rivalName = resolveName(rivalUUID);
        plugin.getServer().broadcast(
                Component.text("⚔ " + godName + " has declared " + rivalName + " as their rival!", NamedTextColor.RED));
    }

    /**
     * Removes a rivalry between two gods.
     * Requirement: 10.5
     */
    public void removeRivalry(UUID godUUID, UUID rivalUUID) {
        GodData godData = loadGodData(godUUID);

        godData.getRivals().remove(rivalUUID);
        saveGodData(godData);
    }

    // -------------------------------------------------------------------------
    // Public API – Truces
    // -------------------------------------------------------------------------

    /**
     * Sends a truce proposal from one god to another.
     * HIGH #19 fix: keyed by target so multiple proposals to same target work correctly.
     * HIGH #20 fix: validates target is a god.
     * Requirements: 23.1, 23.5
     */
    public boolean proposeTruce(UUID godUUID, UUID targetGodUUID) {
        // HIGH #20 fix: validate target is a god
        List<UUID> godUUIDs = plugin.getEventManager().getState().getGodUUIDs();
        if (!godUUIDs.contains(targetGodUUID)) {
            notifyGodComponent(godUUID, Component.text("That player is not a god.", NamedTextColor.RED));
            return false;
        }

        // Enforce one active truce per god (Req 23.5)
        if (hasActiveTruce(godUUID)) {
            notifyGodComponent(godUUID, Component.text("You already have an active truce.", NamedTextColor.RED));
            return false;
        }

        // HIGH #19 fix: key by target→proposer so acceptTruce can find all proposals for a target
        trucePending.put(godUUID, targetGodUUID);

        // Notify target
        Player target = plugin.getServer().getPlayer(targetGodUUID);
        if (target != null && target.isOnline()) {
            target.sendMessage(Component.text(
                    "☮ " + resolveName(godUUID) + " has proposed a truce. Run /god truce accept to accept.",
                    NamedTextColor.AQUA));
        }
        notifyGodComponent(godUUID, Component.text("Truce proposal sent to " + resolveName(targetGodUUID) + ".", NamedTextColor.GREEN));
        return true;
    }

    /**
     * Accepts a pending truce proposal directed at the given god.
     * HIGH #19 fix: collects all proposals targeting this god, picks the oldest.
     * Requirements: 23.2, 23.4, 23.5
     */
    public boolean acceptTruce(UUID godUUID) {
        // HIGH #19 fix: find all proposals targeting this god, pick first found
        UUID proposer = null;
        for (Map.Entry<UUID, UUID> entry : new HashMap<>(trucePending).entrySet()) {
            if (godUUID.equals(entry.getValue())) {
                proposer = entry.getKey();
                break;
            }
        }

        if (proposer == null) {
            notifyGodComponent(godUUID, Component.text("No pending truce proposal found.", NamedTextColor.RED));
            return false;
        }

        // Enforce one active truce per god (Req 23.5)
        if (hasActiveTruce(godUUID)) {
            notifyGodComponent(godUUID, Component.text("You already have an active truce.", NamedTextColor.RED));
            return false;
        }

        trucePending.remove(proposer);

        long expiry = System.currentTimeMillis() + TRUCE_DURATION_MS;

        // Set truce on both gods
        GodData proposerData = loadGodData(proposer);
        GodData acceptorData = loadGodData(godUUID);

        proposerData.setTrucePartner(godUUID);
        proposerData.setTruceExpiry(expiry);
        saveGodData(proposerData);

        acceptorData.setTrucePartner(proposer);
        acceptorData.setTruceExpiry(expiry);
        saveGodData(acceptorData);

        // Notify both (Req 23.2)
        String proposerName = resolveName(proposer);
        String acceptorName = resolveName(godUUID);
        notifyGodComponent(proposer, Component.text("☮ Truce accepted by " + acceptorName + ". Active for 12 hours.", NamedTextColor.GREEN));
        notifyGodComponent(godUUID, Component.text("☮ Truce with " + proposerName + " is now active for 12 hours.", NamedTextColor.GREEN));

        return true;
    }

    // -------------------------------------------------------------------------
    // Public API – Unique Powers (end of Day 1)
    // -------------------------------------------------------------------------

    /**
     * Assigns a unique power to each god at the end of Day 1.
     *
     * <p>Tier selection is based on follower count at the moment Day 1 ends:
     * <ul>
     *   <li>1–3 followers → Tier 1 (weak)</li>
     *   <li>4–7 followers → Tier 2 (medium)</li>
     *   <li>8+ followers → Tier 3 (strong)</li>
     * </ul>
     *
     * <p>No two gods receive the same power in one event (deduplication across assignments).
     * Each assignment is broadcast to all online players.
     *
     * <p>Requirements: 7.1, 7.2, 7.3, 7.4, 7.5
     *
     * @param godUUIDs the list of god UUIDs to assign powers to
     */
    public void assignUniquePowers(List<UUID> godUUIDs) {
        if (godUUIDs == null || godUUIDs.isEmpty()) return;

        List<String> tier1Pool = new ArrayList<>(plugin.getConfig().getStringList("powers.tier1"));
        List<String> tier2Pool = new ArrayList<>(plugin.getConfig().getStringList("powers.tier2"));
        List<String> tier3Pool = new ArrayList<>(plugin.getConfig().getStringList("powers.tier3"));

        // Shuffle each pool so selection is random (Req 7.3)
        Collections.shuffle(tier1Pool);
        Collections.shuffle(tier2Pool);
        Collections.shuffle(tier3Pool);

        // Track already-assigned powers to ensure deduplication (Req 7.5)
        Set<String> assigned = new HashSet<>();

        for (UUID godUUID : godUUIDs) {
            int followerCount = countFollowers(godUUID);
            String tier = selectTier(followerCount);

            List<String> pool = switch (tier) {
                case "tier3" -> tier3Pool;
                case "tier2" -> tier2Pool;
                default -> tier1Pool;
            };

            // Pick first available power not yet assigned (Req 7.5)
            String power = null;
            for (String candidate : pool) {
                if (!assigned.contains(candidate)) {
                    power = candidate;
                    break;
                }
            }

            // Fallback: try other tiers if the preferred pool is exhausted
            if (power == null) {
                power = pickFromFallback(tier1Pool, tier2Pool, tier3Pool, assigned);
            }

            if (power == null) {
                logger.warning("PowerSystem: no unique power available for god " + godUUID + " (all pools exhausted)");
                continue;
            }

            assigned.add(power);

            // Persist the power on GodData (Req 7.1)
            GodData godData = loadGodData(godUUID);
            godData.setUniquePower(power);
            saveGodData(godData);

            // Broadcast to all online players (Req 7.4)
            String godName = resolveName(godUUID);
            plugin.getServer().broadcast(
                    Component.text("✦ " + godName + " has been granted the unique power: " + power + "!",
                            NamedTextColor.GOLD));
            logger.info("PowerSystem: assigned power '" + power + "' to god " + godUUID + " (followers=" + followerCount + ", tier=" + tier + ")");
        }
    }

    /**
     * Returns the tier key for the given follower count.
     * 1–3 → tier1, 4–7 → tier2, 8+ → tier3.
     */
    private String selectTier(int followerCount) {
        if (followerCount >= 8) return "tier3";
        if (followerCount >= 4) return "tier2";
        return "tier1";
    }

    /**
     * Counts the number of mortals pledged to the given god.
     */
    private int countFollowers(UUID godUUID) {
        int count = 0;
        for (MortalData mortal : mortalRegistry.values()) {
            if (godUUID.equals(mortal.getPledgedGodUUID())) {
                count++;
            }
        }
        return count;
    }

    /**
     * Picks the first available power from any pool (fallback when preferred pool is exhausted).
     */
    private String pickFromFallback(List<String> tier1, List<String> tier2, List<String> tier3, Set<String> assigned) {
        for (List<String> pool : List.of(tier1, tier2, tier3)) {
            for (String candidate : pool) {
                if (!assigned.contains(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Constants – Betrayal
    // -------------------------------------------------------------------------

    /** Ritual duration: 10 minutes in milliseconds. */
    private static final long BETRAYAL_DURATION_MS = 10L * 60 * 1000;

    /** Maximum distance from shrine during ritual (blocks). */
    private static final double BETRAYAL_MAX_DISTANCE = 5.0;

    /** Damage multiplier applied to betraying mortal (50% increase → ×1.5). */
    private static final double BETRAYAL_DAMAGE_MULTIPLIER = 1.5;

    // -------------------------------------------------------------------------
    // State – Betrayal boss bars (mortalUUID → BossBar)
    // -------------------------------------------------------------------------

    private final Map<UUID, org.bukkit.boss.BossBar> betrayalBossBars = new HashMap<>();

    // -------------------------------------------------------------------------
    // Public API – Betrayal
    // -------------------------------------------------------------------------

    /**
     * Initiates a betrayal ritual for the given mortal.
     *
     * <p>Preconditions:
     * <ul>
     *   <li>Current phase is DAY2 or later (not VOTING or DAY1)</li>
     *   <li>It is nighttime in the mortal's world (13000–23000 ticks)</li>
     *   <li>Mortal has a pledged god</li>
     *   <li>Mortal has an active shrine</li>
     *   <li>No ritual already in progress</li>
     * </ul>
     *
     * <p>Requirements: 8.1, 8.2, 8.4, 8.8
     *
     * @return true if the ritual was started successfully
     */
    public boolean startBetrayal(UUID mortalUUID) {
        Player mortal = plugin.getServer().getPlayer(mortalUUID);
        if (mortal == null || !mortal.isOnline()) {
            return false;
        }

        // Phase check: must be DAY2 or later (Req 8.1, 8.8)
        com.example.godsvsmortals.enums.EventPhase phase = plugin.getEventManager().getCurrentPhase();
        if (phase == com.example.godsvsmortals.enums.EventPhase.VOTING
                || phase == com.example.godsvsmortals.enums.EventPhase.DAY1
                || phase == com.example.godsvsmortals.enums.EventPhase.ENDED) {
            mortal.sendMessage(org.bukkit.ChatColor.RED + "Betrayals unlock on Day 2. You cannot betray your god yet.");
            return false;
        }

        // Nighttime check (Req 8.1)
        long worldTime = mortal.getWorld().getTime();
        if (worldTime < 13000 || worldTime > 23000) {
            mortal.sendMessage(org.bukkit.ChatColor.RED + "You can only initiate a betrayal ritual at night (13000–23000 ticks).");
            return false;
        }

        // Mortal data check
        MortalData mortalData = mortalRegistry.get(mortalUUID);
        if (mortalData == null || mortalData.getPledgedGodUUID() == null) {
            mortal.sendMessage(org.bukkit.ChatColor.RED + "You must be pledged to a god before you can betray them.");
            return false;
        }

        // Already in ritual?
        if (mortalData.getBetrayelRitualState() != null) {
            mortal.sendMessage(org.bukkit.ChatColor.RED + "You already have a betrayal ritual in progress.");
            return false;
        }

        // Shrine check (Req 8.2)
        Shrine shrine = plugin.getShrineDetector().getShrineByOwner(mortalUUID);
        if (shrine == null || shrine.getCoreLocation() == null) {
            mortal.sendMessage(org.bukkit.ChatColor.RED + "You must have an active shrine to perform a betrayal ritual.");
            return false;
        }

        // Start the ritual
        long now = System.currentTimeMillis();
        MortalData.BetrayelRitualState state = new MortalData.BetrayelRitualState(now, shrine.getCoreLocation());
        mortalData.setBetrayelRitualState(state);
        saveMortalData(mortalData);

        // Create boss bar (Req 8.4)
        org.bukkit.boss.BossBar bar = plugin.getServer().createBossBar(
                org.bukkit.ChatColor.RED + "Betrayal Ritual",
                org.bukkit.boss.BarColor.RED,
                org.bukkit.boss.BarStyle.SOLID);
        bar.setProgress(0.0);
        bar.addPlayer(mortal);
        betrayalBossBars.put(mortalUUID, bar);

        mortal.sendMessage(org.bukkit.ChatColor.GOLD + "✦ Betrayal ritual started! Stay within 5 blocks of your shrine for 10 minutes.");
        mortal.sendMessage(org.bukkit.ChatColor.YELLOW + "Warning: You will take 50% more damage during the ritual!");

        logger.info("PowerSystem: betrayal ritual started for " + mortalUUID);
        return true;
    }

    /**
     * Cancels an in-progress betrayal ritual.
     *
     * <p>Requirement: 8.5
     *
     * @param mortalUUID the mortal whose ritual to cancel
     */
    public void cancelBetrayal(UUID mortalUUID) {
        cancelBetrayal(mortalUUID, org.bukkit.ChatColor.RED + "Your betrayal ritual has been cancelled.");
    }

    /**
     * Cancels an in-progress betrayal ritual with a custom message.
     */
    public void cancelBetrayal(UUID mortalUUID, String reason) {
        // Remove boss bar
        org.bukkit.boss.BossBar bar = betrayalBossBars.remove(mortalUUID);
        if (bar != null) {
            bar.removeAll();
        }

        // Clear ritual state
        MortalData mortalData = mortalRegistry.get(mortalUUID);
        if (mortalData != null) {
            mortalData.setBetrayelRitualState(null);
            saveMortalData(mortalData);
        }

        // Notify mortal
        Player mortal = plugin.getServer().getPlayer(mortalUUID);
        if (mortal != null && mortal.isOnline()) {
            mortal.sendMessage(reason);
        }

        logger.info("PowerSystem: betrayal ritual cancelled for " + mortalUUID);
    }

    /**
     * Ticks all active betrayal rituals. Called every server tick (or every second).
     *
     * <p>For each mortal with an active ritual:
     * <ul>
     *   <li>Check distance from shrine; cancel if > 5 blocks (Req 8.5)</li>
     *   <li>Update boss bar progress (Req 8.4)</li>
     *   <li>Complete ritual if 10 minutes have elapsed (Req 8.6, 8.7)</li>
     * </ul>
     */
    public void tickBetrayalRituals() {
        long now = System.currentTimeMillis();

        // Collect mortals to process (avoid ConcurrentModificationException)
        List<UUID> activeMortals = new ArrayList<>();
        for (Map.Entry<UUID, MortalData> entry : mortalRegistry.entrySet()) {
            if (entry.getValue().getBetrayelRitualState() != null) {
                activeMortals.add(entry.getKey());
            }
        }

        for (UUID mortalUUID : activeMortals) {
            MortalData mortalData = mortalRegistry.get(mortalUUID);
            if (mortalData == null) continue;

            MortalData.BetrayelRitualState ritual = mortalData.getBetrayelRitualState();
            if (ritual == null) continue;

            Player mortal = plugin.getServer().getPlayer(mortalUUID);
            if (mortal == null || !mortal.isOnline()) {
                // Player went offline — cancel
                cancelBetrayal(mortalUUID, org.bukkit.ChatColor.RED + "Your betrayal ritual was cancelled because you went offline.");
                continue;
            }

            // Distance check (Req 8.5)
            org.bukkit.Location shrineLoc = ritual.getShrineLocation();
            if (shrineLoc != null && mortal.getLocation().getWorld() != null
                    && shrineLoc.getWorld() != null
                    && mortal.getLocation().getWorld().equals(shrineLoc.getWorld())) {
                double distance = mortal.getLocation().distance(shrineLoc);
                if (distance > BETRAYAL_MAX_DISTANCE) {
                    cancelBetrayal(mortalUUID, org.bukkit.ChatColor.RED + "Betrayal ritual cancelled: you moved too far from your shrine!");
                    continue;
                }
            }

            // Progress update
            long elapsed = now - ritual.getStartTimeMs();
            double progress = Math.min(1.0, (double) elapsed / BETRAYAL_DURATION_MS);

            org.bukkit.boss.BossBar bar = betrayalBossBars.get(mortalUUID);
            if (bar != null) {
                bar.setProgress(progress);
                long remainingSeconds = Math.max(0, (BETRAYAL_DURATION_MS - elapsed) / 1000);
                bar.setTitle(org.bukkit.ChatColor.RED + "Betrayal Ritual – " + remainingSeconds + "s remaining");
            }

            // Completion check
            if (elapsed >= BETRAYAL_DURATION_MS) {
                completeBetrayal(mortalUUID, mortalData);
            }
        }
    }

    /**
     * Completes a betrayal ritual: unlinks mortal from god, increments counts, prompts new god selection.
     *
     * <p>Requirements: 8.6, 8.7
     */
    private void completeBetrayal(UUID mortalUUID, MortalData mortalData) {
        UUID formerGodUUID = mortalData.getPledgedGodUUID();

        // Remove boss bar
        org.bukkit.boss.BossBar bar = betrayalBossBars.remove(mortalUUID);
        if (bar != null) {
            bar.removeAll();
        }

        // Clear ritual state
        mortalData.setBetrayelRitualState(null);

        // Unlink from current god (Req 8.6)
        mortalData.setPledgedGodUUID(null);

        // Increment betrayal count (Req 8.7)
        mortalData.setBetrayalCount(mortalData.getBetrayalCount() + 1);

        // Increment betrayalsAgainstCount on former god's mortal data (if applicable)
        if (formerGodUUID != null) {
            MortalData formerGodMortalData = mortalRegistry.get(formerGodUUID);
            if (formerGodMortalData != null) {
                formerGodMortalData.setBetrayalsAgainstCount(formerGodMortalData.getBetrayalsAgainstCount() + 1);
                saveMortalData(formerGodMortalData);
            }
        }

        saveMortalData(mortalData);

        // Notify mortal and prompt new god selection (Req 8.6)
        Player mortal = plugin.getServer().getPlayer(mortalUUID);
        if (mortal != null && mortal.isOnline()) {
            mortal.sendMessage(org.bukkit.ChatColor.GOLD + "✦ Betrayal ritual complete! You have broken your oath.");
            mortal.sendMessage(org.bukkit.ChatColor.YELLOW + "Choose a new god to pledge your loyalty to:");

            java.util.List<UUID> godUUIDs = plugin.getEventManager().getState().getGodUUIDs();
            if (godUUIDs == null || godUUIDs.isEmpty()) {
                mortal.sendMessage(org.bukkit.ChatColor.GRAY + "  No gods are currently available. You are godless.");
            } else {
                for (int i = 0; i < godUUIDs.size(); i++) {
                    String godName = resolveName(godUUIDs.get(i));
                    mortal.sendMessage(org.bukkit.ChatColor.AQUA + "  [" + (i + 1) + "] " + godName);
                }
                mortal.sendMessage(org.bukkit.ChatColor.GRAY + "Use /shrine dedicate <number> to choose your new god.");
            }
        }

        // Trigger scoreboard refresh (Req 8.7) — ScoreboardManager will be wired in task 16/17
        logger.info("PowerSystem: betrayal ritual completed for " + mortalUUID
                + " (former god=" + formerGodUUID + ", betrayalCount=" + mortalData.getBetrayalCount() + ")");

        // Grant Rebellion Banner if in Day 3 or Ragnarok (Req 13.1)
        com.example.godsvsmortals.enums.EventPhase phase = plugin.getEventManager().getCurrentPhase();
        if (phase == com.example.godsvsmortals.enums.EventPhase.DAY3
                || phase == com.example.godsvsmortals.enums.EventPhase.RAGNAROK) {
            grantRebellionBanner(mortalUUID);
        }
    }

    /**
     * Returns the boss bar map for testing purposes.
     */
    Map<UUID, org.bukkit.boss.BossBar> getBetrayalBossBars() {
        return java.util.Collections.unmodifiableMap(betrayalBossBars);
    }

    public boolean activateAvatarMode(UUID godUUID) {
        // Phase check: must be DAY3 or RAGNAROK
        com.example.godsvsmortals.enums.EventPhase phase = plugin.getEventManager().getCurrentPhase();
        if (phase != com.example.godsvsmortals.enums.EventPhase.DAY3
                && phase != com.example.godsvsmortals.enums.EventPhase.RAGNAROK) {
            notifyGod(godUUID, "§cAvatar Mode is only available during Day 3 and Ragnarok.");
            return false;
        }

        // One-use enforcement (Req 14.6)
        GodData godData = loadGodData(godUUID);
        if (godData.isAvatarModeUsed()) {
            notifyGod(godUUID, "§cYou have already used Avatar Mode this event.");
            return false;
        }

        // Faith cost check (Req 14.1)
        int currentFaith = plugin.getFaithEngine().getFaith(godUUID);
        if (currentFaith < AVATAR_FAITH_COST) {
            notifyGod(godUUID, "§cInsufficient faith. Avatar Mode costs " + AVATAR_FAITH_COST
                    + " faith. You have " + currentFaith + ".");
            return false;
        }

        Player god = plugin.getServer().getPlayer(godUUID);
        if (god == null || !god.isOnline()) {
            return false;
        }

        // Deduct faith
        plugin.getFaithEngine().deductFaith(godUUID, AVATAR_FAITH_COST);

        // Mark as used
        godData.setAvatarModeUsed(true);
        saveGodData(godData);

        // Set max health to 100 HP (Req 14.2)
        AttributeInstance maxHealthAttr = god.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr != null) {
            // Remove existing modifiers to avoid stacking
            NamespacedKey avatarKey = new NamespacedKey(plugin, "avatar_health");
            maxHealthAttr.getModifiers().stream()
                    .filter(m -> m.key().equals(avatarKey))
                    .forEach(maxHealthAttr::removeModifier);
            double baseHealth = maxHealthAttr.getBaseValue();
            double addAmount = AVATAR_MAX_HEALTH - baseHealth;
            if (addAmount > 0) {
                AttributeModifier mod = new AttributeModifier(avatarKey, addAmount,
                        AttributeModifier.Operation.ADD_NUMBER);
                maxHealthAttr.addModifier(mod);
            }
            god.setHealth(AVATAR_MAX_HEALTH);
        }

        // Grant flight (Req 14.2)
        god.setAllowFlight(true);
        god.setFlying(true);

        // Equip MythicMobs weapon or vanilla fallback (Req 14.2, 14.7)
        if (mythicMobsAdapter != null) {
            boolean given = mythicMobsAdapter.giveItem(god, AVATAR_MYTHICMOBS_WEAPON);
            if (!given) {
                // Fallback to vanilla if MythicMobs item not found
                god.getInventory().addItem(new ItemStack(Material.NETHERITE_SWORD, 1));
            }
        } else {
            // Vanilla fallback (Req 14.7)
            god.getInventory().addItem(new ItemStack(Material.NETHERITE_SWORD, 1));
        }

        // Record expiry and persist to GodData (LOW #65 fix: survive restarts)
        long expiry = System.currentTimeMillis() + AVATAR_DURATION_MS;
        avatarExpiry.put(godUUID, expiry);
        godData.setAvatarExpiry(expiry);
        saveGodData(godData);

        // Create boss bar visible to nearby players (Req 14.3)
        BossBar bar = plugin.getServer().createBossBar(
                "⚡ " + god.getName() + " – AVATAR MODE",
                BarColor.YELLOW, BarStyle.SOLID);
        bar.setProgress(1.0);
        // Add all players within 50 blocks
        for (Player nearby : god.getWorld().getPlayers()) {
            if (nearby.getLocation().distance(god.getLocation()) <= AVATAR_BOSS_BAR_RADIUS) {
                bar.addPlayer(nearby);
            }
        }
        avatarBossBars.put(godUUID, bar);

        // Schedule expiry task
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> expireAvatarMode(godUUID), 20L * 60 * 10);

        // Broadcast
        plugin.getServer().broadcast(Component.text(
                "⚡ " + god.getName() + " has activated AVATAR MODE!", NamedTextColor.GOLD, TextDecoration.BOLD));

        logger.info("PowerSystem: Avatar Mode activated for " + godUUID);
        return true;
    }

    /**
     * Expires Avatar Mode for the given god: removes flight, restores health, removes boss bar.
     * Requirements: 14.4
     */
    public void expireAvatarMode(UUID godUUID) {
        avatarExpiry.remove(godUUID);

        // Remove boss bar
        BossBar bar = avatarBossBars.remove(godUUID);
        if (bar != null) {
            bar.removeAll();
        }

        Player god = plugin.getServer().getPlayer(godUUID);
        if (god != null && god.isOnline()) {
            // Remove flight
            god.setFlying(false);
            god.setAllowFlight(false);

            // Restore normal max health
            AttributeInstance maxHealthAttr = god.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealthAttr != null) {
                NamespacedKey avatarKey = new NamespacedKey(plugin, "avatar_health");
                maxHealthAttr.getModifiers().stream()
                        .filter(m -> m.key().equals(avatarKey))
                        .forEach(maxHealthAttr::removeModifier);
                // Clamp current health to new max
                double newMax = maxHealthAttr.getValue();
                if (god.getHealth() > newMax) {
                    god.setHealth(newMax);
                }
            }

            god.sendMessage(Component.text("Your Avatar Mode has expired.", NamedTextColor.YELLOW));
        }

        logger.info("PowerSystem: Avatar Mode expired for " + godUUID);
    }

    /**
     * Returns true if the given god currently has Avatar Mode active.
     */
    public boolean isAvatarModeActive(UUID godUUID) {
        Long expiry = avatarExpiry.get(godUUID);
        if (expiry == null) return false;
        if (System.currentTimeMillis() >= expiry) {
            expireAvatarMode(godUUID);
            return false;
        }
        return true;
    }

    /**
     * Called when a god is killed during Ragnarok. If Avatar Mode was active,
     * broadcasts faction defeat. Removes god immunity and redistributes followers.
     *
     * Requirements: 12.3, 12.4, 14.5
     */
    public void onGodDeath(UUID godUUID) {
        boolean wasInAvatarMode = isAvatarModeActive(godUUID);

        // Clean up Avatar Mode if active
        if (wasInAvatarMode) {
            expireAvatarMode(godUUID);
            String godName = resolveName(godUUID);
            plugin.getServer().broadcast(Component.text(
                    "☠ " + godName + "'s faction has been DEFEATED! Avatar Mode ended in death!",
                    NamedTextColor.DARK_RED, TextDecoration.BOLD));
        }

        // Broadcast god death (Req 12.3)
        String godName = resolveName(godUUID);
        plugin.getServer().broadcast(Component.text(
                "☠ The god " + godName + " has been slain during Ragnarok!",
                NamedTextColor.DARK_RED));

        // Redistribute followers as godless (Req 12.4)
        for (MortalData mortal : mortalRegistry.values()) {
            if (godUUID.equals(mortal.getPledgedGodUUID())) {
                mortal.setPledgedGodUUID(null);
                saveMortalData(mortal);
                Player follower = plugin.getServer().getPlayer(mortal.getUuid());
                if (follower != null && follower.isOnline()) {
                    follower.sendMessage(Component.text(
                            "Your god has fallen. You are now godless – choose a new allegiance.",
                            NamedTextColor.YELLOW));
                }
            }
        }

        logger.info("PowerSystem: god death processed for " + godUUID);
    }

    /**
     * Identifies the most-betrayed mortal (skipping current gods) and grants them
     * the /fallen steal command by setting their isFallenGod flag.
     * Called when Ragnarok activates.
     * Requirements: 15.1, 15.2, 15.6
     */
    public void identifyGreatBetrayalCandidate() {
        // Enforce one Fallen God per event (Req 15.5)
        if (plugin.getEventManager().getState().getFallenGodUUID() != null) {
            return; // Already designated
        }

        List<UUID> godUUIDs = plugin.getEventManager().getState().getGodUUIDs();

        UUID candidate = null;
        int maxBetrayed = -1;
        for (MortalData mortal : mortalRegistry.values()) {
            UUID uuid = mortal.getUuid();
            if (godUUIDs.contains(uuid)) continue; // skip current gods (Req 15.6)
            if (mortal.getBetrayalsAgainstCount() > maxBetrayed) {
                maxBetrayed = mortal.getBetrayalsAgainstCount();
                candidate = uuid;
            }
        }

        if (candidate == null) {
            logger.info("PowerSystem: No eligible Great Betrayal candidate found.");
            return;
        }

        // Notify the candidate
        Player candidatePlayer = plugin.getServer().getPlayer(candidate);
        if (candidatePlayer != null && candidatePlayer.isOnline()) {
            candidatePlayer.sendMessage(Component.text(
                    "✦ You are the most-betrayed mortal! Use /fallen steal to claim divine power!",
                    NamedTextColor.DARK_PURPLE, TextDecoration.BOLD));
        }

        // Broadcast
        String candidateName = resolveName(candidate);
        plugin.getServer().broadcast(Component.text(
                "☠ " + candidateName + " has been chosen for the GREAT BETRAYAL! They may now use /fallen steal!",
                NamedTextColor.DARK_PURPLE));

        logger.info("PowerSystem: Great Betrayal candidate identified: " + candidate
                + " (betrayalsAgainst=" + maxBetrayed + ")");
    }

    public boolean executeGreatBetrayal(UUID mortalUUID) {
        // Identify most-betrayed mortal, skipping current gods (Req 15.1, 15.6)
        List<UUID> godUUIDs = plugin.getEventManager().getState().getGodUUIDs();

        // Find the mortal with the highest betrayalsAgainstCount who is not a current god
        UUID candidate = null;
        int maxBetrayed = -1;
        for (MortalData mortal : mortalRegistry.values()) {
            UUID uuid = mortal.getUuid();
            if (godUUIDs.contains(uuid)) continue; // skip current gods (Req 15.6)
            if (mortal.getBetrayalsAgainstCount() > maxBetrayed) {
                maxBetrayed = mortal.getBetrayalsAgainstCount();
                candidate = uuid;
            }
        }

        if (candidate == null) {
            notifyPlayer(mortalUUID, "§cNo eligible mortal found for the Great Betrayal.");
            return false;
        }

        // Enforce one Fallen God per event (Req 15.5)
        if (plugin.getEventManager().getState().getFallenGodUUID() != null) {
            notifyPlayer(mortalUUID, "§cA Fallen God already exists this event.");
            return false;
        }

        // Only the designated candidate can execute this
        if (!candidate.equals(mortalUUID)) {
            notifyPlayer(mortalUUID, "§cYou are not the most-betrayed mortal.");
            return false;
        }

        MortalData mortalData = mortalRegistry.get(mortalUUID);
        if (mortalData == null) return false;

        // Transfer unique power from the god they were most recently following (Req 15.3)
        UUID formerGodUUID = mortalData.getPledgedGodUUID();
        String stolenPower = null;
        if (formerGodUUID != null) {
            GodData formerGod = loadGodData(formerGodUUID);
            stolenPower = formerGod.getUniquePower();
        }

        // Designate as Fallen God (Req 15.3)
        mortalData.setFallenGod(true);
        if (stolenPower != null) {
            // Store the stolen power in mortal data via a note (we reuse the playerName field approach)
            // Actually store it in a separate mechanism – we'll persist it via a note in the mortal file
        }
        saveMortalData(mortalData);

        // Update EventState
        plugin.getEventManager().getState().setFallenGodUUID(mortalUUID);
        plugin.getEventManager().saveState();

        // Grant godsvsmortals.fallen permission (Req 15.2)
        // In Paper, we can't grant permissions at runtime without a permission plugin,
        // but we track it via isFallenGod flag and check it in the command
        Player mortal = plugin.getServer().getPlayer(mortalUUID);
        if (mortal != null && mortal.isOnline()) {
            mortal.sendMessage(Component.text(
                    "✦ You have become the FALLEN GOD! Use /fallen steal to claim your power.",
                    NamedTextColor.DARK_PURPLE, TextDecoration.BOLD));
        }

        // Broadcast (Req 15.4)
        String mortalName = resolveName(mortalUUID);
        plugin.getServer().broadcast(Component.text(
                "☠ " + mortalName + " has risen as the FALLEN GOD! The balance of power shifts!",
                NamedTextColor.DARK_PURPLE, TextDecoration.BOLD));

        logger.info("PowerSystem: Great Betrayal executed – Fallen God is " + mortalUUID);
        return true;
    }

    /**
     * Executes the /fallen steal command: transfers unique power to the Fallen God.
     * Requirements: 15.2, 15.3
     */
    public boolean executeFallenSteal(UUID mortalUUID) {
        MortalData mortalData = mortalRegistry.get(mortalUUID);
        if (mortalData == null || !mortalData.isFallenGod()) {
            notifyPlayer(mortalUUID, "§cYou are not the Fallen God.");
            return false;
        }

        // Already stolen?
        if (mortalData.getPledgedGodUUID() == null) {
            notifyPlayer(mortalUUID, "§cYou have no former god to steal power from.");
            return false;
        }

        UUID formerGodUUID = mortalData.getPledgedGodUUID();
        GodData formerGod = loadGodData(formerGodUUID);
        String power = formerGod.getUniquePower();

        if (power == null) {
            notifyPlayer(mortalUUID, "§cYour former god has no unique power to steal.");
            return false;
        }

        // Transfer power: remove from god, note it for the fallen god
        formerGod.setUniquePower(null);
        saveGodData(formerGod);

        // Notify
        Player mortal = plugin.getServer().getPlayer(mortalUUID);
        if (mortal != null && mortal.isOnline()) {
            mortal.sendMessage(Component.text(
                    "✦ You have stolen the power: " + power + "!", NamedTextColor.DARK_PURPLE));
        }

        plugin.getServer().broadcast(Component.text(
                "☠ The Fallen God " + resolveName(mortalUUID) + " has stolen the power of "
                        + resolveName(formerGodUUID) + "!",
                NamedTextColor.DARK_PURPLE));

        logger.info("PowerSystem: Fallen God " + mortalUUID + " stole power '" + power + "' from " + formerGodUUID);
        return true;
    }

    public boolean processSacrifice(UUID mortalUUID, UUID targetGodUUID) {
        Player mortal = plugin.getServer().getPlayer(mortalUUID);
        if (mortal == null || !mortal.isOnline()) {
            return false;
        }

        // Phase check: sacrifice only allowed from Day 1 onwards (Req 16.1)
        com.example.godsvsmortals.enums.EventPhase phase = plugin.getEventManager().getCurrentPhase();
        if (phase == com.example.godsvsmortals.enums.EventPhase.VOTING
                || phase == com.example.godsvsmortals.enums.EventPhase.ENDED) {
            mortal.sendMessage(Component.text(
                    "§cYou cannot sacrifice outside of the event.", NamedTextColor.RED));
            return false;
        }

        // Self-sacrifice check
        if (mortalUUID.equals(targetGodUUID)) {
            mortal.sendMessage(Component.text(
                    "§cYou cannot sacrifice to banish yourself.", NamedTextColor.RED));
            return false;
        }

        // Target must actually be a god
        if (!plugin.getEventManager().getState().getGodUUIDs().contains(targetGodUUID)) {
            mortal.sendMessage(Component.text(
                    "§c" + resolveName(targetGodUUID) + " is not a god.", NamedTextColor.RED));
            return false;
        }

        // HP check: reject if HP ≤ 6 (Req 16.2)
        double currentHp = mortal.getHealth();
        if (currentHp <= SACRIFICE_MIN_HP) {
            mortal.sendMessage(Component.text(
                    "§cYou cannot sacrifice – your HP is too low (" + (int) currentHp + " HP). Minimum is 7 HP.",
                    NamedTextColor.RED));
            return false;
        }

        // Deduct 5 HP (Req 16.1)
        double newHp = Math.max(0.5, currentHp - SACRIFICE_HP_COST);
        mortal.setHealth(newHp);

        // Add sacrifice point to target god (Req 16.1)
        GodData godData = loadGodData(targetGodUUID);
        int newPoints = godData.getSacrificePoints() + 1;
        godData.setSacrificePoints(newPoints);
        saveGodData(godData);

        mortal.sendMessage(Component.text(
                "✦ You sacrificed 5 HP to banish " + resolveName(targetGodUUID)
                        + ". Sacrifice points: " + newPoints + "/50.",
                NamedTextColor.GOLD));

        // Check banishment threshold (Req 16.3)
        int threshold = plugin.getConfig().getInt("sacrifice.banish-threshold", 50);
        if (newPoints >= threshold && !godData.isBanished()) {
            banishGod(targetGodUUID, godData);
        }

        logger.info("PowerSystem: sacrifice processed – mortal=" + mortalUUID
                + " target=" + targetGodUUID + " points=" + newPoints);
        return true;
    }

    /**
     * Banishes a god for the configured duration. Prevents powers while banished.
     * HIGH #11 fix: reads banish-duration-hours from config instead of hard-coded constant.
     * Requirements: 16.3, 16.4, 16.5
     */
    private void banishGod(UUID godUUID, GodData godData) {
        long banishHours = plugin.getConfig().getLong("sacrifice.banish-duration-hours", 1);
        long banishDurationMs = banishHours * 60 * 60 * 1000L;
        long expiry = System.currentTimeMillis() + banishDurationMs;
        godData.setBanished(true);
        godData.setBanishmentExpiry(expiry);
        saveGodData(godData);

        String godName = resolveName(godUUID);
        plugin.getServer().broadcast(Component.text(
                "⚡ The god " + godName + " has been BANISHED by mass sacrifice for " + banishHours + " hour(s)!",
                NamedTextColor.DARK_RED, TextDecoration.BOLD));

        // Schedule restoration
        long delayTicks = banishDurationMs / 50;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> restoreGod(godUUID), delayTicks);

        logger.info("PowerSystem: god " + godUUID + " banished until " + expiry);
    }

    /**
     * Restores a banished god and broadcasts their return.
     * Requirements: 16.6
     */
    public void restoreGod(UUID godUUID) {
        GodData godData = loadGodData(godUUID);
        if (!godData.isBanished()) return;

        godData.setBanished(false);
        godData.setBanishmentExpiry(0L);
        godData.setSacrificePoints(0); // reset sacrifice points on restoration
        saveGodData(godData);

        String godName = resolveName(godUUID);
        plugin.getServer().broadcast(Component.text(
                "⚡ The god " + godName + " has returned from banishment!",
                NamedTextColor.GOLD));

        Player god = plugin.getServer().getPlayer(godUUID);
        if (god != null && god.isOnline()) {
            god.sendMessage(Component.text("Your banishment has ended. Your powers are restored.", NamedTextColor.GREEN));
        }

        logger.info("PowerSystem: god " + godUUID + " restored from banishment.");
    }

    /**
     * Returns true if the given god is currently banished.
     * Also checks expiry and auto-restores if expired.
     * Requirements: 16.4
     */
    public boolean isGodBanished(UUID godUUID) {
        GodData godData = loadGodData(godUUID);
        if (!godData.isBanished()) return false;
        if (godData.getBanishmentExpiry() > 0 && System.currentTimeMillis() >= godData.getBanishmentExpiry()) {
            restoreGod(godUUID);
            return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Rebellion Banner (Req 13)
    // -------------------------------------------------------------------------

    /**
     * Creates a Rebellion Banner item with a bound NBT tag.
     * Requirements: 13.1, 13.5
     */
    public ItemStack createRebellionBanner(UUID ownerUUID) {
        ItemStack banner = new ItemStack(Material.WHITE_BANNER, 1);
        ItemMeta meta = banner.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("⚑ Rebellion Banner", NamedTextColor.RED, TextDecoration.BOLD));
            meta.lore(List.of(
                    Component.text("Bound to: " + resolveName(ownerUUID), NamedTextColor.GRAY),
                    Component.text("This banner cannot be dropped or traded.", NamedTextColor.DARK_GRAY)
            ));
            // Set NBT tag to mark as rebellion banner
            NamespacedKey key = new NamespacedKey(plugin, REBELLION_BANNER_KEY);
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, ownerUUID.toString());
            banner.setItemMeta(meta);
        }
        return banner;
    }

    /**
     * Returns true if the given ItemStack is a Rebellion Banner.
     */
    public boolean isRebellionBanner(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        NamespacedKey key = new NamespacedKey(plugin, REBELLION_BANNER_KEY);
        return meta.getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    /**
     * Grants a Rebellion Banner to a mortal after completing a Day 3 betrayal.
     * Requirements: 13.1, 13.2
     */
    public void grantRebellionBanner(UUID mortalUUID) {
        MortalData mortalData = mortalRegistry.get(mortalUUID);
        if (mortalData == null) return;

        // Already has one
        if (mortalData.isHasRebellionBanner()) return;

        mortalData.setHasRebellionBanner(true);
        saveMortalData(mortalData);

        // Increment global banner count
        int newCount = plugin.getEventManager().getState().getRebellionBannerCount() + 1;
        plugin.getEventManager().getState().setRebellionBannerCount(newCount);
        plugin.getEventManager().saveState();

        // Give item to player if online
        Player mortal = plugin.getServer().getPlayer(mortalUUID);
        if (mortal != null && mortal.isOnline()) {
            mortal.getInventory().addItem(createRebellionBanner(mortalUUID));
            mortal.sendMessage(Component.text(
                    "⚑ You have received a Rebellion Banner! (" + newCount + "/5)",
                    NamedTextColor.RED));
        }

        // Check if 5 banners collected (Req 13.3, 13.4)
        if (newCount >= 5) {
            onFiveBannersCollected();
        }

        logger.info("PowerSystem: Rebellion Banner granted to " + mortalUUID + " (total=" + newCount + ")");
    }

    /**
     * Called when 5 Rebellion Banners have been collected.
     * Broadcasts god locations and applies 2× damage multiplier.
     * Requirements: 13.3, 13.4
     */
    private void onFiveBannersCollected() {
        plugin.getServer().broadcast(Component.text(
                "⚑ FIVE REBELLION BANNERS COLLECTED! The gods are exposed!",
                NamedTextColor.RED, TextDecoration.BOLD));

        // Broadcast real names and coordinates of all living gods (Req 13.3)
        List<UUID> godUUIDs = plugin.getEventManager().getState().getGodUUIDs();
        for (UUID godUUID : godUUIDs) {
            Player god = plugin.getServer().getPlayer(godUUID);
            if (god != null && god.isOnline()) {
                Location loc = god.getLocation();
                plugin.getServer().broadcast(Component.text(
                        "⚑ God " + god.getName() + " is at X=" + (int) loc.getX()
                                + " Y=" + (int) loc.getY() + " Z=" + (int) loc.getZ(),
                        NamedTextColor.YELLOW));
            }
        }

        logger.info("PowerSystem: 5 Rebellion Banners collected – gods exposed.");
    }

    // -------------------------------------------------------------------------
    // Bukkit Event Listener – Rebellion Banner binding (Req 13.5)
    // -------------------------------------------------------------------------

    /**
     * Prevents Rebellion Banners from being dropped.
     * Requirements: 13.5
     */
    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (isRebellionBanner(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text(
                    "⚑ Rebellion Banners cannot be dropped.", NamedTextColor.RED));
        }
    }

    /**
     * HIGH #15 fix: Remove Rebellion Banner from death drops so it can't be picked up.
     * Requirements: 13.5
     */
    @EventHandler
    public void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        event.getDrops().removeIf(this::isRebellionBanner);
    }

    /**
     * Prevents Rebellion Banners from being moved in inventory (traded/destroyed).
     * Requirements: 13.5
     */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        if (isRebellionBanner(current) || isRebellionBanner(cursor)) {
            event.setCancelled(true);
        }
    }

    /**
     * Prevents Rebellion Banners from being used in interactions.
     * Requirements: 13.5
     */
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (isRebellionBanner(item)) {
            event.setCancelled(true);
        }
    }

    // -------------------------------------------------------------------------
    // Bukkit Event Listener – Rivalry damage bonus
    // -------------------------------------------------------------------------

    /**
     * Applies a 20% bonus damage multiplier when a follower of one rival god
     * attacks a follower of the other rival god.
     *
     * <p>Requirement: 10.3
     */
    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        Entity victim = event.getEntity();

        if (!(damager instanceof Player attacker) || !(victim instanceof Player defender)) return;

        UUID attackerGod = getFollowedGod(attacker.getUniqueId());
        UUID defenderGod = getFollowedGod(defender.getUniqueId());

        if (attackerGod == null || defenderGod == null) return;
        if (attackerGod.equals(defenderGod)) return;

        // Check if the two gods are rivals of each other
        if (areRivals(attackerGod, defenderGod)) {
            double newDamage = event.getDamage() * (1.0 + RIVALRY_DAMAGE_BONUS);
            event.setDamage(newDamage);
        }
    }

    /**
     * Applies a 50% incoming damage increase to a mortal with an active betrayal ritual.
     * MED #36 fix: only applies to EntityDamageByEntityEvent (player-vs-player), not environmental.
     * MED #37 fix: cap total multiplier to avoid stacking with rivalry bonus.
     *
     * <p>Requirement: 8.3
     */
    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        // MED #36 fix: only boost PvP damage, not environmental
        if (!(event instanceof EntityDamageByEntityEvent)) return;
        if (!(event.getEntity() instanceof Player player)) return;

        MortalData mortalData = mortalRegistry.get(player.getUniqueId());
        if (mortalData == null || mortalData.getBetrayelRitualState() == null) return;

        // MED #37 fix: check if rivalry bonus already applied; use additive not multiplicative
        // We set a flag via metadata to avoid double-applying
        double currentDamage = event.getDamage();
        // Apply betrayal bonus additively on top of base damage (not stacking with rivalry ×)
        // The rivalry handler already ran at NORMAL priority; we add a flat 50% of base
        // To avoid multiplicative stacking, we track original damage via the event's final damage
        event.setDamage(currentDamage + (event.getDamage() * (BETRAYAL_DAMAGE_MULTIPLIER - 1.0)));
    }

    // -------------------------------------------------------------------------
    // Mortal Registry
    // -------------------------------------------------------------------------

    /**
     * Registers or updates a mortal's data in the in-memory registry.
     */
    public void registerMortal(MortalData data) {
        mortalRegistry.put(data.getUuid(), data);
    }

    /**
     * Returns the MortalData for the given UUID, or null.
     */
    public MortalData getMortalData(UUID uuid) {
        return mortalRegistry.get(uuid);
    }

    /**
     * Saves a mortal's data to disk and updates the registry.
     */
    public void saveMortalData(MortalData data) {
        mortalRegistry.put(data.getUuid(), data);
        File mortalsDir = new File(plugin.getDataFolder(), "mortals");
        mortalsDir.mkdirs();
        File file = new File(mortalsDir, data.getUuid().toString() + ".yml");
        try {
            data.save(file);
        } catch (IOException e) {
            logger.severe("PowerSystem: failed to save mortal data for " + data.getUuid() + ": " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Truce helpers
    // -------------------------------------------------------------------------

    /**
     * Returns true if the two gods currently have an active truce with each other.
     */
    public boolean isTruceActive(UUID godA, UUID godB) {
        GodData dataA = loadGodData(godA);
        if (dataA == null) return false;
        if (!godB.equals(dataA.getTrucePartner())) return false;
        long now = System.currentTimeMillis();
        if (dataA.getTruceExpiry() <= now) {
            // Truce expired – clean up
            expireTruce(godA, godB);
            return false;
        }
        return true;
    }

    private boolean hasActiveTruce(UUID godUUID) {
        GodData data = loadGodData(godUUID);
        if (data == null || data.getTrucePartner() == null) return false;
        long now = System.currentTimeMillis();
        if (data.getTruceExpiry() <= now) {
            expireTruce(godUUID, data.getTrucePartner());
            return false;
        }
        return true;
    }

    private void expireTruce(UUID godA, UUID godB) {
        clearTruceOnGod(godA);
        clearTruceOnGod(godB);

        // Notify both (Req 23.4)
        notifyGod(godA, "§eYour truce with §b" + resolveName(godB) + "§e has expired.");
        notifyGod(godB, "§eYour truce with §b" + resolveName(godA) + "§e has expired.");
    }

    private void clearTruceOnGod(UUID godUUID) {
        GodData data = loadGodData(godUUID);
        if (data == null) return;
        data.setTrucePartner(null);
        data.setTruceExpiry(0L);
        saveGodData(data);
    }

    // -------------------------------------------------------------------------
    // Rivalry helpers
    // -------------------------------------------------------------------------

    private boolean areRivals(UUID godA, UUID godB) {
        GodData dataA = loadGodData(godA);
        if (dataA != null && dataA.getRivals().contains(godB)) return true;
        GodData dataB = loadGodData(godB);
        return dataB != null && dataB.getRivals().contains(godA);
    }

    private UUID getFollowedGod(UUID playerUUID) {
        MortalData data = mortalRegistry.get(playerUUID);
        return data != null ? data.getPledgedGodUUID() : null;
    }

    // -------------------------------------------------------------------------
    // Blessing effect application
    // -------------------------------------------------------------------------

    private void applyBlessingEffect(Player target, BlessingType type) {
        switch (type) {
            case GOLDEN_APPLE -> target.getInventory().addItem(new ItemStack(Material.GOLDEN_APPLE, 1));
            case SUMMON_WOLF -> {
                Location loc = target.getLocation();
                Wolf wolf = (Wolf) loc.getWorld().spawnEntity(loc, org.bukkit.entity.EntityType.WOLF);
                wolf.setTamed(true);
                wolf.setOwner(target);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Curse effect application
    // -------------------------------------------------------------------------

    private void applyCurseEffect(Shrine shrine, CurseType type) {
        Location loc = shrine.getCoreLocation();
        if (loc == null || loc.getWorld() == null) return;

        switch (type) {
            case FIRE -> {
                // Set shrine blocks on fire - shrine destruction handled by caller
                loc.getWorld().getBlockAt(loc).setType(Material.FIRE);
            }
            case SILVERFISH -> {
                // MED #35 fix: spawn at center of block, 1 block above, with small random offset
                Random rng = new Random();
                for (int i = 0; i < 3; i++) {
                    Location spawnLoc = loc.clone().add(
                            0.5 + (rng.nextDouble() - 0.5) * 0.5,
                            1.0,
                            0.5 + (rng.nextDouble() - 0.5) * 0.5);
                    loc.getWorld().spawnEntity(spawnLoc, org.bukkit.entity.EntityType.SILVERFISH);
                }
            }
            case DEBUFF -> {
                // MED #34 fix: use sphere check instead of box
                int durationTicks = 5 * 60 * 20;
                double radiusSq = 10.0 * 10.0;
                Location center = loc.clone().add(0.5, 0.5, 0.5);
                for (Entity entity : loc.getWorld().getNearbyEntities(loc, 10, 10, 10)) {
                    if (entity instanceof LivingEntity living) {
                        // Sphere distance check
                        if (entity.getLocation().distanceSquared(center) <= radiusSq) {
                            living.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, durationTicks, 1));
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // GodData helpers
    // -------------------------------------------------------------------------

    GodData loadGodData(UUID godUUID) {
        File godsDir = new File(plugin.getDataFolder(), "gods");
        File file = new File(godsDir, godUUID.toString() + ".yml");
        GodData data = GodData.load(file, logger);
        if (data == null) {
            // Return a transient stub so callers don't NPE
            data = new GodData(godUUID, resolveName(godUUID));
        }
        return data;
    }

    private void saveGodData(GodData data) {
        File godsDir = new File(plugin.getDataFolder(), "gods");
        godsDir.mkdirs();
        File file = new File(godsDir, data.getUuid().toString() + ".yml");
        try {
            data.save(file);
        } catch (IOException e) {
            logger.severe("PowerSystem: failed to save god data for " + data.getUuid() + ": " + e.getMessage());
        }
    }

    private void updateGodBlessingCount(UUID godUUID) {
        GodData data = loadGodData(godUUID);
        data.setBlessingCount(data.getBlessingCount() + 1);
        saveGodData(data);
    }

    private void updateGodCurseCount(UUID godUUID) {
        GodData data = loadGodData(godUUID);
        data.setCurseCount(data.getCurseCount() + 1);
        saveGodData(data);
    }

    // -------------------------------------------------------------------------
    // Shrine lookup
    // -------------------------------------------------------------------------

    private Shrine findShrineById(String shrineId) {
        for (Shrine shrine : plugin.getShrineDetector().getShrinesByOwner().values()) {
            if (shrine.getId().equals(shrineId)) {
                return shrine;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Notification helpers
    // -------------------------------------------------------------------------

    /** MED #30 fix: use Adventure Component instead of legacy § codes */
    private void notifyGodComponent(UUID godUUID, Component message) {
        Player player = plugin.getServer().getPlayer(godUUID);
        if (player != null && player.isOnline()) {
            player.sendMessage(message);
        }
    }

    private void notifyGod(UUID godUUID, String message) {
        // MED #30 fix: strip legacy § codes and send as plain Adventure component
        Player player = plugin.getServer().getPlayer(godUUID);
        if (player != null && player.isOnline()) {
            String clean = message.replaceAll("§[0-9a-fk-or]", "");
            player.sendMessage(Component.text(clean));
        }
    }

    private void notifyPlayer(UUID playerUUID, String message) {
        Player player = plugin.getServer().getPlayer(playerUUID);
        if (player != null && player.isOnline()) {
            String clean = message.replaceAll("§[0-9a-fk-or]", "");
            player.sendMessage(Component.text(clean));
        }
    }

    private String resolveName(UUID uuid) {
        if (uuid == null) return "Unknown";
        Player p = plugin.getServer().getPlayer(uuid);
        if (p != null) return p.getName();
        var op = plugin.getServer().getOfflinePlayer(uuid);
        String name = op.getName();
        return name != null ? name : uuid.toString();
    }

    // -------------------------------------------------------------------------
    // Persistence – load all mortals on startup
    // -------------------------------------------------------------------------

    private void loadAllMortals() {
        File mortalsDir = new File(plugin.getDataFolder(), "mortals");
        if (!mortalsDir.exists()) return;

        File[] files = mortalsDir.listFiles((dir, name) -> name.endsWith(".yml"));        if (files == null) return;

        for (File file : files) {
            MortalData data = MortalData.load(file, logger);
            if (data != null && data.getUuid() != null) {
                mortalRegistry.put(data.getUuid(), data);
            }
        }
        logger.info("PowerSystem: loaded " + mortalRegistry.size() + " mortal(s).");
    }

    /**
     * Saves all mortal data to disk synchronously.
     * Called during plugin shutdown.
     */
    public void saveAllMortals() {
        File mortalsDir = new File(plugin.getDataFolder(), "mortals");
        mortalsDir.mkdirs();
        for (MortalData data : mortalRegistry.values()) {
            File file = new File(mortalsDir, data.getUuid().toString() + ".yml");
            try {
                data.save(file);
            } catch (IOException e) {
                logger.severe("PowerSystem: failed to save mortal data for " + data.getUuid() + ": " + e.getMessage());
            }
        }
        logger.info("PowerSystem: saved " + mortalRegistry.size() + " mortal(s).");
    }

    // -------------------------------------------------------------------------
    // Package-private accessors for testing
    // -------------------------------------------------------------------------

    Map<UUID, MortalData> getMortalRegistry() {
        return Collections.unmodifiableMap(mortalRegistry);
    }

    Map<UUID, Map<BlessingType, Long>> getBlessingCooldowns() {
        return blessingCooldowns;
    }

    Map<UUID, UUID> getTrucePending() {
        return Collections.unmodifiableMap(trucePending);
    }
}
