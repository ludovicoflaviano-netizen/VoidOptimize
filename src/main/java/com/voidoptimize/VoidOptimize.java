package com.voidoptimize;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
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
    private double emergencyMspt, resumeMspt, chunkPrefetchMaxMspt;
    private boolean cullingEnabled, metricsEnabled, adaptiveEnabled, projectileProtection;
    private boolean chunkPrefetchEnabled, chunkGenerationEnabled;
    private final Set<String> protectedTypes = new HashSet<>();
    private final Set<String> chunksInFlight = ConcurrentHashMap.newKeySet();
    private long lastTickNanos, totalPassNanos, lastPassNanos, passes, entitiesInspected, protectedSeen;
    private long skippedForLoad, chunkRequests, chunkCompletions, chunkFailures, lastChunkPrefetchNanos;
    private double lastMspt;
    private boolean emergencyMode;

    @Override public void onEnable() {
        saveDefaultConfig(); loadSettings(); lastTickNanos = System.nanoTime(); startTasks();
        getLogger().info("VoidOptimize enabled: adaptive culling, guarded chunk prefetch, and overload protection.");
    }
    @Override public void onDisable() {
        if (optimizerTask != -1) Bukkit.getScheduler().cancelTask(optimizerTask);
        if (metricsTask != -1) Bukkit.getScheduler().cancelTask(metricsTask);
        optimizerTask = metricsTask = -1; chunksInFlight.clear();
    }

    private void loadSettings() {
        intervalTicks = clamp(getConfig().getInt("culling.interval-ticks", 10), 2, 200);
        maxEntitiesPerPass = clamp(getConfig().getInt("culling.max-entities-per-pass", 300), 25, 10000);
        radius = clamp(getConfig().getInt("culling.player-radius", 32), 8, 128);
        sampleIntervalTicks = clamp(getConfig().getInt("performance.sample-interval-ticks", 20), 5, 200);
        emergencyMspt = clampDouble(getConfig().getDouble("performance.emergency-mspt", 45.0), 20.0, 100.0);
        resumeMspt = clampDouble(getConfig().getDouble("performance.resume-mspt", 35.0), 15.0, emergencyMspt);
        cullingEnabled = getConfig().getBoolean("culling.enabled", true);
        metricsEnabled = getConfig().getBoolean("memory.metrics", true);
        adaptiveEnabled = getConfig().getBoolean("performance.adaptive", true);
        projectileProtection = getConfig().getBoolean("protection.enabled", true);
        chunkPrefetchEnabled = getConfig().getBoolean("chunks.prefetch.enabled", true);
        chunkGenerationEnabled = getConfig().getBoolean("chunks.prefetch.generate-new-chunks", false);
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
        if (cullingEnabled) runSafeCulling();
        if (chunkPrefetchEnabled && shouldPrefetchChunks()) runChunkPrefetch();
        lastPassNanos = System.nanoTime() - start; totalPassNanos += lastPassNanos; passes++;
    }

    private void runSafeCulling() {
        int budget = maxEntitiesPerPass;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (budget <= 0) break;
            if (!player.isOnline() || player.isDead()) continue;
            List<Entity> nearby;
            try { nearby = player.getNearbyEntities(radius, radius, radius); } catch (Throwable ignored) { continue; }
            for (Entity entity : nearby) {
                if (budget-- <= 0) break;
                if (!entity.isValid()) continue;
                entitiesInspected++;
                if (projectileProtection && isProtected(entity)) protectedSeen++;
            }
        }
    }

    private boolean shouldPrefetchChunks() {
        if (emergencyMode || Bukkit.getOnlinePlayers().isEmpty()) return false;
        if (System.nanoTime() - lastChunkPrefetchNanos < chunkPrefetchIntervalTicks * 50_000_000L) return false;
        return lastMspt <= chunkPrefetchMaxMspt && chunksInFlight.size() < maxChunkRequestsInFlight;
    }

    private void runChunkPrefetch() {
        int requested = 0; lastChunkPrefetchNanos = System.nanoTime();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (requested >= maxChunkRequestsPerPass) break;
            if (!player.isOnline() || player.isDead()) continue;
            World world = player.getWorld();
            int cx = player.getLocation().getBlockX() >> 4, cz = player.getLocation().getBlockZ() >> 4;
            outer: for (int dx = -chunkPrefetchRadius; dx <= chunkPrefetchRadius; dx++) {
                for (int dz = -chunkPrefetchRadius; dz <= chunkPrefetchRadius; dz++) {
                    if (requested >= maxChunkRequestsPerPass || chunksInFlight.size() >= maxChunkRequestsInFlight) break outer;
                    if (dx == 0 && dz == 0) continue;
                    int x = cx + dx, z = cz + dz; String key = world.getUID() + ":" + x + ":" + z;
                    if (!chunksInFlight.add(key)) continue;
                    try {
                        world.getChunkAtAsync(x, z, chunkGenerationEnabled, false).whenComplete((chunk, error) -> {
                            chunksInFlight.remove(key); if (error == null) chunkCompletions++; else chunkFailures++;
                        });
                        chunkRequests++; requested++;
                    } catch (Throwable ignored) { chunksInFlight.remove(key); chunkFailures++; }
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
        long now = System.nanoTime(), elapsed = Math.max(1L, now - lastTickNanos); lastTickNanos = now;
        try {
            double[] tps = Bukkit.getTPS();
            lastMspt = tps.length > 0 && tps[0] > 0.0 ? 1000.0 / Math.min(20.0, tps[0]) : elapsed / 1_000_000.0 / Math.max(1, sampleIntervalTicks);
        } catch (Throwable ignored) { lastMspt = elapsed / 1_000_000.0 / Math.max(1, sampleIntervalTicks); }
        updateEmergencyState();
    }

    public boolean isProtectedProjectile(String type) { return projectileProtection && type != null && protectedTypes.contains(type.toUpperCase(Locale.ROOT)); }
    private boolean isProtected(Entity entity) { return isProtectedProjectile(entity.getType().name()); }

    private String memoryText() { Runtime r = Runtime.getRuntime(); return formatBytes(r.totalMemory() - r.freeMemory()) + " / " + formatBytes(r.maxMemory()); }
    private String heapText() { MemoryUsage h = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage(); return formatBytes(h.getUsed()) + " / " + formatBytes(h.getCommitted()); }
    private String formatBytes(long b) { double v = b; String[] u = {"B","KB","MB","GB"}; int i = 0; while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; } return String.format(Locale.ROOT, "%.1f %s", v, u[i]); }
    private double averagePassMs() { return passes == 0 ? 0.0 : totalPassNanos / 1_000_000.0 / passes; }
    private int loadedChunks() { int n = 0; for (World w : Bukkit.getWorlds()) n += w.getLoadedChunks().length; return n; }
    private int entityCount() { int n = 0; for (World w : Bukkit.getWorlds()) n += w.getEntities().size(); return n; }

    private void sendStatus(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "VoidOptimize " + ChatColor.GRAY + "status");
        s.sendMessage(ChatColor.GRAY + "Players: " + ChatColor.WHITE + Bukkit.getOnlinePlayers().size() + ChatColor.GRAY + " | Entities: " + ChatColor.WHITE + entityCount());
        s.sendMessage(ChatColor.GRAY + "Loaded chunks: " + ChatColor.WHITE + loadedChunks() + ChatColor.GRAY + " | Memory: " + ChatColor.WHITE + memoryText());
        s.sendMessage(ChatColor.GRAY + "Heap: " + ChatColor.WHITE + heapText() + ChatColor.GRAY + " | MSPT estimate: " + ChatColor.WHITE + String.format(Locale.ROOT, "%.2f ms", lastMspt));
        s.sendMessage(ChatColor.GRAY + "Emergency: " + (emergencyMode ? ChatColor.RED + "ON" : ChatColor.GREEN + "OFF") + ChatColor.GRAY + " | Culling: " + (cullingEnabled ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF") + ChatColor.GRAY + " | Prefetch: " + (chunkPrefetchEnabled ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
        s.sendMessage(ChatColor.GRAY + "Passes: " + ChatColor.WHITE + passes + ChatColor.GRAY + " | Avg pass: " + ChatColor.WHITE + String.format(Locale.ROOT, "%.3f ms", averagePassMs()) + ChatColor.GRAY + " | Load skips: " + ChatColor.WHITE + skippedForLoad);
        s.sendMessage(ChatColor.GRAY + "Chunk requests: " + ChatColor.WHITE + chunkRequests + ChatColor.GRAY + " | completed: " + ChatColor.WHITE + chunkCompletions + ChatColor.GRAY + " | failed: " + ChatColor.WHITE + chunkFailures);
    }

    private void sendProfile(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "VoidOptimize " + ChatColor.GRAY + "profile");
        s.sendMessage(ChatColor.GRAY + "Last pass: " + ChatColor.WHITE + String.format(Locale.ROOT, "%.3f ms", lastPassNanos / 1_000_000.0) + ChatColor.GRAY + " | Average: " + ChatColor.WHITE + String.format(Locale.ROOT, "%.3f ms", averagePassMs()));
        s.sendMessage(ChatColor.GRAY + "Entities inspected: " + ChatColor.WHITE + entitiesInspected + ChatColor.GRAY + " | Protected observed: " + ChatColor.WHITE + protectedSeen);
        s.sendMessage(ChatColor.GRAY + "Chunk queue: " + ChatColor.WHITE + chunksInFlight.size() + ChatColor.GRAY + " / " + maxChunkRequestsInFlight + " | Requests: " + ChatColor.WHITE + chunkRequests);
        s.sendMessage(ChatColor.GRAY + "Adaptive emergency mode: " + (emergencyMode ? ChatColor.RED + "ACTIVE" : ChatColor.GREEN + "READY"));
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("voidoptimize")) return false;
        if (!sender.hasPermission("voidoptimize.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> { reloadConfig(); loadSettings(); startTasks(); sender.sendMessage(ChatColor.GREEN + "VoidOptimize configuration reloaded."); }
            case "profile" -> sendProfile(sender);
            case "status" -> sendStatus(sender);
            default -> sender.sendMessage(ChatColor.GRAY + "/voidoptimize [status|profile|reload]");
        }
        return true;
    }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static double clampDouble(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }
}
