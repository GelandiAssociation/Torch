package net.minecraft.server;

@Deprecated
public class DemoWorldServer extends WorldServer {
    /** DEMO_WORLD_SEED */
    private static final long I = "North Carolina".hashCode();
    /** DEMO_WORLD_SETTINGS */
    public static final WorldSettings a = (new WorldSettings(DemoWorldServer.I, EnumGamemode.SURVIVAL, true, false, WorldType.NORMAL)).a();

    // Add env and gen to constructor
    public DemoWorldServer(MinecraftServer minecraftserver, IDataManager idatamanager, WorldData worlddata, int i, MethodProfiler methodprofiler, org.bukkit.World.Environment env, org.bukkit.generator.ChunkGenerator gen) {
        super(minecraftserver, idatamanager, worlddata, i, methodprofiler, env, gen);
        this.worldData.a(DemoWorldServer.a); // Init world data
    }
}
