package org.torch.server;

import lombok.Getter;
import lombok.Setter;
import net.minecraft.server.*;
import net.minecraft.server.BlockPosition.PooledBlockPosition;
import net.minecraft.server.EnumDirection.EnumDirectionLimit;
import net.minecraft.server.PacketPlayOutWorldBorder.EnumWorldBorderAction;

import org.bukkit.Bukkit;
import org.bukkit.block.BlockState;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.event.block.BlockCanBuildEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.generator.ChunkGenerator;
import org.spigotmc.ActivationRange;
import org.spigotmc.AsyncCatcher;
import org.spigotmc.SpigotWorldConfig;
import org.torch.api.Async;
import org.torch.api.TorchReactor;
import org.torch.utils.collection.WrappedCollections;
import com.destroystokyo.paper.PaperWorldConfig;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import com.destroystokyo.paper.event.server.ServerExceptionEvent;
import com.destroystokyo.paper.exception.ServerInternalException;
import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import com.koloboke.collect.map.hash.HashObjFloatMaps;
import com.koloboke.collect.map.hash.HashObjObjMaps;
import com.koloboke.collect.set.hash.HashObjSets;

import co.aikar.timings.TimingHistory;
import co.aikar.timings.WorldTimingsHandler;

import static org.torch.server.TorchServer.logger;
import static org.torch.server.TorchServer.getServer;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import javax.annotation.Nullable;

@Getter
public final class TorchWorld implements TorchReactor, net.minecraft.server.IBlockAccess {
    /**
     * The legacy world instance
     */
    private final World servant;
    
    private static final EnumDirection[] BLOCK_FACES = {EnumDirection.DOWN, EnumDirection.UP, EnumDirection.EAST, EnumDirection.NORTH, EnumDirection.SOUTH, EnumDirection.WEST};
    
    /**
     * Default sea level height
     */
    @Setter private int seaLevel = 63;
    /**
     * Updates scheduled by scheduleBlockUpdate happen immediately if true
     */
    protected boolean scheduledUpdatesAreImmediate;

    /**
     * A list of all loaded entities in the world
     */
    @SuppressWarnings("serial")
    public final List<Entity> entityList = new ArrayList<Entity>() {
        @Override
        public Entity remove(int index) {
            guard();
            return super.remove( index );
        }

        @Override
        public boolean remove(Object o) {
            guard();
            return super.remove( o );
        }

        private void guard() {
            if (guardEntityList) throw new ConcurrentModificationException();
        }
    };
    private boolean guardEntityList;

    private final Set<Entity> unloadedEntities = HashObjSets.newMutableSet();

    @SuppressWarnings("serial")
    private final List<TileEntity> tickableTileEntities = new ArrayList<TileEntity>() {
        @Override
        public boolean add(TileEntity e) {
            if (e == null) return false;
            return super.add(e);
        }
    };

    private final Set<TileEntity> addedTileEntities = HashObjSets.newMutableSet();

    private final Set<TileEntity> tileEntityToUnload = HashObjSets.newMutableSet();
    
    public final Map<BlockPosition, TileEntity> capturedTileEntities = HashObjObjMaps.newMutableMap();

    /**
     * Players in the world
     */
    public final List<EntityHuman> players = WrappedCollections.createHashSetBackedArrayList();
    /**
     * A list of all the lightning entities
     */
    public final List<Entity> lightingEntities = Lists.newArrayList();
    /**
     * Entities in the world, by entity id
     */
    protected final IntHashMap<Entity> entitiesById = new IntHashMap<Entity>();

    private final long cloudColour = 16777215L;
    /**
     * How much light is subtracted from full daylight
     */
    @Setter private int skylightSubtracted;
    /**
     * Contains the current Linear Congruential Generator seed for block updates. Used with an A value of 3 and a C
     * value of 0x3c6ef35f, producing a highly planar series of values ill-suited for choosing random blocks in a
     * 16x128x16 field.
     */
    protected int updateLCG = new Random().nextInt();
    /**
     * Magic number used to generate fast random numbers for 3d distribution within a chunk
     */
    public final int DIST_HASH = 1013904223;

    public float prevRainingStrength;
    public float rainingStrength;

    public float prevThunderingStrength;
    public float thunderingStrength;

    public final Random random = new Random();
    /**
     * The path listener
     */
    protected NavigationListener navigator = new NavigationListener();
    /**
     * The world event listener
     */
    protected List<IWorldAccess> worldListeners;
    /**
     * Handles chunk operations and caching
     */
    @Setter protected IChunkProvider chunkProvider;

    protected final IDataManager dataManager;
    /**
     * Holds information about a world (size on disk, time, spawn point, seed, etc.)
     */
    public WorldData worldData;
    /**
     * If set, this flag forces a request to load a chunk to load the chunk rather than defaulting to the world's
     * chunkprovider's dummy if possible
     */
    protected boolean isLoading;

    private final Calendar calendar;

    private boolean processingLoadedTiles;
    private final WorldBorder worldBorder;
    /**
     * A temporary list of blocks and light values used when updating light levels. Holds up to 32x32x32 blocks (the
     * maximum influence of a light source.) Every element is a packed bit value: 0000000000LLLLzzzzzzyyyyyyxxxxxx. The
     * 4-bit L is a light level used when darkening blocks. 6-bit numbers x, y and z represent the block's offset from
     * the original block, plus 32 (i.e. value of 31 would mean a -1 offset
     */
    int[] lightUpdateBlocks;

    ///////// CB / S / P Stuffs
    public boolean pvpMode;
    public boolean keepSpawnInMemory = true;
    public ChunkGenerator generator;
    
    public boolean captureBlockStates = false;
    public boolean captureTreeGeneration = false;
    
    @SuppressWarnings("serial")
    public ArrayList<BlockState> capturedBlockStates= new ArrayList<BlockState>(){
        @Override
        public boolean add( BlockState blockState ) {
            Iterator<BlockState> blockStateIterator = this.iterator();
            while( blockStateIterator.hasNext() ) {
                BlockState blockState1 = blockStateIterator.next();
                if ( blockState1.getLocation().equals( blockState.getLocation() ) ) {
                    return false;
                }
            }
            
            return super.add( blockState );
        }
    };
    public long ticksPerAnimalSpawns;
    public long ticksPerMonsterSpawns;
    public boolean populating;
    
    public final SpigotWorldConfig spigotConfig;
    public final PaperWorldConfig paperConfig;
    public WorldTimingsHandler timings;
    
    public static boolean haveWeSilencedAPhysicsCrash;
    public static String blockLocation;
    
    // Used during remove entity, designed for performance, or we should copy the list
    private int tickPosition;
    private int tileTickPosition;
    
    public final Map<Explosion.CacheKey, Float> explosionDensityCache = HashObjFloatMaps.newMutableMap();
    
    public TorchWorld(IDataManager dataHandler, WorldData data, WorldProvider worldprovider, boolean flag, ChunkGenerator gen, org.bukkit.World.Environment env, World legacy) {
        servant = legacy;
        
        spigotConfig = new SpigotWorldConfig(data.getName());
        paperConfig = new PaperWorldConfig(data.getName(), this.spigotConfig);
        generator = gen;
        
        worldListeners = Lists.newArrayList(new IWorldAccess[] { this.navigator });
        calendar = Calendar.getInstance();
        servant.allowMonsters = true;
        servant.allowAnimals = true;
        lightUpdateBlocks = new int['\u8000'];
        
        dataManager = dataHandler;
        worldData = data;
        worldBorder = worldprovider.getWorldBorder();
        
        // CraftBukkit start
        getWorldBorder().world = (WorldServer) servant;
        // From PlayerList.setPlayerFileData
        getWorldBorder().a(new IWorldBorderListener() {
            @Override
            public void a(WorldBorder worldborder, double d0) {
                getServer().getPlayerList().sendAll(new PacketPlayOutWorldBorder(worldborder, EnumWorldBorderAction.SET_SIZE), worldborder.world);
            }

            @Override
            public void a(WorldBorder worldborder, double d0, double d1, long i) {
                getServer().getPlayerList().sendAll(new PacketPlayOutWorldBorder(worldborder, EnumWorldBorderAction.LERP_SIZE), worldborder.world);
            }

            @Override
            public void a(WorldBorder worldborder, double d0, double d1) {
                getServer().getPlayerList().sendAll(new PacketPlayOutWorldBorder(worldborder, EnumWorldBorderAction.SET_CENTER), worldborder.world);
            }

            @Override
            public void a(WorldBorder worldborder, int i) {
                getServer().getPlayerList().sendAll(new PacketPlayOutWorldBorder(worldborder, EnumWorldBorderAction.SET_WARNING_TIME), worldborder.world);
            }

            @Override
            public void b(WorldBorder worldborder, int i) {
                getServer().getPlayerList().sendAll(new PacketPlayOutWorldBorder(worldborder, EnumWorldBorderAction.SET_WARNING_BLOCKS), worldborder.world);
            }

            @Override
            public void b(WorldBorder worldborder, double d0) {}

            @Override
            public void c(WorldBorder worldborder, double d0) {}
        });
        // CraftBukkit end
    }

    public CraftServer getCraftServer() {
        return (CraftServer) Bukkit.getServer();
    }
    
    public CraftWorld getCraftWorld() {
        return servant.getWorld();
    }

    // The method added by Paper, however,
    // getLoadedChunkAt(IChunkProvider) method will also mark the chunk as 'unloaded',
    // but getChunkIfLoaded(ChunkProviderServer) method not does that, it's also the original NMS usage(like the below method)
    @Nullable
    public Chunk getChunkIfLoaded(BlockPosition blockPos) {
        return ((ChunkProviderServer) this.chunkProvider).getChunkIfLoaded(blockPos.getX() >> 4, blockPos.getZ() >> 4);
    }

    @Nullable
    public Chunk getChunkIfLoaded(int cx, int cz) {
        return ((ChunkProviderServer) this.chunkProvider).getChunkIfLoaded(cx, cz);
    }
    
    public void applyIfChunkLoaded(BlockPosition blockPos, Consumer<Chunk> how) {
        Chunk chunk = ((ChunkProviderServer) this.chunkProvider).getChunkIfLoaded(blockPos.getX() >> 4, blockPos.getZ() >> 4);
        if (chunk != null) how.accept(chunk);
    }

    public void applyIfChunkLoaded(int cx, int cz, Consumer<Chunk> how) {
        Chunk chunk = ((ChunkProviderServer) this.chunkProvider).getChunkIfLoaded(cx, cz);
        if (chunk != null) how.accept(chunk);
    }
    
    public WorldChunkManager getWorldChunkManager() {
        return servant.worldProvider.k(); // PAIL: getWorldChunkManager
    }
    
    public void initialize(WorldSettings settings) {
        this.worldData.d(true); // PAIL: setServerInitialized
    }
    
    public void setSpawnFlags(boolean monsters, boolean animals) {
        servant.allowMonsters = monsters;
        servant.allowAnimals = animals;
    }
    
    public void doTick() {
        this.tickWeather();
    }
    
    public void tickWeather() {
        if (!servant.worldProvider.m()) return;
        
        boolean doWeather = this.getGameRules().getBoolean("doWeatherCycle");
        if (doWeather) {
            int durationThunder = this.worldData.z();
            if (durationThunder > 0) {
                durationThunder--;
                this.worldData.i(durationThunder);
                this.worldData.setThunderDuration(this.worldData.isThundering() ? 1 : 2);
                this.worldData.setWeatherDuration(this.worldData.hasStorm() ? 1 : 2);
            }

            int j = this.worldData.getThunderDuration();

            if (j <= 0) {
                if (this.worldData.isThundering()) {
                    this.worldData.setThunderDuration(this.random.nextInt(12000) + 3600);
                } else {
                    this.worldData.setThunderDuration(this.random.nextInt(168000) + 12000);
                }
            } else {
                --j;
                this.worldData.setThunderDuration(j);
                if (j <= 0) {
                    this.worldData.setThundering(!this.worldData.isThundering());
                }
            }

            int durationStorm = this.worldData.getWeatherDuration();
            if (durationStorm <= 0) {
                if (this.worldData.hasStorm()) {
                    this.worldData.setWeatherDuration(this.random.nextInt(12000) + 12000);
                } else {
                    this.worldData.setWeatherDuration(this.random.nextInt(168000) + 12000);
                }
            } else {
                durationStorm--;
                this.worldData.setWeatherDuration(durationStorm);
                if (durationStorm <= 0) {
                    this.worldData.setStorm(!this.worldData.hasStorm());
                }
            }
        }
        
        this.prevThunderingStrength = this.thunderingStrength;
        if (this.worldData.isThundering()) {
            this.thunderingStrength = (float) (this.thunderingStrength + 0.01D);
        } else {
            this.thunderingStrength = (float) (this.thunderingStrength - 0.01D);
        }
        this.thunderingStrength = MathHelper.a(this.thunderingStrength, 0.0F, 1.0F);
        
        this.prevRainingStrength = this.rainingStrength;
        if (this.worldData.hasStorm()) {
            this.rainingStrength = (float) (this.rainingStrength + 0.01D);
        } else {
            this.rainingStrength = (float) (this.rainingStrength - 0.01D);
        }
        this.rainingStrength = MathHelper.a(this.rainingStrength, 0.0F, 1.0F);

        // Moved to WorldServer
        /* for (EntityHuman player : this.players) {
            if (player.world == servant) {
                ((EntityPlayer) player).tickWeather();
            }
        } */
    }
    
