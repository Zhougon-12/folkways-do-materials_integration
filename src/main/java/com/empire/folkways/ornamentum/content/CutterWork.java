package com.empire.folkways.ornamentum.content;

import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlockComponent;
import com.ldtteam.domumornamentum.recipe.architectscutter.ArchitectsCutterRecipe;
import com.ldtteam.domumornamentum.recipe.architectscutter.ArchitectsCutterRecipeInput;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Hold;
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
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Block;

/** 一次「在切割机上切 N 个精确 Do 物品」的工作节点。 */
public final class CutterWork implements Node {
    static final int TICKS_PER_CUT = 40;
    static final int MOST_RUNS = 8;

    private final NodeSpec spec;
    private final ResourceLocation recipe;
    private final CutterPlans.Plan plan;
    private final int runs;
    private final WorldPos at;
    private boolean finished;

    CutterWork(UUID colony, WorldPos at, Stances stances, CutterPlans.Plan plan, int runs) {
        this.at = at;
        this.plan = plan;
        this.runs = runs;
        this.recipe = plan.recipe();

        List<Need> needs = new ArrayList<>(plan.components().size() * runs);
        for (int run = 0; run < runs; run++) {
            for (ItemSpec material : CutterPlans.needs(plan)) {
                needs.add(new Need(material, 1L));
            }
        }

        UUID id = UUID.nameUUIDFromBytes(("folkways_ornamentum:cutter/" + colony + "/" + at + "/"
                + recipe + "/" + plan.preview().getComponents()).getBytes(StandardCharsets.UTF_8));

        this.spec = NodeSpec.of(id, OrnamentumContent.ID, new WorkSite.AtBlock(at), stances,
                        Workload.Once.of(TICKS_PER_CUT * runs))
                .needs(needs)
                .gives(new Amount(Goods.specOf(plan.preview()), (long) plan.perRun() * runs))
                .leaves(List.of())
                .gesture(WorkGesture.SWING)
                .focus(at.cell())
                .estimate(TICKS_PER_CUT * runs)
                .doing(OrnamentumContent.CUTTING, Goods.specOf(plan.preview()))
                .vocation(io.github.izakyl.folkways.vanilla.build.BuildContent.trade())
                .done();
    }

    @Override
    public NodeSpec spec() {
        return this.spec;
    }

    @Override
    public Optional<RefusalKind> planned(ServerLevel level) {
        // 切割工位由 colony 的节点排程天然串行；这里不再额外占用 Hold，
        // 否则规划器候选（未真正执行）会永久占住工位，后续工单全部 STATION_BUSY。
        return Optional.empty();
    }

    @Override
    public boolean ready(ServerLevel level) {
        return true;
    }

    @Override
    public Optional<RefusalKind> refusal(ServerLevel level) {
        if (this.finished) {
            return Optional.empty();
        }
        if (!this.at.in(level) || !level.isLoaded(this.at.block(level))) {
            return Optional.empty();
        }
        return CutterStances.cutter(level.getBlockState(this.at.block(level)))
                ? Optional.empty()
                : Optional.of(OrnamentumRefusal.NOT_A_CUTTER);
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

        RecipeHolder<ArchitectsCutterRecipe> holder = CutterPlans.recipes(level).stream()
                .filter(candidate -> candidate.id().equals(this.recipe))
                .findFirst().orElse(null);
        if (holder == null || holder.value().getBlock() != this.plan.block()) {
            return Outcome.failed(OrnamentumRefusal.RECIPE_CHANGED);
        }

        List<ItemStack> pool = new ArrayList<>(who.supplied());
        List<ItemStack> made = new ArrayList<>(this.runs);

        for (int run = 0; run < this.runs; run++) {
            SimpleContainer input = new SimpleContainer(this.plan.components().size());
            for (int index = 0; index < this.plan.components().size(); index++) {
                IMateriallyTexturedBlockComponent component = this.plan.components().get(index);
                Block chosen = this.plan.chosen().get(index);
                ItemStack one = take(pool, stack -> stack.getItem() instanceof BlockItem item
                        && item.getBlock() == chosen
                        && item.getBlock().defaultBlockState().is(component.getValidSkins()));
                if (one.isEmpty()) {
                    return Outcome.failed(OrnamentumRefusal.NOTHING_TO_WORK_WITH);
                }
                input.setItem(index, one);
            }

            ArchitectsCutterRecipeInput recipeInput = new ArchitectsCutterRecipeInput(input);
            if (!holder.value().matches(recipeInput, level)) {
                return Outcome.failed(OrnamentumRefusal.NOTHING_TO_WORK_WITH);
            }
            ItemStack out = holder.value().assemble(recipeInput, level.registryAccess());
            if (out.isEmpty() || !Goods.matches(out, Goods.specOf(this.plan.preview()))) {
                return Outcome.failed(OrnamentumRefusal.RECIPE_CHANGED);
            }
            made.add(out);
        }

        com.empire.folkways.ornamentum.FolkwaysOrnamentum.LOG.info(
                "[{}] cutter work made={} at={} preview={}",
                com.empire.folkways.ornamentum.FolkwaysOrnamentum.MODID,
                made.size(), this.at, this.plan.preview());
        this.finished = true;
        return new Outcome.Done(List.of(), made, Optional.empty(), pool);
    }

    private static ItemStack take(List<ItemStack> pool, Predicate<ItemStack> which) {
        for (int index = 0; index < pool.size(); index++) {
            ItemStack stack = pool.get(index);
            if (stack.isEmpty() || !which.test(stack)) {
                continue;
            }
            ItemStack one = stack.copyWithCount(1);
            if (stack.getCount() <= 1) {
                pool.remove(index);
            } else {
                pool.set(index, stack.copyWithCount(stack.getCount() - 1));
            }
            return one;
        }
        return ItemStack.EMPTY;
    }
}
