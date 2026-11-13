package com.empire.folkways.ornamentum;

import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlock;
import io.github.izakyl.folkways.vanilla.build.SchematicPlacement;
import io.github.izakyl.folkways.vanilla.build.VanillaSchematics;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Folkways 原生放置适配器，只补一件事：Do 的双格门。
 *
 * <p>Folkways 对注册了 StructureCompat.Handler 的方块会跳过
 * {@code Block#setPlacedBy}，而 Do 的门正是靠原版 {@code DoorBlock.setPlacedBy}
 * 生成上半部分的。这里在放下下半部分后，用同一份材质数据补上上半部分。</p>
 */
public final class OrnamentumSchematics extends VanillaSchematics {
    public static void install() {
        SchematicPlacement.install(new OrnamentumSchematics());
    }

    @Override
    public void place(ServerLevel level, BlockPos pos, BlockState state, ItemStack stack, @Nullable CompoundTag data) {
        placeRaw(level, pos, state, stack, data);
        placeDoorHalf(level, pos, state, stack, data);
    }

    private void placeRaw(ServerLevel level, BlockPos pos, BlockState state, ItemStack stack,
                          @Nullable CompoundTag data) {
        super.place(level, pos, state, stack, data);
    }

    private void placeDoorHalf(ServerLevel level, BlockPos pos, BlockState state, ItemStack stack,
                               @Nullable CompoundTag data) {
        if (!(state.getBlock() instanceof IMateriallyTexturedBlock)) {
            return;
        }
        if (!state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                || state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.LOWER) {
            return;
        }

        BlockPos upperPos = pos.above();
        if (!level.isLoaded(upperPos)) {
            return;
        }
        BlockState upperState = state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        BlockState standing = level.getBlockState(upperPos);
        if (!standing.canBeReplaced() && !standing.is(state.getBlock())) {
            return;
        }
        // 走父类的完整放置流程：setBlock + loadWithComponents(textureData)
        placeRaw(level, upperPos, upperState, stack, data);
    }
}
