package org.athlantes.athiSAirdrops;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Simple audit logger respecting config security.audit.enabled.
 * Lightweight so it can be called frequently without overhead.
 */
public final class SecurityAudit {
    private SecurityAudit() {}

    private static boolean enabled() {
        try { return AthiSAirdrops.getInstance().getConfig().getBoolean("security.audit.enabled", false); } catch (Throwable t){ return false; }
    }
    private static boolean flag(String key) {
        try { return AthiSAirdrops.getInstance().getConfig().getBoolean("security.audit." + key, true); } catch (Throwable t){ return false; }
    }

    private static void logRaw(String msg) {
        AthiSAirdrops.getInstance().getLogger().info("[AUDIT] " + msg);
    }

    public static void lockedOpenAttempt(Player p, Location loc) {
        if (!enabled() || !flag("log_locked_open_attempts")) return;
        logRaw("LOCKED_OPEN_ATTEMPT player=" + safe(p) + " loc=" + fmt(loc));
    }

    public static void breakAttempt(Player p, Location loc, boolean allowed) {
        if (!enabled() || !flag("log_break_attempts")) return;
        logRaw("BREAK_ATTEMPT player=" + safe(p) + " loc=" + fmt(loc) + " allowed=" + allowed);
    }

    public static void hopperMove(Location from, Location to, boolean cancelled) {
        if (!enabled() || !flag("log_hopper_moves")) return;
        logRaw("HOPPER_MOVE from=" + fmt(from) + " to=" + fmt(to) + " cancelled=" + cancelled);
    }

    private static String safe(Player p) { return p == null ? "null" : p.getName(); }
    private static String fmt(Location l) {
        if (l == null || l.getWorld() == null) return "null";
        return l.getWorld().getName()+":"+l.getBlockX()+","+l.getBlockY()+","+l.getBlockZ();
    }
}

