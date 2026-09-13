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

public final class VoidOptimize extends JavaPlugin {
    private int optimizerTask = -1;
    private int metricsTask = -1;
    private int intervalTicks;
    private int maxEntitiesPerPass;
    private int radius;
    private int sampleIntervalTicks;
    private double backoffMspt;
    private boolean cullingEnabled;
    private boolean metricsEnabled;
    private boolean adaptiveEnabled;
    private boolean protectProjectiles;
    private final Set<String> protectedTypes = new HashSet<>();

    private long lastTickNanos;
    private long totalPassNanos;
    private long lastPassNanos;
    private long passes;
    private long entitiesInspected;
    private long protectedSeen;
    private long skippedForLoad;
    private double lastMspt;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        lastTickNanos = System.nanoTime();
        startTasks();
        getLogger().info("VoidOptimize enabled: safe adaptive workload culling; no entity deletion or forced GC.");
    }

    @Override
    public void onDisable() {
        if (optimizerTask != -1) Bukkit.getScheduler().cancelTask(optimizerTask);
        if (metricsTask != -1) Bukkit.getScheduler().cancelTask(metricsTask);
        optimizerTask = -1;
        metricsTask = -1;
        protectedTypes.clear();
    }

    private void loadSettings() {
        intervalTicks = clamp(getConfig().getInt("culling.interval-ticks", 20), 5, 200);
        maxEntitiesPerPass = clamp(getConfig().getInt("culling.max-entities-per-pass", 200), 25, 5000);
        radius = clamp(getConfig().getInt("culling.player-radius", 32), 8, 128);
        sampleIntervalTicks = clamp(getConfig().getInt("performance.sample-interval-ticks", 20), 5, 200);
        backoffMspt = clampDouble(getConfig().getDouble("performance.backoff-mspt", 45.0), 20.0, 100.0);
        cullingEnabled = getConfig().getBoolean("culling.enabled", true);
        metricsEnabled = getConfig().getBoolean("memory.metrics", true);
        adaptiveEnabled = getConfig().getBoolean("performance.adaptive", true);
        protectProjectiles = getConfig().getBoolean("protection.enabled", true);

        protectedTypes.clear();
        for (String type : getConfig().getStringList("protection.protected-entity-types")) {
            if (type != null && !type.isBlank()) protectedTypes.add(type.toUpperCase(Locale.ROOT));
        }
        if (protectedTypes.isEmpty()) {
            protectedTypes.add("ENDER_PEARL");
            protectedTypes.add("WIND_CHARGE");
            protectedTypes.add("BREEZE_WIND_CHARGE");
        }
    }

    private void startTasks() {
        if (optimizerTask != -1) Bukkit.getScheduler().cancelTask(optimizerTask);
        if (metricsTask != -1) Bukkit.getScheduler().cancelTask(metricsTask);
        optimizerTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, this::runSafeCullingPass, intervalTicks, intervalTicks);
        if (metricsEnabled) {
            metricsTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, this::samplePerformance, sampleIntervalTicks, sampleIntervalTicks);
        }
    }

    private void runSafeCullingPass() {
        if (!cullingEnabled || Bukkit.getOnlinePlayers().isEmpty()) return;
        if (adaptiveEnabled && lastMspt >= backoffMspt) {
            skippedForLoad++;
            return;
        }

        long start = System.nanoTime();
        int budget = maxEntitiesPerPass;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (budget <= 0) break;
            if (!player.isOnline() || player.isDead()) continue;
            List<Entity> nearby = player.getNearbyEntities(radius, radius, radius);
            for (Entity entity : nearby) {
                if (budget-- <= 0) break;
                if (!entity.isValid()) continue;
                entitiesInspected++;
                if (protectProjectiles && isProtected(entity)) protectedSeen++;
            }
        }
        lastPassNanos = System.nanoTime() - start;
        totalPassNanos += lastPassNanos;
        passes++;
    }

    private boolean isProtected(Entity entity) {
        return protectedTypes.contains(entity.getType().name().toUpperCase(Locale.ROOT));
    }

    private void samplePerformance() {
        long now = System.nanoTime();
        long elapsed = Math.max(1L, now - lastTickNanos);
        lastTickNanos = now;
        try {
            double[] tps = Bukkit.getTPS();
            if (tps.length > 0 && tps[0] > 0.0) {
                lastMspt = 1000.0 / Math.min(20.0, tps[0]);
            } else {
                lastMspt = elapsed / 1_000_000.0 / Math.max(1, sampleIntervalTicks);
            }
        } catch (Throwable ignored) {
            lastMspt = elapsed / 1_000_000.0 / Math.max(1, sampleIntervalTicks);
        }
    }

    public boolean isProtectedProjectile(String entityTypeName) {
        if (!protectProjectiles || entityTypeName == null) return false;
        return protectedTypes.contains(entityTypeName.toUpperCase(Locale.ROOT));
    }

    private String memoryText() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long max = runtime.maxMemory();
        return formatBytes(used) + " / " + formatBytes(max);
    }

    private String heapCommittedText() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return formatBytes(heap.getCommitted()) + " committed, " + formatBytes(heap.getUsed()) + " used";
    }

    private String formatBytes(long bytes) {
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB"};
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private double averagePassMs() {
        return passes == 0 ? 0.0 : totalPassNanos / 1_000_000.0 / passes;
    }

    private int loadedChunks() {
        int count = 0;
        for (World world : Bukkit.getWorlds()) count += world.getLoadedChunks().length;
        return count;
    }

    private int entityCount() {
        int count = 0;
        for (World world : Bukkit.getWorlds()) count += world.getEntities().size();
        return count;
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "VoidOptimize " + ChatColor.GRAY + "status");
        sender.sendMessage(ChatColor.GRAY + "Players: " + ChatColor.WHITE + Bukkit.getOnlinePlayers().size());
        sender.sendMessage(ChatColor.GRAY + "Entities: " + ChatColor.WHITE + entityCount() + ChatColor.GRAY + " | Loaded chunks: " + ChatColor.WHITE + loadedChunks());
        sender.sendMessage(ChatColor.GRAY + "Memory: " + ChatColor.WHITE + memoryText() + ChatColor.GRAY + " | " + heapCommittedText());
        sender.sendMessage(ChatColor.GRAY + "MSPT signal: " + ChatColor.WHITE + String.format(Locale.ROOT, "%.2f ms", lastMspt));
        sender.sendMessage(ChatColor.GRAY + "Culling: " + (cullingEnabled ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF") + ChatColor.GRAY + " | Adaptive: " + (adaptiveEnabled ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
        sender.sendMessage(ChatColor.GRAY + "Passes: " + ChatColor.WHITE + passes + ChatColor.GRAY + " | Avg pass: " + ChatColor.WHITE + String.format(Locale.ROOT, "%.3f ms", averagePassMs()));
        sender.sendMessage(ChatColor.GRAY + "Inspected: " + ChatColor.WHITE + entitiesInspected + ChatColor.GRAY + " | Protected seen: " + ChatColor.WHITE + protectedSeen + ChatColor.GRAY + " | Backoffs: " + ChatColor.WHITE + skippedForLoad);
    }

    private void sendProfile(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "VoidOptimize " + ChatColor.GRAY + "profile");
        sender.sendMessage(ChatColor.GRAY + "Last pass: " + ChatColor.WHITE + String.format(Locale.ROOT, "%.3f ms", lastPassNanos / 1_000_000.0));
        sender.sendMessage(ChatColor.GRAY + "Average pass: " + ChatColor.WHITE + String.format(Locale.ROOT, "%.3f ms", averagePassMs()));
        sender.sendMessage(ChatColor.GRAY + "Passes: " + ChatColor.WHITE + passes + ChatColor.GRAY + " | inspected: " + ChatColor.WHITE + entitiesInspected);
        sender.sendMessage(ChatColor.GRAY + "Protected entities observed: " + ChatColor.WHITE + protectedSeen + ChatColor.GRAY + " | Load backoffs: " + ChatColor.WHITE + skippedForLoad);
        sender.sendMessage(ChatColor.GRAY + "Budget: " + ChatColor.WHITE + maxEntitiesPerPass + " entities/pass, " + intervalTicks + " ticks");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("voidoptimize")) return false;
        if (!sender.hasPermission("voidoptimize.admin")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                reloadConfig();
                loadSettings();
                startTasks();
                sender.sendMessage(ChatColor.GREEN + "VoidOptimize configuration reloaded.");
            }
            case "profile" -> sendProfile(sender);
            case "status" -> sendStatus(sender);
            default -> sender.sendMessage(ChatColor.GRAY + "/voidoptimize [status|profile|reload]");
        }
        return true;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
