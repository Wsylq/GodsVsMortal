package com.example.godsvsmortals;

import com.example.godsvsmortals.enums.EventPhase;
import com.example.godsvsmortals.model.EventState;
import com.example.godsvsmortals.util.Clock;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Manages the Gods vs Mortals event lifecycle, phase transitions, and prophecy broadcasts.
 *
 * <p>Phase sequence: VOTING → DAY1 → DAY2 → DAY3 → RAGNAROK → ENDED
 *
 * <p>Ragnarok activates when 12 hours remain in DAY3 (i.e. after dayDuration - 12h have elapsed).
 * Prophecies are broadcast every 8 hours, cycling through the full pool before repeating.
 */
public class EventManager {

    private static final long RAGNAROK_DURATION_MS = 12L * 60 * 60 * 1000;
    private static final long PROPHECY_INTERVAL_MS = 8L * 60 * 60 * 1000;

    private final GodsVsMortalsPlugin plugin;
    private final Clock clock;
    private final Logger logger;

    // Durations (milliseconds) – read from config on startEvent / loadState
    private long votingDurationMs;
    private long dayDurationMs;

    // Mutable runtime state
    private EventState state;
    private BukkitTask tickTask;

    // Prophecy state
    private List<String> prophecyPool;
    private List<String> prophecyShuffled;
    private int prophecyIndex;
    private long lastProphecyMs;

    public EventManager(GodsVsMortalsPlugin plugin, Clock clock) {
        this.plugin = plugin;
        this.clock = clock;
        this.logger = plugin.getLogger();
        this.state = new EventState(); // defaults to ENDED
        loadConfig();
    }

