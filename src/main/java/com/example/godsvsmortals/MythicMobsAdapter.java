package com.example.godsvsmortals;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.logging.Logger;

/**
 * Adapter for MythicMobs API calls. Only instantiated when MythicMobs is present.
 *
 * All MythicMobs API calls are isolated here so the rest of the plugin compiles
 * and runs without MythicMobs on the classpath.
 *
 * Requirements: 14.7
 */
public class MythicMobsAdapter {

    private final Logger logger;

    public MythicMobsAdapter(Logger logger) {
        this.logger = logger;
        logger.info("MythicMobsAdapter: MythicMobs detected – Avatar Mode will use custom weapon.");
    }

    /**
     * Attempts to give the player a MythicMobs item by internal name.
     * Returns true if the item was successfully given.
     *
     * @param player   the player to give the item to
     * @param itemName the MythicMobs item name (e.g. "AvatarSword")
     * @return true if the item was given successfully
     */
    public boolean giveItem(Player player, String itemName) {
        try {
            // Use reflection to avoid hard compile-time dependency on MythicMobs API
            Class<?> mythicItemsClass = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object mythicBukkit = mythicItemsClass.getMethod("inst").invoke(null);
            Object itemManager = mythicBukkit.getClass().getMethod("getItemManager").invoke(mythicBukkit);
            Object optItem = itemManager.getClass().getMethod("getItem", String.class).invoke(itemManager, itemName);

            // Check if item exists (Optional)
            boolean present = (boolean) optItem.getClass().getMethod("isPresent").invoke(optItem);
            if (!present) {
                logger.warning("MythicMobsAdapter: item '" + itemName + "' not found in MythicMobs.");
                return false;
            }

            Object mythicItem = optItem.getClass().getMethod("get").invoke(optItem);
            ItemStack stack = (ItemStack) mythicItem.getClass().getMethod("generateItemStack", int.class)
                    .invoke(mythicItem, 1);
            player.getInventory().addItem(stack);
            return true;
        } catch (Exception e) {
            logger.warning("MythicMobsAdapter: failed to give item '" + itemName + "': " + e.getMessage());
            return false;
        }
    }
}
