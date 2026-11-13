package com.empire.folkways.ornamentum;

import com.empire.folkways.ornamentum.compat.MaterialsSelfTest;

import io.github.izakyl.folkways.core.colony.api.Claim;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.front.api.Facing;
import io.github.izakyl.folkways.core.front.api.Ghost;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.work.api.StructureCompat;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.build.Blueprint;
import io.github.izakyl.folkways.vanilla.build.BlueprintBlock;
import io.github.izakyl.folkways.vanilla.build.BlockData;
import io.github.izakyl.folkways.vanilla.build.BuildOrders;
import io.github.izakyl.folkways.vanilla.build.SchematicPlacement;
import io.github.izakyl.folkways.vanilla.build.StructureNbt;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 服务端自检：加载一份转换后的结构文件 → 走 Folkways 原生下单 → 预填材料 →
 * 逐 tick 输出工地/幽灵/完成度，供无头测试脚本抓日志。
 *
 * <p>启动参数：{@code -Dfolkways_ornamentum.selftest=<名字>}，
 * 名字可以是文件路径，或相对 {@code config/folkways_ornamentum/test} /
 * {@code folkways/blueprint} 的文件名；可带 {@code @x,y,z} 指定锚点。</p>
 */
public final class OrnamentumSelfTest {
    private static final String PROPERTY = "folkways_ornamentum.selftest";
    private static final int START_DELAY = 100;
    private static final int STATUS_EVERY = 20;
    private static final int END_AFTER = Integer.getInteger("folkways_ornamentum.selftest.ticks", 2400);

    private static boolean started;
    private static boolean ordered;
    private static boolean finished;
    private static boolean cutterMode = Boolean.getBoolean("folkways_ornamentum.cuttertest");
    private static boolean cutDirect = !"false".equals(System.getProperty("folkways_ornamentum.cutdirect", "true"));
    private static final boolean REAL_MODE = Boolean.getBoolean("folkways_ornamentum.realmode");
    private static int orderTick;
    private static Colony colony;
    private static ServerLevel level;
    private static BlockPos anchor;
    private static BlockPos cutterPos;
    private static Blueprint blueprint;

