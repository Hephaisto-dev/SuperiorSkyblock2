package com.bgsoftware.superiorskyblock.nms.v1_21_4.crops;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.core.ChunkPosition;
import com.bgsoftware.superiorskyblock.core.collections.CollectionsFactory;
import com.bgsoftware.superiorskyblock.core.collections.view.Long2ObjectMapView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.function.Consumer;

public class CropsBlockEntity extends BlockEntity {

    private static final SuperiorSkyblockPlugin plugin = SuperiorSkyblockPlugin.getPlugin();

    private static final Long2ObjectMapView<CropsBlockEntity> tickingChunks = CollectionsFactory.createLong2ObjectHashMap();

    private final WeakReference<Island> island;
    private final WeakReference<LevelChunk> chunk;

    private int currentTick = 0;

    private double cachedCropGrowthMultiplier;

    private CropsBlockEntity(Island island, LevelChunk levelChunk, BlockPos blockPos) {
        super(BlockEntityType.COMMAND_BLOCK, blockPos, levelChunk.level.getBlockState(blockPos));
        this.island = new WeakReference<>(island);
        this.chunk = new WeakReference<>(levelChunk);
        setLevel(levelChunk.level);
        levelChunk.level.addBlockEntityTicker(new CropsTickingBlockEntity(this));
        this.cachedCropGrowthMultiplier = island.getCropGrowthMultiplier() - 1;
    }

    public static void create(Island island, LevelChunk levelChunk) {
        ChunkPos chunkPos = levelChunk.getPos();
        long chunkPair = chunkPos.toLong();
        tickingChunks.computeIfAbsent(chunkPair, p -> {
            BlockPos blockPos = new BlockPos(chunkPos.x << 4, 1, chunkPos.z << 4);
            return new CropsBlockEntity(island, levelChunk, blockPos);
        });
    }

    public static CropsBlockEntity remove(long chunkPos) {
        return tickingChunks.remove(chunkPos);
    }

    public static void forEachChunk(List<ChunkPosition> chunkPositions, Consumer<CropsBlockEntity> cropsBlockEntityConsumer) {
        if (tickingChunks.isEmpty())
            return;

        chunkPositions.forEach(chunkPosition -> {
            long chunkKey = chunkPosition.asPair();
            CropsBlockEntity cropsBlockEntity = tickingChunks.get(chunkKey);
            if (cropsBlockEntity != null)
                cropsBlockEntityConsumer.accept(cropsBlockEntity);
        });
    }

    @Override
    public boolean isValidBlockState(BlockState state) {
        return true;
    }

    public void remove() {
        this.remove = true;
    }

    public void tick() {
        if (++currentTick <= plugin.getSettings().getCropsInterval())
            return;

        LevelChunk levelChunk = this.chunk.get();
        Island island = this.island.get();
        ServerLevel serverLevel = (ServerLevel) getLevel();

        if (levelChunk == null || island == null || serverLevel == null) {
            remove();
            return;
        }

        currentTick = 0;

        int worldRandomTick = serverLevel.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
        int chunkRandomTickSpeed = (int) (worldRandomTick * this.cachedCropGrowthMultiplier * plugin.getSettings().getCropsInterval());
        if (chunkRandomTickSpeed > 0)
            CropsTickingMethod.tick(levelChunk, chunkRandomTickSpeed);
    }

    public void setCropGrowthMultiplier(double cropGrowthMultiplier) {
        this.cachedCropGrowthMultiplier = cropGrowthMultiplier;
    }

}
