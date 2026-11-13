package com.empire.folkways.ornamentum.content;

import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkGesture;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import materials_integration.MaterialsIntegration;

/** One batch of resource sorting at a Materials Integration sorting workbench. */
public final class SorterWork implements Node {
    static final int TICKS_PER_RUN = 25;
    static final int MOST_RUNS = 16;

    private final NodeSpec spec;
    private final ItemStack input;
    private final ItemStack output;
    private final int runs;
    private final WorldPos at;
    private boolean finished;

    public SorterWork(UUID colony, WorldPos at, Stances stances, ItemStack input, ItemStack output, int runs) {
        this.at = at;
        this.input = input.copyWithCount(1);
        this.output = output.copy();
        this.runs = Math.max(1, runs);

        UUID id = UUID.nameUUIDFromBytes(("folkways_ornamentum:sorter/" + colony + "/" + at + "/"
                + this.input.getItem() + "/" + this.output.getItem()).getBytes(StandardCharsets.UTF_8));
        List<Need> needs = new ArrayList<>(this.runs);
        for (int run = 0; run < this.runs; run++) {
            needs.add(new Need(Goods.specOf(this.input), 1L));
        }
        this.spec = NodeSpec.of(id, OrnamentumContent.ID, new WorkSite.AtBlock(at), stances,
                        Workload.Once.of(TICKS_PER_RUN * this.runs))
                .needs(needs)
                .gives(new Amount(Goods.specOf(this.output), (long) this.output.getCount() * this.runs))
                .leaves(List.of())
                .gesture(WorkGesture.SWING)
                .focus(at.cell())
                .estimate(TICKS_PER_RUN * this.runs)
                .doing(OrnamentumContent.SORTING, Goods.specOf(this.output))
                .vocation(io.github.izakyl.folkways.vanilla.build.BuildContent.trade())
                .done();
    }

    @Override
    public NodeSpec spec() {
        return this.spec;
    }

    @Override
    public Optional<RefusalKind> planned(ServerLevel level) {
        return Optional.empty();
    }

    @Override
    public boolean ready(ServerLevel level) {
        return true;
    }

    @Override
    public Optional<RefusalKind> refusal(ServerLevel level) {
        if (!this.at.in(level) || !level.isLoaded(this.at.block(level))) {
            return Optional.empty();
        }
        return level.getBlockState(this.at.block(level)).getBlock()
                == MaterialsIntegration.SORTING_WORKBENCH.get()
                ? Optional.empty()
                : Optional.of(OrnamentumRefusal.NOT_A_SORTER);
    }

    @Override
    public void released(ServerLevel level, Worker who, Ending how) {
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        Optional<RefusalKind> refused = this.refusal(level);
        if (refused.isPresent()) {
            return Outcome.failed(refused.get());
        }
        List<ItemStack> pool = new ArrayList<>(who.supplied());
        int consumed = 0;
        for (int index = 0; index < pool.size() && consumed < this.runs; index++) {
            ItemStack stack = pool.get(index);
            if (stack.isEmpty() || !Goods.matches(stack, Goods.specOf(this.input))) {
                continue;
            }
            int take = Math.min(stack.getCount(), this.runs - consumed);
            consumed += take;
            if (take >= stack.getCount()) {
                pool.remove(index);
                index--;
            } else {
                pool.set(index, stack.copyWithCount(stack.getCount() - take));
            }
        }
        if (consumed < this.runs) {
            return Outcome.failed(OrnamentumRefusal.NOTHING_TO_WORK_WITH);
        }
        this.finished = true;
        ItemStack made = this.output.copyWithCount(this.output.getCount() * this.runs);
        return new Outcome.Done(List.of(), List.of(made), Optional.empty(), pool);
    }
}
