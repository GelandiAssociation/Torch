package net.minecraft.server;

import com.google.common.util.concurrent.ListenableFuture;

import co.aikar.timings.TimedChunkGenerator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import org.apache.logging.log4j.Logger;
import org.bukkit.craftbukkit.CraftTravelAgent;
import org.bukkit.craftbukkit.generator.CustomChunkGenerator;
import org.bukkit.craftbukkit.generator.NetherChunkGenerator;
import org.bukkit.craftbukkit.generator.NormalChunkGenerator;
import org.bukkit.craftbukkit.generator.SkyLandsChunkGenerator;
import org.bukkit.craftbukkit.util.HashTreeSet;

import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
// CraftBukkit end
import org.torch.server.TorchServer;
import static org.torch.server.TorchServer.logger;

public class WorldServer extends World implements IAsyncTaskHandler {
    private static final Logger a = logger;
    public boolean stopPhysicsEvent = false; // Paper
    private final TorchServer server;
    public EntityTracker tracker;
    private final PlayerChunkMap manager;
    // private final Set<NextTickListEntry> nextTickListHash = Sets.newHashSet();
    private final HashTreeSet<NextTickListEntry> nextTickList; // CraftBukkit - HashTreeSet
    private final Map<UUID, Entity> entitiesByUUID;
    public boolean savingDisabled;
    
    /** allPlayersSleeping */
    //private boolean O;
    
    //private int emptyTime;
    private PortalTravelAgent portalTravelAgent;
    private final SpawnerCreature spawnerCreature;
    protected final VillageSiege siegeManager;
    private final WorldServer.BlockActionDataList[] S;
    
    /** blockEventCacheIndex */
    //private int T;
    /** pendingTickListEntriesThisTick */
    // private final List<NextTickListEntry> U; // Torch - List -> Set

    // CraftBukkit start
    public final int dimension;

    // Add env and gen to constructor
    public WorldServer(MinecraftServer minecraftserver, IDataManager idatamanager, WorldData worlddata, int i, MethodProfiler methodprofiler, org.bukkit.World.Environment env, org.bukkit.generator.ChunkGenerator gen) {
        super(idatamanager, worlddata, DimensionManager.a(env.getId()).d(), methodprofiler, false, gen, env);
        
        allowMonsters = true;
        allowAnimals = true;
        
        dimension = reactor.dimension = i;
        
        /////////
        server = reactor.getServer();
        nextTickList = reactor.getScheduledBlocks();
        entitiesByUUID = reactor.getEntitiesByUUID();
        savingDisabled = reactor.isSavingDisabled();
        
        spawnerCreature = reactor.getSpawnerCreature().getServant();
        siegeManager = reactor.getSiegeManager();
        S = reactor.getBlockEventQueue();
        //U = reactor.getPendingBlocks(); List -> Set
        ////////
        
        worldProvider.a(this);
        chunkProvider = createChunkProvider();
        
        reactor.siegeManager = new VillageSiege(this);
        reactor.portalTravelAgent = portalTravelAgent = new CraftTravelAgent(this); // CraftBukkit
        
        tracker = reactor.tracker = new EntityTracker(this);
        manager = reactor.playerChunkMap = new PlayerChunkMap(this, spigotConfig.viewDistance);
        
        reactor.setChunkProvider(chunkProvider);
        reactor.worldData.world = reactor.servant = this;
    }
    
    public IChunkProvider createChunkProvider() {
        IChunkLoader ichunkloader = this.dataManager.createChunkLoader(worldProvider);

        org.bukkit.craftbukkit.generator.InternalChunkGenerator gen;

        if (reactor.generator != null) {
            gen = new CustomChunkGenerator(this, this.getSeed(), reactor.generator);
        } else if (worldProvider instanceof WorldProviderHell) {
            gen = new NetherChunkGenerator(this, this.getSeed());
        } else if (worldProvider instanceof WorldProviderTheEnd) {
            gen = new SkyLandsChunkGenerator(this, this.getSeed());
        } else {
            gen = new NormalChunkGenerator(this, this.getSeed());
        }

        return new ChunkProviderServer(this, ichunkloader, new TimedChunkGenerator(this, gen));
    }

    @Override
    public World b() {
        return reactor.init().getServant();
    }

    // CraftBukkit start
    @Override
    public TileEntity getTileEntity(BlockPosition pos) {
        return reactor.getTileEntity(pos);
    }

    private TileEntity fixTileEntity(BlockPosition pos, Block type, TileEntity found) {
        return reactor.fixTileEntity(pos, type, found);
    }

    private boolean canSpawn(int x, int z) {
        return reactor.canSpawnAt(x, z);
    }
    // CraftBukkit end

    @Override
    public void doTick() {
        reactor.doTick();
    }

    @Nullable
    public BiomeBase.BiomeMeta a(EnumCreatureType enumcreaturetype, BlockPosition blockposition) {
        return reactor.createRandomSpawnEntry(enumcreaturetype, blockposition);
    }

