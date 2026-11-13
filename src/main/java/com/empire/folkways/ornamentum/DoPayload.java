package com.empire.folkways.ornamentum;

import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlock;
import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlockComponent;
import com.ldtteam.domumornamentum.client.model.data.MaterialTextureData;
import com.ldtteam.domumornamentum.entity.block.IMateriallyTexturedBlockEntity;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Do 方块的「可移植数据」：{@code textureData} + 物品变体属性（目前只有 type）。
 *
 * <p>这套数据就是蓝图里 {@code blocks[].nbt} 的内容，也是 StructureCompat payload 的内容。
 * 它必须满足两件事：</p>
 * <ul>
 *   <li>能被 Do 的 {@code MateriallyTexturedBlockEntity.loadAdditional} 读取；</li>
 *   <li>能构造出和生存掉落完全一致的 {@code ItemStack}（ItemSpec.exact 需要全量组件相等）。</li>
 * </ul>
 */
public final class DoPayload {
    public static final String TEXTURE = "textureData";
    public static final String VARIANT = "block_state";
    public static final String TYPE = "type";

    private DoPayload() {
    }

    // ------------------------------------------------------------------
    // 采集 / 解码
    // ------------------------------------------------------------------

    /** 从实际方块实体采集 payload。 */
    public static CompoundTag capture(BlockEntity entity) {
        CompoundTag tag = new CompoundTag();
        if (entity instanceof IMateriallyTexturedBlockEntity textured) {
            MaterialTextureData data = textured.getTextureData();
            CompoundTag encoded = encode(data);
            if (!encoded.isEmpty()) {
                tag.put(TEXTURE, encoded);
            }
        }
        CompoundTag variant = variantOf(entity.getBlockState());
        if (!variant.isEmpty()) {
            tag.put(VARIANT, variant);
        }
        return tag;
    }

    public static CompoundTag encode(MaterialTextureData data) {
        if (data == null || data.isEmpty()) {
            return new CompoundTag();
        }
        Tag tag = MaterialTextureData.CODEC.encodeStart(NbtOps.INSTANCE, data)
                .result().orElse(null);
        return tag instanceof CompoundTag compound ? compound.copy() : new CompoundTag();
    }

    public static MaterialTextureData decode(CompoundTag texture) {
        if (texture == null || texture.isEmpty()) {
            return MaterialTextureData.EMPTY;
        }
        return MaterialTextureData.CODEC.parse(NbtOps.INSTANCE, texture)
                .result().orElse(MaterialTextureData.EMPTY);
    }

    /** payload 里的材质映射（已过滤掉不存在的方块）。 */
    public static Map<ResourceLocation, Block> textureMap(CompoundTag payload) {
        Map<ResourceLocation, Block> map = new LinkedHashMap<>();
        if (payload == null) {
            return map;
        }
        CompoundTag texture = payload.getCompound(TEXTURE);
        for (String key : texture.getAllKeys()) {
            ResourceLocation component = ResourceLocation.tryParse(key);
            ResourceLocation blockId = ResourceLocation.tryParse(texture.getString(key));
            if (component == null || blockId == null) {
                continue;
            }
            BuiltInRegistries.BLOCK.getOptional(blockId).ifPresent(block -> map.put(component, block));
        }
        return map;
    }

