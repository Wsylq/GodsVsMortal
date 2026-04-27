package com.example.godsvsmortals;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Manages the god-election voting phase.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Accept / reject votes from players</li>
 *   <li>Close voting and elect the top-3 players as gods</li>
 *   <li>Extend the voting window when fewer than 3 candidates have votes</li>
 *   <li>Broadcast voting start, elected gods, and any extensions</li>
 * </ul>
 *
 * Requirements: 2.1 – 2.10
 */
public class VoteSystem {

    private final GodsVsMortalsPlugin plugin;
    private final Logger logger;

    /** voter UUID → candidate UUID */
    private final Map<UUID, UUID> voterToCandidate = new HashMap<>();

    /** candidate UUID → vote count */
    private final Map<UUID, Integer> voteTallies = new HashMap<>();

    /** UUIDs that have already been elected as gods (cannot receive votes) */
    private final Set<UUID> godUUIDs = new HashSet<>();

    private long extensionDurationMs;

    public VoteSystem(GodsVsMortalsPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        loadConfig();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Records a vote from {@code voter} for the player named {@code candidateName}.
     * MED #27 fix: allow voting for offline players who have played before.
     * MED #38 fix: reject votes outside VOTING phase.
     *
     * @return {@code true} if the vote was accepted, {@code false} if rejected
     */
    public boolean castVote(Player voter, String candidateName) {
        UUID voterUUID = voter.getUniqueId();

        // MED #38 fix: only allow voting during VOTING phase
        com.example.godsvsmortals.enums.EventPhase phase = plugin.getEventManager().getCurrentPhase();
        if (phase != com.example.godsvsmortals.enums.EventPhase.VOTING) {
            voter.sendMessage(Component.text("Voting is not currently open.", NamedTextColor.RED));
            return false;
        }

        // Self-vote rejection (Req 2.4)
        if (voter.getName().equalsIgnoreCase(candidateName)) {
            voter.sendMessage(Component.text(
                    "You cannot vote for yourself.", NamedTextColor.RED));
            return false;
        }

        // Duplicate vote rejection (Req 2.5)
        if (voterToCandidate.containsKey(voterUUID)) {
            voter.sendMessage(Component.text(
                    "You have already cast your vote.", NamedTextColor.RED));
            return false;
        }

        // MED #27 fix: resolve candidate online first, then offline fallback
        UUID candidateUUID;
        String resolvedName;
        Player candidate = Bukkit.getPlayerExact(candidateName);
        if (candidate != null) {
            candidateUUID = candidate.getUniqueId();
            resolvedName = candidate.getName();
        } else {
            @SuppressWarnings("deprecation")
            OfflinePlayer offline = Bukkit.getOfflinePlayer(candidateName);
            if (!offline.hasPlayedBefore()) {
                voter.sendMessage(Component.text(
                        "Player '" + candidateName + "' not found.", NamedTextColor.RED));
                return false;
            }
            candidateUUID = offline.getUniqueId();
            resolvedName = offline.getName() != null ? offline.getName() : candidateName;
        }

        // Prevent voting for an already-elected god (Req 2.10)
        if (godUUIDs.contains(candidateUUID)) {
            voter.sendMessage(Component.text(
                    candidateName + " is already a god and cannot receive votes.", NamedTextColor.RED));
            return false;
        }

        // Record the vote (Req 2.3)
        voterToCandidate.put(voterUUID, candidateUUID);
        voteTallies.merge(candidateUUID, 1, Integer::sum);

        voter.sendMessage(Component.text(
                "Your vote for " + resolvedName + " has been recorded.", NamedTextColor.GREEN));
        return true;
    }

    /**
     * Closes voting and elects the top-3 players as gods.
     * HIGH #7 fix: auto-assigns godsvsmortals.god permission to elected gods.
     */
    public List<UUID> closeVoting() {
        // Count distinct candidates with at least 1 vote
        long distinctCandidates = voteTallies.values().stream().filter(v -> v > 0).count();

        if (distinctCandidates < 3) {
            // Extend voting (Req 2.7) - extendVoting() now actually shifts the timer
            extendVoting();
            logger.info("Voting extended – only " + distinctCandidates + " candidate(s) have votes.");
            return Collections.emptyList();
        }

        List<UUID> elected = electTopThree();

        // Assign god role (Req 2.9)
        godUUIDs.addAll(elected);
        plugin.getEventManager().getState().setGodUUIDs(elected);

        // HIGH #7 fix: auto-assign godsvsmortals.god permission to elected gods
        for (UUID godUUID : elected) {
            Player god = Bukkit.getPlayer(godUUID);
            if (god != null && god.isOnline()) {
                god.addAttachment(plugin, "godsvsmortals.god", true);
            }
        }

        // Broadcast elected gods (Req 2.9)
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < elected.size(); i++) {
            Player p = Bukkit.getPlayer(elected.get(i));
            names.append(p != null ? p.getName() : elected.get(i).toString());
            if (i < elected.size() - 1) names.append(", ");
        }
        broadcastAll(Component.text(
                "⚡ Voting has ended! The gods are: " + names, NamedTextColor.GOLD));
        logger.info("Gods elected: " + names);

        return Collections.unmodifiableList(elected);
    }

