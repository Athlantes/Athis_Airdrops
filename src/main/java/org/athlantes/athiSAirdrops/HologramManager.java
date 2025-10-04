package org.athlantes.athiSAirdrops;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Chest;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HologramManager {
    private final Plugin plugin;
    private final Map<BlockLocationKey, HologramData> holograms = new ConcurrentHashMap<>();

    // Reflection: detect Paper's customName(Component) method at runtime
    private final Method armorStandCustomNameComponent = findCustomNameComponentMethod();

    // Track forced-loaded chunks with reference counting
    private final Map<ChunkKey, Integer> forceLoadedChunkRefCounts = new ConcurrentHashMap<>();

    public HologramManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public void spawnHologram(Location location, Chest chest, int unlockSeconds, int despawnSeconds, Runnable onEmpty, Runnable onDespawn) {
        if (location == null || location.getWorld() == null || chest == null) return;

        removeHologram(location); // remove previous if exists

        Chunk chunk = location.getChunk();
        if (!chunk.isLoaded()) {
            chunk.load(true);
        }
        forceLoadChunk(chunk);

        int createDelay = Math.max(1, plugin.getConfig().getInt("hologram.create_delay_ticks", 2));
        int retries = Math.max(0, plugin.getConfig().getInt("hologram.spawn_retries", 3));
        long retryDelay = Math.max(1, plugin.getConfig().getInt("hologram.retry_delay_ticks", 10));

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!location.getChunk().isLoaded()) {
                trySpawnWithRetries(location, chest, unlockSeconds, despawnSeconds, onEmpty, onDespawn, retries, retryDelay);
                return;
            }
            createHologramEntities(location, chest, unlockSeconds, despawnSeconds, onEmpty, onDespawn);
        }, createDelay);
    }

    private void trySpawnWithRetries(Location location, Chest chest, int unlockSeconds, int despawnSeconds, Runnable onEmpty, Runnable onDespawn, int retries, long retryDelay) {
        if (retries <= 0) {
            plugin.getLogger().warning("Hologram spawn failed: chunk never loaded for " + location);
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (location.getChunk().isLoaded()) {
                createHologramEntities(location, chest, unlockSeconds, despawnSeconds, onEmpty, onDespawn);
            } else {
                trySpawnWithRetries(location, chest, unlockSeconds, despawnSeconds, onEmpty, onDespawn, retries - 1, retryDelay);
            }
        }, retryDelay);
    }

    private void createHologramEntities(Location location, Chest chest, int unlockSeconds, int despawnSeconds, Runnable onEmpty, Runnable onDespawn) {
        // Compute true chest center (handles single and double chests)
        org.bukkit.block.Block chestBlock = chest.getBlock();
        double centerX = chestBlock.getX() + 0.5;
        double centerZ = chestBlock.getZ() + 0.5;
        double baseY = chestBlock.getY();

        // Check for adjacent chest blocks (double chest) and average centers when found
        org.bukkit.block.Block other = null;
        for (org.bukkit.block.BlockFace face : new org.bukkit.block.BlockFace[] {org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST}) {
            org.bukkit.block.Block nb = chestBlock.getRelative(face);
            if (nb.getType() == chestBlock.getType()) {
                other = nb;
                break;
            }
        }
        if (other != null) {
            centerX = ((double) chestBlock.getX() + (double) other.getX()) / 2.0 + 0.5;
            centerZ = ((double) chestBlock.getZ() + (double) other.getZ()) / 2.0 + 0.5;
        }

        Location topLoc = new Location(location.getWorld(), centerX, baseY + 1.9, centerZ);
        Location bottomLoc = new Location(location.getWorld(), centerX, baseY + 1.6, centerZ);

        ArmorStand top = spawnArmorStand(topLoc);
        ArmorStand bottom = spawnArmorStand(bottomLoc);

        final int[] despawnSecondsLeft = {despawnSeconds};

        boolean lockAirdrop = plugin.getConfig().getBoolean("lock-airdrop", true);
        final int[] unlockSecondsLeft = {lockAirdrop ? unlockSeconds : 0};

        BlockLocationKey key = BlockLocationKey.from(location);
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            // Cleanup if chest removed or replaced
            if (chest.getBlock().getType() != Material.CHEST) {
                removeHologram(location);
                return;
            }

            long itemsLeft = Arrays.stream(chest.getInventory().getContents())
                    .filter(i -> i != null && i.getType() != Material.AIR)
                    .count();

            String leftTime;
            Component topComp;

            if (unlockSecondsLeft[0] > 0) {
                leftTime = String.format("%d:%02d", unlockSecondsLeft[0] / 60, unlockSecondsLeft[0] % 60);
                topComp = Component.text("🔒 Unlock in: " + leftTime);
                unlockSecondsLeft[0]--;
            } else {
                leftTime = String.format("%d:%02d", despawnSecondsLeft[0] / 60, despawnSecondsLeft[0] % 60);
                topComp = Component.text("⏳ Despawn in: " + leftTime);
                despawnSecondsLeft[0]--;
            }

            Component bottomComp = Component.text("🎁 Items Left: " + itemsLeft);
            setName(top, topComp);
            setName(bottom, bottomComp);

            if (itemsLeft <= 0) {
                try {
                    // Call the empty-specific callback (no broadcast)
                    if (onEmpty != null) {
                        onEmpty.run();
                    }
                } catch (Exception ex) {
                    plugin.getLogger().warning("onEmpty callback failed: " + ex.getMessage());
                }
                removeHologram(location);
                return;
            }

            // After decrementing, check if timers expired
            if (unlockSecondsLeft[0] <= 0 && despawnSecondsLeft[0] < 0) {
                try {
                    // Call the despawn-specific callback (should broadcast)
                    if (onDespawn != null) {
                        onDespawn.run();
                    }
                } catch (Exception ex) {
                    plugin.getLogger().warning("onDespawn callback failed: " + ex.getMessage());
                }
                removeHologram(location);
                return;
            }
        }, 0L, 20L);

        holograms.put(key, new HologramData(task, List.of(top, bottom)));
    }

    public void removeHologram(Location location) {
        if (location == null || location.getWorld() == null) return;
        BlockLocationKey key = BlockLocationKey.from(location);
        HologramData data = holograms.remove(key);
        if (data == null) return;
        data.task.cancel();
        for (ArmorStand as : data.stands) {
            try {
                if (as != null && !as.isDead()) as.remove();
            } catch (Throwable ignored) {}
        }

        // Unforce-load chunk if no more holograms in it
        Chunk chunk = location.getChunk();
        unforceLoadChunkIfUnused(chunk);
    }

    // Returns true if a hologram is currently tracked for the given location
    public boolean hasHologram(Location location) {
        if (location == null || location.getWorld() == null) return false;
        BlockLocationKey key = BlockLocationKey.from(location);
        return holograms.containsKey(key);
    }

    public void removeAll() {
        for (BlockLocationKey k : new ArrayList<>(holograms.keySet())) {
            HologramData d = holograms.remove(k);
            if (d != null) {
                d.task.cancel();
                for (ArmorStand as : d.stands) {
                    if (as != null && !as.isDead()) as.remove();
                }
                // Unforce-load chunk for each hologram removed
                Chunk chunk = new Location(Bukkit.getWorld(k.world), k.x, k.y, k.z).getChunk();
                unforceLoadChunkIfUnused(chunk);
            }
        }
        holograms.clear();
    }

    private ArmorStand spawnArmorStand(Location loc) {
        ArmorStand as = (ArmorStand) loc.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        as.setVisible(false);
        as.setGravity(false);
        as.setMarker(true);
        as.setCustomNameVisible(true);
        as.setCollidable(false);
        try { as.setPersistent(true); } catch (Throwable ignored) {}
        return as;
    }

    private void setName(ArmorStand as, Component comp) {
        try {
            if (armorStandCustomNameComponent != null) {
                armorStandCustomNameComponent.invoke(as, comp);
            } else {
                String legacy = LegacyComponentSerializer.legacySection().serialize(comp);
                as.setCustomName(legacy);
            }
            as.setCustomNameVisible(true);
        } catch (Throwable t) {
            try {
                String legacy = LegacyComponentSerializer.legacySection().serialize(comp);
                as.setCustomName(legacy);
                as.setCustomNameVisible(true);
            } catch (Throwable ignored) {}
        }
    }

    private Method findCustomNameComponentMethod() {
        try {
            Method m = ArmorStand.class.getMethod("customName", Component.class);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    private void forceLoadChunk(Chunk chunk) {
        ChunkKey key = new ChunkKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        forceLoadedChunkRefCounts.merge(key, 1, Integer::sum);
        if (forceLoadedChunkRefCounts.get(key) == 1) {
            chunk.setForceLoaded(true);
        }
    }

    private void unforceLoadChunkIfUnused(Chunk chunk) {
        ChunkKey key = new ChunkKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        forceLoadedChunkRefCounts.computeIfPresent(key, (k, count) -> {
            int newCount = count - 1;
            if (newCount <= 0) {
                chunk.setForceLoaded(false);
                return null;
            }
            return newCount;
        });
    }

    private static class HologramData {
        final BukkitTask task;
        final List<ArmorStand> stands;
        HologramData(BukkitTask t, List<ArmorStand> s) {
            this.task = t;
            this.stands = s;
        }
    }


    private static class ChunkKey {
        final String world;
        final int x, z;

        ChunkKey(String world, int x, int z) {
            this.world = world;
            this.x = x;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ChunkKey)) return false;
            ChunkKey k = (ChunkKey) o;
            return x == k.x && z == k.z && world.equals(k.world);
        }

        @Override
        public int hashCode() {
            return Objects.hash(world, x, z);
        }

        @Override
        public String toString() {
            return "ChunkKey{" + "world='" + world + '\'' + ", x=" + x + ", z=" + z + '}';
        }
    }
}
