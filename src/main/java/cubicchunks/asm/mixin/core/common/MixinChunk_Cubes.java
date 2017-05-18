/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2015 contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package cubicchunks.asm.mixin.core.common;

import static cubicchunks.asm.JvmNames.CHUNK_CONSTRUCT_1;
import static cubicchunks.asm.JvmNames.CHUNK_ENTITY_LISTS;
import static cubicchunks.asm.JvmNames.CHUNK_IS_CHUNK_LOADED;
import static cubicchunks.asm.JvmNames.CHUNK_STORAGE_ARRAYS;
import static cubicchunks.asm.JvmNames.MATH_HELPER_CLAMP_I;
import static cubicchunks.util.Coords.blockToCube;
import static cubicchunks.util.Coords.blockToLocal;

import cubicchunks.CubicChunks;
import cubicchunks.util.Coords;
import cubicchunks.world.ClientHeightMap;
import cubicchunks.world.ICubicWorld;
import cubicchunks.world.IHeightMap;
import cubicchunks.world.ServerHeightMap;
import cubicchunks.world.column.ColumnTileEntityMap;
import cubicchunks.world.column.CubeMap;
import cubicchunks.world.column.IColumn;
import cubicchunks.world.cube.Cube;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.network.PacketBuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ClassInheritanceMultiMap;
import net.minecraft.util.ReportedException;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.ChunkProviderDebug;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.Arrays;
import java.util.Map;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * Modifies vanilla code in Chunk to use Cubes
 */
// TODO: redirect isChunkLoaded where needed
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
@Mixin(Chunk.class)
public abstract class MixinChunk_Cubes implements IColumn {

    @Shadow @Final private ExtendedBlockStorage[] storageArrays;
    @Shadow @Final public static ExtendedBlockStorage NULL_BLOCK_STORAGE;

    @Shadow private boolean hasEntities;
    @Shadow @Final public int x;
    @Shadow @Final public int z;
    @Shadow @Final private ClassInheritanceMultiMap<Entity>[] entityLists;

    @Shadow @Final @Mutable private Map<BlockPos, TileEntity> chunkTileEntityMap;

    @Shadow @Final private int[] heightMap;
    @Shadow @Final private World world;
    @Shadow protected boolean isChunkLoaded;
    // WARNING: WHEN YOU RENAME ANY OF THESE 3 FIELDS RENAME CORRESPONDING FIELDS IN MixinChunk_Column
    private CubeMap cubeMap;
    private IHeightMap opacityIndex;
    private Cube primedCube; // todo: make it always nonnull using BlankCube

    private boolean isColumn = false;

    // TODO: make it go through cube raw access methods
    // TODO: make cube an interface, use the implementation only here
    private ExtendedBlockStorage getEBS_CubicChunks(int index) {
        if (!isColumn) {
            return storageArrays[index];
        }
        if (primedCube != null && primedCube.getY() == index) {
            return primedCube.getStorage();
        }
        return getCubicWorld().getCubeCache().getCube(getX(), index, getZ()).getStorage();
    }

    private ClassInheritanceMultiMap<Entity> getEntityList_CubicChunks(int index) {
        if (!isColumn) {
            return entityLists[index];
        }
        if (primedCube != null && primedCube.getY() == index) {
            return primedCube.getEntityContainer().getEntitySet();
        }
        return getCubicWorld().getCubeCache().getCube(getX(), index, getZ()).getEntityContainer().getEntitySet();
    }

    private void setEBS_CubicChunks(int index, ExtendedBlockStorage ebs) {
        if (!isColumn) {
            storageArrays[index] = ebs;
            return;
        }
        if (primedCube != null && primedCube.getY() == index) {
            primedCube.setStorage(ebs);
            return;
        }
        Cube loaded = getCubicWorld().getCubeCache().getLoadedCube(getX(), index, getZ());
        if (loaded.getStorage() == null) {
            loaded.setStorage(ebs);
        } else {
            throw new IllegalStateException(String.format(
                    "Attempted to set a Cube ExtendedBlockStorage that already exists. "
                            + "This is not supported. "
                            + "CubePos(%d, %d, %d), loadedCube(%s), loadedCubeStorage(%s)",
                    getX(), index, getZ(),
                    loaded, loaded == null ? null : loaded.getStorage()));
        }
    }

    // modify vanilla:

