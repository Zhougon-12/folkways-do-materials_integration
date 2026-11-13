package com.empire.folkways.ornamentum;

import com.empire.folkways.ornamentum.compat.MaterialsCompat;
import com.empire.folkways.ornamentum.content.OrnamentumContent;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Folkways × Domum Ornamentum 的原生接入。
 *
 * <p>本模组做两件事：</p>
 * <ol>
 *   <li>为所有 Do 的「材质方块」注册 Folkways 的 {@code StructureCompat.Handler}，
 *       让蓝图 / 工地 / 幽灵携带精确的 {@code textureData} 与变体属性；</li>
 *   <li>把建筑师切割机接入殖民地的工作流（后续内容的入口）。</li>
 * </ol>
 */
@Mod(FolkwaysOrnamentum.MODID)
public final class FolkwaysOrnamentum {
    public static final String MODID = "folkways_ornamentum";
    public static final String NAMESPACE = "domum_ornamentum";
    public static final Logger LOG = LogUtils.getLogger();

    public static boolean domumPresent() {
        return ModList.get().isLoaded(NAMESPACE);
    }

    public FolkwaysOrnamentum(IEventBus modBus) {
        if (!domumPresent()) {
            LOG.warn("[{}] 未检测到 Domum Ornamentum，模组进入空闲状态", MODID);
            return;
        }

        modBus.addListener(this::commonSetup);
        modBus.addListener(OrnamentumContent::declare);
        modBus.addListener(OrnamentumContent::relate);
        modBus.addListener(OrnamentumContent::enroll);
        NeoForge.EVENT_BUS.addListener(OrnamentumSelfTest::onServerTick);
        if (ModList.get().isLoaded("materials_integration")) {
            NeoForge.EVENT_BUS.addListener(com.empire.folkways.ornamentum.compat.MaterialsSelfTest::onServerTick);
        }
        if (ModList.get().isLoaded("materials_integration")) {
            NeoForge.EVENT_BUS.addListener(MaterialsCompat::onDrops);
        }
        NeoForge.EVENT_BUS.addListener(OrnamentumCommand::onRegisterCommands);

        if (FMLEnvironment.dist.isClient()) {
            OrnamentumClient.install(modBus);
        }

        LOG.info("[{}] 已初始化（Do 在场）", MODID);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // 方块注册表在 common setup 时已完整；切回主线程注册，避免并行事件线程竞争。
        event.enqueueWork(OrnamentumHandlers::install);
    }
}