    public void tickEntities() {
        Iterator<Entity> it = lightingEntities.iterator();
        while (it.hasNext()) {
            Entity entity = it.next();
            if (entity == null) continue; // CraftBukkit - fixed an NPE
            
            try {
                entity.ticksLived++;
                entity.A_(); // PAIL: onUpdate
            } catch (Throwable t) {
                CrashReport report = CrashReport.a(t, "Ticking entity");
                CrashReportSystemDetails details = report.a("Entity being ticked");
                
                entity.appendEntityCrashDetails(details);
                
                throw new ReportedException(report);
            }
            
            if (entity.dead) {
                it.remove();
            }
        }
        
        timings.entityRemoval.startTiming();
        this.entityList.removeAll(this.unloadedEntities);
        
        for (Entity unload : this.unloadedEntities) {
            int cZ = unload.getChunkZ();
            int cX = unload.getChunkX();
            
            if (unload.isAddedToChunk()) {
                this.applyIfChunkLoaded(cX, cZ, chunk -> chunk.b(unload));
            }
            
            this.onEntityRemove(unload);
        }
        
        this.unloadedEntities.clear();
        this.tickPlayers();
        timings.entityRemoval.stopTiming();
        
        ActivationRange.activateEntities(servant);
        
        timings.entityTick.startTiming();
        guardEntityList = true;
        TimingHistory.entityTicks += this.entityList.size(); // Paper
        
        for (tickPosition = 0; tickPosition < entityList.size(); tickPosition++) {
            tickPosition = (tickPosition < entityList.size()) ? tickPosition : 0;
            Entity entity = this.entityList.get(this.tickPosition);
            Entity entity1 = entity.bB();
            
            if (entity1 != null) {
                if (!entity1.dead && entity1.w(entity)) {
                    continue;
                }
                entity.stopRiding();
            }
            
            if (!entity.dead && !(entity instanceof EntityPlayer)) {
                try {
                    entity.tickTimer.startTiming();
                    this.updateEntity(entity);
                    entity.tickTimer.stopTiming();
                } catch (Throwable t) {
                    entity.tickTimer.stopTiming();
                    // Paper start - Prevent tile entity and entity crashes
                    String msg = "Entity threw exception at " + entity.world.getWorld().getName() + ":" + entity.locX + "," + entity.locY + "," + entity.locZ;
                    logger.error(msg);
                    t.printStackTrace();
                    getCraftServer().getPluginManager().callEvent(new ServerExceptionEvent(new ServerInternalException(msg, t)));
                    entity.dead = true;
                    continue;
                    // Paper end
                }
            }
            
            if (entity.dead) {
                int cX = entity.ab;
                int cZ = entity.ad;
                
                if (entity.aa) {
                    this.applyIfChunkLoaded(cX, cZ, chunk -> chunk.b(entity));
                }
                
                guardEntityList = false;
                entityList.remove(tickPosition--);
                guardEntityList = true;
                
                this.onEntityRemove(entity);
            }
        }
        guardEntityList = false;
        timings.entityTick.stopTiming();
        
        this.processingLoadedTiles = true;
        
        timings.tileEntityTick.startTiming();
        // CraftBukkit start - From below, clean up tile entities before ticking them
        if (!this.tileEntityToUnload.isEmpty()) {
            this.tickableTileEntities.removeAll(this.tileEntityToUnload);
            this.tileEntityToUnload.clear();
        }
        // CraftBukkit end
        
        for (tileTickPosition = 0; tileTickPosition < tickableTileEntities.size(); tileTickPosition++) { // Paper - Disable tick limiters
            tileTickPosition = (tileTickPosition < tickableTileEntities.size()) ? tileTickPosition : 0;
            TileEntity tickable = this.tickableTileEntities.get(tileTickPosition);
            
            if (tickable == null) {
                logger.warn("Spigot has detected a null entity and has removed it, preventing a crash");
                this.tickableTileEntities.remove(tileTickPosition--);
                continue;
            }
            
            if (!tickable.y() && tickable.u()) {
                BlockPosition blockposition = tickable.getPosition();
                
                if (this.isChunkLoaded(blockposition) && this.worldBorder.a(blockposition)) {
                    try {
                        tickable.tickTimer.startTiming();
                        ((ITickable) tickable).F_();
                    } catch (Throwable t) {
                        // Paper start - Prevent tile entity and entity crashes
                        String msg = "TileEntity threw exception at " + tickable.world.getWorld().getName() + ":" + tickable.position.getX() + "," + tickable.position.getY() + "," + tickable.position.getZ();
                        System.err.println(msg);
                        t.printStackTrace();
                        getCraftServer().getPluginManager().callEvent(new ServerExceptionEvent(new ServerInternalException(msg, t)));
                        this.tickableTileEntities.remove(tileTickPosition--);
                        continue;
                    } finally {
                        tickable.tickTimer.stopTiming();
                    }
                }
            }
            
            if (tickable.y()) {
                this.tickableTileEntities.remove(tileTickPosition--);
                
                BlockPosition pos = tickable.getPosition();
                this.applyIfChunkLoaded(pos, chunk -> chunk.d(pos));
            }
	}

        timings.tileEntityTick.stopTiming();
        
        this.processingLoadedTiles = false;
        
        timings.tileEntityPending.startTiming();
        if (!this.addedTileEntities.isEmpty()) {
            for (TileEntity added : this.addedTileEntities) {
                if (!added.y()) {
                    BlockPosition pos = added.getPosition();
                    
                    this.applyIfChunkLoaded(pos, chunk -> {
                        IBlockData iblockdata = chunk.getBlockData(pos);
                        
                        chunk.a(pos, added);
                        this.notifyBlockUpdate(pos, iblockdata, iblockdata);
                        
                        this.addTileEntity(added); // Paper - remove unused list
                    });
                }
            }
            
            this.addedTileEntities.clear();
        }
        timings.tileEntityPending.stopTiming();
        
        TimingHistory.tileEntityTicks += this.tickableTileEntities.size(); // Paper
    }
    
    public void tickPlayers() {
        // Done in WorldServer
        /* for (Entity entity : this.players) {
            Entity entity1 = entity.bB();

            if (entity1 != null) {
                if (!entity1.dead && entity1.w(entity)) {
                    continue;
                }

                entity.stopRiding();
            }
            
            if (!entity.dead) {
                try {
                    this.updateEntity(entity);
                } catch (Throwable throwable) {
                    CrashReport crashreport = CrashReport.a(throwable, "Ticking player");
                    CrashReportSystemDetails crashreportsystemdetails = crashreport.a("Player being ticked");
                    
                    entity.appendEntityCrashDetails(crashreportsystemdetails);
                    throw new ReportedException(crashreport);
                }
            }

            if (entity.dead) {
                int cX = entity.ab;
                int cZ = entity.ad;
                
                if (entity.aa) {
                    this.applyIfChunkLoaded(cX, cZ, chunk -> chunk.b(entity));
                }

                this.entityList.remove(entity);
                this.onEntityRemove(entity);
            }
        } */
    }
    
    /**
     * Forcefully updates the entity
     */
    public void updateEntity(Entity entity) {
        this.entityJoinedWorld(entity, true);
    }
    
    /**
     * Updates the entity in the world if the chunk the entity is in is currently loaded or its forced to update
     */
    public void entityJoinedWorld(Entity entity, boolean forceUpdate) {
        if (forceUpdate && !ActivationRange.checkIfActive(entity)) {
            entity.ticksLived++;
            entity.inactiveTick();
        } else {
            entity.M = entity.locX; // lastTickPosX
            entity.N = entity.locY; // lastTickPosY
            entity.O = entity.locZ; // lastTickPosZ
            entity.lastYaw = entity.yaw;
            entity.lastPitch = entity.pitch;
            
            if (forceUpdate && entity.aa) {
                ++entity.ticksLived;
                ++co.aikar.timings.TimingHistory.activatedEntityTicks; // Paper
                if (entity.isPassenger()) {
                    entity.aw();
                } else {
                    entity.A_();
                    entity.postTick(); // CraftBukkit
                }
            }
            
            if (Double.isNaN(entity.locX) || Double.isInfinite(entity.locX)) {
                entity.locX = entity.M; // lastTickPosX
            }
            
            if (Double.isNaN(entity.locY) || Double.isInfinite(entity.locY)) {
                entity.locY = entity.N; // lastTickPosY
            }
            
            if (Double.isNaN(entity.locZ) || Double.isInfinite(entity.locZ)) {
                entity.locZ = entity.O; // lastTickPosZ
            }
            
            if (Double.isNaN(entity.pitch) || Double.isInfinite(entity.pitch)) {
                entity.pitch = entity.lastPitch;
            }
            
            if (Double.isNaN(entity.yaw) || Double.isInfinite(entity.yaw)) {
                entity.yaw = entity.lastYaw;
            }

            int coordX = MathHelper.floor(entity.locX / 16.0D);
            int coordY = Math.min(15, Math.max(0, MathHelper.floor(entity.locY / 16.0D))); // Paper - stay consistent with chunk add/remove behavior
            int coordZ = MathHelper.floor(entity.locZ / 16.0D);
            
            if (!entity.isAddedToChunk() || entity.ab != coordX || entity.ac != coordY || entity.ad != coordZ) {
                if (entity.isAddedToChunk()) {
                    this.applyIfChunkLoaded(entity.ab, entity.ad, chunk -> chunk.a(entity, entity.ac));
                }

                if (!entity.bv() && !this.isChunkLoaded(coordX, coordZ)) {
                    entity.aa = false; // PAIL: addedToChunk
                } else {
                    this.getChunkAt(coordX, coordZ).a(entity); // PAIL: addEntity
                }
            }
            
            if (forceUpdate && entity.isAddedToChunk()) {
                for (Entity passenger : entity.bx()) {
                    if (!passenger.dead && passenger.bB() == entity) {
                        this.updateEntity(passenger);
                    } else {
                        passenger.stopRiding();
                    }
                }
            }
            
        }
    }
    
    public boolean isChunkGeneratedAt(int cx, int cz) {
        return this.isChunkLoaded(cx, cz) ? true : this.chunkProvider.e(cx, cz);
    }
    
    /**
     * Sets the block state at a given location, flags can be added together,
     * <p>FLAG 1 will cause a block update,
     * <p>FLAG 2 will send the change to clients,
     * <p>FLAG 3 will cause a block update and also send the change to clients,
     * <p>FLAG 4 prevents the block from being re-rendered, if this is a client world
     */
    public boolean setTypeAndData(BlockPosition blockposition, IBlockData iblockdata, int flags) {
        if (this.captureTreeGeneration) {
            BlockState blockstate = null;
            Iterator<BlockState> it = capturedBlockStates.iterator();
            while (it.hasNext()) {
                BlockState previous = it.next();
                if (previous.getX() == blockposition.getX() && previous.getY() == blockposition.getY() && previous.getZ() == blockposition.getZ()) {
                    blockstate = previous;
                    it.remove();
                    break;
                }
            }
            if (blockstate == null) {
                blockstate = org.bukkit.craftbukkit.block.CraftBlockState.getBlockState(servant, blockposition.getX(), blockposition.getY(), blockposition.getZ(), flags);
            }
            blockstate.setTypeId(CraftMagicNumbers.getId(iblockdata.getBlock()));
            blockstate.setRawData((byte) iblockdata.getBlock().toLegacyData(iblockdata));
            this.capturedBlockStates.add(blockstate);
            return true;
        }
        
        if (blockposition.isInvalidYLocation()) { // Paper
            return false;
        } else if (this.worldData.getType() == WorldType.DEBUG_ALL_BLOCK_STATES) {
            return false;
        } else {
            Chunk chunk = this.getChunkAt(blockposition);

            // CraftBukkit start - capture blockstates
            BlockState blockstate = null;
            if (this.captureBlockStates) {
                blockstate = org.bukkit.craftbukkit.block.CraftBlockState.getBlockState(servant, blockposition.getX(), blockposition.getY(), blockposition.getZ(), flags);
                this.capturedBlockStates.add(blockstate);
            }
            // CraftBukkit end

            IBlockData iblockdata1 = chunk.a(blockposition, iblockdata);

            if (iblockdata1 == null) {
                if (this.captureBlockStates) {
                    this.capturedBlockStates.remove(blockstate);
                }
                
                return false;
            } else {
                if (iblockdata.c() != iblockdata1.c() || iblockdata.d() != iblockdata1.d()) {
                    chunk.lightingQueue.add(() -> checkLightAt(blockposition)); // Paper - Queue light update

                }

                if (!this.captureBlockStates) { // Don't notify clients or update physics while capturing blockstates
                    // Modularize client and physic updates
                    notifyAndUpdatePhysics(blockposition, chunk, iblockdata1, iblockdata, flags);
                }

                return true;
            }
        }
    }

    // CraftBukkit start - Split off from above in order to directly send client and physic updates
    public void notifyAndUpdatePhysics(BlockPosition blockposition, Chunk chunk, IBlockData oldBlock, IBlockData newBlock, int flags) {
        if ((flags & 2) != 0 && (chunk == null || chunk.isReady())) { // allow chunk to be null here as chunk.isReady() is false when we send our notification during block placement
            this.notifyBlockUpdate(blockposition, oldBlock, newBlock);
        }

        if ((flags & 1) != 0) {
            this.update(blockposition, oldBlock.getBlock(), true);
            if (newBlock.o()) {
                this.updateAdjacentComparators(blockposition, newBlock.getBlock());
            }
        } else if ((flags & 16) == 0) {
            this.updateObservingBlocksAt(blockposition, newBlock.getBlock());
        }
    }
    // CraftBukkit end

    public boolean setAir(BlockPosition blockposition) {
        return this.setTypeAndData(blockposition, Blocks.AIR.getBlockData(), 3);
    }