    /**
     * Constructor for testing: allows explicit duration injection without reading plugin config.
     */
    EventManager(GodsVsMortalsPlugin plugin, Clock clock, long votingDurationMs, long dayDurationMs) {
        this.plugin = plugin;
        this.clock = clock;
        this.logger = plugin.getLogger();
        this.state = new EventState();
        this.votingDurationMs = votingDurationMs;
        this.dayDurationMs = dayDurationMs;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Starts the event from VOTING phase. No-op if already running. */
    public void startEvent() {
        if (state.getCurrentPhase() != EventPhase.ENDED) {
            logger.warning("startEvent() called but event is already running (phase=" + state.getCurrentPhase() + ")");
            return;
        }
        loadConfig();
        state = new EventState();
        transitionTo(EventPhase.VOTING);
        initProphecyPool();
        lastProphecyMs = clock.currentTimeMillis();
        scheduleTickTask();
        broadcastAll(Component.text("⚡ The Gods vs Mortals event has begun! Voting is now open.", NamedTextColor.GOLD));
        logger.info("Event started – entering VOTING phase.");
    }

    /** Immediately stops the event and resets all state. */
    public void stopEvent() {
        cancelTickTask();
        state = new EventState(); // resets to ENDED
        saveState();
        broadcastAll(Component.text("⚡ The Gods vs Mortals event has been stopped by an admin.", NamedTextColor.RED));
        logger.info("Event stopped and state reset.");
    }

    /** Returns the current event phase. */
    public EventPhase getCurrentPhase() {
        return state.getCurrentPhase();
    }

    /**
     * Returns elapsed time in the current phase in milliseconds.
     * Returns 0 if the event has not started.
     */
    public long getPhaseElapsed() {
        if (state.getPhaseStartTimestamp() == 0L) return 0L;
        return clock.currentTimeMillis() - state.getPhaseStartTimestamp();
    }

    /**
     * Called every second by the repeating BukkitTask.
     * Advances phase if the current phase duration has elapsed and handles prophecy broadcasts.
     */
    public void tick() {
        EventPhase current = state.getCurrentPhase();
        if (current == EventPhase.ENDED) return;

        long elapsed = getPhaseElapsed();
        state.setLastTickTimestamp(clock.currentTimeMillis());

        // Check phase transition
        long phaseDuration = getPhaseDuration(current);
        if (current != EventPhase.ENDED && elapsed >= phaseDuration) {
            advancePhase(current);
        }

        // Prophecy broadcast every 8 hours
        long now = clock.currentTimeMillis();
        if (now - lastProphecyMs >= PROPHECY_INTERVAL_MS) {
            broadcastProphecy();
            lastProphecyMs = now;
        }
    }

    /** Persists current event state to plugins/GodsVsMortals/state.yml. */
    public void saveState() {
        File stateFile = getStateFile();
        stateFile.getParentFile().mkdirs();
        try {
            state.save(stateFile);
            logger.info("Event state saved to " + stateFile.getPath());
        } catch (IOException e) {
            logger.severe("Failed to save event state: " + e.getMessage());
        }
    }

    /** Loads persisted state from disk and resumes timers if the event was active. */
    public void loadState() {
        File stateFile = getStateFile();
        state = EventState.load(stateFile, logger);
        logger.info("Event state loaded – phase=" + state.getCurrentPhase());

        if (state.getCurrentPhase() != EventPhase.ENDED) {
            loadConfig();
            initProphecyPool();
            // Restore prophecy timer: approximate last broadcast as phaseStart
            lastProphecyMs = state.getPhaseStartTimestamp();
            scheduleTickTask();
            logger.info("Resuming event timers after restart.");
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void loadConfig() {
        long votingMinutes = plugin.getConfig().getLong("event.voting-duration-minutes", 10);
        long dayHours = plugin.getConfig().getLong("event.day-duration-hours", 24);
        votingDurationMs = votingMinutes * 60 * 1000;
        dayDurationMs = dayHours * 60 * 60 * 1000;
    }

    private void initProphecyPool() {
        prophecyPool = new ArrayList<>(plugin.getConfig().getStringList("prophecies"));
        if (prophecyPool.isEmpty()) {
            prophecyPool.add("The gods grow restless. Blood will be spilled before dawn.");
        }
        prophecyShuffled = new ArrayList<>(prophecyPool);
        Collections.shuffle(prophecyShuffled);
        prophecyIndex = 0;
    }

    private void transitionTo(EventPhase next) {
        state.setCurrentPhase(next);
        state.setPhaseStartTimestamp(clock.currentTimeMillis());
        if (next == EventPhase.RAGNAROK) {
            state.setRagnarokActive(true);
        }
    }

    private void advancePhase(EventPhase current) {
        switch (current) {
            case VOTING -> {
                transitionTo(EventPhase.DAY1);
                broadcastAll(Component.text("⚡ Voting has ended! Day 1 (Rise) begins!", NamedTextColor.GOLD));
                logger.info("Phase transition: VOTING → DAY1");
            }
            case DAY1 -> {
                transitionTo(EventPhase.DAY2);
                // Assign unique powers to all gods at end of Day 1 (Req 7.1)
                List<UUID> godUUIDs = state.getGodUUIDs();
                if (!godUUIDs.isEmpty()) {
                    plugin.getPowerSystem().assignUniquePowers(godUUIDs);
                }
                // Reset daily quests for Day 2 (Req 11.3)
                if (plugin.getQuestSystem() != null) {
                    plugin.getQuestSystem().setCurrentDayNumber(2);
                    plugin.getQuestSystem().resetDailyQuests();
                }
                broadcastAll(Component.text("⚡ Day 1 has ended! Day 2 (Conflict) begins!", NamedTextColor.GOLD));
                logger.info("Phase transition: DAY1 → DAY2");
            }
            case DAY2 -> {
                transitionTo(EventPhase.DAY3);
                // Reset daily quests for Day 3 (Req 11.3)
                if (plugin.getQuestSystem() != null) {
                    plugin.getQuestSystem().setCurrentDayNumber(3);
                    plugin.getQuestSystem().resetDailyQuests();
                }
                broadcastAll(Component.text("⚡ Day 2 has ended! Day 3 (Reckoning) begins!", NamedTextColor.GOLD));
                logger.info("Phase transition: DAY2 → DAY3");
            }
            case DAY3 -> {
                // DAY3 transitions to RAGNAROK when 12 hours remain
                // (i.e. after dayDuration - 12h have elapsed)
                transitionTo(EventPhase.RAGNAROK);
                activateRagnarok();
                broadcastAll(Component.text(
                        "☠ RAGNAROK HAS BEGUN! The gods are now mortal – strike them down!", NamedTextColor.DARK_RED));
                logger.info("Phase transition: DAY3 → RAGNAROK");
            }
            case RAGNAROK -> {
                transitionTo(EventPhase.ENDED);
                cancelTickTask();
                broadcastFinalStandings();
                logger.info("Phase transition: RAGNAROK → ENDED");
            }
            default -> { /* ENDED – nothing to do */ }
        }
        saveState();
    }

    /**
     * Returns the duration of the given phase in milliseconds.
     * DAY3 is special: it transitions to RAGNAROK after (dayDuration - 12h), not the full day.
     */
    long getPhaseDuration(EventPhase phase) {
        return switch (phase) {
            case VOTING -> votingDurationMs;
            case DAY1, DAY2 -> dayDurationMs;
            case DAY3 -> Math.max(0, dayDurationMs - RAGNAROK_DURATION_MS); // transition when 12h remain
            case RAGNAROK -> RAGNAROK_DURATION_MS;
            case ENDED -> 0L;
        };
    }

    /**
     * Activates Ragnarok: removes god damage immunity, triggers Great Betrayal identification.
     * Requirements: 12.1, 12.2, 15.1
     */
    private void activateRagnarok() {
        state.setRagnarokActive(true);

        // Remove god damage immunity – gods are now fully killable (Req 12.1, 12.2)
        // The PowerSystem handles damage events; we just set the flag here.
        // Notify all gods that they are now mortal
        for (UUID godUUID : state.getGodUUIDs()) {
            org.bukkit.entity.Player god = Bukkit.getPlayer(godUUID);
            if (god != null && god.isOnline()) {
                god.sendMessage(net.kyori.adventure.text.Component.text(
                        "☠ RAGNAROK: You are now mortal. Mortals can deal full damage to you!",
                        net.kyori.adventure.text.format.NamedTextColor.DARK_RED));
            }
        }

        // Trigger Great Betrayal identification (Req 15.1)
        // The PowerSystem will identify the most-betrayed mortal and grant them /fallen steal
        if (plugin.getPowerSystem() != null) {
            plugin.getPowerSystem().identifyGreatBetrayalCandidate();
        }

        logger.info("EventManager: Ragnarok activated.");
    }

    private void broadcastProphecy() {
        if (prophecyShuffled == null || prophecyShuffled.isEmpty()) return;

        if (prophecyIndex >= prophecyShuffled.size()) {
            // Exhausted pool – reshuffle and start again
            Collections.shuffle(prophecyShuffled);
            prophecyIndex = 0;
        }

        String text = prophecyShuffled.get(prophecyIndex++);
        Component msg = Component.text()
                .append(Component.text("✦ Prophecy: ", NamedTextColor.GOLD, TextDecoration.ITALIC))
                .append(Component.text(text, NamedTextColor.GOLD, TextDecoration.ITALIC))
                .build();
        broadcastAll(msg);
    }

    private void broadcastFinalStandings() {
        broadcastAll(Component.text("⚡ The Gods vs Mortals event has ended! Final standings:", NamedTextColor.GOLD));
        // Detailed standings will be populated by other subsystems (FaithEngine, etc.)
        // For now broadcast a placeholder that other tasks will extend.
        broadcastAll(Component.text("  (Detailed standings will be shown once all subsystems are wired.)",
                NamedTextColor.YELLOW));
    }

    private void broadcastAll(Component message) {
        Bukkit.getServer().broadcast(message);
    }

    private void scheduleTickTask() {
        cancelTickTask();
        // 20 ticks = 1 second
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private void cancelTickTask() {
        if (tickTask != null && !tickTask.isCancelled()) {
            tickTask.cancel();
        }
        tickTask = null;
    }

    private File getStateFile() {
        return new File(plugin.getDataFolder(), "state.yml");
    }

    // -------------------------------------------------------------------------
    // Package-private accessors for testing
    // -------------------------------------------------------------------------

    /** Exposes internal state for testing purposes. */
    EventState getState() {
        return state;
    }

    /** Directly sets state (used in tests to inject a pre-built state). */
    void setState(EventState state) {
        this.state = state;
    }

    /** Exposes voting duration in ms (for tests). */
    long getVotingDurationMs() {
        return votingDurationMs;
    }

    /** Exposes day duration in ms (for tests). */
    long getDayDurationMs() {
        return dayDurationMs;
    }

    /** Returns the current prophecy index (for tests). */
    int getProphecyIndex() {
        return prophecyIndex;
    }

    /** Returns the shuffled prophecy list (for tests). */
    List<String> getProphecyShuffled() {
        return prophecyShuffled != null ? Collections.unmodifiableList(prophecyShuffled) : Collections.emptyList();
    }
}
