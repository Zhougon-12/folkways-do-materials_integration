package com.empire.folkways.ornamentum;

import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlock;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.StructureCompat;
import io.github.izakyl.folkways.core.work.api.StructureCompat.Voxels;
import io.github.izakyl.folkways.core.world.api.StructurePayload;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 给每一个 Do 材质方块注册 Folkways 的 StructureCompat.Handler。
 *
 * <p>Handler 是 Folkways 原生建造链里唯一能同时携带「方块状态 + 方块实体数据」的扩展点：
 * 蓝图解析、工地材料、幽灵、放置、完成判定都会经过它。</p>
 */
public final class OrnamentumHandlers {
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(FolkwaysOrnamentum.MODID, "materially_textured");

    private OrnamentumHandlers() {
    }

    public static void install() {
        int count = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (id == null || !id.getNamespace().equals(FolkwaysOrnamentum.NAMESPACE)) {
                continue;
            }
            if (!(block instanceof IMateriallyTexturedBlock)) {
                continue;
            }
            if (id.getPath().equals("dynamic_timberframe")) {
                // 这个方块的方块实体有独立 NBT 结构；先不注册，避免把普通 textureData 塞给它。
                FolkwaysOrnamentum.LOG.info("[{}] 暂不注册 dynamic_timberframe（独立 NBT 结构）",
                        FolkwaysOrnamentum.MODID);
                continue;
            }
            try {
                StructureCompat.register(block, new TexturedHandler(block));
                count++;
            } catch (Throwable error) {
                FolkwaysOrnamentum.LOG.warn("[{}] 注册结构处理器失败：{}（{}）",
                        FolkwaysOrnamentum.MODID, id, error.toString());
            }
        }
        FolkwaysOrnamentum.LOG.info("[{}] 已为 {} 个 Do 材质方块注册原生结构处理器",
                FolkwaysOrnamentum.MODID, count);
        OrnamentumSchematics.install();
        FolkwaysOrnamentum.LOG.info("[{}] 已安装 Do 双格门放置适配器", FolkwaysOrnamentum.MODID);
    }

    public record TexturedHandler(Block block) implements StructureCompat.Handler {
        @Override
        public ResourceLocation id() {
            return ID;
        }

        @Override
        public void inspect(StructurePayload payload) {
            // 材质数据允许为空（缺材质时放置会补默认值）；只有存在却解析不了才拒绝。
            CompoundTag tag = payload.tag();
            if (tag.contains(DoPayload.TEXTURE, 10)) {
                CompoundTag texture = tag.getCompound(DoPayload.TEXTURE);
                if (!texture.isEmpty() && DoPayload.decode(texture).isEmpty()) {
                    throw StructureCompat.invalid();
                }
            }
        }

        @Override
        public BlockEntity detached(HolderLookup.Provider provider, BlockPos pos, BlockState state,
                                   StructurePayload payload) {
            if (!(state.getBlock() instanceof EntityBlock entityBlock)) {
                return null;
            }
            BlockEntity entity = entityBlock.newBlockEntity(pos, state);
            if (entity == null) {
                return null;
            }

            CompoundTag tag = new CompoundTag();
            CompoundTag texture = DoPayload.textureFor(state, payload.tag());
            if (!texture.isEmpty()) {
                tag.put(DoPayload.TEXTURE, texture);
            }
            entity.loadWithComponents(tag, provider);
            return entity;
        }

        @Override
        public CompoundTag capture(BlockEntity entity) {
            return DoPayload.capture(entity);
        }

        @Override
        public CompoundTag placementData(HolderLookup.Provider registries, BlockPos pos, BlockState state,
                                         StructurePayload payload) {
            // VanillaSchematics.loadData 会把它 loadWithComponents 到刚放下的 BE 里。
            CompoundTag tag = new CompoundTag();
            CompoundTag texture = DoPayload.textureFor(state, payload.tag());
            if (!texture.isEmpty()) {
                tag.put(DoPayload.TEXTURE, texture);
            }
            return tag;
        }

        @Override
        public List<Need> materials(BlockEntity entity) {
            if (entity == null) {
                return List.of();
            }
            ItemStack stack = DoPayload.stack(entity.getBlockState(), DoPayload.capture(entity));
            if (stack.isEmpty()) {
                return List.of();
            }
            return List.of(new Need(ItemSpec.exact(stack), 1L));
        }

        @Override
        public Voxels voxels(StructurePayload payload) {
            ItemStack stack = DoPayload.stack(block.defaultBlockState(), payload.tag());
            ItemSpec spec = stack.isEmpty() ? ItemSpec.of(block.asItem()) : ItemSpec.exact(stack);
            return new Voxels(1, Map.of(spec, 1));
        }

        @Override
        public Voxels voxels(BlockEntity entity) {
            if (entity == null) {
                return new Voxels(1, Map.of(ItemSpec.of(block.asItem()), 1));
            }
            ItemStack stack = DoPayload.stack(entity.getBlockState(), DoPayload.capture(entity));
            ItemSpec spec = stack.isEmpty() ? ItemSpec.of(block.asItem()) : ItemSpec.exact(stack);
            return new Voxels(1, Map.of(spec, 1));
        }

        @Override
        public boolean matches(BlockEntity entity, StructurePayload payload) {
            return DoPayload.matches(entity, payload.tag());
        }

        @Override
        public StructurePayload transformed(StructurePayload payload, Mirror mirror, Rotation rotation) {
            // Do 的材质部件 id 与朝向无关；蓝图整体旋转时只需要保留 textureData。
            return payload;
        }

        @Override
        public void turn(BlockEntity entity, Mirror mirror, Rotation rotation) {
            // 不需要旋转方块实体。
        }
    }
}
