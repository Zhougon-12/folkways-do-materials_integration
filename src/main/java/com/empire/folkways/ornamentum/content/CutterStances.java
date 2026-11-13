package com.empire.folkways.ornamentum.content;

import com.ldtteam.domumornamentum.block.ArchitectsCutterBlock;
import io.github.izakyl.folkways.core.world.api.Footing;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.work.api.Stances;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** 切割机的工作站位：优先站在正面，退回到任意可达格子。 */
public final class CutterStances {
    private CutterStances() {
    }

    public static Optional<Stances> of(ServerLevel level, WorldPos at) {
        if (!at.in(level) || !level.isLoaded(at.block(level))) {
            return Optional.empty();
        }
        BlockPos target = at.block(level);
        BlockState state = level.getBlockState(target);
        if (!(state.getBlock() instanceof ArchitectsCutterBlock)) {
            return Optional.empty();
        }

        Direction facing = state.getValue(ArchitectsCutterBlock.FACING);
        BlockPos front = target.relative(facing);
        List<BlockPos> standing = new ArrayList<>(2);
        for (BlockPos cell : List.of(front.below(), front)) {
            if (level.isLoaded(cell) && Footing.withFeetAt(level, cell)) {
                standing.add(cell);
            }
        }
        if (!standing.isEmpty()) {
            List<WorldPos> cells = new ArrayList<>(standing.size());
            for (BlockPos cell : standing) {
                cells.add(at.at(at.cell().offset(cell.subtract(target))));
            }
            return Stances.of(cells);
        }
        return Stances.of(level, Reach.workableCells(level, target));
    }

    public static boolean cutter(BlockState state) {
        return state.getBlock() instanceof ArchitectsCutterBlock;
    }
}
