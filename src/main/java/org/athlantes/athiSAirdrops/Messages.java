package org.athlantes.athiSAirdrops;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

public final class Messages {
    private Messages() {}

    public static void send(CommandSender sender, String path, String... placeholders) {
        String msg = AthiSAirdrops.getInstance().getConfig().getString(path, path);
        msg = applyPlaceholders(msg, placeholders);
        sender.sendMessage(Component.text(color(msg)));
    }

    public static void broadcast(String path, String... placeholders) {
        String msg = AthiSAirdrops.getInstance().getConfig().getString(path, path);
        msg = applyPlaceholders(msg, placeholders);
        // Send to all online players as Component (preserves color codes)
        for (var p : Bukkit.getOnlinePlayers()) {
            try { p.sendMessage(Component.text(color(msg))); } catch (Throwable ignored) {}
        }
        // Also log to console (plain colored string)
        Bukkit.getConsoleSender().sendMessage(color(msg));
    }

    public static String applyPlaceholders(String msg, String... placeholders) {
        if (placeholders != null) {
            for (int i = 0; i + 1 < placeholders.length; i += 2) {
                msg = msg.replace(placeholders[i], placeholders[i + 1]);
            }
        }
        return msg;
    }

    public static String color(String msg) {
        return msg.replace("&", "§");
    }
}
