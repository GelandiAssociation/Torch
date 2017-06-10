package net.minecraft.server;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import co.aikar.timings.WorldTimingsHandler;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import javax.annotation.Nullable;

import java.util.Map;
import org.bukkit.block.BlockState;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.generator.ChunkGenerator;
import org.torch.api.Anaphase;
import org.torch.server.TorchServer;
import org.torch.server.TorchWorld;

// Paper start
import java.util.Set;

import lombok.Getter;

public abstract class World implements IBlockAccess, org.torch.api.TorchServant {
    /**
     * STATIC FIELDS
     */
    public static boolean haveWeSilencedAPhysicsCrash;
    public static String blockLocation;
    
    /**
     * ANAPHASE FIELDS
     */
    @Anaphase private final org.bukkit.craftbukkit.CraftWorld world;
    @Anaphase public final WorldTimingsHandler timings;
    @Anaphase public PersistentCollection worldMaps;
    @Anaphase public WorldProvider worldProvider;
    /** Indicates if enemies are spawned or not */
    @Anaphase public boolean allowMonsters;
    /** Indicating whether we should spawn peaceful mobs */
    @Anaphase public boolean allowAnimals;
    
    /**
     * NORMAL FIELDS
     */
    @Getter private final TorchWorld reactor;
    public final boolean isClientSide = false;
    public ArrayList<BlockState> capturedBlockStates;
    public Map<BlockPosition, TileEntity> capturedTileEntities;
    public final List<Entity> entityList;
    /** tickableTileEntities */
    public final List<TileEntity> tileEntityListTick;
    /** tileEntityToUnload */
    private final Set<TileEntity> tileEntityListUnload;
    public final List<EntityHuman> players;
    public final MethodProfiler methodProfiler;
    public Scoreboard scoreboard;
    protected IChunkProvider chunkProvider;
    protected final IDataManager dataManager;
    public WorldData worldData;
    protected boolean isLoading;
    protected PersistentVillage villages;
    protected final IntHashMap<Entity> entitiesById;
    public final Random random;
    //public final List<TileEntity> tileEntityList = Lists.newArrayList(); // Paper - remove unused list
    
    /**
     * OBFUSCATED FIELDS
     */
    /** worldListeners */
    protected List<IWorldAccess> u;
    /** unloadedEntities */
    protected final Set<Entity> f; // Paper - List -> Set
    /** addedTileEntities */
    private final Set<TileEntity> b; // Torch - List -> Set
    /** lightingEntities */
    public final List<Entity> j;
    /** cloudColour */
    private final long I;
    /** skylightSubtracted */
    private int J;
    /** updateLCG */
    protected int l;
    /** DIST_HASH */
    protected final int m;
    /** prevRainingStrength */
    protected float n;
    /** rainingStrength */
    public float o;
    /** prevThunderingStrength */
    protected float p;
    /** thunderingStrength */
    public float q;
    // private int K; // PAIL: lastLightningBolt - unused
    /** seaLevel */
    private int a;
    /** scheduledUpdatesAreImmediate */
    protected boolean d;
    /** calendar */
    private final Calendar L;
    /** navigator */
    protected NavigationListener t;
    /** processingLoadedTiles */
    private boolean M;
    /** lootTable */
    protected LootTableRegistry B;
    /** worldBorder */
    private final WorldBorder N;
    /** lightUpdateBlocks */
    int[] H;

    /* // Only port if needed (for plugin compatibility)
    // CraftBukkit start Added the following
    public boolean pvpMode;
    public ChunkGenerator generator;

    public boolean captureBlockStates = false;
    public boolean captureTreeGeneration = false;
    
    public long ticksPerAnimalSpawns;
    public long ticksPerMonsterSpawns;
    public boolean populating;
    private int tickPosition;

    public final co.aikar.timings.WorldTimingsHandler timings; // Paper
    private boolean guardEntityList; // Spigot
    
    private org.spigotmc.TickLimiter entityLimiter;
    private org.spigotmc.TickLimiter tileLimiter;
    private int tileTickPosition;
    public final Map<Explosion.CacheKey, Float> explosionDensityCache = HashObjFloatMaps.newMutableMap(); // Paper - Optimize explosions
    */
    
    public final org.spigotmc.SpigotWorldConfig spigotConfig; // Spigot
    public final com.destroystokyo.paper.PaperWorldConfig paperConfig; // Paper
    
    public boolean keepSpawnInMemory = true;

    public CraftWorld getWorld() {
        return world;
    }

    public CraftServer getServer() {
        return reactor.getCraftServer();
    }

