package me.benjamin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.ChunkSnapshot;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class LagRegionReducer extends JavaPlugin implements Listener, CommandExecutor {
    private Map<UUID, Integer> playerViewDists = new HashMap<>();
    private Map<UUID, Integer> playerSimDists = new HashMap<>();
    private Map<String, Region> manualRegions = new HashMap<>();
    private Map<String, Set<ChunkPos>> flaggedChunks = new HashMap<>();
    private int defaultViewDist;
    private int defaultSimDist;
    private int lowViewDist = 5;
    private int lowSimDist = 4;
    private boolean autoDetectEnabled;
    private double densityThreshold;
    private int minCobbleCount;
    private int neighborRadius;
    private int scanMinY, scanMaxY;
    private List<Material> badBlocks;
    private int maxFlagsPerWorld;

    // Per-player
    private Map<UUID, Boolean> playerEnabled = new HashMap<>();
    private Map<UUID, Integer> playerLowView = new HashMap<>();
    private Map<UUID, Integer> playerLowSim = new HashMap<>();
    private File playerFile;
    private FileConfiguration playerConfig;

    public record ChunkPos(int x, int z) {}
    public record Region(String world, double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int viewDist, int simDist) {
        public boolean contains(Location loc) {
            return loc.getWorld().getName().equals(world) &&
                   loc.getX() >= minX && loc.getX() <= maxX &&
                   loc.getY() >= minY && loc.getY() <= maxY &&
                   loc.getZ() >= minZ && loc.getZ() <= maxZ;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        loadConfigValues();
        loadManualRegions();
        loadFlaggedChunks();
        loadPlayerData();  // New
        getServer().getPluginManager().registerEvents(this, this);
        this.getCommand("lagregion").setExecutor(this);
        defaultViewDist = getServer().getViewDistance();
        defaultSimDist = getServer().getSimulationDistance();

        // Throttle checks every 40 ticks (2s)
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : getServer().getOnlinePlayers()) {
                    checkAndSet(p);
                }
            }
        }.runTaskTimer(this, 0L, 40L);
    }

    private void loadConfigValues() {
        autoDetectEnabled = getConfig().getBoolean("auto-detect", true);
        densityThreshold = getConfig().getDouble("density-threshold", 0.35);
        minCobbleCount = getConfig().getInt("min-cobble-count", 15000);
        lowViewDist = getConfig().getInt("low-view-dist", 5);
        lowSimDist = getConfig().getInt("low-sim-dist", 4);
        neighborRadius = getConfig().getInt("neighbor-radius", 1);
        scanMinY = Math.max(0, getConfig().getInt("scan-min-y", 0));
        scanMaxY = Math.min(255, getConfig().getInt("scan-max-y", 200));
        maxFlagsPerWorld = getConfig().getInt("max-flags-per-world", 10000);

        List<String> blockNames = getConfig().getStringList("block-types");
        badBlocks = new ArrayList<>();
        for (String name : blockNames) {
            try {
                badBlocks.add(Material.valueOf(name.toUpperCase()));
            } catch (IllegalArgumentException e) {
                getLogger().warning("Invalid block type: " + name);
            }
        }
        if (badBlocks.isEmpty()) badBlocks.addAll(Arrays.asList(Material.COBBLESTONE, Material.STONE));
    }

    private void loadManualRegions() {
        manualRegions.clear();
        ConfigurationSection regs = getConfig().getConfigurationSection("regions");
        if (regs != null) {
            for (String key : regs.getKeys(false)) {
                ConfigurationSection r = regs.getConfigurationSection(key);
                manualRegions.put(key, new Region(r.getString("world"),
                        r.getDouble("minX"), r.getDouble("minY"), r.getDouble("minZ"),
                        r.getDouble("maxX"), r.getDouble("maxY"), r.getDouble("maxZ"),
                        r.getInt("viewDist"), r.getInt("simDist")));
            }
        }
    }

    private void loadFlaggedChunks() {
        flaggedChunks.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("flagged-chunks");
        if (sec != null) {
            for (String w : sec.getKeys(false)) {
                List<String> list = getConfig().getStringList("flagged-chunks." + w);
                Set<ChunkPos> set = new HashSet<>();
                for (String s : list) {
                    String[] parts = s.split(",");
                    if (parts.length == 2) {
                        set.add(new ChunkPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
                    }
                }
                flaggedChunks.put(w, set);
            }
        }
    }

    private void saveFlaggedChunks() {
        getConfig().set("flagged-chunks", null);
        for (Map.Entry<String, Set<ChunkPos>> entry : flaggedChunks.entrySet()) {
            List<String> list = new ArrayList<>();
            for (ChunkPos pos : entry.getValue()) {
                list.add(pos.x() + "," + pos.z());
            }
            getConfig().set("flagged-chunks." + entry.getKey(), list);
        }
        saveConfig();
    }

    // New: Player data
    private void loadPlayerData() {
        playerFile = new File(getDataFolder(), "players.yml");
        if (!playerFile.exists()) {
            try {
                playerFile.createNewFile();
            } catch (IOException e) {
                getLogger().warning("Failed to create players.yml");
            }
        }
        playerConfig = YamlConfiguration.loadConfiguration(playerFile);
        for (Player p : getServer().getOnlinePlayers()) {
            loadPlayer(p.getUniqueId());
        }
    }

    private void loadPlayer(UUID uuid) {
        String path = uuid.toString();
        playerEnabled.put(uuid, playerConfig.getBoolean(path + ".enabled", true));
        playerLowView.put(uuid, playerConfig.getInt(path + ".lowView", -1));  // -1 = use global
        playerLowSim.put(uuid, playerConfig.getInt(path + ".lowSim", -1));
    }

    private void savePlayer(UUID uuid) {
        String path = uuid.toString();
        playerConfig.set(path + ".enabled", playerEnabled.get(uuid));
        int view = playerLowView.getOrDefault(uuid, -1);
        int sim = playerLowSim.getOrDefault(uuid, -1);
        if (view != -1) playerConfig.set(path + ".lowView", view);
        if (sim != -1) playerConfig.set(path + ".lowSim", sim);
        try {
            playerConfig.save(playerFile);
        } catch (IOException e) {
            getLogger().warning("Failed to save players.yml");
        }
    }

    private void checkAndSet(Player player) {
        UUID uuid = player.getUniqueId();
        if (!playerEnabled.getOrDefault(uuid, true)) {
            // Feature off: Restore default
            if (playerViewDists.getOrDefault(uuid, -1) != defaultViewDist) {
                player.setViewDistance(defaultViewDist);
                player.setSimulationDistance(defaultSimDist);
                playerViewDists.put(uuid, defaultViewDist);
                playerSimDists.put(uuid, defaultSimDist);
            }
            return;
        }

        Location loc = player.getLocation();
        Region activeManual = null;
        for (Region r : manualRegions.values()) {
            if (r.contains(loc)) {
                activeManual = r;
                break;
            }
        }
        boolean nearFlagged = isNearFlagged(loc);
        int targetVD, targetSD;
        if (activeManual != null) {
            targetVD = activeManual.viewDist();
            targetSD = activeManual.simDist();
        } else if (nearFlagged) {
            targetVD = playerLowView.getOrDefault(uuid, lowViewDist);
            targetSD = playerLowSim.getOrDefault(uuid, lowSimDist);
        } else {
            targetVD = defaultViewDist;
            targetSD = defaultSimDist;
        }

        Integer currentVD = playerViewDists.get(uuid);
        if (currentVD == null || currentVD != targetVD) {
            player.setViewDistance(targetVD);
            player.setSimulationDistance(targetSD);
            playerViewDists.put(uuid, targetVD);
            playerSimDists.put(uuid, targetSD);
            String msg = (activeManual != null || nearFlagged) ? "§c[LagReducer] View distance reduced (laggy area)" : "§a[LagReducer] View distance restored";
            player.sendMessage(msg);
        }
    }

    private boolean isNearFlagged(Location loc) {
        String wname = loc.getWorld().getName();
        Set<ChunkPos> flags = flaggedChunks.getOrDefault(wname, Collections.emptySet());
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        for (int dx = -neighborRadius; dx <= neighborRadius; dx++) {
            for (int dz = -neighborRadius; dz <= neighborRadius; dz++) {
                if (flags.contains(new ChunkPos(cx + dx, cz + dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!autoDetectEnabled || !event.isNewChunk()) return;
        org.bukkit.Chunk chunk = event.getChunk();
        String wname = chunk.getWorld().getName();
        Set<ChunkPos> worldFlags = flaggedChunks.computeIfAbsent(wname, k -> new HashSet<>());
        if (worldFlags.size() >= maxFlagsPerWorld) return;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            ChunkSnapshot snap = chunk.getChunkSnapshot();
            int badCount = 0;
            int totalBlocks = 0;
            for (int x = 0; x < 16; x++) {
                for (int y = scanMinY; y <= scanMaxY; y++) {
                    for (int z = 0; z < 16; z++) {
                        Material type = snap.getBlockType(x, y, z);
                        if (badBlocks.contains(type)) badCount++;
                        totalBlocks++;
                    }
                }
            }
            double density = totalBlocks > 0 ? (double) badCount / totalBlocks : 0;
            if (density > densityThreshold && badCount > minCobbleCount) {
                // Capture variables as final for inner lambda
                final org.bukkit.Chunk finalChunk = chunk;
                final String finalWname = wname;
                final int finalBadCount = badCount;
                final double finalDensity = density;
                final Set<ChunkPos> finalWorldFlags = worldFlags;

                Bukkit.getScheduler().runTask(this, () -> {
                    ChunkPos pos = new ChunkPos(finalChunk.getX(), finalChunk.getZ());
                    if (finalWorldFlags.add(pos)) {
                        getLogger().info("Auto-flagged lag chunk: " + pos.x() + "," + pos.z() + " in " +
                                finalWname + " (bad blocks: " + finalBadCount + ", density: " + String.format("%.2f", finalDensity) + ")");
                        saveFlaggedChunks();
                    }
                });
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        loadPlayer(uuid);
        checkAndSet(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        savePlayer(uuid);
        playerEnabled.remove(uuid);
        playerLowView.remove(uuid);
        playerLowSim.remove(uuid);
        playerViewDists.remove(uuid);
        playerSimDists.remove(uuid);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) { checkAndSet(e.getPlayer()); }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (e.getFrom().getChunk() != e.getTo().getChunk()) checkAndSet(e.getPlayer());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!label.equalsIgnoreCase("lagregion")) return false;
        if (args.length == 0) {
            sender.sendMessage("§eUsage: /lagregion <reload|clear-manual|clear-flags [world]|list|toggle|set [view|sim] <2-32>|status>");
            return true;
        }
        if (!(sender instanceof Player) && !args[0].matches("reload|clear-manual|clear-flags|list")) {
            sender.sendMessage("§cPlayer-only command.");
            return true;
        }

        if ("reload".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission("lagregion.admin")) { sender.sendMessage("§cNo perm."); return true; }
            reloadConfig();
            loadConfigValues();
            loadManualRegions();
            loadFlaggedChunks();
            sender.sendMessage("§a[LagReducer] Reloaded!");
            return true;
        }
        if ("clear-manual".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission("lagregion.admin")) { sender.sendMessage("§cNo perm."); return true; }
            manualRegions.clear();
            getConfig().set("regions", null);
            saveConfig();
            sender.sendMessage("§aCleared manual regions!");
            return true;
        }
        if ("clear-flags".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission("lagregion.admin")) { sender.sendMessage("§cNo perm."); return true; }
            String world = args.length > 1 ? args[1] : "world";
            flaggedChunks.remove(world);
            getConfig().set("flagged-chunks." + world, null);
            saveConfig();
            sender.sendMessage("§aCleared auto-flags for " + world + "!");
            return true;
        }
        if ("list".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission("lagregion.admin")) { sender.sendMessage("§cNo perm."); return true; }
            sender.sendMessage("§eManual regions: " + manualRegions.size());
            sender.sendMessage("§eAuto-flagged chunks: " + flaggedChunks.values().stream().mapToInt(Set::size).sum());
            return true;
        }

        // Player commands
        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();
        if ("toggle".equalsIgnoreCase(args[0])) {
            boolean newEnabled = !playerEnabled.getOrDefault(uuid, true);
            playerEnabled.put(uuid, newEnabled);
            savePlayer(uuid);
            checkAndSet(player);  // Immediate update
            sender.sendMessage("§a[LagReducer] Feature " + (newEnabled ? "enabled" : "disabled") + "!");
            return true;
        }
        if ("set".equalsIgnoreCase(args[0]) && args.length == 3) {
            int value;
            try {
                value = Integer.parseInt(args[2]);
                if (value < 2 || value > 32) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid number (2-32).");
                return true;
            }
            if ("view".equalsIgnoreCase(args[1])) {
                playerLowView.put(uuid, value);
                sender.sendMessage("§a[LagReducer] Low view distance set to " + value + "!");
            } else if ("sim".equalsIgnoreCase(args[1])) {
                playerLowSim.put(uuid, value);
                sender.sendMessage("§a[LagReducer] Low sim distance set to " + value + "!");
            } else {
                sender.sendMessage("§cUsage: /lagregion set [view|sim] <2-32>");
                return true;
            }
            savePlayer(uuid);
            checkAndSet(player);
            return true;
        }
        if ("status".equalsIgnoreCase(args[0])) {
            boolean enabled = playerEnabled.getOrDefault(uuid, true);
            int pView = playerLowView.getOrDefault(uuid, lowViewDist);
            int pSim = playerLowSim.getOrDefault(uuid, lowSimDist);
            sender.sendMessage("§e[LagReducer] Status:");
            sender.sendMessage(" - Enabled: " + (enabled ? "§aYes" : "§cNo"));
            sender.sendMessage(" - Low View: " + (playerLowView.containsKey(uuid) ? pView : "§7Global (" + lowViewDist + ")"));
            sender.sendMessage(" - Low Sim: " + (playerLowSim.containsKey(uuid) ? pSim : "§7Global (" + lowSimDist + ")"));
            return true;
        }
        return false;
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        loadConfigValues();
    }

    @Override
    public void onDisable() {
        saveFlaggedChunks();
        for (UUID uuid : playerEnabled.keySet()) {
            savePlayer(uuid);
        }
    }
}
