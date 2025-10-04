package org.athlantes.athiSAirdrops;

import org.bukkit.block.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.logging.Logger;

import com.nexomc.nexo.api.NexoItems;
import com.nexomc.nexo.items.ItemBuilder;

public class NexoLootProvider implements AirdropLoot.LootProvider {
    private static final Logger LOGGER = Logger.getLogger(NexoLootProvider.class.getName());

    public NexoLootProvider(AthiSAirdrops plugin) {
    }

    @Override
    public ConfigurationSection getSection(ConfigurationSection lootTable) {
        ConfigurationSection section = lootTable.getConfigurationSection("nexo_loot");
        if (section == null) {
            return null;
        }
        return section;
    }

    @Override
    public int fillChest(Chest chest, ConfigurationSection section, int maxItems) {
        if (section == null) {
            return 0;
        }

        int added = 0;

        for (String key : section.getKeys(false)) {
            if (added >= maxItems) break;

            double chance = section.getDouble(key + ".chance", 1.0);
            if (Math.random() > chance) {
                continue;
            }

            int min = Math.max(1, section.getInt(key + ".min", 1));
            int max = Math.max(min, section.getInt(key + ".max", min));
            int amount = min + (max > min ? (int) (Math.random() * (max - min + 1)) : 0);

            String nexoId = section.getString(key + ".nexo_id", "").trim();
            if (nexoId.isEmpty()) {
                LOGGER.warning("[AthiSAirdrops] nexo_id missing for key: " + key);
                continue;
            }

            try {
                ItemBuilder itemBuilder = NexoItems.itemFromId(nexoId);
                if (itemBuilder != null) {
                    ItemStack itemStack = itemBuilder.build();
                    if (itemStack != null) {
                        itemStack.setAmount(amount);
                        chest.getInventory().addItem(itemStack);
                        added++;
                    }
                } else {
                    LOGGER.warning("[AthiSAirdrops] Nexo item not found: " + nexoId);
                }
            } catch (Exception e) {
                LOGGER.warning("[AthiSAirdrops] Failed to add Nexo item '" + nexoId + "': " + e.getMessage());
                e.printStackTrace();
            }
        }
        return added;
    }
}