    // Paper start
    public Chunk getChunkIfLoaded(BlockPosition blockposition) {
        return reactor.getChunkIfLoaded(blockposition);
    }
    // Paper end

    public Chunk getChunkIfLoaded(int x, int z) {
        return reactor.getChunkIfLoaded(x, z);
    }

    protected World(IDataManager idatamanager, WorldData worlddata, WorldProvider worldprovider, MethodProfiler methodprofiler, boolean flag, ChunkGenerator gen, org.bukkit.World.Environment env) {
        reactor = new TorchWorld(idatamanager, worlddata, worldprovider, flag, gen, env, this);
        
        methodProfiler = methodprofiler;
        worldProvider = worldprovider;
        scoreboard = new Scoreboard();
        
        //////// CB / S / P Stuffs
        spigotConfig = reactor.spigotConfig;
        paperConfig = reactor.paperConfig;
        
        capturedBlockStates = reactor.getCapturedBlockStates();
        capturedTileEntities = reactor.getCapturedTileEntities();
        entityList = reactor.getEntityList();
        tileEntityListTick = reactor.getTickableTileEntities();
        tileEntityListUnload = reactor.getTileEntityToUnload();
        // players = reactor.getPlayers(); // Torch - List -> Set
        chunkProvider = reactor.getChunkProvider();
        dataManager = reactor.getDataManager();
        worldData = reactor.getWorldData();
        isLoading = reactor.isLoading();
        entitiesById = reactor.getEntitiesById();
        random = reactor.getRandom();
        players = reactor.getPlayers();
        keepSpawnInMemory = reactor.isKeepSpawnInMemory();
        
        a = reactor.getSeaLevel();
        b = reactor.getAddedTileEntities();
        d = reactor.isScheduledUpdatesAreImmediate();
        L = reactor.getCalendar();
        f = reactor.getUnloadedEntities();
        M = reactor.isProcessingLoadedTiles();
        t = reactor.getNavigator();
        N = reactor.getWorldBorder();
        H = reactor.getLightUpdateBlocks();
        
        j = reactor.getLightingEntities();
        I = reactor.getCloudColour();
        J = reactor.getSkylightSubtracted();
        l = reactor.getUpdateLCG();
        m = reactor.DIST_HASH;
        n = reactor.getPrevRainingStrength();
        o = reactor.getRainingStrength();
        p = reactor.getPrevThunderingStrength();
        q = reactor.getThunderingStrength();
        u = reactor.getWorldListeners();
        
        // IN CASE NPE
        world = new CraftWorld((WorldServer) this, gen, env);
        reactor.ticksPerAnimalSpawns = reactor.getCraftServer().getTicksPerAnimalSpawns();
        reactor.ticksPerMonsterSpawns = reactor.getCraftServer().getTicksPerMonsterSpawns();
        reactor.getCraftServer().addWorld(world);
        
        reactor.timings = timings = new WorldTimingsHandler(this);
    }

    public World b() { // PAIL: init
        return this;
    }

    public BiomeBase getBiome(final BlockPosition blockposition) {
        return reactor.getBiomeAt(blockposition);
    }

    public WorldChunkManager getWorldChunkManager() {
        return reactor.getWorldChunkManager();
    }

    protected abstract IChunkProvider n();

    public void a(WorldSettings worldsettings) {
        reactor.initialize(worldsettings);
    }

    @Deprecated
    public MinecraftServer getMinecraftServer() {
        return TorchServer.getMinecraftServer(); // Torch
    }

    public IBlockData c(BlockPosition blockposition) {
        return reactor.findGroundAboveSeaLevel(blockposition);
    }

    private static boolean isValidLocation(BlockPosition blockposition) { // Paper - unused but incase reflection / future uses
        return blockposition.isValidLocation(); // Paper
    }

    private static boolean E(BlockPosition blockposition) { // Paper - unused but incase reflection / future uses
        return blockposition.isInvalidYLocation(); // Paper
    }

    @Override
    public boolean isEmpty(BlockPosition blockposition) {
        return reactor.isEmpty(blockposition);
    }

    public boolean isLoaded(BlockPosition blockposition) {
        return reactor.isChunkLoaded(blockposition);
    }

    public boolean a(BlockPosition blockposition, boolean flag) {
        return reactor.isChunkLoaded(blockposition.getX() >> 4, blockposition.getZ() >> 4/*, flag*/);
    }

    public boolean areChunksLoaded(BlockPosition blockposition, int i) {
        return reactor.areChunksLoaded(blockposition, i);
    }

    public boolean areChunksLoaded(BlockPosition blockposition, int i, boolean flag) {
        return reactor.areChunksLoaded(blockposition, i, flag);
    }

