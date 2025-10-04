package org.athlantes.athiSAirdrops;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class AirdropCommand implements CommandExecutor {

    public static final Map<String, Subcommand> SUBCOMMANDS = new LinkedHashMap<>();

    public record Subcommand(String name, String permission, String usage, CommandHandler handler) {}
    @FunctionalInterface
    public interface CommandHandler { void execute(CommandSender sender, String[] args); }

    private final java.util.Map<java.util.UUID, Long> spawnCooldowns = new java.util.HashMap<>();

    public AirdropCommand(AthiSAirdrops plugin, AirdropManager manager) {
        register(new Subcommand("locate", "athisairdrops.locate",
                "/airdrop locate", (sender, args) -> Messages.send(sender, "messages.airdrop_locate_hint")));

        register(new Subcommand("start", "athisairdrops.start",
                "/airdrop start", (sender, args) -> {
            if (manager.isRunning()) {
                Messages.send(sender, "messages.system_already_running");
            } else {
                manager.start();
                Messages.send(sender, "messages.autostart");
            }
        }));

        register(new Subcommand("stop", "athisairdrops.stop",
                "/airdrop stop", (sender, args) -> {
            boolean wasRunning = manager.isRunning();
            manager.stop();
            if (wasRunning) {
                Messages.send(sender, "messages.system_stopped");
            } else if (!manager.getActiveAirdrops().isEmpty()) {
                Messages.send(sender, "messages.system_stopped_manual");
            } else {
                Messages.send(sender, "messages.system_not_running");
            }
        }));

        register(new Subcommand("spawn", "athisairdrops.spawn",
                "/airdrop spawn <player|x y z> [lootTable]", (sender, args) -> {
            if (args.length < 2) { Messages.send(sender, "messages.usage_spawn"); return; }

            long cooldownSeconds = plugin.getConfig().getLong("security.spawn_cooldown_seconds", 0L);
            boolean bypassCooldown = sender.hasPermission("athisairdrops.bypass.cooldown");
            java.util.UUID senderId = (sender instanceof org.bukkit.entity.Player p) ? p.getUniqueId() : null;
            if (!bypassCooldown && cooldownSeconds > 0 && senderId != null) {
                long now = System.currentTimeMillis();
                Long last = spawnCooldowns.get(senderId);
                long cdMillis = cooldownSeconds * 1000L;
                if (last != null && now - last < cdMillis) {
                    long left = (cdMillis - (now - last)) / 1000 + 1;
                    Messages.send(sender, "messages.spawn_on_cooldown", "%seconds%", String.valueOf(left));
                    return; }
            }

            // Helpers for security bounds
            java.util.function.Predicate<org.bukkit.Location> boundsCheck = (loc) -> {
                if (loc == null || loc.getWorld() == null) return false;
                // World restriction
                java.util.List<String> allowedWorlds = plugin.getConfig().getStringList("security.restrict_worlds");
                if (allowedWorlds != null && !allowedWorlds.isEmpty()) {
                    boolean allowed = allowedWorlds.stream().anyMatch(w -> w.equalsIgnoreCase(loc.getWorld().getName()));
                    if (!allowed) { Messages.send(sender, "messages.manual_spawn_world_restricted"); return false; }
                }
                int radius = plugin.getConfig().getInt("security.max_manual_spawn_radius", 0);
                if (radius > 0) {
                    org.bukkit.Location spawn = loc.getWorld().getSpawnLocation();
                    double dist2 = spawn.distanceSquared(loc);
                    if (dist2 > (double)radius * (double)radius) {
                        Messages.send(sender, "messages.manual_spawn_out_of_bounds", "%radius%", String.valueOf(radius));
                        return false; }
                }
                return true; };

            // Helper to list loot tables for validation
            java.util.function.Supplier<java.util.List<String>> lootTablesSupplier = () -> {
                java.io.File lootDir = new java.io.File(AthiSAirdrops.getInstance().getDataFolder(), "loot");
                if (!lootDir.exists()) return java.util.List.of();
                java.io.File[] files = lootDir.listFiles((d,n) -> n.endsWith(".yml"));
                if (files == null) return java.util.List.of();
                java.util.List<String> names = new java.util.ArrayList<>();
                for (java.io.File f : files) names.add(f.getName().replaceFirst("\\.yml$", ""));
                return names; };
            java.util.List<String> lootTables = lootTablesSupplier.get();
            java.util.function.Function<String,String> resolveLoot = (input) -> {
                if (input == null) return null;
                for (String lt : lootTables) if (lt.equalsIgnoreCase(input)) return lt; // normalized id
                return null; };

            // Player form: /airdrop spawn <player> [loot]
            if (args.length >= 2 && args.length <=3) {
                org.bukkit.entity.Player target = Bukkit.getPlayer(args[1]);
                if (target != null) {
                    if (!sender.hasPermission("athisairdrops.spawn.player")) { Messages.send(sender, "messages.no_permission"); return; }
                    String rawTable = (args.length == 3 ? args[2] : null);
                    String tableId = resolveLoot.apply(rawTable);
                    if (rawTable != null && tableId == null) { Messages.send(sender, "messages.loot_table_not_found", "%table%", rawTable); return; }
                    Location loc = target.getLocation().clone();
                    loc.setY(loc.getWorld().getHighestBlockYAt(loc) + 1.0);
                    if (!boundsCheck.test(loc)) return;
                    boolean ok = manager.spawnAt(loc, tableId);
                    if (ok) {
                        if (tableId != null) Messages.send(sender, "messages.spawned_at_player_with_table", "%player%", target.getName(), "%table%", tableId);
                        else Messages.send(sender, "messages.spawned_at_player", "%player%", target.getName());
                        if (senderId != null) spawnCooldowns.put(senderId, System.currentTimeMillis());
                    } else Messages.send(sender, "messages.spawn_capacity_full");
                    return; }
            }

            // Coordinate form
            if (args.length >= 4) {
                try {
                    double x = Double.parseDouble(args[1]);
                    double y = Double.parseDouble(args[2]);
                    double z = Double.parseDouble(args[3]);
                    String rawTable = (args.length >=5 ? args[4] : null);
                    String tableId = resolveLoot.apply(rawTable);
                    if (rawTable != null && tableId == null) { Messages.send(sender, "messages.loot_table_not_found", "%table%", rawTable); return; }
                    if (!sender.hasPermission("athisairdrops.spawn.coords")) { Messages.send(sender, "messages.no_permission"); return; }
                    World world = Bukkit.getWorld(plugin.getConfig().getString("world", "world"));
                    if (world == null) { Messages.send(sender, "messages.invalid_world"); return; }
                    Location loc = new Location(world, x, y, z);
                    loc.setY(Math.max(y, world.getHighestBlockYAt(loc) + 1.0));
                    if (!boundsCheck.test(loc)) return;
                    boolean ok = manager.spawnAt(loc, tableId);
                    if (ok) {
                        if (tableId != null) Messages.send(sender, "messages.spawned_at_coords_with_table", "%x%", String.valueOf(x), "%y%", String.valueOf(loc.getY()), "%z%", String.valueOf(z), "%table%", tableId);
                        else Messages.send(sender, "messages.spawned_at_coords", "%x%", String.valueOf(x), "%y%", String.valueOf(loc.getY()), "%z%", String.valueOf(z));
                        if (senderId != null) spawnCooldowns.put(senderId, System.currentTimeMillis());
                    } else Messages.send(sender, "messages.spawn_capacity_full");
                } catch (NumberFormatException ex) { Messages.send(sender, "messages.invalid_coordinates"); }
                return; }

            Messages.send(sender, "messages.usage_spawn");
        }));

        register(new Subcommand("list", "athisairdrops.list",
                "/airdrop list", (sender, args) -> {
            Set<Location> visible = manager.getAllVisibleAirdrops();
            if (visible.isEmpty()) { Messages.send(sender, "messages.list_empty"); return; }
            Messages.send(sender, "messages.list_header", "%count%", String.valueOf(visible.size()));
            visible.stream()
                    .sorted(Comparator
                            .comparing((Location l) -> l.getWorld() != null ? l.getWorld().getName() : "")
                            .thenComparing(Location::getBlockX)
                            .thenComparing(Location::getBlockZ)
                            .thenComparing(Location::getBlockY))
                    .forEach(l -> {
                        Location norm = new Location(l.getWorld(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
                        boolean landed = manager.getActiveAirdrops().contains(norm);
                        String state = landed ? "landed" : "falling";
                        // If falling and will be locked, show yes early; otherwise use actual lock
                        String locked = landed ? (manager.isChestLocked(norm) ? "yes" : "no") : (manager.willBeLocked() ? "yes" : "no");
                        String table = Optional.ofNullable(manager.getLootTableFor(norm)).orElse("<random>");
                        Messages.send(sender, "messages.list_item",
                                "%x%", String.valueOf(l.getBlockX()),
                                "%y%", String.valueOf(l.getBlockY()),
                                "%z%", String.valueOf(l.getBlockZ()),
                                "%world%", l.getWorld() != null ? l.getWorld().getName() : "unknown",
                                "%state%", state,
                                "%locked%", locked,
                                "%table%", table);
                    });
        }));

        register(new Subcommand("clear", "athisairdrops.clear",
                "/airdrop clear <nearest|x y z>", (sender, args) -> {
            if (args.length < 2) {
                Messages.send(sender, "messages.usage_clear");
                return;
            }

            if (args[1].equalsIgnoreCase("nearest")) {
                if (!(sender instanceof Player p)) {
                    Messages.send(sender, "messages.clear_player_only_nearest");
                    return;
                }
                Optional<Location> nearest = findNearest(manager.getAllVisibleAirdrops(), p.getLocation());
                if (nearest.isEmpty()) {
                    Messages.send(sender, "messages.no_active_airdrop");
                    return;
                }
                Location loc = nearest.get();
                manager.cleanupAirdropAt(loc, true);
                Messages.send(sender, "messages.cleared_nearest",
                        "%x%", String.valueOf(loc.getBlockX()),
                        "%y%", String.valueOf(loc.getBlockY()),
                        "%z%", String.valueOf(loc.getBlockZ()));
                return;
            }

            if (args.length >= 4) {
                try {
                    int x = (int) Math.floor(Double.parseDouble(args[1]));
                    int y = (int) Math.floor(Double.parseDouble(args[2]));
                    int z = (int) Math.floor(Double.parseDouble(args[3]));

                    World world;
                    if (sender instanceof Player p) world = p.getWorld();
                    else world = Bukkit.getWorld(plugin.getConfig().getString("world", "world"));

                    if (world == null) {
                        Messages.send(sender, "messages.invalid_world");
                        return;
                    }

                    Location target = new Location(world, x, y, z);
                    // Verify there is an active or pending airdrop at these block coords
                    boolean exists = manager.getAllVisibleAirdrops().stream().anyMatch(l ->
                            l.getWorld() != null && l.getWorld().equals(world)
                                    && l.getBlockX() == x && l.getBlockY() == y && l.getBlockZ() == z);
                    if (!exists) {
                        Messages.send(sender, "messages.clear_not_found");
                        return;
                    }

                    manager.cleanupAirdropAt(target, true);
                    Messages.send(sender, "messages.cleared_at_coords",
                            "%x%", String.valueOf(x), "%y%", String.valueOf(y), "%z%", String.valueOf(z));
                } catch (NumberFormatException ex) {
                    Messages.send(sender, "messages.invalid_coordinates");
                }
                return;
            }

            Messages.send(sender, "messages.usage_clear");
        }));

        register(new Subcommand("reload", "athisairdrops.reload",
                "/airdrop reload", (sender, args) -> {
            plugin.reloadConfig();
            manager.reload();
            Messages.send(sender, "messages.reload_success");
        }));

        register(new Subcommand("status", "athisairdrops.status",
                "/airdrop status", (sender, args) -> Messages.send(sender, "messages.status", "%status%", String.valueOf(manager.isRunning()))));
    }

    private void register(Subcommand sub) {
        SUBCOMMANDS.put(sub.name().toLowerCase(), sub);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            showUsage(sender);
            return true;
        }

        Subcommand sub = SUBCOMMANDS.get(args[0].toLowerCase());
        if (sub == null) {
            Messages.send(sender, "messages.unknown_command");
            return true;
        }

        if (!sender.hasPermission(sub.permission())) {
            Messages.send(sender, "messages.no_permission");
            return true;
        }

        sub.handler().execute(sender, args);
        return true;
    }

    private void showUsage(CommandSender sender) {
        Messages.send(sender, "messages.available_commands_header");
        for (Subcommand sub : SUBCOMMANDS.values()) {
            if (sender.hasPermission(sub.permission())) {
                Messages.send(sender, "messages.command_usage_format", "%usage%", sub.usage());
            }
        }
    }

    private Optional<Location> findNearest(Set<Location> locations, Location ref) {
        if (locations == null || locations.isEmpty() || ref == null) return Optional.empty();
        return locations.stream()
                .filter(l -> l.getWorld() != null && l.getWorld().equals(ref.getWorld()))
                .min(Comparator.comparingDouble(l -> l.distanceSquared(ref)));
    }
}