    public boolean setAir(BlockPosition blockposition, boolean drop) {
        IBlockData iblockdata = this.getType(blockposition);
        Block block = iblockdata.getBlock();

        if (iblockdata.getMaterial() == Material.AIR) {
            return false;
        } else {
            this.triggerEvent(2001, blockposition, Block.getCombinedId(iblockdata));
            if (drop) {
                block.b(servant, blockposition, iblockdata, 0); // PAIL: dropBlockAsItem
            }
            
            return this.setTypeAndData(blockposition, Blocks.AIR.getBlockData(), 3);
        }
    }

    public boolean setTypeUpdate(BlockPosition blockposition, IBlockData iblockdata) {
        return this.setTypeAndData(blockposition, iblockdata, 3);
    }

    public void update(BlockPosition blockposition, Block block, boolean flag) {
        if (this.worldData.getType() != WorldType.DEBUG_ALL_BLOCK_STATES) {
            // CraftBukkit start
            if (populating) return;
            // CraftBukkit end
            this.applyPhysics(blockposition, block, flag);
        }
    }
    
    /**
     * Marks a vertical line of blocks as dirty
     */
    public void markVerticalBlocksDirty(int x, int z, int k, int l) {
        int y;
        
        if (k > l) {
            y = l;
            l = k;
            k = y;
        }
        
        if (servant.worldProvider.m()) {
            for (y = k; y <= l; ++y) {
                this.checkLightFor(EnumSkyBlock.SKY, new BlockPosition(x, y, z));
            }
        }
    }

    public void updateObservingBlocksAt(BlockPosition blockposition, Block block) {
        this.observedNeighborChanged(blockposition.west(), block, blockposition);
        this.observedNeighborChanged(blockposition.east(), block, blockposition);
        this.observedNeighborChanged(blockposition.down(), block, blockposition);
        this.observedNeighborChanged(blockposition.up(), block, blockposition);
        this.observedNeighborChanged(blockposition.north(), block, blockposition);
        this.observedNeighborChanged(blockposition.south(), block, blockposition);
    }

    public void applyPhysics(BlockPosition blockposition, Block block, boolean updateObservers) {
        if (captureBlockStates) return; // Paper - Cancel all physics during placement
        this.neighborChanged(blockposition.west(), block, blockposition);
        this.neighborChanged(blockposition.east(), block, blockposition);
        this.neighborChanged(blockposition.down(), block, blockposition);
        this.neighborChanged(blockposition.up(), block, blockposition);
        this.neighborChanged(blockposition.north(), block, blockposition);
        this.neighborChanged(blockposition.south(), block, blockposition);
        if (updateObservers) {
            this.updateObservingBlocksAt(blockposition, block);
        }
    }

    public void applyPhysicsExcept(BlockPosition blockposition, Block block, EnumDirection enumdirection) {
        if (enumdirection != EnumDirection.WEST) {
            this.neighborChanged(blockposition.west(), block, blockposition);
        }

        if (enumdirection != EnumDirection.EAST) {
            this.neighborChanged(blockposition.east(), block, blockposition);
        }

        if (enumdirection != EnumDirection.DOWN) {
            this.neighborChanged(blockposition.down(), block, blockposition);
        }

        if (enumdirection != EnumDirection.UP) {
            this.neighborChanged(blockposition.up(), block, blockposition);
        }

        if (enumdirection != EnumDirection.NORTH) {
            this.neighborChanged(blockposition.north(), block, blockposition);
        }

        if (enumdirection != EnumDirection.SOUTH) {
            this.neighborChanged(blockposition.south(), block, blockposition);
        }
    }

    public void neighborChanged(BlockPosition blockposition, final Block block, BlockPosition blockposition1) {
        IBlockData iblockdata = this.getType(blockposition);

        try {
            CraftWorld world = ((WorldServer) servant).getWorld();
            if (world != null && !((WorldServer) servant).stopPhysicsEvent) { // Paper
                BlockPhysicsEvent event = BlockPhysicsEvent.of(world.getBlockAt(blockposition.getX(), blockposition.getY(), blockposition.getZ()), CraftMagicNumbers.getId(block));
                this.getCraftServer().getPluginManager().callEvent(event);

                if (event.isCancelled()) {
                    return;
                }
            }
            
            iblockdata.doPhysics(servant, blockposition, block, blockposition1);
        } catch (StackOverflowError stackoverflowerror) { // Spigot Start
            haveWeSilencedAPhysicsCrash = true;
            blockLocation = blockposition.getX() + ", " + blockposition.getY() + ", " + blockposition.getZ();
            // Spigot End
        } catch (Throwable t) {
            CrashReport report = CrashReport.a(t, "Exception while updating neighbours");
            CrashReportSystemDetails details = report.a("Block being updated");

            details.a("Source block type", new CrashReportCallable<String>() {
                @Override
                public String call() throws Exception {
                    try {
                        return String.format("ID #%d (%s // %s)", new Object[] { Integer.valueOf(Block.getId(block)), block.a(), block.getClass().getCanonicalName()});
                    } catch (Throwable throwable) {
                        return "ID #" + Block.getId(block);
                    }
                }
            });
            CrashReportSystemDetails.a(details, blockposition, iblockdata);
            throw new ReportedException(report);
        }
    }

    public void observedNeighborChanged(BlockPosition blockposition, final Block block, BlockPosition blockposition1) {
        IBlockData iblockdata = this.getType(blockposition);

        if (iblockdata.getBlock() == Blocks.dk) {
            try {
                ((BlockObserver) iblockdata.getBlock()).b(iblockdata, servant, blockposition, block, blockposition1);
            } catch (Throwable t) {
                CrashReport report = CrashReport.a(t, "Exception while updating neighbours");
                CrashReportSystemDetails details = report.a("Block being updated");

                details.a("Source block type", new CrashReportCallable<String>() {
                    @Override
                    public String call() throws Exception {
                        try {
                            return String.format("ID #%d (%s // %s)", new Object[] { Integer.valueOf(Block.getId(block)), block.a(), block.getClass().getCanonicalName()});
                        } catch (Throwable throwable) {
                            return "ID #" + Block.getId(block);
                        }
                    }
                });
                CrashReportSystemDetails.a(details, blockposition, iblockdata);
                throw new ReportedException(report);
            }
        }
    }

    public boolean canSeeSkyAt(BlockPosition blockposition) {
        return this.getChunkAt(blockposition).c(blockposition);
    }

    public boolean canActualSeeSkyAt(BlockPosition blockposition) {
        if (blockposition.getY() >= seaLevel) {
            return this.canSeeSkyAt(blockposition);
        } else {
            BlockPosition blockposition1 = new BlockPosition(blockposition.getX(), seaLevel, blockposition.getZ());
            
            if (!this.canSeeSkyAt(blockposition1)) {
                return false;
            } else {
                for (blockposition1 = blockposition1.down(); blockposition1.getY() > blockposition.getY(); blockposition1 = blockposition1.down()) {
                    IBlockData iblockdata = this.getType(blockposition1);

                    if (iblockdata.c() > 0 && !iblockdata.getMaterial().isLiquid()) {
                        return false;
                    }
                }
                
                return true;
            }
        }
    }

    // Paper start - test if meets light level, return faster
    // logic copied from below
    public boolean isLightLevel(BlockPosition blockposition, int level) {
        if (blockposition.isValidLocation()) {
            if (this.getType(blockposition).f()) {
                if (this.getLightLevelAt(blockposition.up(), false) >= level) {
                    return true;
                }
                if (this.getLightLevelAt(blockposition.east(), false) >= level) {
                    return true;
                }
                if (this.getLightLevelAt(blockposition.west(), false) >= level) {
                    return true;
                }
                if (this.getLightLevelAt(blockposition.south(), false) >= level) {
                    return true;
                }
                if (this.getLightLevelAt(blockposition.north(), false) >= level) {
                    return true;
                }
                return false;
            } else {
                if (blockposition.getY() >= 256) {
                    blockposition = new BlockPosition(blockposition.getX(), 255, blockposition.getZ());
                }
                
                Chunk chunk = this.getChunkAt(blockposition);
                return chunk.a(blockposition, this.skylightSubtracted) >= level;
            }
        } else {
            return true;
        }
    }
    // Paper end
    
    public int getUnsubtractedLight(BlockPosition blockposition) {
        if (blockposition.getY() < 0) {
            return 0;
        } else {
            if (blockposition.getY() >= 256) {
                blockposition = new BlockPosition(blockposition.getX(), 255, blockposition.getZ());
            }
            
            Chunk chunk = this.getChunkAt(blockposition);
            return chunk.a(blockposition, 0); // PAIL: getLightSubtracted
        }
    }
    
    public int getLightLevelFromNeighbors(BlockPosition blockposition) {
        return this.getLightLevelAt(blockposition, true);
    }
    
    public int getLightLevelAt(BlockPosition pos, boolean checkNeighbors) {
        if (pos.getX() >= -30000000 && pos.getZ() >= -30000000 && pos.getX() < 30000000 && pos.getZ() < 30000000) {
            if (checkNeighbors && this.getType(pos).f()) { // PAIL: f -> useNeighborBrightness
                int level = this.getLightLevelAt(pos.up(), false);
                int eastLevel = this.getLightLevelAt(pos.east(), false);
                int westLevel = this.getLightLevelAt(pos.west(), false);
                int southLevel = this.getLightLevelAt(pos.south(), false);
                int northLevel = this.getLightLevelAt(pos.north(), false);
                
                if (eastLevel > level) {
                    level = eastLevel;
                }

                if (westLevel > level) {
                    level = westLevel;
                }

                if (southLevel > level) {
                    level = southLevel;
                }

                if (northLevel > level) {
                    level = northLevel;
                }
                
                return level;
            } else if (pos.getY() < 0) {
                return 0;
            } else {
                if (pos.getY() >= 256) {
                    pos = new BlockPosition(pos.getX(), 255, pos.getZ());
                }
                
                Chunk chunk = this.getChunkIfLoaded(pos);
                if (chunk == null) {
                    return 0;
                } else {
                    return chunk.a(pos, this.skylightSubtracted); // PAIL: getLightSubtracted
                }
            }
        } else {
            return 15;
        }
    }
    
    /**
     * Gets the lowest height of the chunk where sunlight directly reaches
     */
    @Deprecated
    public int getChunksLowestHorizon(int i, int j) {
        if (i >= -30000000 && j >= -30000000 && i < 30000000 && j < 30000000) {
            Chunk chunk = this.getChunkIfLoaded(i >> 4, j >> 4);
            
            return chunk == null ? 0 : chunk.w(); // PAIL: getLowestHeight
        } else {
            return this.seaLevel + 1;
        }
    }

    public int getBrightness(EnumSkyBlock lightType, BlockPosition pos) {
        if (pos.getY() < 0) {
            pos = new BlockPosition(pos.getX(), 0, pos.getZ());
        }
        
        Chunk chunk;
        if (!pos.isValidLocation()) { // Paper
            return lightType.c;
        } else if ((chunk = this.getChunkIfLoaded(pos)) == null) {
            return lightType.c;
        } else {
            return chunk.getBrightness(lightType, pos);
        }
    }

    public void setLightFor(EnumSkyBlock lightType, BlockPosition position, int lightValue) {
        if (position.isValidLocation()) { // Paper
            this.applyIfChunkLoaded(position, chunk -> chunk.a(lightType, position, lightValue));
        }
    }
    
    public float getLightBrightness(BlockPosition position) {
        return servant.worldProvider.o()[this.getLightLevelFromNeighbors(position)];
    }
    
    public IBlockData getTypeIfLoaded(BlockPosition position) {
        // CraftBukkit start - tree generation
        final int x = position.getX();
        final int y = position.getY();
        final int z = position.getZ();
        if (captureTreeGeneration) {
            final IBlockData previous = getCapturedBlockType(x, y, z);
            if (previous != null) {
                return previous;
            }
        }
        // CraftBukkit end
        Chunk chunk = this.getChunkIfLoaded(position);
        if (chunk != null) {
            return chunk.getBlockData(x, y, z);
        }
        
        return null;
    }
    
    /**
     * Returns true if there are any blocks in the region constrained by an AxisAlignedBB
     */
    public boolean containsBlock(AxisAlignedBB aabb) {
        int minX = MathHelper.floor(aabb.a);
        int maxX = MathHelper.ceil(aabb.d);
        int minY = MathHelper.floor(aabb.b);
        int maxY = MathHelper.ceil(aabb.e);
        int minZ = MathHelper.floor(aabb.c);
        int maxZ = MathHelper.ceil(aabb.f);
        
        try (PooledBlockPosition position = PooledBlockPosition.aquire()) {
            for (int x = minX; x < maxX; x++) {
                for (int y = minY; y < maxY; y++) {
                    for (int z = minZ; z < maxZ; z++) {
                        IBlockData iblockdata = this.getType(position.f(x, y, z));
                        
                        if (iblockdata.getMaterial() != Material.AIR) {
                            return true;
                        }
                    }
                }
            }
            
            return false;
        }
    }
    
    /**
     * Checks if any of the blocks within the aabb are liquids
     */
    public boolean containsLiquid(AxisAlignedBB aabb) {
        int minX = MathHelper.floor(aabb.a);
        int maxX = MathHelper.ceil(aabb.d);
        int minY = MathHelper.floor(aabb.b);
        int maxY = MathHelper.ceil(aabb.e);
        int minZ = MathHelper.floor(aabb.c);
        int maxZ = MathHelper.ceil(aabb.f);
        
        try (PooledBlockPosition position = PooledBlockPosition.aquire()) {
            for (int x = minX; x < maxX; x++) {
                for (int y = minY; y < maxY; y++) {
                    for (int z = minZ; z < maxZ; z++) {
                        IBlockData iblockdata = this.getType(position.f(x, y, z));

                        if (iblockdata.getMaterial().isLiquid()) {
                            return true;
                        }
                    }
                }
            }
            
            return false;
        }
    }
    
