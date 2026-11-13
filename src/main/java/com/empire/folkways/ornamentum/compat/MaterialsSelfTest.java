package com.empire.folkways.ornamentum.compat;

import com.empire.folkways.ornamentum.FolkwaysOrnamentum;
import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlock;
import com.ldtteam.domumornamentum.client.model.data.MaterialTextureData;
import com.ldtteam.domumornamentum.entity.block.IMateriallyTexturedBlockEntity;
import com.empire.folkways.ornamentum.DoPayload;
import com.empire.folkways.ornamentum.content.OrnamentumContent;
import com.empire.folkways.ornamentum.content.SorterWork;
import com.empire.folkways.ornamentum.content.SorterWorkshop;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.ToolNeed;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.effect.Effects;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import materials_integration.MaterialsIntegration;

/** Server-side smoke test for the Materials Integration bridge. */
public final class MaterialsSelfTest {
    private static int stockDue = -1;
    private static Colony stockColony;
    private static MinecraftServer stockServer;
    private MaterialsSelfTest() {
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (stockDue < 0 || event.getServer().getTickCount() < stockDue) {
            return;
        }
        stockDue = -1;
        long planks = io.github.izakyl.folkways.core.front.engine.colony.ColonyStocks.available(
                stockColony, stockServer, ItemSpec.of(Items.OAK_PLANKS));
        long resource = io.github.izakyl.folkways.core.front.engine.colony.ColonyStocks.available(
                stockColony, stockServer, ItemSpec.of(MaterialsIntegration.PLANKS_INTEGRATION.get()));
        FolkwaysOrnamentum.LOG.info("[{}] MATERIALS-CITIZEN-STOCK planks={} resource={}",
                FolkwaysOrnamentum.MODID, planks, resource);
    }

