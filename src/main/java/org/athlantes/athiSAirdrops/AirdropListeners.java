package org.athlantes.athiSAirdrops;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitScheduler;

import java.util.*;

public class AirdropListeners implements Listener {

    private final AthiSAirdrops plugin;
    private final AirdropManager manager;
    private final Map<UUID, Long> compassCooldowns = new HashMap<>();
    // Tracks per-player last-used timestamps for voucher identifiers (joined command string)
    private final Map<UUID, Map<String, Long>> commandCooldowns = new HashMap<>();

    public AirdropListeners(AthiSAirdrops plugin, AirdropManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    private long getCompassCooldownMs() {
        return plugin.getConfig().getInt("compass-cooldown", 10) * 1000L;
    }

    @EventHandler
    public void onChestBreak(BlockBreakEvent event) {
        if (event.getBlock().getState() instanceof Chest chest && manager.isProtectedChest(chest)) {
            boolean allowed = event.getPlayer().hasPermission("athisairdrops.bypass.protect");
            SecurityAudit.breakAttempt(event.getPlayer(), chest.getLocation(), allowed);
            if (allowed) {
                manager.cleanupAirdropAt(chest.getLocation(), true);
                return;
            }
            event.setCancelled(true);
            Messages.send(event.getPlayer(), "messages.chest_protected");
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Chest chest)) return;
        if (!manager.isProtectedChest(chest)) return;

        boolean empty = Arrays.stream(event.getInventory().getContents())
                .allMatch(i -> i == null || i.getType() == Material.AIR);

        if (empty && event.getPlayer() instanceof Player) {
            Messages.send(event.getPlayer(), "messages.chest_empty_notice");
            Location loc = chest.getLocation();
            Block block = loc.getBlock();
            if (block.getType() == Material.CHEST && block.getState() instanceof Chest c) {
                boolean stillEmpty = Arrays.stream(c.getInventory().getContents())
                        .allMatch(i -> i == null || i.getType() == Material.AIR);
                if (stillEmpty) {
                    BukkitScheduler scheduler = plugin.getServer().getScheduler();
                    scheduler.runTask(plugin, () -> manager.cleanupAirdropAt(loc, false));
                }
            }
        }
    }

