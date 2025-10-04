package org.athlantes.athiSAirdrops;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.scheduler.BukkitTask;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global particle manager — one repeating task services all active chests.
 */
public class ParticleManager {
    private final AthiSAirdrops plugin;
    private final Set<Block> activeChests = ConcurrentHashMap.newKeySet();
    private BukkitTask task;
    private int intervalTicks = 10;

    public ParticleManager(AthiSAirdrops plugin) { this.plugin = plugin; }

    public void setInterval(int ticks) {
        this.intervalTicks = Math.max(1, ticks);
        restartIfRunning();
    }

    public void addChest(Block block) {
        if (block == null) return;
        activeChests.add(block);
        startIfNeeded();
    }

    public void removeChest(Block block) {
        if (block == null) return;
        activeChests.remove(block);
        if (activeChests.isEmpty()) stop();
    }

    private void startIfNeeded() {
        if (task != null) return;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            Iterator<Block> it = activeChests.iterator();
            while (it.hasNext()) {
                Block b = it.next();
                if (!(b.getState() instanceof Chest)) { it.remove(); continue; }
                Location baseLoc = b.getLocation().clone().add(0.5, 1.0, 0.5);
                b.getWorld().spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, baseLoc, 1, 0.0, 1.5, 0.0, 0.05);
            }
        }, 0L, intervalTicks);
    }

    private void restartIfRunning() {
        if (task != null) {
            task.cancel(); task = null; startIfNeeded();
        }
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        activeChests.clear();
    }
}

