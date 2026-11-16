package com.vyxac;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class VyxAC extends JavaPlugin {
    private final Settings settings = new Settings();
    private final Map<UUID, PlayerData> data = new HashMap<>();
    private CheckRegistry checks;
    private TickMonitor ticks;
    private Violations vio;
    private PunishManager punish;
    private AiMonitor ai;
    private PacketChecks packets;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        checks = new CheckRegistry();
        // make sure every check has a complete section in config.yml (first start / new version)
        checks.registerDefaults(getConfig());
        getConfig().options().copyDefaults(true);
        saveConfig();

        ticks = new TickMonitor(this);
        vio = new Violations(this);
        punish = new PunishManager(this);
        ai = new AiMonitor(this);
        packets = new PacketChecks(this);
        loadAll();

        for (Player p : Bukkit.getOnlinePlayers()) data(p);

        Bukkit.getPluginManager().registerEvents(new MoveListener(this), this);
        Bukkit.getPluginManager().registerEvents(new CombatListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);
        CustomizeGui gui = new CustomizeGui(this);
        Bukkit.getPluginManager().registerEvents(gui, this);

        VyxCommand cmd = new VyxCommand(this, gui);
        PluginCommand pc = getCommand("vyxac");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }

        ticks.start();
        vio.startFlusher();
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            vio.decayAll();
            packets.secondTick();
        }, 20L, 20L);

        getLogger().info("VyxAC enabled: " + checks.all().size() + " checks, mode " + settings.punishMode
                + (settings.aiEnabled ? ", mini-AI on" : ", mini-AI off"));
    }

    @Override
    public void onDisable() {
        if (ai != null) ai.stop();
        if (vio != null) vio.flushLog();
        data.clear();
    }

    /** (Re)loads config.yml into the cached settings. Safe to call at any time. */
    public void loadAll() {
        reloadConfig();
        checks.registerDefaults(getConfig()); // missing keys fall back to defaults
        settings.load(getConfig());
        checks.load(getConfig());
        ai.reload(getConfig());
        for (PlayerData pd : data.values()) pd.forceBypassRecheck();
    }

    /** Writes a value into config.yml and reloads. Returns false if the path does not exist. */
    public boolean setConfigValue(String path, Object value) {
        if (!getConfig().contains(path)) return false;
        getConfig().set(path, value);
        saveConfig();
        loadAll();
        return true;
    }

    public Settings settings() { return settings; }
    public CheckRegistry checks() { return checks; }
    public TickMonitor ticks() { return ticks; }
    public Violations vio() { return vio; }
    public PunishManager punish() { return punish; }
    public AiMonitor ai() { return ai; }

    public PlayerData data(Player p) {
        PlayerData pd = data.get(p.getUniqueId());
        if (pd == null) {
            pd = new PlayerData(p);
            data.put(p.getUniqueId(), pd);
        } else {
            pd.player = p;
        }
        return pd;
    }

    public PlayerData dataOrNull(UUID id) {
        return data.get(id);
    }

    public Collection<PlayerData> allData() {
        return data.values();
    }

    public void remove(UUID id) {
        data.remove(id);
    }
}
