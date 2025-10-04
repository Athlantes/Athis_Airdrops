package org.athlantes.athiSAirdrops;

import org.bukkit.Location;
import org.bukkit.block.Block;

import java.util.Objects;

/**
 * Immutable key for identifying a block location (world + block coordinates).
 */
public final class BlockLocationKey {
    public final String world;
    public final int x;
    public final int y;
    public final int z;

    public BlockLocationKey(String world, int x, int y, int z) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static BlockLocationKey from(Location l) {
        return new BlockLocationKey(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    public static BlockLocationKey from(Block b) {
        return new BlockLocationKey(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockLocationKey)) return false;
        BlockLocationKey k = (BlockLocationKey) o;
        return x == k.x && y == k.y && z == k.z && world.equals(k.world);
    }

    @Override
    public int hashCode() {
        return Objects.hash(world, x, y, z);
    }

    @Override
    public String toString() {
        return "BlockLocationKey{" +
                "world='" + world + '\'' +
                ", x=" + x +
                ", y=" + y +
                ", z=" + z +
                '}';
    }
}