    /** 变体属性（用于 minecraft:block_state）。 */
    public static CompoundTag variantOf(BlockState state) {
        CompoundTag out = new CompoundTag();
        Property<?> type = findType(state);
        if (type != null) {
            out.putString(TYPE, valueName(state, type));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 默认值 / 清洗
    // ------------------------------------------------------------------

    /** 蓝图没有材质时的确定性默认值（Do 自己的随机材质会让 same-state 判断失效）。 */
    public static CompoundTag defaults(Block block) {
        if (!(block instanceof IMateriallyTexturedBlock textured)) {
            return new CompoundTag();
        }
        CompoundTag out = new CompoundTag();
        for (IMateriallyTexturedBlockComponent component : textured.getComponents()) {
            Block fallback = component.getDefault();
            if (fallback == null) {
                continue;
            }
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(fallback);
            if (key != null) {
                out.putString(component.getId().toString(), key.toString());
            }
        }
        return out;
    }

    /** 得到一份「一定能加载」的 textureData：清洗未知方块，并按方块组件补全缺失项。 */
    public static CompoundTag textureFor(BlockState state, CompoundTag payload) {
        CompoundTag source = payload == null ? new CompoundTag() : payload.getCompound(TEXTURE).copy();
        CompoundTag clean = new CompoundTag();

        Map<String, Block> allowed = new LinkedHashMap<>();
        if (state.getBlock() instanceof IMateriallyTexturedBlock textured) {
            for (IMateriallyTexturedBlockComponent component : textured.getComponents()) {
                allowed.put(component.getId().toString(), component.getDefault());
            }
        }

        for (String key : source.getAllKeys()) {
            ResourceLocation component = ResourceLocation.tryParse(key);
            ResourceLocation blockId = ResourceLocation.tryParse(source.getString(key));
            if (component == null || blockId == null) {
                continue;
            }
            if (!allowed.isEmpty() && !allowed.containsKey(key)) {
                continue;
            }
            if (BuiltInRegistries.BLOCK.containsKey(blockId)) {
                clean.putString(component.toString(), blockId.toString());
            }
        }

        // Do 的 retainComponentsFromBlock 对缺失组件会 NPE；空表又会随机重绘，必须补全。
        for (Map.Entry<String, Block> entry : allowed.entrySet()) {
            if (clean.contains(entry.getKey())) {
                continue;
            }
            Block fallback = entry.getValue();
            if (fallback == null) {
                continue;
            }
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(fallback);
            if (key != null) {
                clean.putString(entry.getKey(), key.toString());
            }
        }

        if (clean.isEmpty()) {
            clean = defaults(state.getBlock());
        }
        return clean;
    }

    // ------------------------------------------------------------------
    // 物品身份
    // ------------------------------------------------------------------

    /** 由蓝图数据构造精确物品：item + minecraft:block_state(type) + domum_ornamentum:texture_data。 */
    public static ItemStack stack(BlockState state, CompoundTag payload) {
        BlockState itemState = applyVariant(state, payload);
        ItemStack stack = new ItemStack(itemState.getBlock().asItem());
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        Property<?> type = findType(itemState);
        if (type != null) {
            BlockItemStateProperties props = with(BlockItemStateProperties.EMPTY, itemState, type);
            if (!props.isEmpty()) {
                stack.set(DataComponents.BLOCK_STATE, props);
            }
        }

        MaterialTextureData data = decode(textureFor(itemState, payload));
        if (!data.isEmpty()) {
            data.writeToItemStack(stack);
        }
        return stack;
    }

    /** 蓝图里的方块是否已经与 payload 描述的一致。 */
    public static boolean matches(BlockEntity entity, CompoundTag payload) {
        if (entity == null) {
            return false;
        }
        if (!(entity instanceof IMateriallyTexturedBlockEntity textured)) {
            return false;
        }

        Map<ResourceLocation, Block> wanted = textureMap(textureFor(entity.getBlockState(), payload));
        if (!wanted.isEmpty()) {
            Map<ResourceLocation, Block> actual = textured.getTextureData().getTexturedComponents();
            if (actual.size() != wanted.size()) {
                return false;
            }
            for (Map.Entry<ResourceLocation, Block> entry : wanted.entrySet()) {
                if (actual.get(entry.getKey()) != entry.getValue()) {
                    return false;
                }
            }
        }

        String wantedType = payload == null ? "" : payload.getCompound(VARIANT).getString(TYPE);
        if (!wantedType.isEmpty()) {
            Property<?> type = findType(entity.getBlockState());
            return type != null && valueName(entity.getBlockState(), type).equals(wantedType);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 属性工具
    // ------------------------------------------------------------------

    public static BlockState applyVariant(BlockState state, CompoundTag payload) {
        if (payload == null) {
            return state;
        }
        String wanted = payload.getCompound(VARIANT).getString(TYPE);
        if (wanted.isEmpty()) {
            return state;
        }
        Property<?> type = findType(state);
        if (type == null) {
            return state;
        }
        return apply(state, type, wanted);
    }

    private static Property<?> findType(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(TYPE)) {
                return property;
            }
        }
        return null;
    }

    private static <T extends Comparable<T>> BlockState apply(BlockState state, Property<T> property, String value) {
        Optional<T> parsed = property.getValue(value);
        return parsed.map(found -> state.setValue(property, found)).orElse(state);
    }

    private static <T extends Comparable<T>> BlockItemStateProperties with(
            BlockItemStateProperties props, BlockState state, Property<T> property) {
        return props.with(property, state.getValue(property));
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
