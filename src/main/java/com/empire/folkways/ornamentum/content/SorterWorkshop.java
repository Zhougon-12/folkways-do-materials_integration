package com.empire.folkways.ornamentum.content;

import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Intent;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.plan.api.Refinement.Change;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import materials_integration.MaterialsIntegration;
import materials_integration.SortingWorkbenchRecipeManager;

/** A claimed Materials Integration sorting workbench exposed to the Folkways planner. */
public record SorterWorkshop(UUID key, UUID colony, ServerLevel level, WorldPos at, Stances stances)
        implements Workshop {

    @Override
    public ResourceLocation id() {
        return OrnamentumContent.ID;
    }

    @Override
    public WorkSite site() {
        return new WorkSite.AtBlock(this.at);
    }

    @Override
    public List<Change> options(Intent intent, Grown graph) {
        if (!(intent instanceof Produce produce)) {
            return List.of();
        }
        List<Change> options = new ArrayList<>();
        for (var holder : MaterialsIntegration.RESOURCE_ITEMS) {
            ItemStack input = new ItemStack(holder.get());
            if (!SortingWorkbenchRecipeManager.isSortableResource(input)) {
                continue;
            }
            for (ItemStack output : SortingWorkbenchRecipeManager.getRecipesFor(input)) {
                if (output.isEmpty() || !Goods.overlap(produce.wanted(), Goods.specOf(output))) {
                    continue;
                }
                long capacity = produce.runs(
                        this.site(),
                        List.of(new Need(Goods.specOf(input), 1L)),
                        output.getCount(),
                        Optional.empty(),
                        SorterWork.MOST_RUNS
                );
                if (capacity < 1) {
                    continue;
                }
                SorterWork work = new SorterWork(this.colony, this.at, this.stances, input, output, (int) capacity);
                options.add(Change.of(Grown.of(work)));
                if (options.size() >= 8) {
                    return List.copyOf(options);
                }
            }
        }
        return List.copyOf(options);
    }
}