    public boolean areChunksLoadedBetween(BlockPosition blockposition, BlockPosition blockposition1) {
        return reactor.areChunksLoadedBetween(blockposition, blockposition1);
    }

    public boolean areChunksLoadedBetween(BlockPosition blockposition, BlockPosition blockposition1, boolean flag) {
        return reactor.areChunksLoadedBetween(blockposition, blockposition1/*, flag*/);
    }

    public boolean a(StructureBoundingBox structureboundingbox) {
        return this.b(structureboundingbox, true);
    }

    public boolean b(StructureBoundingBox structureboundingbox, boolean flag) {
        return reactor.isAreaLoaded(structureboundingbox.a, structureboundingbox.b, structureboundingbox.c, structureboundingbox.d, structureboundingbox.e, structureboundingbox.f/*, flag*/);
    }

    private boolean isAreaLoaded(int i, int j, int k, int l, int i1, int j1, boolean flag) {
        return reactor.isAreaLoaded(i, j, k, l, i1, j1/*, flag*/);
    }

    protected abstract boolean isChunkLoaded(int i, int j, boolean flag);

    public Chunk getChunkAtWorldCoords(BlockPosition blockposition) {
        return reactor.getChunkAt(blockposition);
    }

    public Chunk getChunkAt(int i, int j) {
        return reactor.getChunkAt(i, j);
    }

    public boolean b(int i, int j) {
        return reactor.isChunkGeneratedAt(i, j);
    }

    /**
     * Sets the block state at a given location, flags can be added together.
     * Flag 1 will cause a block update.
     * Flag 2 will send the change to clients (you almost always want this).
     * Flag 4 prevents the block from being re-rendered, if this is a client world.
     */
    public boolean setTypeAndData(BlockPosition blockposition, IBlockData iblockdata, int flags) {
        return reactor.setTypeAndData(blockposition, iblockdata, flags);
    }

    // CraftBukkit start - Split off from above in order to directly send client and physic updates
    public void notifyAndUpdatePhysics(BlockPosition blockposition, Chunk chunk, IBlockData oldBlock, IBlockData newBlock, int i) {
        reactor.notifyAndUpdatePhysics(blockposition, chunk, oldBlock, newBlock, i);
    }
    // CraftBukkit end

    public boolean setAir(BlockPosition blockposition) {
        return reactor.setAir(blockposition);
    }

    public boolean setAir(BlockPosition blockposition, boolean flag) {
        return reactor.setAir(blockposition, flag);
    }

    public boolean setTypeUpdate(BlockPosition blockposition, IBlockData iblockdata) {
        return reactor.setTypeUpdate(blockposition, iblockdata);
    }

    public void notify(BlockPosition blockposition, IBlockData iblockdata, IBlockData iblockdata1, int i) {
        reactor.notifyBlockUpdate(blockposition, iblockdata, iblockdata1);
    }

    public void update(BlockPosition blockposition, Block block, boolean flag) {
        reactor.update(blockposition, block, flag);
    }

    public void a(int i, int j, int k, int l) {
        reactor.markVerticalBlocksDirty(i, j, k, l);
    }

    // PAIL: markBlockRangeForRenderUpdate (client-side only)
    public void b(BlockPosition blockposition, BlockPosition blockposition1) {
        this.b(blockposition.getX(), blockposition.getY(), blockposition.getZ(), blockposition1.getX(), blockposition1.getY(), blockposition1.getZ());
    }

    // PAIL: markBlockRangeForRenderUpdate (client-side only)
    public void b(int i, int j, int k, int l, int i1, int j1) {
        ;
    }

    public void c(BlockPosition blockposition, Block block) {
        reactor.updateObservingBlocksAt(blockposition, block);
    }

    public void applyPhysics(BlockPosition blockposition, Block block, boolean flag) {
        reactor.applyPhysics(blockposition, block, flag);
    }

    public void a(BlockPosition blockposition, Block block, EnumDirection enumdirection) {
        reactor.applyPhysicsExcept(blockposition, block, enumdirection);
    }

    /** PAIL: neighborChanged */
    public void a(BlockPosition blockposition, final Block block, BlockPosition blockposition1) {
        reactor.neighborChanged(blockposition, block, blockposition1);
    }

    public void b(BlockPosition blockposition, final Block block, BlockPosition blockposition1) {
        reactor.observedNeighborChanged(blockposition, block, blockposition1);
    }

    public boolean a(BlockPosition blockposition, Block block) {
        return false;
    }

    public boolean h(BlockPosition blockposition) {
        return reactor.canSeeSkyAt(blockposition);
    }

