package com.voidoptimize;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

public final class VoidOptimize extends JavaPlugin {
    private final Deque<Long> tickSamples = new ArrayDeque<>();
    private int sampleTask = -1;
    private int sampleWindow;
    private long lastTickNanos;
    private boolean metricsEnabled;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        lastTickNanos = System.nanoTime();
        startSampler();
        getLogger().info("VoidOptimize enabled: safe optimization mode, no trash clearing and no forced GC.");
        getLogger().info("Protected projectiles: ENDER_PEARL, WIND_CHARGE, BREEZE_WIND_CHARGE");
    }

    @Override
    public void onDisable() {
        if (sampleTask != -1) Bukkit.getScheduler().cancelTask(sampleTask);
        tickSamples.clear();
    }

    private void loadSettings() {
        sampleWindow = Math.max(10, Math.min(600, getConfig().getInt("performance.sample-window", 60)));
        metricsEnabled = getConfig().getBoolean("memory.metrics", true);
    }

    private void startSampler() {
        int interval = Math.max(1, Math.min(200, getConfig().getInt("performance.sample-interval-ticks", 20)));
        sampleTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            long now = System.nanoTime();
            long elapsed = Math.max(0L, now - lastTickNanos);
            lastTickNanos = now;
            if (!metricsEnabled) return;
            tickSamples.addLast(elapsed);
            while (tickSamples.size() > sampleWindow) tickSamples.removeFirst();
        }, 1L, interval);
    }

    public boolean isProtectedProjectile(String entityTypeName) {
        if (entityTypeName == null) return false;
        String type = entityTypeName.toUpperCase(Locale.ROOT);
        return getConfig().getStringList("protection.protected-entity-types").contains(type);
    }

    private double averageTickMs() {
        if (tickSamples.isEmpty()) return 0.0;
        long total = 0L;
        for (long sample : tickSamples) total += sample;
        return (total / (double) tickSamples.size()) / 1_000_000.0;
    }

    private String memoryText() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long max = runtime.maxMemory();
        return formatBytes(used) + " / " + formatBytes(max);
    }

    private String formatBytes(long bytes) {
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("voidoptimize")) return false;
        if (!sender.hasPermission("voidoptimize.admin")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadSettings();
            sender.sendMessage(ChatColor.GREEN + "VoidOptimize configuration reloaded.");
            return true;
        }
        sender.sendMessage(ChatColor.GOLD + "VoidOptimize " + ChatColor.GRAY + "status");
        sender.sendMessage(ChatColor.GRAY + "Sampled tick interval: " + ChatColor.WHITE + String.format(Locale.ROOT, "%.2f ms", averageTickMs()));
        sender.sendMessage(ChatColor.GRAY + "JVM memory: " + ChatColor.WHITE + memoryText());
        sender.sendMessage(ChatColor.GRAY + "Trash/entity clearing: " + ChatColor.GREEN + "OFF");
        sender.sendMessage(ChatColor.GRAY + "Forced System.gc(): " + ChatColor.GREEN + "OFF");
        sender.sendMessage(ChatColor.GRAY + "Protected: " + ChatColor.WHITE + "ender pearls, wind charges");
        return true;
    }
}
