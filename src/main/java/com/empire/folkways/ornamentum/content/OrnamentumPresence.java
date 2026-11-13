package com.empire.folkways.ornamentum.content;

import com.ldtteam.domumornamentum.block.ArchitectsCutterBlock;
import materials_integration.SortingWorkbenchBlock;
import io.github.izakyl.folkways.core.colony.api.Claim;
import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.colony.api.Sweep;
import io.github.izakyl.folkways.core.front.api.Facing;
import io.github.izakyl.folkways.core.front.api.panel.Board;
import io.github.izakyl.folkways.core.front.api.panel.Board.Act.Ping;
import io.github.izakyl.folkways.core.front.api.panel.Board.Figure;
import io.github.izakyl.folkways.core.front.api.panel.Board.Row;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** 每个殖民地一份的切割机站点状态；向规划器发布 Workshop。 */
public final class OrnamentumPresence implements Facing, Claim.Watch {
    private final Colony colony;
    private final MinecraftServer server;
    private final Sweep sweep;
    private final CutterBookings bookings = new CutterBookings();
    private final Map<WorldPos, Station> stations = new LinkedHashMap<>();
    private final Set<ResourceKey<Level>> published = new LinkedHashSet<>();
    private boolean refreshPending;

    public OrnamentumPresence(Colony colony, MinecraftServer server) {
        this.colony = colony;
        this.server = server;
        this.sweep = new Sweep(colony, OrnamentumContent.ID, 40);
    }

    public CutterBookings bookings() {
        return this.bookings;
    }

    // ------------------------------------------------------------------
    // 认领观察
    // ------------------------------------------------------------------

    @Override
    public void held(Claim claim) {
        for (WorldPos at : claim.what().cells()) {
            this.bookings.admit(at);
        }
        this.refreshPending = true;
        this.sweep.nudge();
    }

    @Override
    public void released(Claim claim, ClaimRelease why) {
        for (WorldPos at : claim.what().cells()) {
            this.bookings.withdraw(at);
        }
        this.refreshPending = true;
        this.sweep.nudge();
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    public void tick(MinecraftServer server) {
        if (this.refreshPending) {
            this.refreshPending = false;
            this.refresh(server);
        }
        this.sweep.tick(server, this::refresh, this::goals);
    }

    private List<Grown> goals(ColonyView view) {
        // 切割工单完全由核心规划器根据已发布的 CutterWorkshop 产生；
        // 插件不再读取 BuildPresence.owed 或直接 colony.submit。
        return List.of();
    }

    private void refresh(MinecraftServer server) {
        Map<WorldPos, Station> fresh = new LinkedHashMap<>();
        Set<ResourceKey<Level>> dimensions = new LinkedHashSet<>();
        int claimed = 0;
        int published = 0;

        for (ColonyView view : this.colony.views(server)) {
            ServerLevel level = view.level();
            dimensions.add(level.dimension());
            List<io.github.izakyl.folkways.core.plan.api.Workshop> workshops = new ArrayList<>();

            for (BlockPos cell : view.blocks(OrnamentumContent.CUTTER)) {
                claimed++;
                if (!level.isLoaded(cell)) {
                    continue;
                }
                WorldPos at = WorldPos.of(level, cell);
                BlockState state = level.getBlockState(cell);
                if (!(state.getBlock() instanceof ArchitectsCutterBlock)) {
                    this.bookings.withdraw(at);
                    continue;
                }
                Optional<Stances> stances = CutterStances.of(level, at);
                if (stances.isEmpty()) {
                    this.bookings.withdraw(at);
                    continue;
                }
                this.bookings.admit(at);
                Station previous = this.stations.get(at);
                UUID key = previous == null ? UUID.randomUUID() : previous.key();
                Station station = new Station(level, key, at, stances.get());
                fresh.put(at, station);
                workshops.add(new CutterWorkshop(station.key(), this.colony.id(), level, at,
                        station.stances()));
                published++;
            }

            for (BlockPos cell : view.blocks(OrnamentumContent.SORTER)) {
                if (!level.isLoaded(cell)) {
                    continue;
                }
                if (!(level.getBlockState(cell).getBlock() instanceof SortingWorkbenchBlock)) {
                    continue;
                }
                WorldPos at = WorldPos.of(level, cell);
                UUID key = UUID.nameUUIDFromBytes(
                        ("folkways_ornamentum:sorter/" + this.colony.id() + "/" + at)
                                .getBytes(StandardCharsets.UTF_8));
                workshops.add(new SorterWorkshop(key, this.colony.id(), level, at,
                        io.github.izakyl.folkways.core.work.api.Stances.at(level, cell)));
            }

            this.colony.publish(OrnamentumContent.ID, level.dimension(), workshops);
        }
        if (System.getProperty("folkways_ornamentum.debug") != null && (claimed > 0 || !this.stations.isEmpty())) {
            com.empire.folkways.ornamentum.FolkwaysOrnamentum.LOG.info(
                    "[{}] joinery refresh claimed={} published={} stations={}",
                    com.empire.folkways.ornamentum.FolkwaysOrnamentum.MODID,
                    claimed, published, this.stations.size());
        }

        for (WorldPos gone : this.stations.keySet()) {
            if (!fresh.containsKey(gone)) {
                this.bookings.withdraw(gone);
            }
        }
        this.stations.clear();
        this.stations.putAll(fresh);

        for (ResourceKey<Level> dimension : this.published) {
            if (!dimensions.contains(dimension)) {
                this.colony.publish(OrnamentumContent.ID, dimension, List.of());
            }
        }
        this.published.clear();
        this.published.addAll(dimensions);
    }

    public void closed(Closing why) {
        this.bookings.clear();
        this.stations.clear();
        this.published.clear();
        this.refreshPending = false;
    }

    // ------------------------------------------------------------------
    // 面板
    // ------------------------------------------------------------------

    @Override
    public Optional<Board> board(ResourceLocation page, ColonyView view) {
        if (!page.equals(OrnamentumContent.ID)) {
            return Optional.empty();
        }
        ServerLevel level = view.level();
        List<Row> rows = new ArrayList<>();
        int here = 0;
        for (Station station : this.stations.values()) {
            if (!station.level().dimension().equals(level.dimension())) {
                continue;
            }
            BlockPos cell = station.at().cell();
            if (!level.isLoaded(cell)) {
                continue;
            }
            here++;
            rows.add(new Row(
                    new ItemStack(level.getBlockState(cell).getBlock()),
                    level.getBlockState(cell).getBlock().getName(),
                    Component.translatable("folkways.page.ornamentum.cutter.ready"),
                    List.of(new Ping(cell))
            ));
        }
        if (rows.isEmpty()) {
            rows.add(Row.of(ItemStack.EMPTY, Component.translatable("folkways.page.ornamentum.empty"), Component.empty()));
        }
        return Optional.of(new Board(
                List.of(new Figure("folkways.page.ornamentum.cutters", Component.literal(Integer.toString(here)))),
                rows,
                Optional.of(Component.translatable("folkways.page.ornamentum.empty"))
        ));
    }

    @Override
    public List<io.github.izakyl.folkways.core.front.api.notice.Line> blockLines(BlockPos at, ColonyView view) {
        return List.of();
    }

    private record Station(ServerLevel level, UUID key, WorldPos at, Stances stances) {
    }
}