    public boolean i(BlockPosition blockposition) {
        return reactor.canActualSeeSkyAt(blockposition);
    }

    public int j(BlockPosition blockposition) {
        return reactor.getUnsubtractedLight(blockposition);
    }

    // Paper start - test if meets light level, return faster
    // logic copied from below
    public boolean isLightLevel(BlockPosition blockposition, int level) {
        return reactor.isLightLevel(blockposition, level);
    }
    // Paper end

    public int getLightLevel(BlockPosition blockposition) {
        return reactor.getLightLevelFromNeighbors(blockposition);
    }

    public int c(BlockPosition blockposition, boolean flag) {
        return reactor.getLightLevelAt(blockposition, flag);
    }

    public BlockPosition getHighestBlockYAt(BlockPosition blockposition) {
        return reactor.getHighestBlockAt(blockposition);
    }

    public int c(int i, int j) {
        return reactor.getHeighestY(i, j);
    }

    @Deprecated
    public int d(int i, int j) {
        return reactor.getChunksLowestHorizon(i, j);
    }

    public int getBrightness(EnumSkyBlock enumskyblock, BlockPosition blockposition) {
        return reactor.getBrightness(enumskyblock, blockposition);
    }

    public void a(EnumSkyBlock enumskyblock, BlockPosition blockposition, int i) {
        reactor.setLightFor(enumskyblock, blockposition, i);
    }

    // PAIL: notifyLightSet (client-side only)
    public void m(BlockPosition blockposition) {
        ;
    }

    public float n(BlockPosition blockposition) {
        return reactor.getLightBrightness(blockposition);
    }

    // Paper start - reduces need to do isLoaded before getType
    public IBlockData getTypeIfLoaded(BlockPosition blockposition) {
        return reactor.getTypeIfLoaded(blockposition);
    }
    // Paper end

    @Override
    public IBlockData getType(BlockPosition blockposition) {
        return reactor.getType(blockposition);
    }

    // Paper start
    private IBlockData getCapturedBlockType(int x, int y, int z) {
        return reactor.getCapturedBlockType(x, y, z);
    }
    // Paper end

    public boolean B() {
        return reactor.isDayTime();
    }

    @Nullable
    public MovingObjectPosition rayTrace(Vec3D vec3d, Vec3D vec3d1) {
        return reactor.rayTraceBlocks(vec3d, vec3d1);
    }

    @Nullable
    public MovingObjectPosition rayTrace(Vec3D vec3d, Vec3D vec3d1, boolean flag) {
        return reactor.rayTraceBlocks(vec3d, vec3d1, flag);
    }

    @Nullable
    public MovingObjectPosition rayTrace(Vec3D vec3d, Vec3D vec3d1, boolean flag, boolean flag1, boolean flag2) {
        return reactor.rayTraceBlocks(vec3d, vec3d1, flag, flag1, flag2);
    }

    public void a(@Nullable EntityHuman entityhuman, BlockPosition blockposition, SoundEffect soundeffect, SoundCategory soundcategory, float f, float f1) {
        reactor.playSoundNearbyExpect(entityhuman, blockposition, soundeffect, soundcategory, f, f1);
    }

    public void a(@Nullable EntityHuman entityhuman, double d0, double d1, double d2, SoundEffect soundeffect, SoundCategory soundcategory, float f, float f1) {
        reactor.playSoundNearbyExpect(entityhuman, soundeffect, soundcategory, d0, d1, d2, f, f1);
    }

    public void a(double d0, double d1, double d2, SoundEffect soundeffect, SoundCategory soundcategory, float f, float f1, boolean flag) {}

    // PAIL: playRecord (client-side only)
    public void a(BlockPosition blockposition, @Nullable SoundEffect soundeffect) {
        ;
    }

    public void addParticle(EnumParticle enumparticle, double d0, double d1, double d2, double d3, double d4, double d5, int... aint) {
        this.a(enumparticle.c(), enumparticle.e(), d0, d1, d2, d3, d4, d5, aint);
    }

    // PAIL: spawnAlwaysVisibleParticle (client-side only)
    public void a(int i, double d0, double d1, double d2, double d3, double d4, double d5, int... aint) {
        ;
    }

    // PAIL: spawnParticle (client-side only)
    private void a(int i, boolean flag, double d0, double d1, double d2, double d3, double d4, double d5, int... aint) {
        ;
    }

    public boolean strikeLightning(Entity entity) {
        return reactor.strikeLightning(entity);
    }

    public boolean addEntity(Entity entity) {
        // CraftBukkit start - Used for entities other than creatures
        return reactor.addEntity(entity);
    }

