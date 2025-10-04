package org.athlantes.athiSAirdrops;

import org.bukkit.Location;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Manages temporary locks on airdrop chests.
 */
public class AirdropLockManager {
    private final ConcurrentMap<BlockLocationKey, Long> unlockTimes = new ConcurrentHashMap<>();

    public void addLock(Location loc, long unlockAtMillis) {
        unlockTimes.put(BlockLocationKey.from(loc), unlockAtMillis);
    }

    public boolean isLocked(Location loc) {
        BlockLocationKey key = BlockLocationKey.from(loc);
        Long at = unlockTimes.get(key);
        if (at == null) return false;
        if (System.currentTimeMillis() >= at) { unlockTimes.remove(key); return false; }
        return true;
    }

    public void removeLock(Location loc) {
        unlockTimes.remove(BlockLocationKey.from(loc));
    }

    public void clear() { unlockTimes.clear(); }
}

