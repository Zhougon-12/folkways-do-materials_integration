package com.empire.folkways.ornamentum.content;

import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Intent;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.plan.api.Refinement.Change;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import com.empire.folkways.ornamentum.FolkwaysOrnamentum;

/** 一台已被殖民地认领的切割机：向规划器回答「这个精确 Do 物品我能切」。 */
public record CutterWorkshop(UUID key, UUID colony, ServerLevel level, WorldPos at, Stances stances)
        implements Workshop {
    private static final AtomicInteger DEBUG = new AtomicInteger();

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
        var plans = CutterPlans.plansFor(this.level, produce.wanted());
        boolean debug = System.getProperty("folkways_ornamentum.debug") != null;
        if (debug) {
            FolkwaysOrnamentum.LOG.info("[{}] cutter options wanted={} plans={} at={}",
                    FolkwaysOrnamentum.MODID, produce.wanted(), plans.size(), this.at);
        }
        if (plans.isEmpty() && debug && DEBUG.getAndIncrement() < 12) {
            FolkwaysOrnamentum.LOG.info("[{}] cutter options: no plan for {} (want={})",
                    FolkwaysOrnamentum.MODID, this.at, produce.wanted());
        }
        for (CutterPlans.Plan plan : plans) {
            if (debug && DEBUG.getAndIncrement() < 24) {
                FolkwaysOrnamentum.LOG.info("[{}] cutter options: plan={} wanted={} at={}",
                        FolkwaysOrnamentum.MODID, plan.block(), produce.wanted(), this.at);
            }
            if (!Goods.overlap(produce.wanted(), Goods.specOf(plan.preview()))) {
                if (debug) {
                    FolkwaysOrnamentum.LOG.info("[{}] cutter overlap=false wanted={} preview={}",
                            FolkwaysOrnamentum.MODID, produce.wanted(), Goods.specOf(plan.preview()));
                }
                continue;
            }
            long capacity = produce.runs(this.site(), CutterPlans.perRunNeeds(plan), plan.perRun(),
                    Optional.empty(), CutterWork.MOST_RUNS);
            if (debug) {
                FolkwaysOrnamentum.LOG.info("[{}] cutter plan ok capacity={} preview={}",
                        FolkwaysOrnamentum.MODID, capacity, plan.preview());
            }
            if (capacity < 1) {
                continue;
            }
            CutterWork work = new CutterWork(this.colony, this.at, this.stances, plan, (int) capacity);
            options.add(Change.of(Grown.of(work)));
        }
        return List.copyOf(options);
    }
}
