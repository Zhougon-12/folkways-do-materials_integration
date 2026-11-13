package com.empire.folkways.ornamentum.compat;

import com.empire.folkways.ornamentum.DoPayload;
import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlock;
import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlockComponent;
import com.ldtteam.domumornamentum.entity.block.IMateriallyTexturedBlockEntity;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import materials_integration.MaterialsIntegration;

/**
 * Optional Materials Integration bridge.
 *
 * <p>Do stores its real material in the block entity textureData. Vanilla loot
 * tables cannot see that data, so this event replaces the Do block drop with the
 * matching Materials Integration resource item (planks/log/rock/stone/sand/dirt).
 * The resource can then be sorted by citizens at a sorting workbench.</p>
 */
public final class MaterialsCompat {
    private static final TagKey<Item> PLANKS = tag("planks_integration");
    private static final TagKey<Item> LOG = tag("log_integration");
    private static final TagKey<Item> ROCK = tag("rock_integration");
    private static final TagKey<Item> STONE = tag("stone_integration");
    private static final TagKey<Item> SAND = tag("sand_integration");
    private static final TagKey<Item> DIRT = tag("dirt_integration");

    private MaterialsCompat() {
    }

    private static TagKey<Item> tag(String name) {
        return TagKey.create(Registries.ITEM,
                ResourceLocation.fromNamespaceAndPath("materials_integration", name));
    }

    public static void onDrops(BlockDropsEvent event) {
        BlockEntity entity = event.getBlockEntity();
        if (!(entity instanceof IMateriallyTexturedBlockEntity)) {
            return;
        }
        Optional<ItemStack> resource = resourceFor(entity);
        if (resource.isEmpty()) {
            return;
        }
        BlockEntity broken = event.getBlockEntity();
        ServerLevel level = event.getLevel();
        event.getDrops().clear();
        event.getDrops().add(new ItemEntity(
                level,
                broken.getBlockPos().getX() + 0.5,
                broken.getBlockPos().getY() + 0.5,
                broken.getBlockPos().getZ() + 0.5,
                resource.get()
        ));
    }

    public static Optional<ItemStack> resourceFor(BlockEntity entity) {
        Map<ResourceLocation, Block> textures = DoPayload.textureMap(DoPayload.capture(entity));
        if (textures.isEmpty()) {
            return Optional.empty();
        }
        Block chosen = null;
        if (entity.getBlockState().getBlock() instanceof IMateriallyTexturedBlock textured) {
            IMateriallyTexturedBlockComponent main = textured.getMainComponent();
            if (main != null) {
                chosen = textures.get(main.getId());
            }
        }
        if (chosen == null) {
            chosen = textures.values().iterator().next();
        }
        if (chosen == null) {
            return Optional.empty();
        }
        Item item = chosen.asItem();
        if (item == net.minecraft.world.item.Items.AIR) {
            return Optional.empty();
        }
        ItemStack probe = new ItemStack(item);
        if (probe.is(PLANKS)) {
            return Optional.of(new ItemStack(MaterialsIntegration.PLANKS_INTEGRATION.get()));
        }
        if (probe.is(LOG)) {
            return Optional.of(new ItemStack(MaterialsIntegration.LOG_INTEGRATION.get()));
        }
        if (probe.is(ROCK)) {
            return Optional.of(new ItemStack(MaterialsIntegration.ROCK_INTEGRATION.get()));
        }
        if (probe.is(STONE)) {
            return Optional.of(new ItemStack(MaterialsIntegration.STONE_INTEGRATION.get()));
        }
        if (probe.is(SAND)) {
            return Optional.of(new ItemStack(MaterialsIntegration.SAND_INTEGRATION.get()));
        }
        if (probe.is(DIRT)) {
            return Optional.of(new ItemStack(MaterialsIntegration.DIRT_INTEGRATION.get()));
        }
        return Optional.empty();
    }

    public static boolean present() {
        return BuiltInRegistries.ITEM.containsKey(
                ResourceLocation.fromNamespaceAndPath("materials_integration", "planks_integration"));
    }
}
