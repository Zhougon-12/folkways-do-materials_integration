package com.empire.folkways.ornamentum.content;

import com.ldtteam.domumornamentum.block.ArchitectsCutterBlock;
import materials_integration.SortingWorkbenchBlock;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.extension.api.Relating;
import io.github.izakyl.folkways.core.front.api.Delegation;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.Facing;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.front.api.panel.PageKind;
import io.github.izakyl.folkways.core.resident.api.Participation;
import io.github.izakyl.folkways.core.resident.api.Stake;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/** 建筑师切割机的原生内容注册（风格与 folkways-cookery 对齐）。 */
public final class OrnamentumContent {
    public static final ResourceLocation ID = id("joinery");
    public static final ResourceLocation CUTTER = id("cutter");
    public static final ResourceLocation SORTER = id("sorting_workbench");
    public static final ResourceLocation CUTTING = id("cutting");
    public static final ResourceLocation SORTING = id("sorting");

    private static final PageKind PAGE = PageKind.boarded(
            ID,
            "folkways.page.ornamentum.joinery",
            ResourceLocation.fromNamespaceAndPath("domum_ornamentum", "architectscutter")
    );

    private OrnamentumContent() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("folkways_ornamentum", path);
    }

    public static void declare(Declaring event) {
        event.colony(ID, scope -> {
            OrnamentumPresence presence = new OrnamentumPresence(scope.colony(), scope.server());
            scope.share(presence);
            scope.watch(CUTTER, presence);
            scope.watch(SORTER, presence);
            scope.tick(presence::tick);
            scope.closed(presence::closed);
        });
    }

    public static void relate(Relating event) {
        event.defaultParticipation(PersonBody.ID, Stake.urge(ID), Participation.ALWAYS);
    }

    public static void enroll(Registering event) {
        event.facing(ID, colony -> colony.service(ID, OrnamentumPresence.class).map(presence -> presence));
        event.enrollment(ID, new Enrollment(
                List.of(new Delegation(
                        CUTTER,
                        new Shape.Point((BlockState state) -> state.getBlock() instanceof ArchitectsCutterBlock),
                        ResourceLocation.fromNamespaceAndPath("domum_ornamentum", "architectscutter")
                ), new Delegation(
                        SORTER,
                        new Shape.Point((BlockState state) -> state.getBlock() instanceof SortingWorkbenchBlock),
                        ResourceLocation.fromNamespaceAndPath("materials_integration", "sorting_workbench")
                )),
                Schema.none(),
                List.of(PAGE)
        ));
    }

    public static Optional<Facing> facing(Colony colony) {
        return colony.service(ID, OrnamentumPresence.class).map(presence -> presence);
    }

    /** 测试/调试入口：直接让一台已认领的切割机切一个精确物品。 */
    public static int submitCut(Colony colony, net.minecraft.server.level.ServerLevel level,
                                net.minecraft.core.BlockPos cutter, io.github.izakyl.folkways.core.item.api.ItemSpec wanted) {
        OrnamentumPresence presence = colony.service(ID, OrnamentumPresence.class).orElse(null);
        if (presence == null) {
            return -1;
        }
        var stances = CutterStances.of(level, io.github.izakyl.folkways.core.world.api.WorldPos.of(level, cutter)).orElse(null);
        if (stances == null) {
            return -2;
        }
        var plans = CutterPlans.plansFor(level, wanted);
        if (plans.isEmpty()) {
            return -3;
        }
        CutterPlans.Plan plan = plans.get(0);
        CutterWork work = new CutterWork(colony.id(),
                io.github.izakyl.folkways.core.world.api.WorldPos.of(level, cutter),
                stances, plan, 1);
        com.empire.folkways.ornamentum.FolkwaysOrnamentum.LOG.info(
                "[{}] cuttest plan={} preview={} needs={}",
                com.empire.folkways.ornamentum.FolkwaysOrnamentum.MODID,
                plan.block(), plan.preview(), CutterPlans.needs(plan));
        colony.submit(ID, level.dimension(), io.github.izakyl.folkways.core.plan.api.Grown.of(work),
                (id, ending) -> com.empire.folkways.ornamentum.FolkwaysOrnamentum.LOG.info(
                        "[{}] cuttest ending id={} how={}",
                        com.empire.folkways.ornamentum.FolkwaysOrnamentum.MODID, id, ending));
        return 1;
    }
}