    @Inject(method = CHUNK_CONSTRUCT_1, at = @At(value = "RETURN"))
    private void cubicChunkColumn_construct(World worldIn, int x, int z, CallbackInfo cbi) {
        ICubicWorld world = (ICubicWorld) worldIn;
        if (!world.isCubicWorld()) {
            return;
        }
        this.isColumn = true;
        // this.lightManager = world.getLightingManager();

        this.cubeMap = new CubeMap();
        //clientside we don't really need that much data. we actually only need top and bottom block Y positions
        if (world.isRemote()) {
            this.opacityIndex = new ClientHeightMap(this, heightMap);
        } else {
            this.opacityIndex = new ServerHeightMap(heightMap);
        }

        // instead of redirecting access to this map, just make the map do the work
        this.chunkTileEntityMap = new ColumnTileEntityMap(this);

        // this.chunkSections = null;
        // this.skylightUpdateMap = null;

        Arrays.fill(getBiomeArray(), (byte) -1);
    }

    // private ExtendedBlockStorage getLastExtendedBlockStorage() - shouldn't be used by anyone

    // this method can't be saved by just redirecting EBS access
    @Inject(method = "getTopFilledSegment", at = @At(value = "HEAD"), cancellable = true)
    public void getTopFilledSegment_CubicChunks(CallbackInfoReturnable<Integer> cbi) {
        if (!isColumn) {
            return;
        }
        int blockY = Coords.NO_HEIGHT;
        for (int localX = 0; localX < Cube.SIZE; localX++) {
            for (int localZ = 0; localZ < Cube.SIZE; localZ++) {
                int y = this.opacityIndex.getTopBlockY(localX, localZ);
                if (y > blockY) {
                    blockY = y;
                }
            }
        }
        if (blockY < getCubicWorld().getMinHeight()) {
            // PANIC!
            // this column doesn't have any blocks in it that aren't air!
            // but we can't return null here because vanilla code expects there to be a surface down there somewhere
            // we don't actually know where the surface is yet, because maybe it hasn't been generated
            // but we do know that the surface has to be at least at sea level,
            // so let's go with that for now and hope for the best

            int ret = Coords.cubeToMinBlock(Coords.blockToCube(this.getCubicWorld().getProvider().getAverageGroundLevel()));
            cbi.setReturnValue(ret);
            cbi.cancel();
            return;
        }
        int ret = Coords.cubeToMinBlock(Coords.blockToCube(blockY)); // return the lowest block in the Cube (kinda weird I know)
        cbi.setReturnValue(ret);
        cbi.cancel();
    }

    @Overwrite
    public ExtendedBlockStorage[] getBlockStorageArray() {
        if (isColumn) {
            // TODO: make the first 16 entries match vanilla
            return cubeMap.getStoragesToTick();
        }
        return storageArrays;
    }

    // ==============================================
    //               generateHeightMap
    // ==============================================

    // TODO: Move to client-only mixin as this method is side-only
    @Inject(method = "generateHeightMap", at = @At(value = "HEAD"), cancellable = true)
    protected void generateHeightMap_CubicChunks_Cancel(CallbackInfo cbi) {
        if (isColumn) {
            cbi.cancel();
        }
    }

    /*
    Light update code called from this:

    if (addedNewCube) {
      generateSkylightMap();
    } else {
      if (placingOpaque) {
        if (placingNewTopBlock) {
          relightBlock(x, y + 1, z);
        } else if (removingTopBlock) {
          relightBlock(x, y, z);
        }
      }
      // equivalent to opacityDecreased || (opacityChanged && receivesLight)
      // which means: propagateSkylight if it lets more light through, or (it receives any light and opacity changed)
      if (opacityChanged && (opacityDecreased || blockReceivesLight)) {
        propagateSkylightOcclusion(x, z);
      }
    }
    */
    // ==============================================
    //             generateSkylightMap
    // ==============================================

    @Inject(method = "generateSkylightMap", at = @At(value = "HEAD"), cancellable = true)
    public void generateSkylightMap_CubicChunks_Replace(CallbackInfo cbi) {
        if (isColumn) {
            // TODO: update skylight in cubes marked for update
            cbi.cancel();
        }
    }

    // ==============================================
    //           propagateSkylightOcclusion
    // ==============================================

