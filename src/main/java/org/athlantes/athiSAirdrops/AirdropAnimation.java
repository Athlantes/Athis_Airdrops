package org.athlantes.athiSAirdrops;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Handles the drop animation. Now supports multiple animations concurrently.
 */
public class AirdropAnimation {
    private final AthiSAirdrops plugin;

    // Per-location animation state
    private final Map<BlockLocationKey, AnimationState> animations = new ConcurrentHashMap<>();

    public AirdropAnimation(AthiSAirdrops plugin) {
        this.plugin = plugin;
    }

    public void start(Location target, Consumer<Location> onFinish) {
        if (target == null) return;

        boolean enabled = plugin.getConfig().getBoolean("animation.enabled", true);
        if (!enabled) {
            try { onFinish.accept(target); } catch (Exception ignored) {}
            return;
        }

        World world = target.getWorld();
        if (world == null) return;

        // If an animation is already running for this location, stop it first
        stop(target);

        int height = Math.max(0, plugin.getConfig().getInt("animation.height", 50));
        long intervalTicks = Math.max(1, plugin.getConfig().getInt("animation.interval_ticks", 2));
        double blocksPerTick = plugin.getConfig().getDouble("animation.blocks_per_tick", 0.1);
        if (blocksPerTick <= 0) blocksPerTick = 0.1;

        Location startLoc = target.clone().add(0, height, 0);
        ArmorStand stand = (ArmorStand) world.spawnEntity(startLoc, EntityType.ARMOR_STAND);
        stand.setInvisible(true);
        stand.setMarker(true);
        stand.setGravity(false);
        stand.setInvulnerable(true);
        stand.setCollidable(false);
        stand.getEquipment().setHelmet(new ItemStack(Material.CHEST));
        stand.setCustomNameVisible(false);

        final double descentPerRun = blocksPerTick * (double) intervalTicks;
        BlockLocationKey key = BlockLocationKey.from(target);

        BukkitTask task = new BukkitRunnable() {
            double currentY = startLoc.getY();
            @Override public void run() {
                if (stand == null || stand.isDead()) { cancel(); animations.remove(key); return; }
                currentY -= descentPerRun;
                if (currentY <= target.getY()) {
                    try { stand.remove(); } catch (Throwable ignored) {}
                    animations.remove(key);
                    try { onFinish.accept(target); } catch (Exception ignored) {}
                    cancel();
                    return;
                }
                Location newLoc = stand.getLocation();
                newLoc.setY(currentY);
                stand.teleport(newLoc);
            }
        }.runTaskTimer(plugin, 0L, intervalTicks);

        animations.put(key, new AnimationState(stand, task));
    }

    public void stop(Location loc) {
        if (loc == null) return;
        AnimationState s = animations.remove(BlockLocationKey.from(loc));
        if (s == null) return;
        try { s.task.cancel(); } catch (Throwable ignored) {}
        try { if (s.stand != null && !s.stand.isDead()) s.stand.remove(); } catch (Throwable ignored) {}
    }

    public void stopAll() {
        for (AnimationState s : animations.values()) {
            try { s.task.cancel(); } catch (Throwable ignored) {}
            try { if (s.stand != null && !s.stand.isDead()) s.stand.remove(); } catch (Throwable ignored) {}
        }
        animations.clear();
    }

    private static final class AnimationState {
        final ArmorStand stand;
        final BukkitTask task;
        AnimationState(ArmorStand stand, BukkitTask task) {
            this.stand = stand; this.task = task;
        }
    }
}
