package net.minecraft.server;

import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import com.koloboke.collect.map.hash.HashObjObjMaps;
import com.koloboke.collect.set.hash.HashObjSets;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import javax.annotation.Nullable;

// CraftBukkit start
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.torch.utils.random.LightRandom;
import org.bukkit.Location;
import org.bukkit.event.block.BlockExplodeEvent;
// CraftBukkit end

public class Explosion {
    
    private final boolean a;
    private final boolean b;
    private final Random c = new LightRandom();
    private final World world;
    private final double posX;
    private final double posY;
    private final double posZ;
    public final Entity source;
    private final float size;
    /** blocksToDestroy */
    private final Set<BlockPosition> blocks = HashObjSets.newMutableSet(); // Torch - ArrayList -> HashSet
    /** playerKnockbackMap */
    private final Map<EntityHuman, Vec3D> k = HashObjObjMaps.newMutableMap();
    public boolean wasCanceled = false; // CraftBukkit - add field

    public Explosion(World world, Entity entity, double d0, double d1, double d2, float f, boolean flag, boolean flag1) {
        this.world = world;
        this.source = entity;
        this.size = (float) Math.max(f, 0.0); // CraftBukkit - clamp bad values
        this.posX = d0;
        this.posY = d1;
        this.posZ = d2;
        this.a = flag;
        this.b = flag1;
    }
    
