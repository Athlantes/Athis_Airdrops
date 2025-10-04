package org.athlantes.athiSAirdrops;

import org.bukkit.block.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.Random;
import java.util.logging.Logger;

public class ItemsAdderLootProvider implements AirdropLoot.LootProvider {
    private static final Random RANDOM = new Random();
    private static final Logger LOGGER = Logger.getLogger(ItemsAdderLootProvider.class.getName());

    private final AthiSAirdrops plugin;
    private Class<?> customStackClass;
    private Method getInstanceMethod;
    private Method getItemStackMethod;

    public ItemsAdderLootProvider(AthiSAirdrops plugin) {
        this.plugin = plugin;
        try {
            customStackClass = Class.forName("dev.lone.itemsadder.api.CustomStack");
            getInstanceMethod = customStackClass.getMethod("getInstance", String.class);
            getItemStackMethod = customStackClass.getMethod("getItemStack");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().info("ItemsAdder not found. Skipping IA loot.");
            customStackClass = null;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load ItemsAdder API methods: " + e.getMessage());
            customStackClass = null;
        }
    }

    @Override
    public ConfigurationSection getSection(ConfigurationSection lootTable) {
        return lootTable.getConfigurationSection("itemsadder_loot");
    }

    @Override
    public int fillChest(Chest chest, ConfigurationSection section, int maxItems) {
        if (section == null || customStackClass == null) return 0;

        int added = 0;

        try {
            for (String key : section.getKeys(false)) {
                if (added >= maxItems) break;

                double chance = section.getDouble(key + ".chance", 1.0);
                if (RANDOM.nextDouble() > chance) continue;

                String id = section.getString(key + ".material");
                if (id == null) continue;

                Object customStack = getInstanceMethod.invoke(null, id);
                if (customStack == null) {
                    plugin.getLogger().warning("[AthisAirdrops] Unknown IA item ID: "
                            + id + " (table: " + section.getCurrentPath() + ", itemsadder_loot: " + key + ")");
                    continue;
                }

                ItemStack base = (ItemStack) getItemStackMethod.invoke(customStack);

                int min = Math.max(1, section.getInt(key + ".min", 1));
                int max = Math.max(min, section.getInt(key + ".max", min));
                base.setAmount(AirdropLoot.randomBetween(min, max));

                chest.getInventory().addItem(base.clone());
                added++;
            }
        } catch (Exception e) {
            LOGGER.warning("Exception occurred: " + e.getMessage());
        }

        return added;
    }
}