    @EventHandler
    public void onCompassUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!event.getAction().toString().contains("RIGHT_CLICK")) return;

        Player player = event.getPlayer();
        if (!player.hasPermission("athisairdrops.compass")) return; // silently ignore

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() != Material.COMPASS) return;

        // If compass feature is disabled, do nothing and let vanilla behavior occur
        boolean compassEnabled = plugin.getConfig().getBoolean("enable-compass", true);
        if (!compassEnabled) return;

        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        Long lastUse = compassCooldowns.get(id);
        long cooldownMs = getCompassCooldownMs();

        if (lastUse != null && now - lastUse < cooldownMs) {
            long secondsLeft = (cooldownMs - (now - lastUse)) / 1000 + 1;
            Messages.send(player, "messages.compass_cooldown", "%seconds%", String.valueOf(secondsLeft));
            event.setCancelled(true);
            return;
        }

        compassCooldowns.put(id, now);

        // Find nearest active airdrop in same world
        Set<Location> activeAirdrops = manager.getAllVisibleAirdrops();
        List<Location> sameWorldAirdrops = activeAirdrops.stream()
                .filter(loc -> loc.getWorld() != null && loc.getWorld().equals(player.getWorld()))
                .toList();

        if (sameWorldAirdrops.isEmpty()) {
            Messages.send(player, "messages.no_active_airdrop");
            event.setCancelled(true);
            return;
        }

        Location nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Location loc : sameWorldAirdrops) {
            try {
                double dist = player.getLocation().distance(loc);
                if (dist < nearestDistance) {
                    nearestDistance = dist;
                    nearest = loc;
                }
            } catch (IllegalArgumentException ignored) {}
        }

        if (nearest == null) {
            Messages.send(player, "messages.no_active_airdrop");
            event.setCancelled(true);
            return;
        }

        // Compass is enabled here — set target and send directional message
        player.setCompassTarget(nearest);
        String direction = calculateDirection(player, nearest);
        Messages.send(player, "messages.compass_location",
                "%distance%", String.valueOf((int) nearestDistance),
                "%direction%", direction);

        event.setCancelled(true);
    }

    private String calculateDirection(Player player, Location target) {
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

    @EventHandler
    public void onChestInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || event.getClickedBlock().getType() != Material.CHEST) return;
        Location loc = event.getClickedBlock().getLocation();

        if (manager.isChestLocked(loc)) {
            if (event.getPlayer().hasPermission("athisairdrops.bypass.lock")) {
                return; // bypass lock
            }
            SecurityAudit.lockedOpenAttempt(event.getPlayer(), loc);
            Messages.send(event.getPlayer(), "messages.chest_locked");
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (!AthiSAirdrops.getInstance().getConfig().getBoolean("security.deny_hopper_looting", true)) return;
        try {
            if (event.getSource().getHolder() instanceof Chest chest) {
                if (manager.isProtectedChest(chest)) {
                    boolean lockedBlock = AthiSAirdrops.getInstance().getConfig().getBoolean("security.block_hoppers_before_unlock", true) && manager.isChestLocked(chest.getLocation());
                    if (lockedBlock || manager.isProtectedChest(chest)) {
                        event.setCancelled(true);
                        SecurityAudit.hopperMove(chest.getLocation(), chest.getLocation(), true);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    @EventHandler
    public void onPlayerUse(PlayerInteractEvent event) {
        if (!event.getAction().toString().contains("RIGHT_CLICK")) return;

        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        // Only allow command execution for items with the airdrop_command_item tag
        NamespacedKey keyAirdropCmd = new NamespacedKey(plugin, "airdrop_command_item");
        Byte airdropTag = meta.getPersistentDataContainer().get(keyAirdropCmd, PersistentDataType.BYTE);
        if (airdropTag == null || airdropTag != 1) return;

        Player player = event.getPlayer();
        if (!player.hasPermission("athisairdrops.voucher")) { // global voucher permission
            event.setCancelled(true);
            return;
        }

        // --- PersistentData keys ---
        NamespacedKey keyConsole = new NamespacedKey(plugin, "reward_console");
        NamespacedKey keyPlayer = new NamespacedKey(plugin, "reward_player");
        NamespacedKey keyConsume = new NamespacedKey(plugin, "reward_consume");
        NamespacedKey keyCooldown = new NamespacedKey(plugin, "reward_cooldown");
        NamespacedKey keyId = new NamespacedKey(plugin, "reward_id");
        NamespacedKey keyPerm = new NamespacedKey(plugin, "reward_permission");

        // --- Permission check ---
        String permission = meta.getPersistentDataContainer().getOrDefault(keyPerm, PersistentDataType.STRING, "");
        if (!permission.isBlank() && !player.hasPermission(permission)) {
            player.sendMessage(Component.text("You do not have permission to redeem this item.")
                    .color(net.kyori.adventure.text.format.NamedTextColor.RED));
            event.setCancelled(true);
            return;
        }

        // --- Cooldown check ---
        int cooldownSeconds = meta.getPersistentDataContainer().getOrDefault(keyCooldown, PersistentDataType.INTEGER, 0);
        String rewardId = meta.getPersistentDataContainer().getOrDefault(keyId, PersistentDataType.STRING, "");
        if (cooldownSeconds > 0) {
            UUID uuid = player.getUniqueId();
            long now = System.currentTimeMillis();
            Map<String, Long> m = commandCooldowns.computeIfAbsent(uuid, k -> new HashMap<>());
            Long last = m.get(rewardId);
            if (last != null && now - last < cooldownSeconds * 1000L) {
                long secondsLeft = ((cooldownSeconds * 1000L) - (now - last)) / 1000 + 1;
                player.sendMessage(Component.text("This voucher is on cooldown for " + secondsLeft + "s.")
                        .color(net.kyori.adventure.text.format.NamedTextColor.RED));
                event.setCancelled(true);
                return;
            }
        }

        // --- Execute console commands ---
        if (meta.getPersistentDataContainer().has(keyConsole, PersistentDataType.STRING)) {
            String consoleCmds = meta.getPersistentDataContainer().get(keyConsole, PersistentDataType.STRING);
            if (consoleCmds != null && !consoleCmds.isBlank()) {
                for (String c : consoleCmds.split("\n")) {
                    String cmd = c.replace("%player%", player.getName());
                    if (!FakeVanillaCommandHandler.handle(player, cmd)) {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                    }
                }
            }
        }

        // --- Execute player commands ---
        if (meta.getPersistentDataContainer().has(keyPlayer, PersistentDataType.STRING)) {
            String playerCmds = meta.getPersistentDataContainer().get(keyPlayer, PersistentDataType.STRING);
            if (playerCmds != null && !playerCmds.isBlank()) {
                for (String c : playerCmds.split("\n")) {
                    String cmd = c.replace("%player%", player.getName());
                    if (!FakeVanillaCommandHandler.handle(player, cmd)) {
                        Bukkit.dispatchCommand(player, cmd);
                    }
                }
            }
        }

        // --- Post-execution handling ---
        boolean consumeOnUse = meta.getPersistentDataContainer().getOrDefault(keyConsume, PersistentDataType.BYTE, (byte)1) != 0;
        if (consumeOnUse) item.setAmount(Math.max(0, item.getAmount() - 1));

        if (cooldownSeconds > 0 && !rewardId.isBlank()) {
            Map<String, Long> m = commandCooldowns.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
            m.put(rewardId, System.currentTimeMillis());
        }

        player.sendMessage(Component.text("You redeemed your reward!")
                .color(net.kyori.adventure.text.format.NamedTextColor.GREEN));

        event.setCancelled(true);
    }

}