    public boolean addEntity(Entity entity, SpawnReason spawnReason) { // Changed signature, added SpawnReason
        return reactor.addEntity(entity, spawnReason);
    }

    /** PAIL: onEntityAdded */
    protected void b(Entity entity) {
        reactor.onEntityAdded(entity);
    }

    /** PAIL: onEntityRemove */
    protected void c(Entity entity) {
        reactor.onEntityRemove(entity);
    }

    public void kill(Entity entity) {
        reactor.kill(entity);
    }

    public void removeEntity(Entity entity) {
        reactor.removeEntity(entity);
    }

    /**
     * Add a world event listener
     */
    public void addIWorldAccess(IWorldAccess access) {
        reactor.addWorldListener(access);
    }

    private boolean a(@Nullable Entity entity, AxisAlignedBB axisalignedbb, boolean flag, @Nullable List<AxisAlignedBB> list) {
        return reactor.collidesWith(entity, axisalignedbb, flag, list);
    }

    public List<AxisAlignedBB> getCubes(@Nullable Entity entity, AxisAlignedBB axisalignedbb) {
        return reactor.getCollisionBoxes(entity, axisalignedbb);
    }

    public boolean g(Entity entity) {
        return reactor.isWithinBorder(entity);
    }

    public boolean a(AxisAlignedBB axisalignedbb) {
        return reactor.collidesWithAnyBlock(axisalignedbb);
    }

    public int a(float f) {
        return reactor.calculateSkylightSubtracted(f);
    }

    public float c(float f) {
        return reactor.getCelestialAngle(f);
    }

    public float E() {
        return reactor.getCurrentMoonPhaseFactor();
    }

    public float d(float f) {
        return reactor.getCelestialAngleRadians(f);
    }

    public BlockPosition p(BlockPosition blockposition) {
        return this.getChunkAtWorldCoords(blockposition).f(blockposition);
    }

    public BlockPosition getTopSolidOrLiquidBlock(BlockPosition position) { return this.q(position); } // OBFHELPER
    public BlockPosition q(BlockPosition blockposition) {
        return reactor.getTopSolidOrLiquidBlock(blockposition);
    }

    public boolean b(BlockPosition blockposition, Block block) {
        return true; // PAIL: isUpdateScheduled
    }

    public void a(BlockPosition blockposition, Block block, int i) {} // scheduleUpdate

    public void a(BlockPosition blockposition, Block block, int i, int j) {} // updateBlockTick

    public void b(BlockPosition blockposition, Block block, int i, int j) {} // scheduleBlockUpdate

    public void tickEntities() {
        reactor.tickEntities();
    }

    protected void l() {} // tickPlayers

    public boolean a(TileEntity tileentity) {
        return reactor.addTileEntity(tileentity);
    }

    public void b(Collection<TileEntity> collection) {
        reactor.addTileEntities(collection);
    }

    /**
     * <b>PAIL: updateEntity</b>
     * <p>Forcefully updates the entity
     */
    public void h(Entity entity) {
        reactor.entityJoinedWorld(entity, true);
    }

    /**
     * Updates the entity in the world if the chunk the entity is in is currently loaded or its forced to update
     */
    public void entityJoinedWorld(Entity entity, boolean forceUpdate) {
        reactor.entityJoinedWorld(entity, forceUpdate);
    }

    public boolean b(AxisAlignedBB axisalignedbb) {
        return reactor.checkNoEntityCollision(axisalignedbb);
    }

    // Paper start - Based on method below
    /**
     * @param axisalignedbb area to search within
     * @param entity causing the action ex. block placer
     * @return if there are no visible players colliding
     */
    public boolean checkNoVisiblePlayerCollisions(AxisAlignedBB axisalignedbb, @Nullable Entity entity) {
        return reactor.checkNoVisiblePlayerCollisions(axisalignedbb, entity);
    }
    // Paper end

    public boolean a(AxisAlignedBB axisalignedbb, @Nullable Entity entity) {
        return reactor.checkNoEntityCollision(axisalignedbb, entity);
    }

    public boolean c(AxisAlignedBB axisalignedbb) {
        return reactor.containsBlock(axisalignedbb);
    }

    public boolean containsLiquid(AxisAlignedBB axisalignedbb) {
        return reactor.containsLiquid(axisalignedbb);
    }

    public boolean e(AxisAlignedBB axisalignedbb) {
        return reactor.containsFlammable(axisalignedbb);
    }

    public boolean a(AxisAlignedBB axisalignedbb, Material material, Entity entity) {
        return reactor.handleMaterialAcceleration(axisalignedbb, material, entity);
    }

