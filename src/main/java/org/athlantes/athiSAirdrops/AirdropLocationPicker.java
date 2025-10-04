package org.athlantes.athiSAirdrops;

import org.bukkit.*;
import org.bukkit.block.Block;

import java.util.Random;

/**
 * Picks random valid locations for airdrops based on config constraints.
 */
public class AirdropLocationPicker {
    private final AthiSAirdrops plugin;
    private final Random random = new Random();

    public AirdropLocationPicker(AthiSAirdrops plugin) { this.plugin = plugin; }

    public Location pickRandomValidLocation(World world) {
        boolean onlyLoaded = plugin.getConfig().getBoolean("spawn_in_loaded_chunks_only", true);
        int radius = Math.max(10, plugin.getConfig().getInt("spawn_radius", 1000));
        int centerX = plugin.getConfig().getInt("center_x", world.getSpawnLocation().getBlockX());
        int centerZ = plugin.getConfig().getInt("center_z", world.getSpawnLocation().getBlockZ());
        final int MAX_ATTEMPTS = plugin.getConfig().getInt("max_location_attempts", 100);

        if (onlyLoaded) {
            Chunk[] loaded = world.getLoadedChunks();
            if (loaded.length == 0) {
                plugin.getLogger().info("Airdrop: no loaded chunks in world '" + world.getName() + "'. Falling back to radius-based search.");
                // fall through to radius-based search below
            } else {
                for (int attempts = 0; attempts < MAX_ATTEMPTS; attempts++) {
                    Chunk c = loaded[random.nextInt(loaded.length)];
                    int bx = (c.getX() << 4) + random.nextInt(16);
                    int bz = (c.getZ() << 4) + random.nextInt(16);
                    int by = world.getHighestBlockYAt(bx, bz);
                    Location loc = new Location(world, bx + 0.5, by + 1.0, bz + 0.5);
                    Block block = loc.getBlock();
                    Block below = loc.clone().subtract(0,1,0).getBlock();
                    if (isValidSurface(block, below)) return loc;
                }
                plugin.getLogger().info("Airdrop: failed to find valid location in loaded chunks after " + MAX_ATTEMPTS + " attempts.");
                return null;
            }
        }

        // Radius-based search (or fallback when no loaded chunks)
        boolean loadIfGenerated = plugin.getConfig().getBoolean("load_generated_chunk_if_unloaded", false);
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            int x = centerX + random.nextInt(radius * 2 + 1) - radius;
            int z = centerZ + random.nextInt(radius * 2 + 1) - radius;
            int chunkX = x >> 4;
            int chunkZ = z >> 4;
            boolean generated;
            try {
                generated = world.isChunkGenerated(chunkX, chunkZ);
            } catch (NoSuchMethodError | UnsupportedOperationException ex) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) continue;
                generated = true;
            }
            if (!generated) continue;
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                if (!loadIfGenerated) continue;
                world.getChunkAt(chunkX, chunkZ).load(true);
            }
            int y = world.getHighestBlockYAt(x, z);
            Location loc = new Location(world, x + 0.5, y + 1.0, z + 0.5);
            Block block = loc.getBlock();
            Block below = loc.clone().subtract(0,1,0).getBlock();
            if (isValidSurface(block, below)) return loc;
        }
        plugin.getLogger().info("Airdrop: failed to find valid location in radius-based search after " + MAX_ATTEMPTS + " attempts.");
        return null;
    }

    private boolean isValidSurface(Block block, Block below) {
        if (block == null || below == null) return false;
        Material b = block.getType();
        Material bl = below.getType();
        if (b == Material.LAVA || b == Material.WATER) return false;
        if (bl == Material.LAVA || bl == Material.WATER) return false;
        return below.getType().isSolid();
    }
}
