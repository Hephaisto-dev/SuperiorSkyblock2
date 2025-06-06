package com.bgsoftware.superiorskyblock.island;

import com.bgsoftware.common.annotations.Nullable;
import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.data.DatabaseBridge;
import com.bgsoftware.superiorskyblock.api.data.DatabaseBridgeMode;
import com.bgsoftware.superiorskyblock.api.handlers.GridManager;
import com.bgsoftware.superiorskyblock.api.hooks.LazyWorldsProvider;
import com.bgsoftware.superiorskyblock.api.hooks.WorldsProvider;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandPreview;
import com.bgsoftware.superiorskyblock.api.island.SortingType;
import com.bgsoftware.superiorskyblock.api.island.container.IslandsContainer;
import com.bgsoftware.superiorskyblock.api.menu.view.MenuView;
import com.bgsoftware.superiorskyblock.api.schematic.Schematic;
import com.bgsoftware.superiorskyblock.api.service.dragon.DragonBattleService;
import com.bgsoftware.superiorskyblock.api.world.Dimension;
import com.bgsoftware.superiorskyblock.api.world.WorldInfo;
import com.bgsoftware.superiorskyblock.api.world.algorithm.IslandCreationAlgorithm;
import com.bgsoftware.superiorskyblock.api.wrappers.BlockOffset;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.ChunkPosition;
import com.bgsoftware.superiorskyblock.core.LazyReference;
import com.bgsoftware.superiorskyblock.core.LazyWorldLocation;
import com.bgsoftware.superiorskyblock.core.Manager;
import com.bgsoftware.superiorskyblock.core.ObjectsPools;
import com.bgsoftware.superiorskyblock.core.SBlockPosition;
import com.bgsoftware.superiorskyblock.core.SequentialListBuilder;
import com.bgsoftware.superiorskyblock.core.collections.EnumerateSet;
import com.bgsoftware.superiorskyblock.core.database.DatabaseResult;
import com.bgsoftware.superiorskyblock.core.database.bridge.GridDatabaseBridge;
import com.bgsoftware.superiorskyblock.core.database.bridge.IslandsDatabaseBridge;
import com.bgsoftware.superiorskyblock.core.errors.ManagerLoadException;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEventsFactory;
import com.bgsoftware.superiorskyblock.core.formatting.Formatters;
import com.bgsoftware.superiorskyblock.core.logging.Debug;
import com.bgsoftware.superiorskyblock.core.logging.Log;
import com.bgsoftware.superiorskyblock.core.messages.Message;
import com.bgsoftware.superiorskyblock.core.serialization.Serializers;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.core.threads.Synchronized;
import com.bgsoftware.superiorskyblock.island.algorithm.DefaultIslandCreationAlgorithm;
import com.bgsoftware.superiorskyblock.island.builder.IslandBuilderImpl;
import com.bgsoftware.superiorskyblock.island.preview.IslandPreviews;
import com.bgsoftware.superiorskyblock.island.preview.SIslandPreview;
import com.bgsoftware.superiorskyblock.island.purge.IslandsPurger;
import com.bgsoftware.superiorskyblock.player.chat.PlayerChat;
import com.bgsoftware.superiorskyblock.world.Dimensions;
import com.bgsoftware.superiorskyblock.world.WorldBlocks;
import com.bgsoftware.superiorskyblock.world.schematic.BaseSchematic;
import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public class GridManagerImpl extends Manager implements GridManager {

    private static final Function<Island, UUID> ISLAND_OWNERS_MAPPER = island -> island.getOwner().getUniqueId();

    private final Set<UUID> pendingCreationTasks = Sets.newHashSet();
    private final Set<UUID> customWorlds = Sets.newHashSet();

    private final LazyReference<DragonBattleService> dragonBattleService = new LazyReference<DragonBattleService>() {
        @Override
        protected DragonBattleService create() {
            return plugin.getServices().getService(DragonBattleService.class);
        }
    };

    private final IslandsPurger islandsPurger;
    private final IslandPreviews islandPreviews;
    private IslandsContainer islandsContainer;
    private DatabaseBridge databaseBridge;
    private IslandCreationAlgorithm islandCreationAlgorithm;

    private Island spawnIsland;
    private SBlockPosition lastIsland;
    @Nullable
    private UUID serverUUID;

    private BigDecimal totalWorth = BigDecimal.ZERO;
    private long lastTimeWorthUpdate = 0;
    private BigDecimal totalLevel = BigDecimal.ZERO;
    private long lastTimeLevelUpdate = 0;

    private boolean pluginDisable = false;

    private boolean forceSort = false;

    private final List<SortingType> pendingSortingTypes = new LinkedList<>();
    private final AtomicInteger activeCalcTasks = new AtomicInteger(0);
    private final LazyReference<Synchronized<EnumerateSet<SortingType>>> activeSortingTasks = new LazyReference<Synchronized<EnumerateSet<SortingType>>>() {
        @Override
        protected Synchronized<EnumerateSet<SortingType>> create() {
            return Synchronized.of(new EnumerateSet<>(SortingType.values()));
        }
    };

    public GridManagerImpl(SuperiorSkyblockPlugin plugin, IslandsPurger islandsPurger, IslandPreviews islandPreviews) {
        super(plugin);
        this.islandsPurger = islandsPurger;
        this.islandPreviews = islandPreviews;
    }

    @Override
    public void loadData() {
        if (this.islandsContainer == null)
            throw new RuntimeException("GridManager was not initialized correctly. Contact Ome_R regarding this!");

        initializeDatabaseBridge();
        if (this.islandCreationAlgorithm == null)
            this.islandCreationAlgorithm = DefaultIslandCreationAlgorithm.getInstance();

        loadServerUuid();

        this.lastIsland = new SBlockPosition(plugin.getSettings().getWorlds().getDefaultWorldName(), 0, 100, 0);
        BukkitExecutor.sync(this::updateSpawn);
    }

    public void updateSpawn() {
        try {
            this.spawnIsland = new SpawnIsland();
        } catch (ManagerLoadException error) {
            ManagerLoadException.handle(error);
        }
    }

    public void syncUpgrades() {
        getIslands().forEach(Island::updateUpgrades);
    }

    public UUID getServerUUID() {
        return serverUUID;
    }

    @Override
    public void createIsland(SuperiorPlayer superiorPlayer, String schemName, BigDecimal bonus, Biome biome, String islandName) {
        Preconditions.checkNotNull(superiorPlayer, "superiorPlayer parameter cannot be null.");
        Preconditions.checkNotNull(schemName, "schemName parameter cannot be null.");
        Preconditions.checkNotNull(bonus, "bonus parameter cannot be null.");
        Preconditions.checkNotNull(biome, "biome parameter cannot be null.");
        Preconditions.checkNotNull(islandName, "islandName parameter cannot be null.");
        createIsland(superiorPlayer, schemName, bonus, biome, islandName, false);
    }

    @Override
    public void createIsland(SuperiorPlayer superiorPlayer, String schemName, BigDecimal bonus, Biome biome, String islandName, boolean offset) {
        Preconditions.checkNotNull(superiorPlayer, "superiorPlayer parameter cannot be null.");
        Preconditions.checkNotNull(schemName, "schemName parameter cannot be null.");
        Preconditions.checkNotNull(bonus, "bonus parameter cannot be null.");
        Preconditions.checkNotNull(biome, "biome parameter cannot be null.");
        Preconditions.checkNotNull(islandName, "islandName parameter cannot be null.");
        createIsland(superiorPlayer, schemName, bonus, BigDecimal.ZERO, biome, islandName, false);
    }

    @Override
    public void createIsland(SuperiorPlayer superiorPlayer, String schemName, BigDecimal bonusWorth,
                             BigDecimal bonusLevel, Biome biome, String islandName, boolean offset) {
        Preconditions.checkNotNull(superiorPlayer, "superiorPlayer parameter cannot be null.");
        Preconditions.checkNotNull(schemName, "schemName parameter cannot be null.");
        Preconditions.checkNotNull(bonusWorth, "bonusWorth parameter cannot be null.");
        Preconditions.checkNotNull(bonusLevel, "bonusLevel parameter cannot be null.");
        Preconditions.checkNotNull(biome, "biome parameter cannot be null.");
        Preconditions.checkNotNull(islandName, "islandName parameter cannot be null.");
        createIsland(superiorPlayer, schemName, bonusWorth, bonusLevel, biome, islandName, offset, null);
    }

    @Override
    public void createIsland(SuperiorPlayer superiorPlayer, String schemName, BigDecimal bonusWorth,
                             BigDecimal bonusLevel, Biome biome, String islandName, boolean offset,
                             @Nullable BlockOffset spawnOffset) {
        Preconditions.checkNotNull(superiorPlayer, "superiorPlayer parameter cannot be null.");
        Preconditions.checkNotNull(schemName, "schemName parameter cannot be null.");
        Preconditions.checkNotNull(bonusWorth, "bonusWorth parameter cannot be null.");
        Preconditions.checkNotNull(bonusLevel, "bonusLevel parameter cannot be null.");
        Preconditions.checkNotNull(biome, "biome parameter cannot be null.");
        Preconditions.checkNotNull(islandName, "islandName parameter cannot be null.");
        Island.Builder builder = Island.newBuilder()
                .setOwner(superiorPlayer)
                .setSchematicName(schemName)
                .setName(islandName);

        if (!offset) {
            builder.setBonusWorth(bonusWorth)
                    .setBonusLevel(bonusLevel);
        }

        createIsland(builder, biome, offset, spawnOffset);
    }

    @Override
    public void createIsland(Island.Builder builderParam, Biome biome, boolean offset) {
        Preconditions.checkNotNull(builderParam, "builder parameter cannot be null.");
        Preconditions.checkNotNull(biome, "biome parameter cannot be null.");
        Preconditions.checkArgument(builderParam instanceof IslandBuilderImpl, "Cannot create islands out of a custom builder.");
        createIsland(builderParam, biome, offset, null);
    }

    @Override
    public void createIsland(Island.Builder builderParam, Biome biome, boolean offset, @Nullable BlockOffset spawnOffset) {
        Preconditions.checkNotNull(builderParam, "builder parameter cannot be null.");
        Preconditions.checkNotNull(biome, "biome parameter cannot be null.");
        Preconditions.checkArgument(builderParam instanceof IslandBuilderImpl, "Cannot create islands out of a custom builder.");

        IslandBuilderImpl builder = (IslandBuilderImpl) builderParam;

        Preconditions.checkArgument(builder.owner != null, "Cannot create an island with an invalid owner.");

        Schematic schematic = builder.islandType == null ? null : plugin.getSchematics().getSchematic(builder.islandType);

        Preconditions.checkArgument(schematic != null, "Cannot create an island with an invalid schematic.");

        try {
            if (!Bukkit.isPrimaryThread()) {
                BukkitExecutor.sync(() -> createIslandInternalAsync(builder, biome, offset, schematic, spawnOffset));
            } else {
                createIslandInternalAsync(builder, biome, offset, schematic, spawnOffset);
            }
        } catch (Throwable error) {
            Log.entering("ENTER", builder.owner.getName(), builder.islandType, biome, offset);
            Log.error(error, "An unexpected error occurred while creating an island:");
            builder.owner.setIsland(null);
            Message.CREATE_ISLAND_FAILURE.send(builder.owner);
        }
    }

    private void createIslandInternalAsync(IslandBuilderImpl builder, Biome biome, boolean offset,
                                           Schematic schematic, @Nullable BlockOffset spawnOffset) {
        assert builder.owner != null;

        Log.debug(Debug.CREATE_ISLAND, builder.owner.getName(), builder.bonusWorth, builder.bonusLevel,
                builder.islandName, offset, biome, schematic.getName());

        // Removing any active previews for the player.
        boolean updateGamemode = this.islandPreviews.endIslandPreview(builder.owner) != null;

        if (!PluginEventsFactory.callPreIslandCreateEvent(builder.owner, builder.islandName))
            return;

        builder.setUniqueId(generateIslandUUID());

        long startTime = System.currentTimeMillis();

        pendingCreationTasks.add(builder.owner.getUniqueId());

        this.islandCreationAlgorithm.createIsland(builder, this.lastIsland).whenComplete((islandCreationResult, error) -> {

            pendingCreationTasks.remove(builder.owner.getUniqueId());

            if (error == null && islandCreationResult != null) {
                Log.debugResult(Debug.CREATE_ISLAND, "Creation Callback", "Successfully created island");

                try {
                    createIslandInternalOnSuccessCallback(builder, biome, offset, spawnOffset,
                            schematic, updateGamemode, startTime, islandCreationResult);
                    return;
                } catch (Throwable runtimeError) {
                    error = runtimeError;
                }
            }

            Log.debugResult(Debug.CREATE_ISLAND, "Creation Callback", "Failed to create island");

            Log.entering(builder.owner.getName(), builder.bonusWorth, builder.bonusLevel, builder.islandName,
                    offset, biome, schematic.getName());

            if (error != null)
                Log.error(error, "An unexpected error occurred while creating an island:");

            builder.owner.setIsland(null);

            Message.CREATE_ISLAND_FAILURE.send(builder.owner);
        });
    }

    private void createIslandInternalOnSuccessCallback(IslandBuilderImpl builder, Biome biome, boolean offset,
                                                       @Nullable BlockOffset spawnOffset, Schematic schematic,
                                                       boolean updateGamemode, long startTime,
                                                       IslandCreationAlgorithm.IslandCreationResult islandCreationResult) {
        switch (islandCreationResult.getStatus()) {
            case NAME_OCCUPIED:
                builder.owner.setIsland(null);
                Message.ISLAND_ALREADY_EXIST.send(builder.owner);
                Log.debugResult(Debug.CREATE_ISLAND, "Creation Callback", "Island already exists");
                return;
            case EVENT_CANCELLED:
                builder.owner.setIsland(null);
                Log.debugResult(Debug.CREATE_ISLAND, "Creation Callback", "Island creation event was cancelled");
                return;
            case SUCCESS:
                break;
            default:
                throw new RuntimeException("Cannot handle creation status: " + islandCreationResult.getStatus());
        }

        Island island = islandCreationResult.getIsland();
        Location islandLocation = islandCreationResult.getIslandLocation();
        boolean teleportPlayer = islandCreationResult.shouldTeleportPlayer();

        List<ChunkPosition> affectedChunks = schematic instanceof BaseSchematic ?
                ((BaseSchematic) schematic).getAffectedChunks() : Collections.emptyList();
        Runnable onTeleportCallback = schematic instanceof BaseSchematic ?
                ((BaseSchematic) schematic).onTeleportCallback() : null;

        Log.debugResult(Debug.CREATE_ISLAND, "Creation Callback", "Registering new island");

        this.islandsContainer.addIsland(island);
        setLastIsland(new SBlockPosition(islandLocation));

        try {
            island.getDatabaseBridge().setDatabaseBridgeMode(DatabaseBridgeMode.IDLE);

            island.setBiome(biome, false);
            island.setSchematicGenerate(plugin.getSettings().getWorlds().getDefaultWorldDimension());
            island.setCurrentlyActive(true);

            if (offset) {
                island.setBonusWorth(island.getRawWorth().negate());
                island.setBonusLevel(island.getRawLevel().negate());
            }
        } finally {
            island.getDatabaseBridge().setDatabaseBridgeMode(DatabaseBridgeMode.SAVE_DATA);
        }

        IslandsDatabaseBridge.insertIsland(island, affectedChunks);

        Location homeLocation = schematic.adjustRotation(islandLocation);
        if (spawnOffset != null)
            homeLocation = spawnOffset.applyToLocation(homeLocation);

        island.setIslandHome(plugin.getSettings().getWorlds().getDefaultWorldDimension(), homeLocation);

        BukkitExecutor.sync(() -> builder.owner.runIfOnline(player -> {
            if (updateGamemode)
                player.setGameMode(GameMode.SURVIVAL);

            if (!teleportPlayer) {
                Log.debugResult(Debug.CREATE_ISLAND, "Creation Callback", "Do not teleport player");

                Message.CREATE_ISLAND.send(builder.owner, Formatters.LOCATION_FORMATTER.format(
                        islandLocation), System.currentTimeMillis() - startTime);
            } else {
                Log.debugResult(Debug.CREATE_ISLAND, "Creation Callback", "Teleporting player");

                builder.owner.teleport(island, result -> {
                    Log.debugResult(Debug.CREATE_ISLAND, "Creation Callback",
                            "Teleported player. Result: " + result);

                    Message.CREATE_ISLAND.send(builder.owner, Formatters.LOCATION_FORMATTER.format(
                            islandLocation), System.currentTimeMillis() - startTime);

                    if (result) {
                        if (!affectedChunks.isEmpty()) {
                            BukkitExecutor.sync(() -> {
                                IslandUtils.resetChunksExcludedFromList(island, affectedChunks);
                                island.setBiome(biome, true);
                            }, 10L);
                        }

                        Dimension defaultDimension = plugin.getSettings().getWorlds().getDefaultWorldDimension();

                        if (defaultDimension.getEnvironment() == World.Environment.THE_END) {
                            plugin.getNMSDragonFight().awardTheEndAchievement(player);
                            this.dragonBattleService.get().resetEnderDragonBattle(island, defaultDimension);
                        }

                        PluginEventsFactory.callPostIslandCreateEvent(island, builder.owner);

                        if (onTeleportCallback != null)
                            onTeleportCallback.run();
                    }
                });
            }
        }), 1L);
    }

    @Override
    public void setIslandCreationAlgorithm(@Nullable IslandCreationAlgorithm islandCreationAlgorithm) {
        this.islandCreationAlgorithm = islandCreationAlgorithm != null ? islandCreationAlgorithm :
                DefaultIslandCreationAlgorithm.getInstance();
    }

    @Override
    public IslandCreationAlgorithm getIslandCreationAlgorithm() {
        return Optional.ofNullable(this.islandCreationAlgorithm).orElse(DefaultIslandCreationAlgorithm.getInstance());
    }

    @Override
    public boolean hasActiveCreateRequest(SuperiorPlayer superiorPlayer) {
        Preconditions.checkNotNull(superiorPlayer, "superiorPlayer parameter cannot be null.");
        return pendingCreationTasks.contains(superiorPlayer.getUniqueId());
    }

    @Override
    public void startIslandPreview(SuperiorPlayer superiorPlayer, String schemName, String islandName) {
        Preconditions.checkNotNull(schemName, "schemName parameter cannot be null.");
        startIslandPreview(superiorPlayer, plugin.getSchematics().getSchematic(schemName), islandName);
    }

    public void startIslandPreview(SuperiorPlayer superiorPlayer, Schematic schematic, String islandName) {
        Preconditions.checkNotNull(superiorPlayer, "superiorPlayer parameter cannot be null.");
        Preconditions.checkNotNull(schematic, "schematic parameter cannot be null.");
        Preconditions.checkNotNull(islandName, "islandName parameter cannot be null.");

        Location previewLocation = plugin.getSettings().getPreviewIslands().get(schematic.getName().toLowerCase(Locale.ENGLISH));
        if (previewLocation != null && previewLocation.getWorld() != null) {
            superiorPlayer.teleport(previewLocation, result -> {
                if (result) {
                    this.islandPreviews.startIslandPreview(new SIslandPreview(superiorPlayer, previewLocation, schematic, islandName));
                    BukkitExecutor.ensureMain(() -> superiorPlayer.runIfOnline(player -> player.setGameMode(GameMode.SPECTATOR)));
                    Message.ISLAND_PREVIEW_START.send(superiorPlayer, schematic.getName());
                }
            });
        }
    }

    @Override
    public void cancelIslandPreview(SuperiorPlayer superiorPlayer) {
        Preconditions.checkNotNull(superiorPlayer, "superiorPlayer parameter cannot be null.");

        IslandPreview islandPreview = this.islandPreviews.endIslandPreview(superiorPlayer);
        if (islandPreview != null) {
            superiorPlayer.runIfOnline(player -> {
                BukkitExecutor.ensureMain(() -> superiorPlayer.teleport(plugin.getGrid().getSpawnIsland(), teleportResult -> {
                    if (teleportResult && superiorPlayer.isOnline())
                        player.setGameMode(GameMode.SURVIVAL);
                }));
                PlayerChat.remove(player);
            });
        }
    }

    @Override
    public void cancelAllIslandPreviews() {
        if (!Bukkit.isPrimaryThread()) {
            BukkitExecutor.sync(this::cancelAllIslandPreviewsSync);
        } else {
            cancelAllIslandPreviewsSync();
        }
    }

    private void cancelAllIslandPreviewsSync() {
        if (!Bukkit.isPrimaryThread()) {
            Log.warn("Trying to cancel all island previews asynchronous. Stack trace:");
            new Exception().printStackTrace();
        }

        this.islandPreviews.getActivePreviews().forEach(islandPreview -> {
            SuperiorPlayer superiorPlayer = islandPreview.getPlayer();
            superiorPlayer.runIfOnline(player -> {
                superiorPlayer.teleport(plugin.getGrid().getSpawnIsland());
                // We don't wait for the teleport to happen, as this method is called when the server is disabled.
                // Therefore, we can't wait for the async task to occur.
                player.setGameMode(GameMode.SURVIVAL);
                PlayerChat.remove(player);
            });
        });
    }

    @Override
    public IslandPreview getIslandPreview(SuperiorPlayer superiorPlayer) {
        Preconditions.checkNotNull(superiorPlayer, "superiorPlayer parameter cannot be null.");
        return this.islandPreviews.getIslandPreview(superiorPlayer);
    }

    @Override
    public void deleteIsland(Island island) {
        Preconditions.checkNotNull(island, "island parameter cannot be null.");

        Log.debug(Debug.DELETE_ISLAND, island.getOwner().getName());

        island.getAllPlayersInside().forEach(superiorPlayer -> {
            MenuView<?, ?> openedView = superiorPlayer.getOpenedView();
            if (openedView != null)
                openedView.closeView();

            island.removeEffects(superiorPlayer);

            superiorPlayer.teleport(plugin.getGrid().getSpawnIsland());
            Message.ISLAND_GOT_DELETED_WHILE_INSIDE.send(superiorPlayer);
        });

        this.islandsContainer.removeIsland(island);

        // Delete island from database
        IslandsDatabaseBridge.deleteIsland(island);

        for (Dimension dimension : Dimension.values()) {
            if (dimension.getEnvironment() == World.Environment.THE_END)
                this.dragonBattleService.get().stopEnderDragonBattle(island, dimension);
        }
    }

    @Override
    public Island getIsland(SuperiorPlayer superiorPlayer) {
        Preconditions.checkNotNull(superiorPlayer, "superiorPlayer parameter cannot be null.");
        return superiorPlayer.getIsland();
    }

    @Override
    public Island getIsland(int index, SortingType sortingType) {
        Preconditions.checkNotNull(sortingType, "sortingType parameter cannot be null.");
        return this.islandsContainer.getIslandAtPosition(index, sortingType);
    }

    @Override
    public int getIslandPosition(Island island, SortingType sortingType) {
        Preconditions.checkNotNull(island, "island parameter cannot be null.");
        Preconditions.checkNotNull(sortingType, "sortingType parameter cannot be null.");
        return this.islandsContainer.getIslandPosition(island, sortingType);
    }

    @Override
    public Island getIsland(UUID uuid) {
        Preconditions.checkNotNull(uuid, "uuid parameter cannot be null.");
        SuperiorPlayer superiorPlayer = plugin.getPlayers().getPlayersContainer().getSuperiorPlayer(uuid);
        return superiorPlayer == null ? null : getIsland(superiorPlayer);
    }

    @Override
    public Island getIslandByUUID(UUID uuid) {
        Preconditions.checkNotNull(uuid, "uuid parameter cannot be null.");
        return this.islandsContainer.getIslandByUUID(uuid);
    }

    @Override
    public Island getIsland(String islandName) {
        Preconditions.checkNotNull(islandName, "islandName parameter cannot be null.");
        String inputName = Formatters.STRIP_COLOR_FORMATTER.format(islandName);
        return getIslands().stream().filter(island -> island.getRawName().equalsIgnoreCase(inputName)).findFirst().orElse(null);
    }

    @Override
    public Island getIslandAt(Location location) {
        if (location == null)
            return null;

        if (spawnIsland != null && spawnIsland.isInside(location))
            return spawnIsland;

        return this.islandsContainer.getIslandAt(location);
    }

    @Override
    @Deprecated
    public Island getIslandAt(Chunk chunk) {
        Preconditions.checkNotNull(chunk, "chunk argument cannot be null");

        if (!plugin.getGrid().isIslandsWorld(chunk.getWorld()))
            return null;

        Location cornerLocation;
        try (ChunkPosition chunkPosition = ChunkPosition.of(chunk)) {
            cornerLocation = WorldBlocks.getChunkBlock(chunkPosition, 0, 100, 0);
        }

        Island island;

        // Checks corner at 0, y, 0
        if ((island = getIslandAt(cornerLocation)) != null)
            return island;

        // Checks corner at 15, y, 0
        cornerLocation.add(15, 0, 0);
        if ((island = getIslandAt(cornerLocation)) != null)
            return island;

        // Checks corner at 15, y, 15
        cornerLocation.add(0, 0, 15);
        if ((island = getIslandAt(cornerLocation)) != null)
            return island;

        // Checks corner at 0, y, 15
        cornerLocation.add(-15, 0, 0);
        if ((island = getIslandAt(cornerLocation)) != null)
            return island;

        return null;
    }

    @Override
    public List<Island> getIslandsAt(Chunk chunk) {
        Preconditions.checkNotNull(chunk, "chunk argument cannot be null");

        if (!plugin.getGrid().isIslandsWorld(chunk.getWorld()))
            return Collections.emptyList();

        Set<Island> islands = new LinkedHashSet<>();

        Location cornerLocation;
        try (ChunkPosition chunkPosition = ChunkPosition.of(chunk)) {
            cornerLocation = WorldBlocks.getChunkBlock(chunkPosition, 0, 100, 0);
        }

        Island island;

        // Checks corner at 0, y, 0
        if ((island = getIslandAt(cornerLocation)) != null)
            islands.add(island);

        // Checks corner at 15, y, 0
        cornerLocation.add(15, 0, 0);
        if ((island = getIslandAt(cornerLocation)) != null)
            islands.add(island);

        // Checks corner at 15, y, 15
        cornerLocation.add(0, 0, 15);
        if ((island = getIslandAt(cornerLocation)) != null)
            islands.add(island);

        // Checks corner at 0, y, 15
        cornerLocation.add(-15, 0, 0);
        if ((island = getIslandAt(cornerLocation)) != null)
            islands.add(island);

        return islands.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(new LinkedList<>(islands));
    }

    @Override
    public void transferIsland(UUID oldOwner, UUID newOwner) {
        // Do nothing.
    }

    @Override
    public int getSize() {
        return this.islandsContainer.getIslandsAmount();
    }

    @Override
    public void sortIslands(SortingType sortingType) {
        Preconditions.checkNotNull(sortingType, "sortingType parameter cannot be null.");
        sortIslands(sortingType, null);
    }

    public void forceSortIslands(SortingType sortingType) {
        Preconditions.checkNotNull(sortingType, "sortingType parameter cannot be null.");
        setForceSort(true);
        sortIslands(sortingType, null);
    }

    @Override
    public void sortIslands(SortingType sortingType, Runnable onFinish) {
        Preconditions.checkNotNull(sortingType, "sortingType parameter cannot be null.");

        Log.debug(Debug.SORT_ISLANDS, sortingType.getName());

        boolean isSortedAlready = activeSortingTasks.get().readAndGet(
                activeSortingTasks -> activeSortingTasks.contains(sortingType));
        if (isSortedAlready) {
            if (onFinish != null)
                onFinish.run();
            forceSort = false;
            return;
        }

        activeSortingTasks.get().write(activeSortingTasks -> activeSortingTasks.add(sortingType));

        this.islandsContainer.sortIslands(sortingType, forceSort, () -> {
            plugin.getMenus().refreshTopIslands(sortingType);
            activeSortingTasks.get().write(activeSortingTasks -> activeSortingTasks.remove(sortingType));
            if (onFinish != null)
                onFinish.run();
        });

        forceSort = false;
    }

    public void setForceSort(boolean forceSort) {
        this.forceSort = forceSort;
    }

    @Override
    public Island getSpawnIsland() {
        if (spawnIsland == null)
            updateSpawn();

        return spawnIsland;
    }

    @Override
    public World getIslandsWorld(Island island, Dimension dimension) {
        Preconditions.checkNotNull(island, "island parameter cannot be null.");
        Preconditions.checkNotNull(dimension, "dimension parameter cannot be null.");

        if (island.isSpawn()) {
            if (island instanceof SpawnIsland)
                return ((SpawnIsland) island).getSpawnWorld();

            return island.getIslandHome(dimension).getWorld();
        }

        return plugin.getProviders().getWorldsProvider().getIslandsWorld(island, dimension);
    }

    @Override
    public Dimension getIslandsWorldDimension(World world) {
        Preconditions.checkNotNull(world, "world parameter cannot be null.");

        if (!isIslandsWorld(world))
            return null;

        if (customWorlds.contains(world.getUID()))
            return Dimension.getByName(world.getName().toUpperCase(Locale.ENGLISH));

        return plugin.getProviders().getWorldsProvider().getIslandsWorldDimension(world);
    }

    @Override
    @Deprecated
    public World getIslandsWorld(Island island, World.Environment environment) {
        return getIslandsWorld(island, Dimensions.fromEnvironment(environment));
    }

    @Override
    public WorldInfo getIslandsWorldInfo(Island island, Dimension dimension) {
        Preconditions.checkNotNull(island, "island parameter cannot be null.");
        Preconditions.checkNotNull(dimension, "dimension parameter cannot be null.");

        if (island.isSpawn()) {
            if (island instanceof SpawnIsland)
                return ((SpawnIsland) island).getSpawnWorldInfo();

            return WorldInfo.of(island.getIslandHome(dimension).getWorld());
        }

        WorldsProvider worldsProvider = plugin.getProviders().getWorldsProvider();

        if (worldsProvider instanceof LazyWorldsProvider)
            return ((LazyWorldsProvider) worldsProvider).getIslandsWorldInfo(island, dimension);

        World world = this.getIslandsWorld(island, dimension);
        return world == null ? null : WorldInfo.of(world);
    }

    @Override
    @Deprecated
    public WorldInfo getIslandsWorldInfo(Island island, World.Environment environment) {
        return getIslandsWorldInfo(island, Dimensions.fromEnvironment(environment));
    }

    @Nullable
    @Override
    public WorldInfo getIslandsWorldInfo(Island island, String worldName) {
        Preconditions.checkNotNull(island, "island parameter cannot be null.");
        Preconditions.checkNotNull(worldName, "worldName parameter cannot be null.");

        if (island.isSpawn()) {
            if (island instanceof SpawnIsland)
                return ((SpawnIsland) island).getSpawnWorldInfo();

            return WorldInfo.of(island.getIslandHome(Dimensions.NORMAL).getWorld());
        }

        WorldsProvider worldsProvider = plugin.getProviders().getWorldsProvider();

        if (worldsProvider instanceof LazyWorldsProvider)
            return ((LazyWorldsProvider) worldsProvider).getIslandsWorldInfo(island, worldName);

        World world = Bukkit.getWorld(worldName);
        return world == null || !isIslandsWorld(world) ? null : WorldInfo.of(world);
    }

    @Override
    public boolean isIslandsWorld(World world) {
        Preconditions.checkNotNull(world, "world parameter cannot be null.");
        return customWorlds.contains(world.getUID()) || plugin.getProviders().getWorldsProvider().isIslandsWorld(world);
    }

    @Override
    public void registerIslandWorld(World world) {
        Preconditions.checkNotNull(world, "world parameter cannot be null.");

        String dimensionName = world.getName().toUpperCase(Locale.ENGLISH);
        try {
            Dimension.register(dimensionName, world.getEnvironment());
        } catch (Exception error) {
            throw new IllegalArgumentException("Dimension with the name " + dimensionName + " already exists.");
        }

        customWorlds.add(world.getUID());
    }

    @Override
    public List<World> getRegisteredWorlds() {
        return new SequentialListBuilder<World>().build(customWorlds, Bukkit::getWorld);
    }

    @Override
    @Deprecated
    public List<UUID> getAllIslands(SortingType sortingType) {
        return new SequentialListBuilder<UUID>()
                .build(getIslands(sortingType), ISLAND_OWNERS_MAPPER);
    }

    @Override
    public List<Island> getIslands() {
        return this.islandsContainer.getIslandsUnsorted();
    }

    @Override
    public List<Island> getIslands(SortingType sortingType) {
        Preconditions.checkNotNull(sortingType, "sortingType parameter cannot be null.");
        return this.islandsContainer.getSortedIslands(sortingType);
    }

    @Override
    @Deprecated
    public int getBlockAmount(Block block) {
        Preconditions.checkNotNull(block, "block parameter cannot be null.");
        try (ObjectsPools.Wrapper<Location> wrapper = ObjectsPools.LOCATION.obtain()) {
            return getBlockAmount(block.getLocation(wrapper.getHandle()));
        }
    }

    @Override
    @Deprecated
    public int getBlockAmount(Location location) {
        return plugin.getStackedBlocks().getStackedBlockAmount(location);
    }

    @Override
    @Deprecated
    public void setBlockAmount(Block block, int amount) {
        plugin.getStackedBlocks().setStackedBlock(block, amount);
    }

    @Override
    public List<Location> getStackedBlocks() {
        return new SequentialListBuilder<Location>().build(plugin.getStackedBlocks().getStackedBlocks().keySet());
    }

    @Override
    public void calcAllIslands() {
        calcAllIslands(null);
    }

    @Override
    public void calcAllIslands(Runnable callback) {
        Log.debug(Debug.CALCULATE_ALL_ISLANDS);

        List<Island> islands = new ArrayList<>();

        {
            for (Island island : this.islandsContainer.getIslandsUnsorted()) {
                if (!island.isBeingRecalculated())
                    islands.add(island);
            }
        }

        for (int i = 0; i < islands.size(); i++) {
            islands.get(i).calcIslandWorth(null, i + 1 < islands.size() ? null : callback);
        }
    }

    public void startCalcTask() {
        this.activeCalcTasks.incrementAndGet();
    }

    public boolean stopCalcTask() {
        int activeCalcTasks = this.activeCalcTasks.decrementAndGet();
        if (activeCalcTasks < 0)
            throw new IllegalStateException();
        return activeCalcTasks == 0;
    }

    @Override
    public void addIslandToPurge(Island island) {
        Preconditions.checkNotNull(island, "island parameter cannot be null.");
        Preconditions.checkNotNull(island.getOwner(), "island's owner cannot be null.");

        Log.debug(Debug.PURGE_ISLAND, island.getOwner().getName());

        this.islandsPurger.scheduleIslandPurge(island);
    }

    @Override
    public void removeIslandFromPurge(Island island) {
        Preconditions.checkNotNull(island, "island parameter cannot be null.");
        Preconditions.checkNotNull(island.getOwner(), "island's owner cannot be null.");

        Log.debug(Debug.UNPURGE_ISLAND, island.getOwner().getName());

        this.islandsPurger.unscheduleIslandPurge(island);
    }

    @Override
    public boolean isIslandPurge(Island island) {
        Preconditions.checkNotNull(island, "island parameter cannot be null.");
        Preconditions.checkNotNull(island.getOwner(), "island's owner cannot be null.");
        return this.islandsPurger.isIslandPurgeScheduled(island);
    }

    @Override
    public List<Island> getIslandsToPurge() {
        return this.islandsPurger.getScheduledPurgedIslands();
    }

    @Override
    public void registerSortingType(SortingType sortingType) {
        Preconditions.checkNotNull(sortingType, "sortingType parameter cannot be null.");

        Log.debug(Debug.REGISTER_SORTING_TYPE, sortingType.getName());

        if (this.islandsContainer == null) {
            pendingSortingTypes.add(sortingType);
        } else {
            this.islandsContainer.addSortingType(sortingType, true);
        }
    }

    @Override
    public BigDecimal getTotalWorth() {
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastTimeWorthUpdate > 60000) {
            lastTimeWorthUpdate = currentTime;
            totalWorth = BigDecimal.ZERO;
            for (Island island : getIslands())
                totalWorth = totalWorth.add(island.getWorth());
        }

        return totalWorth;
    }

    @Override
    public BigDecimal getTotalLevel() {
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastTimeLevelUpdate > 60000) {
            lastTimeLevelUpdate = currentTime;
            totalLevel = BigDecimal.ZERO;
            for (Island island : getIslands())
                totalLevel = totalLevel.add(island.getIslandLevel());
        }

        return totalLevel;
    }

    @Override
    public Location getLastIslandLocation() {
        return lastIsland.parse();
    }

    @Override
    public void setLastIslandLocation(Location location) {
        this.setLastIsland(new SBlockPosition(location));
    }

    @Override
    public IslandsContainer getIslandsContainer() {
        return this.islandsContainer;
    }

    @Override
    public void setIslandsContainer(IslandsContainer islandsContainer) {
        Preconditions.checkNotNull(islandsContainer, "islandsContainer parameter cannot be null");
        this.islandsContainer = islandsContainer;
        pendingSortingTypes.forEach(sortingType -> islandsContainer.addSortingType(sortingType, false));
        pendingSortingTypes.clear();
    }

    @Override
    public DatabaseBridge getDatabaseBridge() {
        return databaseBridge;
    }

    public UUID generateIslandUUID() {
        UUID uuid;

        do {
            uuid = UUID.randomUUID();
        } while (getIslandByUUID(uuid) != null || plugin.getPlayers().getPlayersContainer().getSuperiorPlayer(uuid) != null);

        return uuid;
    }

    public void disablePlugin() {
        this.pluginDisable = true;
        cancelAllIslandPreviews();
    }

    public boolean wasPluginDisabled() {
        return this.pluginDisable;
    }

    public void loadGrid(DatabaseResult resultSet) {
        resultSet.getString("last_island").map(Serializers.LOCATION_SPACED_SERIALIZER::deserialize)
                .ifPresent(lastIsland -> this.lastIsland = new SBlockPosition((LazyWorldLocation) lastIsland));

        if (!plugin.getSettings().getWorlds().getDefaultWorldName().equals(lastIsland.getWorldName())) {
            lastIsland = new SBlockPosition(plugin.getSettings().getWorlds().getDefaultWorldName(),
                    lastIsland.getX(), lastIsland.getY(), lastIsland.getZ());
        }

        int maxIslandSize = resultSet.getInt("max_island_size").orElse(plugin.getSettings().getMaxIslandSize());
        String world = resultSet.getString("world").orElse(plugin.getSettings().getWorlds().getDefaultWorldName());

        try {
            if (plugin.getSettings().getMaxIslandSize() != maxIslandSize) {
                Log.warn("You have changed the max-island-size value without deleting database.");
                Log.warn("Restoring it to the old value...");
                plugin.getSettings().updateValue("max-island-size", maxIslandSize);
            }

            if (!plugin.getSettings().getWorlds().getDefaultWorldName().equals(world)) {
                Log.warn("You have changed the island-world value without deleting database.");
                Log.warn("Restoring it to the old value...");
                plugin.getSettings().updateValue("worlds.normal-world", world);
            }
        } catch (IOException error) {
            Log.error(error, "An unexpected error occurred while loading grid:");
            Bukkit.shutdown();
        }
    }

    public void saveIslands() {
        List<Island> onlineIslands = new SequentialListBuilder<Island>()
                .filter(Objects::nonNull)
                .build(Bukkit.getOnlinePlayers(), player -> plugin.getPlayers().getSuperiorPlayer(player).getIsland());

        List<Island> modifiedIslands = new SequentialListBuilder<Island>()
                .filter(IslandsDatabaseBridge::isModified)
                .build(getIslands());

        if (!onlineIslands.isEmpty())
            onlineIslands.forEach(Island::updateLastTime);

        if (!modifiedIslands.isEmpty())
            modifiedIslands.forEach(IslandsDatabaseBridge::executeFutureSaves);

        getIslands().forEach(Island::removeEffects);
    }

    private void setLastIsland(SBlockPosition lastIsland) {
        Log.debug(Debug.SET_LAST_ISLAND, lastIsland);
        this.lastIsland = lastIsland;
        GridDatabaseBridge.saveLastIsland(this, lastIsland);
    }

    private void initializeDatabaseBridge() {
        databaseBridge = plugin.getFactory().createDatabaseBridge(this);
        databaseBridge.setDatabaseBridgeMode(DatabaseBridgeMode.SAVE_DATA);
    }

    private void loadServerUuid() {
        File bStatsFile = new File(plugin.getDataFolder().getParentFile(), "bStats/config.yml");
        if (!bStatsFile.exists())
            return;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(bStatsFile);
        if (!config.isString("serverUuid"))
            return;

        try {
            this.serverUUID = UUID.fromString(config.getString("serverUuid"));
        } catch (Exception ignored) {
        }
    }

}
