package com.vyxac;

import com.vyxac.hacks.HackChecker;
import com.vyxac.hacks.HackCommands;
import com.vyxac.hacks.HackListener;
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
    private Diagnostics diagnostics;
    private ReportManager reports;
    private StaffManager staff;
    private StaffGui staffGui;
    private HackChecker hacks;

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
        diagnostics = new Diagnostics(this);
        reports = new ReportManager(this);
        staff = new StaffManager(this);
        staffGui = new StaffGui(this);
        loadAll();
        try {
            ai.loadModel();
        } catch (Throwable t) {
            getLogger().log(java.util.logging.Level.WARNING, "ai-model.yml could not be read, using defaults", t);
        }
        try {
            reports.load();
        } catch (Throwable t) {
            getLogger().log(java.util.logging.Level.WARNING, "reports.yml could not be read", t);
        }

        for (Player p : Bukkit.getOnlinePlayers()) data(p);

        Bukkit.getPluginManager().registerEvents(new MoveListener(this), this);
        Bukkit.getPluginManager().registerEvents(new CombatListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);
        CustomizeGui gui = new CustomizeGui(this);
        Bukkit.getPluginManager().registerEvents(gui, this);
        Bukkit.getPluginManager().registerEvents(staffGui, this);
        Bukkit.getPluginManager().registerEvents(new StaffListener(this, staffGui), this);

        // the main command first, so VyxAC stays usable no matter what an optional module does
        VyxCommand cmd = new VyxCommand(this, gui, new ExtraCommands(this, staffGui, new SelfTest(this)));
        PluginCommand pc = getCommand("vyxac");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }

        // CheckHacks is optional: any problem in it must never stop the rest of the plugin
        try {
            hacks = new HackChecker(this);
            hacks.start();
            Bukkit.getPluginManager().registerEvents(new HackListener(this), this);
            HackCommands hackCmd = new HackCommands(this);
            for (String n : new String[]{"checkhacks", "chalerts", "cheditor", "checkhacksupdate", "checklang"}) {
                PluginCommand hc = getCommand(n);
                if (hc != null) {
                    hc.setExecutor(hackCmd);
                    hc.setTabCompleter(hackCmd);
                }
            }
        } catch (Throwable t) {
            hacks = null;
            getLogger().log(java.util.logging.Level.SEVERE, "CheckHacks could not start and was switched off; everything else keeps working", t);
        }

        ticks.start();
        vio.startFlusher();
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            vio.decayAll();
            packets.secondTick();
            diagnostics.secondTick();
        }, 20L, 20L);

        getLogger().info("VyxAC enabled: " + checks.all().size() + " checks, mode " + settings.punishMode
                + (settings.aiEnabled ? ", mini-AI on" : ", mini-AI off"));
    }

    @Override
    public void onDisable() {
        if (ai != null) {
            ai.stop();
            ai.saveModel();
        }
        try {
            if (hacks != null) hacks.stop();
        } catch (Throwable ignored) {
            // shutting down
        }
        if (staff != null) staff.exitAll(); // everybody gets their real inventory back
        if (reports != null) reports.save();
        if (diagnostics != null) diagnostics.finish(true); // partial report of the current day
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
        diagnostics.reload(getConfig());
        if (reports != null) reports.reload(getConfig());
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

    /** Writes several values at once (single save + reload). Unknown paths are skipped. */
    public void setConfigValues(Map<String, Object> values) {
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (getConfig().contains(e.getKey())) getConfig().set(e.getKey(), e.getValue());
        }
        saveConfig();
        loadAll();
    }

    public Settings settings() { return settings; }
    public CheckRegistry checks() { return checks; }
    public TickMonitor ticks() { return ticks; }
    public Violations vio() { return vio; }
    public PunishManager punish() { return punish; }
    public AiMonitor ai() { return ai; }
    public Diagnostics diagnostics() { return diagnostics; }
    public ReportManager reports() { return reports; }
    public StaffManager staff() { return staff; }
    public StaffGui staffGui() { return staffGui; }
    public HackChecker hacks() { return hacks; }
    public java.io.File jarFile() { return getFile(); }

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
