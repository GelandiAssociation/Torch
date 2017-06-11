package net.minecraft.server;

import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListenableFuture;
import net.minecraft.server.BiomeBase.BiomeMeta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import javax.annotation.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
    private boolean O; // TODO: comment
    
    private int emptyTime; // TODO: comment
    private final PortalTravelAgent portalTravelAgent;
    private final SpawnerCreature spawnerCreature;
    protected final VillageSiege siegeManager;
    private final WorldServer.BlockActionDataList[] S;
    
    /** blockEventCacheIndex */
    private int T; // TODO: comment
    /** pendingTickListEntriesThisTick */
    private final List<NextTickListEntry> U;

    // CraftBukkit start
    public final int dimension;

    // Add env and gen to constructor
    public WorldServer(MinecraftServer minecraftserver, IDataManager idatamanager, WorldData worlddata, int i, MethodProfiler methodprofiler, org.bukkit.World.Environment env, org.bukkit.generator.ChunkGenerator gen) {
        super(idatamanager, worlddata, DimensionManager.a(env.getId()).d(), methodprofiler, false, gen, env);
        
        dimension = reactor.dimension = i;
        
        /////////
        server = reactor.getServer();
        tracker = reactor.getTracker();
        manager = reactor.getPlayerChunkMap();
        nextTickList = reactor.getNextTickList();
        entitiesByUUID = reactor.getEntitiesByUUID();
        savingDisabled = reactor.isSavingDisabled();
        
        portalTravelAgent = reactor.getPortalTravelAgent();
        spawnerCreature = reactor.getSpawnerCreature().getServant();
        siegeManager = reactor.getSiegeManager();
        S = reactor.getBlockEventQueue();
        U = reactor.getPendingTickListEntriesThisTick();
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
        super.doTick();
        if (this.getWorldData().isHardcore() && this.getDifficulty() != EnumDifficulty.HARD) {
            this.getWorldData().setDifficulty(EnumDifficulty.HARD);
        }

        this.worldProvider.k().b();
        if (this.everyoneDeeplySleeping()) {
            if (this.getGameRules().getBoolean("doDaylightCycle")) {
                long i = this.worldData.getDayTime() + 24000L;

                this.worldData.setDayTime(i - i % 24000L);
            }

            this.f();
        }

        this.methodProfiler.a("mobSpawner");
        // CraftBukkit start - Only call spawner if we have players online and the world allows for mobs or animals
        long time = this.worldData.getTime();
        if (this.getGameRules().getBoolean("doMobSpawning") && this.worldData.getType() != WorldType.DEBUG_ALL_BLOCK_STATES && (this.allowMonsters || this.allowAnimals) && (this instanceof WorldServer && this.getReactor().players.size() > 0)) {
            timings.mobSpawn.startTiming(); // Spigot
            this.spawnerCreature.a(this, this.allowMonsters && (this.getReactor().ticksPerMonsterSpawns != 0 && time % this.getReactor().ticksPerMonsterSpawns == 0L), this.allowAnimals && (this.getReactor().ticksPerAnimalSpawns != 0 && time % this.getReactor().ticksPerAnimalSpawns == 0L), this.worldData.getTime() % 400L == 0L);
            timings.mobSpawn.stopTiming(); // Spigot
            // CraftBukkit end
        }

        timings.doChunkUnload.startTiming(); // Spigot
        this.methodProfiler.c("chunkSource");
        this.chunkProvider.unloadChunks();
        int j = this.a(1.0F);

        if (j != this.af()) {
            this.c(j);
        }

        this.worldData.setTime(this.worldData.getTime() + 1L);
        if (this.getGameRules().getBoolean("doDaylightCycle")) {
            this.worldData.setDayTime(this.worldData.getDayTime() + 1L);
        }

        timings.doChunkUnload.stopTiming(); // Spigot
        this.methodProfiler.c("tickPending");
        timings.scheduledBlocks.startTiming(); // Paper
        this.a(false);
        timings.scheduledBlocks.stopTiming(); // Paper
        this.methodProfiler.c("tickBlocks");
        timings.chunkTicks.startTiming(); // Paper
        this.j();
        timings.chunkTicks.stopTiming(); // Paper
        this.methodProfiler.c("chunkMap");
        timings.doChunkMap.startTiming(); // Spigot
        this.manager.flush();
        timings.doChunkMap.stopTiming(); // Spigot
        this.methodProfiler.c("village");
        timings.doVillages.startTiming(); // Spigot
        this.villages.tick();
        this.siegeManager.a();
        timings.doVillages.stopTiming(); // Spigot
        this.methodProfiler.c("portalForcer");
        timings.doPortalForcer.startTiming(); // Spigot
        this.portalTravelAgent.a(this.getTime());
        timings.doPortalForcer.stopTiming(); // Spigot

        timings.doSounds.startTiming(); // Spigot
        this.ao();
        timings.doSounds.stopTiming(); // Spigot

        timings.doChunkGC.startTiming();// Spigot
        this.getWorld().processChunkGC(); // CraftBukkit
        timings.doChunkGC.stopTiming(); // Spigot
    }

    @Nullable public BiomeBase.BiomeMeta a(EnumCreatureType enumcreaturetype, BlockPosition blockposition) {
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
        this.O = false;
        Iterator iterator = this.getReactor().players.iterator();

        while (iterator.hasNext()) {
            EntityHuman entityhuman = (EntityHuman) iterator.next();

            if (entityhuman.isSleeping()) {
                entityhuman.a(false, false, true);
            }
        }

        if (this.getGameRules().getBoolean("doWeatherCycle")) {
            this.c();
        }

    }

    private void c() {
        this.worldData.setStorm(false);
        // CraftBukkit start
        // If we stop due to everyone sleeping we should reset the weather duration to some other random value.
        // Not that everyone ever manages to get the whole server to sleep at the same time....
        if (!this.worldData.hasStorm()) {
            this.worldData.setWeatherDuration(0);
        }
        // CraftBukkit end
        this.worldData.setThundering(false);
        // CraftBukkit start
        // If we stop due to everyone sleeping we should reset the weather duration to some other random value.
        // Not that everyone ever manages to get the whole server to sleep at the same time....
        if (!this.worldData.isThundering()) {
            this.worldData.setThunderDuration(0);
        }
        // CraftBukkit end
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
        this.i();
        if (this.worldData.getType() == WorldType.DEBUG_ALL_BLOCK_STATES) {
            Iterator iterator = this.manager.b();

            while (iterator.hasNext()) {
                ((Chunk) iterator.next()).b(false);
            }

        } else {
            int i = this.getGameRules().c("randomTickSpeed");
            boolean flag = this.W();
            boolean flag1 = this.V();

            this.methodProfiler.a("pollingChunks");

            for (Iterator iterator1 = this.manager.b(); iterator1.hasNext(); this.methodProfiler.b()) {
                this.methodProfiler.a("getChunk");
                Chunk chunk = (Chunk) iterator1.next();
                int j = chunk.locX * 16;
                int k = chunk.locZ * 16;

                this.methodProfiler.c("checkNextLight");
                chunk.n();
                this.methodProfiler.c("tickChunk");
                chunk.b(false);
                if ( !chunk.areNeighborsLoaded( 1 ) ) continue; // Spigot
                this.methodProfiler.c("thunder");
                int l;
                BlockPosition blockposition;

                // Paper - Disable thunder
                if (!this.paperConfig.disableThunder && flag && flag1 && this.random.nextInt(100000) == 0) {
                    this.l = this.l * 3 + 1013904223;
                    l = this.l >> 2;
                    blockposition = this.a(new BlockPosition(j + (l & 15), 0, k + (l >> 8 & 15)));
                    if (this.isRainingAt(blockposition)) {
                        DifficultyDamageScaler difficultydamagescaler = this.D(blockposition);

                        if (this.getGameRules().getBoolean("doMobSpawning") && this.random.nextDouble() < difficultydamagescaler.b() * paperConfig.skeleHorseSpawnChance) {
                            EntityHorseSkeleton entityhorseskeleton = new EntityHorseSkeleton(this);

                            entityhorseskeleton.p(true);
                            entityhorseskeleton.setAgeRaw(0);
                            entityhorseskeleton.setPosition(blockposition.getX(), blockposition.getY(), blockposition.getZ());
                            this.addEntity(entityhorseskeleton, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.LIGHTNING); // CraftBukkit
                            this.strikeLightning(new EntityLightning(this, blockposition.getX(), blockposition.getY(), blockposition.getZ(), true));
                        } else {
                            this.strikeLightning(new EntityLightning(this, blockposition.getX(), blockposition.getY(), blockposition.getZ(), false));
                        }
                    }
                }

                this.methodProfiler.c("iceandsnow");
                if (!this.paperConfig.disableIceAndSnow && this.random.nextInt(16) == 0) { // Paper - Disable ice and snow
                    this.l = this.l * 3 + 1013904223;
                    l = this.l >> 2;
                        blockposition = this.p(new BlockPosition(j + (l & 15), 0, k + (l >> 8 & 15)));
                        BlockPosition blockposition1 = blockposition.down();

                        if (this.v(blockposition1)) {
                            org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockFormEvent(this, blockposition1, Blocks.ICE, null); // CraftBukkit
                        }

                        if (flag && this.f(blockposition, true)) {
                            org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockFormEvent(this, blockposition, Blocks.SNOW_LAYER, null); // CraftBukkit
                        }

                        if (flag && this.getBiome(blockposition1).d()) {
                            this.getType(blockposition1).getBlock().h(this, blockposition1);
                        }
                }

                timings.chunkTicksBlocks.startTiming(); // Paper
                if (i > 0) {
                    ChunkSection[] achunksection = chunk.getSections();
                    int i1 = achunksection.length;

                    for (int j1 = 0; j1 < i1; ++j1) {
                        ChunkSection chunksection = achunksection[j1];

                        if (chunksection != Chunk.a && chunksection.shouldTick()) {
                            for (int k1 = 0; k1 < i; ++k1) {
                                this.l = this.l * 3 + 1013904223;
                                int l1 = this.l >> 2;
                            int i2 = l1 & 15;
                            int j2 = l1 >> 8 & 15;
                            int k2 = l1 >> 16 & 15;
                            IBlockData iblockdata = chunksection.getType(i2, k2, j2);
                            Block block = iblockdata.getBlock();

                            this.methodProfiler.a("randomTick");
                            if (block.isTicking()) {
                                block.a(this, new BlockPosition(i2 + j, k2 + chunksection.getYPosition(), j2 + k), iblockdata, this.random);
                            }


                            }
                        }
                    }
                }
                timings.chunkTicksBlocks.stopTiming(); // Paper
            }


        }
    }

    protected BlockPosition a(BlockPosition blockposition) {
        BlockPosition blockposition1 = this.p(blockposition);
        AxisAlignedBB axisalignedbb = (new AxisAlignedBB(blockposition1, new BlockPosition(blockposition1.getX(), this.getHeight(), blockposition1.getZ()))).g(3.0D);
        List list = this.a(EntityLiving.class, axisalignedbb, new Predicate() {
            public boolean a(@Nullable EntityLiving entityliving) {
                return entityliving != null && entityliving.isAlive() && WorldServer.this.h(entityliving.getChunkCoordinates());
            }

            @Override
            public boolean apply(@Nullable Object object) {
                return this.a((EntityLiving) object);
            }
        });

        if (!list.isEmpty()) {
            return ((EntityLiving) list.get(this.random.nextInt(list.size()))).getChunkCoordinates();
        } else {
            if (blockposition1.getY() == -1) {
                blockposition1 = blockposition1.up(2);
            }

            return blockposition1;
        }
    }

    @Override
    public boolean a(BlockPosition blockposition, Block block) {
        NextTickListEntry nextticklistentry = new NextTickListEntry(blockposition, block);

        return this.U.contains(nextticklistentry);
    }

    @Override
    public boolean b(BlockPosition blockposition, Block block) {
        NextTickListEntry nextticklistentry = new NextTickListEntry(blockposition, block);

        return this.nextTickList.contains(nextticklistentry); // CraftBukkit
    }

    @Override
    public void a(BlockPosition blockposition, Block block, int i) {
        this.a(blockposition, block, i, 0);
    }

    @Override
    public void a(BlockPosition blockposition, Block block, int i, int j) {
        if (blockposition instanceof BlockPosition.MutableBlockPosition || blockposition instanceof BlockPosition.PooledBlockPosition) {
            blockposition = new BlockPosition(blockposition);
            LogManager.getLogger().warn("Tried to assign a mutable BlockPos to tick data...", new Error(blockposition.getClass().toString()));
        }

        Material material = block.getBlockData().getMaterial();

        if (this.d && material != Material.AIR) {
            if (block.r()) {
                if (this.areChunksLoadedBetween(blockposition.a(-8, -8, -8), blockposition.a(8, 8, 8))) {
                    IBlockData iblockdata = this.getType(blockposition);

                    if (iblockdata.getMaterial() != Material.AIR && iblockdata.getBlock() == block) {
                        iblockdata.getBlock().b(this, blockposition, iblockdata, this.random);
                    }
                }

                return;
            }

            i = 1;
        }

        NextTickListEntry nextticklistentry = new NextTickListEntry(blockposition, block);

        if (this.isLoaded(blockposition)) {
            if (material != Material.AIR) {
                nextticklistentry.a(i + this.worldData.getTime());
                nextticklistentry.a(j);
            }

            // CraftBukkit - use nextTickList
            if (!this.nextTickList.contains(nextticklistentry)) {
                this.nextTickList.add(nextticklistentry);
            }
        }

    }

    @Override
    public void b(BlockPosition blockposition, Block block, int i, int j) {
        if (blockposition instanceof BlockPosition.MutableBlockPosition || blockposition instanceof BlockPosition.PooledBlockPosition) {
            blockposition = new BlockPosition(blockposition);
            LogManager.getLogger().warn("Tried to assign a mutable BlockPos to tick data...", new Error(blockposition.getClass().toString()));
        }

        NextTickListEntry nextticklistentry = new NextTickListEntry(blockposition, block);

        nextticklistentry.a(j);
        Material material = block.getBlockData().getMaterial();

        if (material != Material.AIR) {
            nextticklistentry.a(i + this.worldData.getTime());
        }

        // CraftBukkit - use nextTickList
        if (!this.nextTickList.contains(nextticklistentry)) {
            this.nextTickList.add(nextticklistentry);
        }

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
        this.emptyTime = 0;
    }

    @Override
    public boolean a(boolean flag) {
        if (this.worldData.getType() == WorldType.DEBUG_ALL_BLOCK_STATES) {
            return false;
        } else {
            int i = this.nextTickList.size();

            if (false) { // CraftBukkit
                throw new IllegalStateException("TickNextTick list out of synch");
            } else {
                if (i > 65536) {
                    // CraftBukkit start - If the server has too much to process over time, try to alleviate that
                    if (i > 20 * 65536) {
                        i = i / 20;
                    } else {
                        i = 65536;
                    }
                    // CraftBukkit end
                }

                this.methodProfiler.a("cleaning");

                timings.scheduledBlocksCleanup.startTiming(); // Paper
                NextTickListEntry nextticklistentry;

                for (int j = 0; j < i; ++j) {
                    nextticklistentry = this.nextTickList.first();
                    if (!flag && nextticklistentry.b > this.worldData.getTime()) {
                        break;
                    }

                    // CraftBukkit - use nextTickList
                    this.nextTickList.remove(nextticklistentry);
                    // this.nextTickListHash.remove(nextticklistentry);
                    this.U.add(nextticklistentry);
                }
                timings.scheduledBlocksCleanup.stopTiming(); // Paper


                this.methodProfiler.a("ticking");
                timings.scheduledBlocksTicking.startTiming(); // Paper
                Iterator iterator = this.U.iterator();

                while (iterator.hasNext()) {
                    nextticklistentry = (NextTickListEntry) iterator.next();
                    iterator.remove();
                    boolean flag1 = false;

                    if (this.areChunksLoadedBetween(nextticklistentry.a.a(0, 0, 0), nextticklistentry.a.a(0, 0, 0))) {
                        IBlockData iblockdata = this.getType(nextticklistentry.a);
                        co.aikar.timings.Timing timing = iblockdata.getBlock().getTiming(); // Paper
                        timing.startTiming(); // Paper

                        if (iblockdata.getMaterial() != Material.AIR && Block.a(iblockdata.getBlock(), nextticklistentry.a())) {
                            try {
                                stopPhysicsEvent = !paperConfig.firePhysicsEventForRedstone && (iblockdata.getBlock() instanceof BlockDiodeAbstract || iblockdata.getBlock() instanceof BlockRedstoneTorch); // Paper
                                iblockdata.getBlock().b(this, nextticklistentry.a, iblockdata, this.random);
                            } catch (Throwable throwable) {
                                CrashReport crashreport = CrashReport.a(throwable, "Exception while ticking a block");
                                CrashReportSystemDetails crashreportsystemdetails = crashreport.a("Block being ticked");

                                CrashReportSystemDetails.a(crashreportsystemdetails, nextticklistentry.a, iblockdata);
                                throw new ReportedException(crashreport);
                            } finally { stopPhysicsEvent = false; } // Paper
                        }
                        timing.stopTiming(); // Paper
                    } else {
                        this.a(nextticklistentry.a, nextticklistentry.a(), 0);
                    }
                }
                timings.scheduledBlocksTicking.stopTiming(); // Paper


                this.U.clear();
                return !this.nextTickList.isEmpty();
            }
        }
    }

    @Override
    @Nullable
    public List<NextTickListEntry> a(Chunk chunk, boolean flag) {
        ChunkCoordIntPair chunkcoordintpair = chunk.k();
        int i = (chunkcoordintpair.x << 4) - 2;
        int j = i + 16 + 2;
        int k = (chunkcoordintpair.z << 4) - 2;
        int l = k + 16 + 2;

        return this.a(new StructureBoundingBox(i, 0, k, j, 256, l), flag);
    }

    @Override
    @Nullable
    public List<NextTickListEntry> a(StructureBoundingBox structureboundingbox, boolean flag) {
        ArrayList arraylist = null;

        for (int i = 0; i < 2; ++i) {
            Iterator iterator;

            if (i == 0) {
                iterator = this.nextTickList.iterator();
            } else {
                iterator = this.U.iterator();
            }

            while (iterator.hasNext()) {
                NextTickListEntry nextticklistentry = (NextTickListEntry) iterator.next();
                BlockPosition blockposition = nextticklistentry.a;

                if (blockposition.getX() >= structureboundingbox.a && blockposition.getX() < structureboundingbox.d && blockposition.getZ() >= structureboundingbox.c && blockposition.getZ() < structureboundingbox.f) {
                    if (flag) {
                        if (i == 0) {
                            // this.nextTickListHash.remove(nextticklistentry); // CraftBukkit - removed
                        }

                        iterator.remove();
                    }

                    if (arraylist == null) {
                        arraylist = Lists.newArrayList();
                    }

                    arraylist.add(nextticklistentry);
                }
            }
        }

        return arraylist;
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
        return reactor.createChunkProvider();
    }

    public List<TileEntity> getTileEntities(int i, int j, int k, int l, int i1, int j1) {
        ArrayList arraylist = Lists.newArrayList();

        // CraftBukkit start - Get tile entities from chunks instead of world
        for (int chunkX = (i >> 4); chunkX <= ((l - 1) >> 4); chunkX++) {
            for (int chunkZ = (k >> 4); chunkZ <= ((j1 - 1) >> 4); chunkZ++) {
                Chunk chunk = getChunkAt(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                for (Object te : chunk.tileEntities.values()) {
                    TileEntity tileentity = (TileEntity) te;
                    if ((tileentity.position.getX() >= i) && (tileentity.position.getY() >= j) && (tileentity.position.getZ() >= k) && (tileentity.position.getX() < l) && (tileentity.position.getY() < i1) && (tileentity.position.getZ() < j1)) {
                        arraylist.add(tileentity);
                    }
                }
            }
        }
        /*
        for (int k1 = 0; k1 < this.tileEntityList.size(); ++k1) {
            TileEntity tileentity = (TileEntity) this.tileEntityList.get(k1);
            BlockPosition blockposition = tileentity.getPosition();

            if (blockposition.getX() >= i && blockposition.getY() >= j && blockposition.getZ() >= k && blockposition.getX() < l && blockposition.getY() < i1 && blockposition.getZ() < j1) {
                arraylist.add(tileentity);
            }
        }
         */
        // CraftBukkit end

        return arraylist;
    }

    @Override
    public boolean a(EntityHuman entityhuman, BlockPosition blockposition) {
        return !this.server.isBlockProtected(this, blockposition, entityhuman) && this.getWorldBorder().a(blockposition);
    }

    @Override
    public void a(WorldSettings worldsettings) {
        if (!this.worldData.v()) {
            try {
                this.b(worldsettings);
                if (this.worldData.getType() == WorldType.DEBUG_ALL_BLOCK_STATES) {
                    this.an();
                }

                super.a(worldsettings);
            } catch (Throwable throwable) {
                CrashReport crashreport = CrashReport.a(throwable, "Exception initializing level");

                try {
                    this.a(crashreport);
                } catch (Throwable throwable1) {
                    ;
                }

                throw new ReportedException(crashreport);
            }

            this.worldData.d(true);
        }

    }

    private void an() {
        this.worldData.f(false);
        this.worldData.c(true);
        this.worldData.setStorm(false);
        this.worldData.setThundering(false);
        this.worldData.i(1000000000);
        this.worldData.setDayTime(6000L);
        this.worldData.setGameType(EnumGamemode.SPECTATOR);
        this.worldData.g(false);
        this.worldData.setDifficulty(EnumDifficulty.PEACEFUL);
        this.worldData.e(true);
        this.getGameRules().set("doDaylightCycle", "false");
    }

    private void b(WorldSettings worldsettings) {
        if (!this.worldProvider.e()) {
            this.worldData.setSpawn(BlockPosition.ZERO.up(this.worldProvider.getSeaLevel()));
        } else if (this.worldData.getType() == WorldType.DEBUG_ALL_BLOCK_STATES) {
            this.worldData.setSpawn(BlockPosition.ZERO.up());
        } else {
            this.isLoading = true;
            WorldChunkManager worldchunkmanager = this.worldProvider.k();
            List list = worldchunkmanager.a();
            Random random = new Random(this.getSeed());
            BlockPosition blockposition = worldchunkmanager.a(0, 0, 256, list, random);
            int i = 8;
            int j = this.worldProvider.getSeaLevel();
            int k = 8;

            // CraftBukkit start
            if (this.getReactor().generator != null) {
                Random rand = new Random(this.getSeed());
                org.bukkit.Location spawn = this.getReactor().generator.getFixedSpawnLocation(this.getWorld(), rand);

                if (spawn != null) {
                    if (spawn.getWorld() != this.getWorld()) {
                        throw new IllegalStateException("Cannot set spawn point for " + this.worldData.getName() + " to be in another world (" + spawn.getWorld().getName() + ")");
                    } else {
                        this.worldData.setSpawn(new BlockPosition(spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ()));
                        this.isLoading = false;
                        return;
                    }
                }
            }
            // CraftBukkit end

            if (blockposition != null) {
                i = blockposition.getX();
                k = blockposition.getZ();
            } else {
                WorldServer.a.warn("Unable to find spawn biome");
            }

            int l = 0;

            while (!this.canSpawn(i, k)) { // CraftBukkit - use our own canSpawn
                i += random.nextInt(64) - random.nextInt(64);
                k += random.nextInt(64) - random.nextInt(64);
                ++l;
                if (l == 1000) {
                    break;
                }
            }

            this.worldData.setSpawn(new BlockPosition(i, j, k));
            this.isLoading = false;
            if (worldsettings.c()) {
                this.o();
            }

        }
    }

    protected void o() {
        WorldGenBonusChest worldgenbonuschest = new WorldGenBonusChest();

        for (int i = 0; i < 10; ++i) {
            int j = this.worldData.b() + this.random.nextInt(6) - this.random.nextInt(6);
            int k = this.worldData.d() + this.random.nextInt(6) - this.random.nextInt(6);
            BlockPosition blockposition = this.q(new BlockPosition(j, 0, k)).up();

            if (worldgenbonuschest.generate(this, this.random, blockposition)) {
                break;
            }
        }

    }

    @Nullable
    public BlockPosition getDimensionSpawn() {
        return reactor.getDimensionSpawn();
    }

    public void save(boolean flag, @Nullable IProgressUpdate iprogressupdate) throws ExceptionWorldConflict {
        ChunkProviderServer chunkproviderserver = this.getChunkProviderServer();

        if (chunkproviderserver.e()) {
            if (flag) org.bukkit.Bukkit.getPluginManager().callEvent(new org.bukkit.event.world.WorldSaveEvent(getWorld())); // CraftBukkit // Paper - Incremental Auto Saving - Only fire event on full save
            timings.worldSave.startTiming(); // Paper
            if (flag || server.serverAutoSave) { // Paper
                if (iprogressupdate != null) {
                    iprogressupdate.a("Saving level");
                }

                this.a();
                if (iprogressupdate != null) {
                    iprogressupdate.c("Saving chunks");
                }
            } // Paper

            timings.worldSaveChunks.startTiming(); // Paper
            chunkproviderserver.a(flag);
            timings.worldSaveChunks.stopTiming(); // Paper
            // CraftBukkit - ArrayList -> Collection
            /* //Paper start Collection arraylist = chunkproviderserver.a();
            Iterator iterator = arraylist.iterator();

            while (iterator.hasNext()) {
                Chunk chunk = (Chunk) iterator.next();

                if (chunk != null && !this.manager.a(chunk.locX, chunk.locZ)) {
                    chunkproviderserver.unload(chunk);
                }
            }*/
            // Paper end
            timings.worldSave.stopTiming(); // Paper
        }
    }

    public void flushSave() {
        ChunkProviderServer chunkproviderserver = this.getChunkProviderServer();

        if (chunkproviderserver.e()) {
            chunkproviderserver.c();
        }
    }

    protected void a() throws ExceptionWorldConflict {
        timings.worldSaveLevel.startTiming(); // Paper
        this.checkSession();
        WorldServer[] aworldserver = this.server.getWorldServers();
        int i = aworldserver.length;

        for (int j = 0; j < i; ++j) {
            WorldServer worldserver = aworldserver[j];

            if (worldserver instanceof SecondaryWorldServer) {
                ((SecondaryWorldServer) worldserver).c();
            }
        }

        // CraftBukkit start - Save secondary data for nether/end
        if (this instanceof SecondaryWorldServer) {
            ((SecondaryWorldServer) this).c();
        }
        // CraftBukkit end

        this.worldData.a(this.getWorldBorder().getSize());
        this.worldData.d(this.getWorldBorder().getCenterX());
        this.worldData.c(this.getWorldBorder().getCenterZ());
        this.worldData.e(this.getWorldBorder().getDamageBuffer());
        this.worldData.f(this.getWorldBorder().getDamageAmount());
        this.worldData.j(this.getWorldBorder().getWarningDistance());
        this.worldData.k(this.getWorldBorder().getWarningTime());
        this.worldData.b(this.getWorldBorder().j());
        this.worldData.e(this.getWorldBorder().i());
        this.dataManager.saveWorldData(this.worldData, (NBTTagCompound) null);
        this.worldMaps.a();
        timings.worldSaveLevel.stopTiming(); // Paper
    }

    // CraftBukkit start
    @Override
    public boolean addEntity(Entity entity, SpawnReason spawnReason) { // Changed signature, added SpawnReason
        // World.addEntity(Entity) will call this, and we still want to perform
        // existing entity checking when it's called with a SpawnReason
        return this.j(entity) ? super.addEntity(entity, spawnReason) : false;
    }
    // CraftBukkit end

    @Override
    public void a(Collection<Entity> collection) {
        ArrayList arraylist = Lists.newArrayList(collection);
        Iterator iterator = arraylist.iterator();

        while (iterator.hasNext()) {
            Entity entity = (Entity) iterator.next();

            if (this.j(entity)) {
                this.entityList.add(entity);
                this.b(entity);
            }
        }

    }

    private boolean j(Entity entity) {
        if (entity.dead) {
            // WorldServer.a.warn("Tried to add entity {} but it was marked as removed already", new Object[] { EntityTypes.a(entity)}); // CraftBukkit
            return false;
        } else {
            UUID uuid = entity.getUniqueID();

            if (this.entitiesByUUID.containsKey(uuid)) {
                Entity entity1 = this.entitiesByUUID.get(uuid);

                if (this.f.contains(entity1)) {
                    this.f.remove(entity1);
                } else {
                    if (!(entity instanceof EntityHuman)) {
                        // WorldServer.a.warn("Keeping entity {} that already exists with UUID {}", new Object[] { EntityTypes.a(entity1), uuid.toString()}); // CraftBukkit
                        return false;
                    }

                    WorldServer.a.warn("Force-added player with duplicate UUID {}", new Object[] { uuid.toString()});
                }

                this.removeEntity(entity1);
            }

            return true;
        }
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
        BlockActionData blockactiondata = new BlockActionData(blockposition, block, i, j);
        Iterator iterator = this.S[this.T].iterator();

        BlockActionData blockactiondata1;

        do {
            if (!iterator.hasNext()) {
                this.S[this.T].add(blockactiondata);
                return;
            }

            blockactiondata1 = (BlockActionData) iterator.next();
        } while (!blockactiondata1.equals(blockactiondata));

    }

    private void ao() {
        while (!this.S[this.T].isEmpty()) {
            int i = this.T;

            this.T ^= 1;
            Iterator iterator = this.S[i].iterator();

            while (iterator.hasNext()) {
                BlockActionData blockactiondata = (BlockActionData) iterator.next();

                if (this.a(blockactiondata)) {
                    // CraftBukkit - this.worldProvider.dimension -> this.dimension
                    this.server.getPlayerList().sendPacketNearby((EntityHuman) null, blockactiondata.a().getX(), blockactiondata.a().getY(), blockactiondata.a().getZ(), 64.0D, dimension, new PacketPlayOutBlockAction(blockactiondata.a(), blockactiondata.d(), blockactiondata.b(), blockactiondata.c()));
                }
            }

            this.S[i].clear();
        }

    }

    private boolean a(BlockActionData blockactiondata) {
        IBlockData iblockdata = this.getType(blockactiondata.a());

        return iblockdata.getBlock() == blockactiondata.d() ? iblockdata.a(this, blockactiondata.a(), blockactiondata.b(), blockactiondata.c()) : false;
    }

    public void saveLevel() {
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
        return this.dataManager.h();
    }

    public void a(EnumParticle enumparticle, double d0, double d1, double d2, int i, double d3, double d4, double d5, double d6, int... aint) {
        this.a(enumparticle, false, d0, d1, d2, i, d3, d4, d5, d6, aint);
    }

    public void a(EnumParticle enumparticle, boolean flag, double d0, double d1, double d2, int i, double d3, double d4, double d5, double d6, int... aint) {
        // CraftBukkit - visibility api support
        sendParticles(null, enumparticle, flag, d0, d1, d2, i, d3, d4, d5, d6, aint);
    }

    public void sendParticles(EntityPlayer sender, EnumParticle enumparticle, boolean flag, double d0, double d1, double d2, int i, double d3, double d4, double d5, double d6, int... aint) {
        // CraftBukkit end
        PacketPlayOutWorldParticles packetplayoutworldparticles = new PacketPlayOutWorldParticles(enumparticle, flag, (float) d0, (float) d1, (float) d2, (float) d3, (float) d4, (float) d5, (float) d6, i, aint);

        for (EntityHuman player : getReactor().players) {
            EntityPlayer entityplayer = (EntityPlayer) player;
            if (sender != null && !entityplayer.getBukkitEntity().canSee(sender.getBukkitEntity())) continue; // CraftBukkit
            BlockPosition blockposition = entityplayer.getChunkCoordinates();
            //double d7 = blockposition.distanceSquared(d0, d1, d2);

            this.a(entityplayer, flag, d0, d1, d2, packetplayoutworldparticles);
        }

    }

    public void a(EntityPlayer entityplayer, EnumParticle enumparticle, boolean flag, double d0, double d1, double d2, int i, double d3, double d4, double d5, double d6, int... aint) {
        PacketPlayOutWorldParticles packetplayoutworldparticles = new PacketPlayOutWorldParticles(enumparticle, flag, (float) d0, (float) d1, (float) d2, (float) d3, (float) d4, (float) d5, (float) d6, i, aint);

        this.a(entityplayer, flag, d0, d1, d2, packetplayoutworldparticles);
    }

    private void a(EntityPlayer entityplayer, boolean flag, double d0, double d1, double d2, Packet<?> packet) {
        BlockPosition blockposition = entityplayer.getChunkCoordinates();
        double d3 = blockposition.distanceSquared(d0, d1, d2);

        if (d3 <= 1024.0D || flag && d3 <= 262144.0D) {
            entityplayer.playerConnection.sendPacket(packet);
        }

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
        return this.getChunkProviderServer().a(this, s, blockposition, flag);
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