    public boolean a(EnumCreatureType enumcreaturetype, BiomeBase.BiomeMeta biomebase_biomemeta, BlockPosition blockposition) {
        return reactor.possibleToSpawn(enumcreaturetype, biomebase_biomemeta, blockposition);
    }

    @Override
    public void everyoneSleeping() {
        reactor.checkEveryoneSleeping();
    }

    protected void f() {
        reactor.wakeAllPlayers();
    }

    private void c() {
        reactor.resetRainAndThunder();
    }

    public boolean everyoneDeeplySleeping() {
        return reactor.everyoneDeeplySleeping();
    }

    @Override
    protected boolean isChunkLoaded(int i, int j, boolean flag) {
        return reactor.isChunkLoaded(i, j);
    }

    protected void i() {
        reactor.updateRandomLight();
    }

    @Override
    protected void j() {
        reactor.tickChunks();
    }

    protected BlockPosition a(BlockPosition blockposition) {
        return reactor.adjustToNearbyEntity(blockposition);
    }

    @Override
    public boolean a(BlockPosition blockposition, Block block) {
        return reactor.isBlockUpdatePending(blockposition, block);
    }

    @Override
    public boolean b(BlockPosition blockposition, Block block) {
        return reactor.isBlockUpdateScheduled(blockposition, block);
    }

    @Override
    public void a(BlockPosition blockposition, Block block, int i) {
        reactor.scheduleBlockUpdate(blockposition, block, i);
    }

    @Override
    public void a(BlockPosition blockposition, Block block, int i, int j) {
        reactor.updateBlockTick(blockposition, block, i, j);
    }

    @Override
    public void b(BlockPosition blockposition, Block block, int i, int j) {
        reactor.scheduleBlockUpdate(blockposition, block, i, j);
    }

    @Override
    public void tickEntities() {
        /* if (false && this.getReactor().players.isEmpty()) { // CraftBukkit - this prevents entity cleanup, other issues on servers with no players
            if (this.emptyTime++ >= 300) {
                return;
            }
        } else {
            this.m();
        } */

        reactor.tickEntities();
    }

    @Override
    protected void l() {
        reactor.tickPlayers();
    }

    public void m() {
        //this.emptyTime = 0; // unused
    }

    @Override
    public boolean a(boolean flag) {
        return reactor.updateBlocks(flag);
    }

    @Override
    @Nullable
    public List<NextTickListEntry> a(Chunk chunk, boolean flag) {
        return reactor.getPendingUpdateBlocks(chunk, flag);
    }

    @Override
    @Nullable
    public List<NextTickListEntry> a(StructureBoundingBox structureboundingbox, boolean flag) {
        return reactor.getPendingUpdateBlocks(structureboundingbox, flag);
    }

    /* CraftBukkit start - We prevent spawning in general, so this butchering is not needed
    public void entityJoinedWorld(Entity entity, boolean flag) {
        if (!this.getSpawnAnimals() && (entity instanceof EntityAnimal || entity instanceof EntityWaterAnimal)) {
            entity.die();
        }

        if (!this.getSpawnNPCs() && entity instanceof NPC) {
            entity.die();
        }

        super.entityJoinedWorld(entity, flag);
    }
    // CraftBukkit end */

    private boolean getSpawnNPCs() {
        return this.server.isSpawnNPCs();
    }

    private boolean getSpawnAnimals() {
        return this.server.isSpawnAnimals();
    }

    @Override
    public IChunkProvider n() {
        return createChunkProvider();
    }

    public List<TileEntity> getTileEntities(int i, int j, int k, int l, int i1, int j1) {
        return reactor.getTileEntities(i, j, k, l, i1, j1);
    }

    @Override
    public boolean a(EntityHuman entityhuman, BlockPosition blockposition) {
        return reactor.canBuildAt(entityhuman, blockposition);
    }

    @Override
    public void a(WorldSettings worldsettings) {
        reactor.initialize(worldsettings);
    }

    private void an() { // setDebugWorldSettings
        reactor.setDebugWorldSettings();
    }

    private void b(WorldSettings worldsettings) {
        reactor.createSpawnPoint(worldsettings);
    }

    protected void o() {
        reactor.createBonusChest();
    }

    @Nullable
    public BlockPosition getDimensionSpawn() {
        return reactor.getDimensionSpawn();
    }

    public void save(boolean flag, @Nullable IProgressUpdate iprogressupdate) throws ExceptionWorldConflict {
        reactor.saveChunks(flag, iprogressupdate);
    }

    public void flushSave() {
        reactor.flushSave();
    }

    protected void a() throws ExceptionWorldConflict {
        reactor.saveLevel();
    }

    // CraftBukkit start
    @Override
    public boolean addEntity(Entity entity, SpawnReason spawnReason) { // Changed signature, added SpawnReason
        // World.addEntity(Entity) will call this, and we still want to perform
        // existing entity checking when it's called with a SpawnReason
        return reactor.addEntity(entity);
    }
    // CraftBukkit end

