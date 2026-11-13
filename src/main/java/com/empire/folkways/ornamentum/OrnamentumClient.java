package com.empire.folkways.ornamentum;

import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlock;
import com.ldtteam.domumornamentum.client.model.data.MaterialTextureData;
import com.ldtteam.domumornamentum.client.model.properties.ModProperties;
import io.github.izakyl.folkways.core.front.api.Ghost;
import io.github.izakyl.folkways.core.front.api.StructureModels;
import io.github.izakyl.folkways.core.world.api.StructurePayload;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 客户端：让 Folkways 的幽灵（工地预览）能用 Do 的材质模型渲染。
 *
 * <p>Folkways 的 GhostRenderer 会调用 {@code tesselateBlock(..., ModelData.EMPTY, renderType)}，
 * 所以这里返回的 BakedModel 必须自己把 {@code MATERIAL_TEXTURE_PROPERTY} 注入进去。</p>
 */
public final class OrnamentumClient {
    private static final AtomicInteger LOGGED = new AtomicInteger();

    private OrnamentumClient() {
    }

    public static void install(IEventBus modBus) {
        modBus.addListener(OrnamentumClient::clientSetup);
        if (System.getProperty("folkways_ornamentum.clienttest") != null) {
            NeoForge.EVENT_BUS.addListener(OrnamentumClient::clientTick);
        }
    }

    private static int clientTicks;
    private static boolean clientTested;

    private static void clientTick(ClientTickEvent.Post event) {
        if (clientTested) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        if (++clientTicks < 200) {
            return;
        }
        clientTested = true;
        try {
            Block panel = BuiltInRegistries.BLOCK.getOptional(
                    ResourceLocation.fromNamespaceAndPath(FolkwaysOrnamentum.NAMESPACE, "panel")).orElse(null);
            if (panel == null) {
                FolkwaysOrnamentum.LOG.error("[{}] SELFTEST-CLIENT-MODEL 找不到 panel", FolkwaysOrnamentum.MODID);
                return;
            }
            BlockState state = panel.defaultBlockState();
            CompoundTag payload = new CompoundTag();
            CompoundTag texture = new CompoundTag();
            texture.putString("minecraft:block/oak_planks", "minecraft:oak_planks");
            payload.put("textureData", texture);
            CompoundTag variant = new CompoundTag();
            variant.putString("type", "full");
            payload.put("block_state", variant);

            Ghost ghost = new Ghost(minecraft.player.blockPosition(), state, new StructurePayload(payload));
            Map<RenderType, BakedModel> built = models(minecraft.level, ghost);
            int quads = 0;
            for (Map.Entry<RenderType, BakedModel> entry : built.entrySet()) {
                quads += entry.getValue()
                        .getQuads(state, null, RandomSource.create(7L), ModelData.EMPTY, entry.getKey())
                        .size();
            }
            boolean registered = StructureModels.of(minecraft.level, ghost).isPresent();
            FolkwaysOrnamentum.LOG.info(
                    "[{}] SELFTEST-CLIENT-MODEL registered={} types={} quads={} payload={}",
                    FolkwaysOrnamentum.MODID, registered, built.keySet(), quads, payload);
        } catch (Throwable error) {
            FolkwaysOrnamentum.LOG.error("[{}] SELFTEST-CLIENT-MODEL-FAIL", FolkwaysOrnamentum.MODID, error);
        }
    }

    private static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(OrnamentumClient::registerModels);
    }

    private static void registerModels() {
        int count = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (id == null || !id.getNamespace().equals(FolkwaysOrnamentum.NAMESPACE)) {
                continue;
            }
            if (block instanceof IMateriallyTexturedBlock) {
                StructureModels.register(block, OrnamentumClient::models);
                count++;
            }
        }
        FolkwaysOrnamentum.LOG.info("[{}] 已为 {} 个 Do 方块注册幽灵模型",
                FolkwaysOrnamentum.MODID, count);
    }

    private static Map<RenderType, BakedModel> models(BlockAndTintGetter view, Ghost ghost) {
        BlockState state = ghost.state();
        CompoundTag payload = ghost.payload().tag();
        MaterialTextureData data = DoPayload.decode(payload.getCompound(DoPayload.TEXTURE));
        if (data.isEmpty()) {
            CompoundTag defaults = DoPayload.textureFor(state, payload);
            data = DoPayload.decode(defaults);
        }

        BakedModel base = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        ModelData modelData = ModelData.builder()
                .with(ModProperties.MATERIAL_TEXTURE_PROPERTY, data)
                .build();

        GhostModel model = new GhostModel(base, modelData);
        Map<RenderType, BakedModel> out = new LinkedHashMap<>();
        for (RenderType type : base.getRenderTypes(state, RandomSource.create(42L), modelData).asList()) {
            out.put(type, model);
        }
        if (out.isEmpty()) {
            out.put(RenderType.cutout(), model);
        }

        if (LOGGED.getAndIncrement() < 8) {
            FolkwaysOrnamentum.LOG.info(
                    "[{}] 幽灵模型：{} 材质={} 渲染层={}（位置 {}）",
                    FolkwaysOrnamentum.MODID,
                    BuiltInRegistries.BLOCK.getKey(state.getBlock()),
                    data.getTexturedComponents(),
                    out.keySet(),
                    ghost.pos()
            );
        }
        return out;
    }

    /**
     * 把固定的 MaterialTextureData 注入所有 getQuads / getRenderTypes 调用。
     * Folkways 的 GhostRenderer 传 ModelData.EMPTY，所以忽略外部 data。
     */
    private record GhostModel(BakedModel base, ModelData injected) implements BakedModel {
        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
            return base.getQuads(state, side, random, injected, RenderType.solid());
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random,
                                        ModelData data, RenderType type) {
            return base.getQuads(state, side, random, injected, type == null ? RenderType.solid() : type);
        }

        @Override
        public boolean useAmbientOcclusion() {
            return base.useAmbientOcclusion();
        }

        @Override
        public boolean isGui3d() {
            return base.isGui3d();
        }

        @Override
        public boolean usesBlockLight() {
            return base.usesBlockLight();
        }

        @Override
        public boolean isCustomRenderer() {
            return base.isCustomRenderer();
        }

        @Override
        public TextureAtlasSprite getParticleIcon() {
            return base.getParticleIcon(injected);
        }

        @Override
        public TextureAtlasSprite getParticleIcon(ModelData data) {
            return base.getParticleIcon(injected);
        }

        @Override
        public ItemTransforms getTransforms() {
            return base.getTransforms();
        }

        @Override
        public ItemOverrides getOverrides() {
            return base.getOverrides();
        }

        @Override
        public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) {
            return base.getRenderTypes(state, random, injected);
        }

        @Override
        public List<RenderType> getRenderTypes(ItemStack stack, boolean fabulous) {
            return base.getRenderTypes(stack, fabulous);
        }
    }
}
