package com.bgsoftware.superiorskyblock.world;

import com.bgsoftware.common.annotations.Nullable;
import com.bgsoftware.common.reflection.ReflectMethod;
import com.bgsoftware.superiorskyblock.core.EnumHelper;
import com.bgsoftware.superiorskyblock.core.PlayerHand;
import com.bgsoftware.superiorskyblock.core.ServerVersion;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.SpawnEggMeta;

import java.util.Map;

public class BukkitItems {

    private static final ReflectMethod<EquipmentSlot> GET_HAND_BLOCK_PLACE = new ReflectMethod<>(BlockPlaceEvent.class, "getHand");
    private static final ReflectMethod<EquipmentSlot> GET_HAND_PLAYER_INTERACT = new ReflectMethod<>(PlayerInteractEvent.class, "getHand");
    private static final ReflectMethod<EquipmentSlot> GET_HAND_PLAYER_INTERACT_ENTITY = new ReflectMethod<>(PlayerInteractEntityEvent.class, "getHand");
    private static final ReflectMethod<ItemStack> GET_ITEM_IN_OFF_HAND = new ReflectMethod<>(PlayerInventory.class, "getItemInOffHand");
    private static final ReflectMethod<ItemStack> SET_ITEM_IN_OFF_HAND = new ReflectMethod<>(PlayerInventory.class, "setItemInOffHand", ItemStack.class);

    private static final Material END_CRYSTAL_ITEM_TYPE = EnumHelper.getEnum(Material.class, "END_CRYSTAL");

    private BukkitItems() {

    }

    public static void removeHandItem(Player onlinePlayer, PlayerHand usedHand, int amount) {
        PlayerInventory playerInventory = onlinePlayer.getInventory();

        if (usedHand == PlayerHand.MAIN_HAND) {
            ItemStack mainHand = playerInventory.getItemInHand();
            if (mainHand != null) {
                mainHand.setAmount(mainHand.getAmount() - amount);
                playerInventory.setItemInHand(mainHand);
            }
        } else if (usedHand == PlayerHand.OFF_HAND) {
            ItemStack offHand = GET_ITEM_IN_OFF_HAND.invoke(playerInventory);
            if (offHand != null) {
                offHand.setAmount(offHand.getAmount() - amount);
                SET_ITEM_IN_OFF_HAND.invoke(playerInventory, offHand);
            }
        }
    }

    @Nullable
    public static PlayerHand getHand(Event event) {
        ReflectMethod<EquipmentSlot> reflectMethod;

        if (event instanceof BlockPlaceEvent) {
            reflectMethod = GET_HAND_BLOCK_PLACE;
        } else if (event instanceof PlayerInteractEvent) {
            reflectMethod = GET_HAND_PLAYER_INTERACT;
        } else if (event instanceof PlayerInteractEntityEvent) {
            reflectMethod = GET_HAND_PLAYER_INTERACT_ENTITY;
        } else {
            throw new IllegalArgumentException("Cannot get hand of event: " + event.getClass());
        }

        EquipmentSlot equipmentSlot = reflectMethod.isValid() ? reflectMethod.invoke(event) : EquipmentSlot.HAND;

        return equipmentSlot == null ? null : PlayerHand.of(equipmentSlot);
    }

    @Nullable
    public static ItemStack getHandItem(Player onlinePlayer, @Nullable PlayerHand usedHand) {
        if (usedHand == null) {
            return null;
        }

        ItemStack handItem;

        if (usedHand == PlayerHand.OFF_HAND) {
            handItem = GET_ITEM_IN_OFF_HAND.invoke(onlinePlayer.getInventory());
        } else {
            handItem = onlinePlayer.getItemInHand();
        }

        return handItem == null || handItem.getType() == Material.AIR ? null : handItem;
    }

    public static void setHandItem(Player player, PlayerHand playerHand, @Nullable ItemStack itemStack) {
        if (playerHand == PlayerHand.MAIN_HAND) {
            player.getInventory().setItemInHand(itemStack == null ? new ItemStack(Material.AIR) : itemStack);
        } else if (playerHand == PlayerHand.OFF_HAND) {
            SET_ITEM_IN_OFF_HAND.invoke(player.getInventory(), itemStack == null ? new ItemStack(Material.AIR) : itemStack);
        }
    }

    @SuppressWarnings("deprecation")
    public static EntityType getEntityType(ItemStack itemStack) {
        if (!isValidAndSpawnEgg(itemStack)) {
            Material itemType = itemStack.getType();

            if (itemType == Material.ARMOR_STAND) {
                return EntityType.ARMOR_STAND;
            } else if (itemType == END_CRYSTAL_ITEM_TYPE) {
                return EntityType.ENDER_CRYSTAL;
            }

            return EntityType.UNKNOWN;
        }

        if (ServerVersion.isLegacy()) {
            try {
                SpawnEggMeta spawnEggMeta = (SpawnEggMeta) itemStack.getItemMeta();
                return spawnEggMeta.getSpawnedType() == null ? EntityType.PIG : spawnEggMeta.getSpawnedType();
            } catch (NoClassDefFoundError error) {
                return EntityType.fromId(itemStack.getDurability());
            }
        } else {
            return EntityType.fromName(itemStack.getType().name().replace("_SPAWN_EGG", ""));
        }
    }

    public static void addItem(ItemStack itemStack, PlayerInventory playerInventory, Location toDrop) {
        Map<Integer, ItemStack> additionalItems = playerInventory.addItem(itemStack);
        for (ItemStack additionalItem : additionalItems.values())
            toDrop.getWorld().dropItemNaturally(toDrop, additionalItem);
    }

    public static boolean isValidAndSpawnEgg(@Nullable ItemStack itemStack) {
        return itemStack != null && isValidAndSpawnEgg(itemStack.getType());
    }

    public static boolean isValidAndSpawnEgg(Material itemType) {
        return !itemType.isBlock() && itemType.name().contains(ServerVersion.isLegacy() ? "MONSTER_EGG" : "SPAWN_EGG");
    }

    public static boolean isInteractableItem(@Nullable ItemStack itemStack) {
        if (itemStack == null)
            return false;

        Material itemType = itemStack.getType();

        return itemType == Material.ARMOR_STAND || isValidAndSpawnEgg(itemType);
    }

}