    @Override
    public void a(Collection<Entity> collection) {
        reactor.addEntities(collection);
    }

    private boolean j(Entity entity) {
        return reactor.canAddEntity(entity);
    }

    @Override
    protected void b(Entity entity) {
        reactor.onEntityAdded(entity);
    }

    @Override
    protected void c(Entity entity) {
        reactor.onEntityRemove(entity);
    }

    @Override
    public boolean strikeLightning(Entity entity) {
        return reactor.strikeLightning(entity);
    }

    @Override
    public void broadcastEntityEffect(Entity entity, byte b0) {
        reactor.broadcastEntityEffect(entity, b0);
    }

    public ChunkProviderServer getChunkProviderServer() {
        return (ChunkProviderServer) reactor.getChunkProvider();
    }

    @Override
    public Explosion createExplosion(@Nullable Entity entity, double d0, double d1, double d2, float f, boolean flag, boolean flag1) {
        return reactor.createExplosion(entity, d0, d1, d2, f, flag, flag1);
    }

    @Override
    public void playBlockAction(BlockPosition blockposition, Block block, int i, int j) {
        reactor.playBlockAction(blockposition, block, i, j);
    }

    private void ao() {
        reactor.sendQueuedBlockActions();
    }

    private boolean a(BlockActionData blockactiondata) {
        return reactor.fireBlockAction(blockactiondata);
    }

    public void saveLevel() throws ExceptionWorldConflict {
        reactor.saveLevel();
    }

    @Override
    protected void t() {
        reactor.tickWeather();
    }

    @Override
    @Nullable
    public MinecraftServer getMinecraftServer() {
        return TorchServer.getMinecraftServer();
    }

    public EntityTracker getTracker() {
        return reactor.getTracker();
    }

    public PlayerChunkMap getPlayerChunkMap() {
        return reactor.getPlayerChunkMap();
    }

    public PortalTravelAgent getTravelAgent() {
        return reactor.getPortalTravelAgent();
    }

    public DefinedStructureManager y() {
        return reactor.getStructureTemplateManager();
    }

    public void a(EnumParticle enumparticle, double d0, double d1, double d2, int i, double d3, double d4, double d5, double d6, int... aint) {
        reactor.sendParticles(enumparticle, d1, d2, d3, i, d4, d5, d6, i, aint);
    }

    public void a(EnumParticle enumparticle, boolean flag, double d0, double d1, double d2, int i, double d3, double d4, double d5, double d6, int... aint) {
        // CraftBukkit - visibility api support
        reactor.sendParticles(enumparticle, flag, d1, d2, d3, i, d4, d5, d6, i, aint);
    }

    public void sendParticles(EntityPlayer sender, EnumParticle enumparticle, boolean flag, double d0, double d1, double d2, int i, double d3, double d4, double d5, double d6, int... aint) {
        // CraftBukkit end
        reactor.sendParticles(sender, enumparticle, flag, d1, d2, d3, i, d4, d5, d6, i, aint);
    }

    public void a(EntityPlayer entityplayer, EnumParticle enumparticle, boolean flag, double d0, double d1, double d2, int i, double d3, double d4, double d5, double d6, int... aint) {
        //PacketPlayOutWorldParticles packetplayoutworldparticles = new PacketPlayOutWorldParticles(enumparticle, flag, (float) d0, (float) d1, (float) d2, (float) d3, (float) d4, (float) d5, (float) d6, i, aint);
        //this.a(entityplayer, flag, d0, d1, d2, packetplayoutworldparticles);
        // TODO: check
        reactor.sendParticles(entityplayer, enumparticle, flag, d1, d2, d3, i, d4, d5, d6, i, aint);
    }

    private void a(EntityPlayer entityplayer, boolean flag, double d0, double d1, double d2, Packet<?> packet) {
        reactor.sendPacketWithinDistance(entityplayer, flag, d0, d1, d2, packet);
    }

    @Nullable
    public Entity getEntity(UUID uuid) {
        return reactor.getEntity(uuid);
    }

    @Override
    public ListenableFuture<Object> postToMainThread(Runnable runnable) {
        return reactor.postToMainThread(runnable);
    }

    @Override
    public boolean isMainThread() {
        return reactor.isMainThread();
    }

    @Override
    @Nullable
    public BlockPosition a(String s, BlockPosition blockposition, boolean flag) {
        return reactor.findNearestMapFeature(s, blockposition, flag);
    }

    @Override
    public IChunkProvider getChunkProvider() {
        return reactor.getChunkProvider();
    }

    @SuppressWarnings({ "hiding", "serial" })
    public static class BlockActionDataList<BlockActionData> extends ArrayList<BlockActionData> {

        public BlockActionDataList() {}

        BlockActionDataList(Object object) {
            this();
        }
    }
}
