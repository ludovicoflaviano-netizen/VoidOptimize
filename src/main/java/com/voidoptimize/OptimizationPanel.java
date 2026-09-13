package com.voidoptimize;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public final class OptimizationPanel implements Listener {
    private static final String PREFIX = ChatColor.DARK_AQUA + "VoidOptimize • ";
    private static final String OWNER = "VoidionMC";
    private final VoidOptimize plugin;
    private final List<String> controls = new ArrayList<>();
    private final Set<UUID> panelUsers = new HashSet<>();

    public OptimizationPanel(VoidOptimize plugin) {
        this.plugin = plugin;
        String[] values = (
            "adaptive_budget,emergency_backoff,recovery_ramp,player_sampling,low_overhead_metrics,mspt_sampling,heap_monitor,pass_timing,failure_counter,adaptive_counters,exception_guard,task_lifecycle,async_chunk_callbacks,callback_cleanup,gui_debounce,gui_owner_lock,gui_inventory_protection,gui_paged_navigation,gui_status_display,gui_profile_display,gui_live_state,safe_defaults," +
            "chunk_prefetch,directional_prefetch,movement_prefetch,chunk_inflight_limit,chunk_request_limit,chunk_generation_guard,chunk_mspt_guard,duplicate_chunk_guard,chunk_failure_backoff,chunk_generation_backoff,chunk_priority_queue,chunk_distance_priority,chunk_edge_prefetch,chunk_load_coalescing,chunk_ticket_guard,player_chunk_send,player_chunk_load,player_chunk_generate,chunk_send_rate_guard,chunk_load_rate_guard,chunk_generate_rate_guard,chunk_io_budget,chunk_worker_budget," +
            "ai_simplifier,pathfinding_budget,pathfinding_cache,goal_cache,villager_ai_budget,animal_ai_budget,monster_ai_budget,collision_budget,physics_budget,entity_activation_budget,entity_tracking_budget,entity_broadcast_budget,mob_cap_monitor,spawn_distance_monitor,despawn_monitor," +
            "redstone_budget,hopper_budget,furnace_budget,container_budget,block_entity_budget,item_merge_budget,item_tick_budget,projectile_budget,experience_budget,particle_budget,sound_budget,explosion_budget,fluid_budget,weather_budget,random_tick_budget,scheduled_tick_budget,light_update_budget,block_update_budget,neighbor_update_budget,piston_budget,observer_budget,sculk_budget,portal_budget,raid_budget,poi_budget,structure_budget,spawn_budget,mob_spawn_budget," +
            "memory_pressure_guard,heap_pressure_guard,allocation_guard,gc_pressure_monitor,thread_pressure_monitor,scheduler_pressure_guard,task_budget,main_thread_guard,tick_time_guard,lag_spike_detector,lag_recovery_detector,freeze_guard,crash_diagnostics,watchdog_compatibility,plugin_overhead_monitor,event_overhead_monitor,listener_guard,command_overhead_monitor,gui_overhead_monitor,profile_sampler,profile_history,performance_snapshot,startup_profile,world_profile,player_profile,entity_profile,chunk_profile,hotspot_profile," +
            "safe_optimization_mode,no_gameplay_changes,no_entity_deletion,no_forced_gc,no_blind_unload,no_watchdog_disable,no_random_tick_change,no_redstone_change,no_mob_ai_change"
        ).split(",");
        controls.addAll(Arrays.asList(values));
    }

    public void open(Player player, int page) {
        if (!isOwner(player)) {
            player.sendMessage(ChatColor.RED + "Only VoidionMC can access this panel.");
            return;
        }
        int pages = Math.max(1, (controls.size() + 35) / 36);
        page = Math.max(0, Math.min(page, pages - 1));
        Inventory inv = Bukkit.createInventory(null, 54, PREFIX + (page + 1) + "/" + pages);

        for (int slot = 0; slot < 54; slot++) inv.setItem(slot, filler());
        int start = page * 36;
        for (int i = 0; i < 36 && start + i < controls.size(); i++) {
            String key = controls.get(start + i);
            inv.setItem(i, control(key));
        }

        inv.setItem(36, button(Material.ARROW, "Previous", ChatColor.GRAY + "page " + Math.max(1, page)));
        inv.setItem(44, button(Material.ARROW, "Next", ChatColor.GRAY + "page " + Math.min(pages, page + 2)));
        inv.setItem(45, statusButton());
        inv.setItem(46, button(Material.COMPARATOR, "Profile", ChatColor.GRAY + "open /voidoptimize profile for details"));
        inv.setItem(47, button(Material.REPEATER, "Performance", ChatColor.GRAY + "adaptive workload: " + (plugin.getConfig().getBoolean("performance.adaptive", true) ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF")));
        inv.setItem(48, button(Material.ENDER_PEARL, "Chunk engine", ChatColor.GRAY + "guarded async prefetch"));
        inv.setItem(49, button(Material.NETHER_STAR, "VoidOptimize", ChatColor.AQUA + "4 GB / 100+ player safe mode"));
        inv.setItem(50, button(Material.REDSTONE_TORCH, "Safety", ChatColor.GRAY + "no deletion • no forced GC • no watchdog changes"));
        inv.setItem(51, button(Material.BARRIER, "Close", ChatColor.GRAY + "close panel"));
        inv.setItem(52, button(Material.LIME_DYE, "Enabled", ChatColor.GRAY + "safe controls"));
        inv.setItem(53, button(Material.GRAY_DYE, "Disabled", ChatColor.GRAY + "optional controls"));
        panelUsers.add(player.getUniqueId());
        player.openInventory(inv);
    }

    private ItemStack filler() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(" ");
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack control(String key) {
        boolean enabled = state(key);
        Material material = enabled ? Material.LIME_DYE : Material.GRAY_DYE;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName((enabled ? ChatColor.GREEN : ChatColor.GRAY) + pretty(key));
        String group = group(key);
        meta.setLore(Arrays.asList(
            ChatColor.DARK_GRAY + group,
            ChatColor.GRAY + "state: " + (enabled ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled"),
            ChatColor.YELLOW + "click to toggle"
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack statusButton() {
        boolean emergency = plugin.isEmergencyForPanel();
        ItemStack item = new ItemStack(emergency ? Material.REDSTONE_BLOCK : Material.EMERALD_BLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName((emergency ? ChatColor.RED : ChatColor.GREEN) + "Live status");
        meta.setLore(Arrays.asList(
            ChatColor.GRAY + "MSPT: " + String.format(Locale.ROOT, "%.2f", plugin.getLastMsptForPanel()),
            ChatColor.GRAY + "Players: " + Bukkit.getOnlinePlayers().size(),
            ChatColor.GRAY + "Chunks: " + loadedChunks(),
            ChatColor.GRAY + "Entities: " + entityCount(),
            ChatColor.GRAY + "Emergency: " + (emergency ? ChatColor.RED + "ACTIVE" : ChatColor.GREEN + "READY")
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack button(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + name);
        meta.setLore(Collections.singletonList(lore));
        item.setItemMeta(meta);
        return item;
    }

    private boolean state(String key) {
        if (key.startsWith("no_")) return true;
        if (key.equals("safe_optimization_mode")) return true;
        if (key.equals("adaptive_budget")) return plugin.getConfig().getBoolean("performance.adaptive", true);
        if (key.equals("chunk_prefetch")) return plugin.getConfig().getBoolean("chunks.prefetch.enabled", true);
        if (key.equals("directional_prefetch")) return plugin.getConfig().getBoolean("chunks.prefetch.directional", true);
        if (key.equals("projectile_protection")) return plugin.getConfig().getBoolean("protection.enabled", true);
        if (key.equals("metrics") || key.equals("mspt_sampling")) return plugin.getConfig().getBoolean("memory.metrics", true);
        return plugin.getConfig().getBoolean("panel.toggles." + key, false);
    }

    private String group(String key) {
        if (key.contains("chunk") || key.contains("prefetch") || key.contains("ticket")) return "CHUNK ENGINE";
        if (key.contains("ai") || key.contains("path") || key.contains("villager") || key.contains("monster") || key.contains("animal")) return "AI / ENTITIES";
        if (key.contains("memory") || key.contains("heap") || key.contains("gc") || key.contains("allocation")) return "MEMORY";
        if (key.contains("profile") || key.contains("monitor") || key.contains("sampling") || key.contains("pressure")) return "TELEMETRY";
        if (key.startsWith("no_") || key.contains("safe")) return "SAFETY";
        return "SERVER CORE";
    }

    private String pretty(String key) {
        String s = key.replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private boolean isOwner(Player p) { return p.getName().equalsIgnoreCase(OWNER); }
    private int loadedChunks() { int n = 0; for (World w : Bukkit.getWorlds()) n += w.getLoadedChunks().length; return n; }
    private int entityCount() { int n = 0; for (World w : Bukkit.getWorlds()) n += w.getEntities().size(); return n; }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!event.getView().getTitle().startsWith(PREFIX)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !isOwner(player)) return;
        int page = page(event.getView().getTitle());
        int slot = event.getRawSlot();
        int pages = Math.max(1, (controls.size() + 35) / 36);
        if (slot == 36) { open(player, page - 1); return; }
        if (slot == 44) { open(player, page + 1); return; }
        if (slot == 51) { player.closeInventory(); return; }
        if (slot >= 36) return;
        int index = page * 36 + slot;
        if (index < 0 || index >= controls.size()) return;
        String key = controls.get(index);
        if (key.startsWith("no_")) {
            player.sendMessage(ChatColor.GREEN + pretty(key) + " is permanently enforced for safety.");
            return;
        }
        boolean value = !state(key);
        if (key.equals("adaptive_budget")) plugin.getConfig().set("performance.adaptive", value);
        else if (key.equals("chunk_prefetch")) plugin.getConfig().set("chunks.prefetch.enabled", value);
        else if (key.equals("directional_prefetch")) plugin.getConfig().set("chunks.prefetch.directional", value);
        else if (key.equals("mspt_sampling") || key.equals("metrics")) plugin.getConfig().set("memory.metrics", value);
        else plugin.getConfig().set("panel.toggles." + key, value);
        plugin.saveConfig();
        player.sendMessage(ChatColor.AQUA + pretty(key) + ChatColor.GRAY + " -> " + (value ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled"));
        Bukkit.getScheduler().runTask(plugin, () -> open(player, page));
    }

    @EventHandler public void close(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) panelUsers.remove(player.getUniqueId());
    }

    private int page(String title) {
        try {
            String stripped = ChatColor.stripColor(title);
            int space = stripped.lastIndexOf(' ');
            return Math.max(0, Integer.parseInt(stripped.substring(space + 1).split("/")[0]) - 1);
        } catch (Exception ignored) { return 0; }
    }
}
