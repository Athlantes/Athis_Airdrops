package org.athlantes.athiSAirdrops;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AirdropManager {

    private final AthiSAirdrops plugin;
    private final Set<Location> activeAirdrops = ConcurrentHashMap.newKeySet();
    private final Set<Location> protectedChests = ConcurrentHashMap.newKeySet();
    // Pending airdrops that are in the air (animation running) but not yet placed
    private final Set<Location> pendingAirdrops = ConcurrentHashMap.newKeySet();
    private final AirdropLockManager lockManager;
    private final ParticleManager particleManager;
    private final HologramManager hologramManager;
    private final AirdropAnimation animation;
    private final Map<BlockLocationKey, BukkitTask> despawnTasks = new ConcurrentHashMap<>();
    // Map to track requested loot table name for pending (falling) airdrops
    private final Map<BlockLocationKey, String> pendingRequestedLoot = new ConcurrentHashMap<>();
    // Map to track the final chosen loot table id for active (landed) airdrops
    private final Map<BlockLocationKey, String> lootTableByLocation = new ConcurrentHashMap<>();
    private BukkitTask spawnTask;
    private boolean running = false;
    private boolean shutdown = false;

    public AirdropManager(AthiSAirdrops plugin) {
        this.plugin = plugin;
        this.lockManager = new AirdropLockManager();
        this.particleManager = new ParticleManager(plugin);
        this.hologramManager = new HologramManager(plugin);
        this.animation = new AirdropAnimation(plugin);
        // Apply particle interval from config
        try { this.particleManager.setInterval(Math.max(1, plugin.getConfig().getInt("particle_interval_ticks", 10))); } catch (Throwable ignored) {}
    }

    // Remove leftover entities from previous crashed runs (armor stands used for drops/holograms)
    public void cleanupLeftovers() {
        try {
            plugin.getLogger().info("AirdropManager: cleaning up leftover armor stands/holograms...");
            for (var world : Bukkit.getWorlds()) {
                // remove armor stands that look like our drop/hologram stands
                world.getEntitiesByClass(org.bukkit.entity.ArmorStand.class).stream()
                        .filter(as -> as != null && !as.isDead())
                        .filter(as -> as.isMarker() && as.isInvisible())
                        .filter(as -> {
                            try {
                                var eq = as.getEquipment();
                                if (eq == null) return false;
                                var helm = eq.getHelmet();
                                if (helm != null && helm.getType() == Material.CHEST) return true;
                                // optionally consider named custom armorstands used as holograms
                                String name = as.getCustomName();
                                return name != null && (name.contains("Unlock") || name.contains("Despawn") || name.contains("Items Left"));
                            } catch (Throwable t) {
                                return false;
                            }
                        })
                        .forEach(as -> {
                            try { as.remove(); } catch (Throwable ignored) {}
                        });
            }
            // Ensure hologram manager state is cleared as well
            try { hologramManager.removeAll(); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            plugin.getLogger().warning("AirdropManager: cleanupLeftovers failed: " + t.getMessage());
        }
    }

    public void start() {
        if (running) return;
        running = true;
        int interval = Math.max(1, plugin.getConfig().getInt("spawn_interval_seconds", 240) * 20); // ticks

        // Immediate spawn if enough players and capacity available
        if (shouldSpawnAirdrop()) {
            spawnAirdrop();
        }

        // Schedule repeating spawn task starting after 'interval' ticks.
        spawnTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (shouldSpawnAirdrop()) spawnAirdrop();
            }
        }.runTaskTimer(plugin, interval, interval);
    }

    public void stop() {
        running = false;
        shutdown = true;

        // Cancel repeating spawn task
        if (spawnTask != null) {
            try { spawnTask.cancel(); } catch (Throwable ignored) {}
            spawnTask = null;
        }

        // Cancel and clear any scheduled despawn tasks
        try {
            for (BukkitTask t : new ArrayList<>(despawnTasks.values())) {
                if (t != null) try { t.cancel(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        despawnTasks.clear();

        // Stop any running animation(s)
        try { animation.stopAll(); } catch (Throwable ignored) {}

        // Remove holograms and stop particle task immediately
        try { hologramManager.removeAll(); } catch (Throwable ignored) {}
        try { particleManager.stop(); } catch (Throwable ignored) {}

        // Silently cleanup all tracked airdrops (no broadcast) and clear protections/locks
        for (Location loc : new HashSet<>(activeAirdrops)) {
            try { cleanupAirdropAt(loc, false); } catch (Throwable ignored) {}
        }
        for (Location loc : new HashSet<>(pendingAirdrops)) {
            try { cleanupAirdropAt(loc, false); } catch (Throwable ignored) {}
        }
        activeAirdrops.clear();
        pendingAirdrops.clear();
        protectedChests.clear();
        try { lockManager.clear(); } catch (Throwable ignored) {}
        shutdown = false;
    }

    public boolean isRunning() { return running; }

    public Set<Location> getActiveAirdrops() { return new HashSet<>(activeAirdrops); }

    // Return union of placed and pending so callers (compass, locate) see in-air airdrops too
    public Set<Location> getAllVisibleAirdrops() {
        Set<Location> all = new HashSet<>(activeAirdrops);
        all.addAll(pendingAirdrops);
        return all;
    }

    public boolean isProtectedChest(Chest chest) { return protectedChests.contains(chest.getLocation()); }

    public boolean isChestLocked(Location location) { return lockManager.isLocked(location); }

    public boolean isPending(Location l) { return l != null && pendingAirdrops.contains(new Location(l.getWorld(), l.getBlockX(), l.getBlockY(), l.getBlockZ())); }

    public boolean willBeLocked() { return plugin.getConfig().getBoolean("lock-airdrop", true); }

    private int getMaxConcurrent() {
        return Math.max(1, plugin.getConfig().getInt("max_concurrent_airdrops", 3));
    }

    private boolean hasCapacity() {
        int total = activeAirdrops.size() + pendingAirdrops.size();
        return total < getMaxConcurrent();
    }

    private boolean shouldSpawnAirdrop() {
        int minPlayers = plugin.getConfig().getInt("min_players_to_spawn", 3);
        if (Bukkit.getOnlinePlayers().size() < minPlayers) return false;
        return hasCapacity();
    }

    private void spawnAirdrop() {
        World world = Bukkit.getWorld(plugin.getConfig().getString("world", "world"));
        if (world == null) {
            plugin.getLogger().warning("Airdrop: configured world not found");
            return;
        }
        AirdropLocationPicker picker = new AirdropLocationPicker(plugin);
        Location location = picker.pickRandomValidLocation(world);
        if (location == null) {
            plugin.getLogger().info("Airdrop: no valid spawn location found");
            return;
        }
        // Use spawnAt which enforces max concurrent airdrops
        boolean started = spawnAt(location);
        if (!started) {
            plugin.getLogger().info("Airdrop: capacity full (max=" + getMaxConcurrent() + "), skipping spawn attempt.");
        }
    }

    /**
     * Returns the loot table id associated with this location.
     * If the airdrop is still pending, this returns the requested table (may be null).
     * If the airdrop is active/landed, returns the final chosen id (may be null).
     */
    public String getLootTableFor(Location location) {
        if (location == null || location.getWorld() == null) return null;
        BlockLocationKey key = BlockLocationKey.from(location);
        String active = lootTableByLocation.get(key);
        if (active != null) return active;
        return pendingRequestedLoot.get(key);
    }

    // Backwards-compatible: keep original no-table method
    public void spawnAirdropAt(Location location) {
        spawnAirdropAt(location, null);
    }

    // New: allow specifying a loot table name (may be null for random selection)
    public void spawnAirdropAt(Location location, String lootTableName) {
        if (location == null || location.getWorld() == null) return; // added null world guard
        // Normalize to block coordinates so set membership and protections match chest location
        Location blockTarget = new Location(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        BlockLocationKey key = BlockLocationKey.from(blockTarget);
        // If a requested loot table was provided, track it while pending (don't store null values)
        if (lootTableName != null && !lootTableName.isEmpty()) {
            try { pendingRequestedLoot.put(key, lootTableName); } catch (Throwable t) { plugin.getLogger().warning("[Airdrop] Failed to store pending loot table: " + t.getMessage()); }
            plugin.getLogger().info("[Airdrop] Pending loot table requested '" + lootTableName + "' at " + key);
        }
        // Do not apply the unlock lock while the airdrop is falling; the lock will be applied
        // when the chest is placed (landed) so players can't loot immediately after it lands.
         // Mark this location as pending (in-air) so compass/locate see it before it lands
         pendingAirdrops.add(blockTarget);

         // Send spawn messages to players first (per your request)
         notifyPlayersOfAirdrop(blockTarget);

         // Play drop animation, then place the chest and finalize setup when animation finishes
         animation.start(blockTarget, (target) -> {
             try {
                 Block block = target.getBlock();
                 // Abort if target occupied and plugin is not configured to replace
                 if (!plugin.getConfig().getBoolean("replace_existing_block", true) && block.getType() != Material.AIR) {
                    plugin.getLogger().warning("[Airdrop] Target block occupied, aborting spawn at " + target);
                    try { pendingAirdrops.remove(target); } catch (Throwable ignored) {}
                    // Remove any pending mapping
                    try { pendingRequestedLoot.remove(key); } catch (Throwable ignored) {}
                    return;
                 }

                 // Place chest at the target
                 block.setType(Material.CHEST);

                 // Finalize on next tick to ensure chest block entity exists
                 Bukkit.getScheduler().runTask(plugin, () -> {
                     Block b = target.getBlock();
                     if (!(b.getState() instanceof Chest chestState)) {
                         try { pendingAirdrops.remove(target); } catch (Throwable ignored) {}
                         try { pendingRequestedLoot.remove(key); } catch (Throwable ignored) {}
                         return;
                     }

                     // Move from pending to active
                     try { pendingAirdrops.remove(target); } catch (Throwable ignored) {}
                     Location chestLoc = chestState.getLocation();
                     BlockLocationKey chestKey = BlockLocationKey.from(chestLoc);
                     boolean added = activeAirdrops.add(chestLoc);
                     protectedChests.add(chestLoc);

                     // Apply lock when the chest has landed so the unlock timer starts now (not while falling)
                     if (plugin.getConfig().getBoolean("lock-airdrop", true)) {
                         int lockTime = plugin.getConfig().getInt("time-before-unlock", 30);
                         long unlockAtMillis = System.currentTimeMillis() + (lockTime * 1000L);
                         try {
                             lockManager.addLock(chestLoc, unlockAtMillis);
                             plugin.getLogger().info("[Airdrop] Applied lock until " + unlockAtMillis + " (ms) for landed airdrop at " + chestKey);
                         } catch (Throwable ignored) {}
                     }

                     // Fill loot
                     AirdropLoot loot = new AirdropLoot(plugin);
                     // Pass the requested loot table name (may be null to select randomly).
                     // The filler returns the actual chosen loot table id (or null).
                     String chosenId = loot.fillChestWithLoot(chestState, lootTableName);
                     if (chosenId != null) {
                         lootTableByLocation.put(chestKey, chosenId);
                         plugin.getLogger().info("[Airdrop] Loot table chosen '" + chosenId + "' for airdrop at " + chestKey);
                     } else {
                         // If no chosen id, attempt to copy pending requested (may be null)
                         String pendingReq = pendingRequestedLoot.get(key);
                         if (pendingReq != null && !pendingReq.isEmpty()) lootTableByLocation.put(chestKey, pendingReq);
                     }
                     // Clear pending requested entry for this location
                     pendingRequestedLoot.remove(key);

                     // Start visuals
                     startVisualEffects(chestLoc);

                     // Schedule despawn
                     scheduleDespawn(chestLoc);
                 });
             } catch (Throwable t) {
                 plugin.getLogger().warning("AirdropManager: error finalizing airdrop after animation: " + t.getMessage());
                 try { pendingAirdrops.remove(blockTarget); } catch (Throwable ignored) {}
                 try { pendingRequestedLoot.remove(key); } catch (Throwable ignored) {}
              }
           });
     }

    private void startVisualEffects(Location location) {
        if (location == null) return;
        Block block = location.getBlock();
        particleManager.addChest(block);

        if (block.getState() instanceof Chest chest) {
            int unlockTime = plugin.getConfig().getInt("time-before-unlock", 30);
            int despawnTime = plugin.getConfig().getInt("time-before-despawn", 120);
            // onEmpty: chest emptied by players -> broadcast cleanup (show despawn message)
            Runnable onEmpty = () -> plugin.getServer().getScheduler().runTask(plugin, () -> cleanupAirdropAt(location, true));
            // onDespawn: natural despawn due to timer -> broadcast cleanup
            Runnable onDespawn = () -> plugin.getServer().getScheduler().runTask(plugin, () -> cleanupAirdropAt(location, true));
            hologramManager.spawnHologram(location, chest, unlockTime, despawnTime, onEmpty, onDespawn);
        }
    }

    private void scheduleDespawn(Location location) {
        if (location == null) return;
        int despawnTimeTicks = plugin.getConfig().getInt("time-before-despawn", 120) * 20;

        BlockLocationKey key = BlockLocationKey.from(location);
        // cancel any previous scheduled despawn for this location
        BukkitTask prev = despawnTasks.remove(key);
        if (prev != null) prev.cancel();

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                // Verify the airdrop is still active before calling cleanup
                if (activeAirdrops.contains(location)) {
                    cleanupAirdropAt(location, true);
                }
            }
        }.runTaskLater(plugin, despawnTimeTicks);

        despawnTasks.put(key, task);
    }

    private void notifyPlayersOfAirdrop(Location location) {
        if (location == null) return;
        boolean compassEnabled = plugin.getConfig().getBoolean("enable-compass", true);
        String biome = location.getBlock().getBiome().name().toLowerCase().replace("_", " ");

        for (var player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().equals(location.getWorld())) continue;

            if (compassEnabled) {
                // set compass target so player's compass points to the airdrop
                try { player.setCompassTarget(location); } catch (Throwable ignored) {}
                double distance = Math.floor(player.getLocation().distance(location));
                String distanceStr = String.valueOf((int) distance);
                // Send the configured airdrop spawn message which includes the biome placeholder
                Messages.send(player, "messages.airdrop_spawned",
                        "%biome%", biome,
                        "%distance%", distanceStr);
            } else {
                Messages.send(player, "messages.compass_coords",
                        "%x%", String.valueOf(location.getBlockX()),
                        "%y%", String.valueOf(location.getBlockY()),
                        "%z%", String.valueOf(location.getBlockZ()));
            }
        }
    }

    // Copied direction calculation from AirdropListeners so we can compute relative direction for spawn messages
    private String calculateDirection(org.bukkit.entity.Player player, Location target) {
        double dx = target.getX() - player.getLocation().getX();
        double dz = target.getZ() - player.getLocation().getZ();
        double angle = Math.toDegrees(Math.atan2(-dx, dz));
        if (angle < 0) angle += 360;

        double playerYaw = (player.getLocation().getYaw() + 360) % 360;
        double relAngle = (angle - playerYaw + 360) % 360;

        if (relAngle >= 337.5 || relAngle < 22.5) return "ahead";
        if (relAngle < 67.5) return "ahead-right";
        if (relAngle < 112.5) return "right";
        if (relAngle < 157.5) return "behind-right";
        if (relAngle < 202.5) return "behind";
        if (relAngle < 247.5) return "behind-left";
        if (relAngle < 292.5) return "left";
        return "ahead-left";
    }

    // Default cleanup (broadcasts by default)
    public void cleanupAirdropAt(Location location) {
        cleanupAirdropAt(location, true);
    }

    // Cleanup with control over broadcasting. This method is idempotent: if the airdrop was already
    // cleaned up (e.g. emptied by players), it will do nothing. It also cancels any scheduled despawn
    // task so the scheduled cleanup won't run after an earlier manual/empty cleanup.
    public void cleanupAirdropAt(Location location, boolean broadcast) {
        if (location == null) return;
        if (broadcast) {
            plugin.getLogger().info("[Airdrop] Airdrop at " + location.getBlockX() + ", " + location.getBlockZ() + " has despawned.");
        }
        // Stop any running animation for this location (if it was still pending)
        try { animation.stop(location); } catch (Throwable ignored) {}

        BlockLocationKey key = BlockLocationKey.from(location);
        BukkitTask scheduled = despawnTasks.remove(key);
        if (scheduled != null) scheduled.cancel();
        try {
            Block preBlock = location.getBlock();
            if (preBlock.getType() == Material.CHEST && preBlock.getState() instanceof Chest preChest) {
                if (!preChest.getInventory().getViewers().isEmpty()) {
                    // If shutting down, forcibly close viewers and remove immediately
                    if (shutdown) {
                        new ArrayList<>(preChest.getInventory().getViewers()).forEach(v -> {
                            try { v.closeInventory(); } catch (Throwable ignored) {}
                        });
                        preBlock.setType(Material.AIR);
                    } else {
                        new ArrayList<>(preChest.getInventory().getViewers()).forEach(v -> {
                            try { v.closeInventory(); } catch (Throwable ignored) {}
                        });
                        Bukkit.getScheduler().runTask(plugin, () -> cleanupAirdropAt(location, broadcast));
                        return;
                    }
                } else {
                    preBlock.setType(Material.AIR);
                }
            } else {
                preBlock.setType(Material.AIR);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("AirdropManager: viewer pre-close check failed: " + t.getMessage());
        }
        boolean removedPending = pendingAirdrops.remove(location);
        boolean removedActive = activeAirdrops.remove(location);
        if (!removedPending && !removedActive) {
            plugin.getLogger().info("AirdropManager: cleanup ignored - airdrop was not active/pending for " + location);
            return;
        }
        protectedChests.remove(location);
        lockManager.removeLock(location);
        // Remove tracking for loot tables
        try { pendingRequestedLoot.remove(key); } catch (Throwable ignored) {}
        try { lootTableByLocation.remove(key); } catch (Throwable ignored) {}
        particleManager.removeChest(location.getBlock());
        hologramManager.removeHologram(location);
        // No need to defer block removal by 1 tick during shutdown
        if (broadcast) {
            Messages.broadcast("messages.airdrop_despawned",
                    "%x%", String.valueOf(location.getBlockX()),
                    "%z%", String.valueOf(location.getBlockZ()));
        }
    }

    // New: spawnAt that returns whether the spawn was initiated (respects max concurrent config).
    public boolean spawnAt(Location location) {
        return spawnAt(location, null);
    }

    // Overload that accepts an optional loot table name
    public boolean spawnAt(Location location, String lootTableName) {
        if (location == null) return false;
        if (!hasCapacity()) {
            return false;
        }
        spawnAirdropAt(location, lootTableName);
        return true;
    }

    public void reload() {
        stop();
        activeAirdrops.clear();
        pendingAirdrops.clear();
        protectedChests.clear();
        lockManager.clear();
        particleManager.stop();
        hologramManager.removeAll();
    }
}