    /**
     * <b>PAIL: destoryBlocks</b>
     * <p>
     * Does the first part of the explosion (destroy blocks)
     */
    public void a() {
        if (this.size < 0.1F) return; // CraftBukkit
        
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    if (x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15) {
                        double stepX = x / 15.0F * 2.0F - 1.0F;
                        double stepY = y / 15.0F * 2.0F - 1.0F;
                        double stepZ = z / 15.0F * 2.0F - 1.0F;
                        
                        double step = Math.sqrt(stepX * stepX + stepY * stepY + stepZ * stepZ);
                        stepX /= step;
                        stepY /= step;
                        stepZ /= step;
                        
                        double blockX = this.posX;
                        double blockY = this.posY;
                        double blockZ = this.posZ;
                        
                        float strength = this.size * (0.7F + this.world.random.nextFloat() * 0.6F);
                        
                        for (; strength > 0.0F; strength -= 0.22500001F) {
                            BlockPosition pos = new BlockPosition(blockX, blockY, blockZ);
                            IBlockData iblockdata = this.world.getType(pos);
                            
                            if (iblockdata.getMaterial() != Material.AIR) {
                                float resistance = this.source != null ? this.source.a(this, this.world, pos, iblockdata) : iblockdata.getBlock().a((Entity) null);
                                
                                strength -= (resistance + 0.3F) * 0.3F;
                            }
                            
                            // CraftBukkit - don't wrap explosions
                            if (strength > 0.0F && (this.source == null || this.source.a(this, this.world, pos, iblockdata, strength)) && pos.getY() < 256 && pos.getY() >= 0) {
                                blocks.add(pos);
                            }
                            
                            blockX += stepX * 0.30000001192092896D;
                            blockY += stepY * 0.30000001192092896D;
                            blockZ += stepZ * 0.30000001192092896D;
                        }
                    }
                }
            }
        }
        
        float expandSize = this.size * 2.0F;
        
        int maxX = MathHelper.floor(this.posX - expandSize - 1.0D);
        int minX = MathHelper.floor(this.posX + expandSize + 1.0D);
        int maxY = MathHelper.floor(this.posY - expandSize - 1.0D);
        int minY = MathHelper.floor(this.posY + expandSize + 1.0D);
        int minZ = MathHelper.floor(this.posZ - expandSize - 1.0D);
        int maxZ = MathHelper.floor(this.posZ + expandSize + 1.0D);
        
        // Paper start - Fix lag from explosions processing dead entities
        List<Entity> living = this.world.getEntities(this.source, new AxisAlignedBB(maxX, maxY, minZ, minX, minY, maxZ), new Predicate<Entity>() {
            @Override
            public boolean apply(Entity entity) {
                return IEntitySelector.d.apply(entity) && !entity.dead;
            }
        });
        // Paper end
        
        Vec3D vec3D = new Vec3D(this.posX, this.posY, this.posZ);
        
        for (int index = 0, size = living.size(); index < size; index++) {
            Entity entity = living.get(index);

            if (entity.bt()) continue; // PAIL: isImmuneToExplosions()
            
            double dist = entity.e(this.posX, this.posY, this.posZ) / expandSize; // PAIL: e -> getDistance
            
            if (dist <= 1.0D) {
                double distX = entity.locX - this.posX;
                double distY = entity.locY + entity.getHeadHeight() - this.posY;
                double distZ = entity.locZ - this.posZ;
                double norm = MathHelper.sqrt(distX * distX + distY * distY + distZ * distZ);
                
                if (norm != 0.0D) {
                    distX /= norm;
                    distY /= norm;
                    distZ /= norm;
                    double blockDensity = this.getBlockDensity(vec3D, entity.getBoundingBox()); // Paper - Optimize explosions
                    double strength = (1.0D - dist) * blockDensity;
                    
                    // CraftBukkit start
                    CraftEventFactory.entityDamage = source;
                    entity.forceExplosionKnockback = false;
                    boolean wasDamaged = entity.damageEntity(DamageSource.explosion(this), ((int) ((strength * strength + strength) / 2.0D * 7.0D * expandSize + 1.0D)));
                    CraftEventFactory.entityDamage = null;
                    
                    if (!wasDamaged && !(entity instanceof EntityTNTPrimed || entity instanceof EntityFallingBlock) && !entity.forceExplosionKnockback) {
                        continue;
                    }
                    // CraftBukkit end
                    
                    double damageReduction = 0;
                    if (entity instanceof EntityLiving) {
                        damageReduction = entity instanceof EntityHuman && world.paperConfig.disableExplosionKnockback ? 0 : EnchantmentProtection.a((EntityLiving) entity, strength); // Paper - Disable explosion knockback
                    }
                    
                    // Paper - This impulse method sets the dirty flag, so clients will get an immediate velocity update
                    entity.addVelocity(distX * damageReduction, distY * damageReduction, distZ * damageReduction);
                    
                    if (entity instanceof EntityHuman) {
                        EntityHuman entityhuman = (EntityHuman) entity;

                        if (!entityhuman.isSpectator() && (!entityhuman.z() && !world.paperConfig.disableExplosionKnockback || !entityhuman.abilities.isFlying)) { // Paper - Disable explosion knockback
                            this.k.put(entityhuman, new Vec3D(distX * strength, distY * strength, distZ * strength));
                        }
                    }
                }
                
            }
        }
        
    }

    /**
     * <b>PAIL: applyPhysics</b>
     * <p>
     * Does the second part of the explosion (sound, particles, drop spawn)
     */
    public void a(boolean spawnParticles) {
        // PAIL: world.a -> world.playSound
        this.world.a(null, this.posX, this.posY, this.posZ, SoundEffects.bP, SoundCategory.BLOCKS, 4.0F, (1.0F + (this.world.random.nextFloat() - this.world.random.nextFloat()) * 0.2F) * 0.7F);
        
        if (this.size >= 2.0F && this.b) { // PAIL: b -> isSmoking
            this.world.addParticle(EnumParticle.EXPLOSION_HUGE, this.posX, this.posY, this.posZ, 1.0D, 0.0D, 0.0D, new int[0]);
        } else {
            this.world.addParticle(EnumParticle.EXPLOSION_LARGE, this.posX, this.posY, this.posZ, 1.0D, 0.0D, 0.0D, new int[0]);
        }
        
        if (this.b) { // PAIL: isSmoking
            // CraftBukkit start
            org.bukkit.World bukkitWorld = this.world.getWorld();
            org.bukkit.entity.Entity explode = this.source == null ? null : this.source.getBukkitEntity();
            Location location = new Location(bukkitWorld, this.posX, this.posY, this.posZ);
            
            List<org.bukkit.block.Block> blockList = Lists.newArrayList();
            for (BlockPosition position : this.blocks) {
                org.bukkit.block.Block bukkitBlock = bukkitWorld.getBlockAt(position.getX(), position.getY(), position.getZ());
                if (bukkitBlock.getType() != org.bukkit.Material.AIR) {
                    blockList.add(bukkitBlock);
                }
            }
            
            boolean cancelled;
            List<org.bukkit.block.Block> bukkitBlocks;
            float yield;
            
            if (explode != null) {
                EntityExplodeEvent event = new EntityExplodeEvent(explode, location, blockList, 1.0F / this.size);
                this.world.getServer().getPluginManager().callEvent(event);
                cancelled = event.isCancelled();
                bukkitBlocks = event.blockList();
                yield = event.getYield();
            } else {
                BlockExplodeEvent event = new BlockExplodeEvent(location.getBlock(), blockList, 1.0F / this.size);
                this.world.getServer().getPluginManager().callEvent(event);
                cancelled = event.isCancelled();
                bukkitBlocks = event.blockList();
                yield = event.getYield();
            }
            
            if (cancelled) {
                this.wasCanceled = true;
                return;
            }
            
            this.blocks.clear();
            
            for (org.bukkit.block.Block bukkitBlock : bukkitBlocks) {
                BlockPosition coords = new BlockPosition(bukkitBlock.getX(), bukkitBlock.getY(), bukkitBlock.getZ());
                blocks.add(coords);
                // CraftBukkit end
                
                // Torch start - moved up from underside, in case duplicate loop
                Block block = this.world.getType(coords).getBlock();
                
                if (spawnParticles) {
                    double x = coords.getX() + this.world.random.nextFloat();
                    double y = coords.getY() + this.world.random.nextFloat();
                    double z = coords.getZ() + this.world.random.nextFloat();
                    double distX = x - this.posX;
                    double distY = y - this.posY;
                    double distZ = z - this.posZ;
                    double dist = MathHelper.sqrt(distX * distX + distY * distY + distZ * distZ);
                    
                    distX /= dist;
                    distY /= dist;
                    distZ /= dist;
                    
                    double distScale = 0.5D / (dist / this.size + 0.1D);
                    distScale *= this.world.random.nextFloat() * this.world.random.nextFloat() + 0.3F;
                    distX *= distScale;
                    distY *= distScale;
                    distZ *= distScale;
                    
                    this.world.addParticle(EnumParticle.EXPLOSION_NORMAL, (x + this.posX) / 2.0D, (y + this.posY) / 2.0D, (z + this.posZ) / 2.0D, distX, distY, distZ, new int[0]);
                    this.world.addParticle(EnumParticle.SMOKE_NORMAL, x, y, z, distX, distY, distZ, new int[0]);
                }
                
                if (block.material != Material.AIR) {
                    if (block.a(this)) { // PAIL: canDropFromExplosion
                        block.dropNaturally(this.world, coords, this.world.getType(coords), yield, 0);
                    }
                    
                    this.world.setTypeAndData(coords, Blocks.AIR.getBlockData(), 3);
                    block.wasExploded(this.world, coords, this);
                }
                
                if (this.a) { // Torch - Copied from underside, PAIL: isFlaming
                    if (this.world.getType(coords).getMaterial() == Material.AIR && this.world.getType(coords.down()).b() && this.c.nextInt(3) == 0) {
                        
                        if (!org.bukkit.craftbukkit.event.CraftEventFactory.callBlockIgniteEvent(this.world, coords.getX(), coords.getY(), coords.getZ(), this).isCancelled()) {
                            this.world.setTypeUpdate(coords, Blocks.FIRE.getBlockData());
                        }
                    }
                }
                // Torch end
            }
            
        } else if (this.a) { // PAIL: isFlaming
            // Flaming only, the concurrent way has been moved up
            for (BlockPosition position : this.blocks) {
                if (this.world.getType(position).getMaterial() == Material.AIR && this.world.getType(position.down()).b() && this.c.nextInt(3) == 0) {
                    
                    // CraftBukkit start - Ignition by explosion
                    if (!org.bukkit.craftbukkit.event.CraftEventFactory.callBlockIgniteEvent(this.world, position.getX(), position.getY(), position.getZ(), this).isCancelled()) {
                        this.world.setTypeUpdate(position, Blocks.FIRE.getBlockData());
                    }
                    // CraftBukkit end
                }
            }
        }

    }

    public Map<EntityHuman, Vec3D> b() {
        return this.k;
    }

    @Nullable
    public EntityLiving getSource() {
        // CraftBukkit start - obtain Fireball shooter for explosion tracking
        return this.source == null ? null : (this.source instanceof EntityTNTPrimed ? ((EntityTNTPrimed) this.source).getSource() : (this.source instanceof EntityLiving ? (EntityLiving) this.source : (this.source instanceof EntityFireball ? ((EntityFireball) this.source).shooter : null)));
        // CraftBukkit end
    }

    public void clearBlocks() {
        this.blocks.clear();
    }

    public Set<BlockPosition> getBlocks() { // Torch
        return this.blocks;
    }

    // Paper start - Optimize explosions
    private float getBlockDensity(Vec3D vec3d, AxisAlignedBB aabb) {
        if (!this.world.paperConfig.optimizeExplosions) {
            return this.world.a(vec3d, aabb);
        }
        CacheKey key = new CacheKey(this, aabb);
        Float blockDensity = this.world.getReactor().explosionDensityCache.get(key);
        if (blockDensity == null) {
            blockDensity = this.world.a(vec3d, aabb);
            this.world.getReactor().explosionDensityCache.put(key, blockDensity);
        }

        return blockDensity;
    }

    public static final class CacheKey {
        private final World world;
        private final double posX, posY, posZ;
        private final double minX, minY, minZ;
        private final double maxX, maxY, maxZ;

        public CacheKey(Explosion explosion, AxisAlignedBB aabb) {
            this.world = explosion.world;
            this.posX = explosion.posX;
            this.posY = explosion.posY;
            this.posZ = explosion.posZ;
            this.minX = aabb.a;
            this.minY = aabb.b;
            this.minZ = aabb.c;
            this.maxX = aabb.d;
            this.maxY = aabb.e;
            this.maxZ = aabb.f;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            CacheKey cacheKey = (CacheKey) o;

            if (Double.compare(cacheKey.posX, posX) != 0) return false;
            if (Double.compare(cacheKey.posY, posY) != 0) return false;
            if (Double.compare(cacheKey.posZ, posZ) != 0) return false;
            if (Double.compare(cacheKey.minX, minX) != 0) return false;
            if (Double.compare(cacheKey.minY, minY) != 0) return false;
            if (Double.compare(cacheKey.minZ, minZ) != 0) return false;
            if (Double.compare(cacheKey.maxX, maxX) != 0) return false;
            if (Double.compare(cacheKey.maxY, maxY) != 0) return false;
            if (Double.compare(cacheKey.maxZ, maxZ) != 0) return false;
            return world.equals(cacheKey.world);
        }

        @Override
        public int hashCode() {
            int result;
            long temp;
            result = world.hashCode();
            temp = Double.doubleToLongBits(posX);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(posY);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(posZ);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(minX);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(minY);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(minZ);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(maxX);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(maxY);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(maxZ);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            return result;
        }
    }
    // Paper end
}
