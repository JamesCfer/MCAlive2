package dev.celestia.mcalive2;

import dev.celestia.mcalive2.actuators.LedgerActuators;
import dev.celestia.mcalive2.actuators.NpcActuators;
import dev.celestia.mcalive2.actuators.PlayerActuators;
import dev.celestia.mcalive2.actuators.SpectacleActuators;
import dev.celestia.mcalive2.actuators.WorldActuators;
import dev.celestia.mcalive2.bridge.BridgeServer;
import dev.celestia.mcalive2.bridge.CommandDispatcher;
import dev.celestia.mcalive2.formula.FormulaActuators;
import dev.celestia.mcalive2.gadget.GadgetActuators;
import dev.celestia.mcalive2.gadget.GadgetManager;
import dev.celestia.mcalive2.npc.DefenseManager;
import dev.celestia.mcalive2.npc.JobManager;
import dev.celestia.mcalive2.npc.NpcManager;
import dev.celestia.mcalive2.npc.behavior.BehaviorActuators;
import dev.celestia.mcalive2.npc.behavior.BehaviorEngine;
import dev.celestia.mcalive2.senses.ExploredTracker;
import dev.celestia.mcalive2.senses.GameListeners;
import dev.celestia.mcalive2.senses.IdleSceneTracker;
import dev.celestia.mcalive2.senses.RegionSense;
import dev.celestia.mcalive2.senses.SpawnGate;
import dev.celestia.mcalive2.senses.VillagerPurge;
import dev.celestia.mcalive2.senses.WorldTurnTracker;
import dev.celestia.mcalive2.update.Updater;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetSocketAddress;

public final class MCAlive2Plugin extends JavaPlugin {

    private BridgeServer bridge;
    private NpcManager npcManager;
    private DefenseManager defenseManager;
    private LedgerActuators ledgerActuators;
    private ExploredTracker exploredTracker;
    private FormulaActuators formulaActuators;
    private GadgetManager gadgetManager;
    private BehaviorEngine behaviorEngine;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        npcManager = new NpcManager(this);
        npcManager.load();

        defenseManager = new DefenseManager(this, npcManager);

        ledgerActuators = new LedgerActuators(this);
        ledgerActuators.load();

        exploredTracker = new ExploredTracker(this);

        CommandDispatcher dispatcher = new CommandDispatcher(this);
        new WorldActuators(this).register(dispatcher);
        new SpectacleActuators(this).register(dispatcher);
        new PlayerActuators(this).register(dispatcher);
        new NpcActuators(this, npcManager).register(dispatcher);
        ledgerActuators.register(dispatcher);
        new JobManager(this, npcManager, defenseManager::isEngaged).register(dispatcher);
        formulaActuators = new FormulaActuators(this, dispatcher);
        formulaActuators.load();
        formulaActuators.register(dispatcher);

        gadgetManager = new GadgetManager(this, dispatcher);
        gadgetManager.loadAll();
        new GadgetActuators(this, gadgetManager).register(dispatcher);

        behaviorEngine = new BehaviorEngine(this, npcManager, defenseManager::isEngaged);
        behaviorEngine.load();
        new BehaviorActuators(this, npcManager, behaviorEngine).register(dispatcher);
        if (getConfig().getBoolean("behavior.enabled", true)) {
            behaviorEngine.start();
        }

        String host = getConfig().getString("bridge.host", "127.0.0.1");
        int port = getConfig().getInt("bridge.port", 8765);
        String token = getConfig().getString("bridge.token", "change-me");
        bridge = new BridgeServer(new InetSocketAddress(host, port), this, dispatcher, token);
        bridge.setReuseAddr(true);
        bridge.start();

        getServer().getPluginManager().registerEvents(
                new GameListeners(this, npcManager, bridge, ledgerActuators, exploredTracker, defenseManager), this);
        getServer().getPluginManager().registerEvents(new SpawnGate(this), this);
        getServer().getPluginManager().registerEvents(new VillagerPurge(this, npcManager), this);

        // clean up any duplicate/orphaned NPC entities once the world has settled
        getServer().getScheduler().runTaskLater(this, () -> {
            int removed = npcManager.sweepAllLoaded();
            if (removed > 0) getLogger().info("Removed " + removed + " duplicate/orphaned NPC entities");
        }, 60L);

        // NPC routine tick, every 5 seconds
        getServer().getScheduler().runTaskTimer(this, () -> npcManager.tickRoutines(), 100L, 100L);
        // Autosave NPCs + ledger + explored cells every 5 minutes
        getServer().getScheduler().runTaskTimer(this, () -> {
            npcManager.save();
            ledgerActuators.save();
            exploredTracker.saveIfDirty();
            formulaActuators.save();
            behaviorEngine.save();
        }, 6000L, 6000L);

        // player_idle_scene heartbeat sense, period from config (0 = disabled)
        int idleMinutes = getConfig().getInt("idle-scene-minutes", 12);
        if (idleMinutes > 0) {
            IdleSceneTracker idleTracker = new IdleSceneTracker(this, bridge);
            long idlePeriodTicks = idleMinutes * 60L * 20L;
            getServer().getScheduler().runTaskTimer(this, idleTracker::tick, idlePeriodTicks, idlePeriodTicks);
        }

        // world_turn heartbeat sense: elapsed period checked once a minute so config
        // reloads of world-turn-minutes (including 0 = disabled) take effect live
        WorldTurnTracker worldTurnTracker = new WorldTurnTracker(this, bridge);
        getServer().getScheduler().runTaskTimer(this, worldTurnTracker::tick, 1200L, 1200L);

        // region_enter / region_exit sense, every 20 ticks (1s)
        RegionSense regionSense = new RegionSense(bridge, ledgerActuators.ledger());
        getServer().getScheduler().runTaskTimer(this, regionSense::tick, 20L, 20L);

        // self-update check against GitHub releases
        new Updater(this).checkAsync();

        getLogger().info("MCAlive2 bridge listening on ws://" + host + ":" + port);
        if ("change-me".equals(token)) {
            getLogger().warning("bridge.token is still the default! Set a real token in config.yml.");
        }
    }

    @Override
    public void onDisable() {
        if (defenseManager != null) defenseManager.disengageAll();
        if (behaviorEngine != null) {
            behaviorEngine.stop(); // also releases every chunk ticket
            behaviorEngine.save();
        }
        if (npcManager != null) npcManager.save();
        if (ledgerActuators != null) ledgerActuators.save();
        if (exploredTracker != null) exploredTracker.save();
        if (formulaActuators != null) formulaActuators.save();
        if (bridge != null) {
            try {
                bridge.stop(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public BridgeServer bridge() {
        return bridge;
    }

    public NpcManager npcManager() {
        return npcManager;
    }

    public DefenseManager defenseManager() {
        return defenseManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(Component.text("[MCAlive2] bridge clients: " + bridge.clientCount()
                    + ", NPCs: " + npcManager.count()));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage(Component.text("[MCAlive2] config reloaded (bridge restart requires server restart)"));
            return true;
        }
        return false;
    }
}