    /**
     * Checks if any of the blocks within the aabb are fire or lava
     */
    public boolean containsFlammable(AxisAlignedBB aabb) {
        int minX = MathHelper.floor(aabb.a);
        int maxX = MathHelper.ceil(aabb.d);
        int minY = MathHelper.floor(aabb.b);
        int maxY = MathHelper.ceil(aabb.e);
        int minZ = MathHelper.floor(aabb.c);
        int maxZ = MathHelper.ceil(aabb.f);
        
        if (this.isAreaLoaded(minX, minY, minZ, maxX, maxY, maxZ)) {
            try (PooledBlockPosition position = PooledBlockPosition.aquire()) {
                for (int x = minX; x < maxX; x++) {
                    for (int y = minY; y < maxY; y++) {
                        for (int z = minZ; z < maxZ; z++) {
                            Block block = this.getType(position.f(x, y, z)).getBlock();
                            
                            if (block == Blocks.FIRE || block == Blocks.FLOWING_LAVA || block == Blocks.LAVA) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        
        return false;
    }
    
    /**
     * Handles the acceleration of an object while in water
     */
    public boolean handleMaterialAcceleration(AxisAlignedBB aabb, Material material, Entity entity) {
        int minX = MathHelper.floor(aabb.a);
        int maxX = MathHelper.ceil(aabb.d);
        int minY = MathHelper.floor(aabb.b);
        int maxY = MathHelper.ceil(aabb.e);
        int minZ = MathHelper.floor(aabb.c);
        int maxZ = MathHelper.ceil(aabb.f);
        
        if (!this.isAreaLoaded(minX, minY, minZ, maxX, maxY, maxZ)) {
            return false;
        } else {
            boolean flag = false;
            Vec3D vec3D = Vec3D.a;
            
            try (PooledBlockPosition position = PooledBlockPosition.aquire()) {
                for (int x = minX; x < maxX; x++) {
                    for (int y = minY; y < maxY; y++) {
                        for (int z = minZ; z < maxZ; z++) {
                            IBlockData block = this.getType(position.f(x, y, z));
                            
                            if (block.getMaterial() == material) {
                                double d0 = y + 1 - BlockFluids.e(block.get(BlockFluids.LEVEL).intValue());

                                if (maxY >= d0) {
                                    flag = true;
                                    vec3D = (block.getBlock()).a(servant, position, entity, vec3D);
                                }
                            }
                        }
                    }
                }
            }
            
            if (vec3D.b() > 0.0D && entity.bg()) {
                vec3D = vec3D.a();
                
                entity.motX += vec3D.x * 0.014D;
                entity.motY += vec3D.y * 0.014D;
                entity.motZ += vec3D.z * 0.014D;
            }
            
            return flag;
        }
    }
    
    /**
     * Returns true if the given bounding box contains the given material
     */
    public boolean containsMaterial(AxisAlignedBB aabb, Material material) {
        int minX = MathHelper.floor(aabb.a);
        int maxX = MathHelper.ceil(aabb.d);
        int minY = MathHelper.floor(aabb.b);
        int maxY = MathHelper.ceil(aabb.e);
        int minZ = MathHelper.floor(aabb.c);
        int maxZ = MathHelper.ceil(aabb.f);
        
        try (PooledBlockPosition position = PooledBlockPosition.aquire()) {
            for (int x = minX; x < maxX; x++) {
                for (int y = minY; y < maxY; y++) {
                    for (int z = minZ; z < maxZ; z++) {
                        if (this.getType(position.f(x, y, z)).getMaterial() == material) {
                            return true;
                        }
                    }
                }
            }
            
            return false;
        }
    }
    
    /**
     * Creates an explosion in the world
     */
    public Explosion explode(@Nullable Entity entity, double x, double y, double z, float strength, boolean smoking) {
        return this.createExplosion(entity, x, y, z, strength, false, smoking);
    }
    
    public Explosion createExplosion(@Nullable Entity entity, double x, double y, double z, float strength, boolean flaming, boolean smoking) {
        Explosion explosion = new Explosion(servant, entity, z, z, z, strength, flaming, smoking);

        explosion.a();
        explosion.a(true);
        
        return explosion;
    }
    
    /**
     * Attempts to extinguish a fire
     */
    public boolean douseFire(@Nullable EntityHuman player, BlockPosition position, EnumDirection direction) {
        position = position.shift(direction);
        if (this.getType(position).getBlock() == Blocks.FIRE) {
            this.playWorldEventNearbyExpect(player, 1009, position, 0);
            this.setAir(position);
            return true;
        } else {
            return false;
        }
    }
    
    /**
     * Find the ground block above sea level, the search starting from the sea level height (63 vanilla)
     */
    public IBlockData findGroundAboveSeaLevel(BlockPosition position) {
        BlockPosition level = new BlockPosition(position.getX(), seaLevel, position.getZ());
        
        while (!this.isEmpty(level = level.up())) {
            ;
        }
        
        return this.getType(level);
    }

    /**
     * Checks to see if an air block exists at the provided location. Note that this only checks to see if the blocks
     * material is set to air, meaning it is possible for non-vanilla blocks to still pass this check.
     */
    @Override
    public boolean isEmpty(BlockPosition position) {
        return getType(position).getMaterial() == Material.AIR;
    }
    
    /**
     * Checks to see if an water block exists at the provided location. Note that this only checks to see if the blocks
     * material is set to water, meaning it is possible for non-vanilla blocks to still pass this check.
     */
    public boolean isWater(BlockPosition position) {
        return getType(position).getMaterial() == Material.WATER;
    }

    /**
     * Check if chunk of the given block position is loaded
     */
    public boolean isChunkLoaded(BlockPosition blockPos) {
        return ((ChunkProviderServer) this.chunkProvider).isLoaded(blockPos.getX() >> 4, blockPos.getZ() >> 4);
    }
    
    /**
     * Check if chunk of the given chunkX, chunkZ is loaded
     */
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        return ((ChunkProviderServer) this.chunkProvider).isLoaded(chunkX, chunkZ);
    }
    
    public ChunkProviderServer getChunkProviderServer() {
        return (ChunkProviderServer) this.chunkProvider;
    }
    
    /** The range is in square */
    public boolean areChunksLoaded(BlockPosition center, int range) {
        return this.areChunksLoaded(center, range, true);
    }
    
    public boolean areChunksLoaded(BlockPosition center, int range, boolean allowEmpty) {
        return this.isAreaLoaded(center.getX() - range, center.getY() - range, center.getZ() - range, center.getX() + range, center.getY() + range, center.getZ() + range);
    }
    
    public boolean areChunksLoadedBetween(BlockPosition startPos, BlockPosition endPos) {
        return this.isAreaLoaded(startPos.getX(), startPos.getY(), startPos.getZ(), endPos.getX(), endPos.getY(), endPos.getZ());
    }
    
    /**
     * Check if chunks between the given x, z are all loaded
     */
    public boolean isAreaLoaded(int xStart, int yStart, int zStart, int xEnd, int yEnd, int zEnd) {
        if (yEnd < 0 || yStart >= 256) return false;
        // Divide by 2^4, convert to chunk x, z
        xStart >>= 4;
        zStart >>= 4;
        xEnd >>= 4;
        zEnd >>= 4;
        
        for (int cX = xStart; cX <= xEnd; cX++) {
            for (int cZ = zStart; cZ <= zEnd; cZ++) {
                if (!this.isChunkLoaded(cX, cZ)) {
                    return false;
                }
            }
        }
        
        return true;
    }
    
    public BiomeBase getBiomeAt(BlockPosition position) {
        Chunk chunk = this.getChunkIfLoaded(position);
        
        if (chunk != null) {
            try {
                return chunk.getBiome(position, servant.worldProvider.k());
            } catch (Throwable t) {
                CrashReport report = CrashReport.a(t, "Getting biome");
                CrashReportSystemDetails details = report.a("Coordinates of biome request");

                details.a("Location", new CrashReportCallable<String>() {
                    @Override
                    public String call() throws Exception {
                        return CrashReportSystemDetails.a(position);
                    }
                });

                throw new ReportedException(report);
            }
        } else {
            return servant.worldProvider.k().getBiome(position, Biomes.c);
        }
    }
    
    public Chunk getChunkAt(BlockPosition position) {
        return this.getChunkAt(position.getX() >> 4, position.getZ() >> 4); // Divide by 2^4, convert to chunk x, z
    }
    
    public Chunk getChunkAt(int chunkX, int chunkZ) {
        return this.chunkProvider.getChunkAt(chunkX, chunkZ);
    }
    
    public void notifyBlockUpdate(BlockPosition position, IBlockData oldBlock, IBlockData newBlock) {
        for (IWorldAccess access : worldListeners) {
            access.a(servant, position, oldBlock, newBlock, 999); // notifyBlockUpdate
        }
    }
    
    @Async
    public void sendBlockBreakProgress(int breakerEntityId, BlockPosition position, int progress) {
        for (IWorldAccess access : worldListeners) {
            access.b(breakerEntityId, position, progress); // sendBlockBreakProgress
        }
    }
    
    /**
     * Puts the world random seed to a specific state dependant on the inputs
     */
    public Random setRandomSeed(int x, int z, int extra) {
        long seed = x * 341873128712L + z * 132897987541L + worldData.getSeed() + extra;
        
        this.random.setSeed(seed);
        return this.random;
    }
    
    /**
     * Assigns the given String id to the given PersistentBase using the worldMaps, removing any existing ones of the same id
     */
    public void setMapData(String dataId, PersistentBase savedData) {
        servant.worldMaps.a(dataId, savedData);
    }
    
    /**
     * Loads an existing PersistentBase corresponding to the given String id from disk using the worldMaps,
     * instantiating the given Class, or returns null if none such file exists.
     */
    @Nullable
    public PersistentBase loadMapData(Class<? extends PersistentBase> clazz, String dataId) {
        return servant.worldMaps.get(clazz, dataId);
    }
    
    /**
     * Returns an unique new data id from the worldMaps for the given prefix and saves the idCounts map to the 'idcounts' file
     */
    public int getUniqueDataId(String key) {
        return servant.worldMaps.a(key); // PAIL: getUniqueDataId
    }
    
    @Async
    public void triggerEvent(int type, BlockPosition position, int data) {
        this.playWorldEventNearbyExpect(null, type, position, data);
    }
    
    @Async
    public void playWorldEventNearbyExpect(@Nullable EntityHuman expect, int type, BlockPosition position, int data) {
        try {
            if (expect == null) {
                for (IWorldAccess access : worldListeners) {
                    access.a(type, position, data); // playWorldEvent
                }
            } else {
                for (IWorldAccess access : worldListeners) {
                    access.a(expect, type, position, data); // playWorldEventNearbyExpect
                }
            }
            
        } catch (Throwable t) {
            CrashReport report = CrashReport.a(t, "Playing level event");
            CrashReportSystemDetails details = report.a("Level event being played");

            details.a("Block coordinates", CrashReportSystemDetails.a(position));
            details.a("Event source", expect);
            details.a("Event type", Integer.valueOf(type));
            details.a("Event data", Integer.valueOf(data));
            
            throw new ReportedException(report);
        }
    }
    
    /**
     * Adds some basic stats of the world to the given crash report
     */
    public CrashReportSystemDetails addInfoToCrashReport(CrashReport report) {
        CrashReportSystemDetails details = report.a("Affected level", 1);
        
        details.a("Level name", this.worldData == null ? "????" : this.worldData.getName());
        details.a("All players", new CrashReportCallable<String>() {
            @Override
            public String call() throws Exception {
                return players.size() + " total; " + players;
            }
        });
        details.a("Chunk stats", new CrashReportCallable<String>() {
            @Override
            public String call() throws Exception {
                return chunkProvider.getName();
            }
        });
        
        try {
            this.worldData.a(details);
        } catch (Throwable t) {
            details.a("Level Data Unobtainable", t);
        }
        
        return details;
    }
    
    /**
     * Gets the percentage of real blocks within within a bounding box, along a specified vector.
     */
    public float getBlockDensity(Vec3D vec, AxisAlignedBB box) {
        double stepX = 1.0D / ((box.d - box.a) * 2.0D + 1.0D);
        double stepY = 1.0D / ((box.e - box.b) * 2.0D + 1.0D);
        double stepZ = 1.0D / ((box.f - box.c) * 2.0D + 1.0D);
        double offsetX = (1.0D - Math.floor(1.0D / stepX) * stepX) / 2.0D;
        double offsetZ = (1.0D - Math.floor(1.0D / stepZ) * stepZ) / 2.0D;

        if (stepX >= 0.0D && stepY >= 0.0D && stepZ >= 0.0D) {
            int noBlockLines = 0;
            int totalLines = 0;
            
            for (float tX = 0.0F; tX <= 1.0F; tX = (float) (tX + stepX)) {
                for (float tY = 0.0F; tY <= 1.0F; tY = (float) (tY + stepY)) {
                    for (float tZ = 0.0F; tZ <= 1.0F; tZ = (float) (tZ + stepZ)) {
                        double x = box.a + (box.d - box.a) * tX;
                        double y = box.b + (box.e - box.b) * tY;
                        double z = box.c + (box.f - box.c) * tZ;
                        
                        if (this.rayTraceBlocks(new Vec3D(x + offsetX, y, z + offsetZ), vec) == null) {
                            noBlockLines++;
                        }

                        totalLines++;
                    }
                }
            }
            
            return (float) noBlockLines / (float) totalLines;
        } else {
            return 0.0F;
        }
    }
    
    public boolean shouldStayLoaded(int chunkX, int chunkZ) {
        BlockPosition spawnPos = this.getSpawn();
        int offsetX = chunkX << 4 + 8 - spawnPos.getX();
        int offsetY = chunkZ << 4 + 8 - spawnPos.getZ();
        
        short keepLoadedRange = paperConfig.keepLoadedRange;
        return offsetX >= -keepLoadedRange && offsetX <= keepLoadedRange && offsetY >= -keepLoadedRange && offsetY <= keepLoadedRange && keepSpawnInMemory;
    }
    
    @Nullable
    public EntityHuman findNearestAttackablePlayer(Entity entity, double maxXZDistance, double maxYDistance) {
        return findNearestAttackablePlayer(entity.locX, entity.locY, entity.locZ, maxXZDistance, maxYDistance, null, null);
    }

    @Nullable
    public EntityHuman findNearestAttackablePlayer(BlockPosition blockposition, double maxXZDistance, double maxYDistance) {
        return findNearestAttackablePlayer(blockposition.getX() + 0.5F, blockposition.getY() + 0.5F, blockposition.getZ() + 0.5F, maxXZDistance, maxYDistance, null, null);
    }
    
    @Nullable
    public EntityHuman findNearestAttackablePlayer(double x, double y, double z, double maxXZDistance, double maxYDistance, @Nullable Function<EntityHuman, Double> playerToDouble, @Nullable Predicate<EntityHuman> filter) {
        double maxOffset = -1.0D;
        
        for (EntityHuman player : this.players) {
            if (player != null && !player.abilities.isInvulnerable) {
                if (!player.isAlive() || player.isSpectator()) continue;
                if (filter != null && !filter.apply(player)) continue;
                
                double offsetSq = player.getOffsetSq(x, player.locY, z);
                double maxXZ = maxXZDistance;
                
                if (player.isSneaking()) {
                    maxXZ = maxXZDistance * 0.800000011920929D;
                }
                
                if (player.isInvisible()) {
                    float armor = player.cO(); // PAIL: getArmorVisibility
                    
                    if (armor < 0.1F) armor = 0.1F;
                    maxXZ *= 0.7F * armor;
                }
                
                if (playerToDouble != null) {
                    maxXZ *= Objects.firstNonNull(playerToDouble.apply(player), Double.valueOf(1.0D)).doubleValue();
                }
                
                if ((maxYDistance < 0.0D || Math.abs(player.locY - y) < maxYDistance * maxYDistance) && (maxXZDistance < 0.0D || offsetSq < maxXZ * maxXZ) && (maxOffset == -1.0D || offsetSq < maxOffset)) {
                    return player;
                }
            }
        }
        
        return null;
    }
    
    /**
     * Checks whether its daytime by seeing if the light subtracted from the skylight is less than 4
     */
    public boolean isDayTime() {
        return this.skylightSubtracted < 4;
    }

    /**
     * Ray traces all blocks, including non-collideable ones
     */
    @Nullable
    public MovingObjectPosition rayTraceBlocks(Vec3D start, Vec3D end) {
        return rayTraceBlocks(start, end, false, false, false);
    }

    @Nullable
    public MovingObjectPosition rayTraceBlocks(Vec3D start, Vec3D end, boolean stopOnLiquid) {
        return rayTraceBlocks(start, end, stopOnLiquid, false, false);
    }

    /**
     * Performs a raycast against all blocks in the world
     */
    @Nullable
    public MovingObjectPosition rayTraceBlocks(Vec3D start, Vec3D end, boolean flag, boolean flag1, boolean flag2) {
        if (Double.isNaN(start.x) || Double.isNaN(start.y) || Double.isNaN(start.z)) return null;
        if (Double.isNaN(end.x) || Double.isNaN(end.y) || Double.isNaN(end.z)) return null;
        
        int i = MathHelper.floor(end.x);
        int j = MathHelper.floor(end.y);
        int k = MathHelper.floor(end.z);
        int l = MathHelper.floor(start.x);
        int i1 = MathHelper.floor(start.y);
        int j1 = MathHelper.floor(start.z);
        BlockPosition blockposition = new BlockPosition(l, i1, j1);
        IBlockData iblockdata = this.getType(blockposition);
        Block block = iblockdata.getBlock();

        if ((!flag1 || iblockdata.c(this, blockposition) != Block.k) && block.a(iblockdata, flag)) {
            MovingObjectPosition movingobjectposition = iblockdata.a(servant, blockposition, start, end);

            if (movingobjectposition != null) {
                return movingobjectposition;
            }
        }

        MovingObjectPosition movingobjectposition1 = null;
        int k1 = 200;

        while (k1-- >= 0) {
            if (Double.isNaN(start.x) || Double.isNaN(start.y) || Double.isNaN(start.z)) {
                return null;
            }

            if (l == i && i1 == j && j1 == k) {
                return flag2 ? movingobjectposition1 : null;
            }

            boolean flag3 = true;
            boolean flag4 = true;
            boolean flag5 = true;
            double d0 = 999.0D;
            double d1 = 999.0D;
            double d2 = 999.0D;

            if (i > l) {
                d0 = l + 1.0D;
            } else if (i < l) {
                d0 = l + 0.0D;
            } else {
                flag3 = false;
            }

            if (j > i1) {
                d1 = i1 + 1.0D;
            } else if (j < i1) {
                d1 = i1 + 0.0D;
            } else {
                flag4 = false;
            }

            if (k > j1) {
                d2 = j1 + 1.0D;
            } else if (k < j1) {
                d2 = j1 + 0.0D;
            } else {
                flag5 = false;
            }

            double d3 = 999.0D;
            double d4 = 999.0D;
            double d5 = 999.0D;
            double d6 = end.x - start.x;
            double d7 = end.y - start.y;
            double d8 = end.z - start.z;

            if (flag3) {
                d3 = (d0 - start.x) / d6;
            }

            if (flag4) {
                d4 = (d1 - start.y) / d7;
            }

            if (flag5) {
                d5 = (d2 - start.z) / d8;
            }

            if (d3 == -0.0D) {
                d3 = -1.0E-4D;
            }

            if (d4 == -0.0D) {
                d4 = -1.0E-4D;
            }

            if (d5 == -0.0D) {
                d5 = -1.0E-4D;
            }

            EnumDirection enumdirection;

            if (d3 < d4 && d3 < d5) {
                enumdirection = i > l ? EnumDirection.WEST : EnumDirection.EAST;
                start = new Vec3D(d0, start.y + d7 * d3, start.z + d8 * d3);
            } else if (d4 < d5) {
                enumdirection = j > i1 ? EnumDirection.DOWN : EnumDirection.UP;
                start = new Vec3D(start.x + d6 * d4, d1, start.z + d8 * d4);
            } else {
                enumdirection = k > j1 ? EnumDirection.NORTH : EnumDirection.SOUTH;
                start = new Vec3D(start.x + d6 * d5, start.y + d7 * d5, d2);
            }

            l = MathHelper.floor(start.x) - (enumdirection == EnumDirection.EAST ? 1 : 0);
            i1 = MathHelper.floor(start.y) - (enumdirection == EnumDirection.UP ? 1 : 0);
            j1 = MathHelper.floor(start.z) - (enumdirection == EnumDirection.SOUTH ? 1 : 0);
            blockposition = new BlockPosition(l, i1, j1);
            IBlockData iblockdata1 = this.getType(blockposition);
            Block block1 = iblockdata1.getBlock();

            if (!flag1 || iblockdata1.getMaterial() == Material.PORTAL || iblockdata1.c(this, blockposition) != Block.k) {
                if (block1.a(iblockdata1, flag)) {
                    MovingObjectPosition movingobjectposition2 = iblockdata1.a(servant, blockposition, start, end); // PAIL: collisionRayTrace

                    if (movingobjectposition2 != null) {
                        return movingobjectposition2;
                    }
                } else {
                    movingobjectposition1 = new MovingObjectPosition(MovingObjectPosition.EnumMovingObjectType.MISS, start, enumdirection, blockposition);
                }
            }
        }

        return flag2 ? movingobjectposition1 : null;
    }
    
    public void onEntityAdded(Entity entity) {
        for (IWorldAccess access : worldListeners) {
            access.a(entity); // onEntityAdded
        }
        
        entity.valid = true;
        EntityAddToWorldEvent.of(entity.getBukkitEntity()).callEvent(); // Paper
    }

    public void onEntityRemove(Entity entity) {
        for (IWorldAccess access : worldListeners) {
            access.b(entity); // onEntityRemoved
        }
        
        EntityRemoveFromWorldEvent.of(entity.getBukkitEntity()).callEvent(); // Paper
        entity.valid = false;
    }
    
    /**
     * Add a world event listener
     */
    public void addWorldListener(IWorldAccess access) {
        this.worldListeners.add(access);
    }
    
    @Async
    public void playSoundNearbyExpect(@Nullable EntityHuman expect, BlockPosition pos, SoundEffect effect, SoundCategory category, float volume, float pitch) {
        playSoundNearbyExpect(expect, effect, category, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, volume, pitch);
    }
    
    @Async
    public void playSoundNearbyExpect(@Nullable EntityHuman expect, SoundEffect effect, SoundCategory category, double x, double y, double z, float volume, float pitch) {
        for (IWorldAccess access : worldListeners) {
            access.a(expect, effect, category, x, y, z, volume, pitch); // playSoundNearbyExpect
        }
    }
    
    /**
     * Get an entity by its entity id
     */
    @Nullable
    public Entity getEntity(int entityId) {
        return this.entitiesById.get(entityId);
    }
    
    /**
     * Add all entities the given collection containing
     */
    public void addEntities(Collection<Entity> entities) {
        AsyncCatcher.catchOp("entity world add");
        
        for (Entity entity : entities) {
            if (entity != null) continue;
            
            this.entityList.add(entity);
            this.onEntityAdded(entity);
        }
    }
    
    /**
     * Unload all entities the given collection containing
     */
    public void unloadEntities(Collection<Entity> entities) {
        this.unloadedEntities.addAll(entities);
    }
    
    /**
     * Gets the closest player to the entity within the specified distance, also check its gamemode
     */
    @Nullable
    public EntityHuman findClosestPlayer(Entity entity, double range, boolean notCreative) {
        return this.findClosestPlayer(entity.locX, entity.locY, entity.locZ, range, notCreative);
    }

    @Nullable
    public EntityHuman findClosestPlayer(double x, double y, double z, double range, boolean notCreative) {
        Predicate<Entity> filter = notCreative ? IEntitySelector.d : IEntitySelector.e; // PAIL: d -> NOT_CREATIVE_MODE, e -> NOT_SPECTATING
        return this.findClosestPlayer(x, y, z, range, filter);
    }
    
    @Nullable
    public EntityHuman findClosestPlayer(double x, double y, double z, double range, Predicate<Entity> filter) {
        double minSq = -1.0D;
        EntityHuman closest = null;
        
        for (EntityHuman player : this.players) {
            if (filter != null && !filter.apply(player)) continue;
            if (player == null || player.dead) continue; // CraftBukkit - fixed an NPE
            
            double sq = player.getOffsetSq(x, y, z);
            if ((range < 0.0D || sq < range * range) && (minSq == -1.0D || sq < minSq)) {
                minSq = sq;
                closest = player;
            }
        }
        
        return closest;
    }
    
    public boolean isPlayerNearby(double x, double y, double z, double range) {
        if (range < 0.0D) return true;
        
        for (EntityHuman player : this.players) {
            if (!player.isSpectator() && player.affectsSpawning) { // Paper - Affects Spawning API
                double sq = player.getOffsetSq(x, y, z);
                
                if (sq < range * range) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Checks whether the session file was modified by another process
     */
    public void checkSession() throws ExceptionWorldConflict {
        this.dataManager.checkSession();
    }
    
    public long getSeed() {
        return this.worldData.getSeed();
    }

    public long getTime() {
        return this.worldData.getTime();
    }

    public long getDayTime() {
        return this.worldData.getDayTime();
    }

    public void setDayTime(long timeTick) {
        this.worldData.setDayTime(timeTick);
    }
    
    public EnumDifficulty getDifficulty() {
        return this.worldData.getDifficulty();
    }
    
    public BlockPosition getSpawn() {
        BlockPosition spawnPos = new BlockPosition(this.worldData.b(), this.worldData.c(), this.worldData.d());
        
        if (!this.getWorldBorder().isInBounds(spawnPos)) {
            spawnPos = this.getHighestBlockAt(new BlockPosition(this.getWorldBorder().getCenterX(), 0.0D, this.getWorldBorder().getCenterZ()));
        }
        
        return spawnPos;
    }
    
    /**
     * Returns the heighest Y blockpos at the given position
     */
    public BlockPosition getHighestBlockAt(BlockPosition position) {
        return new BlockPosition(position.getX(), this.getHeighestY(position.getX(), position.getZ()), position.getZ());
    }
    
    public int getHeighestY(int x, int z) {
        int hY;
        
        if (x >= -30000000 && z >= -30000000 && x < 30000000 && z < 30000000) {
            Chunk chunk = this.getChunkIfLoaded(x >> 4, z >> 4);
            if (chunk != null) {
                hY = chunk.b(x & 15, z & 15);
            } else {
                hY = 0;
            }
        } else {
            hY = seaLevel + 1;
        }
        
        return hY;
    }
    
    public void setSpawn(BlockPosition spawnPosition) {
        this.worldData.setSpawn(spawnPosition);
    }

    public GameRules getGameRules() {
        return this.worldData.w(); // PAIL: getGameRules
    }
    
    public void everyoneSleeping() {
        ;
    }
    
    public DifficultyDamageScaler createDamageScaler(BlockPosition position) {
        long i = 0L;
        float f = 0.0F;
        
        Chunk chunk = this.getChunkIfLoaded(position);
        if (chunk != null) {
            f = this.getCurrentMoonPhaseFactor();
            i = chunk.x(); // PAIL: getInhabitedTime
        }
        
        return new DifficultyDamageScaler(this.getDifficulty(), this.getDayTime(), i, f);
    }
    
    /**
     * Returns the maxium world height, 256 in vanilla
     */
    public int getWorldHeight() {
        return 256; // TODO: configurable?
    }
    
    /**
     * Returns the actual maxium world height, the method considering the sky missing world
     */
    public int getActualWorldHeight() {
        return servant.worldProvider.n() ? 128 : 256; // TODO: configurable?
    }
    
    /**
     * Find a player by name in this world
     */
    @Nullable
    public EntityHuman getPlayerByName(String username) {
        EntityHuman globalPlayer = getServer().getPlayerList().getPlayer(username);
        return players.contains(globalPlayer) ? globalPlayer : null;
    }
    
    /**
     * Find a player by uuid in this world
     */
    @Nullable
    public EntityHuman getPlayerByUUID(UUID uuid) {
        EntityHuman globalPlayer = getServer().getPlayerList().getPlayerByUUID(uuid);
        return players.contains(globalPlayer) ? globalPlayer : null;
    }
    
    /**
     * Returns a calendar object containing the current date
     */
    public Calendar currentDate() {
        if (this.getTime() % 600L == 0L) {
            this.calendar.setTimeInMillis(System.currentTimeMillis());
        }
        
        return this.calendar;
    }
    
    public WorldType getWorldType() {
        return this.worldData.getType();
    }

    @Override @Nullable
    public TileEntity getTileEntity(BlockPosition pos) {
        if (pos.isInvalidYLocation()) return null;
        
        TileEntity result = capturedTileEntities.get(pos);
        
        if (result == null && this.processingLoadedTiles) {
            result = this.getAddedTileEntity(pos);
        }
        
        if (result == null) {
            result = this.getChunkAt(pos).a(pos, Chunk.EnumTileEntityState.IMMEDIATE);
        }
        
        if (result == null) {
            result = this.getAddedTileEntity(pos);
        }
        
        return result;
    }
    
    public void setTileEntity(BlockPosition position, @Nullable TileEntity tileentity) {
        if (!position.isInvalidYLocation()) {
            if (tileentity != null && !tileentity.y()) {
                if (captureBlockStates) {
                    tileentity.a(servant); // PAIL: setWorld
                    tileentity.setPosition(position);
                    capturedTileEntities.put(position, tileentity);
                    return;
                }
                
                if (this.processingLoadedTiles) {
                    tileentity.setPosition(position);
                    
                    Iterator<TileEntity> iterator = this.addedTileEntities.iterator();
                    while (iterator.hasNext()) {
                        TileEntity added = iterator.next();

                        if (added.getPosition().equals(position)) {
                            added.z(); // PAIL: invalidate
                            iterator.remove();
                        }
                    }
                    
                    tileentity.a(servant); // PAIL: setWorld // Spigot - No null worlds
                    this.addedTileEntities.add(tileentity);
                } else {
                    this.getChunkAt(position).a(position, tileentity);
                    this.addTileEntity(tileentity);
                }
            }
        
        }
    }
    
    public boolean addTileEntity(TileEntity tickable) {
        if (tickable instanceof ITickable) {
            return this.tickableTileEntities.add(tickable);
        }
        return true; // TODO
    }
    
    public void addTileEntities(Collection<TileEntity> entities) {
        if (this.processingLoadedTiles) {
            this.addedTileEntities.addAll(entities);
        } else {
            for (TileEntity each : entities) this.addTileEntity(each);
        }
    }
    
    public void removeTileEntity(BlockPosition position) {
        TileEntity tileentity = this.getTileEntity(position);

        if (tileentity != null && this.processingLoadedTiles) {
            tileentity.z(); // PAIL: invalidate
            this.addedTileEntities.remove(tileentity);
        } else {
            if (tileentity != null) {
                this.addedTileEntities.remove(tileentity);
                this.tickableTileEntities.remove(tileentity);
            }
            
            this.getChunkAt(position).d(position);
        }
    }
    
    @Nullable
    public TileEntity getAddedTileEntity(BlockPosition position) {
        for (TileEntity entity : this.addedTileEntities) {
            if (!entity.y() && entity.getPosition().equals(position)) {
                return entity;
            }
        }
        return null;
    }
    
    /**
     * Adds the specified TileEntity to the pending removal list
     */
    public void markTileEntityForRemoval(TileEntity tileentity) {
        this.tileEntityToUnload.add(tileentity);
    }
    
    @Override
    public IBlockData getType(BlockPosition position) {
        // Paper - optimize getType lookup to reduce instructions - getBlockData already enforces valid Y, move tree out
        final int x = position.getX();
        final int y = position.getY();
        final int z = position.getZ();
        
        if (captureTreeGeneration) {
            final IBlockData previous = getCapturedBlockType(x, y, z);
            if (previous != null) {
                return previous;
            }
        }
        
        return this.chunkProvider.getChunkAt(x >> 4, z >> 4).getBlockData(x, y, z);
    }
    
    public IBlockData getCapturedBlockType(int x, int y, int z) {
        for (BlockState previous : capturedBlockStates) {
            if (previous.getX() == x && previous.getY() == y && previous.getZ() == z) {
                return CraftMagicNumbers.getBlock(previous.getTypeId()).fromLegacyData(previous.getRawData());
            }
        }
        return null;
    }
    
    public boolean canBuild(Block block, BlockPosition position, boolean flag, EnumDirection direction, @Nullable Entity entity) {
        IBlockData iblockdata = this.getType(position);
        AxisAlignedBB box = flag ? null : block.getBlockData().c(this, position);
        
        boolean defaultReturn = box != Block.k && !this.checkNoVisiblePlayerCollisions(box.a(position), entity) ? false : (iblockdata.getMaterial() == Material.ORIENTABLE && block == Blocks.ANVIL ? true : iblockdata.getMaterial().isReplaceable() && block.canPlace(servant, position, direction));
        BlockCanBuildEvent event = new BlockCanBuildEvent(this.getCraftWorld().getBlockAt(position.getX(), position.getY(), position.getZ()), CraftMagicNumbers.getId(block), defaultReturn);
        this.getCraftServer().getPluginManager().callEvent(event);

        return event.isBuildable();
    }
    
    public float currentThunderingStrength(float delta) {
        return (this.prevThunderingStrength + (this.thunderingStrength - this.prevThunderingStrength) * delta) * currentRainingStrength(delta);
    }
    
    public float currentRainingStrength(float delta) {
        return this.prevRainingStrength + (this.rainingStrength - this.prevRainingStrength) * delta;
    }
    
    /**
     * Returns true if the current thunder strength (weighted with the rain strength) is greater than 0.9
     */
    public boolean isThundering() {
        return currentThunderingStrength(1.0F) > 0.9D;
    }

    /**
     * Returns true if the current rain strength is greater than 0.2
     */
    public boolean isRaining() {
        return currentRainingStrength(1.0F) > 0.2D;
    }
    
    /**
     * Check if precipitation is currently happening at a position
     */
    public boolean isRainingAt(BlockPosition strikePosition) {
        if (!this.isRaining()) {
            return false;
        } else if (!this.canSeeSkyAt(strikePosition)) {
            return false;
        } else if (this.getPrecipitationHeight(strikePosition).getY() > strikePosition.getY()) {
            return false;
        } else {
            BiomeBase biome = this.getBiomeAt(strikePosition);
            //return biome.getEnableSnow() ? false : (this.canSnowAt(strikePosition, false) ? false : biome.canRain());
            return biome.c() ? false : (this.canSnowAt(strikePosition, false) ? false : biome.d());
        }
    }

    @Nullable
    public <T extends Entity> T findNearestEntityWithinBoundingBox(Class<? extends T> entityType, AxisAlignedBB box, T closestTo) {
        Entity entity = null;
        double minDistance = Double.MAX_VALUE;
        
        for (Entity entity1 : getEntitiesOf(entityType, box)) {
            if (entity1 != closestTo && IEntitySelector.e.apply(entity1)) {
                double distance = closestTo.h(entity1);
                
                if (distance <= minDistance) {
                    entity = entity1;
                    minDistance = distance;
                }
            }
        }
        
        return (T) entity; // CraftBukkit fix decompile error
    }
    
    /**
     * Adds the specified Entity to the pending strike list
     */
    public boolean strikeLightning(Entity entity) {
        this.lightingEntities.add(entity);
        return true; // TODO
    }
    
    public boolean addEntity(Entity entity) {
        // CraftBukkit start - Used for entities other than creatures
        return addEntity(entity, SpawnReason.DEFAULT);
    }

    public boolean addEntity(Entity entity, SpawnReason spawnReason) { // Changed signature, added SpawnReason
        AsyncCatcher.catchOp("entity add");
        
        if (entity == null) return false;
        if (entity.valid) {
            MinecraftServer.LOGGER.error("Attempted Double World add on " + entity, new Throwable());
            return true;
        }
        
        int cX = MathHelper.floor(entity.locX / 16.0D);
        int cZ = MathHelper.floor(entity.locZ / 16.0D);
        boolean spawnAsPlayer = entity.attachedToPlayer;
        
        // Paper start - Set origin location when the entity is being added to the world
        if (entity.origin == null) {
            entity.origin = entity.getBukkitEntity().getLocation();
        }
        // Paper end
        
        if (entity instanceof EntityHuman) {
            spawnAsPlayer = true;
        }
        
        org.bukkit.event.Cancellable event = null;
        if (entity instanceof EntityLiving && !(entity instanceof EntityPlayer)) {
            boolean isAnimal = entity instanceof EntityAnimal || entity instanceof EntityWaterAnimal || entity instanceof EntityGolem;
            boolean isMonster = entity instanceof EntityMonster || entity instanceof EntityGhast || entity instanceof EntitySlime;
            boolean isNpc = entity instanceof NPC;

            if (spawnReason != SpawnReason.CUSTOM) {
                if (isAnimal && !servant.allowAnimals || isMonster && !servant.allowMonsters || isNpc && !getServer().isSpawnNPCs()) {
                    entity.dead = true;
                    return false;
                }
            }
            
            event = CraftEventFactory.callCreatureSpawnEvent((EntityLiving) entity, spawnReason);
        } else if (entity instanceof EntityItem) {
            event = CraftEventFactory.callItemSpawnEvent((EntityItem) entity);
        } else if (entity.getBukkitEntity() instanceof org.bukkit.entity.Projectile) {
            // Not all projectiles extend EntityProjectile, so check for Bukkit interface instead
            event = CraftEventFactory.callProjectileLaunchEvent(entity);
        } else if (entity.getBukkitEntity() instanceof org.bukkit.entity.Vehicle){
            event = CraftEventFactory.callVehicleCreateEvent(entity);
        } else if (entity instanceof EntityExperienceOrb) {
            EntityExperienceOrb xp = (EntityExperienceOrb) entity;
            double radius = spigotConfig.expMerge;
            if (radius > 0) {
                this.applyEntities(entity, entity.getBoundingBox().grow(radius, radius, radius), e -> {
                    if (e instanceof EntityExperienceOrb) {
                        EntityExperienceOrb loopItem = (EntityExperienceOrb) e;
                        if (!loopItem.dead) {
                            xp.value += loopItem.value;
                            loopItem.die();
                        }
                    }
                });
            }
        }
        
        if (event != null && (event.isCancelled() || entity.dead)) {
            entity.dead = true;
            return false;
        }
        
        if (spawnAsPlayer) {
            this.players.add((EntityHuman) entity);
            this.everyoneSleeping();
        }
        
        Chunk chunk;
        if ((chunk = this.getChunkIfLoaded(cX, cZ)) == null) {
            return false;
        } else {
            chunk.a(entity);
            this.entityList.add(entity);
            this.onEntityAdded(entity);
            return true;
        }
    }
    
    public void kill(Entity entity) {
        if (entity.isVehicle()) {
            entity.az();
        }
        
        if (entity.isPassenger()) {
            entity.stopRiding();
        }
        
        entity.die();
        if (entity instanceof EntityHuman) {
            this.players.remove(entity);
            
            for (Object o : servant.worldMaps.c) {
                if (o instanceof WorldMap) {
                    WorldMap map = (WorldMap) o;
                    map.k.remove(entity);
                    for (Iterator<WorldMap.WorldMapHumanTracker> iter = map.i.iterator(); iter.hasNext();) {
                        if (iter.next().trackee == entity) {
                            map.decorations.remove(entity.getUniqueID()); // Paper
                            iter.remove();
                        }
                    }
                }
            }
            
            this.everyoneSleeping();
            this.onEntityRemove(entity);
        }
    }
    
    public void removeEntity(Entity entity) {
        AsyncCatcher.catchOp("entity remove");
        
        entity.b(false);
        
        // It will get removed from the entity list after the tick if we are ticking
        entity.die();
        
        if (entity instanceof EntityHuman) {
            this.players.remove(entity);
            this.everyoneSleeping();
        }
        
        // It will get removed from the entity list after the tick if we are ticking
        int index = this.entityList.indexOf(entity);
        if (index != -1) {
            if (index <= this.tickPosition) {
                this.tickPosition--;
            }
            
            this.entityList.remove(index);
            this.onEntityRemove(entity);
        }
    }
    
    @Deprecated
    public boolean collidesWith(@Nullable Entity entity, AxisAlignedBB aabb, boolean any, @Nullable List<AxisAlignedBB> collidingBoxes) {
        int minX = MathHelper.floor(aabb.a) - 1;
        int maxX = MathHelper.ceil(aabb.d) + 1;
        int minY = MathHelper.floor(aabb.b) - 1;
        int maxY = MathHelper.ceil(aabb.e) + 1;
        int minZ = MathHelper.floor(aabb.c) - 1;
        int maxZ = MathHelper.ceil(aabb.f) + 1;
        WorldBorder border = this.getWorldBorder();
        boolean outsideBorder = entity != null && entity.br();
        boolean withinBorder = entity != null && this.isWithinBorder(entity);
        
        try (BlockPosition.PooledBlockPosition position = BlockPosition.PooledBlockPosition.aquire()) {
            for (int x = minX; x < maxX; x++) {
                for (int z = minZ; z < maxZ; z++) {
                    boolean edgeX = x == minX || x == maxX - 1;
                    boolean degeZ = z == minZ || z == maxZ - 1;
                    
                    if ((!edgeX || !degeZ) && this.isChunkLoaded(position.f(x, 64, z))) {
                        for (int y = minY; y < maxY; y++) {
                            if (!edgeX && !degeZ || y != maxY - 1) {
                                if (any) {
                                    if (x < -30000000 || x >= 30000000 || z < -30000000 || z >= 30000000) {
                                        return true;
                                    }
                                } else if (entity != null && outsideBorder == withinBorder) {
                                    entity.k(!withinBorder);
                                }
                                
                                position.f(x, y, z); // PAIL: setValues
                                
                                IBlockData block;
                                if (!any && !border.a(position) && withinBorder) {
                                    block = Blocks.STONE.getBlockData();
                                } else {
                                    block = this.getType(position);
                                }
                                
                                block.a(servant, position, aabb, collidingBoxes, entity, false); // PAIL: addCollisionBoxToList
                                if (any && !collidingBoxes.isEmpty()) {
                                    return true;
                                }
                                
                            }
                        }
                    }
                }
            }
        }
        
        return !collidingBoxes.isEmpty();
    }
    
    /**
     * Gets a list of bounding boxes that intersect with the provided AABB
     */
    public List<AxisAlignedBB> getCollisionBoxes(@Nullable Entity entity, AxisAlignedBB aabb) {
        List<AxisAlignedBB> boxes = Lists.newArrayList();

        this.collidesWith(entity, aabb, false, boxes);
        if (entity != null) {
            if (entity instanceof EntityArmorStand && !entity.world.paperConfig.armorStandEntityLookups) return boxes; // Paper
            List<Entity> entities = this.getEntities(entity, aabb.g(0.25D));

            for (int i = 0, size = entities.size(); i < size; ++i) {
                Entity entity1 = entities.get(i);

                if (!entity.x(entity1)) {
                    AxisAlignedBB axisalignedbb1 = entity1.ag();

                    if (axisalignedbb1 != null && axisalignedbb1.c(aabb)) {
                        boxes.add(axisalignedbb1);
                    }

                    axisalignedbb1 = entity.j(entity1);
                    if (axisalignedbb1 != null && axisalignedbb1.c(aabb)) {
                        boxes.add(axisalignedbb1);
                    }
                }
            }
        }
        
        return boxes;
    }
    
    public boolean isWithinBorder(Entity entity) {
        double minX = this.worldBorder.b();
        double minZ = this.worldBorder.c();
        double maxX = this.worldBorder.d();
        double maxZ = this.worldBorder.e();
        
        if (entity.br()) { // PAIL: isOutsideBorder
            minX++;
            minZ++;
            maxX--;
            maxZ--;
        } else {
            minX--;
            minZ--;
            maxX++;
            maxZ++;
        }
        
        return entity.locX > minX && entity.locX < maxX && entity.locZ > minZ && entity.locZ < maxZ;
    }
    
    /**
     * Returns true if the given aabb collides with any block
     */
    public boolean collidesWithAnyBlock(AxisAlignedBB aabb) {
        return this.collidesWith((Entity) null, aabb, true, Lists.newArrayList());
    }
    
    /**
     * Returns the amount of skylight subtracted for the current time
     */
    public int calculateSkylightSubtracted(float partialTicks) {
        float f = this.getSunBrightnessFactor(partialTicks);
        f = 1 - f;
        return (int) (f * 11);
    }
    
    /**
     * The current sun brightness factor for this dimension.
     * 0.0f means no light at all, and 1.0f means maximum sunlight.
     * Highly recommended for sunlight detection like solar panel.
     *
     * @return The current brightness factor
     * */
    public float getSunBrightnessFactor(float partialTicks) {
        float f1 = this.getCelestialAngle(partialTicks);
        float f2 = 1.0F - (MathHelper.cos(f1 * 6.2831855F) * 2.0F + 0.5F);

        f2 = MathHelper.a(f2, 0.0F, 1.0F);
        f2 = 1.0F - f2;
        f2 = (float) (f2 * (1.0D - this.currentRainingStrength(partialTicks) * 5.0F / 16.0D));
        f2 = (float) (f2 * (1.0D - this.currentThunderingStrength(partialTicks) * 5.0F / 16.0D));
        
        return f2;
    }
    
    public float getCelestialAngle(float partialTicks) {
        return servant.worldProvider.a(this.worldData.getDayTime(), partialTicks);
    }

    /**
     * Gets the current fullness of the moon expressed as a float between 1.0 and 0.0, in steps of .25
     */
    public float getCurrentMoonPhaseFactor() {
        return WorldProvider.a[servant.worldProvider.a(this.worldData.getDayTime())];
    }
    
    /**
     * Return getCelestialAngle() * 2 * PI
     */
    public float getCelestialAngleRadians(float partialTicks) {
        float f1 = this.getCelestialAngle(partialTicks);
        return f1 * 6.2831855F;
    }
    
    public BlockPosition getPrecipitationHeight(BlockPosition position) {
        return this.getChunkAt(position).f(position);
    }
    
    /**
     * Finds the highest block on the x and z coordinate that is solid or liquid, and returns its y coord
     */
    public BlockPosition getTopSolidOrLiquidBlock(BlockPosition position) {
        Chunk chunk = this.getChunkAt(position);

        BlockPosition blockposition1;
        BlockPosition blockposition2;

        for (blockposition1 = new BlockPosition(position.getX(), chunk.g() + 16, position.getZ()); blockposition1.getY() >= 0; blockposition1 = blockposition2) {
            blockposition2 = blockposition1.down();
            Material material = chunk.getBlockData(blockposition2).getMaterial();

            if (material.isSolid() && material != Material.LEAVES) {
                break;
            }
        }

        return blockposition1;
    }
    
    public boolean isBlockFullCube(BlockPosition position) {
        AxisAlignedBB blockBox = this.getType(position).c(this, position);
        return blockBox != Block.k && blockBox.a() >= 1.0D;
    }
    
    /**
     * Checks if a block's material is opaque, and that it takes up a full cube
     */
    public boolean isBlockNormalCube(BlockPosition position, boolean defaultValue) {
        if (position.isInvalidYLocation()) { // Paper
            return false;
        } else {
            Chunk chunk = this.chunkProvider.getLoadedChunkAt(position.getX() >> 4, position.getZ() >> 4);
            
            if (chunk != null && !chunk.isEmpty()) {
                IBlockData iblockdata = this.getType(position);

                return iblockdata.getMaterial().k() && iblockdata.h();
            } else {
                return defaultValue;
            }
        }
    }
    
    public void initSkylight() {
        int i = this.calculateSkylightSubtracted(1.0F);

        if (i != this.skylightSubtracted) {
            this.skylightSubtracted = i;
        }
    }
    
    public void initWeather() {
        if (this.worldData.hasStorm()) {
            this.rainingStrength = 1.0F;
            if (this.worldData.isThundering()) {
                this.thunderingStrength = 1.0F;
            }
        }
    }
    
    public void immediateBlockTick(BlockPosition position, IBlockData block, Random rand) {
        this.scheduledUpdatesAreImmediate = true;
        block.getBlock().b(servant, position, block, rand); // PAIL: updateTick
        this.scheduledUpdatesAreImmediate = false;
    }
    
    public boolean canBlockFreezeWater(BlockPosition blockposition) {
        return this.canBlockFreeze(blockposition, false);
    }
    
    public boolean canBlockFreezeSelf(BlockPosition blockposition) {
        return this.canBlockFreeze(blockposition, true);
    }
    
    /**
     * Checks to see if a given block is both water and cold enough to freeze
     */
    public boolean canBlockFreeze(BlockPosition blockposition, boolean withoutWater) {
        BiomeBase biomebase = this.getBiomeAt(blockposition);
        float f = biomebase.a(blockposition);

        if (f >= 0.15F) {
            return false;
        } else {
            if (blockposition.getY() >= 0 && blockposition.getY() < 256 && this.getBrightness(EnumSkyBlock.BLOCK, blockposition) < 10) {
                IBlockData iblockdata = this.getType(blockposition);
                Block block = iblockdata.getBlock();

                if ((block == Blocks.WATER || block == Blocks.FLOWING_WATER) && iblockdata.get(BlockFluids.LEVEL).intValue() == 0) {
                    if (!withoutWater) {
                        return true;
                    }

                    boolean flag1 = this.isWater(blockposition.west()) && this.isWater(blockposition.east()) && this.isWater(blockposition.north()) && this.isWater(blockposition.south());
                    
                    if (!flag1) {
                        return true;
                    }
                }
            }

            return false;
        }
    }
    
    /**
     * Checks to see if a given block can accumulate snow from it snowing
     */
    public boolean canSnowAt(BlockPosition position, boolean checkLight) {
        BiomeBase biomebase = this.getBiomeAt(position);
        float f = biomebase.a(position);

        if (f >= 0.15F) {
            return false;
        } else if (!checkLight) {
            return true;
        } else {
            if (position.getY() >= 0 && position.getY() < 256 && this.getBrightness(EnumSkyBlock.BLOCK, position) < 10) {
                IBlockData iblockdata = this.getType(position);

                if (iblockdata.getMaterial() == Material.AIR && Blocks.SNOW_LAYER.canPlace(servant, position)) {
                    return true;
                }
            }

            return false;
        }
    }
    
    public boolean checkLightAt(BlockPosition position) {
        boolean flag = false;

        if (servant.worldProvider.m()) {
            flag |= this.checkLightFor(EnumSkyBlock.SKY, position);
        }

        flag |= this.checkLightFor(EnumSkyBlock.BLOCK, position);
        return flag;
    }

    /**
     * Gets the light level at the supplied position
     */
    public int getRawLight(BlockPosition blockposition, EnumSkyBlock lightType) {
        if (lightType == EnumSkyBlock.SKY && this.canSeeSkyAt(blockposition)) {
            return 15;
        } else {
            IBlockData iblockdata = this.getType(blockposition);
            int i = lightType == EnumSkyBlock.SKY ? 0 : iblockdata.d();
            int j = iblockdata.c();

            if (j >= 15 && iblockdata.d() > 0) {
                j = 1;
            }
            
            if (j < 1) {
                j = 1;
            }

            if (j >= 15) {
                return 0;
            } else if (i >= 14) {
                return i;
            } else {
                BlockPosition.PooledBlockPosition blockposition_pooledblockposition = BlockPosition.PooledBlockPosition.s();
                EnumDirection[] aenumdirection = EnumDirection.values();
                int k = aenumdirection.length;

                for (int l = 0; l < k; ++l) {
                    EnumDirection enumdirection = aenumdirection[l];

                    blockposition_pooledblockposition.j(blockposition).d(enumdirection);
                    int i1 = this.getBrightness(lightType, blockposition_pooledblockposition) - j;

                    if (i1 > i) {
                        i = i1;
                    }

                    if (i >= 14) {
                        return i;
                    }
                }

                blockposition_pooledblockposition.t();
                return i;
            }
        }
    }
    
    public boolean checkLightFor(EnumSkyBlock lightType, BlockPosition blockposition) {
        // CraftBukkit start - Use neighbor cache instead of looking up
        Chunk chunk = this.getChunkIfLoaded(blockposition.getX() >> 4, blockposition.getZ() >> 4);
        if (chunk == null || !chunk.areNeighborsLoaded(1) /*!this.areChunksLoaded(blockposition, 17, false)*/) {
            // CraftBukkit end
            return false;
        } else {
            int i = 0;
            int j = 0;

            int k = this.getBrightness(lightType, blockposition);
            int l = this.getRawLight(blockposition, lightType);
            int i1 = blockposition.getX();
            int j1 = blockposition.getY();
            int k1 = blockposition.getZ();
            int l1;
            int i2;
            int j2;
            int k2;
            int l2;
            int i3;
            int j3;
            int k3;

            if (l > k) {
                this.lightUpdateBlocks[j++] = 133152;
            } else if (l < k) {
                this.lightUpdateBlocks[j++] = 133152 | k << 18;

                while (i < j) {
                    l1 = this.lightUpdateBlocks[i++];
                    i2 = (l1 & 63) - 32 + i1;
                    j2 = (l1 >> 6 & 63) - 32 + j1;
                    k2 = (l1 >> 12 & 63) - 32 + k1;
                    int l3 = l1 >> 18 & 15;
                    BlockPosition blockposition1 = new BlockPosition(i2, j2, k2);

                    l2 = this.getBrightness(lightType, blockposition1);
                    if (l2 == l3) {
                        this.setLightFor(lightType, blockposition1, 0);
                        
                        if (l3 > 0) {
                            i3 = MathHelper.a(i2 - i1);
                            j3 = MathHelper.a(j2 - j1);
                            k3 = MathHelper.a(k2 - k1);
                            if (i3 + j3 + k3 < 17) {
                                BlockPosition.PooledBlockPosition blockposition_pooledblockposition = BlockPosition.PooledBlockPosition.s();
                                EnumDirection[] aenumdirection = EnumDirection.values();
                                int i4 = aenumdirection.length;

                                for (int j4 = 0; j4 < i4; ++j4) {
                                    EnumDirection enumdirection = aenumdirection[j4];
                                    int k4 = i2 + enumdirection.getAdjacentX();
                                    int l4 = j2 + enumdirection.getAdjacentY();
                                    int i5 = k2 + enumdirection.getAdjacentZ();

                                    blockposition_pooledblockposition.f(k4, l4, i5);
                                    int j5 = Math.max(1, this.getType(blockposition_pooledblockposition).c());

                                    l2 = this.getBrightness(lightType, blockposition_pooledblockposition);
                                    if (l2 == l3 - j5 && j < this.lightUpdateBlocks.length) {
                                        this.lightUpdateBlocks[j++] = k4 - i1 + 32 | l4 - j1 + 32 << 6 | i5 - k1 + 32 << 12 | l3 - j5 << 18;
                                    }
                                }

                                blockposition_pooledblockposition.t();
                            }
                        }
                    }
                }

                i = 0;
            }


            while (i < j) {
                l1 = this.lightUpdateBlocks[i++];
                i2 = (l1 & 63) - 32 + i1;
                j2 = (l1 >> 6 & 63) - 32 + j1;
                k2 = (l1 >> 12 & 63) - 32 + k1;
                BlockPosition blockposition2 = new BlockPosition(i2, j2, k2);
                int k5 = this.getBrightness(lightType, blockposition2);
                l2 = this.getRawLight(blockposition2, lightType);
                
                if (l2 != k5) {
                    this.setLightFor(lightType, blockposition2, l2);
                    
                    if (l2 > k5) {
                        i3 = Math.abs(i2 - i1);
                        j3 = Math.abs(j2 - j1);
                        k3 = Math.abs(k2 - k1);
                        boolean flag = j < this.lightUpdateBlocks.length - 6;

                        if (i3 + j3 + k3 < 17 && flag) {
                            if (this.getBrightness(lightType, blockposition2.west()) < l2) {
                                this.lightUpdateBlocks[j++] = i2 - 1 - i1 + 32 + (j2 - j1 + 32 << 6) + (k2 - k1 + 32 << 12);
                            }

                            if (this.getBrightness(lightType, blockposition2.east()) < l2) {
                                this.lightUpdateBlocks[j++] = i2 + 1 - i1 + 32 + (j2 - j1 + 32 << 6) + (k2 - k1 + 32 << 12);
                            }

                            if (this.getBrightness(lightType, blockposition2.down()) < l2) {
                                this.lightUpdateBlocks[j++] = i2 - i1 + 32 + (j2 - 1 - j1 + 32 << 6) + (k2 - k1 + 32 << 12);
                            }

                            if (this.getBrightness(lightType, blockposition2.up()) < l2) {
                                this.lightUpdateBlocks[j++] = i2 - i1 + 32 + (j2 + 1 - j1 + 32 << 6) + (k2 - k1 + 32 << 12);
                            }

                            if (this.getBrightness(lightType, blockposition2.north()) < l2) {
                                this.lightUpdateBlocks[j++] = i2 - i1 + 32 + (j2 - j1 + 32 << 6) + (k2 - 1 - k1 + 32 << 12);
                            }

                            if (this.getBrightness(lightType, blockposition2.south()) < l2) {
                                this.lightUpdateBlocks[j++] = i2 - i1 + 32 + (j2 - j1 + 32 << 6) + (k2 + 1 - k1 + 32 << 12);
                            }
                        }
                    }
                }
            }


            return true;
        }
    }
    
    /**
     * Gets all entities of the specified class type which intersect with the AABB
     */
    public <T extends Entity> List<T> getEntitiesOf(Class<? extends T> entityType, AxisAlignedBB box) {
        return this.getEntitiesOf(entityType, box, IEntitySelector.e);
    }
    
    public <T extends Entity> List<T> getEntitiesOf(Class<? extends T> entityType, AxisAlignedBB aabb, @Nullable Predicate<? super T> filter) {
        ArrayList<T> list = Lists.newArrayList();
        int minX = MathHelper.floor((aabb.a - 2.0D) / 16.0D);
        int maxX = MathHelper.floor((aabb.d + 2.0D) / 16.0D);
        int minZ = MathHelper.floor((aabb.c - 2.0D) / 16.0D);
        int maxZ = MathHelper.floor((aabb.f + 2.0D) / 16.0D);
        
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                this.applyIfChunkLoaded(cx, cz, chunk -> chunk.a(entityType, aabb, list, filter));
            }
        }
        
        return list;
    }
    
    /**
     * Gets all entities within the specified AABB
     */
    public List<Entity> getEntities(AxisAlignedBB aabb) {
        return this.getEntities((Entity) null, aabb, IEntitySelector.e);
    }
    
    /**
     * Gets all entities within the specified AABB excluding the one passed into it
     */
    public List<Entity> getEntities(@Nullable Entity entity, AxisAlignedBB aabb) {
        return this.getEntities(entity, aabb, IEntitySelector.e);
    }
    
    /**
     * Gets all entities within the specified AABB excluding the one passed into it
     */
    public List<Entity> getEntities(@Nullable Entity entity, AxisAlignedBB aabb, @Nullable Predicate<? super Entity> filter) {
        ArrayList<Entity> list = Lists.newArrayList();
        int minX = MathHelper.floor((aabb.a - 2.0D) / 16.0D);
        int maxX = MathHelper.floor((aabb.d + 2.0D) / 16.0D);
        int minZ = MathHelper.floor((aabb.c - 2.0D) / 16.0D);
        int maxZ = MathHelper.floor((aabb.f + 2.0D) / 16.0D);
        
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                this.applyIfChunkLoaded(cx, cz, chunk -> chunk.a(entity, aabb, list, filter));
            }
        }
        
        return list;
    }
    
    public boolean anyEntityAccepted(AxisAlignedBB aabb, Predicate<Entity> check) {
        return this.anyEntityAccepted((Entity) null, aabb, check, IEntitySelector.e);
    }
    
    public boolean anyEntityAccepted(@Nullable Entity entity, AxisAlignedBB aabb, Predicate<Entity> check, @Nullable Predicate<? super Entity> filter) {
        int minX = MathHelper.floor((aabb.a - 2.0D) / 16.0D);
        int maxX = MathHelper.floor((aabb.d + 2.0D) / 16.0D);
        int minZ = MathHelper.floor((aabb.c - 2.0D) / 16.0D);
        int maxZ = MathHelper.floor((aabb.f + 2.0D) / 16.0D);
        
        Chunk chunk;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                chunk = this.getChunkIfLoaded(cx, cz);
                if (chunk != null) {
                    if (chunk.anyEntityAccepted(entity, aabb, check, filter)) return true;
                }
            }
        }
        
        return false;
    }
    
    public void applyEntities(@Nullable Entity entity, AxisAlignedBB aabb, Consumer<Entity> apply) {
        this.applyEntities(entity, aabb, apply, IEntitySelector.e);
    }
    
    public void applyEntities(@Nullable Entity entity, AxisAlignedBB aabb, Consumer<Entity> apply, @Nullable Predicate<? super Entity> filter) {
        int minX = MathHelper.floor((aabb.a - 2.0D) / 16.0D);
        int maxX = MathHelper.floor((aabb.d + 2.0D) / 16.0D);
        int minZ = MathHelper.floor((aabb.c - 2.0D) / 16.0D);
        int maxZ = MathHelper.floor((aabb.f + 2.0D) / 16.0D);
        
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                this.applyIfChunkLoaded(cx, cz, chunk -> chunk.applyEntities(entity, aabb, apply, filter));
            }
        }
    }
    
    public boolean checkNoEntityCollision(AxisAlignedBB aabb) {
        return this.checkNoEntityCollision(aabb, (Entity) null);
    }
    
    public boolean checkNoEntityCollision(AxisAlignedBB aabb, @Nullable Entity entity) {
        Predicate<Entity> noCollision = (each) -> {
            if (!each.dead && each.i && each != entity && (entity == null || each.x(entity))) {
                return true;
            }
            return false; // continue
        };
        
        return !this.anyEntityAccepted(aabb, noCollision);
    }
    
    // Paper start - Based on method above
    /**
     * @param axisalignedbb area to search within
     * @param entity causing the action ex. block placer
     * @return if there are no visible players colliding
     */
    public boolean checkNoVisiblePlayerCollisions(AxisAlignedBB aabb, @Nullable Entity entity) {
        List<Entity> list = this.getEntities(aabb);
        
        for (int i = 0, size = list.size(); i < size; ++i) {
            Entity entity1 = list.get(i);

            if (entity instanceof EntityPlayer && entity1 instanceof EntityPlayer) {
                if (!((EntityPlayer) entity).getBukkitEntity().canSee(((EntityPlayer) entity1).getBukkitEntity())) {
                    continue;
                }
            }

            if (!entity1.dead && entity1.blocksEntitySpawning()) {
                return false;
            }
        }

        return true;
    }
    // Paper end
    
    public <T extends Entity> List<T> getEntitiesOf(Class<? extends T> entityType, Predicate<? super T> filter) {
        List<T> list = Lists.newArrayList();
        
        for (Entity entity : this.entityList) {
            if (entityType.isAssignableFrom(entity.getClass()) && filter.apply((T) entity)) {
                list.add((T) entity);
            }
        }
        
        return list;
    }

    public <T extends Entity> List<T> getPlayersOf(Class<? extends T> playerType, Predicate<? super T> filter) {
        List<T> list = Lists.newArrayList();
        
        for (Entity entity : this.players) {
            if (playerType.isAssignableFrom(entity.getClass()) && filter.apply((T) entity)) {
                list.add((T) entity);
            }
        }

        return list;
    }
    
    public void markChunkDirty(BlockPosition blockPos) {
        this.applyIfChunkLoaded(blockPos, chunk -> chunk.e());
    }

    public int countEntities(Class<?> entityType) {
        int count = 0;
        
        for (Entity entity : this.entityList) {
            if (entity instanceof EntityInsentient) {
                EntityInsentient entityinsentient = (EntityInsentient) entity;
                if (entityinsentient.isNotPersistentType() && entityinsentient.isPersistent()) {
                    continue;
                }
            }
            
            if (entityType.isAssignableFrom(entity.getClass())) {
                count++;
            }
        }
        
        return count;
    }

    public boolean isBlockFacePowered(BlockPosition position, EnumDirection face) {
        return this.getBlockFacePower(position, face) > 0;
    }

    public int getBlockFacePower(BlockPosition position, EnumDirection direction) {
        IBlockData iblockdata = this.getType(position);
        return iblockdata.m() ? this.getBlockPower(position) : iblockdata.a(this, position, direction);
    }

    public boolean isBlockPowered(BlockPosition pos) {
        for (EnumDirection face : BLOCK_FACES) {
            if (this.getBlockFacePower(pos.shift(face), face) > 0) return true;
        }
        return false;
    }
    
    /**
     * Checks if the specified block or its neighbors are powered by a neighboring block. Used by blocks like TNT and Doors
     */
    public int getBlockIndirectlyPower(BlockPosition pos) {
        int max = 0;
        
        for (EnumDirection face : EnumDirection.values()) {
            int facePower = this.getBlockFacePower(pos.shift(face), face);

            if (facePower >= 15) return 15;
            if (facePower > max) max = facePower;
        }
        
        return max;
    }
    
    @Override
    public int getBlockPower(BlockPosition position, EnumDirection face) {
        return this.getType(position).b(this, position, face);
    }
    
    /**
     * Returns the single highest strong power out of all directions
     */
    public int getBlockPower(BlockPosition position) {
        int power = 0;
        
        for (EnumDirection face : BLOCK_FACES) {
            power = Math.max(power, this.getBlockPower(position.shift(face), face));
            if (power >= 15) return power;
        }
        
        return power;
    }

    public void playBlockAction(BlockPosition blockposition, Block block, int i, int j) {
        this.getType(blockposition).a(servant, blockposition, i, j);
    }

    public boolean isHumidAt(BlockPosition position) {
        BiomeBase biome = this.getBiomeAt(position);
        return biome.e();
    }
    
    public void updateAdjacentComparators(BlockPosition blockposition, Block block) {
        for (EnumDirection enumdirection : EnumDirectionLimit.HORIZONTAL) {
            BlockPosition blockposition1 = blockposition.shift(enumdirection);

            if (this.isChunkLoaded(blockposition1)) {
                IBlockData iblockdata = this.getType(blockposition1);

                if (Blocks.UNPOWERED_COMPARATOR.E(iblockdata)) {
                    iblockdata.doPhysics(servant, blockposition1, block, blockposition);
                } else if (iblockdata.m()) {
                    blockposition1 = blockposition1.shift(enumdirection);
                    iblockdata = this.getType(blockposition1);
                    if (Blocks.UNPOWERED_COMPARATOR.E(iblockdata)) {
                        iblockdata.doPhysics(servant, blockposition1, block, blockposition);
                    }
                }
            }
        }

    }
}
