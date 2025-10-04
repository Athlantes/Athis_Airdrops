package org.athlantes.athiSAirdrops;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.*;
import java.util.logging.Logger;

public class AirdropLoot {

    private final AthiSAirdrops plugin;
    private final LootTableSelector lootTableSelector;
    private final List<LootProvider> lootProviders;

    public AirdropLoot(AthiSAirdrops plugin) {
        this.plugin = plugin;
        migrateLootYmlToFolderIfNeeded();
        this.lootTableSelector = new LootTableSelector(plugin);

        this.lootProviders = new ArrayList<>();
        if (plugin.isItemsAdderEnabled()) {
            this.lootProviders.add(new ItemsAdderLootProvider(plugin));
        }
        if (plugin.isMMOItemsEnabled()) {
            this.lootProviders.add(new MMOItemsLootProvider(plugin));
        }
        if (plugin.isNexoEnabled()) {
            this.lootProviders.add(new NexoLootProvider(plugin));
        }
        this.lootProviders.add(new VanillaLootProvider(plugin));
        this.lootProviders.add(new CommandLootProvider(plugin));
    }

    /**
     * Migrates loot tables from loot.yml to loot/ folder if needed.
     */
    private void migrateLootYmlToFolderIfNeeded() {
        File lootYml = new File(plugin.getDataFolder(), "loot.yml");
        File lootDir = new File(plugin.getDataFolder(), "loot");
        if (lootYml.exists() && (!lootDir.exists() || Objects.requireNonNull(lootDir.listFiles()).length == 0)) {
            try {
                FileConfiguration lootConfig = YamlConfiguration.loadConfiguration(lootYml);
                lootDir.mkdirs();
                for (String tableName : lootConfig.getKeys(false)) {
                    ConfigurationSection tableSection = lootConfig.getConfigurationSection(tableName);
                    if (tableSection != null) {
                        File outFile = new File(lootDir, tableName + ".yml");
                        YamlConfiguration outConfig = new YamlConfiguration();
                        for (String key : tableSection.getKeys(false)) {
                            outConfig.set(key, tableSection.get(key));
                        }
                        outConfig.save(outFile);
                    }
                }
                plugin.getLogger().info("[AthiSAirdrops] Migrated loot tables from loot.yml to loot/ folder.");
            } catch (Exception e) {
                plugin.getLogger().warning("[AthiSAirdrops] Failed to migrate loot.yml to loot folder: " + e.getMessage());
            }
        }
    }

    private static final Logger LOGGER = Logger.getLogger(AirdropLoot.class.getName());

    public static int randomBetween(int min, int max) {
        Random random = new Random();
        return max <= min ? min : min + random.nextInt(max - min + 1);
    }

    // -- LootProvider interface and implementations --

    public interface LootProvider {

        ConfigurationSection getSection(ConfigurationSection lootTable);

        int fillChest(Chest chest, ConfigurationSection section, int maxItems);
    }

    public static class VanillaLootProvider implements LootProvider {
        private static final Random RANDOM = new Random();

        private final AthiSAirdrops plugin;

        public VanillaLootProvider(AthiSAirdrops plugin) {
            this.plugin = plugin;
        }

        @Override
        public ConfigurationSection getSection(ConfigurationSection lootTable) {
            return lootTable.getConfigurationSection("loot");
        }

        @Override
        public int fillChest(Chest chest, ConfigurationSection section, int maxItems) {
            if (section == null) {
                LOGGER.warning("Section is null, skipping fillChest.");
                return 0;
            }

            Set<String> keys = section.getKeys(false);
            if (keys == null) {
                LOGGER.warning("Section keys are null, skipping.");
                return 0;
            }

            List<ItemStack> pool = new ArrayList<>();

            for (String key : keys) {
                double chance = section.getDouble(key + ".chance", 1.0);
                if (RANDOM.nextDouble() > chance) continue;

                Material mat = Material.matchMaterial(section.getString(key + ".material", "STONE"));
                if (mat == null) continue;

                int min = Math.max(1, section.getInt(key + ".min", 1));
                int max = Math.max(min, section.getInt(key + ".max", min));

                ItemStack base = new ItemStack(mat, randomBetween(min, max));

                if (section.isConfigurationSection(key + ".enchantments")) {
                    ItemMeta meta = base.getItemMeta();
                    ConfigurationSection enchSection = section.getConfigurationSection(key + ".enchantments");
                    if (enchSection != null) {
                        for (String enchKey : enchSection.getKeys(false)) {
                            NamespacedKey nsKey = NamespacedKey.minecraft(enchKey.toLowerCase(Locale.ROOT));
                            Enchantment ench = Enchantment.getByKey(nsKey);
                            if (ench != null) {
                                int level = Math.max(1, enchSection.getInt(enchKey, 1));
                                meta.addEnchant(ench, level, true);
                            } else {
                                LOGGER.warning("Unknown enchantment: " + enchKey);
                            }
                        }
                    }
                    base.setItemMeta(meta);
                }
                pool.add(base.clone());
            }

            Collections.shuffle(pool, RANDOM);

            int size = chest.getInventory().getSize();
            Set<Integer> usedSlots = new HashSet<>();
            int added = 0;

            for (ItemStack item : pool) {
                if (added >= maxItems) break;

                int tries = 0;
                while (tries < 40) {
                    int slot = RANDOM.nextInt(size);
                    if (!usedSlots.contains(slot)) {
                        chest.getInventory().setItem(slot, item);
                        usedSlots.add(slot);
                        added++;
                        break;
                    }
                    tries++;
                }
            }

            return added;
        }
    }

