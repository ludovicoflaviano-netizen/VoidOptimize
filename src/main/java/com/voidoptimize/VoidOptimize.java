package com.voidoptimize;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Listener;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VoidOptimize extends JavaPlugin {
    private int optimizerTask = -1, metricsTask = -1;
    private int intervalTicks, maxEntitiesPerPass, radius, sampleIntervalTicks;
    private int chunkPrefetchIntervalTicks, chunkPrefetchRadius, maxChunkRequestsPerPass, maxChunkRequestsInFlight;
    private int minAdaptiveEntities, maxAdaptiveEntities;
    private double emergencyMspt, resumeMspt, chunkPrefetchMaxMspt, aggressiveMspt, conservativeMspt;
    private boolean cullingEnabled, metricsEnabled, adaptiveEnabled, projectileProtection;
    private boolean chunkPrefetchEnabled, chunkGenerationEnabled, directionalPrefetch;
    private final Set<String> protectedTypes = new HashSet<>();
    private final Set<String> chunksInFlight = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, Long> lastPlayerChunk = new ConcurrentHashMap<>();
    private long lastTickNanos, totalPassNanos, lastPassNanos, passes, entitiesInspected, protectedSeen;
    private long skippedForLoad, chunkRequests, chunkCompletions, chunkFailures, lastChunkPrefetchNanos, playerSamples;
    private long adaptiveReductions, adaptiveExpansions;
    private double lastMspt;
    private boolean emergencyMode;

    @Override public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(new OptimizationPanel(this), this);
        lastTickNanos = System.nanoTime();
        startTasks();
        getLogger().info("VoidOptimize enabled: adaptive workload control, guarded chunk prefetch, and overload protection.");
    }

    @Override public void onDisable() {
        if (optimizerTask != -1) Bukkit.getScheduler().cancelTask(optimizerTask);
        if (metricsTask != -1) Bukkit.getScheduler().cancelTask(metricsTask);
        optimizerTask = metricsTask = -1;
        chunksInFlight.clear();
        lastPlayerChunk.clear();
    }

    private void loadSettings() {
        intervalTicks = clamp(getConfig().getInt("culling.interval-ticks", 10), 2, 200);
        maxEntitiesPerPass = clamp(getConfig().getInt("culling.max-entities-per-pass", 300), 25, 10000);
        radius = clamp(getConfig().getInt("culling.player-radius", 32), 8, 128);
        minAdaptiveEntities = clamp(getConfig().getInt("culling.min-adaptive-entities", 75), 25, maxEntitiesPerPass);
        maxAdaptiveEntities = clamp(getConfig().getInt("culling.max-adaptive-entities", maxEntitiesPerPass), minAdaptiveEntities, 10000);
        sampleIntervalTicks = clamp(getConfig().getInt("performance.sample-interval-ticks", 20), 5, 200);
        emergencyMspt = clampDouble(getConfig().getDouble("performance.emergency-mspt", 45.0), 20.0, 100.0);
        resumeMspt = clampDouble(getConfig().getDouble("performance.resume-mspt", 35.0), 15.0, emergencyMspt);
        aggressiveMspt = clampDouble(getConfig().getDouble("performance.aggressive-mspt", 25.0), 10.0, 40.0);
        conservativeMspt = clampDouble(getConfig().getDouble("performance.conservative-mspt", 38.0), aggressiveMspt, 60.0);
        cullingEnabled = getConfig().getBoolean("culling.enabled", true);
        metricsEnabled = getConfig().getBoolean("memory.metrics", true);
        adaptiveEnabled = getConfig().getBoolean("performance.adaptive", true);
        projectileProtection = getConfig().getBoolean("protection.enabled", true);
        chunkPrefetchEnabled = getConfig().getBoolean("chunks.prefetch.enabled", true);
        chunkGenerationEnabled = getConfig().getBoolean("chunks.prefetch.generate-new-chunks", false);
        directionalPrefetch = getConfig().getBoolean("chunks.prefetch.directional", true);
        chunkPrefetchIntervalTicks = clamp(getConfig().getInt("chunks.prefetch.interval-ticks", 10), 2, 200);
        chunkPrefetchRadius = clamp(getConfig().getInt("chunks.prefetch.radius", 1), 1, 3);
        maxChunkRequestsPerPass = clamp(getConfig().getInt("chunks.prefetch.max-requests-per-pass", 4), 1, 32);
        maxChunkRequestsInFlight = clamp(getConfig().getInt("chunks.prefetch.max-in-flight", 16), 1, 128);
        chunkPrefetchMaxMspt = clampDouble(getConfig().getDouble("chunks.prefetch.max-mspt", 35.0), 15.0, 50.0);
        protectedTypes.clear();
        for (String type : getConfig().getStringList("protection.protected-entity-types")) if (type != null && !type.isBlank()) protectedTypes.add(type.toUpperCase(Locale.ROOT));
        if (protectedTypes.isEmpty()) { protectedTypes.add("ENDER_PEARL"); protectedTypes.add("WIND_CHARGE"); protectedTypes.add("BREEZE_WIND_CHARGE"); }
    }

    private void startTasks() {
        if (optimizerTask != -1) Bukkit.getScheduler().cancelTask(optimizerTask);
        if (metricsTask != -1) Bukkit.getScheduler().cancelTask(metricsTask);
        optimizerTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, this::runOptimizerPass, 20L, intervalTicks);
        if (metricsEnabled) metricsTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, this::samplePerformance, sampleIntervalTicks, sampleIntervalTicks);
    }

    private void runOptimizerPass() {
        if (!cullingEnabled && !chunkPrefetchEnabled) return;
        updateEmergencyState();
        if (emergencyMode) { skippedForLoad++; return; }
        long start = System.nanoTime();
        if (cullingEnabled) samplePlayersWithoutEntityScan();
        if (chunkPrefetchEnabled && shouldPrefetchChunks()) runChunkPrefetch();
        lastPassNanos = System.nanoTime() - start;
        totalPassNanos += lastPassNanos;
        passes++;
    }

    private int adaptiveBudget() {
        if (!adaptiveEnabled) return maxEntitiesPerPass;
        if (lastMspt >= conservativeMspt) return minAdaptiveEntities;
        if (lastMspt >= aggressiveMspt) return Math.max(minAdaptiveEntities, maxEntitiesPerPass / 2);
        if (lastMspt <= aggressiveMspt * 0.70) return Math.min(maxAdaptiveEntities, (int)(maxEntitiesPerPass * 1.15));
        return maxEntitiesPerPass;
    }

    private void samplePlayersWithoutEntityScan() {
        // This is intentionally O(players), not O(players * nearby entities).
        // The previous entity scan inspected hundreds of entities without changing
        // server state, so it could add load instead of removing it.
        int players = Bukkit.getOnlinePlayers().size();
        playerSamples += players;
        int budget = adaptiveBudget();
        if (budget < maxEntitiesPerPass) adaptiveReductions++;
        else if (budget > maxEntitiesPerPass) adaptiveExpansions++;
    }

    private boolean shouldPrefetchChunks() {
        if (emergencyMode || Bukkit.getOnlinePlayers().isEmpty()) return false;
        long now = System.nanoTime();
        if (now - lastChunkPrefetchNanos < chunkPrefetchIntervalTicks * 50_000_000L) return false;
        int players = Bukkit.getOnlinePlayers().size();
        int safeInFlight = Math.min(maxChunkRequestsInFlight, Math.max(2, 8 + players / 16));
        return lastMspt <= chunkPrefetchMaxMspt && chunksInFlight.size() < safeInFlight;
    }

    private void runChunkPrefetch() {
        lastChunkPrefetchNanos = System.nanoTime();
        int requested = 0;
        int players = Bukkit.getOnlinePlayers().size();
        int requestBudget = Math.min(maxChunkRequestsPerPass, Math.max(1, 2 + players / 50));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (requested >= requestBudget || chunksInFlight.size() >= maxChunkRequestsInFlight) break;
            if (!player.isOnline() || player.isDead()) continue;
            World world = player.getWorld();
            int cx = player.getLocation().getBlockX() >> 4;
            int cz = player.getLocation().getBlockZ() >> 4;
            long previous = lastPlayerChunk.getOrDefault(player.getUniqueId(), Long.MIN_VALUE);
            long current = packChunk(cx, cz);
            if (previous == current && directionalPrefetch) continue;
            lastPlayerChunk.put(player.getUniqueId(), current);
            int dx = Integer.compare(player.getLocation().getBlockX() - (cx << 4) - 8, 0);
            int dz = Integer.compare(player.getLocation().getBlockZ() - (cz << 4) - 8, 0);
            List<int[]> candidates = new ArrayList<>();
            if (directionalPrefetch) {
                if (dx != 0) candidates.add(new int[]{cx + dx, cz});
                if (dz != 0) candidates.add(new int[]{cx, cz + dz});
            }
            candidates.add(new int[]{cx + 1, cz}); candidates.add(new int[]{cx - 1, cz});
            candidates.add(new int[]{cx, cz + 1}); candidates.add(new int[]{cx, cz - 1});
            for (int d = 1; d <= chunkPrefetchRadius && requested < maxChunkRequestsPerPass; d++) {
                candidates.add(new int[]{cx + d, cz}); candidates.add(new int[]{cx - d, cz});
                candidates.add(new int[]{cx, cz + d}); candidates.add(new int[]{cx, cz - d});
            }
            for (int[] c : candidates) {
                if (requested >= requestBudget || chunksInFlight.size() >= maxChunkRequestsInFlight) break;
                String key = world.getUID() + ":" + c[0] + ":" + c[1];
                if (!chunksInFlight.add(key)) continue;
                try {
                    world.getChunkAtAsync(c[0], c[1], chunkGenerationEnabled, false).whenComplete((chunk, error) -> {
                        chunksInFlight.remove(key);
                        if (error == null) chunkCompletions++; else chunkFailures++;
                    });
                    chunkRequests++; requested++;
                } catch (Throwable ignored) {
                    chunksInFlight.remove(key); chunkFailures++;
                }
            }
        }
    }

    private void updateEmergencyState() {
        if (!adaptiveEnabled) return;
        if (!emergencyMode && lastMspt >= emergencyMspt) emergencyMode = true;
        else if (emergencyMode && lastMspt <= resumeMspt) emergencyMode = false;
    }

    private void samplePerformance() {
        long now = System.nanoTime();
        long elapsed = Math.max(1L, now - lastTickNanos);
        lastTickNanos = now;
        double[] tps;
        try { tps = Bukkit.getTPS(); } catch (Throwable ignored) { tps = new double[0]; }
        lastMspt = tps.length > 0 && tps[0] > 0.0 ? 1000.0 / Math.min(20.0, tps[0]) : elapsed / 1_000_000.0 / Math.max(1, sampleIntervalTicks);
        updateEmergencyState();
    }

    public boolean isProtectedProjectile(String type) { return projectileProtection && type != null && protectedTypes.contains(type.toUpperCase(Locale.ROOT)); }
    private boolean isProtected(Entity entity) { return isProtectedProjectile(entity.getType().name()); }
    private long packChunk(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }
    private String memoryText() { Runtime r = Runtime.getRuntime(); return formatBytes(r.totalMemory() - r.freeMemory()) + " / " + formatBytes(r.maxMemory()); }
    private String heapText() { MemoryUsage h = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage(); return formatBytes(h.getUsed()) + " / " + formatBytes(h.getCommitted()); }
    private String formatBytes(long bytes) { double v = bytes; String[] u={"B","KB","MB","GB"}; int i=0; while(v>=1024 && i<u.length-1){v/=1024;i++;} return String.format(Locale.ROOT,"%.1f %s",v,u[i]); }
    private double averagePassMs() { return passes == 0 ? 0.0 : totalPassNanos / 1_000_000.0 / passes; }
    private int loadedChunks() { int n=0; for(World w:Bukkit.getWorlds()) n += w.getLoadedChunks().length; return n; }
    private int entityCount() { int n=0; for(World w:Bukkit.getWorlds()) n += w.getEntities().size(); return n; }

    private void sendStatus(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "VoidOptimize " + ChatColor.GRAY + "status");
        s.sendMessage(ChatColor.GRAY + "Players: " + ChatColor.WHITE + Bukkit.getOnlinePlayers().size() + ChatColor.GRAY + " | Entities: " + ChatColor.WHITE + entityCount());
        s.sendMessage(ChatColor.GRAY + "Loaded chunks: " + ChatColor.WHITE + loadedChunks() + ChatColor.GRAY + " | Memory: " + ChatColor.WHITE + memoryText());
        s.sendMessage(ChatColor.GRAY + "Heap: " + ChatColor.WHITE + heapText() + ChatColor.GRAY + " | MSPT: " + ChatColor.WHITE + String.format(Locale.ROOT,"%.2f ms",lastMspt));
        s.sendMessage(ChatColor.GRAY + "Emergency: " + (emergencyMode ? ChatColor.RED + "ACTIVE" : ChatColor.GREEN + "READY") + ChatColor.GRAY + " | Budget: " + ChatColor.WHITE + adaptiveBudget());
        s.sendMessage(ChatColor.GRAY + "Prefetch queue: " + ChatColor.WHITE + chunksInFlight.size() + ChatColor.GRAY + " / " + maxChunkRequestsInFlight + " | Requests: " + chunkRequests + " | Failed: " + chunkFailures);
    }

    private void sendProfile(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "VoidOptimize " + ChatColor.GRAY + "profile");
        s.sendMessage(ChatColor.GRAY + "Last pass: " + ChatColor.WHITE + String.format(Locale.ROOT,"%.3f ms",lastPassNanos/1_000_000.0) + ChatColor.GRAY + " | Average: " + ChatColor.WHITE + String.format(Locale.ROOT,"%.3f ms",averagePassMs()));
        s.sendMessage(ChatColor.GRAY + "Passes: " + ChatColor.WHITE + passes + ChatColor.GRAY + " | Inspected: " + entitiesInspected + ChatColor.GRAY + " | Protected: " + protectedSeen);
        s.sendMessage(ChatColor.GRAY + "Load skips: " + ChatColor.WHITE + skippedForLoad + ChatColor.GRAY + " | Adaptive reductions: " + adaptiveReductions + ChatColor.GRAY + " | Expansions: " + adaptiveExpansions);
        s.sendMessage(ChatColor.GRAY + "Player samples: " + ChatColor.WHITE + playerSamples + ChatColor.GRAY + " | Plugin pass overhead: " + ChatColor.WHITE + String.format(Locale.ROOT,"%.3f ms",averagePassMs()));
        s.sendMessage(ChatColor.GRAY + "Chunks completed: " + ChatColor.WHITE + chunkCompletions + ChatColor.GRAY + " | Failed: " + chunkFailures);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("voidoptimize")) return false;
        if (!sender.hasPermission("voidoptimize.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("panel") || sub.equals("gui")) {
            if (sender instanceof Player p) new OptimizationPanel(this).open(p, 0);
            else sender.sendMessage(ChatColor.RED + "The panel is in-game only.");
            return true;
        }
        switch (sub) {
            case "reload" -> { reloadConfig(); loadSettings(); startTasks(); sender.sendMessage(ChatColor.GREEN + "VoidOptimize configuration reloaded."); }
            case "profile" -> sendProfile(sender);
            case "status" -> sendStatus(sender);
            default -> sender.sendMessage(ChatColor.GRAY + "/voidoptimize [panel|status|profile|reload]");
        }
        return true;
    }

    private static int clamp(int v,int min,int max){return Math.max(min,Math.min(max,v));}
    private static double clampDouble(double v,double min,double max){return Math.max(min,Math.min(max,v));}
    public double getLastMsptForPanel() { return lastMspt; }
    public boolean isEmergencyForPanel() { return emergencyMode; }

}