    public boolean a(AxisAlignedBB axisalignedbb, Material material) {
        return reactor.containsMaterial(axisalignedbb, material);
    }

    public Explosion explode(@Nullable Entity entity, double d0, double d1, double d2, float f, boolean flag) {
        return reactor.explode(entity, d0, d1, d2, f, flag);
    }

    public Explosion createExplosion(@Nullable Entity entity, double d0, double d1, double d2, float f, boolean flag, boolean flag1) {
        return reactor.createExplosion(entity, d0, d1, d2, f, flag, flag1);
    }

    public float a(Vec3D vec, AxisAlignedBB box) {
        return reactor.getBlockDensity(vec, box);
    }

    public boolean douseFire(@Nullable EntityHuman entityhuman, BlockPosition blockposition, EnumDirection enumdirection) {
        return reactor.douseFire(entityhuman, blockposition, enumdirection);
    }

    @Override
    @Nullable
    public TileEntity getTileEntity(BlockPosition blockposition) {
        return reactor.getTileEntity(blockposition);
    }

    @Nullable
    private TileEntity F(BlockPosition blockposition) {
        return reactor.getAddedTileEntity(blockposition);
    }

    public void setTileEntity(BlockPosition blockposition, @Nullable TileEntity tileentity) {
        reactor.setTileEntity(blockposition, tileentity);
    }

    public void s(BlockPosition blockposition) {
        reactor.removeTileEntity(blockposition);
    }

    public void b(TileEntity tileentity) {
        reactor.markTileEntityForRemoval(tileentity);
    }

    public boolean t(BlockPosition blockposition) {
        return reactor.isBlockFullCube(blockposition);
    }

    public boolean d(BlockPosition blockposition, boolean flag) {
        return reactor.isBlockNormalCube(blockposition, flag);
    }

    public void H() {
        reactor.initSkylight();
    }

    public void setSpawnFlags(boolean flag, boolean flag1) {
        reactor.setSpawnFlags(flag, flag1);
    }

    public void doTick() {
        this.t(); // Link to tick weather in WorldServer
    }

    protected void I() {
        reactor.initWeather();
    }

    protected void t() {
        reactor.tickWeather();
    }

    protected void j() {} // updateBlocks

    public void a(BlockPosition blockposition, IBlockData iblockdata, Random random) {
        reactor.immediateBlockTick(blockposition, iblockdata, random);
    }

    public boolean u(BlockPosition blockposition) {
        return reactor.canBlockFreezeWater(blockposition);
    }

    public boolean v(BlockPosition blockposition) {
        return reactor.canBlockFreezeSelf(blockposition);
    }

    public boolean e(BlockPosition blockposition, boolean flag) {
        return reactor.canBlockFreeze(blockposition, flag);
    }

    private boolean G(BlockPosition blockposition) {
        return reactor.isWater(blockposition);
    }

    public boolean f(BlockPosition blockposition, boolean flag) {
        return reactor.canSnowAt(blockposition, flag);
    }

    public boolean w(BlockPosition blockposition) {
        return reactor.checkLightAt(blockposition);
    }

    private int a(BlockPosition blockposition, EnumSkyBlock enumskyblock) {
        return reactor.getRawLight(blockposition, enumskyblock);
    }

    public boolean c(EnumSkyBlock enumskyblock, BlockPosition blockposition) {
        return reactor.checkLightFor(enumskyblock, blockposition);
    }

    /**
     * Runs through the list of updates to run and ticks them
     */
    public boolean a(boolean flag) {
        return false; // PAIL: tickUpdates
    }

    @Nullable
    public List<NextTickListEntry> a(Chunk chunk, boolean flag) {
        return null;
    }

    @Nullable
    public List<NextTickListEntry> a(StructureBoundingBox structureboundingbox, boolean flag) {
        return null;
    }

    public List<Entity> getEntities(@Nullable Entity entity, AxisAlignedBB axisalignedbb) {
        return this.getEntities(entity, axisalignedbb, IEntitySelector.e);
    }

    public List<Entity> getEntities(@Nullable Entity entity, AxisAlignedBB axisalignedbb, @Nullable Predicate<? super Entity> predicate) {
        return reactor.getEntities(entity, axisalignedbb, predicate);
    }

    public <T extends Entity> List<T> a(Class<? extends T> oclass, Predicate<? super T> predicate) {
        return reactor.getEntitiesOf(oclass, predicate);
    }

    public <T extends Entity> List<T> b(Class<? extends T> oclass, Predicate<? super T> predicate) {
        return reactor.getPlayersOf(oclass, predicate);
    }

