package org.athlantes.athiSAirdrops;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class CommandLootProvider implements AirdropLoot.LootProvider {
    private static final Random RANDOM = new Random();
    private final AthiSAirdrops plugin;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    public CommandLootProvider(AthiSAirdrops plugin) {
        this.plugin = plugin;
    }

    @Override
    public ConfigurationSection getSection(ConfigurationSection lootTable) {
        return lootTable.getConfigurationSection("commands");
    }

    @Override
    public int fillChest(Chest chest, ConfigurationSection section, int maxItems) {
        if (section == null) return 0;
        int added = 0;

        for (String key : section.getKeys(false)) {
            if (added >= maxItems) break;

            double chance = section.getDouble(key + ".chance", 1.0);
            if (RANDOM.nextDouble() > chance) continue;

            List<String> consoleCmds = section.getStringList(key + ".console_commands");
            List<String> playerCmds = section.getStringList(key + ".player_commands");

            if (consoleCmds.isEmpty() && playerCmds.isEmpty()) continue;

            String displayName = section.getString(key + ".name", "&aReward Voucher");
            List<String> loreCfg = section.getStringList(key + ".lore");
            String materialCfg = section.getString(key + ".material", "PAPER");
            Material mat = Material.matchMaterial(materialCfg);
            if (mat == null) mat = Material.PAPER;
            int amount = Math.max(1, section.getInt(key + ".amount", 1));

            ItemStack item = new ItemStack(mat, amount);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                // Name & lore
                meta.displayName(LEGACY.deserialize(displayName));
                List<Component> lore = new ArrayList<>();
                if (!loreCfg.isEmpty()) {
                    lore.addAll(loreCfg.stream().map(LEGACY::deserialize).toList());
                } else {
                    lore.add(LEGACY.deserialize("&7Right Click to redeem"));
                }
                meta.lore(lore);

                // Persistent data keys
                NamespacedKey keyConsoleMeta = new NamespacedKey(plugin, "reward_console");
                NamespacedKey keyPlayerMeta = new NamespacedKey(plugin, "reward_player");
                NamespacedKey keyConsume = new NamespacedKey(plugin, "reward_consume");
                NamespacedKey keyCooldown = new NamespacedKey(plugin, "reward_cooldown");
                NamespacedKey keyId = new NamespacedKey(plugin, "reward_id");
                // Add unique tag to mark as airdrop command item
                NamespacedKey keyAirdropCmd = new NamespacedKey(plugin, "airdrop_command_item");

                if (!consoleCmds.isEmpty())
                    meta.getPersistentDataContainer().set(keyConsoleMeta, PersistentDataType.STRING, String.join("\n", consoleCmds));
                if (!playerCmds.isEmpty())
                    meta.getPersistentDataContainer().set(keyPlayerMeta, PersistentDataType.STRING, String.join("\n", playerCmds));

                meta.getPersistentDataContainer().set(keyConsume, PersistentDataType.BYTE, (byte) (section.getBoolean(key + ".consume_on_use", true) ? 1 : 0));

                if (section.contains(key + ".cooldown_seconds"))
                    meta.getPersistentDataContainer().set(keyCooldown, PersistentDataType.INTEGER, section.getInt(key + ".cooldown_seconds", 0));

                meta.getPersistentDataContainer().set(keyId, PersistentDataType.STRING, java.util.UUID.randomUUID().toString());
                meta.getPersistentDataContainer().set(keyAirdropCmd, PersistentDataType.BYTE, (byte) 1);

                item.setItemMeta(meta);
            }

            chest.getInventory().addItem(item);
            added++;
        }

        return added;
    }
}