    @Inject(method = "propagateSkylightOcclusion", at = @At(value = "HEAD"), cancellable = true)
    private void propagateSkylightOcclusion_CubicChunks_Replace(int x, int z, CallbackInfo cbi) {
        if (isColumn) {
            cbi.cancel();
        }
    }

    // ==============================================
    //                 recheckGaps
    // ==============================================

    @Inject(method = "recheckGaps", at = @At(value = "HEAD"), cancellable = true)
    private void recheckGaps_CubicChunks_Replace(boolean p_150803_1_, CallbackInfo cbi) {
        if (isColumn) {
            cbi.cancel();
        }
    }

    // private void checkSkylightNeighborHeight(int x, int z, int maxValue) - shouldn't be used by anyone

    // private void updateSkylightNeighborHeight(int x, int z, int startY, int endY) - shouldn't be used by anyone

    // ==============================================
    //                 relightBlock
    // ==============================================

    @Inject(method = "setBlockState", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/chunk/Chunk;relightBlock(III)V"),
            locals = LocalCapture.CAPTURE_FAILHARD)
    private void setBlockState_CubicChunks_relightBlockReplace(BlockPos pos, IBlockState state, CallbackInfoReturnable<IBlockState> cir,
            int localX, int y, int localZ, int packedXZ, int oldHeightValue, IBlockState oldState, Block newBlock, Block oldBlock,
            int oldOpacity, ExtendedBlockStorage ebs, boolean createdNewEbsAboveTop, int newOpacity) {

        if (isColumn) {
            getCubicWorld().getLightingManager().doOnBlockSetLightUpdates(this, localX, oldHeightValue, y, localZ);
        }
    }

    // make relightBlock no-op for cubic chunks, handles by injection above
    @Inject(method = "relightBlock", at = @At(value = "HEAD"), cancellable = true)
    private void relightBlock_CubicChunks_Replace(int x, int y, int z, CallbackInfo cbi) {
        if (isColumn) {
            cbi.cancel();
        }
    }

    // ==============================================
    //            getBlockLightOpacity
    // ==============================================

    @Redirect(method = "getBlockLightOpacity(III)I", at = @At(value = "FIELD", target = CHUNK_IS_CHUNK_LOADED))
    private boolean getBlockLightOpacity_isChunkLoadedCubeRedirect(Chunk chunk, int x, int y, int z) {
        if (!isColumn) {
            return isChunkLoaded;
        }
        Cube cube = this.getLoadedCube(blockToCube(y));
        return cube != null && cube.isCubeLoaded();
    }

    // ==============================================
    //                 getBlockState
    // ==============================================

    // TODO: Use @ModifyConstant with expandConditions when it's implemented
    @Overwrite
    public IBlockState getBlockState(final int x, final int y, final int z) {
        if (this.getCubicWorld().getWorldType() == WorldType.DEBUG_WORLD) {
            IBlockState iblockstate = null;

            if (y == 60) {
                iblockstate = Blocks.BARRIER.getDefaultState();
            }

            if (y == 70) {
                iblockstate = ChunkProviderDebug.getBlockStateFor(x, z);
            }

            return iblockstate == null ? Blocks.AIR.getDefaultState() : iblockstate;
        } else {
            try {
                //if (y >= 0 && y >> 4 < this.storageArrays.length)
                {
                    ExtendedBlockStorage extendedblockstorage = getEBS_CubicChunks(y >> 4);

                    if (extendedblockstorage != NULL_BLOCK_STORAGE) {
                        return extendedblockstorage.get(x & 15, y & 15, z & 15);
                    }
                }

                return Blocks.AIR.getDefaultState();
            } catch (Throwable throwable) {
                CrashReport crashreport = CrashReport.makeCrashReport(throwable, "Getting block state");
                CrashReportCategory crashreportcategory = crashreport.makeCategory("Block being got");
                crashreportcategory.addDetail("Location", () ->
                        CrashReportCategory.getCoordinateInfo(x, y, z)
                );
                throw new ReportedException(crashreport);
            }
        }
    }

    // ==============================================
    //                 setBlockState
    // ==============================================