    public <T extends Entity> List<T> a(Class<? extends T> oclass, AxisAlignedBB axisalignedbb) {
        return reactor.getEntitiesOf(oclass, axisalignedbb);
    }

    public <T extends Entity> List<T> a(Class<? extends T> oclass, AxisAlignedBB axisalignedbb, @Nullable Predicate<? super T> predicate) {
        return reactor.getEntitiesOf(oclass, axisalignedbb, predicate);
    }

    @Nullable
    public <T extends Entity> T a(Class<? extends T> oclass, AxisAlignedBB axisalignedbb, T t0) {
        return reactor.findNearestEntityWithinBoundingBox(oclass, axisalignedbb, t0);
    }

    @Nullable
    public Entity getEntity(int i) {
        return reactor.getEntity(i);
    }

    public void b(BlockPosition blockposition, TileEntity tileentity) {
        reactor.markChunkDirty(blockposition);
    }

    public int a(Class<?> oclass) {
        return reactor.countEntities(oclass);
    }

    public void a(Collection<Entity> collection) {
        reactor.addEntities(collection);
    }

    public void c(Collection<Entity> collection) {
        reactor.unloadEntities(collection);
    }

    public boolean a(Block block, BlockPosition blockposition, boolean flag, EnumDirection enumdirection, @Nullable Entity entity) {
        return reactor.canBuild(block, blockposition, flag, enumdirection, entity);
    }

    public int K() {
        return reactor.getSeaLevel();
    }

    public void b(int i) {
        reactor.setSeaLevel(i);
    }

    @Override
    public int getBlockPower(BlockPosition blockposition, EnumDirection enumdirection) {
        return reactor.getBlockPower(blockposition, enumdirection);
    }

    public WorldType L() {
        return reactor.getWorldType();
    }

    public int getBlockPower(BlockPosition blockposition) {
        return reactor.getBlockPower(blockposition);
    }

    public boolean isBlockFacePowered(BlockPosition blockposition, EnumDirection enumdirection) {
        return reactor.isBlockFacePowered(blockposition, enumdirection);
    }

    public int getBlockFacePower(BlockPosition blockposition, EnumDirection enumdirection) {
        return reactor.getBlockFacePower(blockposition, enumdirection);
    }

    public boolean isBlockIndirectlyPowered(BlockPosition blockposition) {
        return reactor.isBlockPowered(blockposition);
    }

    public int z(BlockPosition blockposition) {
        return reactor.getBlockIndirectlyPower(blockposition);
    }

    @Nullable
    public EntityHuman findNearbyPlayer(Entity entity, double d0) {
        return reactor.findClosestPlayer(entity, d0, false);
    }

    @Nullable
    public EntityHuman b(Entity entity, double d0) {
        return reactor.findClosestPlayer(entity, d0, true);
    }

    @Nullable
    public EntityHuman a(double d0, double d1, double d2, double d3, boolean flag) {
        return reactor.findClosestPlayer(d0, d1, d2, d3, flag);
    }

    @Nullable
    public EntityHuman a(double d0, double d1, double d2, double d3, Predicate<Entity> predicate) {
        return reactor.findClosestPlayer(d0, d1, d2, d3, predicate);
    }

    public boolean isPlayerNearby(double d0, double d1, double d2, double d3) {
        return reactor.isPlayerNearby(d0, d1, d2, d3);
    }

    @Nullable
    public EntityHuman a(Entity entity, double d0, double d1) {
        return reactor.findNearestAttackablePlayer(entity, d0, d1);
    }

    @Nullable
    public EntityHuman a(BlockPosition blockposition, double d0, double d1) {
        return reactor.findNearestAttackablePlayer(blockposition, d0, d1);
    }

    @Nullable
    public EntityHuman a(double d0, double d1, double d2, double d3, double d4, @Nullable Function<EntityHuman, Double> function, @Nullable Predicate<EntityHuman> predicate) {
        return reactor.findNearestAttackablePlayer(d0, d1, d2, d3, d4, function, predicate);
    }

    @Nullable
    public EntityHuman a(String s) {
        return reactor.getPlayerByName(s);
    }

    @Nullable
    public EntityHuman b(UUID uuid) {
        return reactor.getPlayerByUUID(uuid);
    }

    public void checkSession() throws ExceptionWorldConflict {
        reactor.checkSession();
    }

    public long getSeed() {
        return reactor.getSeed();
    }

    public long getTime() {
        return reactor.getTime();
    }

    public long getDayTime() {
        return reactor.getDayTime();
    }

    public void setDayTime(long i) {
        reactor.setDayTime(i);
    }

