package org.athlantes.athiSAirdrops;

import org.bukkit.block.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.Random;
import java.util.logging.Logger;

public class MMOItemsLootProvider implements AirdropLoot.LootProvider {
    private static final Random RANDOM = new Random();
    private static final Logger LOGGER = Logger.getLogger(MMOItemsLootProvider.class.getName());

    private final AthiSAirdrops plugin;
    private boolean mmoitemsEnabled;

    public MMOItemsLootProvider(AthiSAirdrops plugin) {
        this.plugin = plugin;
        this.mmoitemsEnabled = plugin.isMMOItemsEnabled();
    }

    @Override
    public ConfigurationSection getSection(ConfigurationSection lootTable) {
        return lootTable.getConfigurationSection("mmoitems_loot");
    }

    @Override
    public int fillChest(Chest chest, ConfigurationSection section, int maxItems) {
        if (section == null || !mmoitemsEnabled) return 0;
        int added = 0;
        try {
            Class<?> mmoItemsClass = Class.forName("net.Indyuce.mmoitems.MMOItems");
            Object mmoItems = mmoItemsClass.getField("plugin").get(null);
            Class<?> typeClass = Class.forName("net.Indyuce.mmoitems.api.Type");
            Method getTypes = mmoItemsClass.getMethod("getTypes");
            Object types = getTypes.invoke(mmoItems);
            Method getType = types.getClass().getMethod("get", String.class);
            Method getItem = mmoItemsClass.getMethod("getItem", typeClass, String.class);

            for (String key : section.getKeys(false)) {
                if (added >= maxItems) break;
                double chance = section.getDouble(key + ".chance", 1.0);
                if (RANDOM.nextDouble() > chance) continue;
                String type = section.getString(key + ".type");
                String id = section.getString(key + ".id");
                if (type == null || id == null) continue;
                Object mmoType = getType.invoke(types, type);
                Object itemStack = getItem.invoke(mmoItems, mmoType, id);
                if (itemStack == null) {
                    plugin.getLogger().warning("[AthiSAirdrops] Unknown MMOItem: type=" + type + ", id=" + id);
                    continue;
                }
                int min = Math.max(1, section.getInt(key + ".min", 1));
                int max = Math.max(min, section.getInt(key + ".max", min));
                ItemStack base = ((ItemStack) itemStack).clone();
                base.setAmount(AirdropLoot.randomBetween(min, max));
                chest.getInventory().addItem(base);
                added++;
            }
        } catch (Exception e) {
            LOGGER.warning("Exception occurred in MMOItemsLootProvider: " + e.getMessage());
        }
        return added;
    }
}

