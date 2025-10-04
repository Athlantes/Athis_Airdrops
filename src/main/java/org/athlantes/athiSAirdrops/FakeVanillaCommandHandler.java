package org.athlantes.athiSAirdrops;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;

public class FakeVanillaCommandHandler {
    public static boolean handle(Player player, String commandLine) {
        String[] args = commandLine.trim().split("\\s+");
        if (args.length == 0) return false;
        String cmd = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (cmd) {
                case "give":
                    // /give %player% <item> <amount>
                    if (args.length >= 4) {
                        String targetName = args[1];
                        Player target = Bukkit.getPlayerExact(targetName);
                        if (target == null) return false;
                        String itemName = args[2];
                        int amount = Integer.parseInt(args[3]);
                        Material mat = Material.matchMaterial(itemName.replace("minecraft:", ""));
                        if (mat == null) return false;
                        target.getInventory().addItem(new org.bukkit.inventory.ItemStack(mat, amount));
                        return true;
                    }
                    break;
                case "gamemode":
                    // /gamemode <mode> %player% or /gamemode %player% <mode>
                    if (args.length == 3) {
                        String modeArg, targetName;
                        if (args[1].matches("[a-zA-Z]+")) {
                            modeArg = args[1];
                            targetName = args[2];
                        } else {
                            targetName = args[1];
                            modeArg = args[2];
                        }
                        Player target = Bukkit.getPlayerExact(targetName);
                        if (target == null) return false;
                        GameMode mode = parseGameMode(modeArg);
                        if (mode == null) return false;
                        target.setGameMode(mode);
                        return true;
                    }
                    break;
                case "effect":
                    // /effect %player% <effect> <duration> <amplifier>
                    if (args.length >= 5) {
                        Player target = Bukkit.getPlayerExact(args[1]);
                        if (target == null) return false;
                        String effectName = args[2].toUpperCase(Locale.ROOT);
                        PotionEffectType type = PotionEffectType.getByName(effectName);
                        if (type == null) return false;
                        int duration = Integer.parseInt(args[3]);
                        int amplifier = Integer.parseInt(args[4]);
                        target.addPotionEffect(new PotionEffect(type, duration, amplifier));
                        return true;
                    }
                    break;
                case "xp":
                    // /xp <amount>[L] [player]
                    if (args.length >= 2) {
                        String amountArg = args[1];
                        int amount;
                        boolean isLevel = false;
                        if (amountArg.endsWith("l") || amountArg.endsWith("L")) {
                            isLevel = true;
                            amountArg = amountArg.substring(0, amountArg.length() - 1);
                        }
                        amount = Integer.parseInt(amountArg);
                        Player target = (args.length >= 3) ? Bukkit.getPlayerExact(args[2]) : player;
                        if (target == null) return false;
                        if (isLevel) {
                            target.giveExpLevels(amount);
                        } else {
                            target.giveExp(amount);
                        }
                        return true;
                    }
                    break;
                case "enchant":
                    // /enchant <player> <enchantment> [level]
                    if (args.length >= 3) {
                        Player target = Bukkit.getPlayerExact(args[1]);
                        if (target == null) return false;
                        String enchName = args[2].toLowerCase(Locale.ROOT);
                        int level = (args.length >= 4) ? Integer.parseInt(args[3]) : 1;
                        org.bukkit.enchantments.Enchantment ench = org.bukkit.enchantments.Enchantment.getByKey(org.bukkit.NamespacedKey.minecraft(enchName));
                        if (ench == null) return false;
                        ItemStack item = target.getInventory().getItemInMainHand();
                        if (item == null || item.getType() == Material.AIR) return false;
                        item.addEnchantment(ench, level);
                        return true;
                    }
                    break;
                case "advancement":
                    // /advancement grant <player> <advancement>
                    if (args.length >= 4 && args[1].equalsIgnoreCase("grant")) {
                        Player target = Bukkit.getPlayerExact(args[2]);
                        if (target == null) return false;
                        String adv = args[3];
                        try {
                            target.getAdvancementProgress(Bukkit.getAdvancement(org.bukkit.NamespacedKey.minecraft(adv))).awardCriteria("impossible");
                        } catch (Exception ignored) {}
                        return true;
                    }
                    break;
                case "attribute":
                    // /attribute <player> <attribute> <operation> <amount>
                    if (args.length >= 5) {
                        Player target = Bukkit.getPlayerExact(args[1]);
                        if (target == null) return false;
                        String attrName = args[2].toUpperCase(Locale.ROOT);
                        String op = args[3].toLowerCase(Locale.ROOT);
                        double amount = Double.parseDouble(args[4]);
                        org.bukkit.attribute.Attribute attr = org.bukkit.attribute.Attribute.valueOf(attrName);
                        if (attr == null) return false;
                        org.bukkit.attribute.AttributeInstance inst = target.getAttribute(attr);
                        if (inst == null) return false;
                        if (op.equals("add")) {
                            inst.setBaseValue(inst.getBaseValue() + amount);
                        } else if (op.equals("set")) {
                            inst.setBaseValue(amount);
                        }
                        return true;
                    }
                    break;
                case "clear":
                    // /clear <player>
                    if (args.length >= 2) {
                        Player target = Bukkit.getPlayerExact(args[1]);
                        if (target == null) return false;
                        target.getInventory().clear();
                        return true;
                    }
                    break;
                case "kill":
                    // /kill <player>
                    if (args.length >= 2) {
                        Player target = Bukkit.getPlayerExact(args[1]);
                        if (target == null) return false;
                        target.setHealth(0.0);
                        return true;
                    }
                    break;
                // Add more vanilla command fakes as needed
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static GameMode parseGameMode(String arg) {
        arg = arg.toLowerCase(Locale.ROOT);
        switch (arg) {
            case "0": case "survival": return GameMode.SURVIVAL;
            case "1": case "creative": return GameMode.CREATIVE;
            case "2": case "adventure": return GameMode.ADVENTURE;
            case "3": case "spectator": return GameMode.SPECTATOR;
            default: return null;
        }
    }
}