    public BlockPosition getSpawn() {
        return reactor.getSpawn();
    }

    public void A(BlockPosition blockposition) {
        reactor.setSpawn(blockposition);
    }

    public boolean a(EntityHuman entityhuman, BlockPosition blockposition) {
        return true;
    }

    public void broadcastEntityEffect(Entity entity, byte b0) {}

    public IChunkProvider getChunkProvider() {
        return reactor.getChunkProvider();
    }

    public void playBlockAction(BlockPosition blockposition, Block block, int i, int j) {
        reactor.playBlockAction(blockposition, block, i, j);
    }

    public IDataManager getDataManager() {
        return reactor.getDataManager();
    }

    public WorldData getWorldData() {
        return reactor.getWorldData();
    }

    public GameRules getGameRules() {
        return reactor.getGameRules();
    }

    public void everyoneSleeping() {}

    // CraftBukkit start
    // Calls the method that checks to see if players are sleeping
    // Called by CraftPlayer.setPermanentSleeping()
    public void checkSleepStatus() {
        everyoneSleeping();
    }
    // CraftBukkit end

    public float h(float f) {
        return reactor.currentThunderingStrength(f);
    }

    public float j(float f) {
        return reactor.currentRainingStrength(f);
    }

    public boolean V() {
        return reactor.isThundering();
    }

    public boolean W() {
        return reactor.isRaining();
    }

    public boolean isRainingAt(BlockPosition blockposition) {
        return reactor.isRainingAt(blockposition);
    }

    public boolean C(BlockPosition blockposition) {
        return reactor.isHumidAt(blockposition);
    }

    @Nullable
    public PersistentCollection X() {
        return worldMaps;
    }

    public void a(String s, PersistentBase persistentbase) {
        reactor.setMapData(s, persistentbase);
    }

    @Nullable
    public PersistentBase a(Class<? extends PersistentBase> oclass, String s) {
        return reactor.loadMapData(oclass, s);
    }

    public int b(String s) {
        return reactor.getUniqueDataId(s);
    }

    // PAIL: broadcastSound (not-implement)
    public void a(int i, BlockPosition blockposition, int j) {
        ;
    }

    public void triggerEffect(int i, BlockPosition blockposition, int j) {
        reactor.triggerEvent(i, blockposition, j);
    }

    public void a(@Nullable EntityHuman entityhuman, int i, BlockPosition blockposition, int j) {
        reactor.playWorldEventNearbyExpect(entityhuman, i, blockposition, j);
    }

    public int getHeight() {
        return reactor.getWorldHeight();
    }

    public int getActualWorldHeight() { return this.Z(); } // OBFHELPER
    public int Z() {
        return reactor.getActualWorldHeight();
    }

    public Random a(int i, int j, int k) {
        return reactor.setRandomSeed(i, j, k);
    }

    public CrashReportSystemDetails a(CrashReport crashreport) {
        return reactor.addInfoToCrashReport(crashreport);
    }

    public void c(int i, BlockPosition blockposition, int j) {
        reactor.sendBlockBreakProgress(i, blockposition, j);
    }

    public Calendar ac() {
        return reactor.currentDate();
    }

    public Scoreboard getScoreboard() {
        return scoreboard;
    }

    public void updateAdjacentComparators(BlockPosition blockposition, Block block) {
        reactor.updateAdjacentComparators(blockposition, block);
    }

    public DifficultyDamageScaler createDamageScaler(BlockPosition position) { return this.D(position); } // OBFHELPER
    public DifficultyDamageScaler D(BlockPosition blockposition) {
        return reactor.createDamageScaler(blockposition);
    }

    public EnumDifficulty getDifficulty() {
        return reactor.getDifficulty();
    }

    public int af() {
        return reactor.getSkylightSubtracted();
    }

    public void c(int i) {
        reactor.setSkylightSubtracted(i);
    }

    public void d(int i) {
        // this.K = i; // unused field
    }

    public PersistentVillage ai() {
        return villages;
    }

    public WorldBorder getWorldBorder() {
        return reactor.getWorldBorder();
    }

    public boolean shouldStayLoaded(int i,  int j) { return e(i, j); } // Paper - OBFHELPER
    public boolean e(int i, int j) {
        return reactor.shouldStayLoaded(i, j);
    }

    public void a(Packet<?> packet) {
        throw new UnsupportedOperationException("Can\'t send packets to server unless you\'re on the client."); // TODO
    }

    public LootTableRegistry ak() {
        return B;
    }

    @Nullable
    public BlockPosition a(String s, BlockPosition blockposition, boolean flag) {
        return null;
    }
}
