package com.bgsoftware.superiorskyblock.nms.v1_21_4;

import com.bgsoftware.common.annotations.Nullable;
import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.key.Key;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.ObjectsPools;
import com.bgsoftware.superiorskyblock.core.Text;
import com.bgsoftware.superiorskyblock.core.formatting.Formatters;
import com.bgsoftware.superiorskyblock.core.formatting.impl.ChatFormatter;
import com.bgsoftware.superiorskyblock.core.io.ClassProcessor;
import com.bgsoftware.superiorskyblock.nms.NMSAlgorithms;
import com.bgsoftware.superiorskyblock.nms.v1_21_4.menu.MenuBrewingStandBlockEntity;
import com.bgsoftware.superiorskyblock.nms.v1_21_4.menu.MenuDispenserBlockEntity;
import com.bgsoftware.superiorskyblock.nms.v1_21_4.menu.MenuFurnaceBlockEntity;
import com.bgsoftware.superiorskyblock.nms.v1_21_4.menu.MenuHopperBlockEntity;
import com.bgsoftware.superiorskyblock.nms.v1_21_4.world.BlockEntityCache;
import com.bgsoftware.superiorskyblock.nms.v1_21_4.world.KeyBlocksCache;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import io.papermc.paper.chat.ChatRenderer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.TextReplacementConfig;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.defaults.BukkitCommand;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftFallingBlock;
import org.bukkit.craftbukkit.entity.CraftMinecart;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.util.CraftChatMessage;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.Optional;
import java.util.function.BiFunction;

public class NMSAlgorithmsImpl implements NMSAlgorithms {

    private static final SuperiorSkyblockPlugin plugin = SuperiorSkyblockPlugin.getPlugin();

    private static final Multimap<Attribute, AttributeModifier> EMPTY_ATTRIBUTES_MAP =
            MultimapBuilder.hashKeys().hashSetValues().build();

    private static final EnumMap<InventoryType, MenuCreator> MENUS_HOLDER_CREATORS = new EnumMap<>(InventoryType.class);

    static {
        MENUS_HOLDER_CREATORS.put(InventoryType.DISPENSER, MenuDispenserBlockEntity::new);
        MENUS_HOLDER_CREATORS.put(InventoryType.DROPPER, MenuDispenserBlockEntity::new);
        MENUS_HOLDER_CREATORS.put(InventoryType.FURNACE, MenuFurnaceBlockEntity::new);
        MENUS_HOLDER_CREATORS.put(InventoryType.BREWING, MenuBrewingStandBlockEntity::new);
        MENUS_HOLDER_CREATORS.put(InventoryType.HOPPER, MenuHopperBlockEntity::new);
        MENUS_HOLDER_CREATORS.put(InventoryType.BLAST_FURNACE, MenuFurnaceBlockEntity::new);
        MENUS_HOLDER_CREATORS.put(InventoryType.SMOKER, MenuFurnaceBlockEntity::new);
    }

    private final ClassProcessor CLASS_PROCESSOR = new ClassProcessor() {
        @Override
        public byte[] processClass(byte[] classBytes, String path) {
            return Bukkit.getUnsafe().processClass(plugin.getDescription(), path, classBytes);
        }
    };

    @Override
    public void registerCommand(BukkitCommand command) {
        ((CraftServer) plugin.getServer()).getCommandMap().register("superiorskyblock2", command);
    }

    @Override
    public String parseSignLine(String original) {
        return Component.Serializer.toJson(CraftChatMessage.fromString(original)[0], MinecraftServer.getServer().registryAccess());
    }

    @Override
    public int getCombinedId(Location location) {
        org.bukkit.World bukkitWorld = location.getWorld();

        if (bukkitWorld == null)
            return 0;

        ServerLevel serverLevel = ((CraftWorld) bukkitWorld).getHandle();
        BlockState blockState;
        try (ObjectsPools.Wrapper<BlockPos.MutableBlockPos> wrapper = NMSUtils.BLOCK_POS_POOL.obtain()) {
            wrapper.getHandle().set(location.getBlockX(), location.getBlockY(), location.getBlockZ());
            blockState = serverLevel.getBlockState(wrapper.getHandle());
        }
        return Block.getId(blockState);
    }

    @Override
    public int getCombinedId(Material material, byte data) {
        BlockState blockState;

        if (data == 0) {
            Block block = CraftMagicNumbers.getBlock(material);
            if (block == null)
                return -1;
            blockState = block.defaultBlockState();
        } else {
            blockState = CraftMagicNumbers.getBlock(material, data);
        }

        return blockState == null ? -1 : Block.getId(blockState);
    }

    @Override
    public Optional<String> getTileEntityIdFromCombinedId(int combinedId) {
        BlockState blockState = Block.stateById(combinedId);

        if (!blockState.hasBlockEntity())
            return Optional.empty();

        String id = BlockEntityCache.getBlockEntityId(blockState);

        return Text.isBlank(id) ? Optional.empty() : Optional.of(id);
    }

    @Override
    public int compareMaterials(Material o1, Material o2) {
        int firstMaterial = o1.isBlock() ? Block.getId(CraftMagicNumbers.getBlock(o1).defaultBlockState()) : o1.ordinal();
        int secondMaterial = o2.isBlock() ? Block.getId(CraftMagicNumbers.getBlock(o2).defaultBlockState()) : o2.ordinal();
        return Integer.compare(firstMaterial, secondMaterial);
    }

