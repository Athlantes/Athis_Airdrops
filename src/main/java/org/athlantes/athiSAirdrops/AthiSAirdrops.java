package org.athlantes.athiSAirdrops;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public final class AthiSAirdrops extends JavaPlugin {

    private static AthiSAirdrops instance;
    private AirdropManager manager;
    private boolean nexoEnabled = false;
    private boolean itemsAdderEnabled = false;
    private boolean mmoItemsEnabled = false;

    @Override
    public void onEnable() {
        instance = this;

        // Save default config
        saveDefaultConfig();

        // Ensure loot/ exists and migrate from legacy loot.yml (on disk or embedded)
        ensureLootFolderAndMigrate();

        itemsAdderEnabled = getServer().getPluginManager().isPluginEnabled("ItemsAdder");
        if (itemsAdderEnabled) {
            getLogger().info("ItemsAdder detected! Enabling ItemsAdder support.");
        }

        // Detect Nexo before initializing the manager
        nexoEnabled = getServer().getPluginManager().isPluginEnabled("Nexo");
        if (nexoEnabled) {
            getLogger().info("Nexo detected! Enabling Nexo integration.");
        }

        mmoItemsEnabled = getServer().getPluginManager().isPluginEnabled("MMOItems");
        if (mmoItemsEnabled) {
            getLogger().info("MMOItems detected! Enabling MMOItems integration.");
        }

        // Initialize manager AFTER detecting Nexo
        manager = new AirdropManager(this);

        // Clean up leftover armor-stands/holograms from previous crashes
        manager.cleanupLeftovers();

        // Register listeners
        getServer().getPluginManager().registerEvents(new AirdropListeners(this, manager), this);

        // Register commands
        getCommand("airdrop").setExecutor(new AirdropCommand(this, manager));
        getCommand("airdrop").setTabCompleter(new AirdropTabCompleter());

        // Auto-start if enabled
        if (getConfig().getBoolean("autostart", false)) {
            manager.start();
        }

        getLogger().info("AthiSAirdrops has been enabled!");
    }

    private void ensureLootFolderAndMigrate() {
        File data = getDataFolder();
        if (!data.exists()) data.mkdirs();
        File lootDir = new File(data, "loot");
        if (!lootDir.exists()) lootDir.mkdirs();

        File lootYml = new File(data, "loot.yml");
        boolean lootDirEmpty = lootDir.listFiles() == null || lootDir.listFiles().length == 0;

        try {
            if (lootYml.exists() && lootDirEmpty) {
                // Migrate from on-disk loot.yml -> loot/*.yml
                FileConfiguration lootConfig = YamlConfiguration.loadConfiguration(lootYml);
                migrateConfigToFolder(lootConfig, lootDir);
                if (!lootYml.delete()) {
                    getLogger().warning("Could not delete legacy loot.yml after migration. You may remove it manually.");
                } else {
                    getLogger().info("Migrated legacy loot.yml to loot/ and removed the old file.");
                }
                return;
            }

            // If no on-disk loot.yml and lootDir is empty, try to use embedded default loot.yml
            if (lootDirEmpty) {
                InputStream in = getResource("loot.yml");
                if (in != null) {
                    YamlConfiguration embedded = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                    migrateConfigToFolder(embedded, lootDir);
                    getLogger().info("Initialized loot/ from embedded default loot.yml.");
                } else {
                    getLogger().info("loot/ folder created. No default loot.yml found in resources; folder left empty.");
                }
            }
        } catch (Exception e) {
            getLogger().warning("Failed to set up loot folder: " + e.getMessage());
        }
    }

    private void migrateConfigToFolder(FileConfiguration source, File lootDir) {
        for (String tableName : source.getKeys(false)) {
            ConfigurationSection table = source.getConfigurationSection(tableName);
            if (table == null) continue;
            try {
                File out = new File(lootDir, tableName + ".yml");
                YamlConfiguration cfg = new YamlConfiguration();
                for (String k : table.getKeys(false)) {
                    cfg.set(k, table.get(k));
                }
                cfg.save(out);
            } catch (Exception ex) {
                getLogger().warning("Failed to write loot table '" + tableName + "': " + ex.getMessage());
            }
        }
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.stop();
        }
        getLogger().info("AthiSAirdrops has been disabled!");
    }

    public static AthiSAirdrops getInstance() {
        return instance;
    }

    public AirdropManager getManager() {
        return manager;
    }

    public boolean isItemsAdderEnabled() {
        return itemsAdderEnabled;
    }

    public boolean isNexoEnabled() {
        return nexoEnabled;
    }

    public boolean isMMOItemsEnabled() {
        return mmoItemsEnabled;
    }
}
