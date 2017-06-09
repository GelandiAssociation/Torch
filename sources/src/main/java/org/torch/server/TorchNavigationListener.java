package org.torch.server;

import lombok.Getter;
import net.minecraft.server.*;

import org.torch.api.TorchReactor;

import com.koloboke.collect.map.hash.HashObjObjMaps;

import java.util.Map;
import javax.annotation.Nullable;

@Getter
public final class TorchNavigationListener implements org.torch.api.IWorldAccess, TorchReactor, IWorldAccess {
    /** The legacy */
    private final NavigationListener servant;
    
    private final Map<EntityInsentient, NavigationAbstract> navigators = HashObjObjMaps.newMutableMap();
    
    public TorchNavigationListener(@Nullable NavigationListener legacy) {
        servant = legacy;
    }
    
    @Override
    public void notifyBlockUpdate(World world, BlockPosition position, IBlockData oldData, IBlockData newData) {
        if (!isBlockChanged(world, position, oldData, newData)) return;
        
        for (NavigationAbstract navigation : navigators.values()) {
            if (navigation.i()) continue; // PAIL: canUpdatePathOnTimeout
            
            PathEntity currentPath = navigation.getPath();
            if (currentPath == null) continue;
            
            if (!currentPath.b() && currentPath.d() != 0) {
                PathPoint finalPathPoint = navigation.getPath().c();
                
                double distance = position.distanceSquared(
                        (finalPathPoint.a + navigation.getEntity().locX) / 2.0D,
                        (finalPathPoint.b + navigation.getEntity().locY) / 2.0D,
                        (finalPathPoint.c + navigation.getEntity().locZ) / 2.0D);
                
                int goal = (currentPath.d() - currentPath.e()) * (currentPath.d() - currentPath.e());

                if (distance < goal) navigation.updatePath();
            }
        }
        
    }
    
    public static boolean isBlockChanged(World world, BlockPosition position, IBlockData oldData, IBlockData newData) {
        // PAIL: c -> getCollisionBoundingBox
        AxisAlignedBB oldBoundingBox = oldData.c(world, position);
        AxisAlignedBB newBoundingBox = newData.c(world, position);
        
        return oldBoundingBox != newBoundingBox && (oldBoundingBox == null || !oldBoundingBox.equals(newBoundingBox));
    }

    @Override
    public void onEntityAdded(Entity entity) {
        if (entity instanceof EntityInsentient) {
            EntityInsentient insentient = (EntityInsentient) entity;
            NavigationAbstract navigation = insentient.getNavigation();
            
            if (navigation != null) navigators.put(insentient, navigation);
        }
    }
    
    @Override
    public void onEntityRemoved(Entity entity) {
        navigators.remove(entity);
    }

    @Override
    public void playSoundNearbyExpect(EntityHuman expect, SoundEffect effect, SoundCategory category, double x, double y, double z, float volume, float pitch) {
        ;
    }

    @Override
    public void playWorldEventNearbyExpect(EntityHuman expect, int type, BlockPosition position, int data) {
        ;
    }

    @Override
    public void playWorldEvent(int type, BlockPosition position, int data) {
        ;
    }

    @Override
    public void sendBlockBreakProgress(int breakerEntityId, BlockPosition position, int progress) {
        ;
    }
    
    ///////// Implement from net.minecraft.server.IWorldAccess
    @Override
    public void a(BlockPosition arg0) {}

    @Override
    public void a(Entity arg0) {
        this.onEntityAdded(arg0);
    }

    @Override
    public void a(SoundEffect arg0, BlockPosition arg1) {}

    @Override
    public void a(int arg0, BlockPosition arg1, int arg2) {}

    @Override
    public void a(EntityHuman arg0, int arg1, BlockPosition arg2, int arg3) {}

    @Override
    public void a(World arg0, BlockPosition arg1, IBlockData arg2, IBlockData arg3, int arg4) {
        this.notifyBlockUpdate(arg0, arg1, arg2, arg3);
    }

    @Override
    public void a(int arg0, int arg1, int arg2, int arg3, int arg4, int arg5) {}

    @Override
    public void a(EntityHuman arg0, SoundEffect arg1, SoundCategory arg2, double arg3, double arg4, double arg5,
            float arg6, float arg7) {}

    @Override
    public void a(int arg0, boolean arg1, double arg2, double arg3, double arg4, double arg5, double arg6, double arg7,
            int... arg8) {}

    @Override
    public void a(int arg0, boolean arg1, boolean arg2, double arg3, double arg4, double arg5, double arg6, double arg7,
            double arg8, int... arg9) {}

    @Override
    public void b(Entity arg0) {
        this.onEntityRemoved(arg0);
    }

    @Override
    public void b(int arg0, BlockPosition arg1, int arg2) {}
}