    /**
     * Returns a snapshot of the current vote tallies (candidateUUID → count).
     */
    public Map<UUID, Integer> getVoteTallies() {
        return Collections.unmodifiableMap(new HashMap<>(voteTallies));
    }

    /**
     * Extends the voting window by the configured extension duration.
     * HIGH #9 fix: actually adjusts the phase duration via EventManager.addVotingExtension().
     * Broadcasts the extension to all online players (Req 2.7).
     */
    public void extendVoting() {
        // #12 fix: use EventManager.addVotingExtension() instead of modifying phaseStartTimestamp
        plugin.getEventManager().addVotingExtension(extensionDurationMs);
        broadcastAll(Component.text(
                "⚡ Voting has been extended by " + (extensionDurationMs / 60_000L) + " minutes!",
                NamedTextColor.YELLOW));
        logger.info("Voting window extended by " + extensionDurationMs + " ms.");
    }

    /**
     * Broadcasts the start of the voting phase to all online players (Req 2.1, 2.2).
     *
     * @param durationMs voting duration in milliseconds
     */
    public void broadcastVotingStart(long durationMs) {
        long minutes = durationMs / 60_000L;
        broadcastAll(Component.text(
                "⚡ Voting has begun! Use /vote <player> to elect your gods. You have "
                        + minutes + " minutes.",
                NamedTextColor.GOLD));
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void loadConfig() {
        long extensionMinutes = plugin.getConfig().getLong("event.voting-extension-minutes", 5);
        extensionDurationMs = extensionMinutes * 60_000L;
    }

    /**
     * Selects the top-3 candidates by vote count, breaking ties randomly for the final slot.
     */
    List<UUID> electTopThree() {
        // Sort candidates by vote count descending
        List<Map.Entry<UUID, Integer>> sorted = new ArrayList<>(voteTallies.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        List<UUID> result = new ArrayList<>();

        // Add the clear top-2 (or fewer if not enough candidates)
        int i = 0;
        while (result.size() < 2 && i < sorted.size()) {
            result.add(sorted.get(i).getKey());
            i++;
        }

        if (i >= sorted.size()) {
            return result; // fewer than 3 candidates total
        }

        // Determine the vote count threshold for the 3rd slot
        int thirdSlotVotes = sorted.get(i).getValue();

        // Collect all candidates tied at that vote count
        List<UUID> tiedCandidates = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : sorted) {
            if (entry.getValue() == thirdSlotVotes && !result.contains(entry.getKey())) {
                tiedCandidates.add(entry.getKey());
            }
        }

        // LOW #41 fix: use ThreadLocalRandom instead of new Random()
        UUID chosen = tiedCandidates.get(ThreadLocalRandom.current().nextInt(tiedCandidates.size()));
        result.add(chosen);

        return result;
    }

    private void broadcastAll(Component message) {
        Bukkit.getServer().broadcast(message);
    }

    // -------------------------------------------------------------------------
    // Package-private accessors for testing
    // -------------------------------------------------------------------------

    /** Directly sets the god UUIDs (used in tests). */
    void setGodUUIDs(Set<UUID> uuids) {
        godUUIDs.clear();
        godUUIDs.addAll(uuids);
    }

    /** Directly injects vote tallies (used in tests). */
    void setVoteTallies(Map<UUID, Integer> tallies) {
        voteTallies.clear();
        voteTallies.putAll(tallies);
    }

    /** Directly injects voter-to-candidate map (used in tests). */
    void setVoterToCandidate(Map<UUID, UUID> map) {
        voterToCandidate.clear();
        voterToCandidate.putAll(map);
    }

    /** Returns the extension duration in ms (for tests). */
    long getExtensionDurationMs() {
        return extensionDurationMs;
    }
}
