package org.athlantes.athiSAirdrops;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class AirdropTabCompleter implements TabCompleter {

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender,@NotNull Command command,@NotNull String alias,@NotNull String[] args) {
        if (args.length == 1) {
            return filter(sender, args[0]);
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("spawn")) {
            // /airdrop spawn <player> [lootTable]
            if (args.length == 2) {
                // If the second token looks like a number, suggest coordinate placeholder for Y
                try {
                    Double.parseDouble(args[1]);
                    return List.of("<y>");
                } catch (NumberFormatException ignored) {}

                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                        .toList();
            }

            // args.length == 3: could be player + lootTable OR x (already typed) and now typing y
            if (args.length == 3) {
                // If args[1] is numeric => user is entering coordinates -> suggest <y>
                try {
                    Double.parseDouble(args[1]);
                    return List.of("<y>");
                } catch (NumberFormatException ignored) {
                    // player-form: suggest loot tables filtered by what the user typed in args[2]
                    return listLootTables().stream()
                            .filter(t -> t.toLowerCase().startsWith(args[2].toLowerCase()))
                            .collect(Collectors.toList());
                }
            }

            // args.length == 4: could be coords x y and now typing z -> suggest <z>
            if (args.length == 4) {
                try {
                    Double.parseDouble(args[1]);
                    return List.of("<z>");
                } catch (NumberFormatException ignored) {
                    // player name + lootTable already handled at length==3; here default to empty
                    return List.of();
                }
            }

            // Suggest loot table for coordinate-form: /airdrop spawn x y z <lootTable>
            if (args.length == 5) {
                return listLootTables().stream()
                        .filter(t -> t.toLowerCase().startsWith(args[4].toLowerCase()))
                        .collect(Collectors.toList());
            }

            return List.of("<x>", "<y>", "<z>");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("clear")) {
            List<String> opts = new ArrayList<>();
            if ("nearest".startsWith(args[1].toLowerCase())) opts.add("nearest");
            opts.add("<x>");
            return opts;
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("clear")) {
            return List.of("<" + (args.length == 3 ? "y" : "z") + ">");
        }
        return Collections.emptyList();
    }

    private List<String> filter(CommandSender sender, String partial) {
        List<String> subs = new ArrayList<>();
        for (AirdropCommand.Subcommand sub : AirdropCommand.SUBCOMMANDS.values()) {
            if (sender.hasPermission(sub.permission()) &&
                    sub.name().toLowerCase().startsWith(partial.toLowerCase())) {
                subs.add(sub.name());
            }
        }
        return subs;
    }

    private List<String> listLootTables() {
        File lootDir = new File(AthiSAirdrops.getInstance().getDataFolder(), "loot");
        if (!lootDir.exists()) return List.of();
        File[] files = lootDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) return List.of();
        return java.util.Arrays.stream(files)
                .map(File::getName)
                .map(n -> n.replaceFirst("\\.yml$", ""))
                .collect(Collectors.toList());
    }
}