    @Inject(method = "setBlockState", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;set"
            + "(IIILnet/minecraft/block/state/IBlockState;)V", shift = At.Shift.AFTER))
    private void onEBSSet_setBlockState_setOpacity(BlockPos pos, IBlockState state, CallbackInfoReturnable<IBlockState> cir) {
        if (!isColumn) {
            return;
        }
        opacityIndex.onOpacityChange(blockToLocal(pos.getX()), pos.getY(), blockToLocal(pos.getZ()), state.getLightOpacity(world, pos));
    }

    @Redirect(method = "setBlockState", at = @At(
            value = "FIELD",
            target = CHUNK_STORAGE_ARRAYS,
            args = "array=get"
    ))
    private ExtendedBlockStorage setBlockState_CubicChunks_EBSGetRedirect(ExtendedBlockStorage[] array, int index) {
        return getEBS_CubicChunks(index);
    }

    @Redirect(method = "setBlockState", at = @At(
            value = "FIELD",
            target = CHUNK_STORAGE_ARRAYS,
            args = "array=set"
    ))
    private void setBlockState_CubicChunks_EBSSetRedirect(ExtendedBlockStorage[] array, int index, ExtendedBlockStorage val) {
        setEBS_CubicChunks(index, val);
    }

    // ==============================================
    //                 getLightFor
    // ==============================================

    @Redirect(method = "getLightFor", at = @At(
            value = "FIELD",
            target = CHUNK_STORAGE_ARRAYS,
            args = "array=get"
    ))
    private ExtendedBlockStorage getLightFor_CubicChunks_EBSGetRedirect(ExtendedBlockStorage[] array, int index) {
        return getEBS_CubicChunks(index);
    }

    // ==============================================
    //                 setLightFor
    // ==============================================

    @Redirect(method = "setLightFor", at = @At(
            value = "FIELD",
            target = CHUNK_STORAGE_ARRAYS,
            args = "array=get"
    ))
    private ExtendedBlockStorage setLightFor_CubicChunks_EBSGetRedirect(ExtendedBlockStorage[] array, int index) {
        return getEBS_CubicChunks(index);
    }

    @Redirect(method = "setLightFor", at = @At(
            value = "FIELD",
            target = CHUNK_STORAGE_ARRAYS,
            args = "array=set"
    ))
    private void setLightFor_CubicChunks_EBSSetRedirect(ExtendedBlockStorage[] array, int index, ExtendedBlockStorage ebs) {
        setEBS_CubicChunks(index, ebs);
    }

    // ==============================================
    //             getLightSubtracted
    // ==============================================

    @Redirect(method = "getLightSubtracted", at = @At(
            value = "FIELD",
            target = CHUNK_STORAGE_ARRAYS,
            args = "array=get"
    ))
    private ExtendedBlockStorage getLightSubtracted_CubicChunks_EBSGetRedirect(ExtendedBlockStorage[] array, int index) {
        return getEBS_CubicChunks(index);
    }

    // ==============================================
    //                  addEntity
    // ==============================================

    // TODO: Use @ModifyConstant with expandConditions when it's implemented
    @Overwrite
    public void addEntity(Entity entityIn) {
        this.hasEntities = true;
        int i = MathHelper.floor(entityIn.posX / 16.0D);
        int j = MathHelper.floor(entityIn.posZ / 16.0D);

        if (i != this.x || j != this.z) {
            CubicChunks.LOGGER.warn("Wrong location! ({}, {}) should be ({}, {}), {}", new Object[]{Integer.valueOf(i), Integer.valueOf(j), Integer
                    .valueOf(this.x), Integer.valueOf(this.z), entityIn});
            entityIn.setDead();
        }

        int k = MathHelper.floor(entityIn.posY / 16.0D);

        if (k < Coords.blockToCube(getCubicWorld().getMinHeight())) {
            k = Coords.blockToCube(getCubicWorld().getMinHeight());
        }

        if (k >= Coords.blockToCube(getCubicWorld().getMaxHeight())) {
            k = Coords.blockToCube(getCubicWorld().getMaxHeight()) - 1;
        }

        net.minecraftforge.common.MinecraftForge.EVENT_BUS
                .post(new net.minecraftforge.event.entity.EntityEvent.EnteringChunk(entityIn, this.x, this.z, entityIn.chunkCoordX,
                        entityIn.chunkCoordZ));
        entityIn.addedToChunk = true;
        entityIn.chunkCoordX = this.x;
        entityIn.chunkCoordY = k;
        entityIn.chunkCoordZ = this.z;
        getEntityList_CubicChunks(k).add(entityIn);
    }

    // ==============================================
    //             removeEntityAtIndex
    // ==============================================

    @Overwrite
    public void removeEntityAtIndex(Entity entityIn, int index) {
        if (index < Coords.blockToCube(getCubicWorld().getMinHeight())) {
            index = Coords.blockToCube(getCubicWorld().getMinHeight());
        }

        if (index >= Coords.blockToCube(getCubicWorld().getMaxHeight())) {
            index = Coords.blockToCube(getCubicWorld().getMaxHeight()) - 1;
        }

        getEntityList_CubicChunks(index).remove(entityIn);
    }

    // ==============================================
    //              removeTileEntity
    // ==============================================

    @Redirect(method = "addTileEntity(Lnet/minecraft/tileentity/TileEntity;)V", at = @At(value = "FIELD", target = CHUNK_IS_CHUNK_LOADED))
    private boolean addTileEntity_isChunkLoadedCubeRedirect(Chunk chunk, TileEntity te) {
        if (!isColumn) {
            return isChunkLoaded;
        }
        Cube cube = this.getLoadedCube(blockToCube(te.getPos().getY()));
        return cube != null && cube.isCubeLoaded();
    }

    // ==============================================
    //              removeTileEntity
    // ==============================================

    @Redirect(method = "removeTileEntity", at = @At(value = "FIELD", target = CHUNK_IS_CHUNK_LOADED))
    private boolean removeTileEntity_isChunkLoadedCubeRedirect(Chunk chunk, BlockPos pos) {
        if (!isColumn) {
            return isChunkLoaded;
        }
        Cube cube = this.getLoadedCube(blockToCube(pos.getY()));
        return cube != null && cube.isCubeLoaded();
    }

    // ==============================================
    //        getEntitiesWithinAABBForEntity
    // ==============================================

    @ModifyArg(method = "getEntitiesWithinAABBForEntity",
               at = @At(value = "INVOKE", target = MATH_HELPER_CLAMP_I), index = 1)
    private int getEntAABBFor_CubicChunks_getMinYConst(int origY) {
        return Coords.blockToCube(getCubicWorld().getMinHeight());
    }

    @ModifyArg(method = "getEntitiesWithinAABBForEntity",
               at = @At(value = "INVOKE", target = MATH_HELPER_CLAMP_I), index = 2)
    private int getEntAABBFor_CubicChunks_getMaxYConst(int origY) {
        return Coords.blockToCube(getCubicWorld().getMaxHeight()) - 1;
    }

    @Redirect(
            method = "getEntitiesWithinAABBForEntity",
            at = @At(value = "FIELD", target = CHUNK_ENTITY_LISTS, args = "array=get")
    )
    private ClassInheritanceMultiMap<Entity> getEntAABBFor_CubicChunks_EntityListsGetRedirect(ClassInheritanceMultiMap<Entity>[] array, int index) {
        return getEntityList_CubicChunks(index);
    }

    // ==============================================
    //          getEntitiesOfTypeWithinAAAB
    // ==============================================

    @ModifyArg(method = "getEntitiesOfTypeWithinAAAB",
               at = @At(value = "INVOKE", target = MATH_HELPER_CLAMP_I), index = 1)
    private int getEntOfTypeAABB_CubicChunks_getMinYConst(int origY) {
        return Coords.blockToCube(getCubicWorld().getMinHeight());
    }

    @ModifyArg(method = "getEntitiesOfTypeWithinAAAB",
               at = @At(value = "INVOKE", target = MATH_HELPER_CLAMP_I), index = 2)
    private int getEntOfTypeAABB_CubicChunks_getMaxYConst(int origY) {
        return Coords.blockToCube(getCubicWorld().getMaxHeight()) - 1;
    }

    @Redirect(
            method = "getEntitiesOfTypeWithinAAAB",
            at = @At(value = "FIELD", target = CHUNK_ENTITY_LISTS, args = "array=get")
    )
    private ClassInheritanceMultiMap<Entity> getEntOfTypeAABB_CubicChunks_EntityListsGetRedirect(ClassInheritanceMultiMap<Entity>[] arr, int index) {
        return getEntityList_CubicChunks(index);
    }

    // public boolean needsSaving(boolean p_76601_1_) - TODO: needsSaving

    // ==============================================
    //            getPrecipitationHeight
    // ==============================================

    @Inject(method = "getPrecipitationHeight", at = @At(value = "HEAD"), cancellable = true)
    private void getPrecipitationHeight_CubicChunks_Replace(BlockPos pos, CallbackInfoReturnable<BlockPos> cbi) {
        if (isColumn) {
            // TODO: precipitationHeightMap
            BlockPos ret = new BlockPos(pos.getX(), getHeightValue(blockToLocal(pos.getX()), blockToLocal(pos.getZ())), pos.getZ());
            cbi.setReturnValue(ret);
            cbi.cancel();
        }
    }

    // ==============================================
    //                    onTick
    // ==============================================

    // TODO: check if we are out of time earlier?
    @Inject(method = "onTick", at = @At(value = "RETURN"))
    private void onTick_CubicChunks_TickCubes(boolean tryToTickFaster, CallbackInfo cbi) {
        if (!isColumn) {
            return;
        }
        this.getLoadedCubes().forEach(c -> c.tickCube(tryToTickFaster));
    }

    // ==============================================
    //               isEmptyBetween
    // ==============================================

    @Overwrite
    public boolean isEmptyBetween(int startY, int endY) {
        if (startY < getCubicWorld().getMinHeight()) {
            startY = getCubicWorld().getMinHeight();
        }

        if (endY >= getCubicWorld().getMaxHeight()) {
            endY = getCubicWorld().getMaxHeight() - 1;
        }

        for (int i = startY; i <= endY; i += 16) {
            ExtendedBlockStorage extendedblockstorage = getEBS_CubicChunks(i >> 4);

            if (extendedblockstorage != NULL_BLOCK_STORAGE && !extendedblockstorage.isEmpty()) {
                return false;
            }
        }

        return true;
    }

    // ==============================================
    //              setStorageArrays
    // ==============================================

    @Inject(method = "setStorageArrays", at = @At(value = "HEAD"))
    private void setStorageArrays_CubicChunks_NotSupported(ExtendedBlockStorage[] newStorageArrays, CallbackInfo cbi) {
        if (isColumn) {
            throw new UnsupportedOperationException("setting storage arrays it not supported with cubic chunks");
        }
    }

    // ==============================================
    //                  fillChunk
    // ==============================================

    @SideOnly(Side.CLIENT)
    @Inject(method = "fillChunk", at = @At(value = "HEAD"))
    private void fillChunk_CubicChunks_NotSupported(PacketBuffer buf, int i, boolean flag, CallbackInfo cbi) {
        if (isColumn) {
            throw new UnsupportedOperationException("setting storage arrays it not supported with cubic chunks");
        }
    }

    // ==============================================
    //             enqueueRelightChecks
    // ==============================================

    @SideOnly(Side.CLIENT)
    @Inject(method = "enqueueRelightChecks", at = @At(value = "HEAD"), cancellable = true)
    private void enqueueRelightChecks_CubicChunks_NotSupported(CallbackInfo cbi) {
        if (isColumn) {
            // todo: enqueueRelightChecks
            cbi.cancel();
        }
    }

    // ==============================================
    //                  checkLight
    // ==============================================

    @Inject(method = "checkLight()V", at = @At(value = "HEAD"), cancellable = true)
    private void checkLight_CubicChunks_NotSupported(CallbackInfo cbi) {
        if (isColumn) {
            // todo: checkLight
            cbi.cancel();
        }
    }

    // private void setSkylightUpdated() - noone should use it

    // private void checkLightSide(EnumFacing facing) - noone should use it

    // private boolean checkLight(int x, int z) - TODO: checkLight

    @Overwrite
    public ClassInheritanceMultiMap<Entity>[] getEntityLists() {
        // TODO: return array of wrappers, 0-th entry will wrap everything below y=1, the 15-th entry will wrap everything above the top
        // at least if possible
        return this.entityLists;
    }

    // ==============================================
    //           removeInvalidTileEntity
    // ==============================================

    @Redirect(method = "removeInvalidTileEntity", at = @At(value = "FIELD", target = CHUNK_IS_CHUNK_LOADED))
    private boolean removeInvalidTileEntity_isChunkLoadedCubeRedirect(Chunk chunk, BlockPos pos) {
        if (!isColumn) {
            return isChunkLoaded;
        }
        Cube cube = this.getLoadedCube(blockToCube(pos.getY()));
        return cube != null && cube.isCubeLoaded();
    }
}