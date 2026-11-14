package com.empire.folkways.ornamentum.content;

import io.github.izakyl.folkways.core.world.api.Footing;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.work.api.Stances;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import materials_integration.SortingWorkbenchBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/** The sorting workbench is a solid block; residents stand beside it, never inside it. */
public final class SorterStances {
    private SorterStances() {
    }

    public static Optional<Stances> of(ServerLevel level, WorldPos at) {
        if (!at.in(level) || !level.isLoaded(at.block(level))) {
            return Optional.empty();
        }
        BlockPos target = at.block(level);
        if (!(level.getBlockState(target).getBlock() instanceof SortingWorkbenchBlock)) {
            return Optional.empty();
        }

        List<BlockPos> standing = new ArrayList<>(4);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos cell = target.relative(direction);
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
}