    public static class LootTableSelector {
        private final AthiSAirdrops plugin;
        private final Map<String, ConfigurationSection> lootTables = new LinkedHashMap<>();

        public LootTableSelector(AthiSAirdrops plugin) {
            this.plugin = plugin;
            loadLootTables();
        }

        public void reload() {
            lootTables.clear();
            loadLootTables();
        }

        private void loadLootTables() {
            File lootDir = new File(plugin.getDataFolder(), "loot");
            if (!lootDir.exists()) lootDir.mkdirs();

            File[] files = lootDir.listFiles((dir, name) -> name.endsWith(".yml"));
            if (files == null) return;

            boolean mmoItemsEnabled = org.bukkit.Bukkit.getPluginManager().isPluginEnabled("MMOItems");
            boolean itemsAdderEnabled = org.bukkit.Bukkit.getPluginManager().isPluginEnabled("ItemsAdder");

            for (File file : files) {
                String tableId = file.getName().replaceFirst("\\.yml$", "");
                FileConfiguration config = YamlConfiguration.loadConfiguration(file);
                lootTables.put(tableId, config);

                if (config.isConfigurationSection("mmoitems_loot") && !mmoItemsEnabled) {
                    plugin.getLogger().warning("[AthiSAirdrops] Loot table '" + tableId + "' contains MMOItems loot, but MMOItems is not enabled. These items will be skipped.");
                }
                if (config.isConfigurationSection("itemsadder_loot") && !itemsAdderEnabled) {
                    plugin.getLogger().warning("[AthiSAirdrops] Loot table '" + tableId + "' contains ItemsAdder loot, but ItemsAdder is not enabled. These items will be skipped.");
                }
            }
        }

        // Selects a loot table id based on the optional requested name. If requestedName is provided
        // and matches an available table (case-insensitive), that id is returned. Otherwise a random
        // selection is performed using the configured chance values. Returns null if no tables are available.
        public String selectLootTableId(String requestedName) {
            if (requestedName != null && !requestedName.isEmpty()) {
                for (String key : lootTables.keySet()) {
                    if (key.equalsIgnoreCase(requestedName)) return key;
                }
            }

            Map<String, Double> tableChances = new LinkedHashMap<>();
            double totalChance = 0;

            for (Map.Entry<String, ConfigurationSection> entry : lootTables.entrySet()) {
                double chance = entry.getValue().getDouble("chance", 1.0);
                if (chance > 0) {
                    tableChances.put(entry.getKey(), chance);
                    totalChance += chance;
                }
            }

            if (tableChances.isEmpty()) return null;

            double roll = Math.random() * totalChance;
            for (Map.Entry<String, Double> entry : tableChances.entrySet()) {
                roll -= entry.getValue();
                if (roll <= 0) {
                    return entry.getKey();
                }
            }
            return null;
        }

        public ConfigurationSection getById(String id) {
            if (id == null) return null;
            return lootTables.get(id);
        }

        // Get a loot table by its id/name, or null if not found
        public ConfigurationSection getByName(String name) {
            if (name == null) return null;
            // Try exact match first
            ConfigurationSection exact = lootTables.get(name);
            if (exact != null) return exact;
            // Fall back to case-insensitive match
            for (Map.Entry<String, ConfigurationSection> e : lootTables.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
            }
            return null;
        }
    }

    // Added: public API used by AirdropManager to fill a chest according to configured loot tables
    // Overload that accepts an optional loot table name. If tableName is null or not found, falls back to random selection.
    // Returns the chosen loot table id that was used to fill the chest, or null if none chosen/used.
    public String fillChestWithLoot(Chest chest, String tableName) {
        if (chest == null) return null;

        // Determine the final table id to use (requested or randomly selected)
        String chosenId = lootTableSelector.selectLootTableId(tableName);
        if (chosenId == null) {
            LOGGER.info("No loot table selected; chest will remain empty.");
            return null;
        }
        ConfigurationSection lootTable = lootTableSelector.getById(chosenId);
        if (lootTable == null) {
            LOGGER.warning("Chosen loot table id '" + chosenId + "' configuration not found; chest will remain empty.");
            return null;
        }

        int maxItems = plugin.getConfig().getInt("max_items_per_airdrop", 5);
        int remaining = Math.max(0, maxItems);
        boolean anySectionFound = false;
        int totalAdded = 0;

        for (LootProvider provider : lootProviders) {
            if (remaining <= 0) break;
            try {
                ConfigurationSection section = provider.getSection(lootTable);
                if (section != null) {
                    anySectionFound = true;
                    int added = provider.fillChest(chest, section, remaining);
                    if (added > 0) {
                        remaining -= added;
                        totalAdded += added;
                    } else {
                        LOGGER.info("[AirdropLoot] " + provider.getClass().getSimpleName()
                                + " did not add any items (section empty, chance rolls, or errors).");
                    }
                }
            } catch (Exception e) {
                LOGGER.warning("Error while filling chest with provider "
                        + provider.getClass().getSimpleName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        if (!anySectionFound) {
            LOGGER.warning("[AirdropLoot] No loot provider found a valid section in the selected loot table. Check your loot.yml structure.");
        }

        if (totalAdded == 0) {
            LOGGER.info("[AirdropLoot] No items were added to the airdrop chest (all providers skipped or failed).");
        }

        return chosenId;
    }

    // Backwards-compatible overload
    public String fillChestWithLoot(Chest chest) {
        return fillChestWithLoot(chest, null);
    }

}