    @Override
    public short getBlockDataValue(org.bukkit.block.BlockState blockState) {
        BlockData blockData = blockState.getBlockData();
        return (short) (blockData instanceof Ageable ? ((Ageable) blockData).getAge() : 0);
    }

    @Override
    public short getBlockDataValue(org.bukkit.block.Block block) {
        BlockData blockData = block.getBlockData();
        return (short) (blockData instanceof Ageable ? ((Ageable) blockData).getAge() : 0);
    }

    @Override
    public short getMaxBlockDataValue(Material material) {
        if (!material.isBlock())
            return 0;

        BlockData blockData = Bukkit.createBlockData(material);
        return (short) (blockData instanceof Ageable ? ((Ageable) blockData).getMaximumAge() : 0);
    }

    @Override
    public Key getBlockKey(int combinedId) {
        Block block = Block.stateById(combinedId).getBlock();
        return KeyBlocksCache.getBlockKey(block);
    }

    @Override
    public Key getMinecartBlock(org.bukkit.entity.Minecart bukkitMinecart) {
        AbstractMinecart minecart = ((CraftMinecart) bukkitMinecart).getHandle();
        Block block = minecart.getDisplayBlockState().getBlock();
        return KeyBlocksCache.getBlockKey(block);
    }

    @Override
    public Key getFallingBlockType(FallingBlock bukkitFallingBlock) {
        FallingBlockEntity fallingBlock = ((CraftFallingBlock) bukkitFallingBlock).getHandle();
        Block block = fallingBlock.getBlockState().getBlock();
        return KeyBlocksCache.getBlockKey(block);
    }

    @Override
    public void setCustomModel(ItemMeta itemMeta, int customModel) {
        itemMeta.setCustomModelData(customModel);
    }

    @Override
    public void addPotion(PotionMeta potionMeta, PotionEffect potionEffect) {
        if (!potionMeta.hasCustomEffects())
            potionMeta.setColor(potionEffect.getType().getColor());
        potionMeta.addCustomEffect(potionEffect, true);
    }

    @Override
    public String getMinecraftKey(ItemStack itemStack) {
        return BuiltInRegistries.ITEM.getKey(CraftItemStack.asNMSCopy(itemStack).getItem()).toString();
    }

    @Override
    public void makeItemGlow(ItemMeta itemMeta) {
        itemMeta.setEnchantmentGlintOverride(true);
    }

    @Nullable
    @Override
    public Object createMenuInventoryHolder(InventoryType inventoryType, InventoryHolder defaultHolder, String title) {
        MenuCreator menuCreator = MENUS_HOLDER_CREATORS.get(inventoryType);
        return menuCreator == null ? null : menuCreator.apply(defaultHolder, title);
    }

    @Override
    public int getMaxWorldSize() {
        return Bukkit.getMaxWorldSize();
    }

    @Override
    public double getCurrentTps() {
        try {
            return MinecraftServer.getServer().tps1.getAverage();
        } catch (Throwable error) {
            //noinspection removal
            return MinecraftServer.getServer().recentTps[0];
        }
    }

    @Override
    public int getDataVersion() {
        return SharedConstants.getCurrentVersion().getDataVersion().getVersion();
    }

    @Override
    public ClassProcessor getClassProcessor() {
        return CLASS_PROCESSOR;
    }

    @Override
    public void handlePaperChatRenderer(Object event) {
        if (!(event instanceof io.papermc.paper.event.player.AsyncChatEvent asyncChatEvent))
            return;

        ChatRenderer originalRenderer = asyncChatEvent.renderer();
        asyncChatEvent.renderer(new ChatRendererWrapper(originalRenderer).renderer);
    }

    @Override
    public void hideAttributes(ItemMeta itemMeta) {
        itemMeta.setAttributeModifiers(EMPTY_ATTRIBUTES_MAP);
    }

    private interface MenuCreator extends BiFunction<InventoryHolder, String, Container> {
    }

    private static class ChatRendererWrapper {

        private static final String MESSAGE_PLACEHOLDER = "{message}";
        private static final net.kyori.adventure.text.Component MESSAGE_PLACEHOLDER_COMPONENT =
                net.kyori.adventure.text.Component.text(MESSAGE_PLACEHOLDER);

        private final ChatRenderer renderer = new ChatRenderer() {
            @Override
            public net.kyori.adventure.text.@NotNull Component render(@NotNull Player source,
                                                                      net.kyori.adventure.text.@NotNull Component sourceDisplayName,
                                                                      net.kyori.adventure.text.@NotNull Component message,
                                                                      @NotNull Audience viewer) {
                String originalFormat = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .legacyAmpersand().serialize(originalRenderer.render(source, sourceDisplayName, MESSAGE_PLACEHOLDER_COMPONENT, viewer));

                SuperiorPlayer superiorPlayer = plugin.getPlayers().getSuperiorPlayer(source);
                Island island = superiorPlayer.getIsland();

                return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(
                                Formatters.CHAT_FORMATTER.format(new ChatFormatter.ChatFormatArgs(originalFormat, superiorPlayer, island)))
                        .replaceText(TextReplacementConfig.builder()
                                .matchLiteral(MESSAGE_PLACEHOLDER)
                                .replacement(message)
                                .build());
            }
        };

        private final ChatRenderer originalRenderer;

        public ChatRendererWrapper(ChatRenderer originalRenderer) {
            this.originalRenderer = originalRenderer;
        }

    }

}
