package com.bgsoftware.superiorskyblock.nms.v1_21_5.world;

import com.bgsoftware.superiorskyblock.api.key.Key;
import com.bgsoftware.superiorskyblock.core.key.Keys;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import org.bukkit.Material;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;

import java.util.IdentityHashMap;
import java.util.Map;

public class KeyBlocksCache {

    private static final Map<Block, Key> BLOCK_TO_KEY = new IdentityHashMap<>();

    private KeyBlocksCache() {

    }

    public static Key getBlockKey(Block block) {
        return BLOCK_TO_KEY.computeIfAbsent(block, unused -> {
            Material blockType = CraftMagicNumbers.getMaterial(block);
            return blockType.isItem() ? Keys.of(blockType, (short) 0) : Keys.of(blockType);
        });
    }

    public static void cacheAllBlocks() {
        BuiltInRegistries.BLOCK.forEach(KeyBlocksCache::getBlockKey);
    }

}