    public static void run(MinecraftServer server) {
        List<Colony> colonies = Colonies.all(server);
        if (colonies.isEmpty()) {
            FolkwaysOrnamentum.LOG.error("[{}] MATERIALS-NO-COLONY", FolkwaysOrnamentum.MODID);
            return;
        }
        Colony colony = colonies.get(0);
        ServerPlayer player = server.getPlayerList().getPlayers().isEmpty()
                ? null : server.getPlayerList().getPlayers().get(0);
        if (player == null) {
            FolkwaysOrnamentum.LOG.error("[{}] MATERIALS-NO-PLAYER", FolkwaysOrnamentum.MODID);
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos base = player.blockPosition().offset(3, 0, 0);
        level.getChunk(base);
        level.setBlock(base.below(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(base, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(base.above(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);

        testDoDrop(level, base.above());
        testSorter(level, colony, base);
        testCitizenSort(server, level, colony, base);

        FolkwaysOrnamentum.LOG.info("[{}] MATERIALS-DONE", FolkwaysOrnamentum.MODID);
    }

    private static void testDoDrop(ServerLevel level, BlockPos pos) {
        try {
            Block panel = BuiltInRegistries.BLOCK.getOptional(
                    ResourceLocation.fromNamespaceAndPath("domum_ornamentum", "panel")).orElse(null);
            if (panel == null || !(panel instanceof IMateriallyTexturedBlock textured)) {
                FolkwaysOrnamentum.LOG.warn("[{}] MATERIALS-DO-MISSING", FolkwaysOrnamentum.MODID);
                return;
            }
            BlockState state = panel.defaultBlockState();
            CompoundTag payload = new CompoundTag();
            payload.put(DoPayload.TEXTURE, DoPayload.defaults(panel));
            MaterialTextureData data = DoPayload.decode(payload.getCompound(DoPayload.TEXTURE));
            level.setBlock(pos, state, 3);
            if (level.getBlockEntity(pos) instanceof IMateriallyTexturedBlockEntity entity) {
                entity.updateTextureDataWith(data);
            }
            int before = level.getEntitiesOfClass(ItemEntity.class,
                    new AABB(pos).inflate(3)).size();
            boolean broken = level.destroyBlock(pos, true);
            List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class,
                    new AABB(pos).inflate(3));
            FolkwaysOrnamentum.LOG.info(
                    "[{}] MATERIALS-DO broken={} entitiesBefore={} drops={} items={}",
                    FolkwaysOrnamentum.MODID, broken, before, drops.size(),
                    drops.stream().map(item -> item.getItem().toString()).toList());
        } catch (Throwable error) {
            FolkwaysOrnamentum.LOG.error("[{}] MATERIALS-DO-FAIL", FolkwaysOrnamentum.MODID, error);
        }
    }

    private static void testCitizenSort(MinecraftServer server, ServerLevel level, Colony colony, BlockPos pos) {
        try {
            BlockPos storePos = pos.offset(0, 0, 2);
            level.setBlock(storePos, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
            if (level.getBlockEntity(storePos) instanceof net.minecraft.world.Container container) {
                container.setItem(0, new ItemStack(MaterialsIntegration.PLANKS_INTEGRATION.get(), 64));
            }
            WorldPos store = WorldPos.of(level, storePos);
            colony.stores(ResourceLocation.fromNamespaceAndPath(FolkwaysOrnamentum.MODID, "materials_test"),
                    level.dimension(), List.of(store));
            colony.inventory().ensureObserved();
            BlockPos sorterPos = pos.offset(1, 0, 0);
            level.setBlock(sorterPos, MaterialsIntegration.SORTING_WORKBENCH.get().defaultBlockState(), 3);
            WorldPos at = WorldPos.of(level, sorterPos);
            colony.hold(OrnamentumContent.SORTER,
                    new io.github.izakyl.folkways.core.colony.api.Claimed.Point(at));
            SorterWorkshop workshop = new SorterWorkshop(UUID.randomUUID(), colony.id(), level, at,
                    Stances.at(level, sorterPos));
            Produce produce = new Produce(
                    io.github.izakyl.folkways.core.work.api.NodeSpec.of(
                            UUID.randomUUID(), OrnamentumContent.ID, new WorkSite.AtBlock(at),
                            Stances.WHEREVER, Workload.Once.of(1)).done(),
                    ItemSpec.of(Items.OAK_PLANKS), 8L, Produce.UNBOUNDED);
            var options = workshop.options(produce, null);
            if (options.isEmpty()) {
                FolkwaysOrnamentum.LOG.warn("[{}] MATERIALS-CITIZEN-NO-OPTION", FolkwaysOrnamentum.MODID);
                return;
            }
            grantBuilder(server, colony);
            colony.publish(OrnamentumContent.ID, level.dimension(), List.of(workshop));
            var grown = options.get(0).replacement();
            colony.submit(OrnamentumContent.ID, level.dimension(), grown, (id, ending) ->
                    FolkwaysOrnamentum.LOG.info("[{}] MATERIALS-CITIZEN-ENDING id={} how={}",
                            FolkwaysOrnamentum.MODID, id, ending));
            stockDue = server.getTickCount() + 400;
            stockColony = colony;
            stockServer = server;
            FolkwaysOrnamentum.LOG.info("[{}] MATERIALS-CITIZEN-SUBMIT options={}", FolkwaysOrnamentum.MODID, options.size());
        } catch (Throwable error) {
            FolkwaysOrnamentum.LOG.error("[{}] MATERIALS-CITIZEN-FAIL", FolkwaysOrnamentum.MODID, error);
        }
    }

    private static void grantBuilder(MinecraftServer server, Colony colony) {
        try {
            var building = io.github.izakyl.folkways.core.vocation.api.Vocations.required(
                    io.github.izakyl.folkways.vanilla.build.BuildContent.VOCATION);
            for (var body : io.github.izakyl.folkways.core.front.engine.colony.Residents.of(colony, server)) {
                body.licences().allow(new io.github.izakyl.folkways.core.resident.api.Licences.Trade(building), true);
            }
        } catch (Throwable error) {
            FolkwaysOrnamentum.LOG.warn("[{}] MATERIALS-LICENSE-FAIL {}", FolkwaysOrnamentum.MODID, error.toString());
        }
    }

    private static void testSorter(ServerLevel level, Colony colony, BlockPos pos) {
        try {
            Block sorter = MaterialsIntegration.SORTING_WORKBENCH.get();
            level.setBlock(pos, sorter.defaultBlockState(), 3);
            WorldPos at = WorldPos.of(level, pos);
            SorterWorkshop workshop = new SorterWorkshop(UUID.randomUUID(), colony.id(), level, at,
                    Stances.at(level, pos));
            Produce produce = new Produce(
                    io.github.izakyl.folkways.core.work.api.NodeSpec.of(
                            UUID.randomUUID(), OrnamentumContent.ID, new WorkSite.AtBlock(at),
                            Stances.WHEREVER, Workload.Once.of(1)).done(),
                    ItemSpec.of(Items.OAK_PLANKS), 4L, Produce.UNBOUNDED);
            int options = workshop.options(produce, null).size();
            FolkwaysOrnamentum.LOG.info("[{}] MATERIALS-SORTER at={} options={}",
                    FolkwaysOrnamentum.MODID, at, options);
            var claim = colony.hold(OrnamentumContent.SORTER, new Claimed.Point(at));
            FolkwaysOrnamentum.LOG.info("[{}] MATERIALS-SORTER-CLAIM claim={} claims={}",
                    FolkwaysOrnamentum.MODID,
                    claim.map(value -> value.id().toString()).orElse("NONE"),
                    colony.claims(OrnamentumContent.SORTER).size());

            ItemStack resource = new ItemStack(MaterialsIntegration.PLANKS_INTEGRATION.get(), 8);
            SorterWork work = new SorterWork(UUID.randomUUID(), at, Stances.at(level, pos),
                    resource, new ItemStack(Items.OAK_PLANKS, 4), 2);
            Worker worker = new Worker() {
                @Override public Resident resident() { return null; }
                @Override public net.minecraft.world.entity.Mob body() { return null; }
                @Override public net.minecraft.world.Container pack() { return null; }
                @Override public List<ItemStack> supplied() { return List.of(resource.copy()); }
                @Override public List<Worker.Store> within() { return List.of(); }
                @Override public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
                @Override public void spill(ItemStack stack) { }
                @Override public Effects effects() { return null; }
                @Override public List<Node> route() { return List.of(); }
                @Override public Placement placement() { return null; }
            };
            Outcome outcome = work.commit(level, worker);
            String made = outcome instanceof Outcome.Done done
                    ? done.made().stream().map(ItemStack::toString).toList().toString() : outcome.toString();
            FolkwaysOrnamentum.LOG.info("[{}] MATERIALS-SORT-COMMIT outcome={} made={}",
                    FolkwaysOrnamentum.MODID, outcome.getClass().getSimpleName(), made);
            level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        } catch (Throwable error) {
            FolkwaysOrnamentum.LOG.error("[{}] MATERIALS-SORTER-FAIL", FolkwaysOrnamentum.MODID, error);
        }
    }
}
