package com.example.godsvsmortals;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
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
     *
     * @return {@code true} if the vote was accepted, {@code false} if rejected
     */
    public boolean castVote(Player voter, String candidateName) {
        UUID voterUUID = voter.getUniqueId();

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

        // Resolve candidate by name
        Player candidate = Bukkit.getPlayerExact(candidateName);
        if (candidate == null) {
            voter.sendMessage(Component.text(
                    "Player '" + candidateName + "' is not online.", NamedTextColor.RED));
            return false;
        }

        UUID candidateUUID = candidate.getUniqueId();

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
                "Your vote for " + candidate.getName() + " has been recorded.", NamedTextColor.GREEN));
        return true;
    }

    /**
     * Closes voting and elects the top-3 players as gods.
     *
     * <p>If fewer than 3 candidates have votes, the voting window is extended instead
     * and an empty list is returned (Req 2.7).
     *
     * <p>Ties for the final slot are broken randomly (Req 2.8).
     *
     * @return list of elected god UUIDs (size 3), or empty list if voting was extended
     */
    public List<UUID> closeVoting() {
        // Count distinct candidates with at least 1 vote
        long distinctCandidates = voteTallies.values().stream().filter(v -> v > 0).count();

        if (distinctCandidates < 3) {
            // Extend voting (Req 2.7)
            extendVoting();
            broadcastAll(Component.text(
                    "⚡ Not enough candidates have votes yet. Voting has been extended by "
                            + (extensionDurationMs / 60_000L) + " minutes!",
                    NamedTextColor.YELLOW));
            logger.info("Voting extended – only " + distinctCandidates + " candidate(s) have votes.");
            return Collections.emptyList();
        }

        List<UUID> elected = electTopThree();

        // Assign god role (Req 2.9)
        godUUIDs.addAll(elected);
        plugin.getEventManager().getState().setGodUUIDs(elected);

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
     * Broadcasts the extension to all online players (Req 2.7).
     */
    public void extendVoting() {
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

        // Randomly pick one from the tied set (Req 2.8)
        UUID chosen = tiedCandidates.get(new Random().nextInt(tiedCandidates.size()));
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