    private OrnamentumSelfTest() {
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server == null || finished) {
            return;
        }
        String spec = System.getProperty(PROPERTY);
        if (spec == null || spec.isBlank()) {
            return;
        }
        if (server.getTickCount() < START_DELAY) {
            return;
        }
        if (!started) {
            started = true;
            try {
                run(server, spec);
            } catch (Throwable error) {
                FolkwaysOrnamentum.LOG.error("[{}] SELFTEST-FAIL", FolkwaysOrnamentum.MODID, error);
                finished = true;
            }
            return;
        }
        if (!ordered) {
            return;
        }
        int elapsed = server.getTickCount() - orderTick;
        if (elapsed >= END_AFTER) {
            logStatus(server);
            if (level != null && anchor != null) {
                int[] progress = progress(level, anchor);
                int[] exact = verifyExact(level, anchor);
                boolean pass = progress[0] > 0 && progress[1] >= progress[0]
                        && progress[3] >= progress[2] && exact[0] == 0;
                FolkwaysOrnamentum.LOG.info(
                        "[{}] SELFTEST-{} wanted={} placed={} doWanted={} doPlaced={} "
                                + "verifyMismatch={} verifyDo={} verifyDoOk={} elapsed={}t",
                        FolkwaysOrnamentum.MODID, pass ? "PASS" : "INCOMPLETE",
                        progress[0], progress[1], progress[2], progress[3],
                        exact[0], exact[1], exact[2], elapsed);
            } else {
                FolkwaysOrnamentum.LOG.info("[{}] SELFTEST-FAIL no-level", FolkwaysOrnamentum.MODID);
            }
            finished = true;
            return;
        }
        if (elapsed % STATUS_EVERY == 0) {
            logStatus(server);
        }
    }

    public static int runFromCommand(net.minecraft.commands.CommandSourceStack source, String spec) {
        MinecraftServer server = source.getServer();
        try {
            run(server, spec);
            return 1;
        } catch (Throwable error) {
            FolkwaysOrnamentum.LOG.error("[{}] SELFTEST-CMD-FAIL", FolkwaysOrnamentum.MODID, error);
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    private static void run(MinecraftServer server, String rawSpec) {
        String spec = rawSpec.trim();
        String name = spec;
        BlockPos fixed = null;
        int at = spec.indexOf('@');
        if (at >= 0) {
            name = spec.substring(0, at).trim();
            String coordinates = spec.substring(at + 1).trim();
            String[] parts = coordinates.split(",");
            if (parts.length == 3) {
                fixed = new BlockPos(
                        Integer.parseInt(parts[0].trim()),
                        Integer.parseInt(parts[1].trim()),
                        Integer.parseInt(parts[2].trim()));
            }
        }
        if (name.isBlank()) {
            name = "ornamentum_test";
        }

        if (name.equals("materials")) {
            MaterialsSelfTest.run(server);
            finished = true;
            return;
        }

        Path file = resolve(name);
        if (file == null) {
            FolkwaysOrnamentum.LOG.error("[{}] SELFTEST-MISSING 找不到结构文件 {}", FolkwaysOrnamentum.MODID, name);
            finished = true;
            return;
        }

        List<Colony> colonies = Colonies.all(server);
        if (colonies.isEmpty()) {
            FolkwaysOrnamentum.LOG.error("[{}] SELFTEST-NO-COLONY", FolkwaysOrnamentum.MODID);
            finished = true;
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayers().isEmpty()
                ? null
                : server.getPlayerList().getPlayers().get(0);
        colony = pickColony(server, colonies, player);
        level = pickLevel(server, colony);
        if (level == null) {
            FolkwaysOrnamentum.LOG.error("[{}] SELFTEST-NO-LEVEL", FolkwaysOrnamentum.MODID);
            finished = true;
            return;
        }
        if (player != null) {
            // 让本地玩家成为测试殖民地成员，才能打开殖民书/蓝图预览页。
            var charter = io.github.izakyl.folkways.core.front.engine.charter.Charters.of(server, colony);
            io.github.izakyl.folkways.core.front.engine.charter.Charters.keep(
                    colony, charter.with(player.getUUID()));
            io.github.izakyl.folkways.core.front.engine.charter.CurrentColony.choose(player, colony);
            FolkwaysOrnamentum.LOG.info("[{}] SELFTEST-CHARTER members={} current={}",
                    FolkwaysOrnamentum.MODID, charter.with(player.getUUID()).members(),
                    io.github.izakyl.folkways.core.front.engine.charter.CurrentColony.of(player)
                            .map(value -> value.id().toString()).orElse("NONE"));
        }
        // 固定成白天，避免测试时居民正好在夜间休息（会表现为 standing=urge、nodes=0）。
        level.setDayTime(1000L);
        level.setWeatherParameters(6000, 0, false, false);
        ensureBuilder(server, colony);
        anchor = fixed != null ? fixed : placementSpot(level, server, colony, player);

        FolkwaysOrnamentum.LOG.info("[{}] SELFTEST-START file={} colony={} level={} anchor={}",
                FolkwaysOrnamentum.MODID, file, colony.id(), level.dimension().location(), anchor);
        if (player != null) {
            // 把玩家镜头摆到工地南侧的高空，并清出一条视线，便于外部截图验证。
            BlockPos camera = anchor.offset(0, 3, -6);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -1; dy <= 2; dy++) {
                    for (int dz = -2; dz <= 1; dz++) {
                        BlockPos clear = camera.offset(dx, dy, dz);
                        if (level.isLoaded(clear)) {
                            level.setBlock(clear, Blocks.AIR.defaultBlockState(), 3);
                        }
                    }
                }
            }
            for (int back = 1; back <= 5; back++) {
                BlockPos clear = anchor.offset(0, 2, -back);
                if (level.isLoaded(clear)) {
                    level.setBlock(clear, Blocks.AIR.defaultBlockState(), 3);
                }
            }
            player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
            player.teleportTo(level,
                    camera.getX() + 0.5, camera.getY() + 0.5, camera.getZ() + 0.5,
                    0.0F, 18.0F);
            player.setNoGravity(true);
            player.setInvulnerable(true);
            FolkwaysOrnamentum.LOG.info("[{}] SELFTEST-CAMERA player={} at={}",
                    FolkwaysOrnamentum.MODID, player.getName().getString(), player.blockPosition());
        }
        CompoundTag tag;
        try (InputStream in = Files.newInputStream(file)) {
            tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        } catch (Throwable error) {
            FolkwaysOrnamentum.LOG.error("[{}] SELFTEST-READ-FAIL {}", FolkwaysOrnamentum.MODID, file, error);
            finished = true;
            return;
        }

        Optional<Blueprint> parsed = StructureNbt.toBlueprint(
                tag,
                server.registryAccess(),
                UUID.randomUUID(),
                name,
                level.getGameTime(),
                StructureNbt.AirMeaning.UNCONSTRAINED,
                200_000
        );
        if (parsed.isEmpty()) {
            FolkwaysOrnamentum.LOG.error("[{}] SELFTEST-PARSE-FAIL {}", FolkwaysOrnamentum.MODID, file);
            finished = true;
            return;
        }
        blueprint = parsed.get();

        long doBlocks = blueprint.blocks().stream()
                .filter(block -> StructureCompat.of(block.state()).isPresent())
                .count();
        long payloads = blueprint.blocks().stream()
                .filter(block -> block.data().present())
                .count();
        FolkwaysOrnamentum.LOG.info("[{}] SELFTEST-BLUEPRINT blocks={} do={} payloads={} size={}",
                FolkwaysOrnamentum.MODID, blueprint.blocks().size(), doBlocks, payloads, blueprint.volume().size());

        try {
            blueprint.compatible();
        } catch (Throwable error) {
            FolkwaysOrnamentum.LOG.error("[{}] SELFTEST-COMPAT-FAIL {}", FolkwaysOrnamentum.MODID, error.toString());
            finished = true;
            return;
        }

        try {
            blueprint = blueprint.confirm(level);
        } catch (Throwable error) {
            FolkwaysOrnamentum.LOG.error("[{}] SELFTEST-CONFIRM-FAIL {}", FolkwaysOrnamentum.MODID, error.toString());
            finished = true;
            return;
        }

        preload(level, anchor);
        checkEntity(level, blueprint);
        if (REAL_MODE) {
            try {
                var bodies = io.github.izakyl.folkways.core.front.engine.colony.Residents.of(colony, server);
                if (!bodies.isEmpty()) {
                    BlockPos near = bodies.get(0).mob().blockPosition().offset(0, 0, 6);
                    anchor = groundNear(level, near);
                    FolkwaysOrnamentum.LOG.info(
                            "[{}] SELFTEST-REAL anchor={} resident={}", FolkwaysOrnamentum.MODID,
                            anchor, bodies.get(0).mob().blockPosition());
                }
            } catch (Throwable error) {
                FolkwaysOrnamentum.LOG.warn("[{}] SELFTEST-REAL-ANCHOR-FAIL {}",
                        FolkwaysOrnamentum.MODID, error.toString());
            }
        }
        clearVolume(level, anchor);
        purgeDoItems(level, colony, blueprint);
        cancelExisting(level, colony);
        if (cutterMode) {
            claimCutter(level, colony, anchor);
        }
        supplyMaterials(level, colony, blueprint);
        if (!REAL_MODE) {
            equipResidents(server, colony, blueprint);
            gatherResidents(server, colony, level, anchor);
        }
        try {
            long stockPlanks = io.github.izakyl.folkways.core.front.engine.colony.ColonyStocks.available(
                    colony, server, ItemSpec.of(net.minecraft.world.item.Items.OAK_PLANKS));
            FolkwaysOrnamentum.LOG.info("[{}] SELFTEST-STOCK planks={}",
                    FolkwaysOrnamentum.MODID, stockPlanks);
            var snapshot = colony.inventory().snapshot();
            for (var store : snapshot.stores().values()) {
                FolkwaysOrnamentum.LOG.info(
                        "[{}] SELFTEST-STORES state={} labor={} slots={} aliases={} stash={}",
                        FolkwaysOrnamentum.MODID, store.state(), store.labor(), store.slots().size(),
                        store.aliases(), store.stash());
            }
        } catch (Throwable error) {
            FolkwaysOrnamentum.LOG.warn("[{}] SELFTEST-STOCK-FAIL {}", FolkwaysOrnamentum.MODID, error.toString());
        }

        try {
            boolean filed = BuildOrders.fileOnce(colony, level, blueprint, WorldPos.of(level, anchor));
            ordered = filed;
            orderTick = server.getTickCount();
            FolkwaysOrnamentum.LOG.info("[{}] SELFTEST-ORDER filed={} order={} anchor={}",
                    FolkwaysOrnamentum.MODID, filed, blueprint.id(), anchor);
        } catch (Throwable error) {
            FolkwaysOrnamentum.LOG.error("[{}] SELFTEST-ORDER-FAIL {}", FolkwaysOrnamentum.MODID, error.toString());
            finished = true;
            return;
        }

        // 独立于居民 AI 的放置验证：门 + 材质方块，直接走原生放置适配器。
        printTest(level, anchor.offset(6, 0, 0));

        if (cutterMode && cutDirect && cutterPos != null) {
            for (BlueprintBlock block : blueprint.blocks()) {
                if (!StructureCompat.of(block.state()).isPresent()) {
                    continue;
                }
                ItemStack stack = DoPayload.stack(block.state(), block.data().tag());
                if (stack.isEmpty()) {
                    continue;
                }
                int submitted = com.empire.folkways.ornamentum.content.OrnamentumContent.submitCut(
                        colony, level, cutterPos, ItemSpec.exact(stack));
                FolkwaysOrnamentum.LOG.info("[{}] SELFTEST-CUT-SUBMIT item={} result={} cutter={}",
                        FolkwaysOrnamentum.MODID, ItemSpec.exact(stack), submitted, cutterPos);
                break;
            }
        }
    }

    // ------------------------------------------------------------------
    // 诊断
    // ------------------------------------------------------------------

    /** 用原生放置适配器直接放一个「门 + 材质」测试件，验证 textureData 与双格门。 */
    private static void printTest(ServerLevel level, BlockPos at) {
        Path file = resolve("ornamentum_doors");
        if (file == null) {
            FolkwaysOrnamentum.LOG.info("[{}] SELFTEST-PRINT-SKIP 找不到 ornamentum_doors.nbt",
                    FolkwaysOrnamentum.MODID);
            return;
        }
        try {
            CompoundTag tag;
            try (InputStream in = Files.newInputStream(file)) {
                tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
            }
            Optional<Blueprint> parsed = StructureNbt.toBlueprint(
                    tag, level.getServer().registryAccess(), UUID.randomUUID(), "ornamentum_doors",
                    level.getGameTime(), StructureNbt.AirMeaning.UNCONSTRAINED, 20_000);
            if (parsed.isEmpty()) {
                FolkwaysOrnamentum.LOG.error("[{}] SELFTEST-PRINT-PARSE-FAIL", FolkwaysOrnamentum.MODID);
                return;
            }
            Blueprint door = parsed.get().confirm(level);
            BlockPos base = groundNear(level, at);
            for (BlueprintBlock block : door.blocks()) {
                BlockPos pos = base.offset(block.offset());
                if (level.isLoaded(pos)) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
            }

            Map<BlockPos, BlockState> states = new LinkedHashMap<>();
            Map<BlockPos, BlockData> data = new LinkedHashMap<>();
            for (BlueprintBlock block : door.blocks()) {
                BlockPos pos = base.offset(block.offset());
                level.getChunk(pos);
                states.put(pos, block.state());
                data.put(pos, block.data());
            }
            Function<BlockPos, BlockState> stateAt = pos -> states.getOrDefault(pos, level.getBlockState(pos));
            Function<BlockPos, BlockData> dataAt = pos -> data.getOrDefault(pos, BlockData.EMPTY);

            for (BlueprintBlock block : door.blocks()) {
                if (block.state().isAir()) {
                    continue;
                }
                BlockPos pos = base.offset(block.offset());
                SchematicPlacement.adapter().launch(stateAt, pos).ifPresent(launch ->
                        SchematicPlacement.adapter().fire(level, launch, stateAt, dataAt, ItemStack.EMPTY));
            }

            int placed = 0;
            int doOk = 0;
            int doorUpper = 0;
            for (BlueprintBlock block : door.blocks()) {
                if (block.state().isAir()) {
                    continue;
                }
                BlockPos pos = base.offset(block.offset());
                BlockState actual = level.getBlockState(pos);
                if (actual.getBlock() == block.state().getBlock()) {
                    placed++;
                    if (StructureCompat.of(block.state()).isPresent()
                            && DoPayload.matches(level.getBlockEntity(pos), block.data().tag())) {
                        doOk++;
                    }
                }
                if (block.state().hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        && block.state().getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
                    BlockPos upper = pos.above();
                    BlockState there = level.getBlockState(upper);
                    if (there.is(block.state().getBlock())
                            && DoPayload.matches(level.getBlockEntity(upper), block.data().tag())) {
                        doorUpper++;
                    }
                }
            }
            FolkwaysOrnamentum.LOG.info(
                    "[{}] SELFTEST-PRINT base={} total={} placed={} doOk={} doorUpper={}",
                    FolkwaysOrnamentum.MODID, base, door.blocks().size(), placed, doOk, doorUpper);
        } catch (Throwable error) {
            FolkwaysOrnamentum.LOG.error("[{}] SELFTEST-PRINT-FAIL", FolkwaysOrnamentum.MODID, error);
        }
    }

    private static void checkEntity(ServerLevel level, Blueprint blueprint) {
        for (BlueprintBlock block : blueprint.blocks()) {
            if (!StructureCompat.of(block.state()).isPresent()) {
                continue;
            }
            try {
                CompoundTag payload = block.data().tag();
                BlockState state = block.state();
                BlockEntity detached = StructureCompat.of(state).orElseThrow()
                        .detached(level.registryAccess(), BlockPos.ZERO, state,
                                new io.github.izakyl.folkways.core.world.api.StructurePayload(payload));
                CompoundTag captured = detached == null ? new CompoundTag()
                        : StructureCompat.of(state).orElseThrow().capture(detached);
                boolean matches = StructureCompat.of(state).orElseThrow()
                        .matches(detached, new io.github.izakyl.folkways.core.world.api.StructurePayload(payload));
                ItemStack stack = DoPayload.stack(state, payload);
                FolkwaysOrnamentum.LOG.info(
                        "[{}] SELFTEST-ENTITY block={} payload={} captured={} matches={} item={} components={}",
                        FolkwaysOrnamentum.MODID,
                        BuiltInRegistries.BLOCK.getKey(state.getBlock()),
                        payload,
                        captured,
                        matches,
                        stack.isEmpty() ? "EMPTY" : BuiltInRegistries.ITEM.getKey(stack.getItem()),
                        stack.isEmpty() ? "{}" : stack.getComponents()
                );
                return;
            } catch (Throwable error) {
                FolkwaysOrnamentum.LOG.error("[{}] SELFTEST-ENTITY-FAIL {}", FolkwaysOrnamentum.MODID, error.toString());
                return;
            }
        }
    }

    private static void preload(ServerLevel level, BlockPos anchor) {
        if (blueprint == null) {
            return;
        }
        int loaded = 0;
        for (BlueprintBlock block : blueprint.blocks()) {
            level.getChunk(anchor.offset(block.offset()));
            loaded++;
        }
        FolkwaysOrnamentum.LOG.info("[{}] SELFTEST-CHUNKS touched={}", FolkwaysOrnamentum.MODID, loaded);
    }

    /** 测试殖民地不一定有建筑工；给居民补一个建筑执照，让工地真的能被接单。 */
    private static void ensureBuilder(MinecraftServer server, Colony colony) {
        try {
            var building = io.github.izakyl.folkways.core.vocation.api.Vocations.required(
                    io.github.izakyl.folkways.vanilla.build.BuildContent.VOCATION);
            var residents = io.github.izakyl.folkways.core.front.engine.colony.Residents.of(colony, server);
            int granted = 0;
            for (var body : residents) {
                boolean had = body.licences().of(building).isPresent();
                body.licences().allow(new io.github.izakyl.folkways.core.resident.api.Licences.Trade(building), true);
                if (!had) {
                    granted++;
                }
                FolkwaysOrnamentum.LOG.info("[{}] SELFTEST-BUILDER resident={} had={} now={}",
                        FolkwaysOrnamentum.MODID, body.id(), had,
                        body.licences().of(building).map(Object::toString).orElse("NONE"));
            }
            FolkwaysOrnamentum.LOG.info("[{}] SELFTEST-BUILDER total={} granted={}",
                    FolkwaysOrnamentum.MODID, residents.size(), granted);
        } catch (Throwable error) {
            FolkwaysOrnamentum.LOG.warn("[{}] SELFTEST-BUILDER-FAIL {}", FolkwaysOrnamentum.MODID, error.toString());
        }
    }

    /** 切割机测试：在工地旁边放一台切割机并用殖民地 API 认领它。 */
    private static void claimCutter(ServerLevel level, Colony colony, BlockPos anchor) {
        try {
            // 旧测试可能留下了多台切割机；先释放全部旧认领，保证自动补料选到新工位。
            for (io.github.izakyl.folkways.core.colony.api.Claim old :
                    colony.claims(com.empire.folkways.ornamentum.content.OrnamentumContent.CUTTER)) {
                colony.release(old.id(), io.github.izakyl.folkways.core.colony.api.ClaimRelease.LET_GO);
            }
            BlockPos pos = anchor.offset(-4, 0, 0);
            level.getChunk(pos);
            var cutter = BuiltInRegistries.BLOCK.getOptional(
                    ResourceLocation.fromNamespaceAndPath("domum_ornamentum", "architectscutter")).orElse(null);
            if (cutter == null) {
                FolkwaysOrnamentum.LOG.warn("[{}] SELFTEST-CUTTER 找不到切割机方块", FolkwaysOrnamentum.MODID);
                return;
            }
            BlockState state = cutter.defaultBlockState().setValue(
                    com.ldtteam.domumornamentum.block.ArchitectsCutterBlock.FACING,
                    net.minecraft.core.Direction.NORTH);
            level.setBlock(pos, state, 3);
            cutterPos = pos;
            var claim = colony.hold(
                    com.empire.folkways.ornamentum.content.OrnamentumContent.CUTTER,
                    new io.github.izakyl.folkways.core.colony.api.Claimed.Point(WorldPos.of(level, pos)));
            FolkwaysOrnamentum.LOG.info("[{}] SELFTEST-CUTTER pos={} claim={}",
                    FolkwaysOrnamentum.MODID, pos, claim.map(value -> value.id().toString()).orElse("NONE"));
        } catch (Throwable error) {
            FolkwaysOrnamentum.LOG.warn("[{}] SELFTEST-CUTTER-FAIL {}", FolkwaysOrnamentum.MODID, error.toString());
        }
    }

    /** 测试殖民地地形经常把居民隔在悬崖/墙后；把居民挪到工地附近，保证路径可达。 */
    private static void gatherResidents(MinecraftServer server, Colony colony, ServerLevel level, BlockPos anchor) {
        try {
            int back = siteMaxZ() + 2;
            BlockPos stand = anchor.offset(0, 0, back);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = back - 1; dz <= back + 3; dz++) {
                    BlockPos floor = anchor.offset(dx, -1, dz);
                    BlockPos head = anchor.offset(dx, 0, dz);
                    if (level.isLoaded(floor)) {
                        level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
                    }
                    if (level.isLoaded(head)) {
                        level.setBlock(head, Blocks.AIR.defaultBlockState(), 3);
                        level.setBlock(head.above(), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
            var bodies = io.github.izakyl.folkways.core.front.engine.colony.Residents.of(colony, server);
            int moved = 0;
            for (var body : bodies) {
                if (body.mob().level() != level) {
                    continue;
                }
                body.mob().teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
                moved++;
            }
            FolkwaysOrnamentum.LOG.info("[{}] SELFTEST-GATHER residents={} at={}",
                    FolkwaysOrnamentum.MODID, moved, stand);
        } catch (Throwable error) {
            FolkwaysOrnamentum.LOG.warn("[{}] SELFTEST-GATHER-FAIL {}", FolkwaysOrnamentum.MODID, error.toString());
        }
    }

    private static int siteMaxZ() {
        if (blueprint == null) {
            return 2;
        }
        int z = 0;
        for (BlueprintBlock block : blueprint.blocks()) {
            z = Math.max(z, block.offset().getZ());
        }
        return z;
    }

    /** 清掉测试蓝图的体积，保证每次测试都是真的从零建造，而不是读到上次的残留。 */
    private static void clearVolume(ServerLevel level, BlockPos anchor) {
        if (blueprint == null) {
            return;
        }
        int cleared = 0;
        for (BlueprintBlock block : blueprint.blocks()) {
            BlockPos pos = anchor.offset(block.offset());
            if (!level.isLoaded(pos)) {
                continue;
            }
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            cleared++;
        }
        FolkwaysOrnamentum.LOG.info("[{}] SELFTEST-CLEAR cells={}", FolkwaysOrnamentum.MODID, cleared);
    }

    /** 清掉仓库/居民身上遗留的精确 Do 物品，避免旧测试的存货掩盖切割机生产。 */
    private static void purgeDoItems(ServerLevel level, Colony colony, Blueprint blueprint) {
        List<ItemSpec> specs = new ArrayList<>();
        for (BlueprintBlock block : blueprint.blocks()) {
            if (!StructureCompat.of(block.state()).isPresent()) {
                continue;
            }
            ItemStack stack = DoPayload.stack(block.state(), block.data().tag());
            if (!stack.isEmpty()) {
                specs.add(ItemSpec.exact(stack));
            }
        }
        if (specs.isEmpty()) {
            return;
        }
        int purged = 0;
        for (WorldPos at : colony.storesIn(level.dimension())) {
            Optional<Container> found = colony.store(level, at);
            if (found.isEmpty()) {
                continue;
            }
            Container container = found.get();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                for (ItemSpec spec : specs) {
                    if (Goods.matches(stack, spec)) {
                        container.setItem(slot, ItemStack.EMPTY);
                        container.setChanged();
                        purged += stack.getCount();
                        break;
                    }
                }
            }
        }
        try {
            for (var body : io.github.izakyl.folkways.core.front.engine.colony.Residents.of(colony, level.getServer())) {
                Container pack = body.pack();
                for (int slot = 0; slot < pack.getContainerSize(); slot++) {
                    ItemStack stack = pack.getItem(slot);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    for (ItemSpec spec : specs) {
                        if (Goods.matches(stack, spec)) {
                            pack.setItem(slot, ItemStack.EMPTY);
                            pack.setChanged();
                            purged += stack.getCount();
                            break;
                        }
                    }
                }
            }
        } catch (Throwable error) {
            FolkwaysOrnamentum.LOG.warn("[{}] SELFTEST-PURGE-PACK-FAIL {}", FolkwaysOrnamentum.MODID, error.toString());
        }
        FolkwaysOrnamentum.LOG.info("[{}] SELFTEST-PURGE doItems={} storedCount={}",
                FolkwaysOrnamentum.MODID, specs.size(), purged);
    }

    /** 测试用：把工地需要的材料直接塞进居民背包，绕开殖民地仓库观察延迟。 */
    private static void equipResidents(MinecraftServer server, Colony colony, Blueprint blueprint) {
        try {
            var bodies = io.github.izakyl.folkways.core.front.engine.colony.Residents.of(colony, server);
            int equipped = 0;
            int planks = 0;
            for (var body : bodies) {
                for (int slot = 0; slot < body.pack().getContainerSize(); slot++) {
                    body.pack().setItem(slot, ItemStack.EMPTY);
                }
                body.pack().setChanged();
                for (BlueprintBlock block : blueprint.blocks()) {
                    if (block.state().isAir()) {
                        continue;
                    }
                    ItemStack stack;
                    if (StructureCompat.of(block.state()).isPresent()) {
                        if (cutterMode) {
                            // 给切割机用的组件材料（panel 的默认组件是 oak_planks）。
                            body.pack().insert(new ItemStack(net.minecraft.world.item.Items.OAK_PLANKS, 32));
                            equipped++;
                            continue;
                        }
                        stack = DoPayload.stack(block.state(), block.data().tag());
                    } else {
                        stack = new ItemStack(block.state().getBlock().asItem());
                    }
                    if (stack.isEmpty()) {
                        continue;
                    }
                    body.pack().insert(stack);
                    equipped++;
                }
                body.pack().insert(new ItemStack(net.minecraft.world.item.Items.SCAFFOLDING, 64));
                body.pack().insert(new ItemStack(net.minecraft.world.item.Items.BREAD, 64));
                body.pack().insert(new ItemStack(net.minecraft.world.item.Items.COOKED_BEEF, 64));
                body.pack().insert(new ItemStack(net.minecraft.world.item.Items.GOLDEN_CARROT, 32));
                body.pack().insert(new ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE, 1));
                planks += body.pack().countMatching(stack -> stack.is(net.minecraft.world.item.Items.OAK_PLANKS));
            }
            FolkwaysOrnamentum.LOG.info("[{}] SELFTEST-EQUIP residents={} stacks={} planks={}",
                    FolkwaysOrnamentum.MODID, bodies.size(), equipped, planks);
        } catch (Throwable error) {
            FolkwaysOrnamentum.LOG.warn("[{}] SELFTEST-EQUIP-FAIL {}", FolkwaysOrnamentum.MODID, error.toString());
        }
    }

    /** 测试前清掉这个殖民地里遗留的建造单，避免工人被旧工单占满。 */
    private static void cancelExisting(ServerLevel level, Colony colony) {
        Optional<Facing> facing = Facing.of(colony, io.github.izakyl.folkways.vanilla.build.BuildContent.ID);
        if (facing.isEmpty()) {
            return;
        }
        ColonyView view = colony.view(level);
        int cancelled = 0;
        for (var placard : facing.get().placards(view)) {
            try {
                facing.get().act(
                        io.github.izakyl.folkways.vanilla.build.BuildContent.ID,
                        "cancel:" + placard.id(),
                        Optional.empty(),
                        view
                );
                cancelled++;
            } catch (Throwable error) {
                FolkwaysOrnamentum.LOG.warn("[{}] SELFTEST-CANCEL-FAIL {}", FolkwaysOrnamentum.MODID, error.toString());
            }
        }
        FolkwaysOrnamentum.LOG.info("[{}] SELFTEST-CANCEL count={}", FolkwaysOrnamentum.MODID, cancelled);
    }

    private static void supplyMaterials(ServerLevel level, Colony colony, Blueprint blueprint) {
        List<WorldPos> stores = new ArrayList<>(colony.storesIn(level.dimension()));
        boolean usable = false;
        for (WorldPos at : stores) {
            if (colony.store(level, at).isPresent()) {
                usable = true;
                break;
            }
        }
        if (!usable && REAL_MODE) {
            // 真实模式：在殖民地 claim 里建一个小型普通仓库（4 个箱子），不传送居民。
            List<WorldPos> warehouse = new ArrayList<>();
            for (var claim : colony.claims()) {
                for (WorldPos cell : claim.what().cells()) {
                    if (warehouse.size() >= 4) {
                        break;
                    }
                    BlockPos candidate = cell.block(level);
                    double distance = Math.sqrt(anchor.distSqr(candidate));
                    if (distance >= 3.0 && distance <= 10.0 && level.isLoaded(candidate)
                            && level.getBlockState(candidate).canBeReplaced()) {
                        level.setBlock(candidate, Blocks.CHEST.defaultBlockState(), 3);
                        warehouse.add(WorldPos.of(level, candidate));
                    }
                }
                if (warehouse.size() >= 4) {
                    break;
                }
            }
            if (warehouse.isEmpty()) {
                for (int index = 0; index < 4; index++) {
                    BlockPos candidate = anchor.offset(-3, 0, -4 - index * 2);
                    if (!level.isLoaded(candidate)) {
                        continue;
                    }
                    level.setBlock(candidate.above(), Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(candidate, Blocks.CHEST.defaultBlockState(), 3);
                    warehouse.add(WorldPos.of(level, candidate));
                }
            }
            if (!warehouse.isEmpty()) {
                colony.stores(
                        ResourceLocation.fromNamespaceAndPath(FolkwaysOrnamentum.MODID, "real_warehouse"),
                        level.dimension(), warehouse);
                colony.inventory().ensureObserved();
                stores = new ArrayList<>(colony.storesIn(level.dimension()));
                usable = true;
                FolkwaysOrnamentum.LOG.info("[{}] SELFTEST-REAL-WAREHOUSE chests={} stores={}",
                        FolkwaysOrnamentum.MODID, warehouse.size(), stores.size());
            }
        }
        if (!usable && !REAL_MODE) {
            BlockPos storePos = null;
            // 优先把箱子放进殖民地自己的 claim，这样 InventoryIndex 才会把它当作 labor 仓库。
            for (var claim : colony.claims()) {
                for (WorldPos cell : claim.what().cells()) {
                    BlockPos candidate = cell.block(level);
                    if (level.isLoaded(candidate) && level.getBlockState(candidate).canBeReplaced()) {
                        storePos = candidate;
                        break;
                    }
                }
                if (storePos != null) {
                    break;
                }
            }
            if (storePos == null) {
                storePos = anchor.offset(-2, 0, 0);
            }
            level.getChunk(storePos);
            if (level.getBlockState(storePos).canBeReplaced()) {
                level.setBlock(storePos, Blocks.CHEST.defaultBlockState(), 3);
                WorldPos storeAt = WorldPos.of(level, storePos);
                colony.stores(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                FolkwaysOrnamentum.MODID, "selftest_store"),
                        level.dimension(),
                        List.of(storeAt)
                );
                colony.inventory().ensureObserved();
                stores = new ArrayList<>(colony.storesIn(level.dimension()));
                FolkwaysOrnamentum.LOG.info("[{}] SELFTEST-STORE 在 {} 放了测试箱子并注册为殖民地仓库（现有 {} 个）",
                        FolkwaysOrnamentum.MODID, storePos, stores.size());
            }
        }
        if (!REAL_MODE) {
        // 大蓝图需要几十个不同精确 Do 物品，单箱 27 格不够；并排放一排测试仓库。
        List<WorldPos> bulk = new ArrayList<>();
        for (var claim : colony.claims()) {
            for (WorldPos cell : claim.what().cells()) {
                if (bulk.size() >= 8) {
                    break;
                }
                BlockPos bulkPos = cell.block(level);
                if (!level.isLoaded(bulkPos) || anchor.distSqr(bulkPos) <= 16) {
                    continue;
                }
                if (Math.abs(bulkPos.getY() - anchor.getY()) > 3) {
                    continue;
                }
                if (level.getBlockState(bulkPos).canBeReplaced()) {
                    level.setBlock(bulkPos, Blocks.CHEST.defaultBlockState(), 3);
                    bulk.add(WorldPos.of(level, bulkPos));
                }
            }
            if (bulk.size() >= 8) {
                break;
            }
        }
        if (bulk.isEmpty()) {
            // 没有可用的 claim 格子时，清出工地南侧一条空地并放测试仓库。
            for (int index = 0; index < 8; index++) {
                BlockPos bulkPos = anchor.offset(-3, 0, -4 - index * 2);
                if (!level.isLoaded(bulkPos)) {
                    continue;
                }
                level.setBlock(bulkPos.above(), Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(bulkPos, Blocks.CHEST.defaultBlockState(), 3);
                bulk.add(WorldPos.of(level, bulkPos));
            }
        }
        if (!bulk.isEmpty()) {
            List<WorldPos> all = new ArrayList<>(colony.storesIn(level.dimension()));
            all.addAll(bulk);
            colony.stores(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                            FolkwaysOrnamentum.MODID, "selftest_store"),
                    level.dimension(),
                    all);
            colony.inventory().ensureObserved();
            stores = new ArrayList<>(colony.storesIn(level.dimension()));
            FolkwaysOrnamentum.LOG.info("[{}] SELFTEST-BULK-STORES added={} total={}",
                    FolkwaysOrnamentum.MODID, bulk.size(), stores.size());
        }
        }
        Map<ItemSpec, Long> needs = new LinkedHashMap<>();
        for (BlueprintBlock block : blueprint.blocks()) {
            BlockState state = block.state();
            if (state.isAir() || !state.getFluidState().isEmpty()) {
                continue;
            }
            if (StructureCompat.of(state).isPresent()) {
                if (cutterMode) {
                    // 切割机测试：只给原材料，让原生工作站去生产精确的 Do 物品。
                    needs.merge(ItemSpec.of(net.minecraft.world.item.Items.OAK_PLANKS), 8L, Long::sum);
                } else {
                    ItemStack stack = DoPayload.stack(state, block.data().tag());
                    if (!stack.isEmpty()) {
                        needs.merge(ItemSpec.exact(stack), 1L, Long::sum);
                    }
                }
            } else {
                var item = state.getBlock().asItem();
                if (item != Blocks.AIR.asItem()) {
                    needs.merge(ItemSpec.of(item), 1L, Long::sum);
                }
            }
        }
        // 脚手架/水桶之类的施工消耗。
        needs.merge(ItemSpec.of(net.minecraft.world.item.Items.SCAFFOLDING), 64L, Long::sum);
        needs.merge(ItemSpec.of(net.minecraft.world.item.Items.WATER_BUCKET), 4L, Long::sum);
        needs.merge(ItemSpec.of(net.minecraft.world.item.Items.BUCKET), 4L, Long::sum);
        // 测试殖民地常常缺粮；居民饿着就只处理 urge，不会去工地。
        needs.merge(ItemSpec.of(net.minecraft.world.item.Items.BREAD), 64L, Long::sum);
        needs.merge(ItemSpec.of(net.minecraft.world.item.Items.COOKED_BEEF), 64L, Long::sum);
        needs.merge(ItemSpec.of(net.minecraft.world.item.Items.GOLDEN_CARROT), 32L, Long::sum);
        needs.merge(ItemSpec.of(net.minecraft.world.item.Items.APPLE), 32L, Long::sum);

        int inserted = 0;
        int storesUsed = 0;
        for (WorldPos at : stores) {
            Optional<Container> found = colony.store(level, at);
            if (found.isEmpty()) {
                continue;
            }
            Container container = found.get();
            storesUsed++;
            for (Map.Entry<ItemSpec, Long> entry : needs.entrySet()) {
                long left = entry.getValue();
                while (left > 0) {
                    ItemStack stack = Goods.display(entry.getKey());
                    if (stack.isEmpty()) {
                        break;
                    }
                    int count = (int) Math.min(left, stack.getMaxStackSize());
                    stack.setCount(count);
                    if (!insert(container, stack)) {
                        break;
                    }
                    left -= count;
                    inserted += count;
                }
            }
        }
        FolkwaysOrnamentum.LOG.info("[{}] SELFTEST-SUPPLY kinds={} stores={} used={} inserted={}",
                FolkwaysOrnamentum.MODID, needs.size(), stores.size(), storesUsed, inserted);
    }

    private static boolean insert(Container container, ItemStack stack) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (!container.getItem(slot).isEmpty()) {
                continue;
            }
            if (!container.canPlaceItem(slot, stack)) {
                continue;
            }
            container.setItem(slot, stack);
            container.setChanged();
            return true;
        }
        return false;
    }

    public static void logStatus(MinecraftServer server) {
        if (server == null) {
            return;
        }
        List<Colony> colonies = Colonies.all(server);
        if (colonies.isEmpty()) {
            FolkwaysOrnamentum.LOG.info("[{}] SELFTEST-STATUS no-colony", FolkwaysOrnamentum.MODID);
            return;
        }
        Colony selected = colony == null ? colonies.get(0) : colony;
        ServerLevel selectedLevel = level == null ? pickLevel(server, selected) : level;
        if (selectedLevel == null) {
            return;
        }
        BlockPos near = anchor == null ? BlockPos.ZERO : anchor;
        int ghosts = 0;
        int doGhosts = 0;
        int placards = 0;
        try {
            Optional<Facing> facing = Facing.of(selected, io.github.izakyl.folkways.vanilla.build.BuildContent.ID);
            if (facing.isPresent()) {
                ColonyView view = selected.view(selectedLevel);
                List<Ghost> list = facing.get().ghosts(view, 4096, near);
                ghosts = list.size();
                for (Ghost ghost : list) {
                    if (StructureCompat.of(ghost.state()).isPresent()) {
                        doGhosts++;
                    }
                }
                placards = facing.get().placards(view).size();
            }
        } catch (Throwable error) {
            FolkwaysOrnamentum.LOG.warn("[{}] SELFTEST-STATUS-FAIL {}", FolkwaysOrnamentum.MODID, error.toString());
        }

        int[] progress = progress(selectedLevel, near);
        FolkwaysOrnamentum.LOG.info(
                "[{}] SELFTEST-STATUS colonies={} ghosts={} doGhosts={} placards={} wanted={} placed={} doWanted={} doPlaced={}",
                FolkwaysOrnamentum.MODID, colonies.size(), ghosts, doGhosts, placards,
                progress[0], progress[1], progress[2], progress[3]);
    }

    /** {wanted, placed, doWanted, doPlaced} */
    private static int[] progress(ServerLevel level, BlockPos near) {
        int wanted = 0;
        int placed = 0;
        int doWanted = 0;
        int doPlaced = 0;
        if (blueprint == null) {
            return new int[]{0, 0, 0, 0};
        }
        for (BlueprintBlock block : blueprint.blocks()) {
            BlockState goal = block.state();
            if (goal.isAir()) {
                continue;
            }
            wanted++;
            boolean textured = StructureCompat.of(goal).isPresent();
            if (textured) {
                doWanted++;
            }
            BlockPos at = near.offset(block.offset());
            if (!level.isLoaded(at)) {
                continue;
            }
            BlockState actual = level.getBlockState(at);
            if (actual.getBlock() != goal.getBlock()) {
                continue;
            }
            placed++;
            if (textured) {
                BlockEntity entity = level.getBlockEntity(at);
                if (DoPayload.matches(entity, block.data().tag())) {
                    doPlaced++;
                }
            }
        }
        return new int[]{wanted, placed, doWanted, doPlaced};
    }

    /** 逐格复核：完整 BlockState 相等，Do 方块还要复核方块实体材质数据。 */
    private static int[] verifyExact(ServerLevel level, BlockPos near) {
        if (blueprint == null) {
            return new int[]{0, 0, 0};
        }
        int mismatch = 0;
        int doWanted = 0;
        int doOk = 0;
        int reported = 0;
        for (BlueprintBlock block : blueprint.blocks()) {
            BlockState goal = block.state();
            if (goal.isAir()) {
                continue;
            }
            BlockPos at = near.offset(block.offset());
            if (!level.isLoaded(at)) {
                mismatch++;
                continue;
            }
            BlockState actual = level.getBlockState(at);
            boolean bad = !actual.equals(goal);
            boolean textured = StructureCompat.of(goal).isPresent();
            if (textured) {
                doWanted++;
                BlockEntity entity = level.getBlockEntity(at);
                if (entity == null || !DoPayload.matches(entity, block.data().tag())) {
                    bad = true;
                } else {
                    doOk++;
                }
            }
            if (bad) {
                mismatch++;
                if (reported++ < 5) {
                    FolkwaysOrnamentum.LOG.warn(
                            "[{}] SELFTEST-MISMATCH at={} expected={} actual={} do={}",
                            FolkwaysOrnamentum.MODID, at, goal, actual, textured);
                }
            }
        }
        return new int[]{mismatch, doWanted, doOk};
    }

    /** 在目标 x/z 找到地面，把蓝图放在地面上，避免从空中起塔。 */
    private static BlockPos groundNear(ServerLevel level, BlockPos near) {
        level.getChunk(near);
        for (int dy = 2; dy >= -32; dy--) {
            BlockPos candidate = near.offset(0, dy, 0);
            if (!level.isLoaded(candidate)) {
                continue;
            }
            BlockState state = level.getBlockState(candidate);
            if (!state.isAir() && state.isSolid()) {
                return candidate.above();
            }
        }
        return near;
    }

    /** 优先贴着殖民地自己的领地放测试建筑，否则工人可能根本走不过去。 */
    private static BlockPos placementSpot(ServerLevel level, MinecraftServer server, Colony colony,
                                          ServerPlayer player) {
        BlockPos claim = nearestClaim(server, colony, player);
        BlockPos center = claim;
        if (player != null && (claim == null || claim.distSqr(player.blockPosition()) <= 64 * 64)) {
            center = player.blockPosition();
        }
        center = center == null ? new BlockPos(0, 80, 0) : center;

        for (int radius = 0; radius <= 12; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    BlockPos base = groundNear(level, center.offset(dx, 0, dz));
                    if (!level.isLoaded(base)) {
                        continue;
                    }
                    BlockState below = level.getBlockState(base.below());
                    if (!below.isSolid()) {
                        continue;
                    }
                    boolean clear = true;
                    for (int up = 0; up < 3; up++) {
                        if (!level.getBlockState(base.above(up)).isAir()) {
                            clear = false;
                            break;
                        }
                    }
                    if (clear) {
                        return base;
                    }
                }
            }
        }
        return groundNear(level, center.offset(8, 0, 0));
    }

    private static BlockPos nearestClaim(MinecraftServer server, Colony colony, ServerPlayer player) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Claim claim : colony.claims()) {
            for (WorldPos cell : claim.what().cells()) {
                Optional<ServerLevel> cellLevel = cell.level(server);
                if (cellLevel.isEmpty()) {
                    continue;
                }
                double distance = player == null ? 0.0 : cell.cell().distSqr(player.blockPosition());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = cell.cell();
                }
            }
        }
        return best;
    }

    private static Colony pickColony(MinecraftServer server, List<Colony> colonies, ServerPlayer player) {
        if (player == null) {
            return colonies.get(0);
        }
        Colony best = colonies.get(0);
        int bestResidents = -1;
        double bestDistance = Double.MAX_VALUE;
        for (Colony candidate : colonies) {
            int residents = candidate.residents().size();
            double closest = Double.MAX_VALUE;
            for (Claim claim : candidate.claims()) {
                for (WorldPos cell : claim.what().cells()) {
                    Optional<ServerLevel> cellLevel = cell.level(server);
                    if (cellLevel.isEmpty()
                            || !cellLevel.get().dimension().equals(player.level().dimension())) {
                        continue;
                    }
                    closest = Math.min(closest, cell.cell().distSqr(player.blockPosition()));
                }
            }
            FolkwaysOrnamentum.LOG.info(
                    "[{}] SELFTEST-COLONY candidate={} residents={} distance={}",
                    FolkwaysOrnamentum.MODID, candidate.id(), residents,
                    closest == Double.MAX_VALUE ? "n/a" : String.format(Locale.ROOT, "%.1f", Math.sqrt(closest)));
            if (residents > bestResidents || (residents == bestResidents && closest < bestDistance)) {
                bestResidents = residents;
                bestDistance = closest;
                best = candidate;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // 文件 / 世界
    // ------------------------------------------------------------------

    private static Path resolve(String name) {
        Path direct = Path.of(name);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        String[] candidates = {
                "config/" + FolkwaysOrnamentum.MODID + "/test/" + name + ".nbt",
                "config/" + FolkwaysOrnamentum.MODID + "/test/" + name,
                "folkways/blueprint/" + name + ".nbt",
                "folkways/blueprint/" + name,
                "config/" + FolkwaysOrnamentum.MODID + "/blueprints/" + name + ".nbt"
        };
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        // 允许直接给不带 .nbt 的绝对路径
        Path withSuffix = Path.of(name + ".nbt");
        return Files.isRegularFile(withSuffix) ? withSuffix : null;
    }

    private static ServerLevel pickLevel(MinecraftServer server, Colony colony) {
        List<ColonyView> views = colony.views(server);
        for (ColonyView view : views) {
            if (view.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
                return view.level();
            }
        }
        return views.isEmpty() ? null : views.get(0).level();
    }

    @SuppressWarnings("unused")
    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unused")
    private static double distance(BlockPos one, BlockPos other) {
        return Math.sqrt(one.distSqr(other));
    }
}
