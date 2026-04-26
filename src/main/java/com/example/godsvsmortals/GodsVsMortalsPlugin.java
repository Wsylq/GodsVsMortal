package com.example.godsvsmortals;

import com.example.godsvsmortals.command.*;
import com.example.godsvsmortals.util.Clock;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.PluginManager;

/**
 * Main plugin class for Gods vs Mortals.
 * Subsystems are initialized in onEnable and torn down in onDisable.
 */
public class GodsVsMortalsPlugin extends JavaPlugin {

    private static GodsVsMortalsPlugin instance;

    private EventManager eventManager;
    private VoteSystem voteSystem;
    private ShrineDetector shrineDetector;
    private FaithEngine faithEngine;
    private PowerSystem powerSystem;
    private NotificationSystem notificationSystem;
    private QuestSystem questSystem;
    private ChatSystem chatSystem;
    private TitleSystem titleSystem;
    private ScoreboardManager scoreboardManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        // Initialise EventManager with the real system clock
        eventManager = new EventManager(this, Clock.system());

        // Initialise VoteSystem
        voteSystem = new VoteSystem(this);

        // Initialise ShrineDetector and register as a Bukkit listener
        shrineDetector = new ShrineDetector(this);
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(shrineDetector, this);

        // Initialise FaithEngine and load persisted faith totals
        faithEngine = new FaithEngine(this);
        faithEngine.loadAllFaith();

        // Initialise NotificationSystem and register as a Bukkit listener
        notificationSystem = new NotificationSystem(this);
        pm.registerEvents(notificationSystem, this);

        // Initialise PowerSystem and register as a Bukkit listener
        powerSystem = new PowerSystem(this);
        pm.registerEvents(powerSystem, this);

        // Initialise QuestSystem
        questSystem = new QuestSystem(this);

        // Initialise ChatSystem
        chatSystem = new ChatSystem(this);

        // Initialise TitleSystem and register as a Bukkit listener
        titleSystem = new TitleSystem(this);
        pm.registerEvents(titleSystem, this);

        // Initialise ScoreboardManager
        scoreboardManager = new ScoreboardManager(this);
        pm.registerEvents(scoreboardManager, this);

        // Load persisted state and resume timers if an event was active
        eventManager.loadState();

        // Apply catch-up faith for any downtime since the last tick
        long lastTick = eventManager.getState().getLastTickTimestamp();
        faithEngine.applyCatchUpFaith(lastTick);

        // Register repeating BukkitTask: distribute faith every 60 seconds (1200 ticks)
        getServer().getScheduler().runTaskTimer(this, faithEngine::distributeFaith, 1200L, 1200L);

        // Register repeating BukkitTask: tick betrayal rituals every second (20 ticks)
        getServer().getScheduler().runTaskTimer(this, powerSystem::tickBetrayalRituals, 20L, 20L);

        // Register repeating BukkitTask: refresh scoreboard every 5 seconds (100 ticks)
        getServer().getScheduler().runTaskTimer(this, scoreboardManager::refresh, 100L, 100L);

        // Register commands
        var gvmCmd = getCommand("gvm");
        if (gvmCmd != null) {
            GvmCommand gvmExecutor = new GvmCommand(eventManager);
            gvmCmd.setExecutor(gvmExecutor);
            gvmCmd.setTabCompleter(gvmExecutor);
        }

        var voteCmd = getCommand("vote");
        if (voteCmd != null) {
            VoteCommand voteExecutor = new VoteCommand(this, voteSystem);
            voteCmd.setExecutor(voteExecutor);
            voteCmd.setTabCompleter(voteExecutor);
        }

        var godCmd = getCommand("god");
        if (godCmd != null) {
            GodCommand godExecutor = new GodCommand(this, powerSystem);
            godCmd.setExecutor(godExecutor);
            godCmd.setTabCompleter(godExecutor);
        }

        var betrayCmd = getCommand("betray");
        if (betrayCmd != null) {
            betrayCmd.setExecutor(new BetrayCommand(powerSystem));
        }

        var fallenCmd = getCommand("fallen");
        if (fallenCmd != null) {
            FallenCommand fallenExecutor = new FallenCommand(powerSystem);
            fallenCmd.setExecutor(fallenExecutor);
            fallenCmd.setTabCompleter(fallenExecutor);
        }

        var sacrificeCmd = getCommand("sacrifice");
        if (sacrificeCmd != null) {
            SacrificeCommand sacrificeExecutor = new SacrificeCommand(this, powerSystem);
            sacrificeCmd.setExecutor(sacrificeExecutor);
            sacrificeCmd.setTabCompleter(sacrificeExecutor);
        }

        var shrineCmd = getCommand("shrine");
        if (shrineCmd != null) {
            ShrineCommand shrineExecutor = new ShrineCommand(this);
            shrineCmd.setExecutor(shrineExecutor);
            shrineCmd.setTabCompleter(shrineExecutor);
        }

        var prayCmd = getCommand("pray");
        if (prayCmd != null) {
            prayCmd.setExecutor(new PrayCommand(this, chatSystem, questSystem));
        }

        var blessCmd = getCommand("bless");
        if (blessCmd != null) {
            BlessCommand blessExecutor = new BlessCommand(this, chatSystem);
            blessCmd.setExecutor(blessExecutor);
            blessCmd.setTabCompleter(blessExecutor);
        }

        getLogger().info("GodsVsMortals enabled.");
    }

    @Override
    public void onDisable() {
        // Save all state synchronously before the server shuts down (Req 1.8, 11.4, 21.4)
        if (eventManager != null) {
            eventManager.saveState();
        }
        if (faithEngine != null) {
            faithEngine.saveAllFaith();
        }
        if (notificationSystem != null) {
            notificationSystem.saveQueue();
        }
        if (powerSystem != null) {
            powerSystem.saveAllMortals();
        }
        if (questSystem != null) {
            questSystem.saveAllMortals();
        }
        if (shrineDetector != null) {
            shrineDetector.saveAllShrines();
        }
        getLogger().info("GodsVsMortals disabled.");
        instance = null;
    }

    public static GodsVsMortalsPlugin getInstance() {
        return instance;
    }

    public EventManager getEventManager() {
        return eventManager;
    }

    public VoteSystem getVoteSystem() {
        return voteSystem;
    }

    public ShrineDetector getShrineDetector() {
        return shrineDetector;
    }

    public FaithEngine getFaithEngine() {
        return faithEngine;
    }

    public PowerSystem getPowerSystem() {
        return powerSystem;
    }

    public NotificationSystem getNotificationSystem() {
        return notificationSystem;
    }

    public QuestSystem getQuestSystem() {
        return questSystem;
    }

    public ChatSystem getChatSystem() {
        return chatSystem;
    }

    public TitleSystem getTitleSystem() {
        return titleSystem;
    }

    public ScoreboardManager getScoreboardManager() {
        return scoreboardManager;
    }
}
